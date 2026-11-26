//! 澄言 Linux —— 会话运行器（引擎与内核的宿主侧封装）。
//!
//! runner（桌面 CLI）与 jnihost（Android cdylib）共用：给一段 busybox ELF
//! 字节与 argv，跑完返回（退出码，全部输出）。分层纪律不变：本层只组装
//! 初始栈与 I/O 注入，不解释指令、不做 syscall 语义。
//!
//! 会话状态（cwd / 环境变量 / 上一条退出码）由本层持有：引擎是
//! 「一次一个 applet」的无进程模型，shell 语义（cd、管道、重定向、变量）
//! 由 `shell` 模块在宿主侧编排，guest 每次只是拿到正确的 cwd 与 envp。

pub mod shell;
/// Tier1 原生执行（仅同 ISA：宿主 aarch64）。跨架构宿主没有这一层。
#[cfg(target_arch = "aarch64")]
pub mod native;

use ch_engine::{interp, Cpu, ExitReason, MemBackend};
use ch_kernel::Process;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::atomic::AtomicI32;
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

/// 虚拟绝对路径 → 宿主路径（glob 等无会话状态的场合用；root 本身对应 guest 的 "/"）。
pub fn virt_to_host(root: &std::path::Path, virt: &str) -> PathBuf {
    ch_kernel::path::join_root(root, &ch_kernel::path::split_abs(virt))
}

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

/// 一次执行的产物。
pub struct ExecResult {
    pub code: i32,
    pub out: Vec<u8>,
    pub err: Vec<u8>,
}

/// 会话：绑一份 busybox（多调用二进制）与 jailed rootfs。
pub struct Session {
    pub busybox_bytes: Vec<u8>,
    pub root: PathBuf,
    /// guest 视角的当前目录（虚拟绝对路径，如 "/"、"/tmp"）
    pub cwd: Mutex<String>,
    /// 会话环境变量（export 修改；注入 guest 初始栈 envp）
    pub env: Mutex<BTreeMap<String, String>>,
    /// 上一条命令的退出码（$?）
    pub last_status: AtomicI32,
}

impl Session {
    pub fn new(busybox_path: &std::path::Path, root: &std::path::Path) -> Result<Self, String> {
        // 第一件事：把 guest arena 的宿主地址窗口占住。必须赶在任何大块分配
        // （下面那一行读 busybox 就有 1.1MB）之前——arm64 的 brk 基址是 32MB
        // 随机化的，堆一旦长进 0x400000，恒等映射就间歇性失败、静默退化成 Tier0。
        ch_kernel::reserve_guest_window();
        let mut env = BTreeMap::new();
        // 默认环境：Alpine rootfs 的常规值。HOME=/root 与 passwd 一致。
        env.insert("PATH".into(), "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".into());
        env.insert("HOME".into(), "/root".into());
        env.insert("PWD".into(), "/".into());
        env.insert("SHELL".into(), "/bin/sh".into());
        env.insert("TERM".into(), "xterm-256color".into());
        env.insert("LANG".into(), "C.UTF-8".into());
        env.insert("USER".into(), "root".into());
        env.insert("LOGNAME".into(), "root".into());
        env.insert("HOSTNAME".into(), "chengyan".into());
        Ok(Self {
            busybox_bytes: std::fs::read(busybox_path).map_err(|e| format!("read busybox: {e}"))?,
            root: root.to_path_buf(),
            cwd: Mutex::new("/".to_string()),
            env: Mutex::new(env),
            last_status: AtomicI32::new(0),
        })
    }

    /// 当前工作目录（虚拟绝对路径）。
    pub fn cwd(&self) -> String { self.cwd.lock().unwrap().clone() }

    /// 设置工作目录（shell 的 cd 用；调用方已校验目录存在）。
    pub fn set_cwd(&self, v: String) {
        self.cwd.lock().unwrap().clone_from(&v);
        self.env.lock().unwrap().insert("PWD".into(), v);
    }

    pub fn env_snapshot(&self) -> Vec<(String, String)> {
        self.env.lock().unwrap().iter().map(|(k, v)| (k.clone(), v.clone())).collect()
    }

    /// 虚拟/相对路径 → 宿主路径（重定向文件、cd 校验用）。
    /// 与 kernel 的解析同源规则：相对 cwd、`.`/`..` 规范化、不出 jail。
    // 注意：cwd 必须作为 virt_components 的**起始组件**传进去，不能自己先 extend。
    // 旧写法 `comps.extend(cwd); comps.extend(virt_components(&[], raw))` 里
    // virt_components 拿到的 cwd 是空的，`..` 的 pop 作用在空数组上 = 白弹，
    // 于是 `cd ..` 永远原地不动（真机表现就是"cd 上跳没反应"）。
    pub fn host_path(&self, raw: &str) -> PathBuf {
        let comps = ch_kernel::path::virt_components(&ch_kernel::path::split_abs(&self.cwd()), raw);
        ch_kernel::path::join_root(&self.root, &comps)
    }

