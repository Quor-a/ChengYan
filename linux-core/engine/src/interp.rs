//! Tier 0 解释器：A64 解码 + 执行。
//!
//! P2 覆盖整数核心子集（musl/busybox 高频）：DP imm/reg（movz/movk/mov/subs/add/sub）、
//! 分支（b/bl/ret/br/cb{,n}z/b.cond/adr/adrp）、load/store（ldp/stp/ldr/str imm）、
//! svc 陷阱、nop。SIMD/FP 按 appine 结论：同 ISA host 上 P4 直接执行原生指令，
//! 解释器不做 SoftFloat。
//!
//! 参考：ARM ARM C4.x（A64 编码），解码按位段直读，避免魔法数字堆砌。

use crate::{Cpu, ExitReason, MemBackend};

#[derive(Clone, Copy, PartialEq, Debug)]
enum ShiftType { Lsl, Lsr, Asr, Ror }

/// 执行一条指令。返回 None = 继续，Some(reason) = 退出。
pub fn step<M: MemBackend>(cpu: &mut Cpu, mem: &mut M) -> Result<Option<ExitReason>, String> {
    let pc0 = cpu.pc;
    let instr = mem.read32(pc0).map_err(|e| format!("fetch {pc0:#x}: {e}"))?;
    cpu.pc = pc0.wrapping_add(4);
    exec(cpu, mem, instr, pc0)
}

