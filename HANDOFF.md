# ChengYan 终端 — 新环境接手文档

> 最后更新：2026-11-27。目标：另一个环境 clone 后能直接构建、理解架构、继续开发。
> 工程根：`D:/Calw OS-project/ChengYan`（GitHub `Quor-a/ChengYan`，main 分支）

## 1. 架构（照 ZorvAI terminal-core 完整搬入，去品牌化）

**终端 = Alpine Linux 3.24 rootfs + proot + 真 PTY**。架构照 ZorvAI terminal-core 完整搬入（去品牌化），发行版按用户要求换成 Alpine Linux 3.24：

```
TerminalScreen (Compose UI，保留原样)
  → LinuxBridge (兼容层，对外 API 不变)
  → TtyBridge (完整运行时，本轮重写)
      ├─ linkNativeLibs()      nativeLibDir 交叉编译工具链 → usr/bin 建链（静态 ET_EXEC，软链失败退实体拷贝）
      ├─ createBusyboxSymlinks() usr/bin 铺 busybox applet
      ├─ extractAssets()       Alpine 3.24 aarch64 tarball (3.9MB) 按 lastUpdateTime+ASSET_VERSION 拷入
      ├─ writeCommonScript()   common.sh (install_alpine 解压)
      ├─ prepareGuestRootfs()  宿主侧 cy-bash 跑 install_alpine（不开 proot）
      └─ buildProotLaunch()    proot -r rootfs ... /bin/sh -c '...exec /bin/sh -l'（Alpine 默认 ash）
  → Pty.start(proot...)        JNI forkpty → execvpe，proot 是 PTY **直接**子进程
```

### 三条铁律（ZorvAI 复盘，违反就黑屏）
1. **proot 必须是 PTY 直接子进程**，不能经宿主 bash 中转 —— 否则 loader 注入 PEEKDATA 失败 → 黑屏。
2. **静态 ET_EXEC 不能做 PIE 门控**：`libbash.so`/`libbusybox.so`/`libloader.so` 都是 e_type=2 无 PT_INTERP，内核 binfmt_elf 直接映射执行；`isPieElf` 误判跳过建链 → exec 不存在的文件 → 终端必死。
3. **软链失败必须退实体拷贝**：云手机 FUSE FS 不支持 symlink；只 Log+return 会导致 usr/bin/cy-bash 不存在 → 黑屏。

## 2. 资产清单（全部已入库）

- `app/src/main/assets/alpine-minirootfs-3.24.0-aarch64.tar.gz` — 4,043,766 B，515 文件，sha256 `4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a`，VERSION_ID=3.24.0，含 busybox/sh/apk/musl 动态链接器
- `app/src/main/jniLibs/arm64-v8a/lib{bash,busybox,proot,loader,libtalloc.so.2,sudo,pty}.so` — 交叉编译工具链（全 aarch64）
- `app/src/main/assets/linux_env/libproot-loader.so` — proot loader
- `app/src/main/cpp/pty.c` + `CMakeLists.txt` — JNI forkpty（去品牌化）

## 3. 构建

```bash
# 需 Android SDK + NDK 27 + JDK 17（本机 gradle 8.13，阿里云镜像优先）
./gradlew :app:assembleRelease --no-daemon
python scripts/gate-userspace.py    # 门禁：验 APK 内全套 so 是 aarch64 ELF + Alpine 3.24 rootfs 3-12MB + 旧内核已删；必须 PASS
```

门禁脚本 `scripts/gate-userspace.py` 是硬门槛，任何资产缺失/架构不符即非零退出，不出包。

## 4. 当前状态 / 待验证

- APK：`ChengYan-v1.9.12-Alpine3.24完整架构-20261127.apk`（35MB，含 3.9MB Alpine rootfs），已放桌面
- 版本 **1.9.12 / versionCode 12**（未动，按用户铁律）
- **待真机验证**：这套完整架构尚未真机跑过。首启动会解压 3.9MB Alpine rootfs（秒级），解压完进 Alpine ash 登录 shell。
- 之前失败的根因已消除：不再用 Ubuntu（用户要求 Alpine 3.24），不再用 830KB 自编译 busybox 拼凑，不再用 ProcessBuilder 管道，proot 直挂真 PTY。

## 5. 已知坑与修复历史（别重犯）

| 现象 | 根因 | 修复 |
|---|---|---|
| `libtalloc.so.2 not found` | AGP 排除带版本号结尾的 so | jniLibs 用 `liblibtalloc.so.2.so`，链成 `usr/bin/libtalloc.so.2` + LD_LIBRARY_PATH |
| `'/bin/sh' is not executable` | 解包丢 tar mode | 按 tar header 恢复 chmod |
| `execve Permission denied` | filesDir noexec | 可执行文件放 nativeLibraryDir（系统给 exec 权限） |
| `CANNOT LINK EXECUTABLE ... unexpected e_type:2` | 把静态 ET_EXEC 当 PIE / dlopen | 不做 PIE 门控，不做 dlopen，直接 exec |
| 终端黑屏但环境就绪 | proot 经 bash 中转，PEEKDATA 失败 | proot 直挂 PTY |
| 修了没变化 | rootfs marker/asset stamp 没升版 | 改资产内容必须升 ASSET_VERSION / marker |

## 6. 仓库布局说明（避免接手方误判）

- `app/` — **唯一现役工程**，所有终端代码与资产都在这里。
- `scripts/gate-userspace.py` — 构建门禁，出包前必跑。
- `chengyan-android/` — 历史源码副本（36 文件），**不参与构建**（settings.gradle.kts 只 include `:app`），仅作参考，勿在其上开发。
- `toolchain/`、`userspace-build/` — 本地交叉编译实验残留，已被 .gitignore 排除，不入库；现役用户空间是 `app/src/main/assets/alpine-minirootfs-3.24.0-aarch64.tar.gz`（预编译资产）。

## 7. 下一步


1. 真机装 APK，观察首启动解压进度与最终是否进 Alpine ash（提示符）。
2. 若失败：点终端「自检」按钮看 `LinuxBridge.diag()` 输出（rootfs/tarball/cyBash/proot/loader/ptyAlive/lastError）。
3. 若进 shell：验证 `ls`/`apk`/`cat`、`^C` 中断、窗口大小变化。需要更多工具时 `apk add bash python3`。
