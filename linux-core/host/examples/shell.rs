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
}
