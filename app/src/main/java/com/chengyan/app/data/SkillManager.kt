package com.chengyan.app.data

import android.content.Context
import java.io.File

/**
 * Skills 完整架构：每个 skill = filesDir/skills/<id>/SKILL.md（frontmatter name/desc + 正文）。
 * 启用的 skill 内容注入系统提示词，AI 按其规程执行对应类型任务；目录里还可放 scripts/ 由终端直接运行。
 */
object SkillManager {

    data class Skill(
        val id: String,
        val name: String,
        val desc: String,
        val body: String,
        val enabled: Boolean,
        val hasScripts: Boolean,
    )

    private const val PREF = "skills"

    fun root(ctx: Context) = File(ctx.filesDir, "skills").apply { mkdirs() }

    fun list(ctx: Context): List<Skill> {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            val md = File(d, "SKILL.md")
            if (!md.isFile) return@mapNotNull null
            val text = runCatching { md.readText(Charsets.UTF_8) }.getOrDefault("")
            val (front, body) = splitFront(text)
            Skill(
                id = d.name,
                name = front["name"] ?: d.name,
                desc = front["desc"] ?: "",
                body = body,
                enabled = prefs.getBoolean("en_" + d.name, true),
                hasScripts = File(d, "scripts").isDirectory && File(d, "scripts").listFiles().orEmpty().isNotEmpty(),
            )
        }?.sortedBy { it.name } ?: emptyList()
    }

    fun get(ctx: Context, id: String): Skill? = list(ctx).find { it.id == id }

    /** 新建/覆盖：id 即目录名。返回错误信息，null=成功。 */
    fun save(ctx: Context, id: String, name: String, desc: String, body: String): String? {
        val sid = id.trim().replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]"), "-").take(48)
        if (sid.isBlank()) return "名称无效"
        val d = File(root(ctx), sid)
        if (!d.isDirectory) d.mkdirs()
        File(d, "SKILL.md").writeText(
            "---\nname: $name\ndesc: $desc\n---\n\n$body\n", Charsets.UTF_8
        )
        return null
    }

    fun delete(ctx: Context, id: String): Boolean = File(root(ctx), id).deleteRecursively()

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("en_$id", on).apply()
    }

    /** 已启用 skills 的注入文本（系统提示词用；总长上限，超出按顺序截断）。 */
    fun promptInjection(ctx: Context, budget: Int = 6000): String {
        val on = list(ctx).filter { it.enabled }
        if (on.isEmpty()) return ""
        val sb = StringBuilder("【Skills 工作规程】以下是用户配置的技能规程，匹配当前任务时严格照做：\n")
        for (s in on) {
            val block = "\n## ${s.name}" + (if (s.desc.isNotBlank()) " — ${s.desc}" else "") + "\n" + s.body.trim() + "\n"
            if (sb.length + block.length > budget) { sb.append("\n（更多技能超出预算未注入）"); break }
            sb.append(block)
        }
        return sb.toString()
    }

    fun scriptDir(ctx: Context, id: String): File = File(File(root(ctx), id), "scripts")

    private fun splitFront(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to text
        val front = text.substring(3, end).trim().lines().associate { l ->
            val i = l.indexOf(':')
            if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else "" to l
        }.filterKeys { it.isNotBlank() }
        return front to text.substring(end + 4)
    }
}
