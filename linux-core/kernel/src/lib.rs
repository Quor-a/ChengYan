//! 澄言 Linux —— Rust 用户态内核。
//!
//! 分层纪律（同 appine）：kernel 不解释指令；engine 不懂进程。
//! 所有 syscall 从 engine 的 svc 陷阱进入这里，由本层对 guest 内存
//! 完成语义后返回。
//!
//! ## 内存后端的两种形态
//!
//! `guest 地址` 在旧实现里直接就是 `Vec<u8>` 的下标，好处是简单，代价是
//! **guest 指针不是宿主指针**：任何「把 guest 代码交给真 CPU 执行」的方案
//! 都要先把这一层换掉（appine 的软 MMU 就是同一件事）。
//!
//! 本层现在同时支持两种 backing：
//!
//! * [`Backing::Flat`]  —— 连续 buffer，guest 地址 == 下标。解释器用，跨架构可用。
//! * [`Backing::Ident`] —— 把一段**固定地址**的匿名内存 mmap 出来，guest 虚拟地址
//!   就等于宿主虚拟地址。这是 Tier1「同 ISA 原生执行」的前提：guest 代码里的
//!   每一条 `ldr/str` 直接命中真实内存，不需要任何地址翻译。
//!
//! 两者共用同一套 `read/write/read_cstr`，所以 syscall 层、ELF 加载、
//! 初始栈组装、解释器全部零改动。

pub mod elf;
pub mod path;
pub mod syscall;

// ---- 宿主导出（bionic / musl 同名同签名，无需 libc crate）----
const PROT_READ: i32 = 1;
const PROT_WRITE: i32 = 2;
const PROT_EXEC: i32 = 4;

const MAP_PRIVATE: i32 = 0x02;
const MAP_ANONYMOUS: i32 = 0x20;
const MAP_FIXED: i32 = 0x10;
const MAP_NORESERVE: i32 = 0x4000;
/// 4.17+：地址被占用时报 EEXIST 而不是把别人的映射拆掉。
const MAP_FIXED_NOREPLACE: i32 = 0x10_0000;

/// guest arena 的宿主地址窗口 `[0x400000, MEM_SIZE)` —— 低位、可从 ELF 推出，
/// 也正是各种 ET_EXEC guest（busybox 就是 0x400000）的落点。
const WINDOW_BASE: u64 = 0x0040_0000;
const WINDOW_LEN: u64 = MEM_SIZE - WINDOW_BASE;

/// 窗口是否已由本进程预留成功。
static WINDOW_OK: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static WINDOW_ONCE: std::sync::Once = std::sync::Once::new();

/// **必须尽早调用**：把 guest arena 的宿主地址窗口整段占住（PROT_NONE + NORESERVE）。
///
/// 为什么非做不可：arm64 内核的 `arch_randomize_brk()` 会把静态 ET_EXEC 的堆基址
/// 在 **32MB** 范围内随机化。随机值落在 0x400000 以下时，宿主堆（busybox 1.1MB、
/// shell 会话表等）一增长就压进 0x400000，`MAP_FIXED_NOREPLACE` 抢不到 ⇒
/// 恒等映射失败 ⇒ 整条链路无声回落到 Tier0。实测约 4~8% 的进程会中招，
/// 表现为「同一台机器、同一条命令，偶尔变慢甚至崩」。
///
/// 预留成功之后堆就再也进不来（brk 无法跨过已有映射，malloc 会转走 mmap），
/// 恒等映射变成确定性成功。预留失败（地址已被真实占用）时静默降级，
/// 绝不抢占别人的映射。
pub fn reserve_guest_window() {
    WINDOW_ONCE.call_once(|| {
        let p = unsafe {
            mmap(
                WINDOW_BASE as *mut u8,
                WINDOW_LEN as usize,
                PROT_NONE,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE | MAP_NORESERVE,
                -1,
                0,
            )
        };
        let ok = !map_failed(p) && p as u64 == WINDOW_BASE;
        if !ok && !map_failed(p) {
            unsafe { munmap(p, WINDOW_LEN as usize) };
        }
        if !ok && std::env::var("CH_IDENTITY_DEBUG").is_ok() {
            let e = std::io::Error::last_os_error();
            eprintln!("[ch-mem] 窗口预留失败 {WINDOW_BASE:#x}+{WINDOW_LEN:#x}: {e}");
            if let Ok(maps) = std::fs::read_to_string("/proc/self/maps") {
                for l in maps.lines() {
                    let mut it = l.split(|c| c == '-' || c == ' ');
                    if let (Some(s), Some(e2)) = (it.next(), it.next()) {
                        if let (Ok(a), Ok(b)) = (u64::from_str_radix(s, 16), u64::from_str_radix(e2, 16)) {
                            if a < WINDOW_BASE + WINDOW_LEN && b > WINDOW_BASE {
                                eprintln!("[ch-mem]   冲突: {l}");
                            }
                        }
                    }
                }
            }
        }
        WINDOW_OK.store(ok, std::sync::atomic::Ordering::Release);
        if std::env::var("CH_IDENTITY_DEBUG").is_ok() {
            eprintln!("[ch-mem] 窗口预留 ok={ok}（构造函数/首次调用时点）");
        }
    });
}

