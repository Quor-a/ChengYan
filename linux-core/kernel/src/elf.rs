//! ELF64 静态加载器（aarch64）。
//! P1 只支持 ET_EXEC 风格静态、非 PIE、PT_LOAD 段平铺进 flat guest 内存；
//! PIE/动态链接/PT_INTERP 在 P3（配合 ld.so 语义）接入。

use crate::GuestMem;

const PT_LOAD: u32 = 1;

struct Reader<'a> {
    b: &'a [u8],
    off: usize,
}

impl<'a> Reader<'a> {
    fn u16(&mut self) -> u16 { let v = u16::from_le_bytes(self.b[self.off..self.off + 2].try_into().unwrap()); self.off += 2; v }
    fn u32(&mut self) -> u32 { let v = u32::from_le_bytes(self.b[self.off..self.off + 4].try_into().unwrap()); self.off += 4; v }
    fn u64(&mut self) -> u64 { let v = u64::from_le_bytes(self.b[self.off..self.off + 8].try_into().unwrap()); self.off += 8; v }
}

/// 加载静态非 PIE ELF64 到 flat 内存，返回入口/栈顶/brk。
pub fn load_static(mem: &mut GuestMem, bytes: &[u8], mem_size: u64) -> Result<crate::LoadedImage, String> {
    if bytes.len() < 64 || &bytes[0..4] != b"\x7fELF" {
        return Err("not an ELF".into());
    }
    if bytes[4] != 2 || bytes[5] != 1 {
        return Err("need ELF64 LE".into());
    }
    if bytes[7] != 0 && bytes[7] != 183 {
        return Err(format!("unsupported abi osabi={}", bytes[7]));
    }
    let mut r = Reader { b: bytes, off: 0 };
    let _ident = { r.off = 16 };
    let e_type = r.u16();
    let e_machine = r.u16();
    if e_machine != 0xB7 {
        return Err(format!("need aarch64 (0xB7), got {e_machine:#x}"));
    }
    r.off = 0x18;
    let e_entry = r.u64();
    let _phoff = r.u64();
    let _shoff = r.u64();
    r.off = 0x36;
    let e_phentsize = r.u16();
    let e_phnum = r.u16();

    // PIE（ET_DYN）在 P3 支持；P1 拒绝并给出明确错误。
    if e_type == 3 {
        return Err("PIE (ET_DYN) not supported until P3 — compile with -no-pie".into());
    }
    if e_type != 2 {
        return Err(format!("unsupported e_type={e_type}"));
    }

    let mut max_end = 0u64;
    for i in 0..e_phnum as usize {
        r.off = 0x40 + i * e_phentsize as usize;
        let p_type = r.u32();
        let _p_flags = r.u32();
        let p_offset = r.u64();
        let p_vaddr = r.u64();
        let _p_paddr = r.u64();
        let p_filesz = r.u64();
        let p_memsz = r.u64();
        if p_type != PT_LOAD {
            continue;
        }
        let end = p_vaddr.checked_add(p_memsz).ok_or("seg overflow")?;
        if end > mem_size {
            return Err(format!("segment {p_vaddr:#x}+{p_memsz} exceeds guest mem"));
        }
        let end_file = p_vaddr.checked_add(p_filesz).ok_or("seg overflow")?;
        if end_file > mem_size {
            return Err(format!("segment file part exceeds guest mem"));
        }
        if p_filesz > 0 {
            let src = bytes
                .get(p_offset as usize..(p_offset + p_filesz) as usize)
                .ok_or("ph filesz out of file")?;
            mem.write(p_vaddr, src)?;
        }
        // p_memsz > p_filesz 的 BSS 部分已是 0（flat mem 初始为 0）
        max_end = max_end.max(end);
    }
    if max_end == 0 {
        return Err("no PT_LOAD segments".into());
    }
    // 栈：放高地址，向下生长；预留 auxv/argv 空间
    let stack_top = mem_size & !0xf;
    Ok(crate::LoadedImage { entry: e_entry, stack_top, brk: (max_end + 0xfff) & !0xfff })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_non_elf() {
        let mut m = GuestMem::new(4096);
        assert!(load_static(&mut m, b"nope", 4096).is_err());
    }
}
