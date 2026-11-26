//! 内核态 TTY / PTY 与线路规程（line discipline）。
//!
//! 这一层是 Appine 终端架构的关键：宿主拿 PTY **主端**，会话 leader 拿从端做
//! stdin/stdout/stderr 与 controlling terminal。宿主喂进来的字节先过线路规程：
//! ECHO 回显、ICANON 攒行、ISIG 把 ^C 变成 SIGINT 发给前台进程组；guest 的输出
//! 进 out 队列，宿主用 take_output 取走。
//!
//! 之前 ChengYan 没有这一层：stdin 是一次性注入的 Cursor，stdout 是环形缓冲，
//! `ioctl` 恒 ENOTTY，`^C` 只能整机重建 —— 那是命令执行器，不是终端。
//!
//! 这里是纯状态机，不依赖宿主 API，便于单测；与主端的连接放在 `console.rs`。

use std::collections::VecDeque;

/// termios 本地标志位（asm-generic 的 c_lflag 子集）。
pub const ISIG: u32 = 0x0000_0001;
pub const ICANON: u32 = 0x0000_0002;
pub const ECHO: u32 = 0x0000_0008;
pub const IEXTEN: u32 = 0x0000_8000;

/// 默认线路规程：规范模式 + 回显 + 信号字符。
pub const DEFAULT_LFLAG: u32 = ISIG | ICANON | ECHO | IEXTEN;

/// 线路规程识别出的、需要投递给前台进程组的信号（aarch64 generic 信号号）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LineSignal {
    /// ^C (0x03) → SIGINT(2)
    Intr,
    /// ^\ (0x1c) → SIGQUIT(3)
    Quit,
    /// ^Z (0x1a) → SIGTSTP(20)
    Stop,
}

impl LineSignal {
    pub fn number(self) -> i64 {
        match self {
            LineSignal::Intr => 2,
            LineSignal::Quit => 3,
            LineSignal::Stop => 20,
        }
    }
}

/// 窗口尺寸（`struct winsize` 的四 u16，guest 视角）。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Winsize {
    pub row: u16,
    pub col: u16,
    pub xpixel: u16,
    pub ypixel: u16,
}

impl Winsize {
    pub fn new(row: u16, col: u16) -> Self {
        Self { row, col, xpixel: 0, ypixel: 0 }
    }

    /// 按 native endian 写进 guest 的 8 字节结构。
    pub fn to_bytes(self) -> [u8; 8] {
        let mut b = [0u8; 8];
        b[0..2].copy_from_slice(&self.row.to_ne_bytes());
        b[2..4].copy_from_slice(&self.col.to_ne_bytes());
        b[4..6].copy_from_slice(&self.xpixel.to_ne_bytes());
        b[6..8].copy_from_slice(&self.ypixel.to_ne_bytes());
        b
    }
}

/// 一个 TTY（PTY 的两端共享同一个 Tty 对象，与 Appine 一致）。
pub struct Tty {
    /// 尚未被读走的规范化输入行（含结尾 `\n`）。
    input: VecDeque<u8>,
    /// 当前正在攒的规范行（未回车前不进 input）。
    line: Vec<u8>,
    /// 等待宿主取走的输出。
    output: VecDeque<u8>,
    pub lflag: u32,
    pub winsize: Winsize,
    /// 会话 leader 的 pid；Some 表示该 tty 已成为某会话的 controlling terminal。
    pub session: Option<u64>,
    /// 前台进程组 pid；信号发给它而不是发给读 tty 的那个线程。
    pub fg: Option<u64>,
    /// 打开的从端数量；master 检测会话结束用（attached && slaves == 0）。
    pub slaves: u32,
    /// EOF 只生效一次：^D 之后必须重新有内容才能再产生一个 EOF。
    eof_pending: bool,
    /// 输入队列容量，满了就丢弃新字节（不阻塞宿主线程）。
    input_limit: usize,
    /// 输出队列容量，超过就丢最老的（终端显示只需要尾部）。
    output_limit: usize,
}

impl Default for Tty {
    fn default() -> Self {
        Self::new()
    }
}

impl Tty {
    pub fn new() -> Self {
        Self {
            input: VecDeque::new(),
            line: Vec::new(),
            output: VecDeque::new(),
            lflag: DEFAULT_LFLAG,
            winsize: Winsize::new(24, 80),
            session: None,
            fg: None,
            slaves: 0,
            eof_pending: false,
            input_limit: 64 * 1024,
            output_limit: 4 * 1024 * 1024,
        }
    }

    /// 输入队列还能放多少字节。宿主用它做背压（Appine 的 `input_room`）。
    pub fn input_room(&self) -> usize {
        self.input_limit.saturating_sub(self.input.len() + self.line.len())
    }

