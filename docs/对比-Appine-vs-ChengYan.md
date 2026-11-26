# Appine vs ChengYan：完整代码级对比（2026-11-26）

对象：`github.com/mthli/appine`（全量 clone，1091 文件 / 35MB） vs 本仓 `linux-core` + `app`。
本文所有数字都是**当场统计出来的**，不是印象。统计口径写在每节里，可复算。

---

## 0. 一句话结论

**两者不是同一个物种。** Appine 是一个跑在 App 进程里的**完整 Linux 内核实现**
（软 MMU + 真 DBT + 真 PTY + 265 个 syscall + 网络栈 + cgroup + seccomp），
ChengYan 是**「宿主侧 shell + 单进程 aarch64 解释器」**：它把 busybox 的某个 applet
装进一块恒等映射内存跑完、收走输出，再跑下一个。

所以「照 Appine 搬功能」这个说法本身是错的：能搬的只有**工程纪律**（日志、panic 隔离、
句柄语义、错误不吞），不能搬的是**架构**（软 MMU / 进程模型 / PTY），那是重写内核。

---

## 1. 规模（可复算）

| 项 | Appine | ChengYan | 统计命令 |
|---|---|---|---|
| Rust | **168,473 行** / 113 文件 | **7,970 行** / 17 文件 | `find -name '*.rs' \| xargs wc -l` |
| C / H | **47,874 行** / 99 文件 | 0 | `find -name '*.c' -o -name '*.h'` |
| Kotlin | **20,973 行** / 60 文件 | **9,223 行** / 37 文件 | `find -name '*.kt' \| xargs wc -l` |
| 设计决策文档 | **377 份 .md**（`.agents/decisions/*` 152 份） | 0（只有代码注释） | `find .agents -name '*.md'` |
| 合计代码量 | ≈ **237,000 行** | ≈ **17,200 行** | |

倍数关系：**约 14 倍**。这不是「补几个补丁」的量级。

---

## 2. 引擎（怎么执行 guest 指令）

| | Appine | ChengYan |
|---|---|---|
| 文件 | `core/engine/src/c/` 13,875 行 C：`dbt.c` 1692 / `dbt_loop.c` 1725 / `dbt_translate.c` 1370 / `dbt_cache.c` 1281 / `interp.c` 1067 / `simd.c` 1072 / `mmu.c` 470 / `native.c` 298 / `guard.c` 370 | `linux-core/engine/src/interp.rs` 2,014 行 + `host/src/native.rs` 582 行 |
| Tier0 | 解释器（完整 aarch64，含 SIMD/FP/原子） | 解释器（子集，够跑 busybox） |
| Tier1 | **真 DBT**：把 guest 基本块翻译成宿主指令、缓存成 executable block（`dbt_cache.c`），另有 `native.c` 把 SIMD/CRC32/LSE 原子指令**直接交给宿主 CPU 执行同一条编码** | **不是 DBT**：扫 `.text` 把 `svc #0`(0xD4000001) 改写成 `brk #0`(0xD4200000)；SIGTRAP handler 里组装寄存器调内核，`pc+=4` 返回 |
| 代码页 W^X | `appine_map_code()`：`mmap` 一份 `MAP_SHARED` 匿名再 `mremap` 出**第二个别名**，一个 RW 写、一个 RX 执行 | 单个 arena：`mmap(RW)` → 装段 → `mprotect` 成 R-X；代码尾页与可写段共页时退 RWX |
| 已知风险 | — | ① 恒等映射要求宿主 `0x400000..64MB` 空闲（`arch_randomize_brk` 会把堆压进来，实测 4~8% 间歇失败，靠 `.init_array`/`JNI_OnLoad` 提前 `PROT_NONE` 预留对抗）；② 共页 RWX 在 Android 可能被 execmem 检查拒绝 → 整条 Tier1 回落解释器（**静默变慢，不会崩**） |

