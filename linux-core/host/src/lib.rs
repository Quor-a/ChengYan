//! 澄言 Linux —— 会话运行器（引擎与内核的宿主侧封装）。
//!
//! runner（桌面 CLI）与 jnihost（Android cdylib）共用：给一段 busybox ELF
//! 字节与 argv，跑完返回（退出码，全部输出）。分层纪律不变：本层只组装
//! 初始栈与 I/O 注入，不解释指令、不做 syscall 语义。

use ch_engine::{interp, Cpu, ExitReason, MemBackend};
use ch_kernel::Process;
use std::sync::{Arc, Mutex};

/// 把 kernel 的 flat guest 内存接成 engine 的 MemBackend（与 runner 同源）。
struct KernelMem<'a>(&'a mut Process);

impl<'a> MemBackend for KernelMem<'a> {
    fn read32(&self, addr: u64) -> Result<u32, String> {
        let mut b = [0u8; 4];
        self.0.mem.read(addr, &mut b)?;
        let v = u32::from_le_bytes(b);
        if std::env::var("CH_TRACE").is_ok() {
            if let Ok(w) = std::env::var("CH_WATCH") {
                let mut it = w.split(':');
                let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                let e = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                if (s..e).contains(&addr) { eprintln!("  [rd32 {addr:#x} -> {v:#x}] pc={:#x}", ch_engine::interp::CUR_PC.load(std::sync::atomic::Ordering::Relaxed)); }
            }
        }
        Ok(v)
    }
    fn read64(&self, addr: u64) -> Result<u64, String> {
        let mut b8 = [0u8; 8];
        if let Err(e) = self.0.mem.read(addr, &mut b8) {
            return Err(format!("{e} pc={:#x}", ch_engine::interp::CUR_PC.load(std::sync::atomic::Ordering::Relaxed)));
        }
        let v = u64::from_le_bytes(b8);
        if std::env::var("CH_TRACE").is_ok() {
            if let Ok(w) = std::env::var("CH_WATCH") {
                let mut it = w.split(':');
                let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                let e = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                if (s..e).contains(&addr) { eprintln!("  [rd64 {addr:#x} -> {v:#x}]"); }
            }
        }
        Ok(v)
    }
    fn write64(&mut self, addr: u64, v: u64) -> Result<(), String> {
        if std::env::var("CH_TRACE").is_ok() {
            if let Ok(w) = std::env::var("CH_WATCH") {
                let mut it = w.split(':');
                let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                let e = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                if (s..e).contains(&addr) { eprintln!("  [wr64 {addr:#x} <- {v:#x}]"); }
            }
        }
        self.0.mem.write(addr, &v.to_le_bytes())
    }
}

/// 共享输出缓冲（会话侧轮询读取）。
pub type SharedOut = Arc<Mutex<Vec<u8>>>;

struct SharedWriter(SharedOut);
impl std::io::Write for SharedWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let mut g = self.0.lock().unwrap();
        if g.len() > 512 * 1024 {
            // 环形截断：保留最新 384KB
            let keep = 384 * 1024;
            let cut = g.len() - keep;
            let cut = cut - cut % 16;
            g.drain(..cut);
            g.extend_from_slice(b"...[truncated]...\n");
        }
        g.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
}

/// 会话：绑一份 busybox（多调用二进制）与 jailed rootfs。
pub struct Session {
    pub busybox_bytes: Vec<u8>,
    pub root: std::path::PathBuf,
}

impl Session {
    pub fn new(busybox_path: &std::path::Path, root: &std::path::Path) -> Result<Self, String> {
        Ok(Self {
            busybox_bytes: std::fs::read(busybox_path).map_err(|e| format!("read busybox: {e}"))?,
            root: root.to_path_buf(),
        })
    }

    /// 合成 /proc：version/meminfo/cpuinfo 写为 jail 内真实文件（guest openat 直通读）。
    /// 幂等：已存在不重写。rootfs.tar 解包会覆盖 proc/ 为空目录，故每次 run 前补。
    fn ensure_proc(&self) {
        let proc_dir = self.root.join("proc");
        let _ = std::fs::create_dir_all(&proc_dir);
        let w = |name: &str, content: String| {
            let f = proc_dir.join(name);
            if !f.exists() { let _ = std::fs::write(f, content); }
        };
        w("version", "Linux version 4.19.0-ch (usermode; no kernel; Alpine 3.20.3 minirootfs)\n".into());
        w("cpuinfo", "processor\t: 0\nHardware\t: ch-engine usermode aarch64\nFeatures\t: fp asimd\n".into());
        w("meminfo", concat!(
"MemTotal:         131072 kB\nMemFree:           98304 kB\nMemAvailable:     114688 kB\nBuffers:            2048 kB\nCached:            14336 kB\nSwapCached:            0 kB\nActive:            20480 kB\nInactive:           8192 kB\nActive(anon):       8192 kB\nInactive(anon):        0 kB\nActive(file):      12288 kB\nInactive(file):     8192 kB\nUnevictable:            0 kB\nMlocked:                0 kB\nSwapTotal:              0 kB\nSwapFree:               0 kB\nDirty:                  0 kB\nWriteback:              0 kB\nAnonPages:          8192 kB\nMapped:             4096 kB\nShmem:                  0 kB\nSlab:               2048 kB\nSReclaimable:        1024 kB\nSUnreclaim:          1024 kB\nKernelStack:          64 kB\nPageTables:           128 kB\nCommitLimit:       65536 kB\nCommitted_AS:      20480 kB\nVmallocTotal: 262143996 kB\nVmallocUsed:            0 kB\nVmallocChunk:           0 kB\nHugePages_Total:       0\nHugePages_Free:        0\nHugePages_Rsvd:        0\nHugePages_Surp:        0\nHugepagesize:       2048 kB\n").into());
    }

