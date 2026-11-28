package com.chengyan.app.data

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * ChengYan 终端运行时 —— **Alpine Linux 3.24 rootfs + proot + 真 PTY，独立实现**。
 *
 * 完整链路（自研实现）：
 *   1) linkNativeLibs(): nativeLibraryDir 的交叉编译工具链（bash/busybox/proot/loader/talloc）
 *      无条件建链到 usr/bin（静态 ET_EXEC，软链失败退实体拷贝 —— 云手机 FS 兼容）
 *   2) createBusyboxSymlinks(): usr/bin 下铺 busybox applet 链
 *   3) extractAssets(): Alpine Linux 3.24 aarch64 rootfs tarball 按 lastUpdateTime+ASSET_VERSION 拷入
 *   4) writeCommonScript(): common.sh（install_alpine）
 *   5) prepareGuestRootfs(): 宿主侧用 cy-bash 跑 install_alpine 解压（不开 proot，无 PEEKDATA 问题）
 *   6) buildProotLaunch(): proot -r rootfs ... /bin/sh -l ...（proot 作为 PTY 直接子进程）
 *   7) Pty.start(proot...) → fallback 600ms 验活 → 回落系统 shell 打屏
 *
 * 关键铁律：proot 必须是 PTY 的**直接**子进程，不能经宿主 bash 中转，
 * 否则 loader 注入 PEEKDATA 失败 → 黑屏。静态 ET_EXEC 不能被 isPieElf 门控误判跳过建链。
 */
object TtyBridge {

    data class State(
        val busy: Boolean = false,
        val exit: Int? = null,
        val output: String = "",
        val outputLen: Int = 0,
    )

    @Volatile
    var lastError: String = ""
        private set

    private var ctx: Context? = null
    private val lock = Any()
    private val readLock = Any()
    private var pty: Pty? = null
    private val outBuf = StringBuilder()
    private var initSwept = false

    // ---- 会话状态机（独立设计：哨兵退出码协议）----
    // 终端是常驻 shell：写入一条命令后，shell 继续执行、PTY 持续吐输出，
    // 必须有个可靠的信号告诉宿主「这条命令跑完了，退出码是多少」。
    // 方案：run() 在命令尾部追加一条 printf，打印带唯一序号的真实退出码 $?；
    // poll() 扫到该标记即 busy=false + exit=rc。比提示符正则可靠（提示符会
    // 被程序输出伪装、PS1 可变），标记是宿主自己注入的、不会与业务输出冲突。
    @Volatile private var busy = false
    @Volatile private var lastExit: Int? = null
    private var seq = 0L                 // 命令序号，标记自带，防旧标记误匹配
    private val pendingSalt = HashMap<Long, String>()   // seq → 本条命令的哨兵盐
    private val runLock = Any()          // run() 状态机互斥：busy 检查+置位+seq 自增必须原子
    @Volatile private var startingPid = -1   // 正在启动、尚未成为正式会话的 pty（清扫跳过它）
    private var cmdStartMs = 0L          // 命令起始时间，用于超时兜底
    private const val CMD_TIMEOUT_MS = 600_000L   // 单命令 10 分钟超时兜底

    private val filesDir: File get() = ctx!!.filesDir
    private val usrDir: File get() = File(filesDir, "usr")
    private val binDir: File get() = File(usrDir, "bin")
    private val nativeLibDir: String get() = ctx!!.applicationInfo.nativeLibraryDir
    private val sharedHostDir: String get() = ctx!!.getExternalFilesDir(null)?.absolutePath
        ?: File(filesDir, "shared").apply { mkdirs() }.absolutePath

