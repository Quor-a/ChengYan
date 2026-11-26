//! 澄言 Linux —— Android JNI 宿主（cdylib: libchlinux.so）。
//!
//! 会话式 API（AI 自主编排，不内置固定流程）：
//!   init() → 建立原生日志通道（stderr → logcat + 内存环形缓冲），**必须先调**
//!   start(dataDir) → machine 句柄（busybox 已装载、rootfs = dataDir/rootfs）
//!   exec(machine, cmd) → 任务号（后台线程跑；同一 machine 同时一个任务，忙时返回 -3）
//!   poll(machine) → JSON {busy, exit, output}（输出为环形缓冲，保留最新 384KB）
//!   stop(machine) → 释放
//!   info() / lastError() / logTail(n) → 现场自检（不需要 adb）
//!
//! 分层纪律：本层只做 JNI 胶水 + 任务生命周期，不碰指令与 syscall。
//!
//! ## 三条从 Appine（mthli/appine）搬来的硬纪律
//!
//! 1. **每个 JNI 入口都 `catch_unwind`**（他们的 `guarded!` 宏）。panic 从
//!    `extern "system"` 里 unwind 出去 = abort 整个 App，用户看到的是「莫名其妙闪退」。
//! 2. **句柄失效后调用必须安全返回**，不能崩（他们的「using it after stop is safe」）。
//!    这里统一用负数表示失败，且 `poll` 退化为 `{}`。
//! 3. **句柄表条目在锁外 drop**（他们的 D1）。`Machine` 的析构会杀进程/join 线程，
//!    持着表锁 drop 就是死锁。另外槽位复用，不无限增长。
//!
//! JNI 绑定自己抄了一份（见 `jni_min.rs`），不依赖 `jni` crate —— 后者的
//! 传递依赖要在**宿主平台**链接一个二进制，交叉编译机上缺宿主链接器时整包编不出来。

mod jni_min;
mod log;

use ch_host::Session;
use jni_min::{jclass, jint, jlong, jstring, JNIEnv, JNINativeInterface_};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

/// JNI 入口拿到的 `env` 是「指向函数表指针的指针」，解一次引用拿到虚表。
#[inline]
unsafe fn tbl<'a>(env: *mut JNIEnv) -> &'a JNINativeInterface_ {
    &**env
}

/// 宿主 String → Java String。
fn mk_string(env: *mut JNIEnv, s: &str) -> jstring {
    let mut buf: Vec<u8> = Vec::with_capacity(s.len() + 1);
    buf.extend_from_slice(s.as_bytes());
    buf.push(0);
    unsafe { ((*tbl(env)).NewStringUTF)(env, buf.as_ptr() as *const core::ffi::c_char) }
}

/// Java String → 宿主 String。注意必须成对 Release，否则每次调用泄一份。
fn get_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if s.is_null() || env.is_null() {
        return None;
    }
    unsafe {
        let t = tbl(env);
        let p = (t.GetStringUTFChars)(env, s, std::ptr::null_mut());
        if p.is_null() {
            return None;
        }
        let out = std::ffi::CStr::from_ptr(p).to_string_lossy().into_owned();
        (t.ReleaseStringUTFChars)(env, s, p);
        Some(out)
    }
}

/// `Any` → 一行可读的 panic 说明（不含栈，栈在 guest 输出里没意义）。
fn panic_msg(e: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = e.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = e.downcast_ref::<String>() {
        s.clone()
    } else {
        "non-string panic".into()
    }
}

/// 跑一个 JNI 函数体：panic 被吃掉，记进原生日志，返回 `fallback`。
///
/// 对齐 Appine 的 `guarded!`：任何未捕获的 panic 穿出 `extern "system"` 都会 abort 进程。
fn guard<T>(what: &str, fallback: T, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(e) => {
            let msg = format!("[ch-panic] {what}: {}", panic_msg(&e));
            log::note(&msg);
            eprintln!("{msg}");
            fallback
        }
    }
}

/// 最近一次失败的完整原因（多行）。`linux info` / 终端页原样显示它，
/// 这样「会话启动失败（-3）」不再是黑盒。
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

fn set_err(s: String) {
    log::note(&s);
    eprintln!("{s}");
    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = s;
}

