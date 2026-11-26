//! Tier1 —— 同 ISA 原生执行。
//!
//! ## 为什么要有这一层
//!
//! Tier0 解释器把每条 ARM64 指令拆成多次 Rust 调用，语义全靠手写，结果是
//! 「一条指令一个坑」：本轮已经连踩 DecodeBitMasks、ldrsw、字节访存、
//! udiv、BFM、EXTR…… 每修一个就冒出下一个（musl 的 `long double` 在 aarch64
//! 上是 f128，`printf` 任何浮点都要走软浮点乘除，最后卡在 fmt_fp 死循环）。
//!
//! 宿主与 guest **同为 aarch64** 时根本没必要解释：把 guest 的 ELF 装进一段
//! 恒等映射内存（guest 虚拟地址 == 宿主虚拟地址），之后把 CPU 语义 100% 交给
//! 真 CPU，唯一需要介入的是 syscall。这层就是干这个的。
//!
//! 这就是 appine 的 Tier1 思路（它是 ARM64→ARM64 的 DBT + Tier0 兜底；我们
//! 宿主同 ISA，连翻译都省了，直接原生跑）。
//!
//! ## 陷门怎么下
//!
//! 把可执行段里每一条 `svc #0`（`0xD4000001`）改写成 `brk #0`（`0xD4200000`），
//! 装 SIGTRAP 处理器。`brk` 的好处是**不占用任何通用寄存器**（用 `bl` 会覆盖
//! x30 = guest 的 LR，而 4 字节里塞不进「先存 x30 再跳」），并且内核在投递信号
//! 时会把 GPR **和 FP/SIMD 状态**一起存进 sigframe、返回时自动恢复 ——
//! 处理器里用 Rust（会随便踩 v0-v31）也不会污染 guest 的浮点状态。
//!
//! ## ucontext 的偏移为什么可以直接写 168
//!
//! 处理器第三个参数指向的是**内核** `rt_sigframe.uc`（不是 bionic/musl 的
//! `ucontext_t`，两者 `sigset_t` 大小不同：bionic 8 字节、musl/glibc 128 字节）。
//! 内核 arm64 布局固定为：
//!
//! ```text
//! ucontext:  uc_flags(8) uc_link(8) uc_stack(24) uc_sigmask(8) __unused(120)  → mcontext @168
//! sigcontext: fault_address(8) regs[31](248) sp(8) pc(8) pstate(8)
//! ```
//!
//! 所以 `regs[i]` 在 `168+8+8i`，`sp` 在 `168+264`，`pc` 在 `168+272`。
//!
//! ## 退出怎么回宿主
//!
//! guest 的栈在我们的 arena 里，宿主 Rust 的帧在宿主栈上，两者不是一条栈。
//! 退出时用自实现的 `ch_longjmp` 回到 `run()` 里的 `ch_setjmp` 点（见下方 global_asm）：
//! 它把 aarch64 的被调用者保存寄存器（x19-x28/x29/x30/sp **以及 d8-d15**）整套恢复。
//! 只 `ret` 是不够的 —— `ret` 恢复不了 d8-d15，宿主浮点局部变量会被 guest 污染。
//! 不用 libc 的 sigsetjmp/siglongjmp：musl 只导出 `__sigsetjmp`、bionic 只导出
//! `sigsetjmp`，同一个 `link_name` 在 Android 上会让 dlopen 直接失败。
//!
//! ## 宿主 TLS 必须切换
//!
//! guest 的 musl 会 `msr tpidr_el0` 把它自己的 TLS 装进去。处理器里任何 libc
//! 调用（malloc / errno）都走 TLS，等于直接踩 guest 的 TLS 区。所以进处理器
//! 先把 TPIDR_EL0 换回宿主值，返回前换回 guest 值。

use ch_kernel::Process;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, AtomicUsize, Ordering};

// ---- 指令编码 ----
const SVC0: u32 = 0xD400_0001;
const BRK0: u32 = 0xD420_0000;
/// 入口返回兜底：`brk #0x7ff`（BRK 的 imm16 在 bit[20:5]）
const BRK_EXIT: u32 = BRK0 | (0x7FF << 5);

