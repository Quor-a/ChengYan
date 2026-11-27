package com.chengyan.app.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * web_search：设备端免密钥网页搜索（AI function calling）。
 *
 * 多源回退链（实测结论 2026-11-27，国内网络）：
 *  1. Bing HTML（html 可达、中文质量好；en 查询走 ensearch 参数减轻本地化污染）
 *  2. DuckDuckGo Lite（国际网络可达，国内常被墙；可达时质量更好）
 * 哪个先解析出 ≥2 条结果用哪个。超时严格：连接 10s / 读取 20s，绝不挂 AI 工具链。
 */
object WebSearch {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun search(query: String, count: Int): String {
        val q = query.trim()
        if (q.isEmpty()) return "错误：query 不能为空"
        val n = count.coerceIn(1, 10)
        val market = if (q.count { it.code < 128 } * 10 > q.length * 8) "en-US" else "zh-CN"

        val errors = StringBuilder()
        for (source in listOf("bing", "ddg")) {
            try {
                val results = when (source) {
                    "bing" -> bing(q, market, n)
                    else -> ddgLite(q, n)
                }
                if (results.size >= 2) return format(results, source)
                errors.append("$source 仅 ${results.size} 条；")
            } catch (t: Throwable) {
                errors.append("$source: ${t.javaClass.simpleName} ${t.message ?: ""}；")
            }
        }
        return "搜索失败（$errors）"
    }

    private fun format(results: List<Triple<String, String, String>>, source: String): String {
        val sb = StringBuilder("搜索结果（${if (source == "bing") "Bing" else "DuckDuckGo"}，${results.size} 条）：\n")
        results.forEachIndexed { i, (t, u, s) ->
            sb.append("${i + 1}. $t\n    $u\n    $s\n")
        }
        return sb.toString().trimEnd()
    }

    // ---------- 通用 HTTP ----------

    private fun httpGet(urlStr: String, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let {
            val raw = if (conn.contentEncoding == "gzip") GZIPInputStream(it) else it
            String(raw.readBytes(), Charsets.UTF_8)
        } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return body
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&nbsp;", " ")

    private fun stripTags(s: String): String =
        unescapeHtml(s.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "…"

    // ---------- Bing HTML ----------

    private fun bing(q: String, market: String, n: Int): List<Triple<String, String, String>> {
        val url = buildString {
            append("https://www.bing.com/search?q=")
            append(URLEncoder.encode(q, "UTF-8"))
            append("&count=").append(n + 2)
            append("&setmkt=").append(market)
            if (market == "en-US") append("&ensearch=1")
        }
        val lang = if (market == "en-US") "en-US,en;q=0.9" else "zh-CN,zh;q=0.9,en;q=0.8"
        val html = httpGet(url, headers = mapOf("Accept-Language" to lang))
        val out = mutableListOf<Triple<String, String, String>>()
        val seen = mutableSetOf<String>()
        for (block in Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.value }) {
            val uMatch = Regex("<a[^>]*href=\"(https?://[^\"]+)\"").find(block) ?: continue
            val tMatch = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).find(block) ?: continue
            val pMatch = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(block)
            var url = uMatch.groupValues[1]
            // bing 重定向 u=a1<base64url> → 解码真实地址
            Regex("[?&]u=a1([^&]+)").find(url)?.let { m ->
                runCatching {
                    val b64 = m.groupValues[1].padEnd(m.groupValues[1].length + (4 - m.groupValues[1].length % 4) % 4, '=')
                    val dec = String(java.util.Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
                    if (dec.startsWith("http")) url = dec
                }
            }
            val title = stripTags(tMatch.groupValues[1])
            if (title.isEmpty() || title.equals("index", true) || title.equals("more results", true)) continue
            if (!seen.add(url)) continue
            val snippet = pMatch?.let { truncate(stripTags(it.groupValues[1]), 200) } ?: ""
            out.add(Triple(truncate(title, 110), url, snippet))
            if (out.size >= n) break
        }
        return out
    }

    // ---------- DuckDuckGo Lite（国际网络） ----------

    private fun ddgLite(q: String, n: Int): List<Triple<String, String, String>> {
        val url = "https://lite.duckduckgo.com/lite?q=" + URLEncoder.encode(q, "UTF-8")
        val html = httpGet(url, headers = mapOf("Accept-Language" to "en-US,en;q=0.9"))
        val out = mutableListOf<Triple<String, String, String>>()
        val seen = mutableSetOf<String>()
        // lite 版结构：<a rel="nofollow" href="URL" class='result-link'>TITLE</a> … <td class='result-snippet'>SNIP</td>
        val linkRe = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        val snips = snipRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        var i = 0
        for (m in linkRe.findAll(html)) {
            var url = m.groupValues[1]
            Regex("[?&]uddg=([^&]+)").find(url)?.let { mm ->
                runCatching { url = URLDecoder.decode(mm.groupValues[1], "UTF-8") }
            }
            if (!url.startsWith("http")) continue
            val title = stripTags(m.groupValues[2])
            if (!seen.add(url)) continue
            val snippet = snips.getOrNull(i) ?: ""
            out.add(Triple(truncate(title, 110), url, truncate(snippet, 200)))
            i++
            if (out.size >= n) break
        }
        return out
    }
}
