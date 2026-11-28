package com.chengyan.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接器框架（对标 Dify builtin_tool provider 模型重做）：
 * 目录 = assets/connectors 目录下的 JSON 声明式清单（身份 + 凭据 schema + 工具清单），
 * 执行 = 通用 REST 引擎（路径/查询/体参数插值 + 鉴权 bearer/header/query/none）。
 * 新增连接器 = 放一个 JSON 清单，零代码。与 MCP（协议级）分家。
 */
object ConnectorManager {

    /** 清单（assets 声明）。 */
    data class Provider(
        val id: String,
        val name: String,
        val category: String,
        val desc: String,
        val auth: JSONObject,
        val credentials: List<CredField>,
        val headers: Map<String, String>,
        val tools: List<ToolSpec>,
    )

    data class CredField(val key: String, val label: String, val placeholder: String, val required: Boolean, val def: String)

    data class ToolSpec(
        val name: String,
        val desc: String,
        val method: String,
        val endpoint: String,          // /path/{p1}?x={q1} 模板
        val params: List<Param>,       // in: path|query|body
        val arrayPath: String,         // 可选：结果取此数组逐条格式化
        val fields: List<String>,      // 数组每条取这些字段成行
    )

    data class Param(val name: String, val `in`: String, val required: Boolean, val type: String, val desc: String, val def: String)

    /** 已保存的连接器实例（凭据 + 启用 + 逐工具开关）。 */
    data class Connector(
        val id: String,
        val creds: Map<String, String> = emptyMap(),
        val enabled: Boolean = false,
        val disabledTools: List<String> = emptyList(),
    )

    private const val PREF = "chengyan_connectors"

    // ---------- 目录加载（assets/connectors/*.json） ----------

    fun catalog(ctx: Context): List<Provider> = runCatching {
        val am = ctx.assets
        val list = mutableListOf<Provider>()
        for (f in am.list("connectors") ?: emptyArray()) {
            if (!f.endsWith(".json")) continue
            runCatching {
                val o = JSONObject(am.open("connectors/$f").bufferedReader(Charsets.UTF_8).use { it.readText() })
                list += parseProvider(o)
            }
        }
        list.sortedBy { it.category }
    }.getOrDefault(emptyList())