    /// 虚拟绝对路径（shell 的 cd 用）。
    pub fn virt_path(&self, raw: &str) -> String {
        ch_kernel::path::to_virt(&ch_kernel::path::virt_components(
            &ch_kernel::path::split_abs(&self.cwd()),
            raw,
        ))
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

    /// 内部：跑一条 argv，stdout/stderr 分别落到调用方给的 writer，并注入 stdin。
    fn run_io(
        &self,
        argv: &[String],
        stdin: &[u8],
        cwd: &str,
        out: Box<dyn std::io::Write + Send>,
        err: Box<dyn std::io::Write + Send>,
    ) -> Result<i32, String> {
        self.ensure_proc();
        let mut proc = Process::new(&self.busybox_bytes)?;
        proc.root = self.root.clone();
        proc.cwd = cwd.to_string();
        proc.out = out;
        proc.err = err;
        // stdin 注入：管道/here 数据由宿主预置，guest read(0) 从游标读
        proc.stdin = std::io::Cursor::new(stdin.to_vec());

        let mut cpu = Cpu::new();
        cpu.pc = proc.image.entry;
        let tls_base = proc.image.stack_top - 0x2_0000;
        cpu.tpidr = tls_base + 0x8000;

        // 初始栈：argc/argv/NULL/envp/auxv（与 runner 同源，紧凑排布）
        cpu.sp = proc.image.stack_top - 0x1000;
        let mut str_base = cpu.sp + 0x900; // 字符串区放 sp 上方：musl/ash 栈帧向下增长，sp-0x1000 会被深栈踩掉
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

        // envp：必须在 argv 的 NULL 之后、auxv 之前。缺 envp 会让 getenv("PATH") 全空，
        // busybox 找不到 applet、脚本解释器与 locale 全部退化。
        let mut env_ptrs: Vec<u64> = Vec::new();
        for (k, v) in self.env_snapshot() {
            let s = format!("{k}={v}");
            let b = s.as_bytes();
            let _ = proc.mem.write(str_base, b);
            let _ = proc.mem.write(str_base + b.len() as u64, &[0u8]);
            env_ptrs.push(str_base);
            str_base += b.len() as u64 + 1;
        }

        let mut off = cpu.sp + 8 + 8 * argv_ptrs.len() as u64;
        let rand_bytes: [u8; 16] = [0x5a; 16];
        let _ = proc.mem.write(str_base + 0x200, &rand_bytes);
        let _ = proc.mem.write(off, &0u64.to_le_bytes()); off += 8; // argv NULL
        for ptr in &env_ptrs {
            let _ = proc.mem.write(off, &ptr.to_le_bytes()); off += 8;
        }
        let _ = proc.mem.write(off, &0u64.to_le_bytes()); off += 8; // envp NULL

        // auxv：与 qemu-aarch64 提供集对齐（musl __init_libc 全量扫描）
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
        // 注意：33 = AT_SYSINFO_EHDR（vDSO 基址），不是 AT_MINSIGSTKSZ(=51)。
        // 旧版把 33 填成 5120(0x1400)，musl 的 __vdsosym 会把 0x1400 当 ELF 头解析、
        // 符号表全是垃圾 → 野指针 / mallocng 元数据崩坏（runner 侧已踩过，host 侧漏修）。
        // AT_MINSIGSTKSZ(51) 填 5120 才是正确项。
        let aux: [(u64, u64); 21] = [
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
            (25, str_base + 0x200),           // AT_RANDOM（16 字节 0x5a）
            (23, 0),                          // AT_SECURE
            (31, str_base + 0x100),           // AT_EXECFN
            (26, 0x1800_7fc7_7fff),           // AT_HWCAP2
            (15, str_base + 0x40),            // AT_PLATFORM
            (33, 0),                          // AT_SYSINFO_EHDR：无 vDSO，必须 0
            (51, 5120),                       // AT_MINSIGSTKSZ
            (0, 0),                           // AT_NULL
        ];
        for (k, v) in aux {
            let _ = proc.mem.write(off, &k.to_le_bytes()); off += 8;
            let _ = proc.mem.write(off, &v.to_le_bytes()); off += 8;
        }
        let _ = proc.mem.write(str_base + 0x40, b"aarch64\0"); // AT_PLATFORM
        let execfn = format!("{}\0", argv.first().map(|s| s.as_str()).unwrap_or("busybox"));
        let _ = proc.mem.write(str_base + 0x100, execfn.as_bytes()); // AT_EXECFN

        // ---- Tier1：把 guest 代码交给真 CPU ----
        // 宿主与 guest 同为 aarch64、且内存是恒等映射时，解释器没有必要存在：
        // 装载完直接原生跑，只有 svc 陷回来进内核。Tier0 退化成跨架构/兜底通道。
        //
        // 这里返回 Err 一定意味着「一条 guest 指令都还没执行」，所以回落安全。
        #[cfg(target_arch = "aarch64")]
        if native::available(&proc) {
            match native::run(&mut proc, cpu.sp) {
                Ok(c) => return Ok(c),
                Err(e) => {
                    if std::env::var("CH_NATIVE_DEBUG").is_ok() {
                        eprintln!("[ch-native] 回落 Tier0：{e}");
                    }
                }
            }
        }

        let mut steps: u64 = 0;
        let trace = std::env::var("CH_HOST_TRACE").is_ok();
        // 崩溃现场取证：记最近 24 条 (pc, instr)，出错时连同寄存器一起打出来
        let mut hist: std::collections::VecDeque<(u64, u32)> = std::collections::VecDeque::new();
        loop {
            let mut iw = [0u8; 4];
            if proc.mem.read(cpu.pc, &mut iw).is_ok() {
                let w = u32::from_le_bytes(iw);
                if hist.len() >= 24 { hist.pop_front(); }
                hist.push_back((cpu.pc, w));
            }
            let mut km = KernelMem(&mut proc);
            if trace {
                eprintln!("pc={:#06x} sp={:#x} x0={:#x} x1={:#x} x8={:#x}", cpu.pc, cpu.sp, cpu.x[0], cpu.x[1], cpu.x[8]);
            }
            match interp::step(&mut cpu, &mut km) {
                Ok(None) => {}
                Ok(Some(ExitReason::Syscall)) => {
                    if let Some(code) = ch_kernel::syscall::handle(&mut proc, &mut cpu.x) {
                        return Ok((code & 0xff) as i32);
                    }
                }
                Ok(Some(ExitReason::Undef(i))) => {
                    return Err(format!("[ch-undef] instr={i:#010x} at pc={:#x}", cpu.pc - 4));
                }
                Ok(Some(ExitReason::Fault(a))) => {
                    return Err(format!("[ch-fault] addr={a:#x} pc={:#x}", cpu.pc - 4));
                }
                Err(e) => {
                    let mut d = format!("[ch-memerr] {e}
  pc={:#x} sp={:#x}
", cpu.pc.wrapping_sub(4), cpu.sp);
                    for i in 0..31 {
                        d.push_str(&format!("  x{:<2}={:#018x}", i, cpu.x[i]));
                        if i % 3 == 2 { d.push_str("\n"); }
                    }
                    d.push_str("  --- last pcs ---
  ");
                    let tail: Vec<String> = hist.iter().map(|(pc, w)| format!("{pc:#x}:{w:08x}")).collect();
                    d.push_str(&tail.join(" "));
                    return Err(d);
                }
            }
            steps += 1;
            if steps > 2_000_000_000 {
                return Err("[ch-stuck] step budget exhausted".into());
            }
        }
    }

    /// 跑一条 argv，输出合并进共享缓冲（终端/AI 工具的历史接口）。
    pub fn run(&self, argv: &[String], out_shared: &SharedOut) -> Result<i32, String> {
        let cwd = self.cwd();
        self.run_io(
            argv,
            b"",
            &cwd,
            Box::new(SharedWriter(out_shared.clone())),
            Box::new(SharedWriter(out_shared.clone())),
        )
    }

    /// 跑一条 argv，stdout/stderr 分别捕获（管道段、重定向用）。
    pub fn run_capture(&self, argv: &[String], stdin: &[u8], cwd: &str) -> ExecResult {
        let o: SharedOut = Arc::new(Mutex::new(Vec::new()));
        let e: SharedOut = Arc::new(Mutex::new(Vec::new()));
        let code = self.run_io(
            argv,
            stdin,
            cwd,
            Box::new(SharedWriter(o.clone())),
            Box::new(SharedWriter(e.clone())),
        );
        let out = o.lock().unwrap().clone();
        let err = e.lock().unwrap().clone();
        match code {
            Ok(c) => ExecResult { code: c, out, err },
            Err(msg) => ExecResult { code: 139, out, err: [err, msg.into_bytes()].concat() },
        }
    }

    /// 执行一行命令（含 shell 语法），输出合并进共享缓冲。
    pub fn run_line(&self, line: &str, out_shared: &SharedOut) -> i32 {
        shell::run_line(self, line, out_shared)
    }
}

/// 简单 shell 词法：空白切分，支持单引号/双引号包裹（不展开变量）。
/// 保留给需要「纯 argv」的调用方；终端与工具走 `shell::run_line`。
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

    #[test]
    #[ignore] // 依赖 /tmp/busybox 与 /tmp/jail（桌面沙盒真值验证用）
    fn session_run_real_busybox() {
        let s = Session::new(std::path::Path::new("/tmp/busybox"), std::path::Path::new("/tmp/jail")).unwrap();
        let out: SharedOut = Arc::new(Mutex::new(Vec::new()));
        let code = s.run(&["busybox".into(), "cat".into(), "/etc/os-release".into()], &out).unwrap();
        let txt = String::from_utf8_lossy(&out.lock().unwrap()).into_owned();
        assert_eq!(code, 0);
        assert!(txt.contains("PRETTY_NAME"), "got: {txt}");
    }
}