/// 构造函数版预留：**在任何 malloc/堆增长之前**跑。
///
/// arm64 内核把静态 ET_EXEC 的 `start_brk` 在 32MB 内随机化，一旦堆先长进
/// 0x400000，`Session::new` 里再预留就拿不到地址了（实测仍有 ~4% 失败）。
/// `.init_array` 在 main 之前、且此时堆还没被用过，成功率接近 100%。
extern "C" fn reserve_at_load() {
    reserve_guest_window();
}

/// **只给静态可执行文件（桌面 runner / 交叉编译的 shell）用。**
///
/// Android 上绝对不能挂 `.init_array`：那是 `dlopen` 期间执行的，此时 bionic
/// 的 linker 还持着锁、Rust std 的 TLS/stdio/环境变量访问都不可靠。实测
/// v1.9.7 加了这条之后 `System.loadLibrary("chlinux")` 直接失败 ——
/// `LinuxNative` 的 `<clinit>` 抛异常 → 类被标记 erroneous → 之后每次访问都是
/// `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`（终端报「会话启动失败」）。
/// 对比历史产物可证：v1.9.6 的 so 没有 DT_INIT_ARRAY，v1.9.7 有 1 项。
///
/// Android 改走 `JNI_OnLoad`（见 jnihost），由 JVM 在 dlopen 成功返回后调用，
/// 时机同样早于任何 native 调用，且环境完全就绪。
#[used]
#[cfg(target_os = "linux")]
#[link_section = ".init_array"]
static RESERVE_AT_LOAD: extern "C" fn() = reserve_at_load;

pub fn window_reserved() -> bool {
    WINDOW_OK.load(std::sync::atomic::Ordering::Acquire)
}

const PROT_NONE: i32 = 0;

extern "C" {
    fn mmap(addr: *mut u8, len: usize, prot: i32, flags: i32, fd: i32, off: i64) -> *mut u8;
    fn munmap(addr: *mut u8, len: usize) -> i32;
    fn mprotect(addr: *mut u8, len: usize, prot: i32) -> i32;
}

fn map_failed(p: *mut u8) -> bool { p.is_null() || p as isize == -1 }

/// guest 内存 backing。
enum Backing {
    /// 连续 buffer：guest 地址 == 数组下标（解释器 / 跨架构）。
    Flat(Vec<u8>),
    /// 恒等映射：guest 虚拟地址 == 宿主虚拟地址（Tier1 原生执行）。
    Ident { base: u64, len: u64 },
}

/// guest 内存：解释器与原生执行共用同一个读写接口。
pub struct GuestMem {
    backing: Backing,
}

impl GuestMem {
    /// flat 后端（原实现；跨架构可用，代价是 64MB 常驻 + 一次 memcpy 语义）。
    pub fn new(size: usize) -> Self {
        Self { backing: Backing::Flat(vec![0; size]) }
    }