**关键差别**：Appine 的 Tier1 不占宿主任何固定的低地址，因为它有软 MMU；
我们的 Tier1 必须让 guest 虚拟地址 == 宿主虚拟地址，所以天生与 ART/zygote 的地址布局抢地盘。
「VM 全绿 ≠ 真机能起」的根源就在这一行。

---

## 3. 内存（guest 地址怎么变成宿主地址）

| | Appine | ChengYan |
|---|---|---|
| 文件 | `core/kernel/src/mm.rs` **12,969 行** + `engine/mmu.c` 470 行 | `kernel/src/lib.rs` 的 `GuestMem`（约 200 行）+ `host/src/native.rs` |
| 模型 | **软件页表 + TLB**（1024 项 stlb、2MiB superpage），guest 虚拟地址由自己的页表翻译 → guest 内存**不在 App 地址空间里** | **恒等映射**：整块 `0x400000..64MB` 直接 mmap 给 guest 用，guest 地址就是宿主地址；失败回落 flat `Vec<u8>` |
| 后果 | 永不与宿主布局冲突；多进程各自地址空间天然成立 | 单进程独占那块窗口 ⇒ **不可能同时有两个 live guest 进程**（fork 在架构上不成立） |
| COW / 按需分页 | 有（`mm::Fill`、FUSE 文件页缺页回填） | 无（一次性 map + 清零） |

---

## 4. ELF 装载

| | Appine | ChengYan |
|---|---|---|
| 文件 | `core/kernel/src/loader.rs` **1,988 行** | `core/kernel/src/elf.rs` **150 行** |
| 支持 | `ET_EXEC` + `ET_DYN` + `PT_INTERP`（动态链接器，如 `ld-musl`）+ `PT_NOTE`/`PT_GNU_PROPERTY`（找 Go buildid）+ **vDSO 映射** + 按 arm64 Linux 原样的 auxv 条目与顺序 | **只 ET_EXEC 静态** |
| 语义对齐 | 按 6.1 `load_elf_binary` 的前/后分界：提交点前失败 = execve 失败；映射期失败 = `Image::doomed`，提交后按 SIGSEGV 杀掉 | 只做「读 PT_LOAD → memcpy → 建栈」 |
| 动态链接程序 | 可跑 | **跑不了**（无 PT_INTERP） |

这是一条**用户可见的能力边界**：rootfs 里只有 `busybox.static`，任何动态链接的程序
（`apk` 装的包、自己编的 hello、glibc 二进制）在我们这里直接失败。

---

## 5. syscall

| | Appine | ChengYan |
|---|---|---|
| 顶层 match 覆盖编号数 | **265**（编号到 451，含 pidfd/statmount 等新接口） | **62** |
| 分布 | `syscall/fs.rs` 12,440 行、`net.rs` 5,839、`poll.rs` 3,805、`fuse.rs` 3,951、`proc.rs` 2,270、`mount.rs` 1,973、`misc.rs` 1,646、`inotify.rs` 1,442 …共 30 个文件 | 单文件 `syscall.rs` 约 1,100 行 |
| 进程相关 | `clone`/`clone3`(220)、`execve`(221)、`execveat`(281)、`wait4`(260)、`futex`(98)、`set_robust_list`(99)、`ptrace`(117) 全有 | **全无**（220/221/260/98/99/117 都不在表里） |
| 未实现时的表现 | 按 Linux 语义返回 ENOSYS 并进 kernel log | 返回 ENOSYS（本轮之前**完全静默**，现在会计数上报） |

`220/221/260` 缺失的直接后果：**guest 里不能自己 fork/exec**。现在 `ls`、`cat`、`awk`
能跑，是因为**宿主侧 shell（`host/src/shell.rs`，911 行）替 guest 做了这些事**：
它自己分词/展开/glob/重定向，把每一段翻译成 `busybox <applet> <args>` 交给引擎。
所以 `sh script.sh`、`busybox sh -c '...'` 里一旦 applet 自己调 fork/exec，就断在这里。

