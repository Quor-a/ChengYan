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

use ch_host::Session;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

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

fn mk_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_start(
    mut env: JNIEnv, _c: JClass, data_dir: JString,
) -> jlong {
    let dir: String = match env.get_string(&data_dir) { Ok(s) => s.into(), Err(_) => return -1 };
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

/// exec：tokenize 后 argv = [busybox, applet, args...]，后台线程执行。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_exec(
    mut env: JNIEnv, _c: JClass, machine: jlong, cmd: JString,
) -> jlong {
    let cmd: String = match env.get_string(&cmd) { Ok(s) => s.into(), Err(_) => return -1 };
    let m = { let g = MACHINES.lock().unwrap(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return -2 } };
    if m.busy.swap(true, Ordering::SeqCst) { return -3; } // 忙
    *m.exit_code.lock().unwrap() = None;
    m.out.lock().unwrap().clear();
    let mm = m.clone();
    let task = m.next_task.fetch_add(1, Ordering::SeqCst) as jlong;
    std::thread::spawn(move || {
        let argv0 = "busybox".to_string();
        let mut tokens = ch_host::tokenize(&cmd);
        if tokens.is_empty() {
            mm.out.lock().unwrap().extend_from_slice(b"[ch] empty command\n");
            *mm.exit_code.lock().unwrap() = Some(2);
            mm.busy.store(false, Ordering::SeqCst);
            return;
        }
        let applet = tokens.remove(0);
        let mut argv = vec![argv0, applet];
        argv.extend(tokens);
        let code = match mm.session.run(&argv, &mm.out) {
            Ok(c) => c,
            Err(e) => {
                mm.out.lock().unwrap().extend_from_slice(format!("[ch] {e}\n").as_bytes());
                127
            }
        };
        mm.out.lock().unwrap().extend_from_slice(format!("\n[ch-exit {code}]\n").as_bytes());
        *mm.exit_code.lock().unwrap() = Some(code);
        mm.busy.store(false, Ordering::SeqCst);
    });
    task
}

/// poll：返回 JSON。转义最少化（控制字符与引号/反斜杠）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_poll(
    mut env: JNIEnv, _c: JClass, machine: jlong,
) -> jstring {
    let m = { let g = MACHINES.lock().unwrap(); match g.get(ptr_to_idx(machine) - 1) { Some(Some(m)) => m.clone(), _ => return mk_string(&mut env, "{}") } };
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
    mk_string(&mut env, &j)
}

/// stop：标记失效并清槽位（运行中线程自然结束后丢弃）。
#[no_mangle]
pub extern "system" fn Java_com_chengyan_app_data_LinuxNative_stop(
    _env: JNIEnv, _c: JClass, machine: jlong,
) {
    let mut g = MACHINES.lock().unwrap();
    let i = ptr_to_idx(machine) - 1;
    if i < g.len() { g[i] = None; }
}
