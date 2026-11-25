//! 澄言 Linux CLI 运行器（P1 验证入口）。
//!
//! 协议：engine 执行到 svc 抛 ExitReason::Syscall → kernel 分派 → 继续或退出。
//! 用法：ch-runner <guest.elf> [args…]

use ch_engine::{interp, Cpu, ExitReason, MemBackend};
use ch_kernel::{syscall, Process};

/// 把 kernel 的 flat guest 内存接成 engine 的 MemBackend。
/// P4 换 soft-MMU/TLB 时只换这里。
struct KernelMem<'a>(&'a mut Process);

impl<'a> MemBackend for KernelMem<'a> {
    fn read32(&self, addr: u64) -> Result<u32, String> {
        let mut b = [0u8; 4];
        self.0.mem.read(addr, &mut b)?;
        Ok(u32::from_le_bytes(b))
    }
    fn read64(&self, addr: u64) -> Result<u64, String> {
        let mut b = [0u8; 8];
        self.0.mem.read(addr, &mut b)?;
        Ok(u64::from_le_bytes(b))
    }
    fn write64(&mut self, addr: u64, v: u64) -> Result<(), String> {
        self.0.mem.write(addr, &v.to_le_bytes())
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: ch-runner <guest.elf>");
        std::process::exit(2);
    }
    let bytes = match std::fs::read(&args[1]) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("read {}: {e}", args[1]);
            std::process::exit(2);
        }
    };
    let mut proc = match Process::new(&bytes) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("load failed: {e}");
            std::process::exit(2);
        }
    };

    let mut cpu = Cpu::new();
    cpu.pc = proc.image.entry;
    cpu.sp = proc.image.stack_top;
    // 最小 auxv/argv：栈顶放 NULL argv 终结（guest 只 printf/exit 时足够）
    cpu.x[0] = 0; // argc
    cpu.x[1] = 0; // argv NULL

    let mut steps: u64 = 0;
    let mut km = KernelMem(&mut proc);
    loop {
        match interp::step(&mut cpu, &mut km) {
            Ok(None) => {}
            Ok(Some(ExitReason::Syscall)) => {
                if let Some(code) = syscall::handle(&mut proc, &mut cpu.x) {
                    eprintln!("\n[exit {}] steps={steps}", code);
                    if let Ok(d) = std::env::var("CH_DUMP") {
                        let mut it = d.split(':');
                        if let (Some(s), Some(e)) = (it.next(), it.next()) {
                            if let (Ok(s), Ok(e)) = (u64::from_str_radix(s.trim_start_matches("0x"), 16), u64::from_str_radix(e.trim_start_matches("0x"), 16)) {
                                let mut buf = vec![0u8; (e - s) as usize];
                                if proc.mem.read(s, &mut buf).is_ok() {
                                    for (k, chunk) in buf.chunks(32).enumerate() {
                                        let hexs: String = chunk.iter().map(|b| format!("{b:02x}")).collect();
                                        let asc: String = chunk.iter().map(|&b| if (32..127).contains(&b) { b as char } else { '.' }).collect();
                                        eprintln!("{:08x} {} {}", s + (k * 32) as u64, hexs, asc);
                                    }
                                }
                            }
                        }
                    }
                    std::process::exit((code & 0xff) as i32);
                }
            }
            Ok(Some(ExitReason::Undef(i))) => {
                eprintln!("\n[undef] instr={i:#010x} at pc={:#x} steps={steps}", cpu.pc - 4);
                if let Ok(d) = std::env::var("CH_DUMP") {
                    let mut it = d.split(':');
                    let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                    let l = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                    for a in (s..s + l).step_by(16) {
                        let mut b = [0u8; 16];
                        let _ = proc.mem.read(a, &mut b);
                        eprintln!("{a:#x}: {:02x?}", b);
                    }
                }
                std::process::exit(3);
            }
            Ok(Some(ExitReason::Fault(a))) => {
                eprintln!("\n[fault] at {a:#x} steps={steps}");
                std::process::exit(3);
            }
            Err(e) => {
                eprintln!("\n[mem error] {e} pc={:#x} steps={steps}", cpu.pc - 4);
                std::process::exit(3);
            }
        }
        steps += 1;
        if steps > 200_000_000 {
            eprintln!("\n[stuck] step budget exhausted");
            std::process::exit(4);
        }
    }
}
