package com.chengyan.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 附件读取与持久化 —— 支持图片 / 视频 / 文档 / 音频。
 *
 * 策略：
 * - 图片：压缩到 ≤1024px 的 JPEG base64（多模态模型直接可读）。
 * - 文档/文本：UTF-8 读取，超过上限截取，作为 text 内联给模型。
 * - 视频/二进制：复制到 App 私有 filesDir/attachments 持久化（不塞进 JSON），
 *   保存 URI 供 UI 播放/打开；模型侧给「附件名+大小+类型」说明。
 */
object AttachmentStore {

    private const val MAX_TEXT_BYTES = 120_000
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: "attachment"

    fun mimeOf(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 按 MIME 判定附件类型。 */
    fun typeOf(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "file"
    }

    /**
     * 从 URI 构造附件。
     * - 图片 → base64（走多模态视觉）
     * - 文本/文档 → text 内联
     * - 视频/音频/二进制 → 复制到私有目录持久化，仅存 uri
     */
    fun fromUri(context: Context, uri: Uri): Attachment? = runCatching {
        val name = displayName(context, uri)
        val mime = mimeOf(context, uri)
        val type = typeOf(mime)
        val size = sizeOf(context, uri)
        when (type) {
            "image" -> {
                val b64 = readImageAsBase64(context, uri, MAX_IMAGE_BYTES)
                    ?: return@runCatching null
                Attachment("image", name, mime, base64 = b64, size = size)
            }
            "file" -> {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                val slice = if (bytes.size > MAX_TEXT_BYTES) bytes.copyOfRange(0, MAX_TEXT_BYTES) else bytes
                val txt = slice.toString(Charsets.UTF_8)
                val printable = txt.count { it.isLetterOrDigit() || it.isWhitespace() || ".,:;!?()[]{}<>=+-*/|~'@#$%^&_\n\r\t".contains(it) }.toFloat() / txt.length.coerceAtLeast(1)
                if (printable > 0.85f) {
                    val truncated = bytes.size > MAX_TEXT_BYTES
                    Attachment("file", name, mime, size = size,
                        text = "【文档：$name${if (truncated) "（内容过长，已截取前 ${MAX_TEXT_BYTES / 1024}KB）" else ""}】\n```\n$txt\n```")
                } else {
                    // 二进制文档（PDF/zip 等）：持久化文件，模型只见到说明
                    val saved = persist(context, uri, name)
                    Attachment("file", name, mime, uri = saved, size = size,
                        text = "【文档附件：$name（${size / 1024}KB，$mime，二进制内容无法直接读取）】")
                }
            }
            "video", "audio" -> {
                val saved = persist(context, uri, name)
                Attachment(type, name, mime, uri = saved, size = size,
                    text = "【${if (type == "video") "视频" else "音频"}附件：$name（${size / 1024}KB，$mime）】")
            }
            else -> null
        }
    }.getOrNull()

    /** 复制 URI 内容到 App 私有 attachments 目录，返回 file:// URI 字符串。 */
    private fun persist(context: Context, uri: Uri, name: String): String? = runCatching {
        val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val safe = name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(80)
        val dst = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        android.net.Uri.fromFile(dst).toString()
    }.getOrNull()

    /** 图片压缩为 base64（无 data URL 前缀）。 */
    fun readImageAsBase64(context: Context, uri: Uri, maxBytes: Int = MAX_IMAGE_BYTES): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        if (out.size() > maxBytes) return@runCatching null
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    fun decodeBase64Bitmap(base64: String): android.graphics.Bitmap = runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrDefault(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
}