    /// 跑一条 argv（argv[0] = busybox 路径，argv[1] = applet，argv[2..] = 参数）。
    /// 返回（退出码，输出字节）。output 为外部共享缓冲时也一并追加。
    pub fn run(&self, argv: &[String], out_shared: &SharedOut) -> Result<i32, String> {
        let mut proc = Process::new(&self.busybox_bytes)?;
        proc.root = self.root.clone();
        proc.out = Box::new(SharedWriter(out_shared.clone()));
        proc.err = Box::new(SharedWriter(out_shared.clone()));

        let mut cpu = Cpu::new();
        cpu.pc = proc.image.entry;
        let tls_base = proc.image.stack_top - 0x2_0000;
        cpu.tpidr = tls_base + 0x8000;

        // 初始栈：argc/argv/NULL/envp NULL/auxv（与 runner 同源，紧凑排布）
        cpu.sp = proc.image.stack_top - 0x1000;
        let mut str_base = cpu.sp + 0x900; // 字符串区放 sp 上方：musl/ash 栈帧向下增长，sp-0x1000 会被深栈踩掉（ash -c 静默空 eval/flush 结构损坏的根因）
        let mut argv_ptrs: Vec<u64> = Vec::new();
        for a in argv {
            let b = a.as_bytes();
            let _ = proc.mem.write(str_base, b);
            let _ = proc.mem.write(str_base + b.len() as u64, &[0u8]);
            argv_ptrs.push(str_base);
            str_base += b.len() as u64 + 1;
        }
        let argc = argv_ptrs.len() as u64;
        let _ = proc.mem.write(cpu.sp, &argc.to_le_bytes());
        for (i, ptr) in argv_ptrs.iter().enumerate() {
            let _ = proc.mem.write(cpu.sp + 8 + 8 * i as u64, &ptr.to_le_bytes());
        }
        let mut off = cpu.sp + 8 + 8 * argv_ptrs.len() as u64;
        // AT_RANDOM 数据放独立区（str_base+0x200），不能叠在 argv 字符串上
        let rand_bytes: [u8; 16] = [0x5a; 16];
        let _ = proc.mem.write(str_base + 0x200, &rand_bytes);
        let _ = proc.mem.write(off, &0u64.to_le_bytes()); off += 8; // argv NULL
        let _ = proc.mem.write(off, &0u64.to_le_bytes()); off += 8; // envp NULL
        // auxv：与 qemu-aarch64 提供集对齐（musl __init_libc 全量扫描 auxv，
        // 缺项会让 bb 的 auxv 拷贝循环提前终结、后续 mallocng 元数据错乱）
        let bytes = &self.busybox_bytes;
        let (e_phoff, e_phentsize, e_phnum) = {
            let phoff = u64::from_le_bytes(bytes[0x20..0x28].to_vec().try_into().unwrap());
            let phent = u16::from_le_bytes(bytes[0x36..0x38].to_vec().try_into().unwrap()) as u64;
            let phnum = u16::from_le_bytes(bytes[0x38..0x3a].to_vec().try_into().unwrap()) as u64;
            (phoff, phent, phnum)
        };
        let load_vaddr = {
            let mut lv = 0u64;
            for k in 0..e_phnum as usize {
                let ph = 0x40 + e_phentsize * k as u64;
                let p = &bytes[ph as usize..(ph + 32) as usize];
                if u32::from_le_bytes(p[0..4].try_into().unwrap()) == 1 {
                    lv = u64::from_le_bytes(p[16..24].try_into().unwrap()); break;
                }
            }
            lv
        };
        let aux: [(u64, u64); 20] = [
            (3, load_vaddr + e_phoff),        // AT_PHDR
            (4, e_phentsize),                 // AT_PHENT
            (5, e_phnum),                     // AT_PHNUM
            (6, 4096),                        // AT_PAGESZ
            (7, 0),                           // AT_BASE
            (8, 0),                           // AT_FLAGS
            (9, proc.image.entry),            // AT_ENTRY
            (11, 0), (12, 0), (13, 0), (14, 0), // uid/euid/gid/egid
            (16, 0xefff_fffb),                // AT_HWCAP
            (17, 100),                        // AT_CLKTCK
            (25, str_base + 0x200),           // AT_RANDOM（16 字节 0x5a @ str_base+0x200）
            (23, 0),                          // AT_SECURE
            (31, str_base + 0x100),           // AT_EXECFN
            (26, 0x1800_7fc7_7fff),           // AT_HWCAP2（与 qemu 对拍一致）
            (15, str_base + 0x40),            // AT_PLATFORM（指向 "aarch64"）
            (33, 5120),                       // AT_MINSIGSTKSZ
            (0, 0),                           // AT_NULL
        ];
        for (k, v) in aux {
            let _ = proc.mem.write(off, &k.to_le_bytes()); off += 8;
            let _ = proc.mem.write(off, &v.to_le_bytes()); off += 8;
        }
        let _ = proc.mem.write(str_base + 0x40, b"aarch64\0"); // AT_PLATFORM 字符串
        let execfn = format!("{}\0", argv.first().map(|s| s.as_str()).unwrap_or("busybox")); // AT_EXECFN=qemu 真值=execve 路径（此处以 argv[0] 近似）
        let _ = proc.mem.write(str_base + 0x100, execfn.as_bytes()); // AT_EXECFN 字符串

        if std::env::var("CH_HOST_DEBUG").is_ok() {
            let mut b = vec![0u8; 0x300];
            if proc.mem.read(cpu.sp - 0x1000, &mut b).is_ok() {
                eprintln!("[dbg] str_base={:#x} sp={:#x} hex={}", str_base, cpu.sp,
                    b.iter().map(|x| format!("{x:02x}")).collect::<String>());
            } else { eprintln!("[dbg] mem.read failed"); }
        }

        let mut steps: u64 = 0;
        let trace = std::env::var("CH_HOST_TRACE").is_ok();
        loop {
            let mut km = KernelMem(&mut proc);
            if trace {
                eprintln!("pc={:#06x} sp={:#x} x0={:#x} x1={:#x} x2={:#x} x3={:#x} x4={:#x} x5={:#x} x6={:#x} x7={:#x} x8={:#x} x19={:#x} x30={:#x}", cpu.pc, cpu.sp, cpu.x[0], cpu.x[1], cpu.x[2], cpu.x[3], cpu.x[4], cpu.x[5], cpu.x[6], cpu.x[7], cpu.x[8], cpu.x[19], cpu.x[30]);
            }
            match interp::step(&mut cpu, &mut km) {
                Ok(None) => {}
                Ok(Some(ExitReason::Syscall)) => {
                    if let Some(code) = ch_kernel::syscall::handle(&mut proc, &mut cpu.x) {
                        return Ok((code & 0xff) as i32);
                    }
                }
                Ok(Some(ExitReason::Undef(i))) => {
                    let mut g = out_shared.lock().unwrap();
                    g.extend_from_slice(format!("\n[ch-undef] instr={i:#010x} at pc={:#x}\n", cpu.pc - 4).as_bytes());
                    return Ok(139);
                }
                Ok(Some(ExitReason::Fault(a))) => {
                    let mut g = out_shared.lock().unwrap();
                    g.extend_from_slice(format!("\n[ch-fault] at {a:#x}\n").as_bytes());
                    return Ok(139);
                }
                Err(e) => {
                    let mut g = out_shared.lock().unwrap();
                    g.extend_from_slice(format!("\n[ch-memerr] {e}\n").as_bytes());
                    return Ok(139);
                }
            }
            steps += 1;
            if steps > 2_000_000_000 {
                let mut g = out_shared.lock().unwrap();
                g.extend_from_slice(b"\n[ch-stuck] step budget exhausted\n");
                return Ok(137);
            }
        }
    }
}

/// 简单 shell 词法：空白切分，支持单引号/双引号包裹（不展开变量，P3 换真 sh）。
pub fn tokenize(cmd: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut q: Option<char> = None;
    for c in cmd.chars() {
        match q {
            Some(qc) => { if c == qc { q = None; } else { cur.push(c); } }
            None => match c {
                '\'' | '"' => q = Some(c),
                c if c.is_whitespace() => { if !cur.is_empty() { out.push(std::mem::take(&mut cur)); } }
                c => cur.push(c),
            },
        }
    }
    if !cur.is_empty() { out.push(cur); }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokenize_quotes() {
        assert_eq!(tokenize("echo 'a b' \"c d\"e plain"), vec!["echo", "a b", "c de", "plain"]);
    }
}
