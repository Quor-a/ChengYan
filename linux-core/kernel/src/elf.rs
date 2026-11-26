//! ELF64 加载器（aarch64）：ET_EXEC 静态 + ET_DYN（static PIE）。
//!
//! # 为什么要支持 ET_DYN（这是架构分水岭）
//!
//! ET_EXEC 把加载地址写死在 ELF 头里（busybox 是 `0x400000`），所以 arena 只能
//! 落在那个固定地址 —— 而 App 进程里 `0x400000` 是公共资源：ART/zygote 的堆基址
//! 随机化（`arch_randomize_brk`）随时会把它压掉，抢不到就只能无声回落到解释器。
//! 这就是「同一台机器、同一条命令，偶尔就起不来」的根。
//!
//! ET_DYN（static PIE）的全部地址都是相对的，可以落在**任意**空闲地址上。于是：
//! - 不再需要预留固定窗口，也就不存在被 ART 抢走的可能；
//! - 每个 guest 进程可以有自己独立的 arena（`mmap(NULL)` 由内核挑地址，天然不冲突），
//!   为以后 guest 内部 fork/exec 打掉最大的结构障碍。
//!
//! static PIE 的自我重定位（R_AARCH64_RELATIVE）由 musl 的
//! `__relocate_static_pie()` 在 `__libc_start_main` 里用 `__ehdr_start` 自己做，
//! 所以加载器只需把 PT_LOAD 平铺到位、并在 auxv 里给出**正确重定位后的**
//! `AT_PHDR`（旧代码拿文件里的 `load_vaddr` 算，PIE 下必然错）。
//!
//! PIE 下 `p_align` 常见 64KB，调用方给的 `arena_base` 必须是 64KB 对齐。

use crate::GuestMem;

const PT_LOAD: u32 = 1;
const PF_X: u32 = 1;
const PF_W: u32 = 2;

/// ET_EXEC：加载地址写死在 ELF 里。
pub const ET_EXEC: u16 = 2;
/// ET_DYN：位置无关（含 static PIE）。
pub const ET_DYN: u16 = 3;

struct Reader<'a> {
    b: &'a [u8],
    off: usize,
}

impl<'a> Reader<'a> {
    fn u16(&mut self) -> u16 {
        let v = u16::from_le_bytes(self.b[self.off..self.off + 2].try_into().unwrap());
        self.off += 2;
        v
    }
    fn u32(&mut self) -> u32 {
        let v = u32::from_le_bytes(self.b[self.off..self.off + 4].try_into().unwrap());
        self.off += 4;
        v
    }
    fn u64(&mut self) -> u64 {
        let v = u64::from_le_bytes(self.b[self.off..self.off + 8].try_into().unwrap());
        self.off += 8;
        v
    }
}

/// ELF 头里与加载相关的信息（不含段内容）。
pub struct ElfInfo {
    pub e_type: u16,
    /// 最低 PT_LOAD vaddr（ET_DYN 通常是 0）。
    pub lo: u64,
    /// 最高 PT_LOAD 末尾（vaddr + memsz）。
    pub hi: u64,
    pub entry_v: u64,
    pub phoff: u64,
    pub phnum: u16,
    pub phent: u16,
}

impl ElfInfo {
    pub fn is_pie(&self) -> bool {
        self.e_type == ET_DYN
    }
}

/// 解析 ELF 头与 PT_LOAD 边界。不是合法 aarch64 ELF64 就返回 `None`。
pub fn info(bytes: &[u8]) -> Option<ElfInfo> {
    if bytes.len() < 64 || &bytes[0..4] != b"\x7fELF" {
        return None;
    }
    if bytes[4] != 2 || bytes[5] != 1 {
        return None;
    }
    let mut r = Reader { b: bytes, off: 16 };
    let e_type = r.u16();
    let e_machine = r.u16();
    if e_machine != 0xB7 {
        return None;
    }
    r.off = 0x18;
    let entry_v = r.u64();
    let phoff = r.u64();
    r.off = 0x36;
    let phent = r.u16();
    let phnum = r.u16();
    let mut lo = u64::MAX;
    let mut hi = 0u64;
    for i in 0..phnum as usize {
        let off = phoff as usize + i * phent as usize;
        if off + 56 > bytes.len() {
            break;
        }
        let mut q = Reader { b: bytes, off };
        let p_type = q.u32();
        let _p_flags = q.u32();
        let _p_offset = q.u64();
        let p_vaddr = q.u64();
        let _p_paddr = q.u64();
        let _p_filesz = q.u64();
        let p_memsz = q.u64();
        if p_type != PT_LOAD || p_memsz == 0 {
            continue;
        }
        lo = lo.min(p_vaddr);
        hi = hi.max(p_vaddr.saturating_add(p_memsz));
    }
    if hi == 0 || lo == u64::MAX {
        return None;
    }
    Some(ElfInfo { e_type, lo, hi, entry_v, phoff, phnum, phent })
}

