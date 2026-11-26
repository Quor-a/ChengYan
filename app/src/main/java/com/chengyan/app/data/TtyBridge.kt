package com.chengyan.app.data

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * ChengYan 终端运行时 —— **照 ZorvAI terminal-core 完整架构搬入，发行版换 Alpine Linux 3.24**。
 *
 * 完整链路（与 ZorvAI `TerminalEnvironment` 一致，仅去品牌化）：
 *   1) linkNativeLibs(): nativeLibraryDir 的交叉编译工具链（bash/busybox/proot/loader/talloc）
 *      无条件建链到 usr/bin（静态 ET_EXEC，软链失败退实体拷贝 —— 云手机 FS 兼容）
 *   2) createBusyboxSymlinks(): usr/bin 下铺 busybox applet 链
 *   3) extractAssets(): Alpine Linux 3.24 aarch64 rootfs tarball 按 lastUpdateTime+ASSET_VERSION 拷入
 *   4) writeCommonScript(): common.sh（install_alpine）
 *   5) prepareGuestRootfs(): 宿主侧用 cy-bash 跑 install_alpine 解压（不开 proot，无 PEEKDATA 问题）
 *   6) buildProotLaunch(): proot -r rootfs ... /bin/sh -l ...（proot 作为 PTY 直接子进程）
 *   7) Pty.start(proot...) → fallback 600ms 验活 → 回落系统 shell 打屏
 *
 * 关键（ZorvAI 复盘铁律）：proot 必须是 PTY 的**直接**子进程，不能经宿主 bash 中转，
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
    private var initialized = false

    private val filesDir: File get() = ctx!!.filesDir
    private val usrDir: File get() = File(filesDir, "usr")
    private val binDir: File get() = File(usrDir, "bin")
    private val nativeLibDir: String get() = ctx!!.applicationInfo.nativeLibraryDir
    private val sharedHostDir: String get() = ctx!!.getExternalFilesDir(null)?.absolutePath
        ?: File(filesDir, "shared").apply { mkdirs() }.absolutePath

    fun init(context: Context) { ctx = context.applicationContext }

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
        // 与 ZorvAI 分名：cy-bash 是终端自己的 bash，避免与其它子系统抢路径。
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
  echo PREPARE_DONE
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

    /** proot 作为 PTY 直接子进程 —— 照 ZorvAI buildProotLaunch。 */
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
            "echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; exec /bin/sh -l"
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
            if (pty != null && pty!!.isAlive()) return 1L
            if (ctx == null) { lastError = "TtyBridge.init 未调用"; return -10L }
            if (!ensureInit()) return -10L
            if (!prepareGuestRootfs()) return -10L
            val env = prootEnv()
            val cmd = buildProotLaunch()
            val p = try {
                Pty.start(cmd, env, filesDir)
            } catch (t: Throwable) {
                Pty.start(fallback(t.message ?: "forkpty 失败"), env, filesDir)
            }
            // 600ms 验活：exec 失败/权限受限时立即回落，避免黑屏
            Thread.sleep(600)
            if (!p.isAlive()) {
                val code = runCatching { p.process.exitValue() }.getOrDefault(-1)
                append("proot 立即退出 exit=$code，回落系统 shell\n")
                runCatching { p.destroy() }
                pty = Pty.start(fallback("proot exit=$code"), env, filesDir)
            } else {
                pty = p
            }
            append("ChengYan terminal ready (Alpine 3.24 + proot + real PTY)\n")
            return 1L
        }
    }

    fun run(cmd: String): Long {
        if (pty == null && ensure() <= 0) return -10L
        return try {
            pty!!.stdin.write((cmd + "\n").toByteArray())
            pty!!.stdin.flush()
            1L
        } catch (e: Throwable) {
            lastError = "写入失败：${e.message}"
            -10L
        }
    }

    fun poll(): State {
        synchronized(readLock) {
            try {
                val s = pty?.stdout
                if (s != null) {
                    val n = s.available()
                    if (n > 0) {
                        val buf = ByteArray(minOf(n, 1 shl 16))
                        val got = s.read(buf)
                        if (got > 0) append(String(buf, 0, got))
                    }
                }
            } catch (_: Throwable) {}
            return State(pty?.isAlive() == true, null, outBuf.toString(), outBuf.length)
        }
    }

    /** ^C：往 PTY 主端写 0x03，线路规程转成 SIGINT 发前台进程组。 */
    fun interrupt() {
        try { pty?.stdin?.write(0x03); pty?.stdin?.flush() } catch (_: Throwable) {}
    }

    private fun append(s: String) {
        outBuf.append(s)
        if (outBuf.length > 384 * 1024) outBuf.delete(0, outBuf.length - 384 * 1024)
    }

    fun restart(): Boolean {
        synchronized(lock) {
            try { pty?.destroy() } catch (_: Throwable) {}
            pty = null
            outBuf.setLength(0)
            lastError = ""
            initialized = false
            return ensure() > 0
        }
    }

    fun diag(): String = buildString {
        appendLine("runtime=Alpine Linux 3.24 rootfs + proot + real PTY (ZorvAI architecture)")
        appendLine("rootfs=${File(usrDir, "var/lib/proot-distro/installed-rootfs/alpine").absolutePath}")
        appendLine("tarball=${File(filesDir, ALPINE_FILENAME).let { "${it.exists()} ${it.length()}" }}")
        appendLine("cyBash=${File(binDir, "cy-bash").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("proot=${File(binDir, "proot").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("loader=${File(binDir, "loader").let { "${it.exists()} ${it.canExecute()}" }}")
        appendLine("ptyAlive=${pty?.isAlive() == true}")
        appendLine("lastError=$lastError")
    }

    private const val ALPINE_FILENAME = "alpine-minirootfs-3.24.0-aarch64.tar.gz"
    private const val ASSET_VERSION = 1
}