    private fun parseProvider(o: JSONObject): Provider = Provider(
        id = o.getString("id"), name = o.getString("name"),
        category = o.optString("category", "服务"), desc = o.optString("desc", ""),
        auth = o.optJSONObject("auth") ?: JSONObject().put("type", "none"),
        credentials = o.optJSONArray("credentials")?.let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                CredField(c.getString("key"), c.optString("label", c.getString("key")),
                    c.optString("placeholder", ""), c.optBoolean("required", false), c.optString("default", ""))
            }
        } ?: emptyList(),
        headers = o.optJSONObject("headers")?.let { h ->
            val m = mutableMapOf<String, String>(); h.keys().forEach { k -> m[k] = h.optString(k) }; m
        } ?: emptyMap(),
        tools = o.optJSONArray("tools")?.let { a ->
            (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                ToolSpec(
                    name = t.getString("name"), desc = t.optString("desc", ""),
                    method = t.optString("method", "GET").uppercase(),
                    endpoint = t.getString("endpoint"),
                    params = t.optJSONArray("params")?.let { pa ->
                        (0 until pa.length()).map { j ->
                            val p = pa.getJSONObject(j)
                            Param(p.getString("name"), p.optString("in", "query"),
                                p.optBoolean("required", false), p.optString("type", "string"),
                                p.optString("desc", ""), p.optString("default", ""))
                        }
                    } ?: emptyList(),
                    arrayPath = t.optString("array_path", ""),
                    fields = t.optJSONArray("fields")?.let { fa -> (0 until fa.length()).map { fa.optString(it) } } ?: emptyList(),
                )
            }
        } ?: emptyList(),
    )

    // ---------- 实例存储 ----------

    fun loadAll(ctx: Context): Map<String, Connector> = runCatching {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString("items", "[]") ?: "[]")
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val creds = runCatching {
                o.optJSONObject("creds")?.let { c ->
                    val m = mutableMapOf<String, String>(); c.keys().forEach { k -> m[k] = c.optString(k) }; m
                }
            }.getOrNull() ?: emptyMap()
            // 旧版单 key 迁移
            val finalCreds = if (creds.isEmpty() && o.optString("keyValue").isNotBlank()) mapOf("token" to o.optString("keyValue")) else creds
            o.optString("id") to Connector(o.optString("id"), finalCreds, o.optBoolean("enabled", false),
                disabledTools = runCatching { o.optJSONArray("disabledTools")?.let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrNull() ?: emptyList())
        }
    }.getOrDefault(emptyMap())

    private fun saveAll(ctx: Context, map: Map<String, Connector>) {
        val arr = JSONArray()
        map.values.forEach { c ->
            val cj = JSONObject(); c.creds.forEach { (k, v) -> cj.put(k, v) }
            arr.put(JSONObject().apply {
                put("id", c.id); put("creds", cj); put("enabled", c.enabled)
                put("disabledTools", JSONArray(c.disabledTools))
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    fun upsert(ctx: Context, c: Connector) = saveAll(ctx, loadAll(ctx) + (c.id to c))

    fun toggleTool(ctx: Context, connectorId: String, fullToolName: String, disabled: Boolean) {
        val c = loadAll(ctx)[connectorId] ?: return
        val set = c.disabledTools.toMutableSet()
        if (disabled) set.add(fullToolName) else set.remove(fullToolName)
        upsert(ctx, c.copy(disabledTools = set.toList()))
    }

    fun isReady(p: Provider, c: Connector?): Boolean =
        c != null && c.enabled && p.credentials.filter { it.required }.all { c.creds[it.key]?.isNotBlank() == true }

    // ---------- 并入 AI 工具表（仅已连接的 provider） ----------

    fun definitionsJson(ctx: Context): List<kotlinx.serialization.json.JsonObject> {
        val saved = loadAll(ctx)
        val defs = mutableListOf<kotlinx.serialization.json.JsonObject>()
        catalog(ctx).forEach { p ->
            if (!isReady(p, saved[p.id])) return@forEach
            val c = saved[p.id]!!
            p.tools.forEach { t ->
                val full = "conn_${p.id}_${t.name}"
                if (full in c.disabledTools) return@forEach
                defs += toolDef(full, t.desc, t.params)
            }
        }
        return defs
    }

    private fun toolDef(name: String, desc: String, params: List<Param>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("function"))
            put("function", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("description", kotlinx.serialization.json.JsonPrimitive(desc))
                put("parameters", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                    put("properties", kotlinx.serialization.json.buildJsonObject {
                        params.forEach { p ->
                            put(p.name, kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive(p.type))
                                if (p.desc.isNotBlank()) put("description", kotlinx.serialization.json.JsonPrimitive(p.desc))
                            })
                        }
                    })
                    val req = params.filter { it.required }.map { kotlinx.serialization.json.JsonPrimitive(it.name) }
                    if (req.isNotEmpty()) put("required", kotlinx.serialization.json.JsonArray(req))
                })
            })
        }

    // ---------- 通用执行引擎 ----------

    fun execute(ctx: Context, toolName: String, argumentsJson: String): String {
        val m = Regex("^conn_([a-z0-9]+)_([a-z0-9_]+)$").find(toolName)
            ?: return "错误：非法连接器工具名"
        val pid = m.groupValues[1]; val toolNameShort = m.groupValues[2]
        val saved = loadAll(ctx)[pid]
        val p = catalog(ctx).find { it.id == pid } ?: return "未知连接器：$pid"
        if (!isReady(p, saved)) return "错误：连接器「${p.name}」未连接（缺必填凭据或未启用）"
        val c = saved!!
        val tool = p.tools.find { it.name == toolNameShort } ?: return "未知工具：$toolNameShort"
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return runCatching { call(p, c, tool, args) }.getOrElse { "连接器调用失败：${it.message}" }
    }

    private fun call(p: Provider, c: Connector, t: ToolSpec, args: JSONObject): String {
        var url = (p.auth.optString("base", "").ifBlank { c.creds["api_base"]?.takeIf { it.isNotBlank() } ?: "" } + t.endpoint)
        // 1) path 插值（完整 URL 参数如 jina 的 {url} 不转义斜杠）
        for (pm in t.params.filter { it.`in` == "path" }) {
            val v = args.optString(pm.name).ifBlank { pm.def }
            val enc = if (v.startsWith("http://") || v.startsWith("https://")) v else java.net.URLEncoder.encode(v, "UTF-8")
            url = url.replace("{${pm.name}}", enc)
        }
        // 2) query 拼接
        val qs = StringBuilder()
        t.params.filter { it.`in` == "query" }.forEach { pm ->
            val v = args.optString(pm.name).ifBlank { pm.def }
            if (v.isNotBlank()) {
                if (qs.isNotEmpty()) qs.append('&')
                qs.append(java.net.URLEncoder.encode(pm.name, "UTF-8")).append('=')
                    .append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        // 鉴权 query 型（如高德 key=…）
        if (p.auth.optString("type") == "query") {
            val kv = p.auth.optString("param", "key")
            val secret = c.creds[p.auth.optString("cred", "token")] ?: ""
            if (secret.isNotBlank()) {
                if (qs.isNotEmpty()) qs.append('&')
                qs.append(kv).append('=').append(java.net.URLEncoder.encode(secret, "UTF-8"))
            }
        }
        if (qs.isNotEmpty()) url += (if (url.contains('?')) "&" else "?") + qs
        if (url.startsWith("http://").not() && !url.startsWith("https://")) url = "https://" + url.removePrefix("/")
        url = url.replace("https://https://", "https://")

        // 3) 头
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ChengYan/1.9")
        p.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        when (p.auth.optString("type")) {
            "bearer" -> conn.setRequestProperty(
                p.auth.optString("header", "Authorization"),
                (p.auth.optString("prefix") ) + (c.creds[p.auth.optString("cred", "token")] ?: ""))
            "header" -> conn.setRequestProperty(
                p.auth.optString("header", "X-Api-Key"),
                (p.auth.optString("prefix")) + (c.creds[p.auth.optString("cred", "token")] ?: ""))
        }
        conn.requestMethod = t.method
        // 4) body
        val bodyParams = t.params.filter { it.`in` == "body" }
        if (t.method != "GET" && bodyParams.isNotEmpty()) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
            bodyParams.forEach { bp ->
                val v = args.optString(bp.name).ifBlank { bp.def }
                if (v.isNotBlank()) body.put(bp.name, v)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) return "HTTP $code: ${text.take(300)}"

        return format(t, text)
    }

    /** 结果格式化：array_path+fields → 每行一条关键字段；否则原文截断。 */
    private fun format(t: ToolSpec, text: String): String {
        return runCatching {
            val trimmed = text.trimStart()
            var node: Any = if (trimmed.startsWith('[')) JSONArray(text) else JSONObject(text)
            if (t.arrayPath.isNotBlank()) {
                for (seg in t.arrayPath.split('.')) {
                    node = if (node is JSONObject) (node as JSONObject).opt(seg) ?: break else break
                }
            }
            if (t.fields.isNotEmpty() && node is JSONArray) {
                val arr = node as JSONArray
                val sb = StringBuilder("共 ${arr.length()} 条：\n")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val line = t.fields.joinToString("  ") { f -> "$f=${dotted(o, f)}" }
                    sb.append("${i + 1}. $line\n")
                }
                sb.toString().trimEnd()
            } else if (t.fields.isNotEmpty() && node is JSONObject) {
                t.fields.joinToString("\n") { f -> "$f=${dotted(node as JSONObject, f)}" }
            } else {
                text.take(3000)
            }
        }.getOrElse { text.take(3000) }
    }

    // 点路径取值：main.temp / commit.message / weather.0.description
    private fun dotted(o: JSONObject, path: String): String {
        var node: Any = o
        for (seg in path.split('.')) {
            if (seg.toIntOrNull() != null) node = (node as? JSONArray)?.opt(seg.toInt()) ?: return ""
            else node = (node as? JSONObject)?.opt(seg) ?: return ""
        }
        return node?.toString()?.take(120) ?: ""
    }
}
