package com.chengyan.app.data

/**
 * UI 组件卡片协议（澄言原创）—— 模型在回复里输出围栏块，客户端解析成富卡片渲染。
 *
 * 语法（markdown 围栏）：
 *   ```ui-card
 *   {"type":"note","title":"标题","body":"正文","tone":"accent"}
 *   ```
 *
 * 记忆铁律：可视化小卡片 = ui_widget/ui_card 富卡片（本协议），
 * 不是 visual_popup / visual_question / ui_control。
 */
object UiCardParser {

    private const val FENCE = "ui-card"
    private val BLOCK = Regex("```$FENCE\\s*\\n([\\s\\S]*?)```")

    /** 卡片类型。 */
    enum class Kind { NOTE, LIST, KV, ACTION, CODE, STAT, QUOTE, TIMELINE, UNKNOWN }

    /** 列表/时间线条目：支持对象形式 {title, desc}。 */
    data class Item(val title: String, val desc: String = "")

    data class Card(
        val kind: Kind,
        val title: String = "",
        val body: String = "",
        val items: List<String> = emptyList(),
        val itemsDetailed: List<Item> = emptyList(),
        val kv: Map<String, String> = emptyMap(),
        val tone: String = "",
        val actions: List<String> = emptyList(),
        val author: String = "",
        val raw: String = "",
    )

    /** 一段文本 → (纯文本, 卡片列表)：卡片围栏从文本里摘除，避免重复显示 JSON。 */
    fun parse(text: String): Pair<String, List<Card>> {
        val cards = mutableListOf<Card>()
        var cleaned = text
        BLOCK.findAll(text).forEach { m ->
            val card = parseOne(m.groupValues[1].trim())
            if (card != null) cards.add(card)
        }
        cleaned = BLOCK.replace(text, "")
        return cleaned to cards
    }

    private fun parseOne(json: String): Card? = runCatching {
        val el = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
            ?: return@runCatching null
        fun s(key: String) = (el[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        fun strList(key: String) = (el[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
        fun objList(key: String): List<Item> = (el[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { o ->
                (o as? kotlinx.serialization.json.JsonObject)?.let {
                    fun ss(k: String) = (it[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    Item(ss("title").ifBlank { ss("text") }, ss("desc").ifBlank { ss("content") })
                }
            }
            ?: emptyList()
        fun kvMap(key: String): Map<String, String> {
            val o = el[key] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            return o.entries.associate { (k, v) -> k to ((v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()) }
        }
        when (s("type").lowercase()) {
            "note" -> Card(Kind.NOTE, s("title"), s("body"), tone = s("tone"), raw = json)
            "list" -> {
                val detailed = objList("items")
                Card(Kind.LIST, s("title"), items = if (detailed.isEmpty()) strList("items") else emptyList(),
                    itemsDetailed = detailed, tone = s("tone"), raw = json)
            }
            "kv", "table" -> Card(Kind.KV, s("title"), kv = kvMap("data"), tone = s("tone"), raw = json)
            "stat" -> Card(Kind.STAT, s("title"), kv = kvMap("data"), tone = s("tone"), raw = json)
            "action" -> Card(Kind.ACTION, s("title"), s("body"), actions = strList("actions"), tone = s("tone"), raw = json)
            "code" -> Card(Kind.CODE, s("title"), s("body"), tone = s("lang"), raw = json)
            "quote" -> Card(Kind.QUOTE, s("title"), s("body"), author = s("author"), tone = s("tone"), raw = json)
            "timeline" -> Card(Kind.TIMELINE, s("title"), itemsDetailed = objList("steps").ifEmpty { objList("items") }, tone = s("tone"), raw = json)
            else -> Card(Kind.UNKNOWN, s("title"), s("body"), raw = json)
        }
    }.getOrNull()
}