---

## 6. 进程 / 线程 / 信号

| | Appine | ChengYan |
|---|---|---|
| 文件 | `task.rs` 1,449 + `process.rs` + `signal.rs` 2,336 + `futex.rs` + `pidns.rs` + `ptrace.rs` + `seccomp.rs` 1,830 | 无 |
| 模型 | 线程组（`ThreadGroup`）+ 每线程 `Task` + 内核态线程调度 + BKL（`kernel.rs` 2,085 行） | 单 `Process` 结构体，一次跑一条命令，跑完取输出 |
| 信号 | 完整：handler/sigaltstack/siginfo/实时信号/作业控制（Ctrl-C 进前台进程组） | `rt_sigaction`/`rt_sigprocmask`/`sigaltstack` 一律**返回 0 但不落地**：注册的 handler 永远不会被调用 |
| 结果 | 程序能被打断、能自己装 handler | 长命令无法中断（终端页那个「重建」键是硬重建整个会话） |

---

## 7. 终端（PTY）

| | Appine | ChengYan |
|---|---|---|
| 文件 | `tty.rs` 2,300 + `terminal.rs` 651 + `pseudo.rs` 6,553 里的一部分 + `hosts/android/vt.rs` 2,124（终端模拟器）+ Kotlin `VtView.kt` 2,257、`TerminalService.kt` 1,557 | 无 |
| 模型 | **进程内真 PTY**：`open_console` → `/dev/pts/N`，`spawn` 时把 slave 当 0/1/2 并设为控制终端；行规程（ICANON/ECHO）、`^C` → SIGINT 给前台进程组、`resize` 全在 | **没有 fd 级 PTY**：宿主把命令输出收成 `Vec<u8>` 交给 UI 显示，输入整行提交 |
| 可见差别 | `less`/`vi`/`top` 能用；`isatty()` 为真；作业控制成立 | 全屏 TUI 程序无法工作；`isatty()` 为假；无 `^C` |

---

## 8. Android 宿主层（JNI + Kotlin）

| | Appine | ChengYan |
|---|---|---|
| JNI 导出 | **42 个** `Java_io_github_mthli_appine_Native_*`（init/importRootfs/browse*/openConsole/spawn/read/write/resize/waitFor/takePower/shutdown …） | **8 个**（init/start/exec/poll/stop/info/lastError/logTail） |
| JNI 文件 | `hosts/android/src/lib.rs` 1,420 行（jni crate） | `jnihost/src/lib.rs` 446 行 + 手写 `jni_min.rs` 972 行（不依赖 jni crate） |
| panic 隔离 | `guarded!` 宏：每个 JNI 入口 catch_unwind，panic → 抛 Java 异常或返回值 | 同上（本轮之前已有）；`JNI_OnLoad` 也包了 |
| native 日志 | `redirect_stderr`：pipe + 读取线程 → logcat | **v1.9.9 起有**：pipe + 读取线程 → logcat + **96KB 内存环形缓冲**，Kotlin `logTail(n)` 直接取，不用 adb |
| so 加载 | `Native.kt` 的 `init { System.loadLibrary("appine"); init(DEBUG) }`（有 clinit 中毒风险） | `NativeLib`：三通路（loadLibrary → nativeLibraryDir → APK 抽出）+ 逐条记录报错 + 可重试；类里**不写 `init{}`** |
| 打包 | `useLegacyPackaging = false`（APK 内直映射，NDK 29 保证 16KB 对齐）、minSdk 30 | `extractNativeLibs=true` + `useLegacyPackaging = true`（安装期解包，抗第三方安装器重打包）、minSdk 26 |
| 应用级初始化 | `MainApplication.onCreate`；`:venus`(GPU) 独立进程 | `ChengYanApp.onCreate` 初始化 `LinuxBridge` |

---

## 9. 其它子系统（Appine 有、我们没有）