// ---- 信号 ----
const SIGILL: i32 = 4;
const SIGTRAP: i32 = 5;
const SIGABRT: i32 = 6;
const SIGBUS: i32 = 7;
const SIGFPE: i32 = 8;
const SIGSEGV: i32 = 11;

const SA_SIGINFO: i32 = 0x4;
const SA_ONSTACK: i32 = 0x0800_0000;
const SA_NODEFER: i32 = 0x4000_0000;

/// `uc_mcontext` 偏移的**兜底值**。真值在进入 guest 前用探针实测（见 `probe_mc_offset`）。
///
/// 为什么不能硬编码：本机内核 6.12 实测是 **176**，公开头文件的算法（168）对不上，
/// 而 Android 的 rt_sigframe 又是另一套。硬编码一次就等于把「能不能跑」赌在
/// 某个内核版本上 —— 偏移错 8 字节的后果就是 PC/SP 全读错，guest 跳到栈上执行。
const UC_MCONTEXT_FALLBACK: usize = 176;

/// 探针魔数：进入 guest 前先塞进 x28 并 `brk #0`，处理器里扫 uc 找它，
/// 由此反推 `regs` 的起点 → `uc_mcontext` 的偏移。
const PROBE_MAGIC: u64 = 0x4B1D_5EED_0BAD_F00D;

#[repr(C)]
struct KSigContext {
    fault_address: u64,
    regs: [u64; 31],
    sp: u64,
    pc: u64,
    pstate: u64,
}

// ---- libc 导出（bionic 与 musl 同名同签名）----
#[repr(C)]
struct SigAction {
    handler: usize,
    // musl: sigset_t = 128B；bionic: 8B。两者都必须精确，否则内核读到的
    // sa_flags / sa_restorer 会错位，handler 直接不被调用。
    #[cfg(not(target_os = "android"))]
    mask: [u64; 16],
    #[cfg(target_os = "android")]
    mask: [u64; 1],
    flags: i32,
    restorer: usize,
}

#[repr(C)]
struct StackT {
    ss_sp: usize,
    ss_flags: i32,
    ss_size: usize,
}

extern "C" {
    fn sigaction(sig: i32, act: *const SigAction, old: *mut SigAction) -> i32;
    fn sigaltstack(ss: *const StackT, old: *mut StackT) -> i32;
    fn sigprocmask(how: i32, set: *const u8, old: *mut u8) -> i32;
}

// ---- setjmp / longjmp：自己实现，不用 libc ----
//
// 这是被真机打回来过的坑，写清楚免得再踩：
//   * musl 的 `sigsetjmp` 是**宏**，展开成 `__sigsetjmp`，只导出 `__sigsetjmp`；
//   * bionic（Android）导出的是**真函数** `sigsetjmp`，没有 `__sigsetjmp`。
//
// 之前用 `#[link_name = "__sigsetjmp"]` 迁就 musl，Android 上 `dlopen` 直接报
//
//     dlopen failed: cannot locate symbol "__sigsetjmp" referenced by libchlinux.so
//
// → `System.loadLibrary` 失败 → `LinuxNative.<clinit>` 抛异常 → 类被标记 erroneous
// → 之后每次访问都是 `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`
// → 终端报「会话启动失败」。同一个 link_name 不可能同时满足两个 libc，所以自己写。
//
// 语义与标准 `sigsetjmp(env, 1)` 等价，只是**不碰 signal mask**（那部分由 `bail()`
// 里显式 `sigprocmask(SIG_UNBLOCK)` 处理）。
// 保存 callee-saved：x19-x28 / x29(fp) / x30(lr) / sp / d8-d15（v8-v15 只有低 64 位需要）。
// 布局：x19..x30 共 12 个 u64 @0..96（每对 stp 16 字节），sp @96，d8..d15 @104..160。
// `JmpBuf` 给的是 512 字节，够。

