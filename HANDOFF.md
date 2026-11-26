# ChengYan 交接文档（2026-11-27）

> 给另一个开发环境的完整接手说明。读完这份就能继续开发，不需要翻聊天记录。

## 1. 当前架构（用户拍板，勿再改方向）

```
终端 UI（app/src/main/java/com/chengyan/app/ui/screens/TerminalScreen.kt）—— 保留，勿重写
        ↓ LinuxBridge（兼容层，API 不变）
ProotBridge（app/src/main/java/com/chengyan/app/data/ProotBridge.kt）
        ↓ proot -0 -r rootfs --link2symlink -b ... /bin/sh
自编译 aarch64 Linux 用户空间（busybox + musl，从源码构建，非复制发行版）
```

**三条铁律（用户反复强调）：**
1. 用户空间是**开发出来**的（从源码交叉编译），不是复制 Alpine/Ubuntu 现成 rootfs。
2. **不用自研内核**（原 `linux-core/` 已删，勿恢复）。
3. **终端 UI 保留当前的**，不要换。

## 2. 用户空间构建链（可完整复现）

```bash
# 依赖：zig 0.13（自带 aarch64-linux-musl libc）、make、python3、curl
# Alpine: apk add zig make python3
sh userspace-build/build-zig.sh     # 自动下载 busybox 1.37.0 源码（若无），编译+组装+打包
# 产物：userspace-build/rootfs.tar.gz + busybox-aarch64
cp userspace-build/rootfs.tar.gz app/src/main/assets/cy-rootfs.tar.gz
cp userspace-build/busybox-aarch64 app/src/main/assets/busybox-aarch64
cp userspace-build/busybox-aarch64 app/src/main/jniLibs/arm64-v8a/libcybusybox.so
```

`build-zig.sh` 已解决的 5 个 kbuild 兼容问题（勿删）：
- `-Wp,-MD,<file>` → zig 写成 `-MD -MF`（zig-cc-wrap.sh）
- 关闭 `CONFIG_SHA1_HWACCEL`/`CONFIG_SHA256_HWACCEL`（x86 SHA-NI）
- 过滤 zig ld 不支持的 `--warn-common`/`--sort-*`/`-Map`/`--verbose`
- strip 走 `aarch64-none-elf-strip`
- busybox make 不能在带空格路径跑 → 在 `/tmp/cy-bb` 编译

## 3. Android 运行时（真机踩过的坑，按此顺序排查）

| 错误 | 根因 | 已做的修复 |
|---|---|---|
| `error=13 Permission denied` 启动 proot | filesDir noexec | proot 打包为 `jniLibs/libproot.so`，走 nativeLibraryDir |
| `libtalloc.so.2 not found` | AGP 排除带版本号结尾的 so 名 | `liblibtalloc.so.2.so` 拷成 rootfs/libtalloc.so.2 + `LD_LIBRARY_PATH`；`PROOT_TMP_DIR`、`PROOT_NO_SECCOMP=1` |
| `'/bin/sh' is not executable` | 自写解包丢 tar mode | 解包按 `hdr[100..108]` 恢复 chmod |
| 修复后真机仍报同样错 | marker 未升，跳过解包用旧 rootfs | `ROOTFS_VERSION` 升版强制重解包 + busybox canExecute 兜底 |
| `execve("/bin/sh"): Permission denied` | filesDir noexec，chmod 无效 | busybox 打包为 `jniLibs/libcybusybox.so`，proot `-b` 绑到 guest `/bin/busybox` |

**关键环境事实**：Android `filesDir` 是 noexec 挂载——任何放在那里的可执行文件都跑不起来，
必须走 `nativeLibraryDir`（jniLibs）并用 proot bind。

## 4. 构建与门禁

```bash
./gradlew :app:assembleRelease --no-daemon
python scripts/gate-userspace.py       # 构建后门禁，必须 PASS 才能交付
```

门禁检查：proot 依赖链、busybox 是 aarch64 ELF 且有执行位、`bin/sh` 软链、
`libcybusybox.so` 存在、旧 `libchlinux.so` 已移除。用户明确要求加这道门禁，勿删。

版本：versionCode 12 / versionName 1.9.12（**用户铁律：绝不擅自改版本号**）。

## 5. 当前状态与待验证

- 最新 commit `024ecdd`（noexec 绕过）；桌面 APK `ChengYan-v1.9.12-noexec绕过-20261127.apk`
- **真机尚未验证成功**：一路修到 `execve("/bin/sh"): Permission denied` 这一层，
  已做 noexec 绕过修复，等真机复测。
- 若还有错，下一步排查方向：proot 的 ptrace/seccomp 权限、loader 路径、guest 库依赖。
- 未 push 前的历史 APK 都在桌面，按文件名区分。

## 6. 其它

- 探针功能独立：`libfdosprobe.so` + `libc++_shared.so` + `FdosProbe.kt`（勿删，依赖 libc++_shared）
- ZorvAI（QuroAI）是另一个项目，**不要动**；它的 `.wb-artifacts-staging/source/` 里有
  成熟的 proot 参数参考（TerminalEnvironment.kt / MoyuRuntime.kt）。
- 提交前先确认工作区干净、门禁 PASS；**未经用户测试确认，不要推送/发布**（用户铁律）。