`net.rs` 4,627 / `netns.rs` 3,671 / `netdev.rs` 3,940 / `nft.rs` 5,230（防火墙）/
`cgroup.rs` 2,140 / `vfs/` 合计 20,000+（含 overlay、FUSE、inode store）/
`unix.rs` 1,740（unix socket）/ `ipc.rs`（shm、msg、sem）/ `bpf.rs` / `watchdog.rs` /
`inittab.rs`（开机流程）/ `gpu.rs` + `hosts/android/gpu.rs` 544（virgl 直通）/
`coredump.rs` / `loadavg.rs` / `vdso.rs` / `uaccess.rs`（SIGSEGV 修 uaccess 缺页）

我们的对应物只有：`openat/read/write/stat/getdents/mkdir/rename/...` 62 个 syscall 直接透传宿主文件系统
（`path.rs` 123 行做 jail + 绝对软链重定向 + 16 跳上限），socket 6 个直通宿主 socket。

---

## 10. 那么「搬什么」是有意义的

按投入产出排序，**能搬且值得搬**的只有工程纪律，我们已搬完 7 条：

1. stderr → logcat + 内存环形缓冲（v1.9.9）— 否则真机排障全靠静态反推
2. 每个 JNI 入口 catch_unwind（v1.9.9）
3. 句柄失效后调用必须安全返回、槽位复用（v1.9.9）
4. 句柄表条目在锁外 drop（v1.9.9）
5. 错误不吞成裸数字（`LAST_ERROR` 全文，v1.9.9）
6. 热路径不碰 `std::env`（v1.9.10，`getenv` + `OnceLock`）
7. 未实现 syscall 计数上报（v1.9.10，`unimpl=nr:次数`）

**不能搬**（需要重写内核，不是补丁）：
软 MMU（→ 才可能 fork/多进程、才不抢宿主地址）、真 DBT、真 PTY、完整信号/线程模型。
这四项任缺一项，都实现不了 Appine 那种「一个 tab 一个真 shell 进程、能 Ctrl-C、
能跑 vi」的终端。

---

## 11. 当前能力边界（诚实清单）

能：`echo/ls/cat/grep/sed/awk/sort/uniq/head/tail/wc/find/cp/mv/rm/mkdir/touch`、
管道、重定向（`> >> < 2> 2>&1 &>`）、`; && ||`、单双引号、`$VAR ${VAR} $?`、`~`、
glob（`* ? []`）、`cd/pwd/export/unset`、`cp/mv` 跨目录、越狱防护。

不能：`$(...)`、`for/while/if`、函数、真后台 `&`、`vi/less/top`（无 tty）、
`^C` 中断、`apk add` / 包安装（未验证：需要脚本执行、chown、硬链接等链路）、
guest 内部 fork/exec（`sh script.sh` 里 applet 自己 exec 会断）。

动态链接已于 v1.9.12 打通（见 §14）：`apk --version/info/list/policy/version` 全部可跑，
包括递归加载 4 个 `DT_NEEDED` 共享库（libcrypto 4.6MB）。

---

## 12. 本轮实测数据（aarch64 VM，qemu + Alpine 真内核）

47 项矩阵两遍：**45 × exit 0 + 2 × 故意负例 exit 1**，无 panic。
未实现 syscall 报表在 47 例里**一次都没出现**——即该矩阵范围内内核不缺 syscall
（唯一挖出来的 `umask(166)` 已补，用于 `mkdir -p`）。
新增 4 例无界生产者（`yes|head`、`yes|head -c 16|wc -c`、`yes|head -5|wc -l`、
`yes|tail -1|head -1`）全部输出正确且不再挂死——这是本轮唯一一处「真机可能表现为
终端卡死」的实际缺陷。

---

## 13. 架构分水岭已落地：ET_DYN（static PIE）+ 每进程任意地址 arena（v1.9.11）

第 3 节列出的第一条差异（**内存基座**）不再只是「承认差距」，本轮改掉了。