fn take_err() -> String {
    LAST_ERROR
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// so 加载完成后由 JVM 调用（dlopen 成功返回之后、任何 Java 侧 native 调用之前）。
///
/// 在这里做 guest arena 窗口预留。**不能放 `.init_array`**：那是 dlopen 内部执行的，
/// bionic linker 还持锁、Rust std 的 TLS/stdio/env 都不可靠，实测会让
/// `System.loadLibrary("chlinux")` 直接失败（表现为 `<clinit>` 抛异常 →
/// `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`，终端报「会话启动失败」）。
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: *mut core::ffi::c_void,
    _reserved: *mut core::ffi::c_void,
) -> i32 {
    // 这里**不再预留 0x400000 固定窗口**。
    //
    // 旧设计：busybox 是 ET_EXEC（写死 0x400000），只能提前 `PROT_NONE` 把
    // [0x400000, 64MB) 占住，否则 ART/zygote 的 `arch_randomize_brk` 会把宿主堆
    // 压进来，`MAP_FIXED_NOREPLACE` 抢不到 ⇒ 恒等映射失败 ⇒ 无声回落解释器。
    //
    // 现在 guest 是 static PIE（ET_DYN），arena 由 `mmap(NULL)` 让内核挑地址，
    // 再也不需要固定窗口——「被 ART 抢走」这一整类故障从根上消失。
    //（只有跑 ET_EXEC 老二进制时才按需预留，见 `Process::new`。）
    // 兜底也要把日志通道建起来：即便 Kotlin 侧忘了调 init()，native 的 eprintln
    // 也不该掉进 /dev/null。init() 内部是 Once，重复调用无副作用。
    let _ = catch_unwind(AssertUnwindSafe(log::init));
    0x0001_0006 // JNI_VERSION_1_6：bionic 要求 >= 这版，否则视为加载失败
}

/// 取 machine 表。
///
/// 用 `unwrap_or_else(into_inner)` 而不是 `unwrap()`：一旦某个线程在持锁时 panic，
/// 标准库会把这把锁标记为 poisoned，之后每次 `unwrap()` 都再 panic —— 而 panic 从
/// JNI 函数里穿出去就是 abort（整个 App 死掉）。数据本身是 `Vec<Option<..>>`，
/// 即使中途 panic 也仍然自洽，恢复出来继续用是对的。
fn machines() -> MutexGuard<'static, Vec<Option<Arc<Machine>>>> {
    MACHINES.lock().unwrap_or_else(|e| e.into_inner())
}

/// 单个 machine：一个 Linux 会话（v1 = 单任务 + 环形输出缓冲）。
struct Machine {
    session: Session,
    out: ch_host::SharedOut,
    busy: Arc<AtomicBool>,
    exit_code: Arc<Mutex<Option<i32>>>,
    alive: Arc<AtomicBool>,
    next_task: AtomicU64,
}

static MACHINES: Mutex<Vec<Option<Arc<Machine>>>> = Mutex::new(Vec::new());

/// 1 起算的槽位号 → 下标。句柄 <= 0 一律视为无效（不做事，不崩）。
fn slot(machine: jlong) -> Option<usize> {
    if machine <= 0 {
        None
    } else {
        Some(machine as usize - 1)
    }
}

/// 取一个 machine 的临时引用（不持锁）。句柄无效 → None。
fn machine_of(handle: jlong) -> Option<Arc<Machine>> {
    let i = slot(handle)?;
    let g = machines();
    g.get(i).and_then(|s| s.as_ref()).cloned()
}

/// 建立原生日志通道（stderr → logcat + 环形缓冲）。Kotlin 在 so 加载成功后立刻调用。
///
/// Appine 的 `Native.init` 同样是被显式调用的第一个函数：日志必须在任何诊断之前就位。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_init(
    _env: *mut JNIEnv,
    _c: jclass,
) {
    log::init();
    log::note(&format!(
        "[ch] native log ready｜abi={} window={} v={}",
        std::env::consts::ARCH,
        if ch_kernel::window_reserved() { "ok" } else { "failed" },
        env!("CARGO_PKG_VERSION"),
    ));
}