    fun init(context: Context) {
        ctx = context.applicationContext
        // 🔴 幂等：MainActivity 组合内会重复调用 init，每次都新开 executor 全扫 /proc（且可能误杀）
        if (initSwept) return
        initSwept = true
        // App 一启动就清旧账：上一版泄漏的 proot/ash 在 App 升级后仍然驻留（同 uid），
        // 拖满 load（真机 load 9.1 ANR）。不能等用户打开终端（ensure()）才清——
        // 用户可能一直停在聊天页，旧进程照样把主线程饿死。
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            runCatching { val n = killOrphanRuntimes(); if (n > 0) lastError = "启动自检: 清理残留运行时进程 $n 个" }
        }
    }
    private var initialized = false

    // ---------------- 初始化（照 TerminalEnvironment.initializeEnvironment）----------------

    private fun ensureInit(): Boolean {
        if (initialized) return true
        synchronized(lock) {
            if (initialized) return true
            try {
                createDirectories()
                linkNativeLibs()
                createBusyboxSymlinks()
                extractAssets()
                writeCommonScript()
                initialized = true
                return true
            } catch (e: Throwable) {
                lastError = "环境初始化失败：${e::class.simpleName} ${e.message}"
                return false
            }
        }
    }

    private fun createDirectories() {
        usrDir.mkdirs()
        binDir.mkdirs()
        File(filesDir, "tmp").mkdirs()
        File(filesDir, "proot_home").mkdirs()
    }

    /** 无条件建链：静态 ET_EXEC 由内核 binfmt_elf 直接映射，不经动态链接器。 */
    private fun linkNativeLibs() {
        val nl = File(nativeLibDir)
        if (!nl.isDirectory) { lastError = "nativeLibraryDir 不存在：$nativeLibDir"; return }

        fun linkOne(libName: String, linkName: String) {
            val src = File(nativeLibDir, libName)
            val dst = File(binDir, linkName)
            if (!src.exists()) return
            val ok = runCatching {
                Files.deleteIfExists(dst.toPath())
                src.setExecutable(true, false)
                Files.createSymbolicLink(dst.toPath(), src.toPath())
                dst.exists() && dst.canExecute()
            }.getOrDefault(false)
            if (!ok) runCatching {
                Files.deleteIfExists(dst.toPath())
                src.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
            }
        }

        linkOne("libbusybox.so", "busybox")
        // cy-bash 是终端自己的 bash，独立命名避免与其它子系统抢路径。
        linkOne("libbash.so", "cy-bash")
        linkOne("libbash.so", "bash")
        linkOne("libproot.so", "proot")
        linkOne("libloader.so", "loader")
        linkOne("liblibtalloc.so.2.so", "libtalloc.so.2")
        linkOne("libsudo.so", "sudo")
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (name in links) {
            val dst = File(binDir, name)
            try {
                Files.deleteIfExists(dst.toPath())
                val target = if (busybox.parentFile == dst.parentFile) Paths.get(busybox.name) else busybox.toPath()
                Files.createSymbolicLink(dst.toPath(), target)
            } catch (_: Throwable) {}
        }
    }

    /** Alpine tarball 按 lastUpdateTime + ASSET_VERSION 拷入（半截包原子替换）。 */
    private fun extractAssets() {
        val assetName = ALPINE_FILENAME
        val dest = File(filesDir, assetName)
        val stamp = File(filesDir, ".assets.stamp")
        val prev = runCatching { stamp.readText() }.getOrDefault("")
        val installTime = runCatching {
            ctx!!.packageManager.getPackageInfo(ctx!!.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val av = Regex("^av=(\\d+)$", RegexOption.MULTILINE).find(prev)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val needCopy = !prev.contains("v=$installTime") || av != ASSET_VERSION || !dest.exists() || dest.length() < 1_000_000L
        if (needCopy) {
            val tmp = File(filesDir, "$assetName.tmp")
            try {
                // AGP 把 assets/*.tar.gz 收进 APK 时去掉 .gz 后缀（rootfs.tar.gz → rootfs.tar），
                // 内容仍是 gzip 流。两个名字都试，避免真机 assets.open 抛异常。
                val names = listOf(assetName, assetName.removeSuffix(".gz"))
                val stream = names.firstNotNullOfOrNull { n ->
                    try { ctx!!.assets.open(n) } catch (_: Throwable) { null }
                } ?: throw IllegalStateException("APK 内找不到 Alpine rootfs 资产（试过 $names）")
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            } catch (e: Throwable) {
                // 瘦身包兜底：资产缺失时若设备已有解压好的 rootfs，仍可继续。
                tmp.delete()
                if (!dest.exists()) throw e
            }
        }
        runCatching { stamp.writeText("v=$installTime\nav=$ASSET_VERSION\n$assetName=${dest.length()}\n") }
    }

    /** common.sh —— 宿主侧 install_alpine 解压用（纯文件操作，不开 proot）。 */
    private fun writeCommonScript() {
        val tmpDir = File(filesDir, "tmp").absolutePath
        val b = binDir.absolutePath
        val guestPath = File(usrDir, "var/lib/proot-distro/installed-rootfs/alpine").absolutePath
        val script = """
export TMPDIR=$tmpDir
export BIN=$b
export GUEST_PATH=$guestPath
export GUEST=$ALPINE_FILENAME
install_alpine(){
  mkdir -p ${'$'}GUEST_PATH 2>/dev/null
  if [ -z "${'$'}(ls -A ${'$'}GUEST_PATH)" ]; then
    echo "extracting Alpine Linux 3.24 rootfs..."
    ${'$'}BIN/busybox tar xf ${'$'}HOME/${'$'}GUEST -C ${'$'}GUEST_PATH/ >/dev/null 2>&1
    rm -f ${'$'}HOME/${'$'}GUEST
  fi
  echo 'nameserver 8.8.8.8' > ${'$'}GUEST_PATH/etc/resolv.conf 2>/dev/null
  fetch_bash
  echo PREPARE_DONE
}
# 完整 bash：Alpine minirootfs 只有 ash。解压后从官方 CDN 拉 bash + 依赖
# （readline/ncurses）解入 rootfs，之后 bash 离线可用。无网络时静默跳过。
# 🔴 解析规则：APKINDEX 优先（权威但可能滞后）、目录页兜底（可能缺包）——
#    两者实测都会临时缺数据，必须双源。
fetch_bash(){
  [ -x "${'$'}GUEST_PATH/bin/bash" ] || [ -x "${'$'}GUEST_PATH/usr/bin/bash" ] && return 0
  CDN=https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64
  APKIDX=${'$'}TMPDIR/APKINDEX
  # APKINDEX 尽力拉取（失败不影响，走目录页兜底）
  wget -qO "${'$'}TMPDIR/apkidx.tar.gz" "${'$'}CDN/APKINDEX.tar.gz" 2>/dev/null \
    && busybox tar xzf "${'$'}TMPDIR/apkidx.tar.gz" -O APKINDEX > "${'$'}APKIDX" 2>/dev/null
  rm -f "${'$'}TMPDIR/apkidx.tar.gz"
  page=${'$'}(wget -qO- "${'$'}CDN/" 2>/dev/null)
  # resolve_pkg NAME -> 文件名；APKINDEX 精确行匹配 P:NAME/V:；目录页锚定 href=" 且包名后紧跟数字（排 -dbg/-dev 等）
  resolve_pkg(){
    name=${'$'}1
    if [ -f "${'$'}APKIDX" ]; then
      v=${'$'}(grep -x -A2 "P:${'$'}name" "${'$'}APKIDX" 2>/dev/null | grep "^V:" | head -1 | cut -d: -f2)
      [ -n "${'$'}v" ] && { echo "${'$'}name-${'$'}v.apk"; return 0; }
    fi
    echo "${'$'}page" | grep -o '"'"${'$'}name"'-[0-9][^"<>]*\.apk' | sort -u | tail -1
  }
  for name in bash readline libncursesw ncurses-libs ncurses-terminfo-base; do
    f=${'$'}(resolve_pkg "${'$'}name")
    [ -n "${'$'}f" ] || continue
    wget -qO "${'$'}TMPDIR/${'$'}f" "${'$'}CDN/${'$'}f" 2>/dev/null || continue
    # 🔴 apk 是多段 gzip 拼接（签名+控制+数据），tar xzf 只解第一段必须 gzip -dc | tar
    gzip -dc "${'$'}TMPDIR/${'$'}f" | busybox tar -x -C "${'$'}GUEST_PATH" 2>/dev/null
    rm -f "${'$'}TMPDIR/${'$'}f"
  done
  if [ -x "${'$'}GUEST_PATH/bin/bash" ]; then
    echo BASH_OK
  elif [ -x "${'$'}GUEST_PATH/usr/bin/bash" ]; then
    ln -sf /usr/bin/bash "${'$'}GUEST_PATH/bin/bash" 2>/dev/null
    echo BASH_OK
  else
    echo BASH_SKIP
  fi
}
""".trimIndent()
        File(filesDir, "common.sh").writeText(script)
    }

    /** 宿主侧用 cy-bash 跑 install_alpine（不开 proot，无 PEEKDATA 问题）。 */
    private fun prepareGuestRootfs(): Boolean {
        val bash = File(binDir, "cy-bash")
        if (!bash.exists() || !bash.canExecute()) { lastError = "缺 usr/bin/cy-bash（工具链未链接）"; return false }
        return try {
            val pb = ProcessBuilder(bash.absolutePath, "-c", "source \"\$HOME/common.sh\" && install_alpine")
            pb.directory(filesDir)
            pb.environment().putAll(baseEnv())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ok = p.waitFor(600_000, java.util.concurrent.TimeUnit.MILLISECONDS) && p.exitValue() == 0
            if (!ok) lastError = "rootfs 解压失败：$out"
            ok
        } catch (e: Throwable) {
            lastError = "rootfs 解压异常：${e::class.simpleName} ${e.message}"
            false
        }
    }

    private fun baseEnv(): MutableMap<String, String> = mutableMapOf(
        "PATH" to "/system/bin:/system/xbin:${binDir.absolutePath}:${System.getenv("PATH") ?: ""}",
        "HOME" to filesDir.absolutePath,
        "PREFIX" to usrDir.absolutePath,
        "LD_LIBRARY_PATH" to "$nativeLibDir:${binDir.absolutePath}:/lib:/usr/lib:/usr/local/lib",
        "PROOT_LOADER" to File(binDir, "loader").absolutePath,
        "TMPDIR" to File(filesDir, "tmp").absolutePath,
        "PROOT_TMP_DIR" to File(filesDir, "tmp").absolutePath,
    )

    /** proot 作为 PTY 直接子进程。 */
    private fun buildProotLaunch(): Array<String> {
        val rootfs = File(usrDir, "var/lib/proot-distro/installed-rootfs/alpine")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val sharedHome = File(filesDir, "proot_home").apply { mkdirs() }.absolutePath
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath

        // guest bind 挂载点必须真实存在，否则 proot 静默跳过 → -w /root 落空 → getcwd 失败
        for (p in listOf("root", "tmp", "mnt", "mnt/shared", "root/shared", "dev", "dev/pts", "proc", "sys", "storage/emulated"))
            File(rootfs, p).mkdirs()

        val cmd = mutableListOf(
            prootBin,
            "--rootfs=${rootfs.absolutePath}",
            "--link2symlink",
            "--bind=/dev", "--bind=/dev/pts", "--bind=/dev/urandom:/dev/random",
            "--bind=/proc", "--bind=/proc/net:/proc/net", "--bind=/sys",
            "--bind=$tmpDir:/tmp", "--bind=$tmpDir:/dev/shm",
            "--bind=$sharedHome:/mnt/shared", "--bind=$sharedHome:/root/shared",
            "--bind=${filesDir.absolutePath}:/chengyan",
            "-0", "-w", "/root",
            "/bin/sh", "-c",
            "echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi"
        )
        return cmd.toTypedArray()
    }

    private fun prootEnv(): Map<String, String> = mapOf(
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.cargo/bin:/root/.local/bin",
        "LD_LIBRARY_PATH" to "$nativeLibDir:${binDir.absolutePath}:/lib:/usr/lib:/usr/local/lib",
        "TMPDIR" to "/tmp",
        "PROOT_TMP_DIR" to File(filesDir, "tmp").absolutePath,
        "PROOT_LOADER" to File(nativeLibDir, "libproot-loader.so").absolutePath
            .let { if (File(it).exists()) it else File(binDir, "loader").absolutePath },
        "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
        "CURL_CA_BUNDLE" to "/etc/ssl/certs/ca-certificates.crt",
    )

    private fun fallback(reason: String): Array<String> {
        val why = reason.replace("'", "").replace("\n", " ").take(160)
        return arrayOf("/system/bin/sh", "-c",
            "echo \"[ChengYan] 终端启动失败：$why\"; echo '[ChengYan] 回落系统 shell（功能受限）'; exec /system/bin/sh")
    }

    // ---------------- 对外 API（LinuxBridge 依赖）----------------

    fun ensure(): Long {
        synchronized(lock) {
            if (pty != null) {
                if (pty!!.isAlive()) return 1L
                // 旧 pty 已死（崩溃/退出）：先强杀回收整组 + 回收 fd，绝不能直接开新会话
                runCatching { pty!!.destroyForcibly() }
                pty = null
            }
            if (ctx == null) { lastError = "TtyBridge.init 未调用"; return -10L }
            if (!ensureInit()) return -10L
            // 启动自检：扫杀历史泄漏的 proot/ash 进程（同 uid 可杀），防 load 累积
            val swept = killOrphanRuntimes()
            if (swept > 0) lastError = "self-check: 清理残留运行时进程 $swept 个"
        }
        // 🔴 rootfs 准备（最长 600s 阻塞）移出锁：旧实现持 lock 阻塞数分钟，
        //    restart/shutdown/其他 run 调用方全部被串行卡死。
        if (!prepareGuestRootfs()) return -10L
        synchronized(lock) {
            // 并发竞态：锁外准备期间另一线程可能已建好会话
            if (pty != null && pty!!.isAlive()) return 1L
            val env = prootEnv()
            val cmd = buildProotLaunch()
            // 🔴 startingPid 保护：防 init 自检清扫/上一轮 killOrphanRuntimes 误杀新 pty
            var p = try {
                Pty.start(cmd, env, filesDir)
            } catch (t: Throwable) {
                Pty.start(fallback(t.message ?: "forkpty 失败"), env, filesDir)
            }
            startingPid = p.pid
            try {
                // 600ms 验活：exec 失败/权限受限时立即回落，避免黑屏
                Thread.sleep(600)
                if (!p.isAlive()) {
                    val code = runCatching { p.process.exitValue() }.getOrDefault(-1)
                    append("proot 立即退出 exit=$code，回落系统 shell\n")
                    // 🔴 必须强杀整组并等死：SIGHUP 会被 proot 忽略，子进程 reparent 后成泄漏
                    runCatching { p.destroyForcibly() }
                    p = Pty.start(fallback("proot exit=$code"), env, filesDir)
                    startingPid = p.pid
                }
            } finally {
                startingPid = -1
            }
            pty = p
            append("ChengYan terminal ready (Alpine 3.24 musl + bash/ash, via proot + real PTY)\n")
            append("[tip] 首次联网启动会自动从 Alpine 官方源安装 bash（BASH_OK=成功）；python3/gcc 等用 `apk add` 装；下载用 busybox wget\n")
            append("[build] v1.9.32 (32) · ${com.chengyan.app.BuildConfig.BUILD_STAMP}\n")
            return 1L
        }
    }

    fun run(cmd: String): Long {
        if (pty == null && ensure() <= 0) return -10L
        // 🔴 busy 检查+置位+seq 自增原子化：旧实现两线程（终端 UI + AI 工具循环）可同时通过
        //    if (busy)，两条命令的哨兵标记交错 → 状态机错乱。
        synchronized(runLock) {
            if (busy) { lastError = "上一条命令尚未完成"; return -3L }
            val id = ++seq
            return try {
                // 哨兵协议：命令照原样交给 shell，尾部追加一条只回显退出码的 printf。
                //   <cmd>; __cy_rc=$?; printf '\n__CY_DONE_<id>_<salt>_%d__\n' "$__cy_rc"
                // salt 为随机数：防业务输出（cat/echo）恰好含标记字符串造成假完成。
                val salt = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x100000))
                pendingSalt[id] = salt
                val line = SessionProtocol.wrap(cmd, id, salt)
                pty!!.stdin.write(line.toByteArray())
                pty!!.stdin.flush()
                busy = true
                cmdStartMs = System.currentTimeMillis()
                id
            } catch (e: Throwable) {
                pendingSalt.remove(id)
                lastError = "写入失败：${e.message}"
                -10L
            }
        }
    }

    fun poll(): State {
        synchronized(readLock) {
            try {
                val s = pty?.stdout
                if (s != null) {
                    // 一次 read 只取当前可用字节，PTY 是被行规程按块喂的，
                    // 标记可能被拆到两次 read —— 所以扫描必须针对累积的 outBuf。
                    val n = s.available()
                    if (n > 0) {
                        val buf = ByteArray(minOf(n, 1 shl 16))
                        val got = s.read(buf)
                        if (got > 0) append(String(buf, 0, got))
                    }
                }
            } catch (_: Throwable) {}

            // 一次 poll 只做一次全文快照：状态机扫描与显示剥离共用，
            // 空闲时 dirty=false 直接返回缓存，零大字符串分配。
            val snapshot = rawSnapshot()
            // 扫描累积输出里的完成标记：只认当前序号，命中即翻转状态。
            if (busy) {
                val hit = SessionProtocol.exitCodeOf(snapshot, seq, pendingSalt[seq].orEmpty())
                if (hit != null) {
                    lastExit = hit
                    busy = false
                    pendingSalt.remove(seq)
                } else if (System.currentTimeMillis() - cmdStartMs > CMD_TIMEOUT_MS) {
                    // 超时兜底：命令卡死（等待输入/网络）时不能让 busy 永远为真，
                    // 否则 UI 一直「运行中」、工具的 wait 永远等下去。
                    busy = false
                    lastExit = -1
                    append("[timeout] 命令超过 ${CMD_TIMEOUT_MS / 1000}s 未完成\n")
                }
            }
            if (visibleDirty) {
                visibleCache = SessionProtocol.stripMarkers(snapshot)
                visibleDirty = false
            }
            return State(pty?.isAlive() == true, lastExit, visibleCache, visibleCache.length)
        }
    }

    /** ^C：往 PTY 主端写 0x03，线路规程转成 SIGINT 发前台进程组；同时立即结束本命令状态。 */
    fun interrupt() {
        try { pty?.stdin?.write(0x03); pty?.stdin?.flush() } catch (_: Throwable) {}
        if (busy) {
            busy = false
            lastExit = 130   // 128 + SIGINT(2)，与 POSIX shell 一致
        }
    }

    /** 显示缓存：poll 每 150ms 调一次，不能每次都把 384KB outBuf 全文 toString+扫描
     *  （真机 ANR：minor faults 69377 = 高频大字符串分配）。只在 append 置脏后重算。 */
    @Volatile private var visibleDirty = true
    private var visibleCache = ""
    private var rawCache = ""

    private fun append(s: String) {
        // 原始字节全部进 outBuf：状态机要从中扫描完成标记，绝不能在这里过滤。
        // 显示层（poll 返回 State.output）才剥掉标记回显，UI 形态无需改动。
        // 🔴 synchronized(readLock)：append 的调用方持两把不同的锁（ensure 持 lock、
        //    poll 持 readLock），旧实现 outBuf 并发 append/toString 是数据竞争。
        synchronized(readLock) {
            outBuf.append(s)
            if (outBuf.length > 384 * 1024) outBuf.delete(0, outBuf.length - 384 * 1024)
            visibleDirty = true
        }
    }

    /** outBuf 的字符串快照（只在 dirty 后重算，poll 高频调用不能每次 toString 384KB）。 */
    private fun rawSnapshot(): String {
        if (visibleDirty) rawCache = outBuf.toString()
        return rawCache
    }

    /** master fd 残留字节 drain：旧命令的半截输出（如标记回显碎片）不能带进新会话。 */
    private fun drainPty() {
        val s = pty?.stdout ?: return
        try {
            var guard = 0
            while (guard++ < 64) {
                val n = s.available()
                if (n <= 0) break
                val buf = ByteArray(minOf(n, 1 shl 16))
                if (s.read(buf) <= 0) break
            }
        } catch (_: Throwable) {}
    }

    /**
     * 孤儿运行时清扫：扫 /proc 找同 uid 下残留的 proot / Alpine ash 进程并 SIGKILL。
     * 只能杀同 uid 进程（Android 内核保证），所以不会伤到系统其他进程；
     * 本 App 主进程（myPid）与当前存活 pty 一律跳过。
     * 返回杀掉的个数。
     */
    fun killOrphanRuntimes(): Int {
        val livePid = pty?.takeIf { it.isAlive() }?.pid ?: -1
        var killed = 0
        val procDir = File("/proc")
        val dirs = procDir.listFiles { f -> f.name.all { it.isDigit() } } ?: return 0
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == android.os.Process.myPid() || pid == livePid || pid == startingPid) continue
            val cmdline = runCatching {
                File(d, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            if (cmdline.isEmpty()) continue
            val argv0 = cmdline.trim().substringBefore(' ')
            val base = argv0.substringAfterLast('/')
            // 🔴 精确匹配 argv[0]：旧规则 contains("ash") 会命中 cy-bash（自己的 rootfs 准备进程），
            //    contains("-l") 会命中 "ls -l" 等任意参数——真机会误杀正在解压的 prepareGuestRootfs。
            val isOrphan = base == "proot" || base == "libproot.so" ||
                base == "ash" || base == "sh" || base == "busybox"
            if (isOrphan) {
                if (runCatching { android.os.Process.killProcess(pid) }.isSuccess) killed++
            }
        }
        return killed
    }

    /** App 退出（onDestroy）调用：整组强杀 + 孤儿清扫，不留任何运行时进程。 */
    fun shutdown() {
        synchronized(lock) {
            drainPty()
            runCatching { pty?.destroyForcibly() }
            pty = null
            killOrphanRuntimes()
        }
    }

    fun restart(): Boolean {
        synchronized(lock) {
            // 1) drain master fd 残留字节（旧命令半截流）
            drainPty()
            // 2) 强杀整组 + 阻塞等待真死：旧 proot/ash 没死透就开新会话 = 每次累积一对
            val p = pty
            if (p != null) {
                val rc = runCatching { p.destroyForcibly() }.getOrDefault(-1)
                if (rc == -2) {
                    // 3s 还没死（极端）：再补一轮 SIGKILL
                    runCatching { p.destroyForcibly() }
                }
                pty = null
            }
            // 3) 自检兜底：扫杀任何历史泄漏的运行时进程
            val swept = killOrphanRuntimes()
            outBuf.setLength(0)
            visibleCache = ""
            rawCache = ""
            visibleDirty = true
            lastError = if (swept > 0) "restart: 清理残留进程 $swept 个" else ""
            initialized = false
            busy = false
            lastExit = null
            return ensure() > 0
        }
    }

    fun diag(): String = buildString {
        appendLine("runtime=Alpine Linux 3.24 rootfs + proot + real PTY")
        appendLine("[build] v1.9.32 (32) · ${com.chengyan.app.BuildConfig.BUILD_STAMP}")
        appendLine("rootfs=${File(usrDir, "var/lib/proot-distro/installed-rootfs/alpine").absolutePath}")
        appendLine("tarball=${File(filesDir, ALPINE_FILENAME).let { "${it.exists()} ${it.length()}" }}")
        appendLine("cyBash=${File(binDir, "cy-bash").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("proot=${File(binDir, "proot").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("loader=${File(binDir, "loader").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("ptyAlive=${pty?.isAlive() == true}")
        appendLine("--- 会话状态机 ---")
        appendLine("busy=$busy lastExit=$lastExit seq=$seq rawLen=${outBuf.length}")
        appendLine("markerHit=${SessionProtocol.exitCodeOf(outBuf.toString(), seq, pendingSalt[seq].orEmpty()) != null}")
        appendLine("lastError=$lastError")
    }

    private const val ALPINE_FILENAME = "alpine-minirootfs-3.24.0-aarch64.tar.gz"
    private const val ASSET_VERSION = 1
}