    /// 恒等后端：在 `base` 处 mmap 一段 `len` 字节，guest 地址即宿主地址。
    ///
    /// 用 `MAP_FIXED_NOREPLACE`：地址被占用时**不会**拆掉既有映射（普通
    /// `MAP_FIXED` 会），而是返回 EEXIST，让调用方回落到 flat。抢占式地
    /// 覆盖 0x400000 这种低地址在 app 进程里是能直接把进程干掉的。
    pub fn identity(base: u64, len: u64) -> Result<Self, String> {
        if base < 0x1_0000 || len == 0 {
            return Err(format!("坏 arena {base:#x}+{len:#x}"));
        }
        // 按 64KB 向上取整：ARM64 段对齐常是 0x10000，避免末页漏保护。
        let len = (len + 0xFFFF) & !0xFFFFu64;
        // 落在我们自己预留的窗口里时，用普通 MAP_FIXED 覆盖掉那层 PROT_NONE。
        // 只有当窗口是**我们预留成功**的才这么干——否则仍走 NOREPLACE，绝不抢占别人。
        let ours = WINDOW_OK.load(std::sync::atomic::Ordering::Acquire)
            && base >= WINDOW_BASE
            && base.checked_add(len).map(|e| e <= WINDOW_BASE + WINDOW_LEN).unwrap_or(false);
        let flags = if ours {
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED
        } else {
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE
        };
        let p = unsafe {
            mmap(
                base as *mut u8,
                len as usize,
                PROT_READ | PROT_WRITE,
                flags,
                -1,
                0,
            )
        };
        if map_failed(p) {
            // 老内核（<4.17）不认 MAP_FIXED_NOREPLACE，会当成 hint 返回别处地址，
            // 这里统一按「拿不到该地址」处理 —— 不赌，直接回落。
            let e = std::io::Error::last_os_error();
            return Err(format!("mmap {base:#x}+{len:#x} 失败/不可用: {e}"));
        }
        if p as u64 != base {
            unsafe { munmap(p, len as usize) };
            return Err(format!("mmap 未落在 {base:#x}（得到 {p:p}）"));
        }
        Ok(Self { backing: Backing::Ident { base, len } })
    }

    pub fn is_identity(&self) -> bool { matches!(self.backing, Backing::Ident { .. }) }

    ///  arena 覆盖区间 `[lo, hi)`。flat 后端即 `[0, len)`。
    pub fn span(&self) -> (u64, u64) {
        match &self.backing {
            Backing::Flat(v) => (0, v.len() as u64),
            Backing::Ident { base, len } => (*base, base + len),
        }
    }

    /// guest 地址 → 宿主指针（仅 Ident 有意义；Flat 也给出 buffer 内地址，
    /// 供「把 guest 代码拷进可执行内存」之类的一次性用途）。
    pub fn host_ptr(&self, addr: u64, n: usize) -> Result<*const u8, String> {
        let i = self.idx(addr, n)?;
        Ok(i as *const u8)
    }

    pub fn host_ptr_mut(&self, addr: u64, n: usize) -> Result<*mut u8, String> {
        let i = self.idx(addr, n)?;
        Ok(i as *mut u8)
    }

    /// 改一页/一段的权限（原生执行装载完段之后按段设 R-X / RW-）。
    pub fn protect(&self, addr: u64, len: u64, prot: i32) -> Result<(), String> {
        let (lo, hi) = self.span();
        if addr < lo || addr.checked_add(len).map(|e| e > hi).unwrap_or(true) {
            return Err(format!("protect {addr:#x}+{len:#x} 出界"));
        }
        let p = unsafe { mprotect(addr as *mut u8, len as usize, prot) };
        if p != 0 { return Err(format!("mprotect {addr:#x}+{len:#x} 失败")); }
        Ok(())
    }

    /// 校验 `[addr, addr+n)` 并返回宿主侧可用作内存基址的 `usize`：
    /// Flat 返回下标，Ident 返回地址本身（两者在各自 backing 内等价）。
    fn idx(&self, addr: u64, n: usize) -> Result<usize, String> {
        match &self.backing {
            Backing::Flat(v) => {
                let a = addr as usize;
                let e = a.checked_add(n).ok_or("addr overflow")?;
                if e > v.len() {
                    let pc = ch_engine::interp::CUR_PC.load(std::sync::atomic::Ordering::Relaxed);
                    return Err(format!("read out of range {addr:#x}+{n} at pc={pc:#x}"));
                }
                Ok(a)
            }
            Backing::Ident { base, len } => {
                let end = addr.checked_add(n as u64).ok_or("addr overflow")?;
                if addr < *base || end > base + len {
                    let pc = ch_engine::interp::CUR_PC.load(std::sync::atomic::Ordering::Relaxed);
                    return Err(format!(
                        "read out of range {addr:#x}+{n}（arena {base:#x}..{:#x}）at pc={pc:#x}",
                        base + len
                    ));
                }
                Ok(addr as usize)
            }
        }
    }

