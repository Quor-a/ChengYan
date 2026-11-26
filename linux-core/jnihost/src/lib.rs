//! 澄言 Linux —— Android JNI 宿主（cdylib: libchlinux.so）。
//!
//! 会话式 API（AI 自主编排，不内置固定流程）：
//!   start(dataDir) → machine 句柄（busybox 已装载、rootfs = dataDir/rootfs）
//!   exec(machine, cmd) → 任务号（后台线程跑；同一 machine 同时一个任务，忙时返回 -1）
//!   poll(machine) → JSON {busy, exit, output}（输出为环形缓冲，保留最新 384KB）
//!   interrupt(machine) → 终止当前任务（强杀工作线程句柄标记；v1 以重启 machine 实现硬中断）
//!   stop(machine) → 释放
//!
//! 分层纪律：本层只做 JNI 胶水 + 任务生命周期，不碰指令与 syscall。
//!
//! JNI 绑定自己抄了一份（见 `jni_min.rs`），不依赖 `jni` crate —— 后者的
//! 传递依赖要在**宿主平台**链接一个二进制，交叉编译机上缺宿主链接器时整包编不出来。

mod jni_min;

use ch_host::Session;
use jni_min::{jclass, jlong, jstring, JNIEnv, JNINativeInterface_};
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
    // 这里绝对不能 panic：`extern "system"` 里 unwind 出去会直接 abort 进程，
    // 表现成「装得上但一加载就闪退」。窗口预留失败也只是降级到 Tier0，不该致命。
    let _ = catch_unwind(AssertUnwindSafe(ch_kernel::reserve_guest_window));
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

fn ptr_to_idx(p: jlong) -> usize { p as usize }

#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_start(
    env: *mut JNIEnv, _c: jclass, data_dir: jstring,
) -> jlong {
    let dir: String = match get_string(env, data_dir) { Some(s) => s, None => return -1 };
    // 尽早占住 guest arena 窗口（Tier1 恒等映射的前提），见 reserve_guest_window 注释
    ch_kernel::reserve_guest_window();
    let root = PathBuf::from(&dir).join("rootfs");
    // 优先用 rootfs 自带的静态 busybox（Alpine aarch64 官方构建）；老包回退 assets busybox
    let busybox = match std::fs::metadata(root.join("bin/busybox.static")) {
        Ok(m) if m.len() > 0 => root.join("bin/busybox.static"),
        _ => PathBuf::from(&dir).join("busybox"),
    };
    if std::fs::create_dir_all(&root).is_err() { return -2; }
    let session = match Session::new(&busybox, &root) { Ok(s) => s, Err(_) => return -3 };
    let m = Arc::new(Machine {
        session,
        out: Arc::new(Mutex::new(Vec::new())),
        busy: Arc::new(AtomicBool::new(false)),
        exit_code: Arc::new(Mutex::new(None)),
        alive: Arc::new(AtomicBool::new(true)),
        next_task: AtomicU64::new(1),
    });
    let mut g = machines();
    g.push(Some(m));
    g.len() as jlong // idx+1，0 保留为非法
}

