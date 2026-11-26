# 第三方组件与许可证（THIRD-PARTY NOTICES）

本项目**不使用** Appine（github.com/mthli/appine，AGPL-3.0）的原创源码。
Appine 只作为**架构参照**：我们读它的设计决策文档与模块边界，然后自己实现对应能力。
判定与处理规则如下。

## 一、处理规则

| 类别 | 处理方式 |
|---|---|
| **第三方上游组件**（Appine 也是从别处拿来用的） | 直接使用/搬运，**保留上游原始许可证与版权声明**，见下表 |
| **Appine 原创部分**（engine DBT、软页表内存模型、PTY/终端、JNI 桥、构建胶水） | **不复制**。按其公开的设计意图自行实现（见「自研替代清单」） |
| Appine 的品牌字样 | 不进入本仓库；本仓库自有命名空间 `com.chengyan.app` / `ch_*` |

> 说明：Appine 整体以 AGPL-3.0 发布。以上「不复制原创代码」的做法是为了不把本项目
> 拉进 AGPL 的传染范围；如果后续确有需要直接复制某段其原创实现，必须在此登记并
> 将相应文件以 AGPL-3.0 标注，且整个分发须满足 AGPL 条款。

## 二、实际随包分发的第三方组件

| 组件 | 版本/来源 | 许可证 | 许可证原文 | 用途 |
|---|---|---|---|---|
| **busybox (static PIE)** | 1.37.0-r31，Alpine `main/aarch64` 的 `busybox-static` 包（未修改二进制，仅换入 rootfs） | **GPL-2.0** | [`GPL-2.0.txt`](third_party/GPL-2.0.txt) | guest 侧全部 shell applet（`sh/ash/ls/cat/awk/sed/sort/tail…`） |
| **musl libc** | 1.2.x，随 Rust `aarch64-unknown-linux-musl` 静态链接进 `libchlinux.so` 与桌面 runner | **MIT**（musl 使用 MIT 许可） | [`musl-COPYRIGHT`](third_party/musl-COPYRIGHT) | guest 与宿主侧静态 libc |
| **Alpine Linux rootfs 骨架** | Alpine 3.20（`/etc/passwd`、`/etc/os-release`、apk keys 等配置与目录结构） | 各文件按其上游（MIT/其他），配置文件本身可自由分发 | — | guest 根文件系统的非 busybox 部分 |

### GPL-2.0 合规（busybox）

分发 `busybox` 二进制必须能获得**完全对应的源码**：

- 上游源码：<https://busybox.net/downloads/busybox-1.37.0.tar.bz2>
- Alpine 构建配方（含全部编译开关）：<https://gitlab.alpinelinux.org/alpine/aports/-/tree/master/main/busybox>
- 我们**未修改** busybox 的任何一个字节，直接取自 Alpine 官方 `busybox-static-1.37.0-r31.apk`。
- 需要源码副本时可通过上述地址获取；本项目的修改仅限于把它放进 `assets/rootfs.tar.gz`。

## 三、自研替代清单（Appine 原创能力 → 我们的实现）

| Appine 的原创能力 | 我们的实现（自研） | 状态 |
|---|---|---|
| 内存模型 / 软 MMU（`core/kernel/src/mm.rs`，12969 行，软页表 + TLB） | `linux-core/kernel/src/lib.rs`：`GuestMem` + `Backing`（`Flat` / `Ident`），**恒等映射 + 任意地址 arena**；PIE 后 arena 由内核挑地址，不再需要固定窗口 | 已有：单进程任意基址；**待做**：软页表（多进程真地址空间） |
| 动态二进制翻译（`core/engine/src/c/dbt*.c` 约 6300 行） | `linux-core/host/src/native.rs`：Tier1 = 扫 `.text` 把 `svc #0` 改 `brk #0` + SIGTRAP 回调内核；Tier0 = `linux-core/engine/src/interp.rs` 解释器 | 已有（Tier1 在 PIE 动态基址下实测 `hits>0, fallback=0`）；**待做**：真 DBT 以去掉 svc 陷门开销 |
| ELF 加载器（`loader.rs` 1988 行，ET_EXEC/ET_DYN/PT_INTERP/vDSO/auxv） | `linux-core/kernel/src/elf.rs`：ET_EXEC + **ET_DYN(static PIE)**、`AT_PHDR` 正确重定位 | 已有 ET_EXEC/ET_DYN；**待做**：`PT_INTERP` + R_AARCH64_RELATIVE/GLOB_DAT/JUMP_SLOT 动态链接 |
| 真 PTY（`tty.rs` 2300 行 + `terminal.rs` + `VtView.kt` 2257 行） | 目前无 fd 级 PTY；管道/重定向由宿主 `linux-core/host/src/shell.rs` 编排 | **待做**（依赖软 MMU 才能有真进程） |
| JNI 桥（42 导出） | `linux-core/jnihost/src/lib.rs`（8 导出）+ 手写 `jni_min.rs`；`guarded!` 式 panic 隔离、stderr→logcat+环形缓冲、句柄安全失效 | 已有 |
| 终端 VT 解析（libghostty-vt，Zig） | 未引入 | **待做/可选**（libghostty-vt 为 MIT，可直接搬入并保留其许可证） |

## 四、构建期工具（不分发）

Rust 工具链、`rust-lld`、Android NDK、Gradle、AGP 均为构建期依赖，其许可证不随 APK 分发，
但 `libc++_shared.so`（NDK 产物，Apache-2.0 WITH LLVM-exception）会随包分发。