    pub fn read(&self, addr: u64, buf: &mut [u8]) -> Result<(), String> {
        // 零长度读恒成功，与 Linux 一致。这一条对 writev/readv 是硬需求：
        // musl 的 __stdio_write 会在 iovec 末尾挂一个 {NULL,0}，若拿 base=0 去做
        // 边界校验，arena 外的地址会被判成非法 ⇒ 整条 writev 返回 EBADF，
        // 于是 busybox 报「standard output: Bad file descriptor」。
        if buf.is_empty() {
            return Ok(());
        }
        let i = self.idx(addr, buf.len())?;
        match &self.backing {
            Backing::Flat(v) => buf.copy_from_slice(&v[i..i + buf.len()]),
            Backing::Ident { .. } => unsafe {
                std::ptr::copy_nonoverlapping(i as *const u8, buf.as_mut_ptr(), buf.len())
            },
        }
        Ok(())
    }

    pub fn write(&mut self, addr: u64, src: &[u8]) -> Result<(), String> {
        if src.is_empty() {
            return Ok(());
        }
        let i = self.idx(addr, src.len())?;
        match &mut self.backing {
            Backing::Flat(v) => v[i..i + src.len()].copy_from_slice(src),
            Backing::Ident { .. } => unsafe {
                std::ptr::copy_nonoverlapping(src.as_ptr(), i as *mut u8, src.len())
            },
        }
        Ok(())
    }

    /// 读 NUL 结尾 C 字符串。
    pub fn read_cstr(&self, addr: u64) -> Result<String, String> {
        let i = self.idx(addr, 1)?;
        let (_lo, hi) = self.span();
        match &self.backing {
            Backing::Flat(v) => {
                let end = v[i..].iter().position(|&b| b == 0).map(|p| i + p).unwrap_or(v.len());
                Ok(String::from_utf8_lossy(&v[i..end]).into_owned())
            }
            Backing::Ident { .. } => {
                let start = i as u64;
                let mut end = start;
                while end < hi {
                    if unsafe { *(end as *const u8) } == 0 { break; }
                    end += 1;
                }
                let n = (end - start) as usize;
                let sl = unsafe { std::slice::from_raw_parts(start as *const u8, n) };
                Ok(String::from_utf8_lossy(sl).into_owned())
            }
        }
    }
}

impl Drop for GuestMem {
    fn drop(&mut self) {
        if let Backing::Ident { base, len } = self.backing {
            if window_reserved() {
                // 预留窗口是自己占的：**只降权限、不还地址**，把空间继续攥在
                // 手里。否则下一个 guest 用 MAP_FIXED 覆盖时会踩掉期间被
                // malloc 塞进来的宿主数据。
                unsafe { mprotect(base as *mut u8, len as usize, PROT_NONE) };
            } else {
                unsafe { munmap(base as *mut u8, len as usize) };
            }
        }
    }
}

/// ELF 加载结果。
pub struct LoadedImage {
    pub entry: u64,
    pub stack_top: u64,
    pub brk: u64,
    /// 可执行段整体区间 `[lo, hi)`。Tier1 要在其中把 `svc #0` 改写成陷门，
    /// 所以必须精确知道哪些字节真的是代码（不能全 arena 扫，会误伤数据）。
    pub text: (u64, u64),
    /// 最低可写段起点（0 = 没有可写段）。R-X 保护区的上界会被它夹住。
    pub wseg: u64,
}

/// socket fd（网络直通；Tcp 待 connect 时为 PendingTcp 占位）
pub enum Sock {
    PendingTcp,
    Tcp(std::net::TcpStream),
    Udp(std::net::UdpSocket),
}

/// 管道缓冲区（pipe2 用；读写端共享同一份）。
/// 注意：宿主侧 shell 的管道是在 Rust 层用内存直接串接的（见 host::shell），
/// guest 内部 pipe2 目前只做占位——真机上没有 fork，guest 不会自建管道。
pub struct PipeBuf {
    pub buf: Vec<u8>,
    pub writers: usize,
}

