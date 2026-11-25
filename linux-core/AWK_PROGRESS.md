# awk 数值归零攻坚进度（2026-11-25，本轮未完成）

## 已修（engine/src/interp.rs，已编译，探针 fp4 两侧全绿）
1. **fmov x, vn.d[1]（0x9eae0001）**：op12=0x0E0 rtype=2 写回时源寄存器误用 rd，改为 q[rn]。
2. **新增 ADC/SBC**（0x9a080146，掩码 0x7FC00000==0x1A000000 且 bits15:10≤1）：
   SBC = Rn - Rm - (1-C)，C 出 = 无借位（a>=sub），初版借位 off-by-one 已修正。
3. **新增标量 SIMD 转换族** FCVTZS/SCVTF Dd,Dn（掩码 0xDFAFFC00==0x5EA1B800/0x5E21D800，type=bit22，U=bit29）。

## 现症（仍未修完）
- `awk 'BEGIN{print 1}'` → 0：strtod("1") 返回 0；d0/d1/d2/d8 全程未出现 0x3ff0000000000000。
- 分数路径 memerr 读 0x4000000+8 @ pc=0x4cd310（musl floatscan 区）。
- `printf "%f"` 死循环（未修）。

## 下一步线索
- 【2026-11-25 深夜突破】strtod 从未被调用！awk `print 1` 的 0 来自 **musl vfprintf fmt_fp**：值以 fp128 正确传入（v0=0x3fff000000000000_0=1.0L ✓），但 0x4020c0-0x402460（fmt_fp 的 fp128→big 归一化）末尾 `fmov d0,x8` x8=0 → 输出 0.0。
- trace 修正：d0/d1/d2/d8 现在是 q[i] 高 64 位，新增 d0l/d1l/d4l = q0/q1/q4 低 64 位；/tmp/awk3.txt（39500 行）。
- 关键路径：0x4d74ac `mov v1.16b,v0.16b`（0x4ea01c01）→ 0x4d74b0 str b0 → bl 0x4020c0。v0、v1 均为 fp128 1.0，传入正确。
- fmt_fp 归一化已验证正确的寄存器：x0=0(低位)、x1=0x3fff→-0x3fff→0、x2=x1+1=1、x4=v1 低=0、x3=1<<51(orr #0x8000000000000)。歧义点：0x402168 `cmp x9,#0xa; b.gt 0x402404` 未跳转——**x9 未在 trace 输出中，需加 x9/x3 到 trace 再跑**。
- 32 位 andimm 的 wmask 打印为 64 位复制（0xff000000ff），疑似无害（后面有截断），待确认。
- andimm debug 打印在 CH_TRACE 下太吵，确认后建议删。
- 下一动作：trace 加 x3,x4,x9；重跑 awk；对 0x4020c0-0x402460 逐条对拍（可用 qemu 's' 单步该区间做真值）。
- CH_TRACE 已加 d0/d1/d2/d8 输出；trace 在 /tmp/awk2.txt（~39500 行）。
- musl floatscan 0x4cd0xx 区：数字缓冲 x1=0x3030303030303030（全 '0'，输入应为 '1'，存疑）。
- 0x492c4c `ldr w1,[x21,#0x94]`（awk 数字节点低字）载入值=0 → 值在存储前就坏了 → 指向 strtod/floatscan 本身。
- fp4（-nostdlib）探针两侧全绿；带 libc 的探针因 bionic TLS 对齐进不了 qemu（未解决）。
- 探针源码 /tmp/fp5.c（未跑通）、fp4.c 在 jail2。
