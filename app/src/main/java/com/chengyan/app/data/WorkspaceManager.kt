package com.chengyan.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 工作区：AI 文件操作的根目录。
 * 默认 = 应用内 filesDir/workspace；用户可在设置里选择手机任意目录（SAF 授权，重启仍有效）。
 */
object WorkspaceManager {

    private const val PREF = "workspace"
    private const val KEY_TREE = "tree_uri"

    /** 当前工作区展示名（app 内 / SAF 目录名）。 */
    fun displayName(ctx: Context): String {
        val uri = treeUri(ctx)
        return if (uri == null) "应用内工作区" else {
            DocumentFile.fromTreeUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "已选目录"
        }
    }

    fun treeUri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /** 选择 SAF 目录（选择器返回后调用；持久授权）。 */
    fun onPicked(ctx: Context, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { cr.takePersistableUriPermission(uri, flags) }
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).commit()
    }

    /** 恢复默认（应用内）。 */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    private fun appRoot(ctx: Context): File {
        val d = java.io.File(ctx.filesDir, "workspace")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun rootDoc(ctx: Context): DocumentFile {
        val uri = treeUri(ctx) ?: throw IllegalStateException("应用内工作区走文件 API")
        return DocumentFile.fromTreeUri(ctx, uri) ?: throw IllegalStateException("工作区授权已失效，请到设置重新选择")
    }

    // ---------- 统一文件操作（SAF 目录 / 应用内都可用） ----------

    fun list(ctx: Context, subPath: String): List<Triple<String, Boolean, Long>> {
        val rel = rel(subPath)
        return if (treeUri(ctx) == null) {
            val dir = java.io.File(appRoot(ctx), rel)
            val out = mutableListOf<Triple<String, Boolean, Long>>()
            dir.listFiles()?.forEach { f -> out.add(Triple(f.name ?: "", f.isDirectory, f.length())) }
            out
        } else {
            val dir = descend(ctx, rel)
            val out2 = mutableListOf<Triple<String, Boolean, Long>>()
            dir.listFiles().forEach { d -> out2.add(Triple(d.name ?: "", d.isDirectory, d.length())) }
            out2
        }
    }

    fun read(ctx: Context, subPath: String, maxChars: Int = 60_000): String {
        val rel = rel(subPath)
        return if (treeUri(ctx) == null) {
            val f = java.io.File(appRoot(ctx), rel)
            if (!f.isFile) throw IllegalStateException("文件不存在：$rel")
            f.readText(Charsets.UTF_8).let { if (it.length > maxChars) it.take(maxChars) + "\n…[截断]" else it }
        } else {
            val f = descend(ctx, rel)
            if (!f.isFile) throw IllegalStateException("文件不存在：$rel")
            ctx.contentResolver.openInputStream(f.uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val buf = StringBuilder(); val cbuf = CharArray(8192); var n = 0
                while (r.read(cbuf).also { n = it } > 0 && buf.length < maxChars) buf.append(cbuf, 0, n)
                if (buf.length >= maxChars) buf.append("\n…[截断]")
                buf.toString()
            } ?: throw IllegalStateException("无法打开：$rel")
        }
    }

    fun write(ctx: Context, subPath: String, content: String): String {
        val rel = rel(subPath)
        if (treeUri(ctx) == null) {
            val f = java.io.File(appRoot(ctx), rel)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            return "已写入 ${f.absolutePath}（${content.length} 字符）"
        }
        val parent = descend(ctx, rel.substringBeforeLast('/', ""))
        val name = rel.substringAfterLast('/')
        val existing = parent.findFile(name)
        val f = if (existing != null) existing else parent.createFile("text/plain", name)
            ?: throw IllegalStateException("创建失败（父目录不可写？）：$rel")
        ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("写入失败：$rel")
        return "已写入 $name（${content.length} 字符）"
    }

    fun mkdir(ctx: Context, subPath: String): String {
        val rel = rel(subPath)
        if (treeUri(ctx) == null) {
            val ok = java.io.File(appRoot(ctx), rel).mkdirs()
            return if (ok || java.io.File(appRoot(ctx), rel).isDirectory) "已创建目录 $rel" else "创建失败：$rel"
        }
        var cur = rootDoc(ctx)
        rel.split('/').filter { it.isNotBlank() }.forEach { seg ->
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: cur.createDirectory(seg)
                ?: throw IllegalStateException("创建目录失败：$seg")
        }
        return "已创建目录 $rel"
    }

    fun delete(ctx: Context, subPath: String): String {
        val rel = rel(subPath)
        if (treeUri(ctx) == null) {
            val f = java.io.File(appRoot(ctx), rel)
            val ok = f.deleteRecursively()
            return if (ok) "已删除 $rel" else "删除失败：$rel"
        }
        val f = descend(ctx, rel)
        return if (f.delete()) "已删除 $rel" else "删除失败：$rel"
    }

    /** 绝对路径提示（供 AI 说明；SAF 目录无绝对路径则给 display name）。 */
    fun describe(ctx: Context): String =
        if (treeUri(ctx) == null) appRoot(ctx).absolutePath else displayName(ctx)

    private fun rel(p: String): String {
        val r = p.trim().trim('/').replace("..", "").ifBlank { "" }
        return r
    }

    private fun descend(ctx: Context, rel: String): DocumentFile {
        var cur = rootDoc(ctx)
        rel.split('/').filter { it.isNotBlank() }.forEach { seg ->
            cur = cur.findFile(seg) ?: throw IllegalStateException("路径不存在：$rel")
        }
        return cur
    }
}
