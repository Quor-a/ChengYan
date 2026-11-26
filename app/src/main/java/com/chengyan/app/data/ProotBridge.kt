package com.chengyan.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * ChengYan 新运行时：用 proot 承载**自己交叉编译**的 aarch64 Linux 用户空间。
 *
 * 与旧实现（自研 Rust 内核 + svc 陷门）的区别：
 *  - 用户空间由 `userspace-build/build-zig.sh` 从 busybox 源码交叉编译而来，
 *    不是复制 Alpine/Ubuntu 现成 rootfs；
 *  - 执行用成熟的 proot（ptrace 仿真 chroot），不再自研内核；
 *  - 终端 UI 不变，仍走 LinuxBridge 那套 ensure/run/poll 接口。
 *
 * 布局（App 私有 filesDir/cy-linux/）：
 *   proot, libproot-loader.so   —— proot 工具
 *   rootfs/                     —— 解包 cy-rootfs.tar.gz（自编译用户空间）
 */
object ProotBridge {

    data class State(
        val busy: Boolean = false,
        val exit: Int? = null,
        val output: String = "",
        val outputLen: Int = 0,
    )

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> get() = _stateFlow

    @Volatile
    var lastError: String = ""
        private set

    private var ctx: Context? = null
    private val lock = Any()

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null
    private val outBuf = StringBuilder()
    private val readLock = Any()

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun baseDir(): File = File(ctx!!.filesDir, "cy-linux")