#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_start(
    env: *mut JNIEnv,
    _c: jclass,
    data_dir: jstring,
) -> jlong {
    let Some(dir) = get_string(env, data_dir) else {
        set_err("start: dataDir 参数为空（JNI 取字符串失败）".into());
        return -1;
    };
    guard("start", -9, move || {
        log::init();
        // 尽早占住 guest arena 窗口（Tier1 恒等映射的前提），见 reserve_guest_window 注释
        ch_kernel::reserve_guest_window();
        let window = if ch_kernel::window_reserved() { "ok" } else { "failed" };
        let root = PathBuf::from(&dir).join("rootfs");
        // 优先用 rootfs 自带的静态 busybox（Alpine aarch64 官方构建）；老包回退 assets busybox
        let bb_static = root.join("bin/busybox.static");
        let bb_fallback = PathBuf::from(&dir).join("busybox");
        let busybox = match std::fs::metadata(&bb_static) {
            Ok(m) if m.len() > 0 => bb_static.clone(),
            _ => bb_fallback.clone(),
        };
        if let Err(e) = std::fs::create_dir_all(&root) {
            set_err(format!(
                "start: 无法创建 rootfs 目录 {}\n  {e}\n  dataDir={dir}",
                root.display()
            ));
            return -2;
        }
        match Session::new(&busybox, &root) {
            Ok(session) => {
                let m = Arc::new(Machine {
                    session,
                    out: Arc::new(Mutex::new(Vec::new())),
                    busy: Arc::new(AtomicBool::new(false)),
                    exit_code: Arc::new(Mutex::new(None)),
                    alive: Arc::new(AtomicBool::new(true)),
                    next_task: AtomicU64::new(1),
                });
                // 槽位复用：stop 过的空位先占，句柄不会随重启次数无限增长
                let handle = {
                    let mut g = machines();
                    match g.iter().position(|s| s.is_none()) {
                        Some(i) => {
                            g[i] = Some(m);
                            i + 1
                        }
                        None => {
                            g.push(Some(m));
                            g.len()
                        }
                    }
                };
                *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
                log::note(&format!(
                    "[ch] start ok｜handle={handle} window={window} busybox={}（{} 字节｜{}）\n  rootfs={}",
                    busybox.display(),
                    std::fs::metadata(&busybox).map(|m| m.len()).unwrap_or(0),
                    if busybox == bb_static { "rootfs 自带" } else { "回退 assets" },
                    root.display(),
                ));
                handle as jlong
            }
            Err(e) => {
                let (exists, size) = match std::fs::metadata(&busybox) {
                    Ok(m) => ("存在".to_string(), m.len()),
                    Err(er) => (format!("不可读：{er}"), 0),
                };
                set_err(format!(
                    "start: 会话创建失败：{e}\n  busybox={}\n    状态={exists}  大小={size}\n  rootfs={}\n  窗口(Tier1 恒等映射)={window}\n  dataDir={dir}",
                    busybox.display(),
                    root.display(),
                ));
                -3
            }
        }
    })
}

/// exec：整行走宿主侧 shell 解析执行（管道/重定向/条件/变量/glob/内建 cd 等）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_exec(
    env: *mut JNIEnv,
    _c: jclass,
    machine: jlong,
    cmd: jstring,
) -> jlong {
    let Some(cmd) = get_string(env, cmd) else {
        set_err("exec: 命令字符串为空（JNI 取字符串失败）".into());
        return -1;
    };
    guard("exec", -9, move || {
        let Some(m) = machine_of(machine) else {
            set_err(format!("exec: 句柄 {machine} 无效（会话已释放或从未建立）"));
            return -2;
        };
        if m.busy.swap(true, Ordering::SeqCst) {
            return -3; // 忙：上一条还没跑完，不是错误，不改 LAST_ERROR
        }
        *m.exit_code.lock().unwrap_or_else(|e| e.into_inner()) = None;
        // 输出缓冲**不清空**：终端要保留会话历史。
        // 旧实现每条命令都 clear，于是 `cd /tmp`（无输出）之后屏幕全空、
        // 前一条 `ls` 的结果被抹掉，用户看到的就是「终端什么都留不住」。
        // 改为追加，并回显命令行，让终端页与 AI 工具都能看到完整上下文。
        m.out
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .extend_from_slice(format!("$ {cmd}\n").as_bytes());
        let mm = m.clone();
        let task = m.next_task.fetch_add(1, Ordering::SeqCst) as jlong;
        std::thread::spawn(move || {
            // 走宿主侧 shell：支持 `|` `>` `>>` `<` `2>&1` `&&` `||` `;` `$VAR` `*` `cd` 等。
            // 旧实现按空格切开直接 `busybox <argv0>`，等于把 shell 语法全当普通参数，
            // 真机上表现为「管道/重定向/cd 全不work」，所以必须走 run_line。
            // guest 侧任何 panic 都必须在这里吃掉：线程 panic 会把 out / exit_code 两把锁
            // 标记 poisoned，之后 JNI 线程取锁再 panic 就从 extern "system" 穿出去 = abort 整个 App。
            let code = catch_unwind(AssertUnwindSafe(|| mm.session.run_line(&cmd, &mm.out)))
                .unwrap_or_else(|e| {
                    let msg = format!("[ch-panic] run_line: {}", panic_msg(&e));
                    log::note(&msg);
                    let mut g = mm.out.lock().unwrap_or_else(|e| e.into_inner());
                    g.extend_from_slice(msg.as_bytes());
                    g.push(b'\n');
                    139 // 128+11(SIGSEGV)：guest 侧崩了，明确标记
                });
            mm.out
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .extend_from_slice(format!("[ch-exit {code}]\n").as_bytes());
            *mm.exit_code.lock().unwrap_or_else(|e| e.into_inner()) = Some(code);
            mm.busy.store(false, Ordering::SeqCst);
        });
        task
    })
}