#[cfg(target_arch = "aarch64")]
core::arch::global_asm!(
    ".text",
    ".global ch_setjmp",
    ".type ch_setjmp, %function",
    "ch_setjmp:",
    "stp x19, x20, [x0, #0]",
    "stp x21, x22, [x0, #16]",
    "stp x23, x24, [x0, #32]",
    "stp x25, x26, [x0, #48]",
    "stp x27, x28, [x0, #64]",
    "stp x29, x30, [x0, #80]",
    "mov x9, sp",
    "str x9, [x0, #96]",
    "stp d8,  d9,  [x0, #104]",
    "stp d10, d11, [x0, #120]",
    "stp d12, d13, [x0, #136]",
    "stp d14, d15, [x0, #152]",
    "mov w0, #0",
    "ret",
    ".size ch_setjmp, .-ch_setjmp",
    ".global ch_longjmp",
    ".type ch_longjmp, %function",
    "ch_longjmp:",
    "ldp x19, x20, [x0, #0]",
    "ldp x21, x22, [x0, #16]",
    "ldp x23, x24, [x0, #32]",
    "ldp x25, x26, [x0, #48]",
    "ldp x27, x28, [x0, #64]",
    "ldp x29, x30, [x0, #80]",
    "ldr x9, [x0, #96]",
    "mov sp, x9",
    "ldp d8,  d9,  [x0, #104]",
    "ldp d10, d11, [x0, #120]",
    "ldp d12, d13, [x0, #136]",
    "ldp d14, d15, [x0, #152]",
    "mov x0, x1",
    "cbnz x0, 1f",
    "mov w0, #1",
    "1:",
    "ret",
    ".size ch_longjmp, .-ch_longjmp",
);

extern "C" {
    fn ch_setjmp(buf: *mut u64) -> i32;
    fn ch_longjmp(buf: *const u64, val: i32) -> !;
}

fn read_tpidr() -> u64 {
    let v: u64;
    unsafe { core::arch::asm!("mrs {}, tpidr_el0", out(reg) v, options(nomem, nostack)) };
    v
}

fn write_tpidr(v: u64) {
    unsafe { core::arch::asm!("msr tpidr_el0, {}", in(reg) v, options(nomem, nostack)) };
}

/// 改写代码后刷 I-cache（EL0 允许 `dc cvau` / `ic ivau`）。
unsafe fn flush_icache(lo: u64, hi: u64) {
    let mut a = lo & !63u64;
    while a < hi {
        core::arch::asm!("dc cvau, {}", in(reg) a, options(nostack));
        a += 64;
    }
    core::arch::asm!("dsb ish", options(nostack));
    let mut a = lo & !63u64;
    while a < hi {
        core::arch::asm!("ic ivau, {}", in(reg) a, options(nostack));
        a += 64;
    }
    core::arch::asm!("dsb ish", "isb", options(nostack));
}

#[repr(align(16))]
struct JmpBuf([u64; 64]);

struct Ctx {
    proc: *mut Process,
    jb: JmpBuf,
    host_tls: u64,
    code: i32,
}

static CTX: AtomicPtr<Ctx> = AtomicPtr::new(std::ptr::null_mut());
/// `uc_mcontext` 偏移；0 = 还没探过。
static MC_OFF: AtomicUsize = AtomicUsize::new(0);
/// 正在跑探针（此期间的 SIGTRAP 不走 syscall 派发）。
static PROBING: AtomicBool = AtomicBool::new(false);
/// 探针那一刻的**宿主** sp。用它定位 sigframe：宿主 sp 与 guest sp（arena 顶部）
/// 差着数量级，且探针时 guest 一条指令都没执行，所以 uc 里出现该值的位置
/// 只能是 sigcontext.sp 字段 —— 比魔数可靠（`in(reg)` 无法指定寄存器，
/// 魔数会同时出现在别的通用寄存器里，扫描取首个匹配必错）。
static HOST_SP: AtomicU64 = AtomicU64::new(0);
static BUSY: AtomicBool = AtomicBool::new(false);

