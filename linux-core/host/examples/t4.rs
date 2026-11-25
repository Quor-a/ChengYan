// t4：把命令行参数原样作为 busybox argv 运行（冒烟/复现入口）
use ch_host::{Session, SharedOut};
use std::sync::{Arc, Mutex};
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let s = Session::new(std::path::Path::new("/tmp/bin/busybox.static"), std::path::Path::new("/tmp/jail2")).unwrap();
    let mut argv = vec!["busybox".to_string()];
    argv.extend(args);
    let out: SharedOut = Arc::new(Mutex::new(Vec::new()));
    let code = s.run(&argv, &out).unwrap();
    print!("{}", String::from_utf8_lossy(&out.lock().unwrap()));
    println!("[t4] {:?} -> exit {code}", argv);
    std::process::exit(code as i32);
}