fn exec<M: MemBackend>(cpu: &mut Cpu, mem: &mut M, i: u32, pc0: u64) -> Result<Option<ExitReason>, String> {
    use ExitReason::*;
    let _tr = std::env::var("CH_TRACE").is_ok();
    if _tr { eprintln!("exec pc={:#x} i={:#010x} fam1={:07b} fam2={:07b}", pc0, i, (i>>23)&0x3F, (i>>23)&0x3F); }

    // ---- svc ----
    if i & 0xFFE0_0000 == 0xD400_0000 {
        return Ok(Some(Syscall));
    }

    // ---- NOP / hint ----
    if i == 0xD503_201F {
        return Ok(None);
    }
    if i & 0xFFFF_F00F == 0xD503_300F {
        return Ok(None);
    }

    // ---- 数据处理-单源：RBIT/REV16/REV/REV32/REV64/CLZ/CLS（汇编器真值 2026-11）----
    // sf 0101101 b22:21=10 opc(13:12) bit10 00000 Rn Rd
    if i & 0x7F80_0000 == 0x5A80_0000 && (i >> 22) & 3 == 3 {
        let sf = (i >> 31) & 1;
        let opc = (i >> 10) & 0xF;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let v = reg_xzr(cpu, rn) & if sf == 1 { u64::MAX } else { 0xFFFF_FFFF };
        let r = match opc {
            0 if sf == 0 => (v as u32).reverse_bits() as u64,       // RBIT w
            0 => v.reverse_bits(),                                  // RBIT
            1 if sf == 0 => { let b = (v as u32).to_le_bytes(); u32::from_le_bytes([b[1], b[0], b[3], b[2]]) as u64 }, // REV16 w
            1 => { let b = v.to_le_bytes(); u64::from_le_bytes([b[1], b[0], b[3], b[2], b[5], b[4], b[7], b[6]]) }, // REV16
            2 if sf == 0 => (v as u32).swap_bytes() as u64,         // REV w
            2 => { let b = v.to_le_bytes(); u64::from_le_bytes([b[3], b[2], b[1], b[0], b[7], b[6], b[5], b[4]]) }, // REV32
            3 if sf == 1 => v.swap_bytes(),                         // REV x
            4 if sf == 0 => (v as u32).leading_zeros() as u64,      // CLZ w
            4 => v.leading_zeros() as u64,                          // CLZ
            5 if sf == 0 => (!(v as i32)).leading_zeros() as u64,   // CLS w
            5 => (!v).leading_zeros() as u64,                       // CLS
            _ => return Err(format!("[undef-dataproc1] {i:#010x}")),
        };
        set_xzr(cpu, rd, r);
        return Ok(None);
    }

    // ---- Load/Store exclusive & ordered（汇编器真值定准，2026-11）----
    // size(31:30) 001(29:27) 000(26:24) o2L(23:22) [b21_20] Rs Rn Rt
    // L(bit22)=1: LDAXR(01)/LDAR(11) → 载入；L=0: STXR(00)/STLXR(00, b21_20=1 为 STLR)
    if (i >> 27) & 7 == 1 && (i >> 24) & 7 == 0 {
        // size 00=字节 01=半字 10=字(32) 11=双字(64)（汇编器真值定准，2026-11）
        let size = (i >> 30) & 3;
        let l = (i >> 22) & 1;
        let b2120 = (i >> 20) & 3;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let addr = if rn == 31 { cpu.sp } else { cpu.x[rn] };
        let mask: u64 = match size {
            0 => 0xFF,
            1 => 0xFFFF,
            2 => 0xFFFF_FFFF,
            _ => u64::MAX,
        };
        if l == 1 {
            // LDAXR/LDXR/LDAR：普通载入（单核无竞争，acquire 序无额外效果）
            let v = match size {
                0 => ld8(mem, addr)?,
                1 => ld16(mem, addr)?,
                2 => mem.read32(addr)? as u64,
                _ => mem.read64(addr)?,
            };
            set_reg(cpu, rt, v);
            return Ok(None);
        }
        if b2120 == 1 {
            // STLR：普通存储
            match size {
                0 => st8(mem, addr, reg_xzr(cpu, rt))?,
                1 => st16(mem, addr, reg_xzr(cpu, rt))?,
                2 => st32(mem, addr, reg_xzr(cpu, rt))?,
                _ => mem.write64(addr, reg_xzr(cpu, rt))?,
            }
            return Ok(None);
        }
        // STXR/STLXR：单核恒成功，按宽度存储并把状态寄存器清 0
        match size {
            0 => st8(mem, addr, reg_xzr(cpu, rt))?,
            1 => st16(mem, addr, reg_xzr(cpu, rt))?,
            2 => st32(mem, addr, reg_xzr(cpu, rt))?,
            _ => mem.write64(addr, reg_xzr(cpu, rt))?,
        }
        let rs = ((i >> 16) & 31) as usize;
        set_reg(cpu, rs, 0);
        return Ok(None);
    }
    if i & 0xFFFF_F00F == 0xD503_300F {
        return Ok(None);
    }

    // ---- Load/Store exclusive & ordered（汇编器真值定准，2026-11）----
    // size(31:30) 001(29:27) 000(26:24) o2L(23:22) [b21_20] Rs Rn Rt
    // L(bit22)=1: LDAXR(01)/LDAR(11) → 载入；L=0: STXR(00)/STLXR(00, b21_20=1 为 STLR)
    if (i >> 27) & 7 == 1 && (i >> 24) & 7 == 0 {
        let size = (i >> 30) & 3; // 0=32位 1=64位（32/64 只有这两档常用）
        let l = (i >> 22) & 1;
        let b2120 = (i >> 20) & 3;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let addr = if rn == 31 { cpu.sp } else { cpu.x[rn] };
        let nbytes = 4u64 << size;
        let mask = if nbytes == 8 { u64::MAX } else { 0xFFFF_FFFF };
        if l == 1 {
            // LDAXR/LDXR/LDAR：普通载入（单核无竞争，acquire 序无额外效果）
            let v = mem.read64(addr)? & mask;
            set_reg(cpu, rt, v);
            return Ok(None);
        }
        if b2120 == 1 {
            // STLR：普通存储
            let v = reg_xzr(cpu, rt) & mask;
            mem.write64(addr, v)?;
            return Ok(None);
        }
        // STXR/STLXR：单核恒成功，存储并把状态寄存器清 0
        let v = reg_xzr(cpu, rt) & mask;
        mem.write64(addr, v)?;
        let rs = ((i >> 16) & 31) as usize;
        set_reg(cpu, rs, 0);
        return Ok(None);
    }

    // ---- Move wide immediate: movz/movk/movn ----
    // sf opc(30:29) 100101(28:23) hw(22:21) imm16 Rd（MOVN=00 MOVZ=10 MOVK=11）
    if (i >> 23) & 0x3F == 0b100101 {
        let sf = i >> 31;
        let opc = (i >> 29) & 3;
        let hw = (i >> 21) & 3;
        let imm = (i >> 5) & 0xFFFF;
        let rd = (i & 31) as usize;
        let shift = (hw as u64) << 4;
        let val = (imm as u64) << (shift as u32);
        let cur = reg(cpu, rd, sf);
        let newv = match opc {
            0 => !(val), // movn
            2 => val,    // movz
            3 => (cur & !(0xFFFFu64 << shift)) | val, // movk
            _ => return Ok(Some(Undef(i))),
        };
        set_reg(cpu, rd, mask64(newv, sf));
        return Ok(None);
    }

    // ---- PC-relative addressing: adr/adrp ----
    // op immlo:2 1 0 0 0 0 immhi:19 Rd
    if i & 0x1F00_0000 == 0x1000_0000 {
        let op = (i >> 31) & 1;
        let rd = (i & 31) as usize;
        let immlo = (i >> 29) & 3;
        let immhi = (i >> 5) & 0x7_FFFF;
        // 21 位立即数必须符号扩展（adrp 负偏移：向后引用静态数据，缺扩展=偏 2^33）
        let imm21 = ((((immhi << 2) | immlo) as i64) << 43) >> 43; // sext21
        let imm = imm21 << (if op == 1 { 12 } else { 0 });
        let base = if op == 1 { pc0 & !0xFFF } else { pc0 };
        set_reg(cpu, rd, (base as i64 + imm) as u64);
        return Ok(None);
    }

    // ---- Add/subtract immediate ----
    // sf 0 0 1 0 0 0 1 [sh] 0 imm12 Rn Rd  (add/sub imm)
    if i & 0x1F00_0000 == 0x1100_0000 {
        let sf = i >> 31;
        let op = (i >> 30) & 1; // 0=add 1=sub
        let s = (i >> 29) & 1;  // flag-setting (subs/cmps)
        let sh = (i >> 22) & 1;
        let imm = ((i >> 10) & 0xFFF) << (if sh == 1 { 12 } else { 0 });
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_sp(cpu, rn);
        let b = if op == 1 { 0u64.wrapping_sub(imm as u64) } else { imm as u64 };
        let r = a.wrapping_add(b);
        if s == 1 {
            if op == 1 { set_flags_sub(cpu, a, imm as u64, sf); } else { set_flags_add(cpu, a, imm as u64, sf); }
        }
        // Rd=31：S=0 时恒为 SP（含 add sp,xN,#imm / mov sp,xN）；S=1（ADDS/SUBS）Rd=31 为 XZR 丢弃
        if rd == 31 && s == 0 {
            set_sp(cpu, 31, mask64(r, sf));
        } else if rd != 31 {
            set_sp(cpu, rd, mask64(r, sf));
        }
        return Ok(None);
    }

    // ---- Conditional compare：CCMP/CCMN（imm/reg）----
        let shift_t = ((i >> 22) & 3) as u8;
        let n = (i >> 21) & 1;
        let rm = ((i >> 16) & 31) as usize;
        let imm6 = (i >> 10) & 63;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg(cpu, rn, sf);
        let b = shift_val(reg(cpu, rm, sf), imm6 as u64, shift_t, if sf == 1 { 64u32 } else { 32u32 });
        let operand = if n == 1 { !b } else { b };
        let r = match opc {
            0 => a & operand,
            1 => a | operand,
            2 => a ^ operand,
            3 => { set_flags_logical(cpu, a & operand, sf); a & operand }
            _ => unreachable!(),
        };
        set_reg(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- Add/subtract shifted register ----
    // sf 0 0 0 1 0 1 1 shift:2 0(bit21=0!) Rm:5 imm6 Rn Rd（bit21=1 时是扩展寄存器族）
    if i & 0x1F00_0000 == 0x0B00_0000 && (i >> 21) & 1 == 0 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let s = (i >> 29) & 1;
        let shift_t = ((i >> 22) & 3) as u8;
        let rm = ((i >> 16) & 31) as usize;
        let imm6 = (i >> 10) & 63;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn); // shifted 族 31 恒为 XZR（qemu 微测：neg x1,x1=Rn31 得 0-x1；SP 只在 extended/immediate 族可用）
        let b = shift_val(reg(cpu, rm, sf), imm6 as u64, shift_t, if sf == 1 { 64u32 } else { 32u32 });
        let (r, op_a, op_b) = if op == 1 { (a.wrapping_sub(b), a, b) } else { (a.wrapping_add(b), a, b) };
        if s == 1 {
            if op == 1 { set_flags_sub(cpu, a, b, sf); } else { set_flags_add(cpu, op_a, op_b, sf); }
        }
        set_reg(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- Add/subtract extended register（bit21=1）----
    // sf op S 1011 shift:2 1 opt:3 100 Rm:5 Rn:5 Rd:5
    // opt: 000=uxtb 001=uxth 010=uxtw 011=uxtx 100=sxtb 101=sxth 110=sxtw 111=sxtx
    if i & 0x1F00_0000 == 0x0B00_0000 && (i >> 21) & 1 == 1 && (i >> 10) & 7 == 4 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let s = (i >> 29) & 1;
        let sh = (i >> 22) & 7;
        let opt = (i >> 13) & 7;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let m = reg_xzr(cpu, rm);
        let ext: u64 = match opt {
            0 => m & 0xFF,
            1 => m & 0xFFFF,
            2 => m & 0xFFFF_FFFF,
            3 => m,
            4 => (m & 0xFF) as i8 as i64 as u64,
            5 => (m & 0xFFFF) as i16 as i64 as u64,
            6 => (m & 0xFFFF_FFFF) as i32 as i64 as u64,
            _ => m,
        };
        let b = ext.wrapping_shl(sh as u32);
        // 32 位运算（sf=0）：源按 W 语义（高 32 位本应为 0）。extended 族 Rn=31 恒读 SP（含 cmp sp,xN，S 无关）；
        // Rd=31 仅 S=0 写 SP（qemu 微测 spn/spn2）
        let a = if rn == 31 { cpu.sp } else { reg_xzr(cpu, rn) };
        let r = if op == 1 { a.wrapping_sub(b) } else { a.wrapping_add(b) };
        if s == 1 {
            if op == 1 { set_flags_sub(cpu, a, b, sf); } else { set_flags_add(cpu, a, b, sf); }
        }
        if s == 0 && rd == 31 { cpu.sp = mask64(r, sf); } else { set_reg(cpu, rd, mask64(r, sf)); }
        return Ok(None);
    }

    // ---- Compare & branch ----
    // sf 011010 op(bit24) 0 imm19 Rt（掩码含 sf 位）
    if i & 0x7E00_0000 == 0x3400_0000 {
        let sf = i >> 31;
        let op = (i >> 24) & 1;
        let imm19 = (i >> 5) & 0x7_FFFF;
        let rt = (i & 31) as usize;
        let v = reg(cpu, rt, sf);
        let zero = v == 0;
        if zero != (op == 1) {
            let off = sign_extend(imm19 as i64, 19) * 4;
            cpu.pc = (pc0 as i64 + off) as u64;
        }
        return Ok(None);
    }

    // ---- Unconditional branch / BL ----
    // 0 0 1 0 1 [op] imm26
    if i & 0x7C00_0000 == 0x1400_0000 {
        let op = (i >> 31) & 1;
        let imm26 = i & 0x3FF_FFFF;
        let off = sign_extend(imm26 as i64, 26) * 4;
        if op == 1 {
            cpu.set_lr(cpu.pc); // pc0+4
        }
        cpu.pc = (pc0 as i64 + off) as u64;
        return Ok(None);
    }

    // ---- Branch register / RET ----
    // 0 1 1 0 1 0 0 opc:2 11111 [opc2] 11111 Rn [opc3] 00000
    if i & 0xFE1F_FC1F == 0xD61F_0000 || i & 0xFE1F_FC1F == 0xD63F_0000 {
        let opc = (i >> 21) & 3;
        let rn = ((i >> 5) & 31) as usize;
        if opc == 2 && rn == 30 {
            cpu.pc = cpu.lr(); // RET
            return Ok(None);
        }
        if opc == 0 {
            cpu.pc = cpu.x[rn]; // BR
            return Ok(None);
        }
        if opc == 1 {
            cpu.set_lr(cpu.pc); // BLR：lr = pc0+4
            cpu.pc = cpu.x[rn];
            return Ok(None);
        }
        return Ok(Some(Undef(i)));
    }

    // ---- Conditional branch ----
    // 0 1 0 1 0 1 0 0 imm19 0 cond:4
    if i & 0xFF00_0010 == 0x5400_0000 {
        let imm19 = (i >> 5) & 0x7_FFFF;
        let cond = i & 15;
        let cond_met = check_cond(cpu, cond as u8);
        if cond_met {
            let off = sign_extend(imm19 as i64, 19) * 4;
            cpu.pc = (pc0 as i64 + off) as u64;
        }
        return Ok(None);
    }

    // ---- Logical (immediate)：AND/ORR/EOR/ANDS + DecodeBitMasks ----
    // sf 00 100100 N immr:6 imms:6 Rn Rd
    if (i >> 23) & 0x3F == 0b0100100 {
        let sf = i >> 31;
        let opc = (i >> 29) & 3;
        let n = (i >> 22) & 1;
        let immr = (i >> 16) & 0x3F;
        let imms = (i >> 10) & 0x3F;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn);
        // DecodeBitMasks（ARM ARM 简化式）
        let npipe = ((n as u64) << 6) | (!(imms as u64) & 0x3F);
        let len = 64 - npipe.leading_zeros(); // highest set bit (1-based); 0=none
        if len < 1 { return Ok(Some(Undef(i))); }
        let levels = (1u64 << len) - 1;
        let s_v = (imms as u64) & levels;
        let r_v = (immr as u64) & levels;
        if s_v == levels { return Ok(Some(Undef(i))); }
        let esize = 1u64 << len;
        let welem = (1u64 << (s_v + 1)) - 1;
        // ROR(welem, R) in esize
        let rotr = (r_v % esize) as u32;
        let elem = welem.rotate_right(rotr) & (esize.wrapping_mul(2).checked_sub(1).map(|m| m & ((esize<<1)-1)).unwrap_or(u64::MAX));
        // 注意 rotate_right 是 64 位上的旋转；welem < 2^esize，先放入低 esize 位再旋转取模 esize
        let elem = welem.rotate_right(rotr) & (if esize == 64 { u64::MAX } else { esize - 1 }).wrapping_add(0).wrapping_mul(0).wrapping_add(if esize == 64 { u64::MAX } else { esize - 1 });
        // 正确做法：把 welem 视作 esize 位，在 esize 位宽内旋转
        let elem = if esize == 64 { welem.rotate_right(rotr) } else {
            let w = welem & (esize - 1);
            w.rotate_right(rotr) & (esize - 1) | (w.rotate_right(rotr) >> esize) & 0
        };
        let _ = elem;
        // replicate elem 到 esize=64/32
        let mut wmask = 0u64;
        if esize == 64 {
            wmask = welem.rotate_right(rotr);
        } else {
            // 在 esize 位内旋转 welem
            let w = welem & (esize - 1);
            let r2 = w.rotate_right(rotr); // 64 位旋转后需折回
            let rotated = ((w << (esize - rotr)) & (esize.wrapping_sub(1) << 0)) | (w >> rotr);
            let _ = r2;
            wmask = rotated;
            // replicate 到 64 位
            let mut acc = 0u64;
            let mut filled = 0;
            while filled < 64 {
                acc |= wmask << filled;
                filled += esize;
            }
            wmask = acc;
        }
        if std::env::var("CH_TRACE").is_ok() {
            eprintln!("andimm: opc={} n={} imms={} immr={} len={} esize={} welem={:#x} wmask={:#x} a={:#x} rd={}", opc, n, imms, immr, len, esize, welem, wmask, a, rd);
        }
        let r = match opc {
            0 => a & wmask,
            1 => a | wmask,
            2 => a ^ wmask,
            3 => { set_flags_logical(cpu, a & wmask, sf); a & wmask }
            _ => return Ok(Some(Undef(i))),
        };
        if opc == 3 { set_xzr(cpu, rd, mask64(r, sf)); } else { set_sp(cpu, rd, mask64(r, sf)); }
        return Ok(None);
    }

    // ---- Test bit and branch: tbz/tbnz ----
    // b5 011011 0 b40:14 Rt
    if i & 0x7E00_0000 == 0x3600_0000 {
        let b5 = (i >> 31) & 1;
        let op = (i >> 24) & 1;
        let b40 = (i >> 19) & 0x1F;
        let bit = (b5 << 5) | b40;
        let rt = (i & 31) as usize;
        let imm14 = (i >> 5) & 0x3FFF;
        let v = reg_xzr(cpu, rt);
        let bit_set = (v >> bit) & 1 == 1;
        // tbz(op=0)：位为 0 跳；tbnz(op=1)：位为 1 跳
        if bit_set == (op == 1) {
            let off = sign_extend(imm14 as i64, 14) * 4;
            cpu.pc = (pc0 as i64 + off) as u64;
        }
        return Ok(None);
    }

    // ---- SIMD: dup scalar→vector / umov (mov) vector→general ----
    // dup: Q 001110 000 imm5 000111 Rn Rd；(i & 0xFFC00000) == 0x4E000C00
    if i & 0xFFE0_FC00 == 0x4E00_0C00 {
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let imm5 = (i >> 16) & 0x1F;
        let v = reg_xzr(cpu, rn);
        let q = if imm5 & 1 != 0 {
            let b = (v & 0xFF) as u128;
            let mut acc = 0u128;
            for k in 0..16 { acc |= b << (8 * k); }
            acc
        } else if imm5 & 2 != 0 {
            let h = (v & 0xFFFF) as u128;
            let mut acc = 0u128;
            for k in 0..8 { acc |= h << (16 * k); }
            acc
        } else if imm5 & 4 != 0 {
            let w = (v & 0xFFFF_FFFF) as u128;
            let mut acc = 0u128;
            for k in 0..4 { acc |= w << (32 * k); }
            acc
        } else {
            let d: u128 = (v & u64::MAX) as u128;
            d | (d << 64)
        };
        cpu.q[rd] = q;
        return Ok(None);
    }
    // ins (general→element)：0Q001110 000 imm5 Rn 000111 Rd（mov v0.s[1], w1）
    // imm5 = index*2*sz + sz，sz = imm5 最低位（1/2/4/8 = b/h/s/d），汇编器真值校验
    if i & 0xFFE0_FC00 == 0x4E00_1C00 {
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let imm5 = (i >> 16) & 0x1F;
        let sz = (1u32 << imm5.trailing_zeros()).max(1); // sz = imm5 最低位（b/h/s/d = 1/2/4/8）
        let idx = ((imm5 / sz - 1) / 2) as u32;
        let v = reg_xzr(cpu, rn);
        let off = 8 * idx * sz;
        let mask = ((1u128 << (8 * sz)) - 1) << off;
        cpu.q[rd] = (cpu.q[rd] & !mask) | ((v as u128) << off & mask);
        return Ok(None);
    }
    // sxtl/uxtl：汇编器真值族掩码（0F10A440/0F08A4A4/2F20A461/2F08A4E6）→ 0x9F00FC00==0x0F00A400
    // esize = imm5 ∈ {8,16,32}；取源低 64 位 esize 条 lane 符号/零扩展到 2×esize；U=b29
    if i & 0x9F00_FC00 == 0x0F00_A400 {
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let esize = (i >> 16) & 0x3F; // immh:immb=bits21:16 真值即 esize（0x08=8b,0x10=4h,0x20=2s）
        if esize == 0 || esize > 32 { /* 非 sxtl/uxtl，落入后续族 */ } else {
        let unsigned = (i >> 29) & 1 == 1;
        let v = cpu.q[rn];
        let lanes = 64 / esize;
        let mut acc = 0u128;
        for k in 0..lanes {
            let raw = (v >> (esize * k)) & ((1u128 << esize) - 1);
            let ext: u128 = if unsigned {
                raw
            } else {
                // sext 到目标 lane 宽度 2*esize（不是 128 位！），否则负值糊满全寄存器
                ((((raw << (128 - 2 * esize)) as i128) >> (128 - 2 * esize)) as u128)
                    & (((1u128 << (2 * esize)) - 1) as u128)
            };
            acc |= ext << (2 * esize * k);
        }
        cpu.q[rd] = acc;
        return Ok(None);
        }
    }
    // umov/smov/mov (to general): Q 0011100 size imm5 001111 Rn Rd（0x0E00_3C00/0x4E00_3C00；bit22=0→smov 符号扩展）
    if i & 0xFFE0_FC00 == 0x0E00_3C00 || i & 0xFFE0_FC00 == 0x4E00_3C00 {
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let imm5 = (i >> 16) & 0x1F;
        let signed = (i >> 22) & 1 == 0; // opc0=SMOV，opc1=UMOV
        let v = cpu.q[rn];
        let (out, bits) = if imm5 & 1 != 0 {
            let idx = (imm5 >> 1) as usize;
            (((v >> (8 * idx)) & 0xFF) as u64, 8u32)
        } else if imm5 & 2 != 0 {
            let idx = (imm5 >> 2) as usize;
            (((v >> (16 * idx)) & 0xFFFF) as u64, 16u32)
        } else if imm5 & 4 != 0 {
            let idx = (imm5 >> 3) as usize;
            (((v >> (32 * idx)) & 0xFFFF_FFFF) as u64, 32u32)
        } else {
            let idx = (imm5 >> 4) as usize;
            (((v >> (64 * idx)) & u64::MAX as u128) as u64, 64u32)
        };
        let out = if signed && bits < 64 {
            let sh = 64 - bits;
            ((out << sh) as i64 >> sh) as u64 // 符号扩展
        } else { out };
        set_xzr(cpu, rd, out);
        return Ok(None);
    }

    // ---- SIMD ORR（mov v.16b, v.16b 别名）----
    if i & 0xBFE0_1C00 == 0x0EA0_1C00 {
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        cpu.q[rd] = cpu.q[rn] | cpu.q[rm];
        return Ok(None);
    }

    // ---- FP 标量（最小集）：sf?(31) 11110 type(23:22) 1 opcode(20:12) Rn Rd ----
    // 汇编器真值：op12（bits20:12）fmul=0x000 / fdiv=0x021 / fadd=0x022(b11:10=2) / fsub=0x022(b11:10=3) /
    // fcmp=0x022(b11:10=0) / fmov 寄存器=0x004 / fmov imm=0x001 / scvtf=0x020 / ucvtf=0x030 /
    // fcvtzs=0x180 / fcvtzu=0x190 / fcvt s←d=0x024 / d←s=0x02c / fmov s,w|d,x=0x070
    if (i >> 24) & 0x1F == 0b11110 && i & 0x0020_0000 != 0 {
        // 汇编器真值（aarch64-linux-gnu-as 对拍）：
        //  op12=(i>>12)&0x1FF 含 Rm(20:16)<<4；bits14:10 区分族：0x00=2源(需sub2==2)、
        //  0x08=FCMP、0x10=1源、0x04=fmov 立即数。2源 opcode(bits15:12)：
        //  fmul=0/fdiv=1/fadd=2/fsub=3/fmax=4/fmin=5/fnmul=8；第二操作数是 Rm(20:16) 不是 Rd。
        let op12 = (i >> 12) & 0x1FF;
        let sub2 = (i >> 10) & 3;
        let b14_10 = (i >> 10) & 0x1F;
        let rtype = (i >> 22) & 3; // 0=s/32-bit, 1=d/64-bit
        let rn = ((i >> 5) & 31) as usize;
        let rm = ((i >> 16) & 31) as usize;
        let rd = (i & 31) as usize;
        let is2src = sub2 == 2; // 2源族全部 bit11=1 bit10=0；1源/fcmp/fmov-imm 均 sub2==0（汇编器真值）
        let getf = |cpu: &Cpu, r: usize| -> f64 {
            let v = cpu.q[r];
            if rtype == 1 { f64::from_bits(v as u64) } else { f32::from_bits(v as u32) as f64 }
        };
        let setf = |cpu: &mut Cpu, r: usize, x: f64| {
            cpu.q[r] = if rtype == 1 { x.to_bits() as u128 } else { ((x as f32).to_bits() as u128) };
        };
        let setf2 = |cpu: &mut Cpu, r: usize, x: f64| {
            // 2源运算：单精度按 f32 舍入（原实现直接塞 f64 bits，sNaN/舍入语义错）
            cpu.q[r] = if rtype == 1 { x.to_bits() as u128 } else { ((x as f32).to_bits() as u128) };
        };
        match op12 {
            _ if is2src && op12 & 0xF == 0x0 => { let r = getf(cpu, rn) * getf(cpu, rm); setf2(cpu, rd, r); return Ok(None); } // fmul
            _ if is2src && op12 & 0xF == 0x1 => { let r = getf(cpu, rn) / getf(cpu, rm); setf2(cpu, rd, r); return Ok(None); } // fdiv
            _ if is2src && op12 & 0xF == 0x2 => { let r = getf(cpu, rn) + getf(cpu, rm); setf2(cpu, rd, r); return Ok(None); } // fadd
            _ if is2src && op12 & 0xF == 0x3 => { let r = getf(cpu, rn) - getf(cpu, rm); setf2(cpu, rd, r); return Ok(None); } // fsub
            _ if is2src && op12 & 0xF == 0x4 => { let (a, b) = (getf(cpu, rn), getf(cpu, rm)); setf2(cpu, rd, if a > b || b.is_nan() { a } else { b }); return Ok(None); } // fmax
            _ if is2src && op12 & 0xF == 0x5 => { let (a, b) = (getf(cpu, rn), getf(cpu, rm)); setf2(cpu, rd, if a < b || b.is_nan() { a } else { b }); return Ok(None); } // fmin
            _ if is2src && op12 & 0xF == 0x8 => { let r = -(getf(cpu, rn) * getf(cpu, rm)); setf2(cpu, rd, r); return Ok(None); } // fnmul
            0x004 if b14_10 == 0x10 => { let v = getf(cpu, rn); setf(cpu, rd, v); return Ok(None); } // fmov 寄存器
            0x00C if b14_10 == 0x10 => { let v = getf(cpu, rn); setf(cpu, rd, v.abs()); return Ok(None); } // fabs
            0x014 if b14_10 == 0x10 => { let v = getf(cpu, rn); setf(cpu, rd, -v); return Ok(None); } // fneg
            0x01C if b14_10 == 0x10 => { let v = getf(cpu, rn); setf(cpu, rd, v.sqrt()); return Ok(None); } // fsqrt
            _ if b14_10 == 0x08 => {
                // FCMP：N=less Z=equal；无序 → C=1 V=1；(i>>3)&1 = 与 0.0 比较
                // op12 低 3 位 = 010（opcode），bits[8:4] 是 Rm——此前精确匹配 0x002 导致 Rm≠0 永远 Undef（seq 崩真因）
                if op12 & 7 != 2 { return Ok(Some(Undef({ eprintln!("[undef] L{}", line!()); i }))); }
                let a = getf(cpu, rn);
                let b = if (i >> 3) & 1 == 1 { 0.0 } else { getf(cpu, rm) };
                cpu.nzcv = if a.is_nan() || b.is_nan() { 0b0110 } else if a < b { 0b1000 } else if a == b { 0b0100 } else { 0b0010 };
                return Ok(None);
            }
            _ if (i >> 5) & 0xFF == 0x80 && op12 & 1 == 1 => {
                // fmov 立即数：bits12:5 = 1000_0000，imm8 = bits20:13（= op12>>1）；
                // value = (-1)^a × (1 + b3b2b1b0/16) × 2^(exp-3)，exp = (NOT imm8[6]) imm8[5] imm8[4]
                //（汇编器真值拟合：1.0→0x70 10.0→0x24 3.5→0x0C 0.5→0x60）
                let imm8 = op12 >> 1;
                let a = (imm8 >> 7) & 1;
                let exp = (((1 - ((imm8 >> 6) & 1)) << 2) | (((imm8 >> 5) & 1) << 1) | ((imm8 >> 4) & 1)) as i32;
                let frac = (imm8 & 0xF) as f64;
                let v = if a == 1 { -(1.0 + frac / 16.0) } else { 1.0 + frac / 16.0 };
                let v = v * 2f64.powi(exp - 3);
                cpu.q[rd] = if rtype == 1 { v.to_bits() as u128 } else { ((v as f32).to_bits() as u128) };
                return Ok(None);
            }
            0x020 | 0x030 => {
                // SCVTF/UCVTF：源宽 = sf(bit31)：0=w 1=x；目标宽 = rtype
                let src = reg_xzr(cpu, rn);
                let v = if i >> 31 == 1 {
                    let w = if op12 == 0x020 { src as i64 as f64 } else { src as f64 };
                    w
                } else {
                    let w32 = src as u32;
                    if op12 == 0x020 { w32 as i32 as f64 } else { w32 as f64 }
                };
                cpu.q[rd] = if rtype == 1 { v.to_bits() as u128 } else { ((v as f32).to_bits() as u128) };
                return Ok(None);
            }
            0x180 | 0x190 => {
                // FCVTZS/FCVTZU：目标宽 = sf(bit31)：0=w 1=x；截断向零
                let v = getf(cpu, rn);
                let r = if i >> 31 == 1 {
                    if op12 == 0x180 { v as i64 as u64 } else { (v as u64).max(0) }
                } else {
                    if op12 == 0x180 { v as i32 as u64 } else { (v as u32) as u64 }
                };
                set_xzr(cpu, rd, r);
                return Ok(None);
            }
            0x024 => { // fcvt s←d
                let v = f64::from_bits(cpu.q[rn] as u64);
                cpu.q[rd] = (v as f32).to_bits() as u128;
                return Ok(None);
            }
            0x02c => { // fcvt d←s
                let v = f32::from_bits(cpu.q[rn] as u32);
                cpu.q[rd] = (v as f64).to_bits() as u128;
                return Ok(None);
            }
            0x060 | 0x070 => {
                // FMOV general↔SIMD（objdump 真值：0x9e660000=fmov x0,d0=读；0x9e670040=fmov d0,x0=写）。
                // bits20:16=6 → SIMD→通用（读）；=7 → 通用→SIMD（写）。旧代码两臂都当写，每次
                // fp→int 搬运都把目标 SIMD 高 64 位清零 → musl fmt_fp 全部归 0（awk 数字归零真因）。
                let _ = (op12, rtype);
                if op12 == 0x060 {
                    // 读：rtype=1(d/x) 取低 64 位；rtype=0(s/w) 取低 32 位
                    set_xzr(cpu, rd, if rtype == 1 { cpu.q[rn] as u64 } else { cpu.q[rn] as u32 as u64 });
                } else {
                    let v = reg_xzr(cpu, rn);
                    cpu.q[rd] = if rtype == 1 { v as u128 } else { v as u128 & 0xFFFF_FFFF };
                }
                return Ok(None);
            }
            _ if i & 0xDFAF_FC00 == 0x5EA1_B800 => {
                // 标量 SIMD 转换（llvm 汇编对拍）：FCVTZS/FCVTZU Dd,Dn / Sd,Sn（截断向零，llvm 真值
                // 0x5ee1b820=fcvtzs d0,d1 / 0x5ea1b820=fcvtzs s0,s1）。U=bit29，type=bit22（1=d）。
                // 整数结果以原始位型存入目标 d/s 寄存器低 64/32 位（awk int() 路径 fcvtzs→scvtf 往返）。
                let v = getf(cpu, rn);
                let bits = if (i >> 29) & 1 == 1 { (v.max(0.0)) as u64 } else { (v as i64) as u64 };
                cpu.q[rd] = if (i >> 22) & 1 == 1 { bits as u128 } else { bits as u128 & 0xFFFF_FFFF };
                return Ok(None);
            }
            _ if i & 0xDFAF_FC00 == 0x5E21_D800 => {
                // 标量 SIMD SCVTF/UCVTF Dd,Dn / Sd,Sn（llvm 真值 0x5e61d820 / 0x5e21d820）
                let src = cpu.q[rn] as u64;
                let v = if (i >> 22) & 1 == 1 {
                    let w = if (i >> 29) & 1 == 1 { src as f64 } else { (src as i64) as f64 };
                    w
                } else {
                    let w32 = src as u32;
                    if (i >> 29) & 1 == 1 { w32 as f64 } else { (w32 as i32) as f64 }
                };
                setf(cpu, rd, v);
                return Ok(None);
            }
            0x0F0 if rtype == 2 => {
                // fmov v0.d[1], rx：通用寄存器写入 SIMD 高 64 位（仅 d[1] 形式可编码）
                let v = reg_xzr(cpu, rn);
                cpu.q[rd] = (cpu.q[rd] & 0xFFFF_FFFF_FFFF_FFFF) | ((v as u128) << 64);
                return Ok(None);
            }
            0x0E0 if rtype == 2 => {
                // fmov rx, vn.d[1]：读 SIMD 高 64 位到通用寄存器——源是 Rn（0x9eae0001=fmov x1,v0.d[1]：
                // 旧代码误读 q[rd]，long double 高位取错 → musl fmt_fp 数字全 0）
                set_xzr(cpu, rd, (cpu.q[rn] >> 64) as u64);
                return Ok(None);
            }
            _ => {}
        }
        return Ok(Some(Undef(i)));
    }

    // sf 00 11011 op54(23:21) 0 Rm Ra Rn Rd；汇编器真值：op54 000=MADD/MSUB(o0@15) / 010=SMULH / 110=UMULH
    if i & 0x1F00_0000 == 0x1B00_0000 {
        // 汇编器真值：U=bit23；bits22:21 → 00=MADD/MSUB(o0@15) / 01=加宽 l 族 / 10=MULH / 11=Undef
        let sf = i >> 31;
        let u = (i >> 23) & 1;
        let cls = (i >> 21) & 3;
        let o0 = (i >> 15) & 1;  // MADD:0 / MSUB:1（MUL 时无效）
        let rm = ((i >> 16) & 31) as usize;
        let ra = ((i >> 10) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let m = reg_xzr(cpu, rm);
        let n = reg_xzr(cpu, rn);
        let prod = match (u, cls) {
            (0, 0b00) => {
                let a = reg_xzr(cpu, ra);
                let pr = (m as i64).wrapping_mul(n as i64) as u64;
                if o0 == 0 { pr.wrapping_add(a) } else { a.wrapping_sub(pr) }
            }
            (0, 0b01) => {
                // 加宽 l 族：Xd = Ra ± (Wn ×s Wm)（Ra=31 即 SMULL 别名）
                let n32 = (reg_xzr(cpu, rn) as u32) as i32 as i64;
                let m32 = (reg_xzr(cpu, rm) as u32) as i32 as i64;
                let pr = n32.wrapping_mul(m32) as u64;
                let a = reg_xzr(cpu, ra);
                if o0 == 0 { a.wrapping_add(pr) } else { a.wrapping_sub(pr) }
            }
            (1, 0b01) => {
                // UMADDL/UMULL：Xd = Ra ± (Wn ×u Wm)
                let n32 = (reg_xzr(cpu, rn) as u32) as u64;
                let m32 = (reg_xzr(cpu, rm) as u32) as u64;
                let pr = n32.wrapping_mul(m32);
                let a = reg_xzr(cpu, ra);
                if o0 == 0 { a.wrapping_add(pr) } else { a.wrapping_sub(pr) }
            }
            (0, 0b10) => (((m as i64 as i128) * (n as i64 as i128)) >> 64) as u64, // SMULH
            (1, 0b10) => (((m as u128) * (n as u128)) >> 64) as u64,               // UMULH
            _ => return Ok(Some(Undef(i))),
        };
        set_xzr(cpu, rd, mask64(prod, sf));
        return Ok(None);
    }

    // ---- EXTR/ROR #imm：sf 00 100111 N0 Rm imms Rn Rd（mask 落 0x1380_0000 族）----
    if i & 0x7FC0_0000 == 0x13C0_0000 && i & 0x0020_0000 == 0 {
        let sf = i >> 31;
        let rm = ((i >> 16) & 31) as usize;
        let lsb = ((i >> 10) & 63) as u32;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        // ARM 真值：EXTR Xd, Xn, Xm, #lsb = ((Xn:Xm) >> lsb)，Xn 是拼接的高 64 位
        // （此前写成 (Rm:Rn) 导致 musl smoothsort qsort 元数据被写坏 = ls 随机 SIGSEGV 根因）
        let (hi, lo) = (reg_xzr(cpu, rn), reg_xzr(cpu, rm));
        let r = if sf == 1 {
            if lsb == 0 { lo } else { ((hi << (64 - lsb)) | (lo >> lsb)) as u64 }
        } else {
            let hi32 = (hi & 0xFFFF_FFFF) as u64;
            let lo32 = (lo & 0xFFFF_FFFF) as u64;
            let cat = (hi32 << 32) | lo32;
            let l = lsb & 63;
            if l == 0 { cat & 0xFFFF_FFFF } else { ((cat >> l) | (cat << (64 - l))) & 0xFFFF_FFFF }
        };
        set_xzr(cpu, rd, r);
        return Ok(None);
    }

    // ---- EXTR：sf opc=00 100111 N=0 Rm imms Rn Rd（2026-11：ROR 拼接抽取）----
    if (i >> 23) & 0x3F == 0x27 && (i >> 29) & 3 == 0 && ((i >> 22) & 1) == 0 {
        let sf = i >> 31;
        let rm = ((i >> 16) & 31) as usize;
        let lsb = ((i >> 10) & 63) as u64;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let r = if sf == 1 {
            // ARM 真值：EXTR = ((Xn:Xm) >> lsb)，Xn 为高位（与上一分支同一语义）
            let lo = reg_xzr(cpu, rm);
            let hi = reg_xzr(cpu, rn);
            if lsb == 0 { lo } else { (lo >> lsb) | (hi << (64 - lsb)) }
        } else {
            // 32 位分支同样修正：Xn 高 32 位、Xm 低 32 位
            let lo = (reg_xzr(cpu, rm) & 0xFFFF_FFFF) as u128;
            let hi = (reg_xzr(cpu, rn) & 0xFFFF_FFFF) as u128;
            let l = lsb & 31;
            (((hi << 32) | lo) >> l) as u64 & 0xFFFF_FFFF
        };
        set_xzr(cpu, rd, r);
        return Ok(None);
    }

    // ---- Bitfield / shift immediate：UBFM(LSR/LSL/UBFX)/SBFM(ASR/SXTx/SBFX) ----
    // sf opc:2 100110 N immr:6 imms:6 Rn Rd
    if i & 0x1F80_0000 == 0x1300_0000 {
        let sf = i >> 31;
        let opc = (i >> 29) & 3; // 00=SBFM 10=UBFM
        let n = (i >> 22) & 1;
        let immr = ((i >> 16) & 63) as u64;
        let imms = ((i >> 10) & 63) as u64;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        if opc == 3 || n != sf { return Ok(Some(Undef(i))); }
        let size: u64 = if sf == 1 { 64 } else { 32 };
        let v = reg_xzr(cpu, rn) & (if size == 64 { u64::MAX } else { 0xFFFF_FFFF });
        let ones = |w: u64| if w >= 64 { u64::MAX } else { (1u64 << w) - 1 };
        let r = if opc == 2 {
            // UBFM
            if imms == size - 1 {
                v >> immr // LSR #immr
            } else if imms + 1 == immr {
                let l = (size - immr) & (size - 1); // LSL #(size-immr)
                if l >= 64 { 0 } else { v << l }
            } else if imms >= immr {
                (v >> immr) & ones(imms - immr + 1) // UBFX
            } else {
                // imms < immr：ROR 后取低 imms+1 位
                let sh = (size - immr) & (size - 1);
                let rot = if sh >= 64 { 0 } else { v << sh } | (v >> (immr & (size - 1)));
                rot & ones(imms + 1)
            }
        } else {
            // SBFM
            let w = imms.wrapping_sub(immr).wrapping_add(1) & 63;
            if imms == size - 1 {
                let sh = immr & (size - 1);
                if sh >= 64 { 0 } else { ((v << (0)) as i64) } // 占位，下面覆盖
            } else if imms < immr {
                let sh = (size - immr) & (size - 1);
                let rot = (if sh >= 64 { 0 } else { v << sh }) | (v >> (immr & (size - 1)));
                let w = imms + 1;
                let sign = (rot >> (w - 1)) & 1;
                let mask = ones(w);
                if sign == 1 { (rot & mask) | !mask } else { rot & mask }
            } else {
                // SBFX：提取并符号扩展
                let val = (v >> immr) & ones(w);
                let sign = (val >> (w - 1)) & 1;
                if sign == 1 { val | !ones(w) } else { val }
            }
        };
        if opc == 0 && imms == size - 1 {
            // ASR #immr（含 64 位正确符号移位）
            let sh = immr & 63;
            let r = (((v as i64) >> sh.min(63)) as u64).wrapping_sub((immr >= 64) as u64 * (v >> 63 & 1));
            let r = if immr >= 64 { if v >> 63 == 1 { u64::MAX } else { 0 } } else { ((v as i64) >> sh) as u64 };
            set_xzr(cpu, rd, if sf == 1 { r } else { (r as i32) as i64 as u64 });
            return Ok(None);
        }
        set_xzr(cpu, rd, if sf == 1 { r } else { (r as i32) as i64 as u64 });
        return Ok(None);
    }

    // ---- Conditional compare：CCMP/CCMN（imm/reg）----
    // 汇编器真值（2026-11）：bits28:21 = 11010010，b30: 1=CCMP 0=CCMN，
    // b20:16=imm5/Rm，b15:12=cond，b11:10: 00=寄存器 10=立即数，nzcv=b3:0
    if (i >> 21) & 0xFF == 0b1101_0010 && ((i >> 10) & 3) != 1 {
        let sf = i >> 31;
        let is_cmp = (i >> 30) & 1 == 1;
        let is_imm = (i >> 10) & 3 == 2;
        let a = (i >> 16) & 31;      // imm5 或 Rm
        let cond = ((i >> 12) & 15) as u8;
        let nzcv = i & 15;
        let rn = ((i >> 5) & 31) as usize;
        let v = reg_sp(cpu, rn);
        if check_cond(cpu, cond) {
            let b = if is_imm { a as u64 } else { reg_xzr(cpu, a as usize) };
            // 64 位下 a 作为立即数仍是 0..31
            if is_cmp { set_flags_sub(cpu, v, b, sf); } else { set_flags_add(cpu, v, b, sf); }
        } else {
            cpu.nzcv = nzcv;
        }
        return Ok(None);
    }

    // ---- ADC/SBC：带进位/借位加减（sf 00 110101 00 Rm 000000/100000 Rn Rd）----
    // 编码真值（2026-11）：bits28:21=11010000；bit30=0 加(ADC/ADCS)、bit30=1 减(SBC/SBCS)；
    // bits15:10=000000 不置 flags，100000 置 flags。0xda0d0061=sbc x1,x3,x13。
    if (i >> 21) & 0xFF == 0xD0 && ((i >> 10) & 0x3F == 0 || (i >> 10) & 0x3F == 0x20) && (i >> 29) & 1 == 0 {
        let sf = i >> 31;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let sets_flags = ((i >> 10) & 0x3F) == 0x20;
        let is_sub = (i >> 30) & 1 == 1; // bit30=1 → SBC/SBCS（ARM 真值）
        let c_in = (cpu.nzcv >> 1) & 1; // C=bit1
        let (a, b) = (reg_xzr(cpu, rn), reg_xzr(cpu, rm));
        let (r, cf, vf) = if sf == 1 {
            if is_sub {
                // SBC: Rd = Rn - Rm - (1-C)
                let sub = (b as u128) + (1 - c_in as u128);
                let full = (a as u128).wrapping_sub(sub);
                let rr = full as u64;
                let c = (a as u128) >= sub; // SBC：C=1 表示无借位
                let v = ((a as i64) < 0) == ((b as i64) < 0) && ((a as i64) < 0) != ((rr as i64) < 0);
                (rr, c, v)
            } else {
                let full = (a as u128) + (b as u128) + (c_in as u128);
                let rr = full as u64;
                let c = (full >> 64) & 1 == 1;
                let v = ((a as i64) < 0) == ((b as i64) < 0) && ((a as i64) < 0) != ((rr as i64) < 0);
                (rr, c, v)
            }
        } else {
            if is_sub {
                let sub = (b as u32 as u64) + (1 - c_in as u64);
                let full = (a as u32 as u64).wrapping_sub(sub);
                let rr = (full as u32) as u64;
                let c = (a as u32 as u64) >= sub; // SBC：C=1 无借位
                let a32 = a as u32; let b32 = b as u32; let r32 = rr as u32;
                let v = ((a32 as i32) < 0) == ((b32 as i32) < 0) && ((a32 as i32) < 0) != ((r32 as i32) < 0);
                (rr, c, v)
            } else {
                let full = (a as u32 as u64) + (b as u32 as u64) + (c_in as u64);
                let rr = (full as u32) as u64;
                let c = full > 0xFFFF_FFFF;
                let a32 = a as u32; let b32 = b as u32; let r32 = rr as u32;
                let v = ((a32 as i32) < 0) == ((b32 as i32) < 0) && ((a32 as i32) < 0) != ((r32 as i32) < 0);
                (rr, c, v)
            }
        };
        if sets_flags { cpu.nzcv = ((r == 0) as u32) << 2 | (cf as u32) << 1 | (vf as u32); }
        set_xzr(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- Divide：SDIV/UDIV（bits15:10 = 2/3）----
    if i & 0x7FC0_0000 == 0x1AC0_0000 && (i >> 10) & 0x3F >= 2 && (i >> 10) & 0x3F <= 3 {
        let sf = i >> 31;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn);
        let b = reg_xzr(cpu, rm);
        // 除零结果为 0（ARM 语义）
        let r = if b == 0 { 0 }
            else if (i >> 10) & 3 == 3 { a / b }
            else { ((a as i64).wrapping_div(b as i64)) as u64 };
        set_xzr(cpu, rd, mask64(r, i >> 31));
        return Ok(None);
    }

    // ---- Conditional select：CSSEL/CSINC/CSINV/CSNEG ----
    // sf 00 110101 op 0 Rm cond 00(bits11:10) Rn Rd
    if i & 0x3FE0_0000 == 0x1A80_0000 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let sel = (i >> 10) & 1; // csel/csinv=0；csinc/csneg=1（汇编器真值）
        let rm = ((i >> 16) & 31) as usize;
        let cond = ((i >> 12) & 15) as u8;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        // CSSEL/CSINC 族 Rn/Rm 为通用寄存器，31 = ZR（不允许 SP）；
        // 此前 Rn 用 reg_sp 读 → 条件成立的 cset 全部写入 SP 低 32 位
        let n = reg_xzr(cpu, rn);
        let m = reg_xzr(cpu, rm);
        let r = if check_cond(cpu, cond) {
            n
        } else {
            match (op, sel) {
                (0, 0) => m,              // csinc 的 else：rm+1？不——csinc=op0,bit10=1
                (0, 1) => m.wrapping_add(1),
                (1, 0) => !m,
                (1, 1) => m.wrapping_neg(),
                _ => return Ok(Some(Undef(i))),
            }
        };
        set_xzr(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- SIMD movi/mvni/fmov-zero ----
    // 汇编器真值（2026-11，NDK clang 逐条验证）：Q=bit31（bit30 恒 1），U(bit29)，
    // cmode=bits15:12，imm8 = bits20:16<<5 | bits9:5（此前 (i>>13)&0xFF 全错：
    // movi v1.2d,#0=0x6F00E401 被解码成字节填充 7 → floatscan 比较/awk 数字全错）
    if (i >> 24) & 0x8F == 0x0F && i & 0x0800 == 0 && i & 0x0400 != 0 {
        let rd = (i & 31) as usize;
        let imm8 = (((i >> 16) & 0x1F) << 5) as u32 | ((i >> 5) & 0x1F) as u32;
        let cmode = (i >> 12) & 0xF;
        let u = (i >> 29) & 1;
        let q = i >> 31; // 全宽/半宽
        let value: u128 = match cmode {
            0b1110 if u == 0 => { // MOVI .16b/.8b：字节填充
                let b = imm8 & 0xFF;
                let mut acc = 0u128;
                for k in 0..8 { acc |= (b as u128) << (8 * k); }
                acc | (acc << 64)
            }
            0b1110 => 0, // U=1：FMOV 零常量 / MOVI .2d #0（全零）
            0b1100 => { // 16 位填充：imm8<<8
                let h = ((imm8 << 8) as u128) & 0xFFFF;
                let mut acc = 0u128;
                for k in 0..4 { acc |= h << (16 * k); }
                let full = acc | (acc << 64);
                if u == 1 { !full } else { full }
            }
            0b1101 => { // 16 位填充：imm8 直接占半字
                let h = imm8 as u128;
                let mut acc = 0u128;
                for k in 0..4 { acc |= h << (16 * k); }
                let full = acc | (acc << 64);
                if u == 1 { !full } else { full }
            }
            0b1000 | 0b1001 | 0b1010 => { // 64 位 imm8 << (0/8/16)
                let sh = ((cmode & 3) * 8) as u32;
                let d = ((imm8 as u64) << sh) as u128;
                let full = d | (d << 64);
                if u == 1 { !full } else { full }
            }
            c @ 0b0000..=0b0110 => { // 32 位 imm8 << (0/8/16/24)：cmode=0ab0，sh=ab*8
                let sh = (c >> 1) * 8;
                let w = ((imm8 as u32) << sh) as u128;
                let mut acc = 0u128;
                for k in 0..4 { acc |= w << (32 * k); }
                let full = acc | (acc << 64);
                if u == 1 { !full } else { full }
            }
            _ => return Ok(Some(Undef({ eprintln!("[undef] movi cmode={:x}", cmode); i }))), // cmode 1111 = FMOV 标量立即数、cmode 1011/1110+U 未覆盖
        };
        cpu.q[rd] = value & if q == 1 { u128::MAX } else { (1u128 << 64) - 1 };
        return Ok(None);
    }

    // ---- Variable shift：LSLV/LSRV/ASRV/RORV ----
    if i & 0x7FC0_0000 == 0x1AC0_0000 && (i >> 10) & 0x0F == 0 && (i >> 10) & 0x3F <= 48 {
        let sf = i >> 31;
        // 两源移位 opcode 在 bits[11:10]（字段 15:10 值 8..=11），此前误取 bits[14:15] 恒为 0 → 全被当 LSLV
        let opc2 = (i >> 10) & 3;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let width: u32 = if sf == 1 { 64 } else { 32 };
        let v = reg_xzr(cpu, rn);
        let amt = (reg_xzr(cpu, rm) as u32) & (width - 1);
        let r = match opc2 {
            0 => if amt >= width { 0 } else { v.wrapping_shl(amt) },
            1 => if amt >= width { 0 } else { v >> amt },
            // ASRV：W 形按 32 位符号位扩展（此前按 64 位 bit63，W 值高位为 0 会算错符号）
            2 => if sf == 1 { ((v as i64) >> amt) as u64 } else { (((v as u32) as i32) >> amt) as u32 as u64 },
            3 => v.rotate_right(amt),
            _ => return Ok(Some(Undef(i))),
        };
        set_xzr(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- System register: mrs/msr ----
    if i & 0xFFC0_0000 == 0xD500_0000 {
        let l = (i >> 21) & 1; // 1=mrs 0=msr
        let op0 = (i >> 19) & 3;
        let op1 = (i >> 16) & 7;
        let crn = (i >> 12) & 15;
        let crm = (i >> 8) & 15;
        let op2 = (i >> 5) & 7;
        let rt = (i & 31) as usize;
        if l == 1 && op0 == 3 && crn == 13 && crm == 0 && op2 == 2 {
            // mrs xRt, tpidr_el0（用户态 TLS 基址）
            set_xzr(cpu, rt, cpu.tpidr);
            return Ok(None)
        }
        if l == 0 && op0 == 3 && crn == 13 && crm == 0 && op2 == 2 {
            // msr tpidr_el0, xRt：musl 设置线程指针，直接记录
            cpu.tpidr = reg_xzr(cpu, rt);
            return Ok(None);
        }
        if l == 1 && op0 == 3 && crn == 14 && crm == 0 && op2 == 1 {
            // mrs xRt, cntvct_el0：虚拟计数器（近似：用单调递增计步）
            set_xzr(cpu, rt, cpu.counter);
            return Ok(None);
        }
        if l == 1 && op0 == 3 && crn == 0 && crm == 0 && op2 == 0 {
            // mrs xRt, midr_el1
            set_xzr(cpu, rt, 0x410FD414); // Cortex-A55 样值
            return Ok(None);
        }
        if l == 0 && op0 == 3 && crn == 13 && crm == 0 && op2 == 2 {
            cpu.tpidr = cpu.x[rt];
            return Ok(None);
        }
        // 其余 mrs 读 0、msr 忽略（P3 按需精确）
        if l == 1 { set_xzr(cpu, rt, 0); }
        return Ok(None);
    }

    // ---- Conditional select: csel/csinc/csinv/csneg ----
    // sf 00 11010100 op2:2 0 cond:2? Rm cond op2 Rn Rd（掩码 0x7FE0_0C00 == 0x1A80_0000）
    if i & 0x7FE0_0C00 == 0x1A80_0000 {
        let sf = i >> 31;
        let op2 = (i >> 10) & 3;
        let cond = ((i >> 12) & 15) as u8;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn);
        let b = reg_xzr(cpu, rm);
        let met = check_cond(cpu, cond);
        let r = match op2 {
            0 => if met { a } else { b },                                   // csel
            1 => if met { a } else { b.wrapping_add(1) },                   // csinc
            2 => if met { a } else { !b },                                  // csinv
            3 => if met { a } else { b.wrapping_neg() },                    // csneg
            _ => return Ok(Some(Undef(i))),
        };
        set_xzr(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- Add/subtract (extended register)：cmp/mov 变体（uxtx/sxtw…）----
    // sf 00 010110 1 ext:3 0 Rm option:3 imm3 Rn Rd（bit21=1, mask 0x1F80_0000 == 0x0B20_0000）
    if i & 0x1F00_0000 == 0x0B00_0000 && i & 0x0020_0000 != 0 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let s = (i >> 29) & 1;
        let rm = ((i >> 16) & 31) as usize;
        let option = (i >> 13) & 7;
        let imm3 = (i >> 10) & 7;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = if rn == 31 { cpu.sp } else { reg_xzr(cpu, rn) }; // extended 族 Rn=31 恒读 SP（S 无关）；Rd=31 仅 S=0 写 SP（qemu 微测 spn）
        let m_raw = cpu.x[rm];
        let m = match option {
            0 => (m_raw & 0xFF) as u64,                    // uxtb
            1 => (m_raw & 0xFFFF) as u64,                  // uxth
            2 => (m_raw & 0xFFFF_FFFF) as u64,             // uxtw
            6 => ((m_raw & 0xFFFF_FFFF) as i32) as i64 as u64, // sxtw
            3 | 7 => m_raw,                                // uxtx / sxtx
            4 => ((m_raw & 0xFF) as i8) as i64 as u64,     // sxtb
            5 => ((m_raw & 0xFFFF) as i16) as i64 as u64,  // sxth
            _ => return Ok(Some(Undef(i))),
        };
        let m = m << imm3;
        let r = if op == 1 { a.wrapping_sub(m) } else { a.wrapping_add(m) };
        if s == 1 {
            if op == 1 { set_flags_sub(cpu, a, m, sf); } else { set_flags_add(cpu, a, m, sf); }
        }
        if s == 0 && rd == 31 { cpu.sp = mask64(r, sf); } else { set_xzr(cpu, rd, mask64(r, sf)); } // Rd=31 S=0 → SP
        return Ok(None);
    }


    // ---- Load/Store register offset
    // sf 0 0 11010110 Rm o2:3 00000 Rn Rd（mask 0x7FE0_0000 == 0x1AC0_0000）
    if i & 0x7FE0_0000 == 0x1AC0_0000 {
        let sf = i >> 31;
        let op2 = (i >> 10) & 7;
        let rm = ((i >> 16) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn);
        let b = reg_xzr(cpu, rm);
        let width = if sf == 1 { 64u32 } else { 32 };
        let r = match op2 {
            1 => { // udiv
                if b == 0 { 0 } else { a / b }
            }
            2 => { // sdiv? 实际 sdiv=1? udiv=1, sdiv=2? ARM: SDIV o2=001(1)? — SDIV=1, UDIV=2? 正确：SDIV=0b001, UDIV=0b010
                if b == 0 { 0 } else { ((a as i64) / (b as i64)) as u64 }
            }
            3 => a.wrapping_shl((b & (width as u64 - 1)) as u32), // lslv? o2=001 lslv... ARM: LSLV=0b001? 实际 LSLV=001, LSRV=010, ASRV=011, RORV=100, SDIV=001? — 按标准: SDIV=001? 不对。
            _ => return Ok(Some(Undef(i))),
        };
        let _ = r;
        return Ok(Some(Undef(i)));
    }

    // ---- Data processing (1 source)：rev/rev16/rev32/clz/cls/rbit/neg ----
    // sf 0 0 11010110 opcode:6 00000 Rn Rd（mask 0x7FE0_0000 == 0x5AC0_0000）
    if i & 0x7FE0_0000 == 0x5AC0_0000 {
        let sf = i >> 31;
        let opcode = (i >> 10) & 0x3F;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg_xzr(cpu, rn);
        let width = if sf == 1 { 64u32 } else { 32 };
        let r = match opcode {
            0 => a.rotate_right(8) & 0x00FF_00FF_00FF_00FF, // rbit? 实际 rbit=0; 需精确
            _ => return Ok(Some(Undef(i))),
        };
        let _ = r; let _ = width;
        return Ok(Some(Undef(i)));
    }

    // ---- Load/Store register offset：ldr/str xt, [xn, xm, lsl #n / sxtx] ----
    // size 11 V 01 1 0 Rm option(13:11) S(12) 10 Rn Rt（family: 0xF860_0800 掩码 0xFFC0_0C00 内 32/64 位）
    if i & 0x3F80_0C00 == 0x3800_0800 {
        let size = (i >> 30) & 3;
        let opc = (i >> 22) & 3;
        let rm = ((i >> 16) & 31) as usize;
        let option = (i >> 13) & 7;
        let sh = (i >> 12) & 1;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let m = cpu.x[rm];
        let shift = sh * size; // 位移量 = S × log2(元素字节数)=S×size（ldrh uxtw#1→1，ldr x uxtx S=1→3）
        let off = match option {
            3 => m.wrapping_shl(shift),                          // lsl
            7 => m.wrapping_shl(shift),                          // sxtx（64 位同值）
            2 => ((m & 0xFFFF_FFFF) as u64).wrapping_shl(shift), // uxtw
            6 => (((m & 0xFFFF_FFFF) as i32) as i64 as u64).wrapping_shl(shift), // sxtw
            _ => return Ok(Some(Undef(i))),
        };
        let addr = reg_sp(cpu, rn).wrapping_add(off);
        match (size, opc) {
            (3, 1) => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v); }
            (3, 0) => { mem.write64(addr, reg_xzr(cpu, rt))?; }
            (2, 1) => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v & 0xFFFF_FFFF); }
            (2, 2) => { let v = mem.read64(addr)?; let w = v & 0xFFFF_FFFF; set_xzr(cpu, rt, ((w as u32) as i32) as i64 as u64); } // ldrsw
            (2, 0) => { mem.write64(addr, reg_xzr(cpu, rt) & 0xFFFF_FFFF)?; }
            _ => return Ok(Some(Undef(i))),
        }
        return Ok(None);
    }

    // ---- Load/Store register offset：ldr/str xt, [xn, xm, lsl #n / sxtx] ----
    // size 11 V 01 1 0 Rm option(13:11) S(12) 10 Rn Rt（family: 0xF860_0800 掩码 0xFFC0_0C00 内 32/64 位）
    if i & 0x3F80_0C00 == 0x3800_0800 {
        let size = (i >> 30) & 3;
        let opc = (i >> 22) & 3;
        let rm = ((i >> 16) & 31) as usize;
        let option = (i >> 13) & 7;
        let sh = (i >> 12) & 1;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let m = cpu.x[rm];
        let shift = if sh == 1 { 3 } else { 0 };
        let off = match option {
            3 => m.wrapping_shl(shift),                          // lsl
            7 => m.wrapping_shl(shift),                          // sxtx（64 位同值）
            2 => ((m & 0xFFFF_FFFF) as u64).wrapping_shl(shift), // uxtw
            6 => (((m & 0xFFFF_FFFF) as i32) as i64 as u64).wrapping_shl(shift), // sxtw
            _ => return Ok(Some(Undef(i))),
        };
        let addr = reg_sp(cpu, rn).wrapping_add(off);
        match (size, opc) {
            (3, 1) => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v); }
            (3, 0) => { mem.write64(addr, reg_xzr(cpu, rt))?; }
            (2, 1) => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v & 0xFFFF_FFFF); }
            (2, 0) => { mem.write64(addr, reg_xzr(cpu, rt) & 0xFFFF_FFFF)?; }
            _ => return Ok(Some(Undef(i))),
        }
        return Ok(None);
    }

    // ---- LDR literal（字面量池）：opc(31:30) 011 V00 imm19 Rt ----
    if i & 0x3B00_0000 == 0x1800_0000 {
        let rt = (i & 31) as usize;
        let opc = (i >> 30) & 3;
        let imm19 = (i >> 5) & 0x7FFFF;
        let off = sign_extend(imm19 as i64, 19) * 4;
        let addr = (pc0 as i64 + off) as u64;
        match opc {
            0 => { let v = mem.read32(addr)?; set_xzr(cpu, rt, v as u64); }
            1 => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v); }
            2 => { let v = mem.read32(addr)?; cpu.q[rt] = v as u128; }
            _ => { let lo = mem.read64(addr)? as u128; let hi = mem.read64(addr.wrapping_add(8))? as u128; cpu.q[rt] = lo | (hi << 64); }
        }
        return Ok(None);
    }

    // ---- LD1/ST1 多结构 no-offset（SIMD load/store multiple structures）----
    // 0(31) Q(30) 001100(29:24) 0(23,no-offset) L(22) 0(21) 00000(20:16) opcode(15:12) size(11:10) Rn Rt
    // opcode 15:12（汇编器真值）：0111=1reg / 1010=2reg / 0110=3reg / 0010=4reg（LD1/ST1 同码；LD2=8 LD3=4 LD4=0）
    if (i >> 23) & 0x7F == 0b0011000 && (i >> 21) & 1 == 0 {
        let l = (i >> 22) & 1;
        let q = (i >> 30) & 1; // 同 movi：Q 位在 bit30（见上方注释）
        let opcode = (i >> 12) & 0xF;
        let size = (i >> 10) & 3;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let regs: u64 = match opcode { 0b0111 => 1, 0b1010 => 2, 0b0110 => 3, 0b0010 => 4, _ => return Ok(Some(Undef(i))) };
        let elbytes = 1u64 << size;               // b/h/s/d
        let regbytes = if q == 1 { 16 } else { 8 }; // 每寄存器承载的字节数（Q=0 取半宽）
        let total = regs * regbytes;
        let addr = reg_sp(cpu, rn);
        if l == 1 {
            let mut buf = Vec::with_capacity(total as usize);
            for k in 0..total { buf.push(mem.read8(addr.wrapping_add(k))?); }
            let mut pos = 0usize;
            for r in 0..regs as usize {
                let mut v = 0u128;
                for b in 0..regbytes as usize { v |= (buf[pos + b] as u128) << (8 * b); }
                pos += regbytes as usize;
                cpu.q[(rt + r) & 31] = v;
            }
        } else {
            let mut pos = 0usize;
            for r in 0..regs as usize {
                let v = cpu.q[(rt + r) & 31];
                for b in 0..regbytes as usize { st8(mem, addr.wrapping_add(pos + b), (v >> (8 * b)) as u8)?; }
                pos += regbytes as usize;
            }
        }
        return Ok(None);
    }

