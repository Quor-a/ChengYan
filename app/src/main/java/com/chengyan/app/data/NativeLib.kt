package com.chengyan.app.data

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * `libchlinux.so` 的唯一加载入口。
 *
 * ## 为什么不能写在 `LinuxNative` 的 `init {}` 里
 *
 * Java 类的 `<clinit>` 一旦抛异常，ART 会把该类永久标记为 *erroneous*：**同一个进程内
 * 再也救不回来**，之后每一次访问都是
 * `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`（真机事故，2026-11-26）。
 * 而 dlopen 失败的原因往往是**可恢复**的——临时内存不足、APK 直映射被拒、
 * `nativeLibraryDir` 下文件缺失、上一次会话把进程搞脏……只要类还是干净的，
 * 用户点一次「重建」就能重试；一旦类被弄脏，就只能杀进程。
 *
 * 所以：
 * 1. 加载逻辑放在这个普通对象里，**失败只记录原因，绝不弄脏任何类的 `<clinit>`**；
 * 2. 三条通路依次尝试（系统 loadLibrary → nativeLibraryDir 绝对路径 → 从 APK 解出来再 load）；
 * 3. 失败原因全文保留，终端页与 `linux` 工具都会显示——下次真机出问题能直接看到
 *    `dlopen failed: cannot locate symbol "..."` 这一句，不用再靠猜。
 */
internal object NativeLib {
    const val LIB = "chlinux"

    private val lock = Any()

    @Volatile
    var loaded: Boolean = false
        private set

    /** 走通的那条通路（loadLibrary / nativeLibraryDir / apk-extract）。 */
    @Volatile
    var via: String = ""
        private set

    /** 全部尝试过程的完整记录（多行，含每一条通路的具体报错）。 */
    @Volatile
    var detail: String = ""
        private set

    /**
     * 确保 so 已加载。可重复调用（成功即短路）；失败后可再次调用重试。
     * @param ctx 为空时只走通路 1。
     */
    fun ensure(ctx: Context?): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            val log = StringBuilder()

            // ---- 通路 1：系统标准加载（extractNativeLibs=false 时直接从 APK 映射）----
            try {
                System.loadLibrary(LIB)
                loaded = true
                via = "loadLibrary"
                detail = (log.toString() + "loadLibrary($LIB) OK").trim()
                return true
            } catch (t: Throwable) {
                log.append("① loadLibrary(").append(LIB).append(") 失败：").append(trace(t)).append('\n')
            }

            // ---- 通路 2：nativeLibraryDir 下的绝对路径 ----
            // extractNativeLibs=true 时系统会把 so 解到 /data/app/.../lib/arm64/，
            // 用 System.load 走绝对路径可以绕开「按 soname 搜索」这一层。
            val libDir = ctx?.applicationInfo?.nativeLibraryDir
            if (!libDir.isNullOrBlank()) {
                val f = File(libDir, "lib$LIB.so")
                if (f.isFile && f.length() > 0) {
                    try {
                        System.load(f.absolutePath)
                        loaded = true
                        via = "nativeLibraryDir"
                        detail = (log.toString() + "System.load(${f.absolutePath}) OK").trim()
                        return true
                    } catch (t: Throwable) {
                        log.append("② System.load(").append(f.absolutePath).append(") 失败：")
                            .append(trace(t)).append('\n')
                    }
                } else {
                    log.append("② nativeLibraryDir 里没有 lib$LIB.so（$libDir）\n")
                }
            }

            // ---- 通路 3：自己从 APK 里抽出来放到 filesDir 再 load ----
            // 绕开「APK 内直接 mmap 必须页对齐」这条硬约束：某些第三方安装器会重新
            // 打包/对齐 APK，落到设备上就可能 loadLibrary 失败，而解出来再 load 永远可用。
            val self = extractFromApk(ctx, log)
            if (self != null) {
                try {
                    System.load(self)
                    loaded = true
                    via = "apk-extract"
                    detail = (log.toString() + "System.load($self) OK").trim()
                    return true
                } catch (t: Throwable) {
                    log.append("③ System.load(").append(self).append(") 失败：").append(trace(t)).append('\n')
                }
            }

            via = ""
            detail = log.toString().trim().ifBlank { "未知原因" }
            return false
        }
    }

    /** 从 APK（含 split APK）里抽出本机 ABI 的 libchlinux.so，返回落盘绝对路径。 */
    private fun extractFromApk(ctx: Context?, log: StringBuilder): String? {
        if (ctx == null) {
            log.append("③ 无 Context，跳过 APK 解包通路\n")
            return null
        }
        val ai = ctx.applicationInfo
        val apks = buildList {
            ai.sourceDir?.let { add(File(it)) }
            ai.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }
        // ABI 顺序：设备首选在前
        val abis = android.os.Build.SUPPORTED_ABIS.toList().ifEmpty { listOf("arm64-v8a") }
        val out = File(ctx.filesDir, "native/lib$LIB.so")
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (abi in abis) {
                    val entry = zip.getEntry("lib/$abi/lib$LIB.so") ?: continue
                    return try {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { o -> input.copyTo(o) }
                        }
                        out.absolutePath
                    } catch (t: Throwable) {
                        log.append("③ 从 ${apk.name} 抽 $abi 失败：").append(trace(t)).append('\n')
                        null
                    }
                }
            }
        }
        log.append("③ APK 里没有任何 lib/$LIB.so（sourceDir=${ai.sourceDir}，abis=$abis）\n")
        return null
    }

    /** 单行摘要，给终端页顶栏与 `linux` 工具的头部用。 */
    fun summary(): String = when {
        loaded -> "so=ok($via)"
        detail.isNotBlank() -> "so=失败"
        else -> "so=未加载"
    }

    /** 失败时给用户看的多行诊断（成功时返回空串）。 */
    fun failureText(): String = if (loaded) "" else buildString {
        append("[ch-native] lib").append(LIB).append(".so 加载失败，Linux 子系统无法启动。\n")
        append("设备 ABI：").append(android.os.Build.SUPPORTED_ABIS.joinToString(","))
        append("｜minSdk=26｜SDK=").append(android.os.Build.VERSION.SDK_INT).append('\n')
        append("nativeLibraryDir：").append(nativeLibDirSafe()).append('\n')
        append(detail.ifBlank { "（无记录）" })
    }

    private fun nativeLibDirSafe(): String = runCatching {
        val ctx = LinuxBridge.appContext()
        ctx?.applicationInfo?.nativeLibraryDir ?: "?"
    }.getOrDefault("?")

    /** 把异常连同 cause 链拼成一行——dlopen 的真实原因常常在 cause 里。 */
    private fun trace(t: Throwable): String = buildString {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 4) {
            if (depth > 0) append(" <- ")
            append(e.javaClass.simpleName).append(": ").append(e.message ?: "")
            e = e.cause
            depth++
        }
    }
}