    /** 解包资产（幂等）：proot + loader + 自编译 rootfs。 */
    private fun ensureAssets(): Boolean {
        val base = baseDir()
        val rootfs = File(base, "rootfs")
        val proot = File(base, "proot")
        val loader = File(base, "libproot-loader.so")
        try {
            base.mkdirs()
            // proot / loader
            for (name in listOf("proot", "libproot-loader.so")) {
                val dst = File(base, name)
                if (!dst.exists() || dst.length() == 0L) {
                    ctx!!.assets.open("linux_env/$name").use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    }
                }
                // 必须无条件 chmod：assets 解包出来是 0644，且旧版本残留可能没 x 位。
                // Android 的 /data/user/.../files 常被挂载为 noexec，chmod 也没用 ——
                // 所以运行时优先用 jniLibs 里系统解压的 proot（nativeLibraryDir 有 exec 权限）。
                try { android.system.Os.chmod(dst.path, 0b111101101) } catch (_: Throwable) {
                    dst.setExecutable(true)
                }
            }
            // 自编译用户空间（cy-rootfs.tar.gz 首启解包；AGP 可能改名成 .tar）
            val marker = File(rootfs, ".cy-version")
            if (!marker.exists() || marker.readText().trim() != ROOTFS_VERSION) {
                rootfs.deleteRecursively()
                rootfs.mkdirs()
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
                        lastError = "找不到自编译 rootfs 资产（cy-rootfs.tar.gz）"
                        return false
                    }
                val raw: InputStream = if (gz) java.util.zip.GZIPInputStream(stream) else stream
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
                            val type = hdr[156].toInt()
                            when (type) {
                                '5'.code -> File(rootfs, name).mkdirs()
                                '2'.code -> {
                                    val link = String(hdr, 157, 100).trimEnd('\u0000')
                                    val dst = File(rootfs, name)
                                    dst.parentFile?.mkdirs()
                                    try { android.system.Os.symlink(link, dst.path) } catch (_: Exception) {}
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
                                    // 关键：恢复 tar header 里的 mode（hdr[100..108] 八进制）。
                                    // 不恢复的话 busybox 是 0644，/bin/sh 软链就不可执行，
                                    // proot 报 "'/bin/sh' is not executable"。
                                    var m = 0
                                    for (i in 100 until 108) {
                                        val c = hdr[i].toInt()
                                        if (c == 0 || c == ' '.code) break
                                        if (c in '0'.code..'7'.code) m = m * 8 + (c - '0'.code)
                                    }
                                    if (m != 0) {
                                        try { android.system.Os.chmod(dst.path, m) } catch (_: Throwable) {}
                                    }
                                }
                            }
                            val pad = (512 - (size % 512)) % 512
                            if (pad > 0) t.skipNBytes(pad)
                        }
                    }
                }
                marker.writeText(ROOTFS_VERSION)
            }
            // rootfs/bin/busybox 保持 tar 解包出来的实体文件（0755）。
            // filesDir 是 noexec：真正执行时不走它，而是 proot -b 把
            // nativeLibraryDir/libcybusybox.so 绑进 guest 的 /.cybin 后执行那份。
            return true
        } catch (e: Throwable) {
            lastError = "资产准备失败：${e::class.simpleName} ${e.message}"
            return false
        }
    }

    /** 启动常驻 shell（proot -r rootfs /bin/sh）。 */
    fun ensure(): Long {
        synchronized(lock) {
            if (process != null) return 1L
            if (ctx == null) { lastError = "ProotBridge.init 未调用"; return -10L }
            if (!ensureAssets()) return -10L
            try {
                val base = baseDir()
                val rootfs = File(base, "rootfs")
                // 优先 jniLibs 的系统解压路径：/data/app/.../lib/arm64 有 exec 权限，
                // 而 filesDir 常被 noexec 挂载（真机实测 error=13 Permission denied）。
                val nativeLibDir = ctx!!.applicationInfo.nativeLibraryDir
                val nativeProot = listOf("libproot.so", "libproot_exec.so")
                    .map { File(nativeLibDir, it) }.firstOrNull { it.exists() }
                val proot = nativeProot ?: File(base, "proot")

                // ZorvAI 同款：AGP jniLibs 只收 lib*.so，libtalloc.so.2 带版本号结尾会被
                // 静默排除；把 liblibtalloc.so.2.so 拷成 rootfs/libtalloc.so.2，
                // 再靠 LD_LIBRARY_PATH 让 linker 按文件名找到它。
                val tallocDst = File(rootfs, "libtalloc.so.2")
                if (!tallocDst.exists() || tallocDst.length() == 0L) {
                    for (n in listOf("liblibtalloc.so.2.so", "libtalloc.so", "libtalloc.so.2.5.0")) {
                        val src = File(nativeLibDir, n)
                        if (src.exists()) { src.copyTo(tallocDst, overwrite = true); break }
                    }
                }
                val loader = File(base, "libproot-loader.so")
                // 🔴 filesDir 是 noexec 挂载：rootfs/bin/busybox 就算 chmod 0755，
                // execve 仍被内核拒（真机 execve("/bin/sh"): Permission denied）。
                // 解法：把自编译 busybox 作为 native library 放进 nativeLibraryDir
                //（系统安装到有 exec 权限的路径），再用 proot -b 绑到 guest 的 /bin/busybox。
                // guest 的 /bin/sh 软链 → /bin/busybox → 实际执行 nativeLibraryDir 那份。
                val busyboxSo = File(nativeLibDir, "libcybusybox.so")
                // filesDir noexec：guest 里任何实体文件都 execve 不了。
                // 把 nativeLibraryDir bind 到 guest 的 /.cybin，直接执行 bind 进来的 busybox
                //（真实路径在 nativeLibraryDir，有 exec 权限），彻底绕开软链解析与 noexec。
                val cmd = mutableListOf(
                    proot.absolutePath, "-0",
                    "-r", rootfs.absolutePath,
                    "--link2symlink",
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "/dev/pts",
                )
                val shell = if (busyboxSo.exists()) {
                    cmd += listOf("-b", "$nativeLibDir:/.cybin")
                    "/.cybin/libcybusybox.so"
                } else {
                    "/bin/sh"
                }
                // busybox 靠 argv[0] basename 选 applet，basename 是 libcybusybox.so
                // 不是 applet → 必须显式给 "sh" 子命令（等价于 busybox sh）。
                cmd += listOf("-w", "/", shell, "sh")
                val cmdFinal = cmd.toList()
                val pb = ProcessBuilder(cmdFinal).redirectErrorStream(true)
                // proot 每次调用必备的环境（照 ZorvAI MoyuRuntime.prootBaseEnv 的成熟做法）
                if (loader.exists()) pb.environment()["PROOT_LOADER"] = loader.absolutePath
                pb.environment()["LD_LIBRARY_PATH"] = rootfs.absolutePath
                val tmp = File(ctx!!.cacheDir, "proot-tmp").apply { mkdirs() }
                pb.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                val p = pb.start()
                process = p
                stdin = p.outputStream
                stdout = p.inputStream
                append("ChengYan userspace ready (self-compiled aarch64 busybox)\n")
                return 1L
            } catch (e: Throwable) {
                lastError = "proot 启动失败：${e::class.simpleName} ${e.message}"
                return -10L
            }
        }
    }

    /** 执行一行命令：写进常驻 shell 的 stdin。 */
    fun run(cmd: String): Long {
        if (process == null && ensure() <= 0) return -10L
        return try {
            stdin!!.write((cmd + "\n").toByteArray())
            stdin!!.flush()
            1L
        } catch (e: Throwable) {
            lastError = "写入失败：${e.message}"
            -10L
        }
    }

    /** 非阻塞读输出。 */
    fun poll(): State {
        synchronized(readLock) {
            try {
                val s = stdout ?: return _stateFlow.value
                val available = s.available()
                if (available > 0) {
                    val buf = ByteArray(minOf(available, 1 shl 16))
                    val n = s.read(buf)
                    if (n > 0) append(String(buf, 0, n))
                }
            } catch (_: Throwable) {}
            val st = State(
                busy = process?.isAlive == true,
                exit = if (process?.isAlive == false) process?.exitValue() else _stateFlow.value.exit,
                output = outBuf.toString(),
                outputLen = outBuf.length
            )
            _stateFlow.value = st
            return st
        }
    }

    private fun append(s: String) {
        outBuf.append(s)
        if (outBuf.length > 384 * 1024) outBuf.delete(0, outBuf.length - 384 * 1024)
    }

    fun restart(): Boolean {
        synchronized(lock) {
            try { process?.destroyForcibly() } catch (_: Throwable) {}
            process = null; stdin = null; stdout = null
            outBuf.setLength(0)
            lastError = ""
            return ensure() > 0
        }
    }

    fun diag(): String = buildString {
        appendLine("runtime=proot (self-compiled userspace)")
        appendLine("rootfs=${File(baseDir(), "rootfs").absolutePath}")
        appendLine("rootfsExists=${File(baseDir(), "rootfs/bin/busybox").exists()}")
        appendLine("prootAlive=${process?.isAlive == true}")
        appendLine("lastError=$lastError")
    }

    fun current(): State = _stateFlow.value

    // 升版强制重装：v1 的解包丢了 tar mode（busybox 0644 → /bin/sh not executable），
    // 旧设备上 marker 仍是 cy-userspace-1，不升版就会继续用坏的旧 rootfs。
    private const val ROOTFS_VERSION = "cy-userspace-3"
}