/// Tier1 原生执行成功次数（真机自检要看这个：`tier1>0` 才证明原生通道在 App 进程里成立）。
pub static TIER1_HITS: AtomicU64 = AtomicU64::new(0);
/// Tier1 没能启动、回落解释器的次数（恒等映射抢不到 / execmem 被拒等）。
pub static TIER1_FALLBACK: AtomicU64 = AtomicU64::new(0);

pub fn tier1_hits() -> u64 { TIER1_HITS.load(Ordering::Relaxed) }
pub fn tier1_fallbacks() -> u64 { TIER1_FALLBACK.load(Ordering::Relaxed) }

#[repr(align(16))]
struct AltStack([u8; 64 * 1024]);
static mut ALTSTACK: AltStack = AltStack([0; 64 * 1024]);

fn uc_sc(uc: *mut u8) -> *mut KSigContext {
    let off = match MC_OFF.load(Ordering::Acquire) {
        0 => UC_MCONTEXT_FALLBACK,
        v => v,
    };
    unsafe { uc.add(off) as *mut KSigContext }
}

/// 从处理器里跳回宿主。
///
/// 调用前必须已把 TPIDR_EL0 恢复成宿主值 —— longjmp 之后就在宿主 Rust 里跑了。
unsafe fn bail(ctx: *mut Ctx, code: i32) -> ! {
    (*ctx).code = code;
    write_tpidr((*ctx).host_tls);
    // 处理器执行期间内核把 SIGTRAP 加进了 mask，正常返回靠 sigreturn 还原；
    // 我们是跳出去、不经过 sigreturn，所以必须手动解除。不然回到宿主后
    // guest 后续所有 `brk #0` 都不再触发 handler，Tier1 就此静默挂死。
    const SIG_UNBLOCK: i32 = 1;
    let mut set = [0u64; 16];
    set[0] = 1u64 << (SIGTRAP - 1);
    sigprocmask(SIG_UNBLOCK, set.as_ptr() as *const u8, std::ptr::null_mut());
    ch_longjmp(std::ptr::addr_of_mut!((*ctx).jb) as *mut u64 as *const u64, 1)
}