/// 管道一端（fd → 端）。
pub struct PipeEnd {
    pub is_write: bool,
    pub shared: std::sync::Arc<std::sync::Mutex<PipeBuf>>,
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
    /// socket 表（网络直通）
    pub sockets: std::collections::BTreeMap<i64, Sock>,
    /// 管道端表（pipe2；宿主 shell 不走这里，见 PipeBuf 注释）
    pub pipes: std::collections::BTreeMap<i64, PipeEnd>,
    /// 伪 fd 占位（/dev/null、/dev/zero 等由 openat 直通宿主文件，无需实体）
    pub pseudo: std::collections::BTreeMap<i64, ()>,
    /// 目录枚举游标（fd → 已返回条目数）
    pub dir_pos: std::collections::BTreeMap<i64, usize>,
    /// dup/dup3/fcntl(F_DUPFD) 产生的别名表：新 fd → 源 fd。
    /// 语义上等价于两个 fd 指向同一个打开文件描述；这里只记指向关系，
    /// 读写时用 `real_fd()` 回溯到源 fd 再走既有的 files/pipes/sockets 表。
    pub dup_alias: std::collections::BTreeMap<i64, i64>,
    /// 文件系统越狱防护根（openat 全部落到 root 下；"/" = 不隔离，桌面 runner 默认）
    pub root: std::path::PathBuf,
    /// 当前工作目录（**虚拟**绝对路径，guest 视角，如 "/"、"/tmp"）。
    /// 旧实现没有这个字段，所有相对路径都直接拼 root ⇒ cwd 永远是 "/"，
    /// 于是 `cd` 无效、`touch a` 永远落在 jail 根。chdir/getcwd 靠它。
    pub cwd: String,
    /// stdout/stderr 可注入（Android 内置进缓冲，桌面直通终端）
    pub out: Box<dyn std::io::Write + Send>,
    pub err: Box<dyn std::io::Write + Send>,
    /// stdin 注入（AI 可写）：游标式缓冲
    pub stdin: std::io::Cursor<Vec<u8>>,
}

/// guest 内存总大小（guest 视角的地址上限）。
pub const MEM_SIZE: u64 = 64 * 1024 * 1024;

/// 是否尝试恒等映射。
///
/// 只有宿主与 guest 同为 aarch64 时才有意义（否则 guest 指令根本跑不了，
/// 拿恒等映射白搭）；解释器在 aarch64 上也用恒等——省掉 64MB 常驻 buffer，
/// 而且和原生通道共用同一条内存路径，少一条没人测的分支。
fn want_identity() -> bool {
    if std::env::var("CH_TIER").map(|v| v.trim() == "0").unwrap_or(false) {
        return false;
    }
    cfg!(target_arch = "aarch64") || std::env::var("CH_IDENTITY").is_ok()
}

impl Process {
    /// guest 路径 → 宿主路径（跟随末段软链）。失败 = 越狱或软链环。
    pub fn resolve(&self, raw: &str) -> Option<std::path::PathBuf> {
        self.resolve_ex(raw, true)
    }

    /// lstat 语义：末段软链不跟随（readlink / lstat / unlink 用）。
    pub fn resolve_nofollow(&self, raw: &str) -> Option<std::path::PathBuf> {
        self.resolve_ex(raw, false)
    }

    fn resolve_ex(&self, raw: &str, follow_last: bool) -> Option<std::path::PathBuf> {
        let comps = path::virt_components(&path::split_abs(&self.cwd), raw);
        if !follow_last {
            // 不跟随末段：先对父目录跟随软链，再拼末段
            if comps.is_empty() {
                return Some(self.root.clone());
            }
            let (last, parent) = comps.split_last().unwrap();
            let p = path::resolve_following(&self.root, parent, true)?;
            return Some(p.join(last));
        }
        path::resolve_following(&self.root, &comps, true)
    }

    /// 把 raw 解析成虚拟绝对路径字符串（cd 用；不做宿主访问，符号链接不展开）
    pub fn resolve_virt(&self, raw: &str) -> String {
        path::to_virt(&path::virt_components(
            &path::split_abs(&self.cwd),
            raw,
        ))
    }

