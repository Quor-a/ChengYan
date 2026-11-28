package com.chengyan.app.data

import org.json.JSONObject

/**
 * A2UI 渲染器（澄言实现子集，协议 a2ui.org v1.0）——
 * 模型输出 ```a2ui 围栏 JSON（单消息 createSurface 内嵌组件树），客户端渲染成原生 Compose 组件。
 *
 * 支持组件：Text/Row/Column/Card/Button/Image/Divider；
 * 动态值：{"@path": "/data/model/path"} 绑定 dataModel（JSON Pointer）；
 * Button.action {name, context} 点击后以用户消息回传给 AI 继续编排（renderer→agent action 的会话化实现）。
 */
object A2uiParser {

    private val BLOCK = Regex("```a2ui\\s*\\n([\\s\\S]*?)```")

    /** 一块 A2UI 画布：组件表 + 数据模型。 */
    data class Surface(
        val surfaceId: String,
        val components: Map<String, JSONObject>,
        val dataModel: JSONObject,
    )

    /** 文本 → (摘除 a2ui 围栏后的正文, 画布列表)。 */
    fun parse(text: String): Pair<String, List<Surface>> {
        val surfaces = mutableListOf<Surface>()
        BLOCK.findAll(text).forEach { m ->
            runCatching {
                val root = JSONObject(m.groupValues[1].trim())
                // v1.0 单消息：createSurface 内嵌 components/dataModel；宽容兼容顶层直接给 components
                val cs = root.optJSONObject("createSurface") ?: root
                val arr = cs.optJSONArray("components") ?: return@runCatching
                val map = LinkedHashMap<String, JSONObject>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val id = c.optString("id")
                    if (id.isNotBlank()) map[id] = c
                }
                if (map.containsKey("root")) {
                    surfaces += Surface(
                        surfaceId = cs.optString("surfaceId", "s" + (surfaces.size + 1)),
                        components = map,
                        dataModel = cs.optJSONObject("dataModel") ?: JSONObject(),
                    )
                }
            }
        }
        val cleaned = if (surfaces.isEmpty()) text else BLOCK.replace(text, "")
        return cleaned to surfaces
    }

    /** 动态值解析：{"@path": "/a/b"} → dataModel 取值；其余原样返回字符串。 */
    fun resolve(v: Any?, dm: JSONObject): String {
        val o = v as? JSONObject
        if (o != null && o.has("@path")) {
            var node: Any? = dm
            for (seg in o.optString("@path").trim('/').split('/')) {
                if (seg.isBlank()) continue
                node = (node as? JSONObject)?.opt(seg) ?: return ""
            }
            return node?.toString() ?: ""
        }
        return when (v) {
            null -> ""
            is JSONObject -> v.toString()
            else -> v.toString()
        }
    }
}