/// SIGTRAP：`brk #0` = guest 执行了被改写过的 svc。
extern "C" fn on_trap(_sig: i32, _info: *mut u8, uc: *mut u8) {
    let ctx = CTX.load(Ordering::Acquire);
    if ctx.is_null() {
        return;
    }
    // 进来时 TPIDR_EL0 是 guest 的；先换回宿主，之后任何 libc 调用才是安全的。
    let guest_tls = read_tpidr();
    let host_tls = unsafe { (*ctx).host_tls };
    write_tpidr(host_tls);

    // ---- 探针期：用「宿主 sp」定位 sigcontext，一次确定 uc_mcontext 偏移 ----
    //
    // sigcontext 布局固定为 fault_address(8) + regs[31](248) + sp(8) + pc(8) + pstate(8)，
    // 所以找到 sp 字段的位置就等于找到了 mcontext 起点（sp 在 mcontext+256）。
    // 校验两条：① 该位置往上的 x28 槽必须是探针魔数；② pstate 必须是合法值。
    if PROBING.load(Ordering::Acquire) {
        let host_sp = HOST_SP.load(Ordering::Acquire);
        let dbg = std::env::var("CH_NATIVE_TRACE").is_ok();
        if dbg {
            eprintln!("[probe] host_sp={host_sp:#x} uc={uc:p}");
            for k in 0..64usize {
                let v = unsafe { *((uc as *const u64).add(k)) };
                if v != 0 {
                    eprintln!("  uc+{:#05x} [{k}] = {:#018x}", k * 8, v);
                }
            }
        }
        let mut found = 0usize;
        for k in 0..128usize {
            let v = unsafe { *((uc as *const u64).add(k)) };
            if v != host_sp || host_sp == 0 {
                continue;
            }
            let off = (k * 8) as isize - 256;
            if off < 0 {
                continue;
            }
            let sc = unsafe { uc.offset(off) as *mut KSigContext };
            let x28 = unsafe { (*sc).regs[28] };
            // 只认魔数：同一个宿主 sp 在 uc 里会出现两次（前面那份是 uc_stack.ss_sp
            // 或其它字段的残留），但只有真 sigcontext 的那份，往上 232 字节处是 x28。
            // 不要拿 pstate 做校验 —— 探针前刚执行过 `str`，N/Z 标志随机（实测见过
            // 0x80000000），用 0x60000000 这类固定值判定会把正确的布局挡掉。
            if x28 == PROBE_MAGIC {
                found = off as usize;
                break;
            }
        }
        if found != 0 {
            MC_OFF.store(found, Ordering::Release);
            if std::env::var("CH_NATIVE_DEBUG").is_ok() {
                eprintln!("[ch-native] sigframe 自检：uc_mcontext = uc+{found}");
            }
            let sc = unsafe { uc.add(found) as *mut KSigContext };
            unsafe { (*sc).pc += 4 }; // 跳过探针的 brk
            PROBING.store(false, Ordering::Release);
            write_tpidr(guest_tls);
            return;
        }
        // 两条校验都没过的布局我们不认识，别冒险，直接收摊
        PROBING.store(false, Ordering::Release);
        unsafe { bail(ctx, 0) };
    }
    let sc = uc_sc(uc);
    // 内核 ELR 指向 brk 指令本身（同步异常，PC 不前移）
    let insn = unsafe { *((*sc).pc as *const u32) };
    let imm = (insn >> 5) & 0xFFFF;

    if imm == 0x7FF {
        // 入口返回兜底：guest 的 `_start` 不会 return，走到这里说明 ELF 有点怪
        unsafe { bail(ctx, 0) };
    }

    let mut regs = [0u64; 31];
    unsafe {
        std::ptr::copy_nonoverlapping(std::ptr::addr_of!((*sc).regs) as *const u64, regs.as_mut_ptr(), 31)
    };
    let trace = std::env::var("CH_NATIVE_TRACE").is_ok();
    if trace {
        unsafe {
            eprintln!(
                "[trap] nr={} x0={:#x} x1={:#x} x2={:#x} pc={:#x} insn={:#010x} sp={:#x} pstate={:#x}",
                regs[8], regs[0], regs[1], regs[2],
                (*sc).pc, insn, (*sc).sp, (*sc).pstate
            );
        }
    }
    let ret = unsafe { ch_kernel::syscall::handle(&mut *(*ctx).proc, &mut regs) };
    if trace {
        eprintln!("[trap]   -> r0={:#x}", regs[0]);
    }
    if let Some(code) = ret {
        unsafe { bail(ctx, (code & 0xff) as i32) };
    }
    unsafe {
        std::ptr::copy_nonoverlapping(regs.as_ptr(), std::ptr::addr_of_mut!((*sc).regs) as *mut u64, 31);
        // 越过这条 brk
        (*sc).pc += 4;
    }
    write_tpidr(guest_tls);
}

/// SIGSEGV/SIGBUS/SIGILL/SIGFPE：guest 自己炸了。给一份能定位的现场再退出。
extern "C" fn on_fault(sig: i32, _info: *mut u8, uc: *mut u8) {
    let ctx = CTX.load(Ordering::Acquire);
    if ctx.is_null() {
        return;
    }
    let host_tls = unsafe { (*ctx).host_tls };
    write_tpidr(host_tls);
    let sc = uc_sc(uc);
    unsafe {
        let p = &mut *(*ctx).proc;
        use std::io::Write;
        let msg = format!(
            "\n[ch-native] 非法访问：signal={sig} pc={:#x} fault={:#x} sp={:#x} x8={:#x}\n",
            (*sc).pc,
            (*sc).fault_address,
            (*sc).sp,
            (*sc).regs[8]
        );
        let _ = p.err.write_all(msg.as_bytes());
        let _ = p.err.flush();
        bail(ctx, 128 + sig)
    }
}

