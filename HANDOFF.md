# ChengYan 交接文档（2026-11-27 深夜更新）

> 给另一个开发环境的完整接手说明。读完就能继续，不用翻聊天记录。

## 1. 当前架构（用户拍板，勿再改方向）

```
终端 UI（app/.../ui/screens/TerminalScreen.kt）—— 保留，勿重写
        ↓ LinuxBridge（兼容层，API 不变）
TtyBridge（app/.../data/TtyBridge.kt）
        ↓ Pty.start([nativeLibDir/libcybusybox.so, "sh"], env, rootfs)
JNI pty.c（app/src/main/cpp/pty.c，forkpty → execvpe）
        ↓
自编译全静态 aarch64 busybox 用户空间（从源码构建，非复制发行版）
```

**三条铁律（用户反复强调）：**
1. 用户空间是**开发出来**的（源码交叉编译），不是复制 Alpine/Ubuntu。
2. **不用自研内核**（原 `linux-core/` 已删，勿恢复）。
3. **终端 UI 保留当前的**。

## 2. 为什么放弃 proot（2026-11-27 深夜重大架构修正）

看完整 ZorvAI 终端架构（`.wb-artifacts-staging/source/terminal-core/`）后确认：

| | ZorvAI | ChengYan |
|---|---|---|
| 用户空间 | Ubuntu 完整发行版（/bin/bash + glibc 动态程序） | **自编译全静态 busybox** |
| 为什么用 proot | 动态程序需要 chroot + loader + bind | **根本不需要** |
| 承载 | `Pty.start(libproot.so --rootfs=... /bin/bash -il)` | `Pty.start(libcybusybox.so sh)` |

之前照搬 proot 踩了 5 个坑（talloc 缺失、noexec、loader、软链、PEEKDATA），
根源是**我们的用户空间是全静态的，不需要 proot**。现在直接 forkpty 挂静态 busybox，
整个 proot 层（libproot.so / libtalloc / loader / PROOT_LOADER / PROOT_TMP_DIR）全部不需要。

**ZorvAI 架构的关键learnings（已用上）：**
- `Pty.kt` + `pty.c` 的 forkpty（不是 ProcessBuilder 管道）——真 tty 才有 ^C/isatty
- proot 必须是 PTY 直接子进程（不能直接 ProcessBuilder），经 bash 中转 PEEKDATA 会失败
- 但静态 busybox 直连 PTY 更干净，连 proot 都省了

## 3. 用户空间构建链（可完整复现）

```bash
# 依赖：zig 0.13（自带 aarch64-linux-musl libc）、make、python3、curl
# Alpine: apk add zig make python3 aarch64-none-elf-binutils
sh userspace-build/build-zig.sh     # 自动下载 busybox 1.37.0 源码，编译+组装+打包
cp userspace-build/rootfs.tar.gz app/src/main/assets/cy-rootfs.tar.gz
cp userspace-build/busybox-aarch64 app/src/main/assets/busybox-aarch64
cp userspace-build/busybox-aarch64 app/src/main/jniLibs/arm64-v8a/libcybusybox.so
```

`build-zig.sh` 解决的 kbuild 兼容问题（勿删）：`-Wp,-MD` 改写、关 SHA-NI、
过滤 zig ld 不支持的链接参数、strip 包装、/tmp 无空格路径编译。

## 4. 构建与门禁

```bash
./gradlew :app:assembleRelease --no-daemon
python scripts/gate-userspace.py       # 必须 PASS 才能交付
```

门禁检查：libpty.so + libcybusybox.so 是 aarch64 ELF、rootfs busybox 有执行位、
bin/sh 软链、旧 libchlinux.so 已移除。用户明确要求，勿删。

版本：versionCode 12 / versionName 1.9.12（**绝不擅自改版本号**）。

## 5. 当前状态

- 最新本地 commit `?`（真 PTY + 静态 busybox）；桌面 APK `ChengYan-v1.9.12-真PTY静态busybox-20261127.apk`
- **真机尚未验证**：构建 + 门禁 PASS，但还没真机测。
- 若有问题，先看 `diag()` 输出和 PTY 是否 alive；可能的点：pty.c 的 forkpty 权限、
  busybox sh 的 argv[0]、rootfs 挂载点。
- 之前 push 到 GitHub 的版本还是 proot 架构（HEAD `d10b0f2`），本地新架构尚未 push。

## 6. 其它

- 探针功能独立：`libfdosprobe.so` + `libc++_shared.so` + `FdosProbe.kt`（勿删）
- ZorvAI（QuroAI）是另一个项目，**不要动**；它的 `.wb-artifacts-staging/source/terminal-core/`
  有完整终端架构参考（Pty.kt / pty.c / TerminalEnvironment.kt / ProotLaunch.kt）。
- **未经用户测试确认，不要推送/发布**（用户铁律）。
