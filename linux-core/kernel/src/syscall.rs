//! syscall 分派：aarch64 Linux ABI（x8=号，x0-x5=参，返回 x0）。
//! P1 集合：write/exit/exit_group/getpid/getppid/getuid/geteuid/getgid/getegid/uname。
//! 返回 i64（负值 = Linux errno）。

use crate::{Process, Sock};

// arch/arm64: linux syscall numbers
pub const SYS_IOCTL: i64 = 29;
pub const SYS_WRITE: i64 = 64;
pub const SYS_WRITEV: i64 = 66;
pub const SYS_WRITEV: i64 = 66;
pub const SYS_EXIT_GROUP: i64 = 94;
pub const SYS_EXIT: i64 = 93;
pub const SYS_GETPID: i64 = 172;
pub const SYS_GETPPID: i64 = 173;
pub const SYS_GETUID: i64 = 174;
pub const SYS_GETEUID: i64 = 175;
pub const SYS_GETGID: i64 = 176;
pub const SYS_GETEGID: i64 = 177;
pub const SYS_UNAME: i64 = 160;
pub const SYS_BRK: i64 = 214;
pub const SYS_MMAP: i64 = 222;
pub const SYS_MUNMAP: i64 = 215;
pub const SYS_MPROTECT: i64 = 226;
pub const SYS_OPENAT: i64 = 56;
pub const SYS_CLOSE: i64 = 57;
pub const SYS_LSEEK: i64 = 62;
pub const SYS_READ: i64 = 63;
pub const SYS_SENDFILE: i64 = 71;

/// errno（include/uapi/asm-generic/errno-base.h）
const ENOSYS: i64 = -38;
const EBADF: i64 = -9;
const EINVAL: i64 = -22;

/// mmap bump 分配器区间：16MB 起到 56MB 止（栈在 64MB-64KB 之下）
pub const MMAP_BASE: u64 = 0x0100_0000;
pub const MMAP_TOP: u64 = 0x0380_0000;