### 改之前（ET_EXEC 写死落点）

```
busybox e_type=2 (ET_EXEC)  LOAD.vaddr = 0x400000
  ⇒ arena 只能落在 0x400000，64MB 窗口必须 PROT_NONE 提前占住
  ⇒ ART/zygote 的 arch_randomize_brk 一旦把宿主堆压进 0x400000，
     MAP_FIXED_NOREPLACE 抢不到 ⇒ 恒等映射失败 ⇒ 无声回落解释器
  ⇒ 实测 4~8% 进程中招（"同一台机器同一条命令偶尔就不对"）
  ⇒ 同一时刻只可能有 1 个 guest 进程（fork 架构上不成立）
```

### 改之后（ET_DYN，内核挑地址）

```
busybox.static e_type=3 (ET_DYN, static PIE)  LOAD.vaddr = 0
  ⇒ GuestMem::identity_any() 用 mmap(NULL) 让内核挑空闲地址 ⇒ 永不冲突
  ⇒ 不再预留任何固定窗口（JNI_OnLoad 里的 reserve_guest_window 已移除）
  ⇒ 每个 guest 一条独立 arena ⇒ 多进程（fork/exec）的结构前提成立
```

busybox 取自 Alpine 1.37.0-r31 官方 `busybox-static`（未改一个字节，GPL-2.0，
见 `docs/THIRD_PARTY_NOTICES.md`）。1.37 比原来那版多 `ssl_client`，rootfs 软链已补齐。

### 代码改动

| 文件 | 改动 |
|---|---|
| `kernel/src/elf.rs` | 重写：`info()` 解析头/PT_LOAD 边界；`load()` 支持 ET_EXEC（delta=0）与 ET_DYN（delta=arena_base-lo）；返回 `phdr`（AT_PHDR 的内存地址） |
| `kernel/src/lib.rs` | `Backing::Ident` 增加 `plain` 标志；新增 `GuestMem::identity_any()`；`Process::new` 按 e_type 分流；新增 `arena()` / `LAST_ARENA` / `LAST_PIE` |
| `kernel/src/syscall.rs` | `mmap` 的 `MAP_FIXED` 校验与 bump 上界改用 `p.arena()`，不再写死 `MEM_SIZE`/`MMAP_TOP` |
| `host/src/lib.rs`、`runner/src/main.rs` | `AT_PHDR` 改用 `proc.image.phdr`（PIE 下必须带加载偏移，旧代码拿文件里的 `load_vaddr` 算必错） |
| `jnihost/src/lib.rs` | `JNI_OnLoad` 不再预留固定窗口；`info()` 输出改为 `arena=0x… pie=0/1` |
| `assets/rootfs.tar.gz`、`assets/busybox` | 换成 PIE busybox；`Rootfs.VERSION` → `alpine-1.37-pie-2` 强制重解包 |

### 验证（aarch64 VM，qemu-system-aarch64 + Alpine 真内核）

- 47 项矩阵 + 1 项 Tier0 冒烟：**46 × exit 0 + 2 × 故意负例 exit 1**，无 panic
- 每例都打印 `[ch-tier1 hits=N fallback=M]`：**fallback 全为 0**，`hits>0` 的命令说明
  真的跑在原生通道上 —— 即 **Tier1 在「内核挑的动态基址」下依然成立**
- `CH_TIER=0` 解释器冒烟：`echo tier0-ok | wc -c` → `9`，exit 0，`hits=0`（正确回落）
- 未实现 syscall 报表：47 例中一次都没出现

### 还没做到的（诚实清单，按依赖顺序）

1. **软页表**（Appine `mm.rs` 的对应物）：把 `Backing::Ident` 换成「guest 页表 + 宿主页管理」，
   才能有 >1 个并发 guest 进程 → 才能有 `fork/execve/wait4`（当前 220/221/260 全未实现）