    pub fn new(elf_bytes: &[u8]) -> Result<Self, String> {
        // 兜底：入口没调过预留就这里补一次（幂等）。理想情况是 host/jnihost
        // 在**任何大块分配之前**调，那才是确定性的。
        reserve_guest_window();
        let low = elf::min_vaddr(elf_bytes).unwrap_or(0);
        // 恒等映射的基址必须是 ELF 最低 vaddr：非 PIE 的 busybox 写死 0x400000，
        // 它算出来的每个指针都是那个基址上的绝对地址。映射失败就回落 flat ——
        // 解释器照旧能跑，只是没有原生通道。
        let mem = if want_identity() && low >= 0x1_0000 && low < MEM_SIZE {
            match GuestMem::identity(low, MEM_SIZE - low) {
                Ok(m) => m,
                Err(e) => {
                    if std::env::var("CH_IDENTITY_DEBUG").is_ok() {
                        eprintln!("[ch-mem] 恒等映射失败 base={low:#x} len={:#x}: {e}", MEM_SIZE - low);
                        // 把占住 0x4xxxxx 的那一段宿主映射打出来 —— 这是定位
                        // 「身份映射时好时坏」的唯一直接证据
                        if let Ok(maps) = std::fs::read_to_string("/proc/self/maps") {
                            for l in maps.lines() {
                                let mut it = l.split(|c| c == '-' || c == ' ');
                                let (s, e) = (it.next(), it.next());
                                if let (Some(s), Some(e)) = (s, e) {
                                    if let (Ok(a), Ok(b)) = (u64::from_str_radix(s, 16), u64::from_str_radix(e, 16)) {
                                        // 与 [0x400000, 0x4000000) 有交集就报
                                        if a < 0x0400_0000 && b > 0x0040_0000 {
                                            eprintln!("[ch-mem]   冲突: {l}");
                                        }
                                    }
                                }
                            }
                        }
                    }
                    GuestMem::new(MEM_SIZE as usize)
                }
            }
        } else {
            GuestMem::new(MEM_SIZE as usize)
        };
        let mut mem = mem;
        let image = elf::load_static(&mut mem, elf_bytes, MEM_SIZE)?;
        Ok(Self {
            mem, image, exit_code: None,
            mmap_next: syscall::MMAP_BASE, mmap_base: syscall::MMAP_BASE,
            files: std::collections::BTreeMap::new(),
            sockets: std::collections::BTreeMap::new(),
            pipes: std::collections::BTreeMap::new(),
            pseudo: std::collections::BTreeMap::new(),
            dup_alias: std::collections::BTreeMap::new(),
            dir_pos: std::collections::BTreeMap::new(),
            root: std::path::PathBuf::from("/"),
            cwd: "/".to_string(),
            out: Box::new(std::io::stdout()),
            err: Box::new(std::io::stderr()),
            stdin: std::io::Cursor::new(Vec::new()),
        })
    }

    /// 别名回溯：dup/dup3 之后的 fd 指向源 fd。最多跟 8 跳（防环）。
    pub fn real_fd(&self, mut fd: i64) -> i64 {
        for _ in 0..8 {
            match self.dup_alias.get(&fd) {
                Some(&src) if src != fd => fd = src,
                _ => break,
            }
        }
        fd
    }

    /// fd 是否存在（含 0/1/2 与别名）。
    pub fn fd_live(&self, fd: i64) -> bool {
        let r = self.real_fd(fd);
        r >= 0 && r <= 2
            || self.files.contains_key(&r)
            || self.pipes.contains_key(&r)
            || self.sockets.contains_key(&r)
            || self.pseudo.contains_key(&r)
    }

    /// 分配最小的空闲 fd（跳过 0/1/2，与 Linux 的 alloc_fd 一致）。
    pub fn alloc_fd(&self, from: i64) -> i64 {
        let mut fd = from.max(3);
        while self.fd_live(fd) || self.dup_alias.contains_key(&fd) {
            fd += 1;
        }
        fd
    }

