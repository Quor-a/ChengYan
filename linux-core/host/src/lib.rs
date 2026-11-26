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

/// 管道段/重定向用的**有界**捕获写端。
///
/// 与 `SharedWriter` 的区别：SharedWriter 是终端历史（保留最新 384KB），
/// 而管道下游要的是流的**开头**（`cat big | head -3`）。所以这里保留全部开头字节，
/// 到 `CAP_HARD` 才放弃，并把 `over` 置位让 syscall 层按 SIGPIPE 结束上游。
///
/// 没有这一步时 `yes | head -2` / `cat /dev/zero | head -c 10` 这类命令会在
/// guest 里无限 write：栈上缓冲一路涨到 Android 的 oom-killer 把整个 App 干掉，
/// 或者一路跑到引擎的 step budget（真机上是几分钟的"终端没反应"）。
pub struct CappedWriter {
    buf: SharedOut,
    over: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

/// 管道缓冲硬上限。低于它一律逐字节保留（`cat 20MB | wc -l` 结果仍正确）。
const CAP_HARD: usize = 24 * 1024 * 1024;

impl std::io::Write for CappedWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let mut g = self.buf.lock().unwrap_or_else(|e| e.into_inner());
        let room = CAP_HARD.saturating_sub(g.len());
        if room == 0 {
            drop(g);
            self.over.store(true, std::sync::atomic::Ordering::Relaxed);
            return Ok(buf.len());
        }
        if buf.len() <= room {
            g.extend_from_slice(buf);
            Ok(buf.len())
        } else {
            // 只截数据，**不往数据流里写提示**：这条流可能正接给 `tail -1` / `wc -l`，
            // 多一行提示就会让它们的结果错掉。提示由 shell 在管道结束后写到 stderr。
            g.extend_from_slice(&buf[..room]);
            drop(g);
            self.over.store(true, std::sync::atomic::Ordering::Relaxed);
            Ok(buf.len())
        }
    }
    fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
}

/// 一次执行的产物。
pub struct ExecResult {
    pub code: i32,
    pub out: Vec<u8>,
    pub err: Vec<u8>,
    /// 输出触到 `CAP_HARD`（上游已按 SIGPIPE 结束）。shell 据此给用户一句提示，
    /// 但**不塞进数据流**——否则 `tail`/`wc` 的结果会被提示行带偏。
    pub truncated: bool,
}

/// 解析出来的一次执行：程序字节 + 解释器字节 + 真正的 argv。
struct RunSpec {
    /// 实际装进 arena 的程序字节。
    bytes: Vec<u8>,
    /// 动态链接解释器字节（`PT_INTERP` 指向的文件）。`None` = 静态程序。
    interp: Option<Vec<u8>>,
    /// 交给 guest 的 argv（`argv[0]` 保持用户输入的原样，哪怕实际执行的是
    /// `/bin/ls` —— 与 ash 的 exec 语义一致）。
    argv: Vec<String>,
    /// guest 视角的程序路径（诊断用；空 = 回落到会话自带的 busybox）。
    prog: String,
}