2. **动态链接**（`PT_INTERP` + RELATIVE/GLOB_DAT/JUMP_SLOT）：才能跑 Alpine 常规动态 PIE（`apk` 等）
3. **真 PTY**：依赖 1；有它才有 `^C`(SIGINT)、`isatty()`、`vi/less/top`
4. **真 DBT**：当前 Tier1 是「svc 改 brk + 信号回调」，功能等价但每条 syscall 两次态切换

---

## 14. 动态链接落地：PT_INTERP + 共享库 + 真 exec（v1.9.12）

上一节的第 2 项本轮完成。这条线是「只能跑 busybox applet」与「整个 Alpine 用户空间能跑」
之间那道门：rootfs 里 500+ 文件此前绝大多数起不来。

### 14.1 内核侧

| 改动 | 为什么必须 |
|---|---|
| `elf.rs::interp_path()` + `load_with_interp()` | 主程序 `ET_DYN` 带 `PT_INTERP=/lib/ld-musl-aarch64.so.1`：两个 ELF 平铺进同一个 arena（主程序低地址、解释器紧随其后 64KB 对齐），起点切到解释器入口，auxv 传 `AT_BASE`/`AT_PHDR`/`AT_ENTRY`/`AT_PHENT`/`AT_PHNUM` |
| `AT_PHDR = bias + e_phoff`（`bias = min(vaddr - offset)` per PT_LOAD） | 旧代码直接 `e_phoff + base`：static PIE（lo=0）碰巧对，跑 ET_EXEC 的 `apk.static`（落点 0x400000、phoff=0x40）算出 `0x40` → musl 拿 `0x40` 当程序头 → `fault=0x40` SIGSEGV |
| `page_prots()` 逐页归并 PF_W/PF_X | 双镜像后是「主程序 3~4 段 + 解释器 3~4 段」，旧的手写「代码尾页与数据首段共页就 RWX」假设失效 |
| `mmap(fd>=0)` 文件映射：按 `[off, off+len)` 读进 arena；加 X 时真 `mprotect(RWX)` | musl 不用「读文件再写内存」：`mmap(NULL,PROT_NONE,ANON|NORESERVE)` 先占地址，再对每段 `mmap(base+off,len,prot,MAP_FIXED\|MAP_PRIVATE,fd,file_off)`。没有文件映射，动态库一个都进不来 |
| `mem.span()` 取真实上界 | `arena + MEM_SIZE` 在 ET_EXEC 下比 backing 长（backing 只有 `MEM_SIZE - low`），越界读 `0x43ef900+16` |
| Tier1 扫 `image.xranges`（主程序 **+ 解释器**所有可执行段） | svc 指令全在解释器代码里；只扫 `image.text` ⇒ 动态程序一律静默回落 Tier0 |
| `close_fd` dup 别名升格 + 所有 fd 查表走 `real_fd()` | busybox ash 的 `open_script` 是 `open → fcntl(F_DUPFD,10) → close 原位`。关原件把副本一起弄死 ⇒ 脚本被当空文件、退出码 0 且零输出（极隐蔽） |
| 新增 `readlinkat(78)`、`fchmod/fchmodat(52/53)`、`statfs/fstatfs(43/44)`、`madvise(233)`；aarch64 信号号按 generic 表重排 | `ls -l`、`chmod +x 脚本`、`df /`、`apk` 分别需要；`135` 被误当 rt_sigprocmask 会让 apk 直接挂 |

### 14.2 真 exec：按 PATH / shebang / PT_INTERP 分流

`host/src/lib.rs` 新增 `resolve_prog()`：带 `/` 走路径、否则按 PATH 逐个试；`#!` 脚本转成
`busybox sh <脚本>`；ELF 装它（有 `PT_INTERP` 就连解释器一起装）；都不是才回落 `busybox <applet>`。
`resolve_host()` 在 jail 内跟随软链（Alpine 的 `/bin/ls -> /bin/busybox` 是**绝对**软链，
宿主 `metadata()` 会去解析宿主自己的 `/bin/busybox`）。

