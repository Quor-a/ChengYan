//! 澄言 Linux CPU 引擎 —— Tier 0 解释器。
//!
//! 只认内存和指令，不懂进程/内核（分层纪律）。svc 不在此解析语义，
//! 抛 `ExitReason::Syscall` 给上层分派 —— 与 appine engine↔kernel
//! 「exit reason / cpu_run」协议同构。宿主侧把内存后端注入本层。

pub mod interp;

/// 引擎退出原因（appine 协议：svc / fault / undef / …）。
pub enum ExitReason {
    Syscall,
    Undef(u32),
    Fault(u64),
}

pub struct Cpu {
    pub x: [u64; 31], // x0..x30
    pub sp: u64,
    pub pc: u64,
    pub nzcv: u32,
}

impl Cpu {
    pub fn new() -> Self {
        Self { x: [0; 31], sp: 0, pc: 0, nzcv: 0 }
    }
}

/// 执行环境：把「取指」与「内存访问」抽象出来，kernel 侧的 flat 内存与
/// 未来 VMA/soft-MMU（P4）都实现这个 trait。
pub trait MemBackend {
    fn read32(&self, addr: u64) -> Result<u32, String>;
    fn read64(&self, addr: u64) -> Result<u64, String>;
    fn write64(&mut self, addr: u64, v: u64) -> Result<(), String>;
}

#[cfg(test)]
pub(crate) struct FlatMem(pub Vec<u8>);

#[cfg(test)]
impl MemBackend for FlatMem {
    fn read32(&self, addr: u64) -> Result<u32, String> {
        let a = addr as usize;
        if a + 4 > self.0.len() { return Err(format!("read32 oob {addr:#x}")); }
        Ok(u32::from_le_bytes(self.0[a..a + 4].try_into().unwrap()))
    }
    fn read64(&self, addr: u64) -> Result<u64, String> {
        let a = addr as usize;
        if a + 8 > self.0.len() { return Err(format!("read64 oob {addr:#x}")); }
        Ok(u64::from_le_bytes(self.0[a..a + 8].try_into().unwrap()))
    }
    fn write64(&mut self, addr: u64, v: u64) -> Result<(), String> {
        let a = addr as usize;
        if a + 8 > self.0.len() { return Err(format!("write64 oob {addr:#x}")); }
        self.0[a..a + 8].copy_from_slice(&v.to_le_bytes());
        Ok(())
    }
}