/// 会话：绑一份 busybox（多调用二进制）与 jailed rootfs。
pub struct Session {
    pub busybox_bytes: Vec<u8>,
    /// busybox 在 jail 里的路径（PATH 解析的首选落点）。
    pub busybox_path: String,
    pub root: PathBuf,
    /// guest 视角的当前目录（虚拟绝对路径，如 "/"、"/tmp"）
    pub cwd: Mutex<String>,
    /// 会话环境变量（export 修改；注入 guest 初始栈 envp）
    pub env: Mutex<BTreeMap<String, String>>,
    /// 上一条命令的退出码（$?）
    pub last_status: AtomicI32,
    /// 程序字节缓存：key = 宿主绝对路径，value = (大小, 字节)。
    /// 每条命令都要读一次 ELF（PIE 之后每次都要重新装载），不做缓存的话
    /// busybox 那 1.1MB 每条命令都要从闪存/页缓存里再拷一遍。
    prog_cache: Mutex<BTreeMap<String, (u64, Arc<Vec<u8>>)>>,
    /// 绑定的 PTY 从端（真终端路径）。Some 时 guest 的 0/1/2 走线路规程，
    /// 宿主通过 feed/take_output 驱动；None 时保留旧的注入缓冲执行器语义。
    pub console: Mutex<Option<ch_kernel::console::TtyRef>>,
    pub console_id: std::sync::atomic::AtomicI32,
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
            busybox_path: busybox_path.to_string_lossy().into_owned(),
            root: root.to_path_buf(),
            cwd: Mutex::new("/".to_string()),
            env: Mutex::new(env),
            last_status: AtomicI32::new(0),
            prog_cache: Mutex::new(BTreeMap::new()),
            console: Mutex::new(None),
            console_id: std::sync::atomic::AtomicI32::new(-1),
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
        // /proc/mounts：`df` / `mount` / `apk` 都会读它。缺这个文件时 busybox 的 df
        // 只打一句 "can't find mount point"，用户看到的就是「df 坏了」。
        w("mounts", "/dev/root / rootfs rw,relatime 0 0\nproc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\ntmpfs /dev tmpfs rw,nosuid,relatime 0 0\n".into());
        w("cpuinfo", "processor\t: 0\nHardware\t: ch-engine usermode aarch64\nFeatures\t: fp asimd\n".into());
        w("meminfo", concat!(
"MemTotal:         131072 kB\nMemFree:           98304 kB\nMemAvailable:     114688 kB\nBuffers:            2048 kB\nCached:            14336 kB\nSwapCached:            0 kB\nActive:            20480 kB\nInactive:           8192 kB\nActive(anon):       8192 kB\nInactive(anon):        0 kB\nActive(file):      12288 kB\nInactive(file):     8192 kB\nUnevictable:            0 kB\nMlocked:                0 kB\nSwapTotal:              0 kB\nSwapFree:               0 kB\nDirty:                  0 kB\nWriteback:              0 kB\nAnonPages:          8192 kB\nMapped:             4096 kB\nShmem:                  0 kB\nSlab:               2048 kB\nSReclaimable:        1024 kB\nSUnreclaim:          1024 kB\nKernelStack:          64 kB\nPageTables:           128 kB\nCommitLimit:       65536 kB\nCommitted_AS:      20480 kB\nVmallocTotal: 262143996 kB\nVmallocUsed:            0 kB\nVmallocChunk:           0 kB\nHugePages_Total:       0\nHugePages_Free:        0\nHugePages_Rsvd:        0\nHugePages_Surp:        0\nHugepagesize:       2048 kB\n").into());
    }

    /// 读一个 guest 路径的程序字节（带回大小校验的缓存）。
    fn prog_bytes(&self, host: &std::path::Path) -> Option<Arc<Vec<u8>>> {
        let key = host.to_string_lossy().into_owned();
        let meta = std::fs::metadata(host).ok()?;
        if !meta.is_file() {
            return None;
        }
        let size = meta.len();
        if let Some((sz, b)) = self.prog_cache.lock().ok()?.get(&key) {
            if *sz == size {
                return Some(b.clone());
            }
        }
        // 8MB 上限：真机上一条命令读几十 MB 等于让 UI 卡死，直接判不可执行。
        if size > 8 * 1024 * 1024 {
            return None;
        }
        let b = Arc::new(std::fs::read(host).ok()?);
        let mut g = self.prog_cache.lock().ok()?;
        if g.len() > 8 {
            g.clear();
        }
        g.insert(key, (size, b.clone()));
        Some(b)
    }

    /// 把用户敲的命令解析成「要装哪个程序 + 它的解释器」。
    ///
    /// 这是本轮修掉的**最大一个坑**：以前不管敲什么都只跑会话自带的那一份
    /// busybox（`busybox <命令> <参数>`），于是 rootfs 里 500 多个文件、`/sbin/apk.static`
    /// 以及任何 `apk add` 装进来的程序**全都跑不了**，终端只是个「busybox applet 播放器」。
    ///
    /// 现在的规则与 ash 一致：
    /// 1. `argv[0]` 带 `/` → 按路径（相对 cwd）；
    /// 2. 否则按 `PATH` 逐个目录找可执行文件；
    /// 3. 找到的是 ELF → 装它（有 `PT_INTERP` 就连解释器一起装）；
    /// 4. 找到的是脚本（`#!`）→ 交给 busybox sh 跑；
    /// 5. 一个都没找到 → 回落 busybox applet（老行为，报错信息也保持原样）。
    fn resolve_prog(&self, argv: &[String], cwd: &str) -> RunSpec {
        let name = argv.first().cloned().unwrap_or_default();
        let bb = |argv: Vec<String>| RunSpec {
            bytes: self.busybox_bytes.clone(),
            interp: None,
            argv,
            prog: String::new(),
        };
        if name.is_empty() {
            return bb(argv.to_vec());
        }
        // 候选 guest 路径
        let mut cands: Vec<String> = Vec::new();
        if name.contains('/') {
            cands.push(self.virt_path_at(cwd, &name));
        } else {
            let path = self
                .env
                .lock()
                .ok()
                .and_then(|e| e.get("PATH").cloned())
                .unwrap_or_else(|| "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".into());
            for d in path.split(':').filter(|d| !d.is_empty()) {
                cands.push(format!("{}/{}", d.trim_end_matches('/'), name));
            }
        }
        for cand in cands {
            let dbg = std::env::var("CH_EXEC_DEBUG").is_ok();
            let Some(host) = self.resolve_host(cwd, &cand) else {
                if dbg {
                    eprintln!("[ch-exec] 候选越狱/软链环: {cand}");
                }
                continue;
            };
            let Some(bytes) = self.prog_bytes(&host) else {
                if dbg {
                    eprintln!("[ch-exec] 候选不可执行: {cand} → {}", host.display());
                }
                continue;
            };
            if bytes.len() >= 2 && &bytes[0..2] == b"#!" {
                // 脚本：`busybox sh <脚本> <参数...>`（与内核 shebang 语义等价）
                let mut a: Vec<String> = vec!["sh".into(), cand.clone()];
                a.extend(argv.iter().skip(1).cloned());
                return RunSpec {
                    bytes: self.busybox_bytes.clone(),
                    interp: None,
                    argv: {
                        let mut v = vec!["busybox".to_string()];
                        v.extend(a);
                        v
                    },
                    prog: cand,
                };
            }
            if bytes.len() < 4 || &bytes[0..4] != b"\x7fELF" {
                continue;
            }
            // 动态链接：解释器路径是 guest 绝对路径，必须能在 jail 里读到
            let interp = ch_kernel::elf::interp_path(&bytes).and_then(|ip| {
                let ih = self.resolve_host("/", &ip)?;
                self.prog_bytes(&ih).map(|b| (*b).clone())
            });
            if std::env::var("CH_EXEC_DEBUG").is_ok() {
                eprintln!(
                    "[ch-exec] 命中 {cand} → {}（{} 字节）interp={:?} {}",
                    host.display(),
                    bytes.len(),
                    ch_kernel::elf::interp_path(&bytes),
                    if interp.is_some() { "已载入" } else { "无/缺失" }
                );
            }
            return RunSpec {
                bytes: (*bytes).clone(),
                interp,
                argv: argv.to_vec(),
                prog: cand,
            };
        }
        // 一个候选都没有：回落 busybox applet（`busybox <命令> ...`）
        if std::env::var("CH_EXEC_DEBUG").is_ok() {
            eprintln!("[ch-exec] 未命中任何候选，回落 busybox applet：{name}");
        }
        let mut a = vec!["busybox".to_string()];
        a.extend(argv.iter().cloned());
        bb(a)
    }

    /// 相对 guest 虚拟 cwd 解析绝对虚拟路径（不做软链跟随，交给内核 openat 那套）。
    pub fn virt_path_at(&self, cwd: &str, raw: &str) -> String {
        ch_kernel::path::to_virt(&ch_kernel::path::virt_components(
            &ch_kernel::path::split_abs(cwd),
            raw,
        ))
    }

    /// 虚拟 cwd + 路径 → 宿主路径。
    pub fn host_path_at(&self, cwd: &str, raw: &str) -> PathBuf {
        let comps = ch_kernel::path::virt_components(&ch_kernel::path::split_abs(cwd), raw);
        ch_kernel::path::join_root(&self.root, &comps)
    }

    /// 虚拟 cwd + 路径 → **jail 内跟随过软链的**宿主路径。
    ///
    /// 必须用这个来定位可执行文件，不能直接用 [`Session::host_path_at`]：
    /// Alpine 的 `/bin/ls` 是 `-> /bin/busybox` 这样的**绝对**软链，宿主直接
    /// `metadata()` 会去解析宿主自己根下的 `/bin/busybox`（Android 上不存在、
    /// 桌面上是另一个程序），于是所有 applet 都被判「不可执行」、全部回落到
    /// `busybox <applet>` —— 真机上就是「rootfs 里 500 个文件一个都跑不了」。
    pub fn resolve_host(&self, cwd: &str, raw: &str) -> Option<PathBuf> {
        let comps = ch_kernel::path::virt_components(&ch_kernel::path::split_abs(cwd), raw);
        ch_kernel::path::resolve_following(&self.root, &comps, true)
    }

    /// 内部：跑一条 argv，stdout/stderr 分别落到调用方给的 writer，并注入 stdin。
    fn run_io(
        &self,
        argv: &[String],
        stdin: &[u8],
        cwd: &str,
        out: Box<dyn std::io::Write + Send>,
        err: Box<dyn std::io::Write + Send>,
        cap: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    ) -> Result<i32, String> {
        self.ensure_proc();
        let spec = self.resolve_prog(argv, cwd);
        let mut proc = if let Some(ib) = spec.interp.as_deref() {
            Process::new_ex(&spec.bytes, Some(ib), 0)?
        } else {
            Process::new(&spec.bytes)?
        };
        proc.root = self.root.clone();
        proc.cwd = cwd.to_string();
        proc.prog = spec.prog.clone();
        proc.out = out;
        proc.err = err;
        proc.out_cap = cap;
        // 真终端：把 0/1/2 绑到 PTY 从端；ECHO/ICANON/^C 由线路规程处理。
        if let Some(t) = self.console.lock().unwrap().as_ref() {
            proc.set_tty(t.clone());
        }
        // stdin 注入：管道/here 数据由宿主预置，guest read(0) 从游标读
        proc.stdin = std::io::Cursor::new(stdin.to_vec());

        let mut cpu = Cpu::new();
        cpu.pc = proc.image.entry;
        let tls_base = proc.image.stack_top - 0x2_0000;
        cpu.tpidr = tls_base + 0x8000;

        // 初始栈：argc/argv/NULL/envp/auxv（内核统一组装，含动态链接的 AT_BASE）
        let env: Vec<(String, String)> = self.env_snapshot();
        cpu.sp = ch_kernel::setup_stack(&mut proc, &spec.argv, &env)?;

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
            None,
        )
    }

    /// 跑一条 argv，stdout/stderr 分别捕获（管道段、重定向用）。
    // ---- 真终端（Appine console 协议）----
    // 宿主只做 open / feed / take_output / resize，不碰 guest 内存与 fd。

    pub fn open_console(&self, row: u16, col: u16) -> Result<i32, String> {
        let id = ch_kernel::console::open(ch_kernel::tty::Winsize::new(row, col))?;
        let tty = ch_kernel::console::slave(id)?;
        ch_kernel::console::attach(id)?;
        *self.console.lock().unwrap() = Some(tty);
        self.console_id.store(id as i32, std::sync::atomic::Ordering::Relaxed);
        Ok(id as i32)
    }

    /// 宿主往主端喂字节；返回线路规程识别出的信号号（2=SIGINT 等）。
    pub fn feed(&self, data: &[u8]) -> Result<Vec<i64>, String> {
        let id = self.console_id.load(std::sync::atomic::Ordering::Relaxed);
        if id < 0 { return Err("终端未打开".into()); }
        Ok(ch_kernel::console::feed(id as u32, data)?
            .into_iter().map(|s| s.number()).collect())
    }

    pub fn take_output(&self, max: usize) -> Result<Vec<u8>, String> {
        let id = self.console_id.load(std::sync::atomic::Ordering::Relaxed);
        if id < 0 { return Err("终端未打开".into()); }
        Ok(ch_kernel::console::take_output(id as u32, max)?.unwrap_or_default())
    }

    pub fn resize_console(&self, row: u16, col: u16) -> Result<(), String> {
        let id = self.console_id.load(std::sync::atomic::Ordering::Relaxed);
        if id < 0 { return Err("终端未打开".into()); }
        ch_kernel::console::resize(id as u32, ch_kernel::tty::Winsize::new(row, col))
    }

    pub fn close_console(&self) {
        let id = self.console_id.swap(-1, std::sync::atomic::Ordering::Relaxed);
        if id >= 0 { ch_kernel::console::close(id as u32); }
        *self.console.lock().unwrap() = None;
    }

    pub fn run_capture(&self, argv: &[String], stdin: &[u8], cwd: &str) -> ExecResult {
        let o: SharedOut = Arc::new(Mutex::new(Vec::new()));
        let e: SharedOut = Arc::new(Mutex::new(Vec::new()));
        let over = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let code = self.run_io(
            argv,
            stdin,
            cwd,
            Box::new(CappedWriter { buf: o.clone(), over: over.clone() }),
            Box::new(CappedWriter { buf: e.clone(), over: over.clone() }),
            Some(over.clone()),
        );
        let out = o.lock().unwrap().clone();
        let err = e.lock().unwrap().clone();
        let truncated = over.load(std::sync::atomic::Ordering::Relaxed);
        match code {
            Ok(c) => ExecResult { code: c, out, err, truncated },
            Err(msg) => ExecResult { code: 139, out, err: [err, msg.into_bytes()].concat(), truncated },
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
