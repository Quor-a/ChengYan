package com.chengyan.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.A2uiParser
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A2UI 画布渲染（Compose 原生）——组件树按 id 引用挂到 root，
 * 配色完全走澄言主题（协议 v1.0「Decoupled Branding」：不携带主题色）。
 */
@Composable
fun A2uiSurface(surface: A2uiParser.Surface, onAction: (label: String, action: org.json.JSONObject) -> Unit) {
    Surface_(surface, "root", onAction)
}

@Composable
private fun Surface_(s: A2uiParser.Surface, id: String, onAction: (String, org.json.JSONObject) -> Unit) {
    val c = s.components[id] ?: return
    val dm = s.dataModel
    when (c.optString("component")) {
        "Column" -> ColumnOf(s, c, dm, onAction)
        "Row" -> RowOf(s, c, dm, onAction)
        "Card" -> CardOf(s, c, dm, onAction)
        "Text" -> TextOf(c, dm)
        "Button" -> ButtonOf(c, dm, onAction)
        "Image" -> ImageOf(c, dm)
        "Divider" -> androidx.compose.material3.HorizontalDivider(color = Line2, modifier = Modifier.padding(vertical = 6.dp))
        else -> TextOf(c, dm) // 未知组件降级为文本（text/label/title 任一）
    }
}

private val ArrangementH: Map<String, Arrangement.Horizontal> = mapOf(
    "start" to Arrangement.Start, "center" to Arrangement.Center,
    "end" to Arrangement.End, "between" to Arrangement.SpaceBetween,
)

@Composable
private fun ColumnOf(s: A2uiParser.Surface, c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when (c.optString("align")) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        },
        verticalArrangement = Arrangement.spacedBy(c.optInt("gap", 8).dp),
    ) {
        ChildList(s, c, dm, onAction)
    }
}

@Composable
private fun RowOf(s: A2uiParser.Surface, c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = ArrangementH[c.optString("justify")] ?: Arrangement.Start,
        verticalAlignment = when (c.optString("align")) {
            "center" -> Alignment.CenterVertically
            "end" -> Alignment.Bottom
            else -> Alignment.Top
        },
    ) {
        ChildList(s, c, dm, onAction)
    }
}

@Composable
private fun ChildList(s: A2uiParser.Surface, c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    val children = c.optJSONArray("children") ?: c.optJSONArray("child")
    if (children != null) {
        for (i in 0 until children.length()) {
            val ref = children.optString(i)
            // child 直接内嵌对象（ChildList 也允许内联组件）
            val inline = children.optJSONObject(i)
            if (inline != null) {
                InlineOf(s, inline, dm, onAction)
            } else if (ref.isNotBlank()) {
                Surface_(s, ref, onAction)
            }
        }
    }
}

@Composable
private fun InlineOf(s: A2uiParser.Surface, c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    val cid = c.optString("id").ifBlank { "__inline__" + Integer.toHexString(c.hashCode()) }
    c.put("id", cid)
    val merged = java.util.LinkedHashMap(s.components); merged[cid] = c
    Surface_(s.copy(components = merged), cid, onAction)
}

@Composable
private fun CardOf(s: A2uiParser.Surface, c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = Paper2,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            val child = c.optString("child")
            if (child.isNotBlank()) Surface_(s, child, onAction)
        }
    }
}

@Composable
private fun TextOf(c: org.json.JSONObject, dm: org.json.JSONObject) {
    val raw = c.opt("text") ?: c.opt("label") ?: c.opt("title") ?: ""
    val text = A2uiParser.resolve(raw, dm)
    if (text.isBlank()) return
    val style = when (c.optString("variant", "body")) {
        "h1" -> MaterialTheme.typography.titleLarge
        "h2" -> MaterialTheme.typography.titleMedium
        "h3" -> MaterialTheme.typography.titleSmall
        "caption" -> MaterialTheme.typography.labelSmall
        else -> MaterialTheme.typography.bodyMedium
    }
    val weight = when (c.optString("weight", "")) {
        "bold" -> FontWeight.Bold
        "semibold" -> FontWeight.SemiBold
        "light" -> FontWeight.Light
        else -> null
    }
    Text(text, style = style, fontWeight = weight ?: if (c.optString("variant").startsWith("h")) FontWeight.SemiBold else null,
        color = Ink, lineHeight = 22.sp)
}

@Composable
private fun ButtonOf(c: org.json.JSONObject, dm: org.json.JSONObject, onAction: (String, org.json.JSONObject) -> Unit) {
    val label = A2uiParser.resolve(c.opt("label") ?: c.opt("text"), dm).ifBlank { "操作" }
    val action = c.optJSONObject("action") ?: org.json.JSONObject()
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(10.dp),
        color = AccentInk,
        modifier = Modifier.padding(vertical = 4.dp).clickable { onAction(label, action) },
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Paper,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun ImageOf(c: org.json.JSONObject, dm: org.json.JSONObject) {
    val url = A2uiParser.resolve(c.opt("url") ?: c.opt("src"), dm)
    if (url.isBlank()) return
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        failed = false
        runCatching {
            bmp = withContext(Dispatchers.IO) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "ChengYan/1.9")
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / sample > 1024) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.onFailure { failed = true }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "A2UI 图片",
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
    } else if (failed) {
        Text("图片加载失败", fontSize = 11.sp, color = Ink4)
    } else {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Paper3, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { Text("加载中…", fontSize = 11.sp, color = Ink4) }
    }
}