    /// 宿主把字节喂给主端。返回线路规程产生的信号列表。
    ///
    /// 顺序很重要：ISIG 字符先处理并**不回显**（^C 显示在屏幕上是应用自己的事），
    /// ECHO 只作用于真正进行的普通字符。
    pub fn write_input(&mut self, data: &[u8]) -> Vec<LineSignal> {
        let mut sigs = Vec::new();
        for &c in data {
            if self.lflag & ISIG != 0 {
                let s = match c {
                    0x03 => Some(LineSignal::Intr),
                    0x1c => Some(LineSignal::Quit),
                    0x1a => Some(LineSignal::Stop),
                    _ => None,
                };
                if let Some(s) = s {
                    sigs.push(s);
                    self.line.clear();
                    continue;
                }
            }
            if self.lflag & IEXTEN != 0 && c == 0x15 {
                // ^U：擦掉整行，回显一个 ^U
                self.line.clear();
                if self.lflag & ECHO != 0 {
                    self.echo(b"^U");
                }
                continue;
            }
            if c == 0x7f || c == 0x08 {
                // 退格
                if self.line.pop().is_some() && self.lflag & ECHO != 0 {
                    self.echo(b"\x08 \x08");
                }
                continue;
            }
            if c == b'\r' || c == b'\n' {
                if self.lflag & ECHO != 0 {
                    self.echo(b"\r\n");
                }
                if self.lflag & ICANON != 0 {
                    self.line.push(b'\n');
                    self.push_input();
                } else {
                    self.input.push_back(b'\n');
                }
                continue;
            }
            if c == 0x04 && self.lflag & ICANON != 0 {
                // ^D：空行时是一次 EOF；非空行时先提交当前行。
                if self.line.is_empty() {
                    self.eof_pending = true;
                } else {
                    self.push_input();
                }
                continue;
            }
            if self.lflag & ECHO != 0 {
                self.echo(&[c]);
            }
            if self.lflag & ICANON != 0 {
                if self.line.len() < self.input_limit {
                    self.line.push(c);
                }
            } else {
                self.input.push_back(c);
            }
        }
        sigs
    }

    fn echo(&mut self, b: &[u8]) {
        for &c in b {
            if self.output.len() >= self.output_limit {
                self.output.pop_front();
            }
            self.output.push_back(c);
        }
    }

    fn push_input(&mut self) {
        let line = std::mem::take(&mut self.line);
        for c in line {
            if self.input.len() >= self.input_limit {
                break;
            }
            self.input.push_back(c);
        }
    }

    /// guest read(0, buf, n)：规范模式一次最多返回一行。
    /// 返回 0 表示 EOF（^D），`None` 表示暂时没有数据（调用方应阻塞/让出）。
    pub fn read(&mut self, max: usize) -> Option<Vec<u8>> {
        if self.input.is_empty() {
            if self.eof_pending {
                self.eof_pending = false;
                return Some(Vec::new());
            }
            return None;
        }
        let n = max.min(self.input.len());
        // 规范模式只给到第一个换行（含换行）
        let n = if self.lflag & ICANON != 0 {
            self.input.iter().take(n).position(|&c| c == b'\n').map_or(n, |i| i + 1)
        } else {
            n
        };
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.input.pop_front().unwrap());
        }
        Some(out)
    }

    /// guest write(1/2, buf) → 输出队列。
    pub fn write_output(&mut self, data: &[u8]) {
        self.echo(data);
    }

    /// 宿主取输出；超过 max 的部分留在队列里。
    pub fn take_output(&mut self, max: usize) -> Option<Vec<u8>> {
        if self.output.is_empty() {
            return None;
        }
        let n = max.min(self.output.len());
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.output.pop_front().unwrap());
        }
        Some(out)
    }

    pub fn has_output(&self) -> bool {
        !self.output.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_echo_and_line() {
        let mut t = Tty::new();
        let sig = t.write_input(b"echo hi\r");
        assert!(sig.is_empty());
        // 回显先进输出队列
        assert_eq!(t.take_output(64).unwrap(), b"echo hi\r\n");
        assert_eq!(t.read(64), Some(b"echo hi\n".to_vec()));
        assert_eq!(t.read(64), None);
    }

    #[test]
    fn ctrl_c_becomes_sigint_and_drops_partial_line() {
        let mut t = Tty::new();
        t.write_input(b"half");
        let sig = t.write_input(b"\x03");
        assert_eq!(sig, vec![LineSignal::Intr]);
        assert_eq!(sig[0].number(), 2);
        assert_eq!(t.read(64), None, "^C 必须丢掉没提交的行");
        // ^C 本身不回显
        assert_eq!(t.take_output(64).unwrap(), b"half");
    }

    #[test]
    fn ctrl_d_is_eof() {
        let mut t = Tty::new();
        t.write_input(b"\x04");
        assert_eq!(t.read(64), Some(Vec::new()));
        assert_eq!(t.read(64), None, "EOF 只生效一次");
    }

    #[test]
    fn backspace_and_ctrl_u() {
        let mut t = Tty::new();
        t.write_input(b"ab\x7fc\r");
        assert_eq!(t.read(64).unwrap(), b"ac\n");
        t.write_input(b"junk\x15ok\r");
        assert_eq!(t.read(64).unwrap(), b"ok\n");
    }

    #[test]
    fn output_queue_and_winsize() {
        let mut t = Tty::new();
        t.write_output(b"hello");
        assert_eq!(t.take_output(3).unwrap(), b"hel");
        assert_eq!(t.take_output(8).unwrap(), b"lo");
        assert_eq!(t.take_output(8), None);
        assert_eq!(Winsize::new(40, 120).to_bytes()[0..2], 40u16.to_ne_bytes());
    }
}