fn install(sig: i32, f: extern "C" fn(i32, *mut u8, *mut u8)) -> Result<(), String> {
    let act = SigAction {
        handler: f as usize,
        mask: [0; MASK_WORDS],
        flags: SA_SIGINFO | SA_NODEFER | SA_ONSTACK,
        restorer: 0,
    };
    let rc = unsafe { sigaction(sig, &act, std::ptr::null_mut()) };
    if rc != 0 {
        return Err(format!("sigaction({sig}) 失败"));
    }
    Ok(())
}

#[cfg(target_os = "android")]
const MASK_WORDS: usize = 1;
#[cfg(not(target_os = "android"))]
const MASK_WORDS: usize = 16;

fn install_all() -> Result<(), String> {
    // 备用栈：guest 栈溢出时也要能跑处理器（否则连诊断都打不出来）
    let ss = StackT {
        ss_sp: std::ptr::addr_of_mut!(ALTSTACK) as *mut u8 as usize,
        ss_flags: 0,
        ss_size: 64 * 1024,
    };
    unsafe { sigaltstack(&ss, std::ptr::null_mut()) };
    install(SIGTRAP, on_trap)?;
    install(SIGSEGV, on_fault)?;
    install(SIGBUS, on_fault)?;
    install(SIGILL, on_fault)?;
    install(SIGFPE, on_fault)?;
    install(SIGABRT, on_fault)?;
    Ok(())
}

/// 把 guest 入口当普通函数调用。正常不会返回：所有出口都走信号 + longjmp。
unsafe fn enter(entry: u64, sp: u64) -> ! {
    // 探针块：x28 塞魔数 + `brk #0`，处理器扫 uc 反推 mcontext 偏移。
    // 这一步保证 sigframe 布局不靠猜（各内核 / 各 Android 版本都不一样）。
    // 单独成块是因为 `options(noreturn)` 下不允许声明 out。
    core::arch::asm!(
        "mov x9, sp",
        "mov x10, {slot}",
        "str x9, [x10]",
        "mov x28, {magic}",
        "brk #0",
        "mov x28, xzr",
        magic = in(reg) PROBE_MAGIC,
        slot = in(reg) &HOST_SP as *const _ as usize,
        out("x9") _,
        out("x10") _,
        out("x28") _,
    );
    // 跳转块：sp 一改就再也回不来了，必须 noreturn。
    core::arch::asm!(
        "mov x29, #0",
        "mov x30, #0",
        "mov sp, {sp}",
        "br {entry}",
        sp = in(reg) sp,
        entry = in(reg) entry,
        options(noreturn)
    )
}

/// 当前 Process 是否具备原生执行条件。
pub fn available(proc: &Process) -> bool {
    proc.mem.is_identity()
}