// ---- Load/Store pair ----
    // opc(31:30) 10101(29:25) V? mode(24:23)=10 offset/01 post/11 pre, L(22)
    // imm7(21:15) 按 8/4 字节缩放；Rt2(14:10) Rn(9:5) Rt(4:0)
    if i & 0x3E00_0000 == 0x2800_0000 || i & 0x3E00_0000 == 0x2C00_0000 {
        let opc = (i >> 30) & 3;
        let l = (i >> 22) & 1;
        let mode = (i >> 23) & 3;
        let imm7 = (i >> 15) & 0x7F;
        let rt2 = ((i >> 10) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        // 汇编器真值（2026-11）：opc=0 → 32 位对（W/S，×4）；opc=1 → 64 位对（V=0 仅 LDPSW，V=1 D，×8）；
        // opc=2 → V=0 X 对 ×8 / V=1 Q 对 ×16。此前 opc=1(V=1) STP/LDP D 被当 Undef、opc=0 按双 8 字节访存。
        let scale: u32 = match opc { 0 => 2, 1 => 3, _ => if (i >> 26) & 1 == 1 { 4 } else { 3 } };
        if opc == 1 && (i >> 26) & 1 == 0 {
            // LDPSW：两个符号扩展 32 位
            let base = reg_sp(cpu, rn);
            let off = (sign_extend(imm7 as i64, 7) as u64) << 2;
            let addr = match mode {
                2 => base.wrapping_add(off),
                3 => { set_sp(cpu, rn, mask64(base.wrapping_add(off), 1)); base.wrapping_add(off) }
                _ => base,
            };
            let lo = mem.read32(addr)? as i32 as i64 as u64;
            let hi = mem.read32(addr.wrapping_add(4))? as i32 as i64 as u64;
            set_xzr(cpu, rt, lo);
            set_xzr(cpu, rt2, hi);
            if mode == 1 { set_sp(cpu, rn, mask64(base.wrapping_add(off), 1)); }
            return Ok(None);
        }
        if opc == 1 && (i >> 26) & 1 == 0 && l == 0 {
            return Ok(Some(Undef(i))); // opc=1 V=0 仅 LDPSW（上方已处理）
        }
        let off = (sign_extend(imm7 as i64, 7) as u64) << scale;
        let base_val = reg_sp(cpu, rn);
        let addr = match mode {
            2 => base_val.wrapping_add(off),                            // offset
            3 => { set_sp(cpu, rn, mask64(base_val.wrapping_add(off), 1)); base_val.wrapping_add(off) } // pre-index
            1 => base_val,                                              // post-index：访存后回写
            _ => return Ok(Some(Undef(i))),
        };
        if (i >> 26) & 1 == 1 && opc == 2 {
            // 128 位 SIMD pair
            if l == 1 {
                let lo0 = mem.read64(addr)? as u128; let hi0 = mem.read64(addr.wrapping_add(8))? as u128;
                let lo1 = mem.read64(addr.wrapping_add(16))? as u128; let hi1 = mem.read64(addr.wrapping_add(24))? as u128;
                cpu.q[rt] = lo0 | (hi0 << 64); cpu.q[rt2] = lo1 | (hi1 << 64);
            } else {
                let a = cpu.q[rt]; let b = cpu.q[rt2];
                mem.write64(addr, (a & u64::MAX as u128) as u64)?;
                mem.write64(addr.wrapping_add(8), (a >> 64) as u64)?;
                mem.write64(addr.wrapping_add(16), (b & u64::MAX as u128) as u64)?;
                mem.write64(addr.wrapping_add(24), (b >> 64) as u64)?;
            }
            if mode == 1 { set_sp(cpu, rn, mask64(base_val.wrapping_add(off), 1)); }
            return Ok(None);
        }
        let el = if opc == 0 { 4u64 } else { if opc == 2 && (i >> 26) & 1 == 1 { 16 } else { 8 } };
        let simd = (i >> 26) & 1 == 1;
        if el == 16 {
            // Q 对
            if l == 1 {
                let lo0 = mem.read64(addr)? as u128; let hi0 = mem.read64(addr.wrapping_add(8))? as u128;
                let lo1 = mem.read64(addr.wrapping_add(16))? as u128; let hi1 = mem.read64(addr.wrapping_add(24))? as u128;
                cpu.q[rt] = lo0 | (hi0 << 64); cpu.q[rt2] = lo1 | (hi1 << 64);
            } else {
                let a = cpu.q[rt]; let b = cpu.q[rt2];
                mem.write64(addr, (a & u64::MAX as u128) as u64)?;
                mem.write64(addr.wrapping_add(8), (a >> 64) as u64)?;
                mem.write64(addr.wrapping_add(16), (b & u64::MAX as u128) as u64)?;
                mem.write64(addr.wrapping_add(24), (b >> 64) as u64)?;
            }
        } else {
            // 8/4 字节元素对；SIMD 寄存器只动 q 低位，GP 用 x/zr
            let (a0, a1) = (addr, addr.wrapping_add(el));
            if l == 1 {
                let lo = if el == 4 { mem.read32(a0)? as u64 } else { mem.read64(a0)? };
                let hi = if el == 4 { mem.read32(a1)? as u64 } else { mem.read64(a1)? };
                if simd {
                    cpu.q[rt] = lo as u128; cpu.q[rt2] = hi as u128;
                } else {
                    set_xzr(cpu, rt, lo); set_xzr(cpu, rt2, hi);
                }
            } else {
                let lo = if simd { cpu.q[rt] as u64 } else { reg_xzr(cpu, rt) };
                let hi = if simd { cpu.q[rt2] as u64 } else { reg_xzr(cpu, rt2) };
                if el == 4 {
                    st32(mem, a0, lo)?; st32(mem, a1, hi)?;
                } else {
                    mem.write64(a0, lo)?; mem.write64(a1, hi)?;
                }
            }
        }
        if mode == 1 {
            set_sp(cpu, rn, mask64(base_val.wrapping_add(off), 1)); // post-index
        }
        return Ok(None);
    }

    // ---- LDR literal（字面量池）：opc(31:30) 011 V00 imm19 Rt ----
    if i & 0x3B00_0000 == 0x1800_0000 {
        let rt = (i & 31) as usize;
        let opc = (i >> 30) & 3;
        let imm19 = (i >> 5) & 0x7FFFF;
        let off = sign_extend(imm19 as i64, 19) * 4;
        let addr = (pc0 as i64 + off) as u64;
        match opc {
            0 => { let v = mem.read32(addr)?; set_xzr(cpu, rt, v as u64); }
            1 => { let v = mem.read64(addr)?; set_xzr(cpu, rt, v); }
            2 => { let v = mem.read32(addr)?; cpu.q[rt] = v as u128; }
            _ => { let lo = mem.read64(addr)? as u128; let hi = mem.read64(addr.wrapping_add(8))? as u128; cpu.q[rt] = lo | (hi << 64); }
        }
        return Ok(None);
    }

    // ---- Load/Store immediate: unscaled / post-index / pre-index ----
    // size:2 111 V 00 opc:2 0 imm9:9 mode:2 Rn Rt（V=0, bits29:27=111, 25:24=00, 21=0）
    if i & 0x3F20_0000 == 0x3800_0000 || (i & 0x3F00_0000) == 0x3C00_0000 && i & 0x0020_0000 == 0 { // GPR unscaled/post(V=0) + SIMD unscaled/post(V=1)，b21=0
        let size = (i >> 30) & 3;
        let opc = (i >> 22) & 3;
        let imm9 = (i >> 12) & 0x1FF;
        let mode = (i >> 10) & 3;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let off = sign_extend(imm9 as i64, 9) as u64;
        let base_val = reg_sp(cpu, rn);
        let addr = match mode {
            0 => base_val.wrapping_add(off),                    // LDUR/STUR
            1 => base_val,                                      // post-index: 先访后加
            3 => { set_sp(cpu, rn, base_val.wrapping_add(off)); base_val.wrapping_add(off) } // pre-index
            _ => return Ok(Some(Undef(i))),
        };
        let v = (i >> 26) & 1;
        let done = match (size, opc, v) {
            (0, 0, 1) => { // stur b（V=1：存 q 低字节）
                st8(mem, addr, (cpu.q[rt] & 0xFF) as u64).map(|_| true)
            }
            (0, 1, 1) => ld8(mem, addr).map(|b| { cpu.q[rt] = b as u128; true }),
            (1, 0, 1) => st16(mem, addr, (cpu.q[rt] & 0xFFFF) as u64).map(|_| true),
            (1, 1, 1) => ld16(mem, addr).map(|h| { cpu.q[rt] = h as u128; true }),
            (2, 0, 1) => st32(mem, addr, (cpu.q[rt] & 0xFFFF_FFFF) as u64).map(|_| true),
            (2, 1, 1) => mem.read32(addr).map(|w| { cpu.q[rt] = w as u128; true }),
            (3, 0, 1) => mem.write64(addr, (cpu.q[rt] & u64::MAX as u128) as u64).map(|_| true),
            (3, 1, 1) => mem.read64(addr).map(|x| { cpu.q[rt] = x as u128; true }),
            (0, 2, 1) => { // str qt（128 位）
                let lo = (cpu.q[rt] & u64::MAX as u128) as u64;
                let hi = (cpu.q[rt] >> 64) as u64;
                mem.write64(addr, lo)?;
                mem.write64(addr.wrapping_add(8), hi).map(|_| true)
            }
            (0, 3, 1) => { // ldr qt
                let lo = mem.read64(addr)? as u128;
                let hi = mem.read64(addr.wrapping_add(8))? as u128;
                cpu.q[rt] = lo | (hi << 64);
                Ok(true)
            }
            (3, 0, 0) => mem.write64(addr, reg_xzr(cpu, rt)).map(|_| true),
            (3, 1, 0) => mem.read64(addr).map(|v| { set_xzr(cpu, rt, v); true }),
            (2, 0, 0) => st32(mem, addr, reg_xzr(cpu, rt)).map(|_| true),
            (2, 1, 0) => mem.read32(addr).map(|v| { set_xzr(cpu, rt, v as u64); true }),
            (0, 0, 0) => st8(mem, addr, reg_xzr(cpu, rt)).map(|_| true),           // strb
            (0, 1, 0) => ld8(mem, addr).map(|v| { set_xzr(cpu, rt, v); true }),    // ldrb
            (1, 0, 0) => st16(mem, addr, reg_xzr(cpu, rt)).map(|_| true),          // strh
            (1, 1, 0) => ld16(mem, addr).map(|v| { set_xzr(cpu, rt, v); true }),   // ldrh
            (2, 2, 0) => mem.read32(addr).map(|w| { set_xzr(cpu, rt, w as i32 as i64 as u64); true }), // ldursw
            (1, 2, 0) => ld16(mem, addr).map(|h| { set_xzr(cpu, rt, (h as u16 as i16 as i64) as u64); true }), // ldursh (64)
            (0, 2, 0) => ld8(mem, addr).map(|b| { set_xzr(cpu, rt, (b as u8 as i8 as i64) as u64); true }),  // ldursb x
            (0, 3, 0) => ld8(mem, addr).map(|b| { set_xzr(cpu, rt, ((b as u8 as i8 as i32) as u32) as u64); true }), // ldursb w
            (1, 3, 0) => ld16(mem, addr).map(|h| { set_xzr(cpu, rt, ((h as u16 as i16 as i32 as u32) as u64)); true }), // ldrsh (32)
            _ => Err(format!("unsupported size/opc {size}/{opc}")),
        if mode == 1 {
            set_sp(cpu, rn, base_val.wrapping_add(off));
        }
        return Ok(None);
    }

    // ---- Load/Store unsigned immediate (ldr/str) ----
    // size:2 1 1 [opc:2] 1 [imm12] Rn Rt
    if i & 0x3B00_0000 == 0x3900_0000 && ((i >> 26) & 1 == 1 || (i >> 22) & 3 < 2) {
        let size = (i >> 30) & 3;
        let opc = (i >> 22) & 3;
        let imm12 = (i >> 10) & 0xFFF;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let scale = if (i >> 26) & 1 == 1 && opc >= 2 { 4 } else { size }; // SIMD Q ×16；GPR 按 size
        let off = (imm12 as u64) << scale;
        let addr = reg_sp(cpu, rn).wrapping_add(off);
        match (size, opc) {
            (0, 2) => { // str qt
                let lo = (cpu.q[rt] & u64::MAX as u128) as u64;
                let hi = (cpu.q[rt] >> 64) as u64;
                mem.write64(addr, lo)?; mem.write64(addr.wrapping_add(8), hi)?;
            }
            (0, 3, 1) => { // ldr qt
                let lo = mem.read64(addr)? as u128;
                let hi = mem.read64(addr.wrapping_add(8))? as u128;
                cpu.q[rt] = lo | (hi << 64);
                Ok(true)
            }
            (3, 0, 0) => mem.write64(addr, reg_xzr(cpu, rt)).map(|_| true),
            (3, 1, 0) => mem.read64(addr).map(|v| { set_xzr(cpu, rt, v); true }),
            (2, 0, 0) => mem.write64(addr, reg_xzr(cpu, rt) & 0xFFFF_FFFF).map(|_| true),
            (2, 1, 0) => mem.read64(addr).map(|v| { set_xzr(cpu, rt, v & 0xFFFF_FFFF); true }),
            (0, 0, 0) => st8(mem, addr, reg_xzr(cpu, rt)).map(|_| true),           // strb
            (0, 1, 0) => ld8(mem, addr).map(|v| { set_xzr(cpu, rt, v); true }),    // ldrb
            (1, 0, 0) => st16(mem, addr, reg_xzr(cpu, rt)).map(|_| true),          // strh
            (1, 1, 0) => ld16(mem, addr).map(|v| { set_xzr(cpu, rt, v); true }),   // ldrh
            _ => Err(format!("unsupported size/opc {size}/{opc}")),
}

/* ---------- 寄存器 / 位段工具 ---------- */

fn reg(cpu: &Cpu, r: usize, _sf: u32) -> u64 { reg_xzr(cpu, r) }
fn set_reg(cpu: &mut Cpu, r: usize, v: u64) { set_xzr(cpu, r, v) }

/// GPR 语境：31 = XZR（读 0，写丢弃）。
fn reg_xzr(cpu: &Cpu, r: usize) -> u64 { if r == 31 { 0 } else { cpu.x[r] } }
fn set_xzr(cpu: &mut Cpu, r: usize, v: u64) { if r != 31 { cpu.x[r] = v } }

/// SP 语境（add/sub imm 的 Rn/Rd、ldr/str 的 Rn、ldp/stp 的 Rn）：31 = SP。
fn reg_sp(cpu: &Cpu, r: usize) -> u64 { if r == 31 { cpu.sp } else { cpu.x[r] } }
fn set_sp(cpu: &mut Cpu, r: usize, v: u64) { if r == 31 { cpu.sp = v } else { cpu.x[r] = v } }

fn mask64(v: u64, sf: u32) -> u64 {
    if sf == 1 { v } else { v & 0xFFFF_FFFF }
}

fn sign_extend(v: i64, bits: u32) -> i64 {
    let sh = 64 - bits;
    (v << sh) >> sh
}

fn shift_val(v: u64, imm6: u64, st: u8, width: u32) -> u64 {
    match st {
        0 => v.wrapping_shl(imm6 as u32), // LSL（写入时再 mask）
        1 => v.wrapping_shr(imm6 as u32), // LSR（32 位零扩展语义一致）
        2 => { // ASR：32 位必须先按 bit31 符号扩展再移（neg w0,w0,asr#1 真因）
            if width == 32 {
                let amt = (imm6 & 31) as u32;
                (((v as u32) as i32).wrapping_shr(amt)) as u64
            } else {
                ((v as i64).wrapping_shr(imm6 as u32)) as u64
            }
        }
        3 => { // ROR：32 位按 32 位旋转（64 位旋转会丢 bit31 的环绕）
            if width == 32 {
                ((v as u32).rotate_right((imm6 & 31) as u32)) as u64
            } else {
                v.rotate_right(imm6 as u32)
            }
        }
        _ => v,
    }
}

fn set_flags_add(cpu: &mut Cpu, a: u64, b: u64, sf: u32) {
    let r = a.wrapping_add(b);
    let (n, z) = flag_nz(r, sf);
    let c = r < a;
    let v = (!((a ^ b) & 0x8000_0000_0000_0000 != 0)) && ((a ^ r) & 0x8000_0000_0000_0000 != 0);
    cpu.nzcv = n | z | ((c as u32) << 1) | (v as u32);
}

fn set_flags_sub(cpu: &mut Cpu, a: u64, b: u64, sf: u32) {
    let r = a.wrapping_sub(b);
    let (n, z) = flag_nz(r, sf);
    let c = a >= b;
    let v = ((a ^ b) & 0x8000_0000_0000_0000 != 0) && ((a ^ r) & 0x8000_0000_0000_0000 != 0);
    cpu.nzcv = n | z | ((c as u32) << 1) | (v as u32);
}

fn set_flags_logical(cpu: &mut Cpu, r: u64, sf: u32) {
    let (n, z) = flag_nz(r, sf);
    cpu.nzcv = n | z | (cpu.nzcv & 0b11); // C/V 不受影响（ARM 语义），保留原值
}

// 标准布局：N=bit3 Z=bit2 C=bit1 V=bit0（与 check_cond / CCMP 的 nzcv 字段一致）
fn flag_nz(r: u64, sf: u32) -> (u32, u32) {
    let eff = mask64(r, sf);
    let n = ((eff >> 31) as u32 & 1) << 3;
    (n, ((eff == 0) as u32) << 2)
}

fn check_cond(cpu: &Cpu, cond: u8) -> bool {
    let n = cpu.nzcv >> 3 & 1 == 1;
    let z = cpu.nzcv >> 2 & 1 == 1;
    let c = cpu.nzcv >> 1 & 1 == 1;
    let v = cpu.nzcv & 1 == 1;
    match cond {
        0 => z,
        1 => !z,
        2 => c,
        3 => !c,
        4 => n,
        5 => !n,
        6 => v,
        7 => !v,
        8 => c && !z,
        9 => !c || z,
        10 => n == v,
        11 => n != v,
        12 => !z && n == v,
        13 => z || n != v,
        14 => true,
        _ => false,
    }
}

/* ---------- 测试：先用汇编级用例锁语义，再上真实 guest ELF ---------- */

#[cfg(test)]
mod tests {
    use super::*;
    use crate::FlatMem;

    fn asm_run(insns: &[u32]) -> (Cpu, FlatMem, Option<ExitReason>) {
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 4096]);
        let mut c = Cpu::new();
        let r = run_until_syscall(&mut c, &mut m);
        (c, m, r)
    }

    pub fn run_until_syscall<M: MemBackend>(cpu: &mut Cpu, mem: &mut M) -> Option<ExitReason> {
        let mut steps = 0;
        loop {
            match step(cpu, mem) {
                Ok(None) => {}
                Ok(Some(r)) => return Some(r),
                Err(e) => { eprintln!("{e}"); return Some(ExitReason::Fault(cpu.pc)); }
            }
            steps += 1;
            if steps > 100_000 { return Some(ExitReason::Fault(cpu.pc)); }
        }
    }

    #[test]
    fn movz_movk_add() {
        // movz x0, #0x1234; movk x0, #0x1, lsl #16; add x1, x0, #1
        let mut c = Cpu::new();
        let insns = [
            0xD282_4680, // movz x0, #0x1234
            0xF2A0_0020, // movk x0, #0x1, lsl #16
            0x9100_0401, // add x1, x0, #1
            0xD400_0001, // svc #0
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[0], 0x1_1234);
        assert_eq!(c.x[1], 0x1_1235);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn bl_ret_and_branch() {
        // bl 落到 ret 块；ret 回 lr 指向的主线；主线 b 跳过死区到 svc
        let mut c = Cpu::new();
        let insns = [
            0x9400_0003, // bl func（+12）
            0x1400_0004, // b land（+16）
            0xD400_0001, // svc（死区，不应执行）
            0xD280_0EE2, // func: movz x2, #0x77
            0xD65F_03C0, // ret → lr 回主线
            0xD400_0001, // land: svc #0
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[2], 0x77);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn cbz_skips() {
        let mut c = Cpu::new();
        let insns = [
            0xD280_0F03, // movz x3, #0x78
            0xB400_0040, // cbz x0, land（+16，跨过中间 svc）
            0xD400_0001, // svc（跳过）
            0xD400_0001, // land: svc #0
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[3], 0x78);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn subs_cmp_bcond() {
        let mut c = Cpu::new();
        let insns = [
            0x5280_00A4, // movz w4, #5
            0x7100_149F, // subs wzr, w4, #5  (Z=1)
            0x5400_0040, // b.eq land（+16）
            0xD400_0001, // svc（跳过）
            0xD400_0001, // land: svc #0
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert!(matches!(r, Some(ExitReason::Syscall)));
        assert_eq!(c.nzcv >> 2 & 1, 1, "Z flag");
        // b.ne 必须在 Z=1 时不跳、Z=0 时跳（NZCV 位序曾写读不一致）
        let mut c2 = Cpu::new();
        c2.x[4] = 7;
        let insns2 = [
            0x5280_00A4, // movz w4, #5
            0x7100_149F, // subs wzr, w4, #5 → Z=1
            0x5400_0041, // b.ne skip（不跳）
            0x5280_00A5, // movz w5, #5
            0xD400_0001, // svc
        ];
        let mut m2 = FlatMem(insns2.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m2.0.extend(vec![0u8; 256]);
        let r2 = run_until_syscall(&mut c2, &mut m2);
        assert_eq!(c2.x[5], 5, "b.ne must not branch on Z=1");
        assert!(matches!(r2, Some(ExitReason::Syscall)));
        let mut c3 = Cpu::new();
        c3.x[4] = 7;
        let insns3 = [
            0x5280_00E4, // movz w4, #7
            0x7100_149F, // subs wzr, w4, #5 → NE
            0x5400_0041, // b.ne skip
            0x5280_00E5, // movz w5, #5（必须被跳过）
            0xD400_0001, // svc
        ];
        let mut m3 = FlatMem(insns3.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m3.0.extend(vec![0u8; 256]);
        let r3 = run_until_syscall(&mut c3, &mut m3);
        assert_eq!(c3.x[5], 0, "b.ne must branch on NE");
        assert!(matches!(r3, Some(ExitReason::Syscall)));
    }

    #[test]
    fn logical_imm_bitmask() {
        // 真汇编：tst x0, #0x8080808080808080 (0xf201c01f)
        // 曾因 esize<64 旋转分支掩码错误解成 0x0 → strlen SIMD 死循环
        let mut c = Cpu::new();
        c.x[0] = 0x2626_2626_2626_feff; // 高字节含 NUL 特征
        let insns = [0xf201_c01f, 0x5400_0041 /* b.ne land(+8) */, 0x5280_00E5 /* movz w5,#7 */, 0xD400_0001 /* land: svc */];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[5], 0, "tst with 0x80..80 must set NE on nonzero");
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn ccmp_ccmn() {
        // 真汇编：ccmn x0, #1, #5, eq（0xba511805? 用 as 真值 ba410805）／ccmp x0, #1, #5, ne
        let mut c = Cpu::new();
        let insns = [
            0xba41_0805, // ccmn x0, #1, nzcv=5, eq  （x0=0 → Z=1，条件成立 → 加法置 NZCV）
            0x5400_0046, // b.ls land(+8)
            0x5280_00E5, // movz w5, #7（必须被跳过）
            0xD400_0001, // land: svc
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[5], 0, "ccmn must execute when cond met");
        assert!(matches!(r, Some(ExitReason::Syscall)));
        // 条件不成立 → nzcv 直接载入指令给定值
        let mut c2 = Cpu::new();
        c2.x[0] = 5;
        let insns2 = [0xba41_0805, 0xD400_0001];
        let mut m2 = FlatMem(insns2.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m2.0.extend(vec![0u8; 256]);
        let _ = run_until_syscall(&mut c2, &mut m2);
        assert_eq!(c2.nzcv, 5, "ccmn with cond unmet must load nzcv field");
    }

    #[test]
    fn ldr_str_roundtrip() {
        // str x5,[x6]; ldr x7,[x6]
        let mut c = Cpu::new();
        c.x[5] = 0xAABB_CCDD_1122_3344;
        c.x[6] = 0x1000;
        let insns = [
            0xF900_00C5, // str x5, [x6]
            0xF940_00C7, // ldr x7, [x6]
            0xD400_0001, // svc
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 8192]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[7], 0xAABB_CCDD_1122_3344);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn and_sp_align() {
        // 真汇编：and sp, x0, #0xfffffffffffffff0 (0x927cec1f)
        let mut c = Cpu::new();
        c.x[0] = 0x3fef000;
        let insns = [0x927c_ec1f, 0xD400_0001];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        // 手动单步观察
        let r0 = step(&mut c, &mut m).unwrap();
        let tag = matches!(r0, None); eprintln!("step1: {} sp={:#x} x0={:#x}", tag, c.sp, c.x[0]);
        let r = run_until_syscall(&mut c, &mut m);
        eprintln!("final: syscall={} sp={:#x}", matches!(r, Some(ExitReason::Syscall)), c.sp);
        assert_eq!(c.sp, 0x3fef000, "sp after and");
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn adr_adrp() {
        let mut c = Cpu::new();
        // 真汇编：adr x2, land（land = 下一条 = pc0+4，编码 0x10000022）
        let insns = [
            0x1000_0022, // adr x2, land
            0xD400_0001, // land: svc
        ];
        let mut m = FlatMem(insns.iter().flat_map(|&i: &u32| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[2], 4);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }
}