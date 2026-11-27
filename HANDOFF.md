# ChengYan 终端 — 新环境接手文档

> 最后更新：2026-11-27。目标：另一个环境 clone 后能直接构建、理解架构、继续开发。
> 工程根：`D:/Calw OS-project/ChengYan`（GitHub `Quor-a/ChengYan`，main 分支）

## 1. 架构（独立实现）

**终端 = Alpine Linux 3.24 rootfs + proot + 真 PTY**。发行版按用户要求为 Alpine Linux 3.24，运行时独立实现：

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

### 三条铁律（违反就黑屏）
1. **proot 必须是 PTY 直接子进程**，不能经宿主 bash 中转 —— 否则 loader 注入 PEEKDATA 失败 → 黑屏。
2. **静态 ET_EXEC 不能做 PIE 门控**：`libbash.so`/`libbusybox.so`/`libloader.so` 都是 e_type=2 无 PT_INTERP，内核 binfmt_elf 直接映射执行；`isPieElf` 误判跳过建链 → exec 不存在的文件 → 终端必死。
3. **软链失败必须退实体拷贝**：云手机 FUSE FS 不支持 symlink；只 Log+return 会导致 usr/bin/cy-bash 不存在 → 黑屏。

## AI 工具调用（节流）
`ChatViewModel.request()` 的工具执行循环：**逐个执行 + 320ms 间隔 + 每完成一个立即刷新 UI**。
原因：模型一轮可能发起十几个 tool_call，原来全同步执行完才刷新 = 「一下子执行太快、全蹦出来」。
注意：本轮所有调用必须全部执行（OpenAI function calling 协议要求 assistant.tool_calls
与 tool 结果一一对应，漏一个 API 报错），节流只改执行节奏不改协议结构。
用户点停止会中断后续调用。

## 多模态附件（图片/视频/文档/文件，双向）
- `data/Attachment.kt`（在 Models.kt）：统一附件模型 type=image|video|file|audio + name/mime/base64/uri/size/text。
- `data/AttachmentStore.kt`：URI→附件。图片压 JPEG base64；文本/文档 UTF-8 内联（≤120KB）；
  视频/音频/二进制复制到 `filesDir/attachments` 持久化存 file:// URI（不塞 JSON）。
- `ChatViewModel.send(text, images, attachments)` + `historyUpTo`：附件→ChatTurn.parts。
- `OpenAiClient.ChatTurn.parts` + content 构造：图片走 image_url(data URL)，文档走内联文本块。
- 输入区图标：图片(PickVisualMedia) / ▶视频(GetContent video/*) / 📎文档(GetContent */*)，统一预览栏可删。
- 消息气泡 `AttachmentBubble`：图片缩略图、视频卡片(点击系统播放器)、文档卡片(点击打开)。
- 单测 `AttachmentTypeTest`（5）+ `SessionProtocolTest`（8）共 13 项。

## 灵魂注入（内置角色库）
`data/BuiltinSouls.kt` 四个内置角色（小澄/言砚/墨语/码匠），四段式设定：身份锚点/能力边界/行为准则/表达风格。
`SoulRepository.loadAll()` 缺哪个补哪个，不覆盖用户编辑过的内置。
`SoulCard.toSystemPrompt()` 身份锁首尾声明 + 角色标签 + ui-card 能力说明 + 记忆注入。

## ui-card 富卡片（组件渲染 SDK）
`data/UiCard.kt`：模型在回复里输出 ```ui-card JSON 围栏 → 解析成 Card(note/list/kv/action/code)。
`MessageBubble.UiCardView` 渲染成暖纸卡片；正文里围栏被摘除避免重复显示。
**术语铁律**：可视化小卡片 = ui_widget/ui_card 富卡片，不是 visual_popup/visual_question/ui_control。
系统提示词里说明了 ui-card 用法，模型才知道有这个能力。单测 5 项。

## 系统能力设置
`SettingsScreen` 第 06 章「系统能力」：无障碍服务状态(去设置)/Shizuku 状态(反射 ServiceManager.getService，纯检测不引 SDK)。

## 返回导航（全局）
`MainActivity` 用单一界面栈 `backStack: SnapshotStateList<Route>` 管理所有界面：
- 根 `CHAT` 永远在栈底；`SETTINGS / PREFS / TERMINAL` 逐层 push。
- `BackHandler { pop() }` 注册在栈根，系统返回键/手势只 pop 一层；栈底才 `finish()` 退出。
- 支持二层路径 `SETTINGS → PREFS`，返回 PREFS → SETTINGS → CHAT。
- 对话框/下拉菜单/ModalBottomSheet 由 Compose 自带 BackHandler 先消费，返回只关弹窗不穿层。
- **铁律：新增界面必须走 push/pop，禁止再加独立 boolean 标志，也不要依赖 Activity 默认返回。**

## UI 说明（终端页）
`TerminalScreen.kt` 保持形态：顶栏 / 输出区 / 快捷键行 / 输入行。
视觉为澄言原创暖墨终端配色（TermTheme）：三层底色递进、状态徽章、等宽输出字、
快捷键横向滚动（防挤压）、重建键危险色。所有阻塞 API（ensure/poll/run/restart）已在 IO 线程调用，
**新增任何调 LinuxBridge 的代码必须切 IO，否则 ANR**。

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
