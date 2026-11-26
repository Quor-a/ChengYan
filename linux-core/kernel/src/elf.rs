//! ELF64 加载器（aarch64）：ET_EXEC 静态 / ET_DYN（static PIE）/ 动态链接（PT_INTERP）。
//!
//! # 三代能力
//!
//! 1. **ET_EXEC**：加载地址写死在 ELF 头里（老 busybox 是 `0x400000`），arena 只能
//!    落在那个固定地址 —— 而 App 进程里 `0x400000` 是公共资源：ART/zygote 的堆基址
//!    随机化随时会把它压掉，抢不到就只能无声回落到解释器。
//! 2. **ET_DYN（static PIE）**：全部地址相对，可以落在**任意**空闲地址上
//!    （`mmap(NULL)` 由内核挑）。这正是 v1.9.11 的分水岭。
//!    static PIE 的自我重定位由 musl 的 `__relocate_static_pie()` 完成，
//!    加载器只需平铺 PT_LOAD + 给出**正确重定位后的** `AT_PHDR`。
//! 3. **PT_INTERP（动态链接）**：文件里写着「请先用 /lib/ld-musl-aarch64.so.1 启动我」。
//!    加载器要把**两个**镜像都平铺进同一个 arena：主程序在低地址、解释器紧随其后，
//!    然后把控制权交给解释器的入口，并在 auxv 里给出
//!    `AT_BASE`（解释器基址）/ `AT_PHDR`+`AT_ENTRY`（主程序）三件套，
//!    剩下的重定位由 ld-musl 自己做（它会用 mmap 按 fd 把 DT_NEEDED 的 .so 映射进来）。
//!
//! # 为什么要 `Prots`（按页权限表）
//!
//! Tier1 把 guest 代码交给真 CPU 跑，而 Android 是 W^X 强制的：整段 arena 申请成
//! `PROT_READ|WRITE`，代码页必须显式 `mprotect` 成 `R-X` 才能执行。静态程序只有
//! 一段代码、一段数据，旧代码用手写的「代码尾页与数据首段共页就保留 RWX」逻辑凑合；
//! 动态链接之后段数变多（主程序 3~4 段 + 解释器 3~4 段），必须按**页**归并权限，
//! 否则要么执行不了（漏 X），要么 `.data` 写不进去（多砍了 W）。

use crate::GuestMem;

const PT_LOAD: u32 = 1;
const PT_INTERP: u32 = 3;
const PF_X: u32 = 1;
const PF_W: u32 = 2;
const PF_R: u32 = 4;

/// ET_EXEC：加载地址写死在 ELF 里。
pub const ET_EXEC: u16 = 2;
/// ET_DYN：位置无关（含 static PIE、共享库、动态可执行）。
pub const ET_DYN: u16 = 3;

const PAGE: u64 = 4096;

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

/// 一个 PT_LOAD 段（**绝对 guest 地址**，页边界未对齐）。
#[derive(Clone, Copy, Debug)]
pub struct Seg {
    pub lo: u64,
    pub hi: u64,
    pub flags: u32,
}

/// 一个镜像平铺完之后的落点信息。
#[derive(Debug)]
pub struct Placed {
    /// 该镜像的装载基址（= 调用方给的 base，ET_EXEC 时等于 ELF 写死的落点）。
    pub base: u64,
    pub entry: u64,
    /// 最高段末尾（未按页对齐）。
    pub end: u64,
    /// 程序头在**内存里**的地址（`AT_PHDR`）。
    pub phdr: u64,
    pub phent: u64,
    pub phnum: u64,
    pub segs: Vec<Seg>,
}