    /// 统一写 fd：1/2 → 注入的 out/err；管道写端；socket；已打开文件。
    /// 返回写入字节数或负 errno。**管道/文件走这里，避免每条 syscall 各写一份分派**。
    pub fn write_fd(&mut self, fd: i64, buf: &[u8]) -> i64 {
        use std::io::Write;
        let fd = self.real_fd(fd);
        if fd == 1 {
            return match self.out.write_all(buf) {
                Ok(_) => { let _ = self.out.flush(); buf.len() as i64 }
                Err(_) => -5,
            };
        }
        if fd == 2 {
            return match self.err.write_all(buf) {
                Ok(_) => { let _ = self.err.flush(); buf.len() as i64 }
                Err(_) => -5,
            };
        }
        if let Some(pe) = self.pipes.get(&fd) {
            if !pe.is_write {
                return -9; // EBADF：读端不可写
            }
            if let Ok(mut g) = pe.shared.lock() {
                g.buf.extend_from_slice(buf);
                return buf.len() as i64;
            }
            return -5;
        }
        if let Some(sk) = self.sockets.get_mut(&fd) {
            return match sk {
                Sock::Tcp(s) => match s.write(buf) { Ok(n) => n as i64, Err(_) => -32 },
                Sock::Udp(s) => match s.send(buf) { Ok(n) => n as i64, Err(_) => -11 },
                Sock::PendingTcp => -9,
            };
        }
        match self.files.get_mut(&fd) {
            Some((f, _)) => match f.write_all(buf) { Ok(_) => buf.len() as i64, Err(_) => -5 },
            None => -9,
        }
    }

    /// 统一读 fd：0 → 注入的 stdin 游标；管道读端；socket；已打开文件。
    pub fn read_fd(&mut self, fd: i64, buf: &mut [u8]) -> i64 {
        use std::io::Read;
        let fd = self.real_fd(fd);
        if fd == 0 && !self.files.contains_key(&0) && !self.pipes.contains_key(&0) {
            return self.stdin.read(buf).map(|n| n as i64).unwrap_or(0);
        }
        if let Some(pe) = self.pipes.get(&fd) {
            if pe.is_write {
                return -9;
            }
            if let Ok(mut g) = pe.shared.lock() {
                let n = g.buf.len().min(buf.len());
                buf[..n].copy_from_slice(&g.buf[..n]);
                g.buf.drain(..n);
                if n == 0 && g.writers > 0 {
                    return -11; // EAGAIN：无人写但写端仍开着（单进程下按可重试处理）
                }
                return n as i64;
            }
            return -5;
        }
        if let Some(sk) = self.sockets.get_mut(&fd) {
            return match sk {
                Sock::Tcp(s) => match s.read(buf) { Ok(n) => n as i64, Err(_) => -11 },
                Sock::Udp(s) => match s.recv(buf) { Ok(n) => n as i64, Err(_) => -11 },
                Sock::PendingTcp => -9,
            };
        }
        match self.files.get_mut(&fd) {
            Some((f, _)) => match f.read(buf) { Ok(n) => n as i64, Err(_) => -5 },
            None => -9,
        }
    }

    /// dup 语义：把 `src` 复制到 `target`（target = -1 表示自动分配）。
    /// 已有 target 先按 Linux 语义静默关闭。返回新 fd 或负 errno。
    pub fn dup_to(&mut self, src: i64, target: i64) -> i64 {
        let real = self.real_fd(src);
        if !self.fd_live(src) {
            return -9;
        }
        let newfd = if target < 0 { self.alloc_fd(3) } else {
            self.close_fd(target);
            self.dup_alias.remove(&target);
            target
        };
        // 源本身可能是别名，这里直接指向它的源头，链不增长
        if real == newfd {
            return newfd;
        }
        self.dup_alias.insert(newfd, real);
        newfd
    }

    /// 关闭一个 fd（清掉它在各表里的实体 + 别名）。
    pub fn close_fd(&mut self, fd: i64) -> bool {
        let mut hit = self.dup_alias.remove(&fd).is_some();
        hit |= self.files.remove(&fd).is_some();
        hit |= self.sockets.remove(&fd).is_some();
        hit |= self.pipes.remove(&fd).is_some();
        hit |= self.pseudo.remove(&fd).is_some();
        hit |= self.dir_pos.remove(&fd).is_some();
        hit
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

    #[test]
    fn identity_roundtrip() {
        // 挑一个几乎不可能被占用的高地址
        let base = 0x0000_7f30_0000_0000u64;
        match GuestMem::identity(base, 0x1_0000) {
            Ok(mut m) => {
                assert!(m.is_identity());
                assert_eq!(m.span(), (base, base + 0x1_0000));
                m.write(base + 0x10, b"arena\0").unwrap();
                assert_eq!(m.read_cstr(base + 0x10).unwrap(), "arena");
                assert!(m.read(base + 0x2_0000, &mut [0u8; 4]).is_err());
            }
            Err(_) => { /* 环境不允许 mmap 固定地址时跳过 */ }
        }
    }
}
