package com.chengyan.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接器框架 —— 连接第三方应用的账号/服务（填 key 后 AI 直接调用该应用的真实 API）。
 * 与 MCP（协议级服务器）并列、互不混用：连接器=应用级内置工具集，MCP=用户自接服务器。
 * 澄言原创目录：GitHub / 高德地图，均为真实 REST 调用，无 key 不启用不注册。
 */
object ConnectorManager {

    data class CatalogItem(
        val id: String,
        val name: String,
        val desc: String,
        val keyLabel: String,
        val tools: List<Triple<String, String, String>>, // (tool, 描述, 参数 JSON schema 字面量)
    )

    data class Connector(
        val id: String,
        val keyValue: String = "",
        val enabled: Boolean = false,
        val disabledTools: List<String> = emptyList(),
    )

    private const val PREF = "chengyan_connectors"

    /** 内置连接器目录。 */
    fun catalog(): List<CatalogItem> = listOf(
        CatalogItem(
            "github", "GitHub", "用 PAT 连接 GitHub：搜仓库/读文件/建 Issue，AI 直接操作你的 GitHub。",
            "Personal Access Token（需 repo 权限）",
            listOf(
                Triple("github_search_repos", "搜索 GitHub 仓库，返回名称/描述/star/地址", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索关键词\"},\"limit\":{\"type\":\"number\",\"description\":\"条数，默认5\"}},\"required\":[\"query\"]}"),
                Triple("github_get_file", "读取公开或已授权仓库某路径文件内容（base64 解码，截断 4000 字）", "{\"type\":\"object\",\"properties\":{\"owner\":{\"type\":\"string\"},\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"ref\":{\"type\":\"string\",\"description\":\"分支/commit，默认默认分支\"}},\"required\":[\"owner\",\"repo\",\"path\"]}"),
                Triple("github_create_issue", "在你的仓库创建 Issue", "{\"type\":\"object\",\"properties\":{\"owner\":{\"type\":\"string\"},\"repo\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"owner\",\"repo\",\"title\"]}"),
            ),
        ),
        CatalogItem(
            "amap", "高德地图", "用 Web 服务 key 连接高德：地理编码 / 实时天气，AI 可查位置与天气。",
            "Web 服务 API Key（lbs.amap.com 创建）",
            listOf(
                Triple("amap_geocode", "地址→经纬度与行政区划", "{\"type\":\"object\",\"properties\":{\"address\":{\"type\":\"string\",\"description\":\"如「北京市朝阳区望京SOHO\"}},\"required\":[\"address\"]}"),
                Triple("amap_weather", "中国城市实时天气（adcode 或城市名）", "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名或 adcode，如 110000 / 北京\"}},\"required\":[\"city\"]}"),
            ),
        ),
    )