/// 一个镜像在内存里的最终形态（主程序或解释器）。
#[derive(Debug)]
pub struct Image {
    pub entry: u64,
    pub segs: Vec<Seg>,
    pub phdr: u64,
    pub end: u64,
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

/// `PT_INTERP` 里的解释器路径（`/lib/ld-musl-aarch64.so.1` 之类）。
///
/// 返回 `Some` 就说明这是个**动态链接**的可执行文件：光把它自己平铺好还不能跑，
/// 必须先把它交给解释器。这是「只能跑 busybox applet」与「整个 Alpine 用户空间
/// 都能跑」之间的那道门。
pub fn interp_path(bytes: &[u8]) -> Option<String> {
    let ei = info(bytes)?;
    for i in 0..ei.phnum as usize {
        let off = ei.phoff as usize + i * ei.phent as usize;
        if off + 56 > bytes.len() {
            return None;
        }
        let mut q = Reader { b: bytes, off };
        let p_type = q.u32();
        let _p_flags = q.u32();
        let p_offset = q.u64();
        let _p_vaddr = q.u64();
        let _p_paddr = q.u64();
        let p_filesz = q.u64();
        if p_type != PT_INTERP || p_filesz == 0 {
            continue;
        }
        let s = bytes.get(p_offset as usize..(p_offset + p_filesz) as usize)?;
        let end = s.iter().position(|&c| c == 0).unwrap_or(s.len());
        let p = String::from_utf8_lossy(&s[..end]).trim().to_string();
        if p.is_empty() {
            return None;
        }
        return Some(p);
    }
    None
}

/// ELF 最低 PT_LOAD vaddr（历史接口，保留给「ET_EXEC 固定落点」判断）。
pub fn min_vaddr(bytes: &[u8]) -> Option<u64> {
    info(bytes).map(|i| i.lo)
}

/// 是否 static PIE / 共享库（ET_DYN）。
pub fn is_pie(bytes: &[u8]) -> bool {
    info(bytes).map(|i| i.is_pie()).unwrap_or(false)
}

/// 把**一个**镜像平铺进 guest 内存，返回落点信息（不做权限、不动其它镜像）。
///
/// `base` 是该镜像的加载基址：ET_DYN 时 delta = base - lo；ET_EXEC 时 delta = 0
/// （此时 base 必须等于 `min_vaddr`，否则指针全错）。
fn place(
    mem: &mut GuestMem,
    bytes: &[u8],
    base: u64,
    vm_end: u64,
    what: &str,
) -> Result<Placed, String> {
    if bytes.len() < 64 || &bytes[0..4] != b"\x7fELF" {
        return Err(format!("{what}: 不是 ELF"));
    }
    if bytes[4] != 2 || bytes[5] != 1 {
        return Err(format!("{what}: 需要 ELF64 LE"));
    }
    // osabi: 0=SYSV, 3=Linux（glibc 产 3）；183=ARM
    if bytes[7] != 0 && bytes[7] != 3 && bytes[7] != 183 {
        return Err(format!("{what}: 不支持的 osabi={}", bytes[7]));
    }
    let ei = info(bytes).ok_or_else(|| format!("{what}: ELF 头非法 / 非 aarch64"))?;
    let delta = match ei.e_type {
        ET_EXEC => 0u64,
        ET_DYN => {
            if base < ei.lo {
                return Err(format!("{what}: 基址 {base:#x} 低于镜像原点 {:#x}", ei.lo));
            }
            base - ei.lo
        }
        other => return Err(format!("{what}: 不支持的 e_type={other}")),
    };
    let mut r = Reader { b: bytes, off: 0 };
    let mut max_end = base;
    let mut segs: Vec<Seg> = Vec::new();
    // 「装载偏移」= 段 vaddr − 段文件偏移，所有 PT_LOAD 都相同。
    // AT_PHDR 必须用它算：程序头在**内存里**的地址 = bias + delta + e_phoff。
    //
    // 旧实现直接 `e_phoff + delta`，在 static PIE（lo=0、base=arena）下碰巧对；
    // 一旦跑 ET_EXEC（`/sbin/apk.static` 就是，落点 0x400000、e_phoff=0x40），
    // 算出来就是 **0x40** —— musl 拿 0x40 当程序头去读，立刻在 fault=0x40 崩掉。
    let mut bias: Option<i64> = None;
    for i in 0..ei.phnum as usize {
        r.off = ei.phoff as usize + i * ei.phent as usize;
        if r.off + 56 > bytes.len() {
            return Err(format!("{what}: program header 越界"));
        }
        let p_type = r.u32();
        let p_flags = r.u32();
        let p_offset = r.u64();
        let p_vaddr = r.u64();
        let _p_paddr = r.u64();
        let p_filesz = r.u64();
        let p_memsz = r.u64();
        if p_type != PT_LOAD || p_memsz == 0 {
            continue;
        }
        if p_vaddr >= p_offset {
            let cand = (p_vaddr - p_offset) as i64;
            bias = Some(match bias {
                Some(b) => b.min(cand),
                None => cand,
            });
        }
        let vaddr = p_vaddr.checked_add(delta).ok_or("段地址溢出")?;
        let end = vaddr.checked_add(p_memsz).ok_or("段地址溢出")?;
        if vaddr < base {
            return Err(format!("{what}: 段 {vaddr:#x} 落在基址 {base:#x} 之下"));
        }
        if end > vm_end {
            return Err(format!("{what}: 段 {vaddr:#x}+{p_memsz} 超出 arena（上界 {vm_end:#x}）"));
        }
        if p_filesz > 0 {
            let src = bytes
                .get(p_offset as usize..(p_offset + p_filesz) as usize)
                .ok_or_else(|| format!("{what}: 段文件内容越界"))?;
            mem.write(vaddr, src)?;
        }
        // p_memsz > p_filesz 的 BSS 部分已是 0（flat / 新 mmap 都保证全零）
        max_end = max_end.max(end);
        segs.push(Seg { lo: vaddr, hi: end, flags: p_flags });
    }
    if segs.is_empty() {
        return Err(format!("{what}: 没有 PT_LOAD 段"));
    }
    let bias = bias.unwrap_or(0).max(0) as u64;
    Ok(Placed {
        base,
        entry: ei.entry_v.checked_add(delta).ok_or("entry 溢出")?,
        end: max_end,
        phdr: bias
            .checked_add(delta)
            .and_then(|b| b.checked_add(ei.phoff))
            .ok_or("phoff 溢出")?,
        phent: ei.phent as u64,
        phnum: ei.phnum as u64,
        segs,
    })
}

/// 按**页**归并一组段的最终权限（`PROT_*` 位）。
///
/// 优先级：同页既 W 又 X ⇒ RWX；只 W ⇒ RW；只 X ⇒ RX；否则 R。
/// 返回 `(lo, hi, prot)` 的连续区间列表（已合并同类相邻页）。
pub fn page_prots(segs: &[Seg]) -> Vec<(u64, u64, i32)> {
    if segs.is_empty() {
        return Vec::new();
    }
    let lo_page = segs.iter().map(|s| s.lo).min().unwrap() & !(PAGE - 1);
    let hi_page = (segs.iter().map(|s| s.hi).max().unwrap() + PAGE - 1) & !(PAGE - 1);
    let mut out: Vec<(u64, u64, i32)> = Vec::new();
    let mut a = lo_page;
    while a < hi_page {
        let mut w = false;
        let mut x = false;
        let mut any = false;
        for s in segs {
            if s.lo < a + PAGE && s.hi > a {
                any = true;
                if s.flags & PF_W != 0 {
                    w = true;
                }
                if s.flags & PF_X != 0 {
                    x = true;
                }
            }
        }
        let prot = if !any {
            0
        } else {
            let mut p = PF_R as i32;
            if w {
                p |= PF_W as i32;
            }
            if x {
                p |= PF_X as i32;
            }
            p
        };
        match out.last_mut() {
            Some(t) if t.2 == prot => t.1 = a + PAGE,
            _ => out.push((a, a + PAGE, prot)),
        }
        a += PAGE;
    }
    out
}

/// ELF 加载结果。
#[derive(Debug)]
pub struct LoadedImage {
    /// 真正的**起始执行地址**：有解释器时是解释器入口，否则是主程序入口。
    pub entry: u64,
    pub stack_top: u64,
    pub brk: u64,
    /// 可执行段整体区间 `[lo, hi)`（兼容字段；精确态见 `xranges`）。
    pub text: (u64, u64),
    /// 最低可写段起点（0 = 没有可写段）。兼容字段。
    pub wseg: u64,
    /// 主程序头在**内存里**的地址（`AT_PHDR`）。
    pub phdr: u64,
    /// 主程序 program header 表项大小 / 表项数（`AT_PHENT` / `AT_PHNUM`）。
    pub phent: u64,
    pub phnum: u64,
    /// 主程序入口（`AT_ENTRY`，动态链接时与 `entry` 不同）。
    pub main_entry: u64,
    /// 解释器基址（`AT_BASE`）。0 = 静态程序。
    pub interp_base: u64,
    /// **所有**可执行段的绝对区间（主程序 + 解释器）。Tier1 只在这些字节里
    /// 把 `svc #0` 改写成陷门——扫全 arena 会误伤常量表与数据。
    pub xranges: Vec<(u64, u64)>,
    /// 按页归并后的最终权限表 `(lo, hi, prot)`，Tier1 据此 mprotect。
    pub prots: Vec<(u64, u64, i32)>,
}

impl LoadedImage {
    fn finish(
        main: Placed,
        interp: Option<Placed>,
        vm_end: u64,
    ) -> LoadedImage {
        let mut segs: Vec<Seg> = main.segs.clone();
        let mut entry = main.entry;
        let mut interp_base = 0u64;
        let mut end = main.end;
        if let Some(ip) = interp {
            interp_base = ip.base;
            entry = ip.entry;
            end = end.max(ip.end);
            segs.extend_from_slice(&ip.segs);
        }
        let mut xranges: Vec<(u64, u64)> = segs
            .iter()
            .filter(|s| s.flags & PF_X != 0)
            .map(|s| (s.lo, s.hi))
            .collect();
        xranges.sort();
        let text = xranges
            .iter()
            .fold((u64::MAX, 0u64), |acc, r| (acc.0.min(r.0), acc.1.max(r.1)));
        let wseg = segs
            .iter()
            .filter(|s| s.flags & PF_W != 0)
            .map(|s| s.lo)
            .min()
            .unwrap_or(0);
        LoadedImage {
            entry,
            stack_top: (vm_end - 0x1_0000) & !0xf,
            brk: (end + 0xfff) & !0xfff,
            text: if text.1 == 0 { (0, 0) } else { text },
            wseg,
            phdr: main.phdr,
            phent: main.phent,
            phnum: main.phnum,
            main_entry: main.entry,
            interp_base,
            xranges,
            prots: page_prots(&segs),
        }
    }
}

/// 把**静态** ELF（ET_EXEC / static PIE）平铺进 guest 内存。
///
/// - `arena_base`：guest 地址空间起点（`GuestMem::span().0`）。
/// - `mem_size`：arena 长度。guest 可见地址是 `[arena_base, arena_base + mem_size)`。
pub fn load(
    mem: &mut GuestMem,
    bytes: &[u8],
    arena_base: u64,
    mem_size: u64,
) -> Result<LoadedImage, String> {
    load_with_interp(mem, bytes, None, arena_base, mem_size, 0)
}

/// 平铺主程序 + 可选解释器。
///
/// `interp_hint`：解释器希望的基址（0 = 由本函数选：主程序末尾按 64KB 向上对齐）。
/// 解释器必须整体落在 arena 内且不与主程序重叠。
pub fn load_with_interp(
    mem: &mut GuestMem,
    bytes: &[u8],
    interp_bytes: Option<&[u8]>,
    arena_base: u64,
    mem_size: u64,
    interp_hint: u64,
) -> Result<LoadedImage, String> {
    let vm_end = arena_base.checked_add(mem_size).ok_or("arena 溢出")?;
    let main = place(mem, bytes, arena_base, vm_end, "主程序")?;
    let interp = match interp_bytes {
        None => None,
        Some(ib) => {
            let ii = info(ib).ok_or("解释器不是合法 ELF")?;
            let want = if interp_hint != 0 {
                interp_hint & !0xFFFF
            } else {
                (main.end + 0xFFFF) & !0xFFFFu64
            };
            // ET_EXEC 解释器（极少见）只能落在自己写死的地址
            let base = if ii.e_type == ET_EXEC { ii.lo } else { want };
            // 与主程序重叠就是硬错误（会互相踩坏）
            let clash = main
                .segs
                .iter()
                .any(|s| s.lo < base + (ii.hi - ii.lo) && s.hi > base);
            if clash {
                return Err(format!("解释器基址 {base:#x} 与主程序段重叠"));
            }
            Some(place(mem, ib, base, vm_end, "解释器")?)
        }
    };
    Ok(LoadedImage::finish(main, interp, vm_end))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pie() -> Vec<u8> {
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
        b[64 + 4..64 + 8].copy_from_slice(&(PF_X | PF_R).to_le_bytes()); // flags=R|X
        b[64 + 8..64 + 16].copy_from_slice(&0u64.to_le_bytes()); // offset
        b[64 + 16..64 + 24].copy_from_slice(&0u64.to_le_bytes()); // vaddr
        b[64 + 32..64 + 40].copy_from_slice(&(0x200u64).to_le_bytes()); // filesz
        b[64 + 40..64 + 48].copy_from_slice(&(0x200u64).to_le_bytes()); // memsz
        b
    }

    #[test]
    fn rejects_non_elf() {
        let mut m = GuestMem::new(4096);
        assert!(load(&mut m, b"nope", 0, 4096).is_err());
    }

    /// 造一个**能容纳 `base`** 的 guest 内存。
    ///
    /// `GuestMem::new(n)` 是 flat 后端、基址恒为 0；要在 `base != 0` 处装镜像
    /// 必须用恒等后端（guest 地址 = 宿主地址）。拿不到该地址（被占 / 老内核
    /// 不认 MAP_FIXED_NOREPLACE）时返回 None，调用方跳过该组断言 —— 与
    /// `identity_roundtrip` 的处理一致：测的是布局算法，不是宿主 mmap 运气。
    fn arena_at(base: u64, size: u64) -> Option<GuestMem> {
        if base == 0 {
            return Some(GuestMem::new(size as usize));
        }
        GuestMem::identity(base, size).ok()
    }

    #[test]
    fn pie_loads_anywhere() {
        let b = pie();
        // flat（arena_base = 0）与任意高地址都必须得到同一套相对布局。
        for base in [0u64, 0x1000_0000, 0x7f00_0000] {
            let mut m = match arena_at(base, 0x10_0000) {
                Some(m) => m,
                None => continue,
            };
            let img = load(&mut m, &b, base, 0x10_0000).expect("PIE 应可自由放置");
            assert_eq!(img.entry, base + 0x120);
            assert_eq!(img.phdr, base + 64);
            assert_eq!(img.text, (base, base + 0x200));
            assert_eq!(img.stack_top, base + 0x10_0000 - 0x1_0000);
            assert_eq!(img.interp_base, 0);
            assert_eq!(img.main_entry, base + 0x120);
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
        b[64 + 4..64 + 8].copy_from_slice(&(PF_X | PF_R).to_le_bytes());
        b[64 + 8..64 + 16].copy_from_slice(&0u64.to_le_bytes());
        b[64 + 16..64 + 24].copy_from_slice(&0x400000u64.to_le_bytes());
        b[64 + 32..64 + 40].copy_from_slice(&(0x10u64).to_le_bytes());
        b[64 + 40..64 + 48].copy_from_slice(&(0x10u64).to_le_bytes());
        let mut m = GuestMem::new(0x1_0000);
        let e = load(&mut m, &b, 0, 0x1_0000).unwrap_err();
        assert!(e.contains("arena"), "{e}");
    }

    #[test]
    fn interp_parsed_and_placed_after_main() {
        // 主程序 + PT_INTERP 段；解释器是同形状的 ET_DYN。
        let mut b = pie();
        let mut ip = pie();
        // 加一个 PT_INTERP：phnum=2，第二项在 64+56
        b[0x38..0x3a].copy_from_slice(&2u16.to_le_bytes());
        let str_off = 0x1f0u64;
        b[0x1f0..0x1f8].copy_from_slice(b"/lib/ld\0");
        b[64 + 56..64 + 56 + 4].copy_from_slice(&PT_INTERP.to_le_bytes());
        b[64 + 56 + 4..64 + 56 + 8].copy_from_slice(&PF_R.to_le_bytes());
        b[64 + 56 + 8..64 + 56 + 16].copy_from_slice(&str_off.to_le_bytes());
        b[64 + 56 + 32..64 + 56 + 40].copy_from_slice(&8u64.to_le_bytes()); // filesz
        b[64 + 56 + 40..64 + 56 + 48].copy_from_slice(&8u64.to_le_bytes()); // memsz
        assert_eq!(interp_path(&b).as_deref(), Some("/lib/ld"));
        // 解释器入口改一下，便于区分
        ip[0x18..0x20].copy_from_slice(&0x88u64.to_le_bytes());

        // 两个基址都要成立：flat（base=0）与高基址（恒等后端，拿不到就跳过）。
        // 关键断言是「解释器被放到主程序之后、且入口切到解释器」——
        // 这正是 ET_DYN + PT_INTERP 与旧 static-only 路径的分水岭。
        for base in [0u64, 0x8000_0000u64] {
            let mut m = match arena_at(base, 0x40_0000) {
                Some(m) => m,
                None => continue,
            };
            let img = load_with_interp(&mut m, &b, Some(&ip), base, 0x40_0000, 0).unwrap();
            assert_eq!(img.main_entry, base + 0x120);
            assert_eq!(img.entry, base + 0x1_0000 + 0x88, "入口必须切到解释器");
            assert_eq!(img.interp_base, base + 0x1_0000);
            assert_eq!(img.xranges.len(), 2);
            assert!(img.brk >= base + 0x1_0000 + 0x200);
        }
    }

    #[test]
    fn page_prots_shared_page_is_rwx() {
        // 0x1000..0x1800 可写、0x1400..0x2000 可执行 → 页 0x1000 必须 RWX
        let segs = vec![
            Seg { lo: 0x1000, hi: 0x1800, flags: PF_R | PF_W },
            Seg { lo: 0x1400, hi: 0x2000, flags: PF_R | PF_X },
        ];
        let p = page_prots(&segs);
        assert_eq!(p[0], (0x1000, 0x2000, 7));
    }
}
