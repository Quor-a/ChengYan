//! 桌面验证入口：直接跑一行命令（走 host 侧 shell 编排）。
//!
//! 用法：cargo run --release -p ch-host --example shell -- <busybox> <rootfs> "<命令>"
//! 例：  ... /tmp/bb /tmp/jail "cd /etc && ls | grep os | wc -l"

use ch_host::{Session, SharedOut};
use std::sync::{Arc, Mutex};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: shell <busybox> <rootfs> <command>");
        std::process::exit(2);
    }
    let s = match Session::new(std::path::Path::new(&args[1]), std::path::Path::new(&args[2])) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("session: {e}");
            std::process::exit(2);
        }
    };
    let cmd = args[3..].join(" ");
    let out: SharedOut = Arc::new(Mutex::new(Vec::new()));
    // 支持多行（用 $'a\nb' 传入）：逐行执行，验证 cwd/env 跨命令持久
    let mut code = 0;
    for line in cmd.split('\n') {
        if line.trim().is_empty() {
            continue;
        }
        code = s.run_line(line, &out);
    }
    let txt = String::from_utf8_lossy(&out.lock().unwrap()).into_owned();
    print!("{txt}");
    eprintln!("[ch-exit {code}]");
    // 原生执行证据：`hits>0` 且 `fallback=0` 才说明这条命令真的跑在 Tier1 上
    // （PIE 之后 arena 是内核挑的地址，这两项就是「原生通道在动态基址下仍成立」的凭据）。
    eprintln!(
        "[ch-tier1 hits={} fallback={}]",
        ch_host::native::tier1_hits(),
        ch_host::native::tier1_fallbacks()
    );
    // 未实现 syscall 报表：VM 矩阵里这一行就是「内核还缺什么」的直接证据，
    // 在真机上同样的数字由 `linux info` / 终端「自检」给出。
    let ui = ch_kernel::syscall::unimplemented_report();
    if !ui.is_empty() {
        eprintln!(
            "[ch-unimpl total={} {ui}]",
            ch_kernel::syscall::unimplemented_total()
        );
    }
}
