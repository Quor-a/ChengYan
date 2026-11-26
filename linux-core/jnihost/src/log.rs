//! 原生日志通道：Android 上把 fd 2 接进 logcat **和**内存环形缓冲。
//!
//! ## 为什么必须有这一层
//!
//! App 进程的 `fd 2` 在 Android 上通常指向 `/dev/null`。于是 native 侧所有诊断——
//! Tier1 为什么回落到解释器、ELF/`mmap` 为什么失败、panic 在哪一行——**在真机上全部
//! 掉进黑洞**。之前三轮真机排障全靠猜，根子就在这里。
//!
//! 做法与 Appine（`hosts/android/src/lib.rs` 的 `redirect_stderr`）一致：`pipe` +
//! 专用读取线程 → `__android_log_write`。在本项目上再加一层收益：同一个读取线程
//! 顺手把每行写进内存环形缓冲，Kotlin 通过 `logTail(n)` 直接取出来显示在终端页 /
//! `linux info`，**不需要连电脑、不需要 adb**。
//!
//! ## 纪律
//!
//! - 只重定向 `fd 2`（stderr）。guest 的 stdout/stderr 走我们自己捕获的 writer，
//!   与宿主 fd 1 无关，互不影响。
//! - 读取线程**绝不往 stderr 写**，否则 pipe 自环。
//! - 任何一步失败都静默返回：日志通道不能成为新的失败源。
//! - 非 Android（桌面 CLI / 交叉编译的 VM 测试 shell）不改 fd，只做 no-op——
//!   那里 stderr 本来就看得见，重定向反而会把诊断吞掉。

use std::sync::{Mutex, Once};

/// 环形缓冲上限。够装一次启动全过程（几百行），又不至于在低内存机型上抢堆。
const CAP: usize = 96 * 1024;

static RING: Mutex<Vec<u8>> = Mutex::new(Vec::new());
static ONCE: Once = Once::new();

fn push_line(line: &[u8]) {
    let mut g = RING.lock().unwrap_or_else(|e| e.into_inner());
    g.extend_from_slice(line);
    g.push(b'\n');
    if g.len() > CAP {
        let over = g.len() - CAP;
        // 对齐到下一个换行再切，否则缓冲永远以半行残句开头，读起来像乱码
        let cut = g[over..]
            .iter()
            .position(|&b| b == b'\n')
            .map(|i| over + i + 1)
            .unwrap_or(over);
        g.drain(..cut);
    }
}

/// 取最近 `max` 字节的原生日志。
pub fn tail(max: usize) -> String {
    let g = RING.lock().unwrap_or_else(|e| e.into_inner());
    let start = g.len().saturating_sub(max);
    let start = g[start..]
        .iter()
        .position(|&b| b == b'\n')
        .map(|i| start + i + 1)
        .unwrap_or(start);
    String::from_utf8_lossy(&g[start..]).into_owned()
}

/// 追加一行到环形缓冲（Kotlin 侧想留痕也可以，通过 `Java_..._note`）。
pub fn note(line: &str) {
    push_line(line.as_bytes());
}

/// 建立日志通道。可重复调用（内部 `Once`）。
pub fn init() {
    ONCE.call_once(|| unsafe { install() });
}

#[cfg(target_os = "android")]
unsafe fn install() {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(
            prio: i32,
            tag: *const core::ffi::c_char,
            text: *const core::ffi::c_char,
        ) -> i32;
    }
    extern "C" {
        fn pipe(fds: *mut i32) -> i32;
        fn dup2(old_fd: i32, new_fd: i32) -> i32;
        fn close(fd: i32) -> i32;
    }

    const ANDROID_LOG_INFO: i32 = 4;
    let mut fds = [0i32; 2];
    if pipe(fds.as_mut_ptr()) != 0 {
        return;
    }
    let (read_end, write_end) = (fds[0], fds[1]);

    // fd 2 被关掉的进程里 `pipe` 可能把读端或写端正好分配到 2：那样后面的 `dup2(wr, 2)`
    // 会把读端直接关掉，读取线程立刻 EOF，日志静默全丢。这种情况宁可不重定向。
    if read_end <= 2 || write_end <= 2 {
        close(read_end);
        close(write_end);
        return;
    }

    // 先起读取线程、再改 fd 2：spawn 失败时 stderr 还指在原来的地方，不会留下一个
    // 没人读的 pipe（写满 64KB 后任何 eprintln 都会永久阻塞）。
    let spawned = std::thread::Builder::new()
        .name("ch-log".into())
        .spawn(move || {
            use std::io::{BufRead, BufReader};
            use std::os::fd::FromRawFd;
            let tag = b"chengyan\0";
            let f = std::fs::File::from_raw_fd(read_end);
            /// 单行上限：native 侧日志都是短句，超长的只可能是误写二进制，
            /// 截断保住环形缓冲与 logcat（`split` 本身不设界）。
            const LINE_MAX: usize = 8 * 1024;
            for line in BufReader::new(f).split(b'\n').map_while(Result::ok) {
                let line = &line[..line.len().min(LINE_MAX)];
                push_line(line);
                if let Ok(text) = std::ffi::CString::new(line) {
                    __android_log_write(
                        ANDROID_LOG_INFO,
                        tag.as_ptr() as *const core::ffi::c_char,
                        text.as_ptr(),
                    );
                }
            }
        });
    if spawned.is_err() {
        close(read_end);
        close(write_end);
        return;
    }
    if dup2(write_end, 2) < 0 {
        // 关掉写端 → 读端收到 EOF，读取线程自然退出
        close(write_end);
        return;
    }
    if write_end != 2 {
        close(write_end);
    }
}

/// 桌面 / VM 测试：stderr 本来就看得见，不动它。
#[cfg(not(target_os = "android"))]
unsafe fn install() {}