### 14.3 真正难的一个 bug：fd 0/1/2 与 musl `map_library()`

`apk.static`（静态）能跑，但 `/sbin/apk`（动态）打 **107 条 `symbol not found`**
（`apk_db_*`、`fetchConnectionCacheInit`、`uncompress` 等）后 exit 127。`CH_MMAP_DEBUG=1`
细粒度日志给出的决定性证据：

```
[ch-open] /lib/libcrypto.so.3 -> fd=0     ← 只有第一个 DT_NEEDED 成功 mmap
[ch-mmap] addr=0 len=0x466000 fd=0 ... got=4597896
[ch-open] /lib/libz.so.1                 ← 打开了，之后一个 mmap 都没有
[ch-open] /lib/libapk.so.2.14.0          ← 同上
Error relocating /sbin/apk: ...: symbol not found   （×107）
```

根因链：`openat` 用 `while files.contains_key(fd)` 从 **0** 找空位 ⇒ 第一个库拿 fd 0、
第二个 fd 1、第三个 fd 2；而 `fstat(1)/fstat(2)` 走的是「stdio 字符设备 stub、
`st_size=0`」分支（本来是给 busybox `sort` 判断 stdout 用的）；
musl 的 `map_library()` 一见 `st_size==0` 就 EINVAL 放弃，库压根没被映进地址空间，
随后所有符号查找必然失败。musl 的加载失败提示在这里也不打印，所以表面上只看到 107 条
重定位错误 —— 从错误表象完全反推不到 fd 分配。

修法三处（互为补充）：
1. `openat` 改用 `p.alloc_fd(0)`：0/1/2 是终端注入的 stdin/out/err，恒视为已占用，新文件从 **3** 起（Linux `alloc_fd` 语义）。
2. `fstat(0/1/2)` 先看 `p.files` 是否真接了文件；接了就报真实 metadata，只有没接才用 stub。
3. `close(0/1/2)` 不再只摘别名——必须真的 `close_fd()`，否则 musl 关库文件时 fd 表一路泄漏。

修完同一条命令直接输出 `apk-tools 2.14.4, compiled for aarch64.`，
4 个共享库（libcrypto 4.6MB / libz / libapk / libssl）递归加载、符号解析、GOT 重定位、
malloc 堆扩张全部走通。

### 14.4 验证

- **aarch64 VM（qemu + Alpine 真内核）66 项矩阵**：**65 × exit 0 + 2 × 故意负例 exit 1**，
  `[ch-unimpl]` **0 次**，无 panic / 越界；每一例 `fallback=0`，`[ch-dyn loads]` 在动态项为 1/2/3。
- 新增 apk 实测：`--version` 2.14.4、`info` 16 个包、`list --installed`、
  `policy musl`（真读 `/lib/apk/db/installed` 包库）、`info -v musl`、`--help`、与静态 busybox 混合管道。
- 单测：`cargo test -p ch-kernel -p ch-engine -p ch-runner` → **18 passed / 0 failed**。
  新增 `alloc_fd_skips_stdio` 回归用例，把上面那条根因钉死。
- `CH_MMAP_DEBUG=1` 作为常驻排障开关保留（默认关）：只打印 open/fstat/mmap，
  不会像 `CH_STRACE` 那样把几万条 syscall 刷出来。

### 14.5 仍然没做到（依赖顺序不变）

1. **软页表**（→ 并发 guest 进程 → `clone/execve/wait4`）— 仍是第一优先级
2. **真 PTY**（→ `^C`/`isatty`/`vi/less/top`）— 依赖 1
3. **真 DBT** — 当前 Tier1 功能等价但每条 syscall 两次态切换

`apk add` 未纳入矩阵：包安装还要走脚本执行、chown、硬链接等链路，
在软页表/真 PTY 落地前没有验证价值。