/// ELF 最低 PT_LOAD vaddr（历史接口，保留给「ET_EXEC 固定落点」判断）。
pub fn min_vaddr(bytes: &[u8]) -> Option<u64> {
    info(bytes).map(|i| i.lo)
}

/// 是否 static PIE。非 PIE 时 `arena_base` 必须等于 `min_vaddr`。
pub fn is_pie(bytes: &[u8]) -> bool {
    info(bytes).map(|i| i.is_pie()).unwrap_or(false)
}

/// 把 ELF 平铺进 guest 内存。
///
/// - `arena_base`：guest 地址空间的起点（也就是 `GuestMem::span().0`）。
///   ET_DYN 可以任意（64KB 对齐）；ET_EXEC 必须等于 `min_vaddr`，否则指针全错。
/// - `mem_size`：arena 长度。guest 可见地址是 `[arena_base, arena_base + mem_size)`。
///
/// 返回的 `entry / stack_top / brk / text / wseg / phdr` **全部是 guest 绝对地址**。
pub fn load(
    mem: &mut GuestMem,
    bytes: &[u8],
    arena_base: u64,
    mem_size: u64,
) -> Result<crate::LoadedImage, String> {
    if bytes.len() < 64 || &bytes[0..4] != b"\x7fELF" {
        return Err("not an ELF".into());
    }
    if bytes[4] != 2 || bytes[5] != 1 {
        return Err("need ELF64 LE".into());
    }
    // osabi: 0=SYSV, 3=Linux（glibc 产 3）；183=ARM
    if bytes[7] != 0 && bytes[7] != 3 && bytes[7] != 183 {
        return Err(format!("unsupported abi osabi={}", bytes[7]));
    }

    let ei = info(bytes).ok_or("bad ELF header / unsupported machine")?;
    let mut r = Reader { b: bytes, off: 0 };

    // delta = guest 地址 - ELF 里的 vaddr。
    //
    // ET_EXEC：vaddr 本身就是 guest 绝对地址（写死的落点），delta 恒为 0。
    // ET_DYN ：vaddr 从 `lo`（通常 0）算，整个镜像随 arena 平移，delta = arena_base - lo。
    let delta = match ei.e_type {
        ET_EXEC => 0u64,
        ET_DYN => {
            if arena_base < ei.lo {
                return Err(format!("arena_base {arena_base:#x} 低于镜像原点 {:#x}", ei.lo));
            }
            arena_base - ei.lo
        }
        other => return Err(format!("unsupported e_type={other}")),
    };

    let vm_end = arena_base.checked_add(mem_size).ok_or("arena overflow")?;
    let mut max_end = arena_base;
    let mut text: (u64, u64) = (u64::MAX, 0);
    // 最低可写段起点：原生执行要把代码页设成 R-X，但如果某个可写段的起始页
    // 和代码尾页共页，就不能整页锁掉，否则 guest 一写 .data 就段错误。
    let mut wseg: u64 = 0;

    for i in 0..ei.phnum as usize {
        r.off = ei.phoff as usize + i * ei.phent as usize;
        if r.off + 56 > bytes.len() {
            return Err("program header 越界".into());
        }
        let p_type = r.u32();
        let p_flags = r.u32();
        let p_offset = r.u64();
        let p_vaddr = r.u64();
        let _p_paddr = r.u64();
        let p_filesz = r.u64();
        let p_memsz = r.u64();
        if p_type != PT_LOAD {
            continue;
        }
        let vaddr = p_vaddr.checked_add(delta).ok_or("seg overflow")?;
        let end = vaddr.checked_add(p_memsz).ok_or("seg overflow")?;
        if vaddr < arena_base {
            return Err(format!("segment {vaddr:#x} 落在 arena 起点 {arena_base:#x} 之下"));
        }
        if end > vm_end {
            return Err(format!("segment {vaddr:#x}+{p_memsz} 超出 arena（上界 {vm_end:#x}）"));
        }
        if p_filesz > 0 {
            let src = bytes
                .get(p_offset as usize..(p_offset + p_filesz) as usize)
                .ok_or("ph filesz 超出文件")?;
            mem.write(vaddr, src)?;
        }
        // p_memsz > p_filesz 的 BSS 部分已是 0（flat / 新 mmap 都保证全零）
        max_end = max_end.max(end);
        // 可执行段区间：Tier1 只在这里面找 svc（扫全 arena 会误伤常量表）
        if p_flags & PF_X != 0 && p_memsz > 0 {
            text.0 = text.0.min(vaddr);
            text.1 = text.1.max(end);
        }
        if p_flags & PF_W != 0 && p_memsz > 0 {
            wseg = if wseg == 0 { vaddr } else { wseg.min(vaddr) };
        }
    }
    if max_end == arena_base {
        return Err("no PT_LOAD segments".into());
    }
    if text.1 == 0 {
        return Err("no executable segment".into());
    }

    // 栈：顶在地址空间上限下方 64KB，向下生长
    let stack_top = (vm_end - 0x1_0000) & !0xf;
    Ok(crate::LoadedImage {
        entry: ei.entry_v.checked_add(delta).ok_or("entry overflow")?,
        stack_top,
        brk: (max_end + 0xfff) & !0xfff,
        text,
        wseg,
        // AT_PHDR：程序头在内存里的**实际**地址。PIE 下必须加 delta，
        // 否则 musl 的 static-pie 自举会拿一个错地址去解析 phdr。
        phdr: ei.phoff.checked_add(delta).ok_or("phoff overflow")?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_non_elf() {
        let mut m = GuestMem::new(4096);
        assert!(load(&mut m, b"nope", 0, 4096).is_err());
    }

    #[test]
    fn pie_loads_anywhere() {
        // 最小 ET_DYN（static PIE 形状）：一个 R-X 的 PT_LOAD，vaddr 0、offset 0。
        let mut b = vec![0u8; 0x200];
        b[0..4].copy_from_slice(b"\x7fELF");
        b[4] = 2;
        b[5] = 1;
        b[6] = 1;
        b[16..18].copy_from_slice(&ET_DYN.to_le_bytes());
        b[18..20].copy_from_slice(&0xB7u16.to_le_bytes());
        b[0x18..0x20].copy_from_slice(&0x120u64.to_le_bytes()); // e_entry
        b[0x20..0x28].copy_from_slice(&64u64.to_le_bytes()); // phoff
        b[0x36..0x38].copy_from_slice(&56u16.to_le_bytes()); // phentsize
        b[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes()); // phnum
        b[64..68].copy_from_slice(&PT_LOAD.to_le_bytes());
        b[64 + 4..64 + 8].copy_from_slice(&(PF_X | 4u32).to_le_bytes()); // flags=R|X
        b[64 + 8..64 + 16].copy_from_slice(&0u64.to_le_bytes()); // offset
        b[64 + 16..64 + 24].copy_from_slice(&0u64.to_le_bytes()); // vaddr
        b[64 + 32..64 + 40].copy_from_slice(&(0x200u64).to_le_bytes()); // filesz
        b[64 + 40..64 + 48].copy_from_slice(&(0x200u64).to_le_bytes()); // memsz

        // flat（arena_base = 0）与任意高地址都必须得到同一套相对布局。
        for base in [0u64, 0x1000_0000, 0x7f00_0000] {
            let mut m = GuestMem::new(0x10_0000);
            let img = load(&mut m, &b, base, 0x10_0000).expect("PIE 应可自由放置");
            assert_eq!(img.entry, base + 0x120);
            assert_eq!(img.phdr, base + 64);
            assert_eq!(img.text, (base, base + 0x200));
            assert_eq!(img.stack_top, base + 0x10_0000 - 0x1_0000);
        }
    }

    #[test]
    fn rejects_exec_outside_arena() {
        // 最小 ET_EXEC：落点 0x400000，塞进 [0, 0x10000) 的 arena 必须被拒。
        let mut b = vec![0u8; 0x200];
        b[0..4].copy_from_slice(b"\x7fELF");
        b[4] = 2;
        b[5] = 1;
        b[6] = 1;
        b[16..18].copy_from_slice(&ET_EXEC.to_le_bytes());
        b[18..20].copy_from_slice(&0xB7u16.to_le_bytes());
        b[0x20..0x28].copy_from_slice(&64u64.to_le_bytes());
        b[0x36..0x38].copy_from_slice(&56u16.to_le_bytes());
        b[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes());
        b[64..68].copy_from_slice(&PT_LOAD.to_le_bytes());
        b[64 + 4..64 + 8].copy_from_slice(&(PF_X | 4u32).to_le_bytes());
        b[64 + 8..64 + 16].copy_from_slice(&0u64.to_le_bytes());
        b[64 + 16..64 + 24].copy_from_slice(&0x400000u64.to_le_bytes());
        b[64 + 32..64 + 40].copy_from_slice(&(0x10u64).to_le_bytes());
        b[64 + 40..64 + 48].copy_from_slice(&(0x10u64).to_le_bytes());
        let mut m = GuestMem::new(0x1_0000);
        let e = load(&mut m, &b, 0, 0x1_0000).unwrap_err();
        assert!(e.contains("arena"), "{e}");
    }
}