/// 执行到 guest 退出为止。`sp` 是已经组装好的初始栈指针。
///
/// 返回 `Err` 只表示「没能启动」（任何 guest 指令都没执行过），调用方可以
/// 安全地回落解释器。
pub fn run(proc: &mut Process, sp: u64) -> Result<i32, String> {
    if !available(proc) {
        TIER1_FALLBACK.fetch_add(1, Ordering::Relaxed);
        return Err("内存不是恒等映射".into());
    }
    if BUSY.swap(true, Ordering::AcqRel) {
        return Err("已有原生会话在跑".into());
    }
    let guard = BusyGuard;

    // 1) 先扫出所有 svc 站点。扫不到就说明这条二进制不靠 svc 进内核（可能被
    //    ptrace/seccomp 改过），直接放弃，别把代码改坏。
    //
    // **动态链接时必须扫两个镜像**：起点是解释器（ld-musl）的入口，真正的 syscall
    // 指令全在解释器自己的代码里；主程序的 .text 可能一条 svc 都没有。旧实现只扫
    // `image.text`（主程序那一段）→ 动态程序一律「可执行段里没有 svc #0」→ 无声
    // 回落 Tier0。`xranges` 是主程序 + 解释器所有可执行段的并集。
    let ranges = if proc.image.xranges.is_empty() {
        vec![proc.image.text]
    } else {
        proc.image.xranges.clone()
    };
    let mut sites: Vec<u64> = Vec::new();
    for (t0, t1) in &ranges {
        let mut a = t0 & !3;
        while a + 4 <= *t1 {
            let mut b = [0u8; 4];
            if proc.mem.read(a, &mut b).is_ok() && u32::from_le_bytes(b) == SVC0 {
                sites.push(a);
            }
            a += 4;
        }
    }
    if sites.is_empty() {
        return Err("可执行段里没有 svc #0".into());
    }

    // 2) 装处理器（失败就整条放弃，此时代码还没动过）
    install_all()?;

    // 3) 代码页必须显式带上 X：恒等映射申请的是 RW，不带 PROT_EXEC 就是 NX，
    //    跳进入口立刻 fault。Android 上这一步会走 execmem 检查，被拒绝时
    //    mprotect 直接失败 —— 正好在「一条指令都没执行」时退出去。
    //
    //    权限按 ELF 段**逐页归并**（`image.prots`）：旧实现假设「只有一段代码 +
    //    一段数据」，动态链接之后是两个镜像七八个段，那个假设不成立了。
    const PAGE: u64 = 4096;
    const PROT_RX: i32 = 1 | 4;
    const PROT_RWX: i32 = 1 | 2 | 4;
    let mut applied: Vec<(u64, u64, i32)> = Vec::new();
    for &(lo, hi, p) in &proc.image.prots {
        if p == 0 {
            continue;
        }
        let prot = match p {
            7 => PROT_RWX,
            5 => PROT_RX,
            // 纯 R / RW 不需要动：arena 本来就是 RW
            _ => continue,
        };
        let lo = lo & !(PAGE - 1);
        let hi = (hi + PAGE - 1) & !(PAGE - 1);
        if hi <= lo {
            continue;
        }
        applied.push((lo, hi, prot));
    }
    if applied.is_empty() {
        return Err("没有任何可执行段（prots 为空）".into());
    }

    // 4) 改写 svc → brk #0
    for &s in &sites {
        proc.mem.write(s, &BRK0.to_le_bytes())?;
    }
    let (t0, t1) = ranges
        .iter()
        .fold((u64::MAX, 0u64), |a, r| (a.0.min(r.0), a.1.max(r.1)));
    unsafe { flush_icache(t0, t1) };

    // 5) 设权限。失败要先把代码还原，好让调用方干净地回落到解释器
    //    —— 否则解释器看到的是 brk，一条 syscall 都进不去。
    for &(lo, hi, prot) in &applied {
        if let Err(e) = proc.mem.protect(lo, hi - lo, prot) {
            for &s in &sites {
                let _ = proc.mem.write(s, &SVC0.to_le_bytes());
            }
            unsafe { flush_icache(t0, t1) };
            return Err(format!("代码页设 R-X 失败（Android execmem？）：{e}"));
        }
    }

    // 4) 起跑
    let mut boxed = Box::new(Ctx {
        proc: proc as *mut Process,
        jb: JmpBuf([0; 64]),
        host_tls: read_tpidr(),
        code: 0,
    });
    let ctx: *mut Ctx = &mut *boxed;
    CTX.store(ctx, Ordering::Release);

    let entry = proc.image.entry;
    if std::env::var("CH_NATIVE_DEBUG").is_ok() {
        eprintln!(
            "[ch-native] ranges={} sites={} entry={:#x} sp={:#x} interp={:#x} prots={}",
            ranges.len(), sites.len(), entry, sp, proc.image.interp_base, applied.len()
        );
    }
    TIER1_HITS.fetch_add(1, Ordering::Relaxed);
    PROBING.store(true, Ordering::Release);
    let rc = unsafe { ch_setjmp(std::ptr::addr_of_mut!((*ctx).jb) as *mut u64) };
    if rc == 0 {
        unsafe { enter(entry, sp) };
    }

    CTX.store(std::ptr::null_mut(), Ordering::Release);
    let code = boxed.code;
    drop(boxed);
    drop(guard);
    Ok(code)
}

struct BusyGuard;
impl Drop for BusyGuard {
    fn drop(&mut self) {
        BUSY.store(false, Ordering::Release);
    }
}
