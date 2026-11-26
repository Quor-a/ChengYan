package com.chengyan.app.data

import android.content.Context
import java.io.File

/**
 * ChengYan 新运行时：**静态 busybox + 真 PTY**，不用 proot。
 *
 * 为什么不用 proot（ZorvAI 用的那套）：
 *  - ZorvAI 跑的是 Ubuntu 动态程序（/bin/bash + glibc），需要 proot 做 chroot；
 *  - 我们的是**自己交叉编译的全静态 busybox 用户空间**，没有动态链接依赖，
 *    直接用 forkpty 把 busybox sh 挂到真终端即可 —— 绕开 proot 的全部坑
 *    （loader、talloc、PTRACE、noexec bind）。
 *
 * 架构（照 ZorvAI 的「Pty 直接挂子进程」范式）：
 *   Pty.start([nativeLibDir/libcybusybox.so, "sh"], env, rootfs)
 *     → JNI forkpty → execvpe → busybox sh（controlling tty 已建立）
 *     → ^C 走内核线路规程发 SIGINT，isatty=1，^D 退出
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
    private var pty: Pty? = null
    private val outBuf = StringBuilder()
    private val readLock = Any()

    fun init(context: Context) { ctx = context.applicationContext }

    private fun baseDir(): File = File(ctx!!.filesDir, "cy-linux")

    /** 解包自编译 rootfs（幂等）。busybox 从 nativeLibraryDir 执行（noexec 绕过）。 */
    private fun ensureAssets(): Boolean {
        val base = baseDir()
        val rootfs = File(base, "rootfs")
        val marker = File(rootfs, ".cy-version")
        if (marker.exists() && marker.readText().trim() == VERSION) return true
        try {
            rootfs.deleteRecursively()
            for (d in listOf("bin", "sbin", "lib", "etc", "usr/bin", "usr/sbin", "proc", "sys", "dev", "tmp", "root", "home")) {
                File(rootfs, d).mkdirs()
            }
            val (stream, gz) = listOf("cy-rootfs.tar.gz", "cy-rootfs.tar")
                .mapNotNull { name ->
                    try {
                        val s = java.io.PushbackInputStream(ctx!!.assets.open(name), 2)
                        val magic = ByteArray(2)
                        val n = s.read(magic)
                        val isGz = n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
                        if (!isGz) s.unread(magic, 0, n)
                        s to isGz
                    } catch (e: Exception) { null }
                }.firstOrNull() ?: run {
                    lastError = "找不到自编译 rootfs 资产"
                    return false
                }
            val raw = if (gz) java.util.zip.GZIPInputStream(stream) else stream
            raw.use { tar ->
                java.io.DataInputStream(tar).use { t ->
                    val hdr = ByteArray(512)
                    while (true) {
                        var off = 0
                        while (off < 512) {
                            val n = t.read(hdr, off, 512 - off)
                            if (n < 0) break
                            off += n
                        }
                        if (off < 512) break
                        if (hdr.all { it == 0.toByte() }) break
                        var size = 0L
                        for (i in 124 until 136) {
                            val c = hdr[i].toInt()
                            if (c == 0 || c == ' '.code) break
                            size = size * 8 + (c - '0'.code)
                        }
                        val name = String(hdr, 0, 100).trimEnd('\u0000').removePrefix("./")
                        if (name.isEmpty()) { t.skipNBytes(size); continue }
                        when (hdr[156].toInt()) {
                            '5'.code -> File(rootfs, name).mkdirs()
                            '2'.code -> {
                                val link = String(hdr, 157, 100).trimEnd('\u0000')
                                val dst = File(rootfs, name)
                                dst.parentFile?.mkdirs()
                                try { android.system.Os.symlink(link, dst.path) } catch (_: Throwable) {}
                            }
                            else -> {
                                val dst = File(rootfs, name)
                                dst.parentFile?.mkdirs()
                                dst.outputStream().use { o ->
                                    var left = size
                                    val buf = ByteArray(8192)
                                    while (left > 0) {
                                        val n = t.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                        if (n <= 0) break
                                        o.write(buf, 0, n)
                                        left -= n
                                    }
                                }
                                var m = 0
                                for (i in 100 until 108) {
                                    val c = hdr[i].toInt()
                                    if (c == 0 || c == ' '.code) break
                                    if (c in '0'.code..'7'.code) m = m * 8 + (c - '0'.code)
                                }
                                if (m != 0) try { android.system.Os.chmod(dst.path, m) } catch (_: Throwable) {}
                            }
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) t.skipNBytes(pad)
                    }
                }
            }
            marker.writeText(VERSION)
            return true
        } catch (e: Throwable) {
            lastError = "资产解包失败：${e::class.simpleName} ${e.message}"
            return false
        }
    }

    fun ensure(): Long {
        synchronized(lock) {
            if (pty != null && pty!!.isAlive()) return 1L
            if (ctx == null) { lastError = "TtyBridge.init 未调用"; return -10L }
            if (!ensureAssets()) return -10L
            try {
                val rootfs = File(baseDir(), "rootfs")
                val busybox = File(ctx!!.applicationInfo.nativeLibraryDir, "libcybusybox.so")
                if (!busybox.exists()) { lastError = "缺 nativeLibraryDir/libcybusybox.so"; return -10L }
                // 静态 busybox 直接挂 PTY：execvpe 的真实路径在 nativeLibraryDir（有 exec 权限），
                // chdir 到 rootfs；busybox 的 "sh" 子命令进入交互 shell。
                val cmd = arrayOf(busybox.absolutePath, "sh")
                val env = mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM" to "xterm-256color",
                    "LANG" to "C.UTF-8",
                )
                val p = Pty.start(cmd, env, rootfs)
                pty = p
                append("ChengYan userspace ready (self-compiled static busybox, real PTY)\n")
                return 1L
            } catch (e: Throwable) {
                lastError = "PTY 启动失败：${e::class.simpleName} ${e.message}"
                return -10L
            }
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
            val st = State(pty?.isAlive() == true, null, outBuf.toString(), outBuf.length)
            return st
        }
    }

    /** ^C：往 PTY 主端写 0x03，真线路规程会转成 SIGINT 发给前台进程组。 */
    fun interrupt() {
        try {
            pty?.stdin?.write(0x03)
            pty?.stdin?.flush()
        } catch (_: Throwable) {}
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
            return ensure() > 0
        }
    }

    fun diag(): String = buildString {
        appendLine("runtime=static-busybox + real PTY (no proot)")
        appendLine("rootfs=${File(baseDir(), "rootfs").absolutePath}")
        appendLine("ptyAlive=${pty?.isAlive() == true}")
        appendLine("lastError=$lastError")
    }

    private const val VERSION = "cy-tty-1"
}
