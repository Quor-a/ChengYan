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
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

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
    let mut g = MACHINES.lock().unwrap();
    g.push(Some(m));
    g.len() as jlong // idx+1，0 保留为非法
}

/// exec：整行走宿主侧 shell 解析执行（管道/重定向/条件/变量/glob/内建 cd 等）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_exec(
    env: *mut JNIEnv, _c: jclass, machine: jlong, cmd: jstring,
) -> jlong {
    let cmd: String = match get_string(env, cmd) { Some(s) => s, None => return -1 };
    let m = { let g = MACHINES.lock().unwrap(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return -2 } };
    if m.busy.swap(true, Ordering::SeqCst) { return -3; } // 忙
    *m.exit_code.lock().unwrap() = None;
    // 输出缓冲**不清空**：终端要保留会话历史。
    // 旧实现每条命令都 clear，于是 `cd /tmp`（无输出）之后屏幕全空、
    // 前一条 `ls` 的结果被抹掉，用户看到的就是「终端什么都留不住」。
    // 改为追加，并回显命令行，让终端页与 AI 工具都能看到完整上下文。
    m.out.lock().unwrap().extend_from_slice(format!("$ {cmd}\n").as_bytes());
    let mm = m.clone();
    let task = m.next_task.fetch_add(1, Ordering::SeqCst) as jlong;
    std::thread::spawn(move || {
        // 走宿主侧 shell：支持 `|` `>` `>>` `<` `2>&1` `&&` `||` `;` `$VAR` `*` `cd` 等。
        // 旧实现按空格切开直接 `busybox <argv0>`，等于把 shell 语法全当普通参数，
        // 真机上表现为「管道/重定向/cd 全不work」，所以必须走 run_line。
        let code = mm.session.run_line(&cmd, &mm.out);
        mm.out.lock().unwrap().extend_from_slice(format!("\n[ch-exit {code}]\n").as_bytes());
        *mm.exit_code.lock().unwrap() = Some(code);
        mm.busy.store(false, Ordering::SeqCst);
    });
    task
}

/// poll：返回 JSON。转义最少化（控制字符与引号/反斜杠）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_poll(
    env: *mut JNIEnv, _c: jclass, machine: jlong,
) -> jstring {
    let m = { let g = MACHINES.lock().unwrap(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return mk_string(env, "{}") } };
    // 环形缓冲：保留最新 384KB（此前只增不减，大输出 \u00XX 转义 6 倍膨胀直接撑爆 Java 堆）
    const OUT_CAP: usize = 384 * 1024;
    let out = {
        let mut g = m.out.lock().unwrap();
        if g.len() > OUT_CAP { let drop = g.len() - OUT_CAP; g.drain(..drop); }
        g.clone()
    };
    let busy = m.busy.load(Ordering::SeqCst);
    let exit = *m.exit_code.lock().unwrap();
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
    let mut g = MACHINES.lock().unwrap();
    let i = ptr_to_idx(machine) - 1;
    if i < g.len() { g[i] = None; }
}
