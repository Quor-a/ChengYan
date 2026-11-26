package com.chengyan.app.data

import java.io.File

/**
 * Alpine aarch64 rootfs 安装器：assets/rootfs.tar.gz 首启解包到 filesDir/chlinux/rootfs。
 * native 侧 start(dataDir) 直接以 <dataDir>/rootfs 为 guest 根（openat 透传），无需内核层改动。
 * tar 解析为最小实现：GZIPInputStream + 512 字节头（支持 typeflag '0'/'5' 与 GNU 'L' 长名）。
 */
object Rootfs {
    private const val VERSION = "alpine-3.20-mini-1"

    fun ensure(ctx: android.content.Context): Boolean {
        val root = File(ctx.filesDir, "chlinux/rootfs")
        val marker = File(root, ".rc")
        // 已安装：只做幂等补丁（不删用户数据）。缺 /bin/busybox 的老包就地补齐。
        if (marker.exists() && marker.readText().trim() == VERSION) {
            patchBusybox(root)
            return true
        }
        try {
            root.deleteRecursively()
            root.mkdirs()
            // AGP 8.x 会把 assets 里的 .gz 解压后改名打包(rootfs.tar.gz → assets/rootfs.tar)，
            // 所以按实际存在的名字取，并按魔数自动识别是否 gzip（Pushback 回退字节）。
            val (stream, isGzip) = sequenceOf("rootfs.tar.gz", "rootfs.tar").mapNotNull { name ->
                try {
                    val s = java.io.PushbackInputStream(ctx.assets.open(name), 2)
                    val magic = ByteArray(2)
                    val n = s.read(magic)
                    val gz = n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
                    if (!gz) s.unread(magic, 0, n)
                    s to gz
                } catch (e: Exception) { null }
            }.firstOrNull() ?: return false
            val base = if (isGzip) java.util.zip.GZIPInputStream(stream) else stream
            base.use { raw ->
                java.io.DataInputStream(raw).use { tar ->
                    val hdr = ByteArray(512)
                    var pendingLong: String? = null
                    while (true) {
                        var off = 0
                        while (off < 512) {
                            val n = tar.read(hdr, off, 512 - off)
                            if (n < 0) return finish(root, off == 0)
                            off += n
                        }
                        if (hdr.all { it.toInt() == 0 }) continue // 两个全零块 = 尾部
                        val name = pendingLong ?: str(hdr, 0, 100)
                        pendingLong = null
                        if (name.isEmpty()) continue
                        var sz = 0L
                        for (i in 124 until 136) {
                            val c = hdr[i].toInt()
                            if (c == 0 || c == ' '.code) break
                            sz = sz * 8 + (c - '0'.code)
                        }
                        val typeflag = hdr[156].toInt()
                        when (typeflag) {
                            'L'.code -> { // GNU 长文件名
                                val buf = ByteArray(sz.toInt())
                                tar.readFully(buf)
                                val z = buf.indexOf(0)
                                pendingLong = String(buf, 0,
                                    if (z >= 0) z else buf.size, Charsets.UTF_8)
                                if (sz % 512 != 0L) tar.skip(512 - (sz % 512))
                                continue
                            }
                            '5'.code -> File(root, name).mkdirs()
                            '2'.code -> { // symlink：GNU tar 目标在头 157-257 字节，body 通常为空
                                val target = str(hdr, 157, 100)
                                if (sz > 0) { tar.readFully(ByteArray(sz.toInt()))
                                    if (sz % 512 != 0L) tar.skip(512 - (sz % 512)) }
                                val f = File(root, name)
                                f.parentFile?.mkdirs()
                                try {
                                    if (f.exists()) f.delete()
                                    java.nio.file.Files.createSymbolicLink(
                                        java.nio.file.Path.of(f.absolutePath),
                                        java.nio.file.Path.of(target))
                                } catch (e: Exception) { /* 降级：软链不可用则留占位 */ f.createNewFile() }
                            }
                            '1'.code -> { // hardlink：按链接目标复制内容
                                val linkname = str(hdr, 102, 100)
                                val src = File(root, linkname)
                                val f = File(root, name)
                                f.parentFile?.mkdirs()
                                try { src.copyTo(f, overwrite = true) } catch (e: Exception) {}
                            }
                            '0'.code, 0 -> {
                                val f = File(root, name)
                                f.parentFile?.mkdirs()
                                f.outputStream().use { out ->
                                    var left = sz
                                    val buf = ByteArray(65536)
                                    while (left > 0) {
                                        val n = tar.read(buf, 0,
                                            minOf(buf.size.toLong(), left).toInt())
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        left -= n
                                    }
                                }
                            }
                            else -> tar.skip(sz)
                        }
                        if (sz % 512 != 0L) tar.skip(512 - (sz % 512))
                    }
                    return finish(root, false)
                }
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 幂等补丁：根包里 /bin/sh、/bin/ls 等 334 个 applet 全是符号链接 → /bin/busybox，
     * 但包内只带了 bin/busybox.static（改名遗留），这些链接**全部悬空**——任何走
     * PATH 或 #!/bin/sh 的路径都会 ENOENT。Android 应用私有目录不允许建软链，
     * 所以直接复制一份 /bin/busybox（1.1MB）。已存在则跳过。
     */
    private fun patchBusybox(root: File) {
        val bbStatic = root.resolve("bin/busybox.static")
        val bb = root.resolve("bin/busybox")
        if (!bbStatic.isFile || bbStatic.length() <= 0L) return
        if (bb.isFile && bb.length() > 0L) return
        try {
            bbStatic.copyTo(bb, overwrite = true)
            bb.setExecutable(true)
        } catch (e: Exception) { /* 不阻断：host 仍以 busybox.static 直接跑 applet */ }
    }

    private fun finish(root: File, cleanEof: Boolean): Boolean {
        if (!cleanEof) return false
        root.resolve("tmp").mkdirs()
        patchBusybox(root)
        root.resolve(".rc").writeText(VERSION)
        return true
    }

    private fun str(hdr: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && hdr[end].toInt() != 0) end++
        return String(hdr, off, end - off, Charsets.UTF_8)
    }
}