/// poll：返回 JSON。转义最少化（控制字符与引号/反斜杠）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_poll(
    env: *mut JNIEnv,
    _c: jclass,
    machine: jlong,
) -> jstring {
    let j = guard("poll", "{}".to_string(), move || {
        let Some(m) = machine_of(machine) else {
            return "{}".to_string();
        };
        // 环形缓冲：保留最新 384KB（此前只增不减，大输出 \u00XX 转义 6 倍膨胀直接撑爆 Java 堆）
        const OUT_CAP: usize = 384 * 1024;
        let out = {
            let mut g = m.out.lock().unwrap_or_else(|e| e.into_inner());
            if g.len() > OUT_CAP {
                let drop = g.len() - OUT_CAP;
                g.drain(..drop);
            }
            g.clone()
        };
        let busy = m.busy.load(Ordering::SeqCst);
        let exit = *m.exit_code.lock().unwrap_or_else(|e| e.into_inner());
        let mut j = String::with_capacity(out.len() + 64);
        j.push_str("{\"busy\":");
        j.push_str(if busy { "true" } else { "false" });
        if let Some(c) = exit {
            j.push_str(&format!(",\"exit\":{c}"));
        }
        j.push_str(",\"output\":\"");
        for &b in &out {
            match b {
                b'\\' | b'"' => {
                    j.push('\\');
                    j.push(b as char);
                }
                b'\n' => j.push_str("\\n"),
                b'\r' => j.push_str("\\r"),
                b'\t' => j.push_str("\\t"),
                0x20..=0x7e => j.push(b as char),
                _ => {
                    j.push_str(&format!("\\u{:04x}", b));
                }
            }
        }
        j.push_str("\"}");
        j
    });
    mk_string(env, &j)
}

/// stop：标记失效并清槽位。条目在锁外 drop —— `Machine` 的析构会杀 guest 进程，
/// 持着表锁析构就是死锁（Appine 的 D1 踩过这个坑）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_stop(
    _env: *mut JNIEnv,
    _c: jclass,
    machine: jlong,
) {
    guard("stop", (), move || {
        let Some(i) = slot(machine) else { return };
        let taken = {
            let mut g = machines();
            if i < g.len() {
                g[i].take()
            } else {
                None
            }
        };
        drop(taken); // 锁已释放
        log::note(&format!("[ch] stop handle={machine}"));
    })
}

/// 最近一次失败的完整原因（多行，空串 = 没有失败）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_lastError(
    env: *mut JNIEnv,
    _c: jclass,
) -> jstring {
    let s = take_err();
    mk_string(env, &s)
}

/// 最近的原生日志（`max` 字节上限）。给终端「自检」和 `linux info` 用——
/// 有它就不需要连电脑抓 logcat。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_logTail(
    env: *mut JNIEnv,
    _c: jclass,
    max: jint,
) -> jstring {
    let n = if max <= 0 {
        16 * 1024
    } else {
        (max as usize).min(96 * 1024)
    };
    let s = guard("logTail", String::new(), move || log::tail(n));
    mk_string(env, &s)
}

/// 原生自检：so 已加载后由 Kotlin 侧 `action=info` / 终端「自检」按钮调用。
///
/// `window` 是 Tier1 恒等映射窗口（0x400000..64MB）在本进程里能否预留成功 ——
/// 这是「原生执行」能不能成立的前提，而 VMM 里永远测不出来（Android 的 ART/zygote
/// 可能已经占了那块地址，真机与 VM 是两套布局）。`tier1` 是原生执行成功次数。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_info(
    env: *mut JNIEnv,
    _c: jclass,
) -> jstring {
    let text = guard("info", "info: panic".to_string(), move || {
        let live = machines().iter().filter(|s| s.is_some()).count();
        // 未实现 syscall：真机上「命令没反应/莫名失败」的第一嫌疑。
        // 空串 = 这条会话里 guest 要的 syscall 全都实现了。
        let unimpl = ch_kernel::syscall::unimplemented_report();
        // arena / pie：真机上「会话起不来」时最先要看的两项。
        // pie=1 表示走 static PIE（arena 由内核挑）；pie=0 且 arena=0 表示回落 flat 解释器。
        let (arena, pie) = ch_kernel::last_arena();
        format!(
            "abi={} arena={:#x} pie={} tier1={} fallback={} machines={} v={}
unimpl_total={} unimpl={}",
            std::env::consts::ARCH,
            arena,
            if pie { 1 } else { 0 },
            ch_host::native::tier1_hits(),
            ch_host::native::tier1_fallbacks(),
            live,
            env!("CARGO_PKG_VERSION"),
            ch_kernel::syscall::unimplemented_total(),
            if unimpl.is_empty() { "none" } else { &unimpl },
        )
    });
    mk_string(env, &text)
}
