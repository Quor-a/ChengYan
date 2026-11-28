package com.chengyan.app.net

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 连接器（对标 zorv AI「连接器」：授权后连接器的真实工具并入 AI 工具表）。
 *
 * 传输 = MCP Streamable HTTP（JSON-RPC 2.0 over POST）：
 *   initialize → notifications/initialized → tools/list →（调用期）tools/call
 * 响应可为 application/json 或 text/event-stream（data: 行），两者都解析。
 * 严格超时：连接 10s / 读取 30s，绝不挂 AI 工具链。
 */
object McpManager {

    data class Connector(
        val id: String,
        val name: String,
        val url: String,
        val headerName: String = "",   // 可选鉴权头（如 Authorization）
        val headerValue: String = "",
        val enabled: Boolean = true,
        val toolsJson: String = "[]",  // 上次 tools/list 缓存：[{name,description,schemaJson}]
        val lastError: String = "",
    )

    private const val PREF = "chengyan_mcp"
    private const val KEY = "connectors"
    private const val PROTOCOL = "2025-03-26"

    fun loadAll(ctx: Context): List<Connector> = runCatching {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Connector(
                id = o.optString("id"), name = o.optString("name"), url = o.optString("url"),
                headerName = o.optString("headerName"), headerValue = o.optString("headerValue"),
                enabled = o.optBoolean("enabled", true),
                toolsJson = o.optString("toolsJson", "[]"), lastError = o.optString("lastError"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveAll(ctx: Context, list: List<Connector>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("name", c.name); put("url", c.url)
                put("headerName", c.headerName); put("headerValue", c.headerValue)
                put("enabled", c.enabled); put("toolsJson", c.toolsJson); put("lastError", c.lastError)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, c: Connector) {
        saveAll(ctx, loadAll(ctx).filterNot { it.id == c.id } + c)
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, loadAll(ctx).filterNot { it.id == id })
    }

    // ---------- JSON-RPC over Streamable HTTP ----------

    private fun post(ctx: Context, c: Connector, body: JSONObject, sessionId: String?): Pair<Int, String> {
        val conn = (java.net.URL(c.url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL)
            if (sessionId != null) setRequestProperty("Mcp-Session-Id", sessionId)
            if (c.headerName.isNotBlank() && c.headerValue.isNotBlank()) setRequestProperty(c.headerName, c.headerValue)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val sid = conn.getHeaderField("Mcp-Session-Id")
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /** SSE（data: {...} 行）或纯 JSON → JSONRPC 响应对象。 */
    private fun parseResponse(raw: String): JSONObject? = runCatching {
        if (raw.trimStart().startsWith("{")) return JSONObject(raw)
        val lines = raw.lineSequence().filter { it.startsWith("data:") }.toList()
        for (line in lines.reversed()) {
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val o = JSONObject(payload)
            if (o.has("result") || o.has("error")) return o
        }
        null
    }.getOrNull()

    private fun rpc(ctx: Context, c: Connector, method: String, params: JSONObject?, sessionId: String?, id: Int): Pair<JSONObject?, String> {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id); put("method", method)
            if (params != null) put("params", params)
        }
        val (code, raw) = post(ctx, c, body, sessionId)
        if (code !in 200..299) return null to "HTTP $code: ${raw.take(120)}"
        val resp = parseResponse(raw) ?: return null to "响应解析失败（非 JSON-RPC）"
        if (resp.has("error")) return null to "RPC error: ${resp.optJSONObject("error")?.optString("message")?.take(120)}"
        return resp.optJSONObject("result") to ""
    }

    /** 连接并刷新工具列表；返回错误信息（空串=成功）。 */
    fun refresh(ctx: Context, c0: Connector): String {
        var sid: String? = null
        val (_, initRaw) = post(ctx, c0, JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", PROTOCOL)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().put("name", "chengyan").put("version", "1.9"))
            })
        }, null)
        runCatching {
            val o = parseResponse(initRaw)
            sid = o?.optString("mcp.sessionId", "")?.takeIf { it.isNotBlank() }
        }
        // notifications/initialized（无响应体）
        runCatching {
            post(ctx, c0, JSONObject().apply { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, sid)
        }
        val (tools, err) = rpc(ctx, c0, "tools/list", JSONObject(), sid, 2)
        if (tools == null) {
            upsert(ctx, c0.copy(lastError = err))
            return err
        }
        val arr = JSONArray()
        val tarr = tools.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until tarr.length()) {
            val t = tarr.getJSONObject(i)
            arr.put(JSONObject().apply {
                put("name", t.optString("name"))
                put("description", t.optString("description"))
                put("schemaJson", t.optJSONObject("inputSchema")?.toString() ?: "{}")
            })
        }
        upsert(ctx, c0.copy(toolsJson = arr.toString(), lastError = ""))
        return ""
    }

    private fun parseTools(c: Connector): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(c.toolsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.optString("name"), o.optString("description"), o.optString("schemaJson"))
        }
    }.getOrDefault(emptyList())

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** 并入 AI 工具表：工具名 mcp_{连接器名}_{工具名}，描述前缀标明连接器来源。 */
    fun definitionsJson(ctx: Context): List<kotlinx.serialization.json.JsonObject> {
        val defs = mutableListOf<kotlinx.serialization.json.JsonObject>()
        loadAll(ctx).filter { it.enabled && it.toolsJson != "[]" }.forEach { c ->
            parseTools(c).forEach { (name, desc, schemaJson) ->
                defs += kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("function"))
                    put("function", kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive("mcp_" + sanitize(c.name) + "_" + sanitize(name)))
                        put("description", kotlinx.serialization.json.JsonPrimitive("【连接器:${c.name}】" + desc.ifBlank { name }))
                        put("parameters", runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(schemaJson.ifBlank { "{}" })
                        }.getOrDefault(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }))
                    })
                }
            }
        }
        return defs
    }

    /** 执行连接器工具调用；返回给模型的文本结果。 */
    fun execute(ctx: Context, toolName: String, argumentsJson: String): String {
        val m = Regex("^mcp_([A-Za-z0-9]+)_([A-Za-z0-9_]+)$").find(toolName)
            ?: return "错误：非法连接器工具名 $toolName"
        val cname = m.groupValues[1]
        val tname = m.groupValues[2]
        val c = loadAll(ctx).firstOrNull { sanitize(it.name) == cname && it.enabled }
            ?: return "错误：连接器不存在或未启用"
        var sid: String? = null
        runCatching {
            val (_, initRaw) = post(ctx, c, JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", PROTOCOL); put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "chengyan").put("version", "1.9"))
                })
            }, null)
            sid = JSONObject(initRaw.takeIf { it.trimStart().startsWith("{") } ?: "{}")
                .optString("mcp.sessionId", "").takeIf { it.isNotBlank() }
        }
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val (result, err) = rpc(ctx, c, "tools/call", JSONObject().apply {
            put("name", tname); put("arguments", args)
        }, sid, 3)
        if (result == null) return "连接器调用失败：$err"
        val content = result.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.getJSONObject(i)
                when (item.optString("type")) {
                    "text" -> sb.append(item.optString("text"))
                    else -> sb.append("[${item.optString("type")} 内容]")
                }
                if (i < content.length() - 1) sb.append("\n")
            }
            return sb.toString().ifBlank { "（连接器返回空内容）" }
        }
        return result.toString().take(2000)
    }
}
