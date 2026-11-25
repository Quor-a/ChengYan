//! 澄言 Linux —— Rust 用户态内核。
//!
//! 分层纪律（同 appine）：kernel 不解释指令；engine 不懂进程。
//! 所有 syscall 从 engine 的 svc 陷阱进入这里，由本层对 guest 内存（flat Vec）
//! 完成语义后返回。P1 覆盖：write/exit/exit_group/getpid/getuid 等；后续按
//! P3/P6 计划扩 openat/brk/mmap/fstat 与 VFS store。

pub mod elf;
pub mod syscall;

/// guest 内存：P1 用连续 buffer + 简单 top 分配（P4/P5 换 VMA 表 + 软件页表）。
pub struct GuestMem {
    pub data: Vec<u8>,
}

impl GuestMem {
    pub fn new(size: usize) -> Self {
        Self { data: vec![0; size] }
    }

    pub fn read(&self, addr: u64, buf: &mut [u8]) -> Result<(), String> {
        let a = addr as usize;
        let e = a.checked_add(buf.len()).ok_or("addr overflow")?;
        if e > self.data.len() {
            let pc = ch_engine::interp::CUR_PC.load(std::sync::atomic::Ordering::Relaxed);
            return Err(format!("read out of range {addr:#x}+{} at pc={pc:#x}", buf.len()));
        }
        buf.copy_from_slice(&self.data[a..e]);
        Ok(())
    }

    pub fn write(&mut self, addr: u64, src: &[u8]) -> Result<(), String> {
        let a = addr as usize;
        let e = a.checked_add(src.len()).ok_or("addr overflow")?;
        if e > self.data.len() {
            return Err(format!("write out of range {addr:#x}+{}", src.len()));
        }
        self.data[a..e].copy_from_slice(src);
        Ok(())
    }

    /// 读 NUL 结尾 C 字符串。
    pub fn read_cstr(&self, addr: u64) -> Result<String, String> {
        let a = addr as usize;
        if a >= self.data.len() {
            return Err(format!("bad cstr {addr:#x}"));
        }
        let end = self.data[a..].iter().position(|&b| b == 0).map(|p| a + p).unwrap_or(self.data.len());
        Ok(String::from_utf8_lossy(&self.data[a..end]).into_owned())
    }
}

/// ELF 加载结果。
pub struct LoadedImage {
    pub entry: u64,
    pub stack_top: u64,
    pub brk: u64,
}

/// socket fd（网络直通；Tcp 待 connect 时为 PendingTcp 占位）
pub enum Sock {
    PendingTcp,
    Tcp(std::net::TcpStream),
    Udp(std::net::UdpSocket),
}

/// 最小进程：P2 起 owner 字段扩成 Task（P1 单任务）。
pub struct Process {
    pub mem: GuestMem,
    pub image: LoadedImage,
    pub exit_code: Option<i64>,
    /// mmap bump 分配器游标（MMAP_BASE..MMAP_TOP）
    pub mmap_next: u64,
    pub mmap_base: u64,
    /// 打开的宿主文件描述符表（fd → 文件 + 打开路径）
    pub files: std::collections::BTreeMap<i64, (std::fs::File, String)>,
    /// 目录枚举游标（fd → 已返回条目数）
    pub dir_pos: std::collections::BTreeMap<i64, usize>,
    /// 文件系统越狱防护根（openat 全部落到 root 下；"/" = 不隔离，桌面 runner 默认）
    pub root: std::path::PathBuf,
    /// stdout/stderr 可注入（Android 内置进缓冲，桌面直通终端）
    pub out: Box<dyn std::io::Write + Send>,
    pub err: Box<dyn std::io::Write + Send>,
    /// stdin 注入（AI 可写）：游标式缓冲
    pub stdin: std::io::Cursor<Vec<u8>>,
}

impl Process {
    pub fn new(elf_bytes: &[u8]) -> Result<Self, String> {
        const MEM: u64 = 64 * 1024 * 1024;
        let mut mem = GuestMem::new(MEM as usize);
        let image = elf::load_static(&mut mem, elf_bytes, MEM)?;
        Ok(Self {
            mem, image, exit_code: None,
            mmap_next: syscall::MMAP_BASE, mmap_base: syscall::MMAP_BASE,
            files: std::collections::BTreeMap::new(),
            sockets: std::collections::BTreeMap::new(),
            dir_pos: std::collections::BTreeMap::new(),
            root: std::path::PathBuf::from("/"),
            out: Box::new(std::io::stdout()),
            err: Box::new(std::io::stderr()),
            stdin: std::io::Cursor::new(Vec::new()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mem_roundtrip() {
        let mut m = GuestMem::new(1024);
        m.write(0x100, b"hi\0").unwrap();
        assert_eq!(m.read_cstr(0x100).unwrap(), "hi");
        assert!(m.read(0x1000, &mut [0u8; 4]).is_err());
    }
}
