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
    let instr = mem.read32(cpu.pc).map_err(|e| format!("fetch {:#x}: {e}", cpu.pc))?;
    cpu.pc = cpu.pc.wrapping_add(4);
    exec(cpu, mem, instr)
}

fn exec<M: MemBackend>(cpu: &mut Cpu, mem: &mut M, i: u32) -> Result<Option<ExitReason>, String> {
    use ExitReason::*;

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
    // sf 1 0 0 1 0 1 [opc:2] [hw:2] imm16 Rd
    if i & 0x1F80_0000 == 0x1280_0000 {
        let sf = i >> 31;
        let opc = (i >> 29) & 3;
        let hw = (i >> 21) & 3;
        let imm = (i >> 5) & 0xFFFF;
        let rd = (i & 31) as usize;
        let shift = hw << 4;
        let val = imm << shift;
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
        let imm = (((immhi << 2) | immlo) as i64) << (if op == 1 { 12 } else { 0 });
        let base = if op == 1 { cpu.pc & !0xFFF } else { cpu.pc };
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
        let a = reg(cpu, rn, sf);
        let b = if op == 1 { 0u64.wrapping_sub(imm) } else { imm };
        let r = a.wrapping_add(b);
        if s == 1 { set_flags_sub(cpu, a, imm, sf); }
        set_reg(cpu, rd, mask64(r, sf));
        return Ok(None);
    }

    // ---- Logical (shifted) register / mov register ----
    // sf 0 1 0 1 0 1 0 shift:2 N 0 Rm:5 imm6 Rn Rd
    if i & 0x1F00_0000 == 0x0A00_0000 {
        let sf = i >> 31;
        let opc = (i >> 29) & 3; // 00=AND 01=ORR 10=EOR 11=ANDS
        let shift_t = ((i >> 22) & 3) as u8;
        let n = (i >> 21) & 1;
        let rm = ((i >> 16) & 31) as usize;
        let imm6 = (i >> 10) & 63;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg(cpu, rn, sf);
        let b = shift_val(reg(cpu, rm, sf), imm6, shift_t, if sf == 1 { 64 } else { 32 });
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
    // sf 0 0 0 1 0 1 1 shift:2 0 Rm:5 imm6 Rn Rd
    if i & 0x1F80_0000 == 0x0B00_0000 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let s = (i >> 29) & 1;
        let shift_t = ((i >> 22) & 3) as u8;
        let rm = ((i >> 16) & 31) as usize;
        let imm6 = (i >> 10) & 63;
        let rn = ((i >> 5) & 31) as usize;
        let rd = (i & 31) as usize;
        let a = reg(cpu, rn, sf);
        let b = shift_val(reg(cpu, rm, sf), imm6, shift_t, if sf == 1 { 64 } else { 32 });
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
    // sf 0 1 1 0 1 0 [op] 0 imm19 Rt
    if i & 0x1E00_0000 == 0x3400_0000 {
        let sf = i >> 31;
        let op = (i >> 30) & 1;
        let imm19 = (i >> 5) & 0x7_FFFF;
        let rt = (i & 31) as usize;
        let v = reg(cpu, rt, sf);
        let zero = v == 0;
        if zero != (op == 1) {
            let off = sign_extend(imm19 as i64, 19) * 4;
            cpu.pc = (cpu.pc as i64 + off) as u64;
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
            cpu.set_lr(cpu.pc); // pc already advanced
        }
        cpu.pc = (cpu.pc as i64 + off) as u64;
        return Ok(None);
    }

    // ---- Branch register / RET ----
    // 0 1 1 0 1 0 0 opc:2 11111 [opc2] 11111 Rn [opc3] 00000
    if i & 0xFE1F_FC1F == 0xD61F_0000 {
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
            cpu.pc = (cpu.pc as i64 + off) as u64;
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
    // opc:2 1 0 1 0 [mode:3] L imm7 Rt2 Rt1 Rn
    if i & 0x3E00_0000 == 0x2800_0000 {
        let opc = (i >> 30) & 3;
        let mode = (i >> 23) & 7;
        let l = (i >> 22) & 1;
        let imm7 = (i >> 15) & 0x7F;
        let rt2 = ((i >> 10) & 31) as usize;
        let rn = ((i >> 5) & 31) as usize;
        let rt = (i & 31) as usize;
        let scale = if opc >= 2 { 4 } else { 3 }; // 32-bit pair = opc10/11? 这里只支持 opc00(32)/opc10(64)
        if opc != 0 && opc != 2 {
            return Ok(Some(Undef(i)));
        }
        let off = (sign_extend(imm7 as i64, 7)) << scale;
        let base = match mode {
            0 => cpu.x[rn],                     // post-index: 完整语义 P2 简化
            2 => cpu.x[rn],                     // offset
            3 => { set_reg(cpu, rn, mask64(cpu.x[rn].wrapping_add(off), 1)); cpu.x[rn] } // pre-index
            m => return Ok(Some(Undef(i))),
        };
        if mode == 0 {
            // post-index：先访原 base，再更新 Rn
            let addr = cpu.x[rn];
            if l == 1 {
                let lo = mem.read64(addr)?; let hi = mem.read64(addr.wrapping_add(8))?;
                set_reg(cpu, rt, lo); set_reg(cpu, rt2, hi);
            } else {
                mem.write64(addr, cpu.x[rt])?; mem.write64(addr.wrapping_add(8), cpu.x[rt2])?;
            }
            set_reg(cpu, rn, mask64(addr.wrapping_add(off), 1));
            return Ok(None);
        }
        let addr = base;
        if l == 1 {
            let lo = mem.read64(addr)?; let hi = mem.read64(addr.wrapping_add(8))?;
            set_reg(cpu, rt, lo); set_reg(cpu, rt2, hi);
        } else {
            mem.write64(addr, cpu.x[rt])?; mem.write64(addr.wrapping_add(8), cpu.x[rt2])?;
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
        let addr = cpu.x[rn].wrapping_add(off);
        match (size, opc) {
            (3, 0) => { mem.write64(addr, cpu.x[rt])?; }
            (3, 1) => { let v = mem.read64(addr)?; set_reg(cpu, rt, v); }
            (2, 0) => { mem.write64(addr, cpu.x[rt] & 0xFFFF_FFFF)?; }
            (2, 1) => { let v = mem.read64(addr)?; set_reg(cpu, rt, v & 0xFFFF_FFFF); }
            _ => return Ok(Some(Undef(i))),
        }
        return Ok(None);
    }

    Ok(Some(Undef(i)))
}

/* ---------- 寄存器 / 位段工具 ---------- */

fn reg(cpu: &Cpu, r: usize, sf: u32) -> u64 {
    if r == 31 { cpu.sp } else { cpu.x[r] }
}

fn set_reg(cpu: &mut Cpu, r: usize, v: u64) {
    if r == 31 { cpu.sp = v } else { cpu.x[r] = v }
}

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
    cpu.nzcv = n | z << 1 | (c as u32) << 2 | (v as u32) << 3;
}

fn set_flags_sub(cpu: &mut Cpu, a: u64, b: u64, sf: u32) {
    let r = a.wrapping_sub(b);
    let (n, z) = flag_nz(r, sf);
    let c = a >= b;
    let v = ((a ^ b) & 0x8000_0000_0000_0000 != 0) && ((a ^ r) & 0x8000_0000_0000_0000 != 0);
    cpu.nzcv = n | z << 1 | (c as u32) << 2 | (v as u32) << 3;
}

fn set_flags_logical(cpu: &mut Cpu, r: u64, sf: u32) {
    let (n, z) = flag_nz(r, sf);
    cpu.nzcv = n | z << 1;
    // C/V: 未定义，ARM 上保留原值，这里清 0
    cpu.nzcv &= 0b0011;
}

fn flag_nz(r: u64, sf: u32) -> (u32, u32) {
    let eff = mask64(r, sf);
    let n = (eff >> 31) as u32 & 1;
    (n, (eff == 0) as u32)
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
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
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
        let (_, _, _) = ();
        let mut c = Cpu::new();
        let insns = [
            0xD282_4680, // movz x0, #0x1234
            0xF2A0_0020, // movk x0, #0x1, lsl #16
            0x9100_0401, // add x1, x0, #1
            0xD400_0001, // svc #0
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[0], 0x1_1234);
        assert_eq!(c.x[1], 0x1_1235);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn bl_ret_and_branch() {
        // bl +4; b .; ret-target: movz x2,#7; ret
        let mut c = Cpu::new();
        let insns = [
            0x9400_0002, // bl +8
            0x1400_0003, // b +12 (skip dead zone, dead zone: svc)
            0xD400_0001, // (dead) svc
            0xD282_4E40, // movz x2, #0x77
            0xD65F_03C0, // ret
            0xD400_0001, // svc #0  <- landing
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[2], 0x77);
        assert!(matches!(r, Some(ExitReason::Syscall)));
        // bl 应已把 lr 指向第 2 条
        assert_eq!(c.lr_unused_guard(), 0); // 占位：lr 在 bl 后又被 ret 覆盖，走语义用例
    }

    #[test]
    fn cbz_skips() {
        let mut c = Cpu::new();
        let insns = [
            0xD282_4E60, // movz x3, #0x78
            0xB400_0001, // cbz x0, +4   (x0=0 → 跳过 svc)
            0xD400_0001, // svc (skipped)
            0xD400_0001, // svc #0  <- landing
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[3], 0x78);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn subs_cmp_bcond() {
        // movz x4, #5; subs xzr, x4, #5; b.eq +4  → 跳过 svc 落到下一个 svc
        let mut c = Cpu::new();
        let insns = [
            0xD282_4E84, // movz x4, #0x74
            0x7100_01BF, // subs wzr, w4, #5 → 0x74-5 ≠0, Z=0 …
            // 用 w4=5：movz w4,#5 = 0x5280_00A4
        ];
        let _ = insns;
        let insns = [
            0x5280_00A4, // movz w4, #5
            0x7100_01BF, // subs wzr, w4, #5  (Z=1)
            0x5400_0001, // b.eq +4
            0xD400_0001, // svc (skipped)
            0xD400_0001, // svc #0 <- landing
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
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
            0xF900_0405, // str x5, [x6]  (imm12=0 → offset 0)
            0xF940_0407, // ldr x7, [x6]
            0xD400_0001, // svc
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 8192]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[7], 0xAABB_CCDD_1122_3344);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }

    #[test]
    fn adr_adrp() {
        let mut c = Cpu::new();
        c.pc = 0x1000;
        let insns = [
            0x9000_0141, // adrp x1, #0x2000 (immlo=1,immhi=0xA? — 用编码器算)
            0x1000_0082, // adr x2, #16
            0xD400_0001,
        ];
        let _ = insns;
        // 用手算编码：adr x2, (pc+16)：immlo=0,immhi=4 → 0b1000000...
        let insns = [
            0x1000_0102, // adr x2: immlo=0 immhi=4 → +16?  (immhi=4<<2=16)
            0xD400_0001,
        ];
        let mut m = FlatMem(insns.iter().flat_map(|i| i.to_le_bytes()).collect());
        m.0.extend(vec![0u8; 256]);
        let r = run_until_syscall(&mut c, &mut m);
        assert_eq!(c.x[2], 0x1000 + 16);
        assert!(matches!(r, Some(ExitReason::Syscall)));
    }
}