/// 处理一次 svc。`regs` 是 guest 寄存器视图（可写 x0 回返回值）。
/// 返回：Some(code) 表示进程退出；None 继续执行。
pub fn handle(p: &mut Process, regs: &mut [u64; 31]) -> Option<i64> {
    let n = regs[8] as i64;
    let a0 = regs[0];
    let a1 = regs[1];
    let a2 = regs[2];
    let a4 = regs[4];
    let a5 = regs[5];
    let a3 = regs[3];
    let strace = std::env::var("CH_STRACE").is_ok();
    if strace { eprintln!("[svc] n={n} a0={a0:#x} a1={a1:#x} a2={a2:#x}"); }
    let ret: i64 = match n {
        SYS_WRITE => {
            // a0=fd, a1=buf, a2=len
            let len = a2 as usize;
            let mut buf = vec![0u8; len.min(1 << 20)];
            if p.mem.read(a1, &mut buf).is_err() {
                EBADF
            } else {
                match a0 {
                    1 => {
                        use std::io::Write;
                        let _ = p.out.write_all(&buf);
                        let _ = p.out.flush();
                        len as i64
                    }
                    2 => {
                        use std::io::Write;
                        let _ = p.err.write_all(&buf);
                        let _ = p.err.flush();
                        len as i64
                    }
                    _ => {
                        // 已打开宿主文件写入；socket fd（网络直通）走 send
                        if let Some(sk) = p.sockets.get_mut(&(a0 as i64)) {
                            match sk {
                                Sock::Tcp(s) => { use std::io::Write; match s.write_all(&buf) { Ok(_) => buf.len() as i64, Err(_) => -32 } }
                                Sock::Udp(s) => match s.send(&buf) { Ok(n) => n as i64, Err(_) => -11 },
                                Sock::PendingTcp => EBADF,
                            }
                        } else if let Some((f, _)) = p.files.get_mut(&(a0 as i64)) {
                            use std::io::Write;
                            match f.write_all(&buf) { Ok(_) => buf.len() as i64, Err(_) => -5 }
                        } else {
                            EBADF
                        }
                    }
                }
            }
        }
        SYS_EXIT | SYS_EXIT_GROUP => {
            if strace { eprintln!("[strace] n={} a0={:#x} a1={:#x} a2={:#x} -> exit", n, a0, a1, a2); }
            p.exit_code = Some(a0 as i64);
            return Some(a0 as i64);
        }
        // set_tid_address：单进程 P2，返回伪 tid
        113 => { // clock_gettime：CLOCK_REALTIME=0 / MONOTONIC=1
            use std::time::{SystemTime, UNIX_EPOCH};
            let (s, ns) = if a0 == 1 {
                (0u64, 0u64) // 单调钟：单进程无锚点，给 0（避免负值）
            } else {
                match SystemTime::now().duration_since(UNIX_EPOCH) {
                    Ok(d) => (d.as_secs(), d.subsec_nanos() as u64),
                    Err(_) => (0, 0),
                }
            };
            let b = s.to_le_bytes().iter().chain(ns.to_le_bytes().iter()).cloned().collect::<Vec<u8>>();
            if p.mem.write(a1, &b).is_err() { -14 } else { 0 }
        }
        101 => 0, // nanosleep：单进程即睡即醒（真实休眠由 P3 计时器接管，此处即时返回）
        115 => 0, // clock_nanosleep
        96 => 1,
        // ppoll：P2 无真实事件源，返回 0（超时，fd 均视为未就绪）
        73 => 0,
        SYS_GETPID => 1000,
        SYS_GETPPID => 1,
        SYS_GETUID | SYS_GETEUID => {
            extern "C" { fn getuid() -> u32; }
            unsafe { getuid() as i64 }
        },
        SYS_GETGID | SYS_GETEGID => {
            extern "C" { fn getgid() -> u32; }
            unsafe { getgid() as i64 }
        },
        SYS_UNAME => {
            // struct utsname: 6 个 65 字节域
            let mut put = |off: u64, s: &str| {
                let mut b = [0u8; 65];
                let sb = s.as_bytes();
                b[..sb.len().min(65)].copy_from_slice(&sb[..sb.len().min(65)]);
                p.mem.write(a0 + off, &b).is_ok()
            };
            if put(0, "Linux") && put(65, "chengyan") && put(130, "6.8.0-chengyan")
                && put(195, "#1 SMP") && put(260, "aarch64") && put(325, "gnu")
            { 0 } else { EBADF }
        }
        SYS_BRK => {
            // 真 brk 语义：传 0/低于当前返回当前值；在 heap 区间内则推进
            let req = regs[0] as i64;
            if req == 0 || req < p.image.brk as i64 || req as u64 >= p.mmap_base {
                p.image.brk as i64
            } else {
                p.image.brk = req as u64;
                req
            }
        }
        // mmap（aarch64: 222）。匿名映射用 bump 分配器（16MB 起，向栈方向但不越过 mmap 区顶）
        SYS_MMAP => {
            const MAP_FIXED: u64 = 0x10;
            const MAP_ANONYMOUS: u64 = 0x20;
            let addr = regs[0];
            let len = regs[1];
            let flags = regs[3];
            let fd = regs[4] as i64;
            if len == 0 || fd != -1 {
                -EINVAL
            } else {
                let len = (len + 0xfff) & !0xfff;
                let target = if addr != 0 && flags & MAP_FIXED != 0 {
                    // MAP_FIXED：不移动、不清零（平铺内存已驻留；musl 用它把 heap 钉回地址空间）
                    addr & !0xfff
                } else {
                    if p.mmap_next + len > MMAP_TOP { return Some(12); } // -ENOMEM 优雅退出
                    let t = p.mmap_next;
                    p.mmap_next = t + len;
                    t
                };
                if target + len > 0x0400_0000 { return Some(12); }
                // 仅 bump 新区清零（匿名页保证全零）
                if addr == 0 || flags & MAP_FIXED == 0 {
                    if flags & MAP_ANONYMOUS != 0 || fd == -1 {
                        let zeros = vec![0u8; len as usize];
                        let _ = p.mem.write(target, &zeros);
                    }
                }
                target as i64
            }
        }
        SYS_MUNMAP | SYS_MPROTECT => 0, // bump 分配器不回收；protect 忽略
        SYS_WRITEV => {
            // a0=fd, a1=iovec 数组（16 字节/项：ptr,len）, a2=项数
            let cnt = a2.min(1024) as u64;
            let mut total: i64 = 0;
            let mut ok = true;
            for i in 0..cnt {
                let mut e = [0u8; 16];
                if p.mem.read(a1 + i * 16, &mut e).is_err() { ok = false; break; }
                let ptr = u64::from_le_bytes(e[0..8].try_into().unwrap());
                let len = u64::from_le_bytes(e[8..16].try_into().unwrap()) as usize;
                let mut buf = vec![0u8; len.min(1 << 20)];
                if p.mem.read(ptr, &mut buf).is_err() { ok = false; break; }
                if a0 == 1 || a0 == 2 {
                    use std::io::Write;
                    let mut out: Box<dyn Write> = if a0 == 1 { Box::new(&mut p.out) as Box<dyn Write> } else { Box::new(&mut p.err) as Box<dyn Write> };
                    let mut h = out;
                    let _ = h.write_all(&buf);
                    let _ = h.flush();
                    total += len as i64;
                } else { ok = false; break; }
            }
            if ok { total } else { EBADF }
        }
        SYS_IOCTL => -25, // ENOTTY：无 tty，printf/isatty 路径需要
        // 信号/资源桩：单进程无信号事件，一律成功（此前 ENOSYS 使 musl 初始化路径中断）
        132 => 0, // sigaltstack
        133 => 0, // rt_sigaction
        134 => 0, // rt_sigprocmask
        146 => { // prlimit64：写回旧 rlim（全 0 即无限），返回 0
            if a3 != 0 {
                let z = [0u8; 16];
                let _ = p.mem.write(a3, &z);
            }
            0
        }
        25 => 0, // fcntl：返回 0（flags 无 tty 位）
        158 => 0, // getgroups：组数为 0
        80 => { // fstat：填 aarch64 stat 结构（体例：dev/ino/mode/nlink/uid/gid/rdev/size/atim/mtim/ctim…）
            let f = match p.files.get(&(a0 as i64)) { Some((f, _)) => f, None => return Some(EBADF) };
            use std::os::unix::fs::MetadataExt;
            match f.metadata() {
                Ok(m) => {
                    let mut b = vec![0u8; 128];
                    b[0..8].copy_from_slice(&m.dev().to_le_bytes());
                    b[8..16].copy_from_slice(&m.ino().to_le_bytes());
                    b[16..20].copy_from_slice(&(m.mode() as u32).to_le_bytes());
                    b[20..24].copy_from_slice(&(m.nlink() as u32).to_le_bytes());
                    b[24..28].copy_from_slice(&m.uid().to_le_bytes());
                    b[28..32].copy_from_slice(&m.gid().to_le_bytes());
                    b[32..40].copy_from_slice(&m.rdev().to_le_bytes());
                    // 标准 aarch64 struct stat：size@40 blksize@48 blocks@56 atim@64 mtim@80 ctim@96（nsec 紧随）
                    b[40..48].copy_from_slice(&m.size().to_le_bytes());
                    b[48..56].copy_from_slice(&4096u64.to_le_bytes());
                    b[56..64].copy_from_slice(&((m.size() + 511) / 512).to_le_bytes());
                    b[64..72].copy_from_slice(&m.atime().to_le_bytes());
                    b[72..76].copy_from_slice(&(m.atime_nsec() as u32).to_le_bytes());
                    b[80..88].copy_from_slice(&m.mtime().to_le_bytes());
                    b[88..92].copy_from_slice(&(m.mtime_nsec() as u32).to_le_bytes());
                    b[96..104].copy_from_slice(&m.ctime().to_le_bytes());
                    b[104..108].copy_from_slice(&(m.ctime_nsec() as u32).to_le_bytes());
                    if p.mem.write(a1, &b).is_err() { -14 } else { 0 }
                }
                Err(_) => -5,
            }
            }
        }
        79 => { // newfstatat(AT_FDCWD,path,buf,flags)：aarch64 musl stat 布局，与 fstat 同款 128 字节填充
            use std::os::unix::fs::MetadataExt;
            let mut pathb = Vec::new();
            let mut q = a1;
            loop {
                let mut b = [0u8; 1];
                if p.mem.read(q, &mut b).is_err() { return Some(-14); }
                if b[0] == 0 { break; }
                pathb.push(b[0]);
                q += 1;
                if pathb.len() > 4096 { return Some(-36); }
            }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            // AT_EMPTY_PATH(0x1000)+空路径 → 按 dirfd 文件 stat；否则按路径（fs root 内）
            if (regs[3] as u32 & 0x1000) != 0 && raw.is_empty() {
                let f = match p.files.get(&(a0 as i64)) { Some((f, _)) => f, None => return Some(EBADF) };
                return Some(match f.metadata() {
                    Ok(m) => { let mut b = vec![0u8; 128];
                        b[0..8].copy_from_slice(&m.dev().to_le_bytes());
                        b[8..16].copy_from_slice(&m.ino().to_le_bytes());
                        b[16..20].copy_from_slice(&(m.mode() as u32).to_le_bytes());
                        b[20..24].copy_from_slice(&(m.nlink() as u32).to_le_bytes());
                        b[24..28].copy_from_slice(&m.uid().to_le_bytes());
                        b[28..32].copy_from_slice(&m.gid().to_le_bytes());
                        b[32..40].copy_from_slice(&m.rdev().to_le_bytes());
                        b[40..48].copy_from_slice(&m.size().to_le_bytes());
                        b[48..56].copy_from_slice(&4096u64.to_le_bytes());
                        b[56..64].copy_from_slice(&((m.size() + 511) / 512).to_le_bytes());
                        b[64..72].copy_from_slice(&m.atime().to_le_bytes());
                        b[72..76].copy_from_slice(&(m.atime_nsec() as u32).to_le_bytes());
                        b[80..88].copy_from_slice(&m.mtime().to_le_bytes());
                        b[88..92].copy_from_slice(&(m.mtime_nsec() as u32).to_le_bytes());
                        b[96..104].copy_from_slice(&m.ctime().to_le_bytes());
                        b[104..108].copy_from_slice(&(m.ctime_nsec() as u32).to_le_bytes());
                        if p.mem.write(a2, &b).is_err() { -14 } else { 0 } }
                    Err(_) => -5,
                });
            }
            let full = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            match std::fs::symlink_metadata(&full) {
                Ok(m) => {
                    let mut b = vec![0u8; 128];
                    b[0..8].copy_from_slice(&m.dev().to_le_bytes());
                    b[8..16].copy_from_slice(&m.ino().to_le_bytes());
                    b[16..20].copy_from_slice(&(m.mode() as u32).to_le_bytes());
                    b[20..24].copy_from_slice(&(m.nlink() as u32).to_le_bytes());
                    b[24..28].copy_from_slice(&m.uid().to_le_bytes());
                    b[28..32].copy_from_slice(&m.gid().to_le_bytes());
                    b[32..40].copy_from_slice(&m.rdev().to_le_bytes());
                    b[40..48].copy_from_slice(&m.size().to_le_bytes());
                    b[48..56].copy_from_slice(&4096u64.to_le_bytes());
                    b[56..64].copy_from_slice(&((m.size() + 511) / 512).to_le_bytes());
                    b[64..72].copy_from_slice(&m.atime().to_le_bytes());
                    b[72..76].copy_from_slice(&(m.atime_nsec() as u32).to_le_bytes());
                    b[80..88].copy_from_slice(&m.mtime().to_le_bytes());
                    b[88..92].copy_from_slice(&(m.mtime_nsec() as u32).to_le_bytes());
                    b[96..104].copy_from_slice(&m.ctime().to_le_bytes());
                    b[104..108].copy_from_slice(&(m.ctime_nsec() as u32).to_le_bytes());
                    if p.mem.write(a2, &b).is_err() { -14 } else { 0 }
                }
                Err(_) => -2,
            }
        }
        61 => { // getdents64：按 fd 打开路径枚举宿主目录，游标续批
            use std::fs;
            let path = match p.files.get(&(a0 as i64)) { Some((_, pt)) => pt.clone(), None => return Some(EBADF) };
            let rd = match fs::read_dir(&path) { Ok(r) => r, Err(_) => return Some(-5) };
            let mut names: Vec<(u64, String, u8)> = Vec::new(); // (ino, name, d_type)
            // Linux 真实 getdents64 会先返回 "." 和 ".."（qemu 对拍真值），必须补上
            let dir_md = fs::metadata(&path).ok();
            let dir_ino = dir_md.as_ref().map(|m| {
                use std::os::unix::fs::MetadataExt;
                m.ino()
            }).unwrap_or(1);
            names.push((dir_ino, ".".to_string(), 4u8));
            names.push((dir_ino, "..".to_string(), 4u8));
            for e in rd.flatten() {
                let ty = match e.file_type() {
                    Ok(t) if t.is_dir() => 4u8,
                    Ok(t) if t.is_symlink() => 10,
                    _ => 8,
                };
                let name = match e.file_name().into_string() { Ok(s) => s, Err(_) => continue };
                names.push((e.ino(), name, ty));
            }
            // 顺序必须与宿主 readdir（= qemu 真值）一致；按名字排序会让 ls 的
            // 名字分配/比较序列与 qemu 分叉（对拍已证）
            // names.sort_by(|a, b| a.1.cmp(&b.1));
            let start = *p.dir_pos.get(&(a0 as i64)).unwrap_or(&0);
            let mut off = 0u64;
            let mut emitted = 0usize;
            for (idx, (ino, name, ty)) in names.iter().enumerate().skip(start) {
                let reclen = ((19 + name.len() + 8) / 8 * 8) as u64; // 头 19 字节 + name + NUL，8 对齐
                if off + reclen > a2 { break; }
                let mut rec = vec![0u8; reclen as usize];
                rec[0..8].copy_from_slice(&ino.to_le_bytes());
                rec[8..16].copy_from_slice(&((idx + 1) as u64).to_le_bytes()); // d_off（伪 cookie）
                rec[16..18].copy_from_slice(&(reclen as u16).to_le_bytes());
                rec[18] = *ty;
                rec[19..19 + name.len()].copy_from_slice(name.as_bytes());
                if p.mem.write(a1 + off, &rec).is_err() { return Some(-14); }
                off += reclen;
                emitted += 1;
            }
            p.dir_pos.insert(a0 as i64, start + emitted);
            off as i64
        }
        88 => { // utimensat：文件必须真实存在（不存在→ENOENT，busybox touch 才会 fallback open(O_CREAT) 建文件）
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            let full = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            if std::fs::symlink_metadata(&full).is_err() { -2 } else { 0 }
        }
        179 => { // sysinfo：free 的数据源（此前返回 0 → busybox 算出天文数字）
            // struct sysinfo: uptime(8) loads[3](24) totalram(8) freeram(8) sharedram(8) bufferram(8)
            // totalswap(8) freeswap(8) procs(u16) pad(22) totalhigh(8) freehigh(8) mem_unit(u32)
            let mut b = vec![0u8; 112];
            b[0..8].copy_from_slice(&3600u64.to_le_bytes()); // uptime
            b[32..40].copy_from_slice(&131_072u64.to_le_bytes()); // totalram KiB（mem_unit=1）
            b[40..48].copy_from_slice(&98_304u64.to_le_bytes()); // freeram
            b[56..64].copy_from_slice(&16_384u64.to_le_bytes()); // bufferram
            b[120..124].copy_from_slice(&1u32.to_le_bytes()); // mem_unit=1（pad 22 字节后：totalhigh@104 freehigh@112 mem_unit@120）
            if p.mem.write(a0, &b).is_err() { -14 } else { 0 }
        }
        292 => { // statx：musl 的 stat/fstat 底层走 statx。struct statx 256 字节
            use std::os::unix::fs::MetadataExt;
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            if (a2 as u32) & 0x1000 != 0 && raw.is_empty() {
                let f = match p.files.get(&(a0 as i64)) { Some((f, _)) => f, None => return ProcCtl::Exit(EBADF) };
                let m = match f.metadata() { Ok(m) => m, Err(_) => return ProcCtl::Exit(-5) };
                return ProcCtl::Exit({ fill_statx(&mut p, a4, &m); 0 });
            }
            let full = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            match std::fs::symlink_metadata(&full) {
                Ok(m) => { fill_statx(&mut p, a4, &m); 0 }
                Err(_) => -2,
            }
        }
        34 => { // mkdirat(AT_FDCWD, path, mode)：jail 内真实建目录（musl mkdir→mkdirat，aarch64 无 mkdir）
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            let mut norm = std::path::PathBuf::new();
            for comp in joined.components() { match comp { std::path::Component::ParentDir => { norm.pop(); } std::path::Component::CurDir => {} c => norm.push(c.as_os_str()) } }
            if !norm.starts_with(&p.root) { return ProcCtl::Exit(-13); }
            match std::fs::create_dir(&norm) { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        35 => { // unlinkat(AT_FDCWD, path, flags)：AT_REMOVEDIR(0x200)=删目录，否则删文件（aarch64 无 unlink）
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            let mut norm = std::path::PathBuf::new();
            for comp in joined.components() { match comp { std::path::Component::ParentDir => { norm.pop(); } std::path::Component::CurDir => {} c => norm.push(c.as_os_str()) } }
            if !norm.starts_with(&p.root) { return ProcCtl::Exit(-13); }
            let r = if a2 & 0x200 != 0 { std::fs::remove_dir(&norm) } else { std::fs::remove_file(&norm) };
            match r { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        38 => { // renameat(oldpath, newpath)
            let mut rd = |mut q: u64| -> Result<String, i64> {
                let mut pathb = Vec::new();
                loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return Err(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return Err(-36); } }
                Ok(String::from_utf8_lossy(&pathb).into_owned())
            };
            let old = match rd(a1) { Ok(s) => s, Err(e) => return ProcCtl::Exit(e) };
            let new = match rd(a3) { Ok(s) => s, Err(e) => return ProcCtl::Exit(e) };
            let norm = |raw: String| -> Result<std::path::PathBuf, i64> {
                let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
                let mut n = std::path::PathBuf::new();
                for comp in joined.components() { match comp { std::path::Component::ParentDir => { n.pop(); } std::path::Component::CurDir => {} c => n.push(c.as_os_str()) } }
                if !n.starts_with(&p.root) { return Err(-13); }
                Ok(n)
            };
            let o = match norm(old) { Ok(x) => x, Err(e) => return ProcCtl::Exit(e) };
            let nw = match norm(new) { Ok(x) => x, Err(e) => return ProcCtl::Exit(e) };
            match std::fs::rename(&o, &nw) { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        34 => { // mkdirat(AT_FDCWD, path, mode)：jail 内真实建目录（musl mkdir→mkdirat，aarch64 无 mkdir）
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            let mut norm = std::path::PathBuf::new();
            for comp in joined.components() { match comp { std::path::Component::ParentDir => { norm.pop(); } std::path::Component::CurDir => {} c => norm.push(c.as_os_str()) } }
            if !norm.starts_with(&p.root) { return ProcCtl::Exit(-13); }
            match std::fs::create_dir(&norm) { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        35 => { // unlinkat(AT_FDCWD, path, flags)：AT_REMOVEDIR(0x200)=删目录，否则删文件（aarch64 无 unlink）
            let mut pathb = Vec::new();
            let mut q = a1;
            loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return ProcCtl::Exit(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return ProcCtl::Exit(-36); } }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            let mut norm = std::path::PathBuf::new();
            for comp in joined.components() { match comp { std::path::Component::ParentDir => { norm.pop(); } std::path::Component::CurDir => {} c => norm.push(c.as_os_str()) } }
            if !norm.starts_with(&p.root) { return ProcCtl::Exit(-13); }
            let r = if a2 & 0x200 != 0 { std::fs::remove_dir(&norm) } else { std::fs::remove_file(&norm) };
            match r { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        38 => { // renameat(oldpath, newpath)
            let mut rd = |mut q: u64| -> Result<String, i64> {
                let mut pathb = Vec::new();
                loop { let mut b = [0u8; 1]; if p.mem.read(q, &mut b).is_err() { return Err(-14); } if b[0] == 0 { break; } pathb.push(b[0]); q += 1; if pathb.len() > 4096 { return Err(-36); } }
                Ok(String::from_utf8_lossy(&pathb).into_owned())
            };
            let old = match rd(a1) { Ok(s) => s, Err(e) => return ProcCtl::Exit(e) };
            let new = match rd(a3) { Ok(s) => s, Err(e) => return ProcCtl::Exit(e) };
            let norm = |raw: String| -> Result<std::path::PathBuf, i64> {
                let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
                let mut n = std::path::PathBuf::new();
                for comp in joined.components() { match comp { std::path::Component::ParentDir => { n.pop(); } std::path::Component::CurDir => {} c => n.push(c.as_os_str()) } }
                if !n.starts_with(&p.root) { return Err(-13); }
                Ok(n)
            };
            let o = match norm(old) { Ok(x) => x, Err(e) => return ProcCtl::Exit(e) };
            let nw = match norm(new) { Ok(x) => x, Err(e) => return ProcCtl::Exit(e) };
            match std::fs::rename(&o, &nw) { Ok(_) => 0, Err(e) => -(e.raw_os_error().unwrap_or(5) as i64) }
        }
        // openat（真实宿主文件，P3 前的直通实现）：读 C 字符串路径，按低 2 位访问模式 + CREAT/TRUNC/APPEND 打开
        SYS_OPENAT => {
            let mut pathb = Vec::new();
            let mut q = a1;
            loop {
                let mut b = [0u8; 1];
                if p.mem.read(q, &mut b).is_err() { return Some(-14); } // EFAULT
                if b[0] == 0 { break; }
                pathb.push(b[0]);
                q += 1;
                if pathb.len() > 4096 { return Some(-36); } // ENAMETOOLONG
            }
            let raw = String::from_utf8_lossy(&pathb).into_owned();
            // fs 越狱防护：规范化后强制落在 root 内
            let joined = if raw.starts_with('/') { p.root.join(raw.trim_start_matches('/')) } else { p.root.join(&raw) };
            let mut norm = std::path::PathBuf::new();
            for comp in joined.components() {
                match comp {
                    std::path::Component::ParentDir => { norm.pop(); }
                    std::path::Component::CurDir => {}
                    c => norm.push(c.as_os_str()),
                }
            }
            if !norm.starts_with(&p.root) { return Some(-13); } // EACCES：拒绝越狱
            let path = norm;
            let flags = a2 as u32;
            const O_ACCMODE: u32 = 3;
            const O_CREAT: u32 = 0x40;
            const O_TRUNC: u32 = 0x200;
            const O_APPEND: u32 = 0x400;
            let mut opt = std::fs::OpenOptions::new();
            opt.read(true);
            match flags & O_ACCMODE {
                1 => { opt.read(false).write(true); }
                2 => { opt.write(true); }
                _ => {}
            }
            if flags & O_APPEND != 0 { opt.append(true); }
            if flags & O_CREAT != 0 { opt.create(true); }
            if flags & O_TRUNC != 0 { opt.truncate(true); }
            match opt.open(&path) {
                Ok(f) => {
                    // 分配未用的最小 fd ≥ 0（宿主透传，guest 0/1/2 亦可为普通文件重定向）
                    let mut fd = 0i64;
                    while p.files.contains_key(&fd) { fd += 1; }
                    p.files.insert(fd, (f, path.to_string_lossy().into_owned()));
                    fd
                }
                Err(e) => {
                    match e.kind() {
                        std::io::ErrorKind::NotFound => -2,      // ENOENT
                        std::io::ErrorKind::PermissionDenied => -13, // EACCES
                        _ => -5,                                  // EIO
                    }
                }
            }
        }
        SYS_CLOSE => {
            if p.files.remove(&(a0 as i64)).is_some() { 0 } else { EBADF }
        }
        SYS_READ => {
            // a0=fd, a1=buf, a2=len；fd 0 未在表中时读注入的 stdin 缓冲
            use std::io::Read;
            let len = (a2 as usize).min(1 << 20);
            if a0 == 0 && !p.files.contains_key(&0) {
                use std::io::Seek;
                let mut buf = vec![0u8; len];
                let n = p.stdin.read(&mut buf).unwrap_or(0);
                if p.mem.write(a1, &buf[..n]).is_err() { -14 } else { n as i64 }
            } else {
            let f = match p.files.get_mut(&(a0 as i64)) { Some((f, _)) => f, None => return Some(EBADF) };
            let mut buf = vec![0u8; len];
            match f.read(&mut buf) {
                Ok(n) => {
                    if p.mem.write(a1, &buf[..n]).is_err() { -14 } else { n as i64 }
                }
                Err(_) => -5,
            }
            }
        }
        SYS_LSEEK => {
            use std::io::{Seek, SeekFrom};
            let f = match p.files.get_mut(&(a0 as i64)) { Some((f, _)) => f, None => return Some(EBADF) };
            let off = a1 as i64;
            let w = match a2 { 0 => SeekFrom::Start(off as u64), 1 => SeekFrom::Current(off), 2 => SeekFrom::End(off), _ => return Some(-22) };
            match f.seek(w) { Ok(pos) => pos as i64, Err(_) => -29 } // ESPIPE
        }
SYS_SENDFILE => {
            // sendfile(out, in, offset_ptr, count)：busybox cat 用；offset 为 NULL 时从当前位置
            // 入端支持虚拟 stdin（fd0 游标）、socket、文件；出端支持 p.out/p.err（fd1/2）、文件
            let count = regs[3] as usize;
            use std::io::{Read, Seek, SeekFrom, Write};
            let (buf, cur) = {
                let mut buf = vec![0u8; count.min(1 << 20)];
                let cur;
                if let Some(sk) = p.sockets.get_mut(&(a1 as i64)) {
                    let n = match sk {
                        Sock::Tcp(s) => s.read(&mut buf),
                        Sock::Udp(s) => s.recv(&mut buf),
                        Sock::PendingTcp => return ProcCtl::Exit(EBADF),
                    };
                    let n = match n { Ok(n) => n, Err(_) => return ProcCtl::Exit(-5) };
                    cur = 0; buf.truncate(n);
                } else if a1 == 0 && !p.files.contains_key(&0) {
                    let n = p.stdin.read(&mut buf).unwrap_or(0);
                    cur = p.stdin.position();
                    buf.truncate(n);
                } else {
                let f = match p.files.get_mut(&(a1 as i64)) { Some((f, _)) => f, None => return ProcCtl::Exit(EBADF) };
                if a2 != 0 {
                    let mut e = [0u8; 8];
                    if p.mem.read(a2, &mut e).is_err() { return ProcCtl::Exit(-14); }
                    let off = u64::from_le_bytes(e);
                    if f.seek(SeekFrom::Start(off)).is_err() { return ProcCtl::Exit(-29); }
                }
                let n = match f.read(&mut buf) { Ok(n) => n, Err(_) => return ProcCtl::Exit(-5) };
                cur = f.stream_position().unwrap_or(0);
                buf.truncate(n);
                }
                (buf, cur)
            };
            let sent = if a0 == 1 || a0 == 2 {
                use std::io::Write;
                let sink: &mut dyn std::io::Write = if a0 == 1 { &mut p.out } else { &mut p.err };
                sink.write_all(&buf).and_then(|_| sink.flush()).is_ok()
            } else {
                match p.files.get_mut(&(a0 as i64)) { Some((g, _)) => g.write_all(&buf).is_ok(), None => return ProcCtl::Exit(EBADF) }
            };
            if !sent { return Some(-5); }
            if a2 != 0 {
                let _ = p.mem.write(a2, &cur.to_le_bytes());
            }
            buf.len() as i64
        }
        // ===== 网络直通（S5 passt 等价物）：guest socket 系 syscall 转发宿主 socket =====
        // aarch64 号：socket=198 bind=200 connect=203 sendto=206 recvfrom=207
        //            setsockopt=208 getsockopt=209 sendmsg=211 recvmsg=212
        198 => { // socket(domain, type, protocol)
            let d = a0 as i64; let ty = a1 as i64;
            if d != 2 && d != 10 { -97 } // EPFNOSUPPORT：仅 AF_INET/AF_INET6 占位
            else {
                let mut fd = 0i64;
                while p.files.contains_key(&fd) || p.pipes.contains_key(&fd)
                    || p.pseudo.contains_key(&fd) || p.sockets.contains_key(&fd) { fd += 1; }
                if ty & 0xff == 2 { // SOCK_DGRAM：UDP 立即建宿主 socket
                    match std::net::UdpSocket::bind("0.0.0.0:0") {
                        Ok(s) => { p.sockets.insert(fd, Sock::Udp(s)); fd }
                        Err(_) => -12, // ENOMEM
                    }
                } else { p.sockets.insert(fd, Sock::PendingTcp); fd }
            }
        }
        203 => { // connect(fd, sockaddr*, addrlen)
            let mut sa = [0u8; 16];
            if p.mem.read(a1, &mut sa).is_err() { -14 }
            else if p.sockets.get(&(a0 as i64)).is_none() { EBADF }
            else {
                let port = u16::from_be_bytes([sa[2], sa[3]]);
                let ip = std::net::Ipv4Addr::new(sa[4], sa[5], sa[6], sa[7]);
                match p.sockets.remove(&(a0 as i64)) {
                    Some(Sock::PendingTcp) => match std::net::TcpStream::connect((ip, port)) {
                        Ok(s) => { p.sockets.insert(a0 as i64, Sock::Tcp(s)); 0 }
                        Err(_) => -111, // ECONNREFUSED/ENETUNREACH 归并
                    },
                    Some(Sock::Udp(s)) => {
                        let r = s.connect((ip, port));
                        p.sockets.insert(a0 as i64, Sock::Udp(s));
                        if r.is_err() { -111 } else { 0 }
                    }
                    _ => { p.sockets.insert(a0 as i64, Sock::PendingTcp); -106 } // EISCONN 异常路径
                }
            }
        }
        206 => { // sendto(fd, buf, len, flags, addr*, addrlen)
            use std::io::Write;
            let len = (a2 as usize).min(1 << 20);
            let mut buf = vec![0u8; len];
            if p.mem.read(a1, &mut buf).is_err() { -14 }
            else match p.sockets.get_mut(&(a0 as i64)) {
                Some(Sock::Tcp(s)) => match s.write(&buf) { Ok(n) => n as i64, Err(_) => -32 }, // EPIPE
                Some(Sock::Udp(s)) => {
                    if a4 != 0 && a5 >= 16 {
                        let mut sa = [0u8; 16];
                        if p.mem.read(a4, &mut sa).is_err() { return ProcCtl::Exit(-14); }
                        let port = u16::from_be_bytes([sa[2], sa[3]]);
                        let ip = std::net::Ipv4Addr::new(sa[4], sa[5], sa[6], sa[7]);
                        match s.send_to(&buf, (ip, port)) { Ok(n) => n as i64, Err(_) => -11 }
                    } else match s.send(&buf) { Ok(n) => n as i64, Err(_) => -11 }
                }
                _ => EBADF,
            }
        }
        207 => { // recvfrom(fd, buf, len, flags, addr*, addrlen*)
            use std::io::Read;
            let len = (a2 as usize).min(1 << 20);
            let mut buf = vec![0u8; len];
            match p.sockets.get_mut(&(a0 as i64)) {
                Some(Sock::Tcp(s)) => match s.read(&mut buf) {
                    Ok(n) => if p.mem.write(a1, &buf[..n]).is_err() { -14 } else { n as i64 },
                    Err(_) => -11,
                },
                Some(Sock::Udp(s)) => match s.recv_from(&mut buf) {
                    Ok((n, from)) => {
                        if a4 != 0 && a5 != 0 {
                            if let std::net::SocketAddr::V4(v4) = from {
                                let mut sa = [0u8; 16];
                                sa[0] = 2; sa[1] = 0; // AF_INET
                                sa[2..4].copy_from_slice(&v4.port().to_be_bytes());
                                sa[4..8].copy_from_slice(&v4.ip().octets());
                                let _ = p.mem.write(a4, &sa);
                                let _ = p.mem.write(a5, &16u32.to_le_bytes());
                            }
                        }
                        if p.mem.write(a1, &buf[..n]).is_err() { -14 } else { n as i64 }
                    }
                    Err(_) => -11,
                },
                _ => EBADF,
            }
        }
        211 | 212 => { // sendmsg/recvmsg：musl 常走这条，实现单 iovec 主路径
            // msghdr(musl aarch64)：{name*, namelen, iov*, iovlen(int), ctrl*, ctrllen, flags}
            let mut mh = [0u8; 56];
            if p.mem.read(a1, &mut mh).is_err() { -14 }
            else {
                let iovp = u64::from_le_bytes(mh[16..24].try_into().unwrap());
                let iovlen = i32::from_le_bytes(mh[24..28].try_into().unwrap()) as usize;
                if iovp == 0 || iovlen == 0 { 0i64 }
                else {
                    // iovec：{base*, len}
                    let mut iv = [0u8; 16];
                    if p.mem.read(iovp, &mut iv).is_err() { -14 }
                    else {
                        let base = u64::from_le_bytes(iv[0..8].try_into().unwrap());
                        let ilen = (u64::from_le_bytes(iv[8..16].try_into().unwrap()) as usize).min(1 << 20);
                        if n == 211 {
                            use std::io::Write;
                            let mut buf = vec![0u8; ilen];
                            if p.mem.read(base, &mut buf).is_err() { -14 }
                            else match p.sockets.get_mut(&(a0 as i64)) {
                                Some(Sock::Tcp(s)) => match s.write(&buf) { Ok(x) => x as i64, Err(_) => -32 },
                                Some(Sock::Udp(s)) => match s.send(&buf) { Ok(x) => x as i64, Err(_) => -11 },
                                _ => EBADF,
                            }
                        } else {
                            use std::io::Read;
                            let mut buf = vec![0u8; ilen];
                            match p.sockets.get_mut(&(a0 as i64)) {
                                Some(Sock::Tcp(s)) => match s.read(&mut buf) {
                                    Ok(x) => if p.mem.write(base, &buf[..x]).is_err() { -14 } else { x as i64 },
                                    Err(_) => -11,
                                },
                                Some(Sock::Udp(s)) => match s.recv(&mut buf) {
                                    Ok(x) => if p.mem.write(base, &buf[..x]).is_err() { -14 } else { x as i64 },
                                    Err(_) => -11,
                                },
                                _ => EBADF,
                            }
                        }
                    }
                }
            }
        }
        200 => { // bind(fd, sockaddr*, addrlen)：UDP 直通宿主已隐式绑定 0.0.0.0:0，全部成功返回
            if p.sockets.contains_key(&(a0 as i64)) { 0 } else { EBADF }
        }
        208 => 0, // setsockopt：SO_* 全忽略（阻塞模型无需）
        7 => { // poll(fds*, nfds, timeout)：阻塞模型下 socket 恒就绪、文件可读、管道按缓冲
            const POLLIN: i16 = 0x1; const POLLOUT: i16 = 0x4; const POLLERR: i16 = 0x8;
            let mut ret = 0i64;
            for i in 0..(a1 as usize).min(1024) {
                let ea = a0 + (i * 8) as u64;
                let mut e = [0u8; 8];
                if p.mem.read(ea, &mut e).is_err() { return ProcCtl::Exit(-14); }
                let fd = i32::from_le_bytes(e[0..4].try_into().unwrap()) as i64;
                let mut rev: i16 = 0;
                if fd >= 0 {
                    if p.sockets.contains_key(&fd) { rev = POLLIN | POLLOUT; }
                    else if p.files.contains_key(&fd) { rev = POLLIN; }
                    else if let Some(pe) = p.pipes.get(&fd) {
                        if pe.is_write { rev = POLLOUT; }
                        else {
                            let g = pe.shared.lock().unwrap();
                            if !g.buf.is_empty() || g.writers == 0 { rev = POLLIN; }
                        }
                    } else { rev = POLLERR; }
                }
                if rev != 0 { ret += 1; }
                let _ = p.mem.write(ea + 4, &rev.to_le_bytes());
            }
            ret
        }
        209 => { // getsockopt：SO_TYPE(3)/SO_ERROR(4) 有真实值，其余零
            if a3 != 0 && a4 >= 4 {
                let v: i32 = match a2 {
                    3 => match p.sockets.get(&(a0 as i64)) {
                        Some(Sock::Tcp(_)) => 1, Some(Sock::Udp(_)) => 2, _ => 0,
                    },
                    4 => 0,
                    _ => 0,
                };
                if p.mem.write(a3, &v.to_le_bytes()).is_err() { -14 } else { 0 }
            } else { 0 }
        }
        _ => ENOSYS,
    };
    if strace {
        eprintln!("[strace] n={} a0={a0:#x} a1={a1:#x} a2={a2:#x} a3={a3:#x} -> {ret:#x}", n, a3 = regs[3]);
        if n == SYS_WRITEV {
            for i in 0..a2.min(4) as u64 {
                let mut e = [0u8; 16];
                if p.mem.read(a1 + i * 16, &mut e).is_ok() {
                    let ptr = u64::from_le_bytes(e[0..8].try_into().unwrap());
                    let len = u64::from_le_bytes(e[8..16].try_into().unwrap());
                    eprintln!("  iov[{i}] ptr={ptr:#x} len={len:#x}");
                }
            }
        }
    }
    regs[0] = ret as u64;
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::GuestMem;

    #[test]
    fn write_and_exit() {
        let mut p = Process::new_for_test();
        let mut regs = [0u64; 31];
        regs[8] = SYS_WRITE as u64;
        regs[0] = 1;
        regs[1] = 0x100;
        let msg = b"ping\n";
        p.mem.write(0x100, msg).unwrap();
        regs[2] = msg.len() as u64;
        assert_eq!(handle(&mut p, &mut regs), None);
        assert_eq!(regs[0], msg.len() as u64);
        regs[8] = SYS_EXIT as u64;
        regs[0] = 42;
        assert_eq!(handle(&mut p, &mut regs), Some(42));
    }

    impl Process {
        pub fn new_for_test() -> Self {
            Self {
                mem: GuestMem::new(1 << 20),
                image: crate::LoadedImage { entry: 0, stack_top: 1 << 20, brk: 0x1000 },
                exit_code: None,
                mmap_next: MMAP_BASE,
                mmap_base: MMAP_BASE,
                files: std::collections::BTreeMap::new(),
                dir_pos: std::collections::BTreeMap::new(),
                root: std::path::PathBuf::from("/"),
                out: Box::new(Vec::new()),
                err: Box::new(Vec::new()),
                stdin: std::io::Cursor::new(Vec::new()),
            }
        }
    }
}