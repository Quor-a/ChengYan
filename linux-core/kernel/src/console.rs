//! 宿主侧 PTY 主端 / console 注册表（Appine `kernel/src/terminal.rs` 的对应物）。
//!
//! 分层纪律：这里只持有 TTY 对象与信号事件，不碰宿主文件、不碰 JNI、不碰 UI。
//! 宿主只能做四件事：`open` 一个 console、往里 `feed` 字节、`take_output` 取输出、
//! `resize`。guest 侧通过 `Process::tty` 拿到同一个 Tty 作为从端。
//!
//! `feed()` 返回的信号由上层（内核/宿主）投递给前台进程组 —— 在这个内核里
//! 前台就是当前命令，所以上层把它变成"取消当前执行"，语义与 SIGINT 一致。

use std::sync::{Arc, Mutex, OnceLock};

use crate::tty::{LineSignal, Tty, Winsize};

/// 从端拿到的 tty 句柄，主端与 guest 共享。
pub type TtyRef = Arc<Mutex<Tty>>;

pub struct Console {
    pub id: u32,
    pub tty: TtyRef,
    /// 是否已有会话 attach 过；用于判断"会话已结束"（slave 全关且曾有会话）。
    pub attached: bool,
}

/// 进程内的 console 表。Android 上一个 app 进程可以有多个终端 tab，
/// 所以用表而不是单实例；当前只用 id 0，结构保留扩展位。
static CONSOLES: OnceLock<Mutex<Vec<Console>>> = OnceLock::new();

fn table() -> &'static Mutex<Vec<Console>> {
    CONSOLES.get_or_init(|| Mutex::new(Vec::new()))
}

fn find(id: u32) -> Option<Console> {
    table().lock().ok()?.iter().find(|c| c.id == id).map(|c| Console {
        id: c.id,
        tty: c.tty.clone(),
        attached: c.attached,
    })
}

/// 打开一个 console（PTY 主端），返回 id。窗口尺寸由宿主给出。
pub fn open(winsize: Winsize) -> Result<u32, String> {
    let mut t = table().lock().map_err(|_| "console 表锁中毒".to_string())?;
    let id = t.iter().map(|c| c.id).max().map_or(0, |m| m + 1);
    let mut tty = Tty::new();
    tty.winsize = winsize;
    t.push(Console { id, tty: Arc::new(Mutex::new(tty)), attached: false });
    Ok(id)
}

/// 取从端句柄给 guest 进程（fd 0/1/2 都指向它）。
pub fn slave(id: u32) -> Result<TtyRef, String> {
    let c = find(id).ok_or_else(|| format!("console {id} 不存在"))?;
    if let Ok(mut t) = c.tty.lock() {
        t.slaves += 1;
    }
    Ok(c.tty)
}

/// 标记该 console 已有会话 leader  attach（此后 slave 全关 = 会话结束）。
pub fn attach(id: u32) -> Result<(), String> {
    let mut tab = table().lock().map_err(|_| "console 表锁中毒".to_string())?;
    let c = tab.iter_mut().find(|c| c.id == id).ok_or_else(|| format!("console {id} 不存在"))?;
    c.attached = true;
    if let Ok(mut t) = c.tty.lock() {
        if t.session.is_none() {
            t.session = Some(1); // 单会话内核：leader 就是 pid 1
            t.fg = Some(1);
        }
    }
    Ok(())
}

/// 宿主喂字节给主端。返回线路规程产生的信号，交给上层投递。
pub fn feed(id: u32, data: &[u8]) -> Result<Vec<LineSignal>, String> {
    let c = find(id).ok_or_else(|| format!("console {id} 不存在"))?;
    let mut t = c.tty.lock().map_err(|_| "tty 锁中毒".to_string())?;
    Ok(t.write_input(data))
}

/// 宿主取 guest 输出（含 ECHO）。没有输出时返回 None，调用方应等待而不是空转。
pub fn take_output(id: u32, max: usize) -> Result<Option<Vec<u8>>, String> {
    let c = find(id).ok_or_else(|| format!("console {id} 不存在"))?;
    let mut t = c.tty.lock().map_err(|_| "tty 锁中毒".to_string())?;
    Ok(t.take_output(max))
}

pub fn resize(id: u32, w: Winsize) -> Result<(), String> {
    let c = find(id).ok_or_else(|| format!("console {id} 不存在"))?;
    let mut t = c.tty.lock().map_err(|_| "tty 锁中毒".to_string())?;
    t.winsize = w;
    Ok(())
}

pub fn winsize(id: u32) -> Result<Winsize, String> {
    let c = find(id).ok_or_else(|| format!("console {id} 不存在"))?;
    let t = c.tty.lock().map_err(|_| "tty 锁中毒".to_string())?;
    Ok(t.winsize)
}

/// 会话是否结束：曾经 attach 过、且所有从端都已关闭。
pub fn ended(id: u32) -> bool {
    match find(id) {
        Some(c) => {
            c.attached
                && c.tty.lock().map(|t| t.slaves == 0).unwrap_or(false)
        }
        None => true,
    }
}

pub fn close(id: u32) {
    let mut tab = match table().lock() {
        Ok(t) => t,
        Err(_) => return,
    };
    tab.retain(|c| c.id != id);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_feed_take_roundtrip() {
        let id = open(Winsize::new(40, 100)).unwrap();
        assert_eq!(winsize(id).unwrap().col, 100);
        let tty = slave(id).unwrap();
        assert_eq!(tty.lock().unwrap().slaves, 1);
        assert!(feed(id, b"echo tty\r").unwrap().is_empty());
        assert_eq!(take_output(id, 64).unwrap().unwrap(), b"echo tty\r\n");
        assert_eq!(tty.lock().unwrap().read(64).unwrap(), b"echo tty\n");
        attach(id).unwrap();
        assert!(!ended(id));
        close(id);
        assert!(ended(id));
    }

    #[test]
    fn ctrl_c_signal_surfaces_to_host() {
        let id = open(Winsize::default()).unwrap();
        let sig = feed(id, b"\x03").unwrap();
        assert_eq!(sig.len(), 1);
        assert_eq!(sig[0].number(), 2);
        close(id);
    }
}