/// exec：整行走宿主侧 shell 解析执行（管道/重定向/条件/变量/glob/内建 cd 等）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_exec(
    env: *mut JNIEnv, _c: jclass, machine: jlong, cmd: jstring,
) -> jlong {
    let cmd: String = match get_string(env, cmd) { Some(s) => s, None => return -1 };
    let m = { let g = machines(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return -2 } };
    if m.busy.swap(true, Ordering::SeqCst) { return -3; } // 忙
    *m.exit_code.lock().unwrap_or_else(|e| e.into_inner()) = None;
    // 输出缓冲**不清空**：终端要保留会话历史。
    // 旧实现每条命令都 clear，于是 `cd /tmp`（无输出）之后屏幕全空、
    // 前一条 `ls` 的结果被抹掉，用户看到的就是「终端什么都留不住」。
    // 改为追加，并回显命令行，让终端页与 AI 工具都能看到完整上下文。
    m.out.lock().unwrap_or_else(|e| e.into_inner()).extend_from_slice(format!("$ {cmd}\n").as_bytes());
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
                let msg = panic_msg(&e);
                let mut g = mm.out.lock().unwrap_or_else(|e| e.into_inner());
                g.extend_from_slice(format!("
[ch-panic] {msg}
").as_bytes());
                139 // 128+11(SIGSEGV)：guest 侧崩了，明确标记
            });
        mm.out.lock().unwrap_or_else(|e| e.into_inner())
            .extend_from_slice(format!("
[ch-exit {code}]
").as_bytes());
        *mm.exit_code.lock().unwrap_or_else(|e| e.into_inner()) = Some(code);
        mm.busy.store(false, Ordering::SeqCst);
    });
    task
}

/// poll：返回 JSON。转义最少化（控制字符与引号/反斜杠）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_poll(
    env: *mut JNIEnv, _c: jclass, machine: jlong,
) -> jstring {
    let m = { let g = machines(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return mk_string(env, "{}") } };
    // 环形缓冲：保留最新 384KB（此前只增不减，大输出 \u00XX 转义 6 倍膨胀直接撑爆 Java 堆）
    const OUT_CAP: usize = 384 * 1024;
    let out = {
        let mut g = m.out.lock().unwrap_or_else(|e| e.into_inner());
        if g.len() > OUT_CAP { let drop = g.len() - OUT_CAP; g.drain(..drop); }
        g.clone()
    };
    let busy = m.busy.load(Ordering::SeqCst);
    let exit = *m.exit_code.lock().unwrap_or_else(|e| e.into_inner());
    let mut j = String::with_capacity(out.len() + 64);
    j.push_str("{\"busy\":");
    j.push_str(if busy { "true" } else { "false" });
    if let Some(c) = exit { j.push_str(&format!(",\"exit\":{c}")); }
    j.push_str(",\"output\":\"");
    for &b in &out {
        match b {
            b'\\' | b'"' => { j.push('\\'); j.push(b as char); }
            b'\n' => j.push_str("\\n"),
            b'\r' => j.push_str("\\r"),
            b'\t' => j.push_str("\\t"),
            0x20..=0x7e => j.push(b as char),
            _ => { j.push_str(&format!("\\u{:04x}", b)); }
        }
    }
    j.push_str("\"}");
    mk_string(env, &j)
}

/// stop：标记失效并清槽位（运行中线程自然结束后丢弃）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_stop(
    _env: *mut JNIEnv, _c: jclass, machine: jlong,
) {
    let mut g = machines();
    let i = ptr_to_idx(machine) - 1;
    if i < g.len() { g[i] = None; }
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

/// 原生自检：so 已加载后由 Kotlin 侧 `action=info` / 终端「自检」按钮调用。
///
/// `window` 是 Tier1 恒等映射窗口（0x400000..64MB）在本进程里能否预留成功 ——
/// 这是「原生执行」能不能成立的前提，而 VMM 里永远测不出来（Android 的 ART/zygote
/// 可能已经占了那块地址，真机与 VM 是两套布局）。`tier1` 是原生执行成功次数。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_info(
    env: *mut JNIEnv, _c: jclass,
) -> jstring {
    let text = catch_unwind(AssertUnwindSafe(|| {
        let live = machines().iter().filter(|s| s.is_some()).count();
        format!(
            "abi={} window={} tier1={} fallback={} machines={} v={}",
            std::env::consts::ARCH,
            if ch_kernel::window_reserved() { "ok" } else { "failed" },
            ch_host::native::tier1_hits(),
            ch_host::native::tier1_fallbacks(),
            live,
            env!("CARGO_PKG_VERSION"),
        )
    }))
    .unwrap_or_else(|_| "info: panic".to_string());
    mk_string(env, &text)
}
