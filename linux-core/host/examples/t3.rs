use ch_host::{Session, SharedOut};
use std::sync::{Arc, Mutex};
fn main() {
    let a0 = std::env::args().nth(1).unwrap_or("busybox".into());
    let s = Session::new(std::path::Path::new("/tmp/bin/busybox.static"), std::path::Path::new("/tmp/jail2")).unwrap();
    let argv = vec![a0.clone(), "ls".into(), "/".into()];
    let out: SharedOut = Arc::new(Mutex::new(Vec::new()));
    let code = s.run(&argv, &out).unwrap();
    println!("argv0={a0} exit={code}\n{}", String::from_utf8_lossy(&out.lock().unwrap()));
}