    fun loadAll(ctx: Context): Map<String, Connector> = runCatching {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString("items", "[]") ?: "[]")
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            o.optString("id") to Connector(o.optString("id"), o.optString("keyValue"), o.optBoolean("enabled", false),
                disabledTools = runCatching { o.optJSONArray("disabledTools")?.let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrNull() ?: emptyList())
        }
    }.getOrDefault(emptyMap())

    private fun saveAll(ctx: Context, map: Map<String, Connector>) {
        val arr = JSONArray()
        map.values.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("keyValue", c.keyValue); put("enabled", c.enabled)
                put("disabledTools", JSONArray(c.disabledTools))
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    fun upsert(ctx: Context, c: Connector) = saveAll(ctx, loadAll(ctx) + (c.id to c))

    /** 切换单个工具是否暴露给 AI。 */
    fun toggleTool(ctx: Context, connectorId: String, fullToolName: String, disabled: Boolean) {
        val c = loadAll(ctx)[connectorId] ?: return
        val set = c.disabledTools.toMutableSet()
        if (disabled) set.add(fullToolName) else set.remove(fullToolName)
        upsert(ctx, c.copy(disabledTools = set.toList()))
    }

    // ---------- 并入 AI 工具表（仅已启用且有 key 的连接器） ----------

    private fun toolDef(name: String, desc: String, schema: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("function"))
            put("function", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("description", kotlinx.serialization.json.JsonPrimitive(desc))
                put("parameters", runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(schema)
                }.getOrDefault(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }))
            })
        }

    fun definitionsJson(ctx: Context): List<kotlinx.serialization.json.JsonObject> {
        val conns = loadAll(ctx)
        val defs = mutableListOf<kotlinx.serialization.json.JsonObject>()
        catalog().forEach { item ->
            val c = conns[item.id]
            if (c != null && c.enabled && c.keyValue.isNotBlank()) {
                item.tools.forEach { (tool, desc, schema) ->
                    val full = "conn_${item.id}_$tool"
                    if (full in c.disabledTools) return@forEach
                    defs += toolDef(full, desc, schema)
                }
            }
        }
        return defs
    }

    // ---------- 通用 HTTP ----------

    private fun httpJson(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ChengYan/1.9")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${body.take(160)}")
        return body
    }

    /** 执行连接器工具。 */
    fun execute(ctx: Context, toolName: String, argumentsJson: String): String {
        val m = Regex("^conn_([a-z]+)_([a-z_]+)$").find(toolName)
            ?: return "错误：非法连接器工具名"
        val cid = m.groupValues[1]
        val tool = m.groupValues[2]
        val c = loadAll(ctx)[cid]
        if (c == null || !c.enabled || c.keyValue.isBlank()) return "错误：连接器未启用或未填 key"
        val json = org.json.JSONTokener(argumentsJson.ifBlank { "{}" }).let { org.json.JSONObject(argumentsJson.ifBlank { "{}" }) }
        return runCatching {
            when (cid) {
                "github" -> github(tool, json, c.keyValue)
                "amap" -> amap(tool, json, c.keyValue)
                else -> "未知连接器：$cid"
            }
        }.getOrElse { "连接器调用失败：${it.message}" }
    }

    private fun github(tool: String, args: JSONObject, pat: String): String {
        val auth = mapOf("Authorization" to "token $pat")
        return when (tool) {
            "github_search_repos" -> {
                val q = java.net.URLEncoder.encode(args.optString("query"), "UTF-8")
                val limit = args.optInt("limit", 5).coerceIn(1, 20)
                val arr = JSONObject(httpJson("https://api.github.com/search/repositories?q=$q&per_page=$limit&sort=stars", auth)).optJSONArray("items") ?: JSONArray()
                val sb = StringBuilder("GitHub 搜索结果（${arr.length()} 条）：\n")
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    sb.append("${i + 1}. ${r.optString("full_name")} ★${r.optInt("stargazers_count")} — ${r.optString("description").take(120)}\n    ${r.optString("html_url")}\n")
                }
                sb.toString().trimEnd()
            }
            "github_get_file" -> {
                val ref = args.optString("ref").takeIf { it.isNotBlank() }?.let { "?ref=$it" } ?: ""
                val path = args.optString("path")
                val b64 = JSONObject(httpJson(
                    "https://api.github.com/repos/${args.optString("owner")}/${args.optString("repo")}/contents/$path$ref", auth))
                    .optString("content")
                if (b64.isBlank()) "未找到文件或无 content 字段"
                else String(android.util.Base64.decode(b64.replace("\n", ""), android.util.Base64.DEFAULT)).take(4000)
            }
            "github_create_issue" -> {
                val body = JSONObject().put("title", args.optString("title")).put("body", args.optString("body"))
                val conn = java.net.URL("https://api.github.com/repos/${args.optString("owner")}/${args.optString("repo")}/issues")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 10_000; conn.readTimeout = 30_000
                conn.setRequestProperty("Authorization", "token $pat")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val o = JSONObject(resp)
                "已创建 Issue #${o.optInt("number")}：${o.optString("title")}\n${o.optString("html_url")}"
            }
            else -> "未知 GitHub 工具：$tool"
        }
    }

    private fun amap(tool: String, args: JSONObject, key: String): String {
        return when (tool) {
            "amap_geocode" -> {
                val addr = java.net.URLEncoder.encode(args.optString("address"), "UTF-8")
                val o = JSONObject(httpJson("https://restapi.amap.com/v3/geocode/geo?address=$addr&key=$key"))
                val g = o.optJSONArray("geocodes")?.optJSONObject(0)
                if (g == null) "未找到该地址（${o.optString("info")}）"
                else "地址：${g.optString("formatted_address")}\n国家：${g.optString("country")} 省：${g.optString("province")} 市：${g.optString("city")} 区：${g.optString("district")}\n经纬度：${g.optString("location")}"
            }
            "amap_weather" -> {
                var city = args.optString("city")
                if (!city.matches(Regex("\\d{6}"))) {
                    val addr = java.net.URLEncoder.encode(city, "UTF-8")
                    val g = JSONObject(httpJson("https://restapi.amap.com/v3/geocode/geo?address=$addr&key=$key"))
                        .optJSONArray("geocodes")?.optJSONObject(0)
                    city = g?.optString("adcode") ?: ""
                    if (city.isBlank()) return "未识别城市"
                }
                val o = JSONObject(httpJson("https://restapi.amap.com/v3/weather/weatherInfo?city=$city&key=$key&extensions=base"))
                val w = o.optJSONArray("lives")?.optJSONObject(0)
                if (w == null) "查询失败（${o.optString("info")}）"
                else "${w.optString("city")} 实时天气：\n天气：${w.optString("weather")}  气温：${w.optString("temperature")}℃\n风向：${w.optString("winddirection")}  风力：${w.optString("windpower")}级  湿度：${w.optString("humidity")}%\n报告时间：${w.optString("reporttime")}"
            }
            else -> "未知高德工具：$tool"
        }
    }
}
