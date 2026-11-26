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
        let v = u64::from_le_bytes(b);
        if std::env::var("CH_TRACE").is_ok() {
            if let Ok(w) = std::env::var("CH_WATCH") {
                let mut it = w.split(':');
                let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                let e = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                if (s..e).contains(&addr) {
                    eprintln!("  [rd64 {addr:#x} -> {v:#x}]");
                }
            }
        }
        Ok(v)
    }
    fn write64(&mut self, addr: u64, v: u64) -> Result<(), String> {
        if std::env::var("CH_TRACE").is_ok() {
            if let Ok(w) = std::env::var("CH_WATCH") {
                let mut it = w.split(':');
                let s = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                let e = u64::from_str_radix(it.next().unwrap().trim_start_matches("0x"), 16).unwrap();
                if (s..e).contains(&addr) {
                    eprintln!("  [wr64 {addr:#x} <- {v:#x}]");
                }
            }
        }
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

    // TLS 区（musl/glibc 经 tpidr_el0 访问 pthread 自身/errno）：64KB 全零区，
    // tpidr 指向中段，负偏移（self 指针）与正偏移（errno 等）都落在区内。
    let tls_base = proc.image.stack_top - 0x2_0000;
    cpu.tpidr = tls_base + 0x8000;

    // 初始栈（Linux aarch64 ABI）：argc, argv[0..argc], NULL, envp NULL, auxv AT_NULL
    cpu.sp = proc.image.stack_top - 0x1000;
    // 字符串区放栈下方：argv[0]=guest 名，其余为命令行参数
    let mut str_base = cpu.sp - 0x1000;
    let mut argv_ptrs: Vec<u64> = Vec::new();
    let argv0 = if std::env::var("CH_ARGV0").is_ok() {
        std::env::var("CH_ARGV0").unwrap()
    } else { args[1].clone() };
    for a in std::iter::once(&argv0).chain(args[2..].iter()) {
        let b = a.as_bytes();
        let _ = proc.mem.write(str_base, b);
        let _ = proc.mem.write(str_base + b.len() as u64, &[0u8]);
        argv_ptrs.push(str_base);
        str_base += b.len() as u64 + 1;
        // 注：内核复制 argv 字符串时不做逐串 8 字节对齐（连续紧凑排布），
        // 此前对齐导致 strlen/stdio 内部路径与 qemu 对拍分歧
    }
    let argc = argv_ptrs.len() as u64;
    let _ = proc.mem.write(cpu.sp, &argc.to_le_bytes());
    for (i, ptr) in argv_ptrs.iter().enumerate() {
        let _ = proc.mem.write(cpu.sp + 8 + 8 * i as u64, &ptr.to_le_bytes());
    }
    // argv 终结 NULL + envp 空（NULL）+ auxv：AT_PAGESZ/AT_CLKTCK/AT_RANDOM/AT_NULL
    // （AT_PAGESZ 必需：musl mallocng ctx.pagesize=sysconf(_SC_PAGESIZE) 读 auxv，
    //   缺失得 0 → pagesize-1=-1 → 取整掩码失效 → malloc 崩坏）
    let mut off = cpu.sp + 8 + 8 * argv_ptrs.len() as u64;
    let rand_bytes: [u8; 16] = [0x5a; 16];
    let _ = proc.mem.write(str_base + 0x200, &rand_bytes); // 不叠 argv 字符串
    let _ = proc.mem.write(off, &0u64.to_le_bytes());            // argv NULL
    off += 8;
    let _ = proc.mem.write(off, &0u64.to_le_bytes());            // envp NULL
    off += 8;
    // auxv：与 qemu-aarch64 提供集对齐（musl __init_libc 全量扫描 auxv，
    // 缺项会让 bb 的 auxv 拷贝循环提前终结、后续 mallocng 元数据错乱）
    let (e_phoff, e_phentsize, e_phnum) = {
        let phoff = u64::from_le_bytes(bytes[0x20..0x28].to_vec().try_into().unwrap());
        let phent = u16::from_le_bytes(bytes[0x36..0x38].to_vec().try_into().unwrap()) as u64;
        let phnum = u16::from_le_bytes(bytes[0x38..0x3a].to_vec().try_into().unwrap()) as u64;
        (phoff, phent, phnum)
    };
    // first PT_LOAD vaddr（静态可执行：phdr vaddr = base + phoff）
    let (load_vaddr, _load_off) = {
        let mut lv = 0u64; let mut lo = 0u64;
        for k in 0..e_phnum as usize {
            let ph = 0x40 + e_phentsize * k as u64;
            let p = &bytes[ph as usize..(ph + 32) as usize];
            let p_type = u32::from_le_bytes(p[0..4].try_into().unwrap());
            if p_type == 1 { lv = u64::from_le_bytes(p[16..24].try_into().unwrap()); lo = u64::from_le_bytes(p[8..16].try_into().unwrap()); break; }
        }
        (lv, lo)
    };
    let aux: [(u64, u64); 20] = [
        (3, proc.image.phdr),            // AT_PHDR（PIE 下必须带加载偏移）
        (4, e_phentsize),                 // AT_PHENT
        (5, e_phnum),                     // AT_PHNUM
        (6, 4096),                        // AT_PAGESZ
        (7, 0),                           // AT_BASE
        (8, 0),                           // AT_FLAGS
        (9, proc.image.entry),            // AT_ENTRY
        (11, 0), (12, 0), (13, 0), (14, 0), // uid/euid/gid/egid
        (16, 0xefff_fffb),                // AT_HWCAP
        (17, 100),                        // AT_CLKTCK
        (25, str_base + 0x200),           // AT_RANDOM（16 字节 0x5a @ str_base+0x200）
        (23, 0),                          // AT_SECURE
        (31, str_base + 0x100),           // AT_EXECFN
        (26, 0x1800_7fc7_7fff),           // AT_HWCAP2（与 qemu 对拍一致）
        (15, str_base + 0x40),            // AT_PLATFORM（指向 "aarch64"）
        (33, 0),                          // AT_SYSINFO_EHDR=33（vDSO 基址）。ch 不提供 vDSO，必须给 0；
                                          // 此前误当成 AT_MINSIGSTKSZ 填 5120=0x1400，musl __vdsosym 把
                                          // 0x1400（ELF 头）当 vDSO 解析，符号表全是垃圾 → 野指针/mallocng 崩坏
        (0, 0),                           // AT_NULL
    ];
    for (k, v) in aux {
        let _ = proc.mem.write(off, &k.to_le_bytes()); off += 8;
        let _ = proc.mem.write(off, &v.to_le_bytes()); off += 8;
    }
    let _ = proc.mem.write(str_base + 0x40, b"aarch64\0"); // AT_PLATFORM 字符串
    let _ = proc.mem.write(str_base + 0x100, b"ls\0");     // AT_EXECFN 字符串

    if std::env::var("CH_MEMDUMP").is_ok() {
        let mut b = [0u8; 64];
        proc.mem.read(cpu.sp, &mut b).unwrap();
        let mut s2 = [0u8; 32];
        proc.mem.read(cpu.sp - 0x1000, &mut s2).unwrap();
        println!("strings@{:#x}: {:?}", cpu.sp - 0x1000, String::from_utf8_lossy(&s2));
        println!("stack@sp: {:02x?}", b);
    }
    let pctwatch: Vec<u64> = std::env::var("CH_PCTRACE").unwrap_or_default()
        .split(',').filter_map(|s| u64::from_str_radix(s.trim().trim_start_matches("0x"), 16).ok()).collect();
    let mut steps: u64 = 0;
    loop {
        let mut km = KernelMem(&mut proc);
        if std::env::var("CH_TRACE").is_ok() {
            eprintln!("pc={:#06x} sp={:#x} x0={:#x} x1={:#x} x2={:#x} x3={:#x} x4={:#x} x5={:#x} x6={:#x} x7={:#x} x8={:#x} x9={:#x} x19={:#x} x30={:#x} x20={:#x} x21={:#x} x22={:#x} x23={:#x} x24={:#x} x25={:#x} x26={:#x} x27={:#x} x28={:#x} x29={:#x}", cpu.pc, cpu.sp, cpu.x[0], cpu.x[1], cpu.x[2], cpu.x[3], cpu.x[4], cpu.x[5], cpu.x[8], cpu.x[9], cpu.x[19], cpu.x[30], cpu.x[20], cpu.x[21], cpu.x[22], cpu.x[23], cpu.x[24], cpu.x[25], cpu.x[26], cpu.x[27], cpu.x[28], cpu.x[29]);
        }
        if pctwatch.contains(&cpu.pc) {
            let regs: Vec<String> = (0..31).map(|k| format!("x{k}={:#x}", cpu.x[k])).collect();
            eprintln!("[pctrace pc={:#x}] {} sp={:#x}", cpu.pc, regs.join(" "), cpu.sp);
        }
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
