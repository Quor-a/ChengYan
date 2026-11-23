package com.chengyan.app.ui.components

/*
 * 活动流 —— 语义完整移植自 Quor-a/ZorvAI 的 ActivityStream.kt / ActivityStreamView.kt / ActivityDetailSheet.kt：
 *   · 一轮回复里「思考 + 工具调用」逐条明细：汇总行（按类别计数：「思考、运行了 N 条命令、…」）
 *     + 每条一行（图标 + 一句话描述，失败红字，同一动作 ×N 折叠）
 *   · 点任意一条 → 底部弹层看完整细节（按类别分派：命令/文件/搜索/思考/通用）
 *   · 标题解析：display_intent → 按类别回退提取参数（命令/路径/关键词）→ 工具名
 * 视觉为澄言原创暖纸×墨×陶土橙。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.chengyan.app.ui.theme.FontCJK
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.ui.theme.*
import org.json.JSONObject

/* ---------- 类别（对齐 ZorvAI ActivityCategory，映射到澄言的 11 手机领域 + 通用工具） ---------- */

enum class ActivityCat(val unitLabel: String, val detailTitle: String) {
    THINK("思考", "思考"),
    COMMAND("条命令", "运行命令"),
    READ_FILE("个文件", "读取文件"),
    WRITE_FILE("个文件", "写入文件"),
    SEARCH("次文件", "搜索文件"),
    WEB("次网页", "网络能力"),
    PHONE("次手机", "操作手机"),
    CALENDAR("次日历", "读写日历"),
    ALARM("次提醒", "设置提醒"),
    DEVICE("次设备", "控制设备"),
    MEDIA("次媒体", "读取媒体"),
    APPS("次应用", "操作应用"),
    LOCATION("次定位", "获取定位"),
    TOOL("个工具", "调用工具"),
}

data class ActivityEntry(
    val category: ActivityCat,
    val title: String,
    val toolName: String = "",
    val args: String = "",
    val result: String? = null,
    val failed: Boolean = false,
    val durationMs: Long = 0,
) {
    val isThink: Boolean get() = category == ActivityCat.THINK
}

/** 工具名 → 类别（澄言 phone_* 领域工具 + 通用工具名匹配，ZorvAI ofTool 语义）。 */
fun activityCategoryOf(toolName: String): ActivityCat {
    val n = toolName.trim().lowercase().removePrefix("phone_")
    return when {
        n == "info" || n == "phone_info" -> ActivityCat.DEVICE
        n == "contacts" || n == "sms" || n == "calls" -> ActivityCat.PHONE
        n == "calendar" -> ActivityCat.CALENDAR
        n == "media" -> ActivityCat.MEDIA
        n == "location" -> ActivityCat.LOCATION
        n == "alarm" -> ActivityCat.ALARM
        n == "apps" -> ActivityCat.APPS
        n == "device" -> ActivityCat.DEVICE
        n == "files" -> ActivityCat.READ_FILE
        toolName.contains("command") || toolName.contains("shell") || toolName.contains("terminal") -> ActivityCat.COMMAND
        toolName.contains("web") || toolName.contains("search") || toolName.contains("url") -> ActivityCat.WEB
        toolName.contains("write") -> ActivityCat.WRITE_FILE
        toolName.contains("read") || toolName.contains("file") -> ActivityCat.READ_FILE
        toolName.contains("memory") -> ActivityCat.TOOL
        else -> ActivityCat.TOOL
    }
}

private val INTENT_KEYS = listOf("display_intent", "intent", "description", "summary", "purpose")

/** 标题解析（ZorvAI ActivityTitleResolver）：display_intent → 参数回退 → 类别名。 */
fun resolveActivityTitle(category: ActivityCat, toolName: String, args: String): String {
    val raw = args.trim()
    if (raw.startsWith("{")) {
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o != null) {
            for (k in INTENT_KEYS) {
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty() && v.length <= 200) return v
            }
            // 按类别回退取最有信息量的值
            fun s(vararg keys: String): String? = keys.firstOrNull { o.optString(it, "").trim().isNotEmpty() }?.let { o.optString(it).trim() }
            val v = when (category) {
                ActivityCat.COMMAND -> s("command", "cmd", "script", "code", "input")
                ActivityCat.WRITE_FILE, ActivityCat.READ_FILE -> s("path", "file_path", "filename", "file")
                ActivityCat.SEARCH, ActivityCat.WEB -> s("query", "keyword", "q", "pattern")
                ActivityCat.CALENDAR -> s("title", "event", "name")
                ActivityCat.ALARM -> s("label", "message", "hour")
                ActivityCat.APPS -> s("package", "app", "name")
                ActivityCat.MEDIA -> s("action", "query")
                ActivityCat.PHONE -> s("action", "contact", "address", "body")
                ActivityCat.DEVICE -> s("action", "setting")
                ActivityCat.LOCATION -> s("action")
                else -> null
            }
            if (v != null) {
                val short = if (v.length > 120) v.take(117) + "…" else v
                return when (category) {
                    ActivityCat.COMMAND -> "运行命令：$short"
                    ActivityCat.WRITE_FILE -> "写入 $short"
                    ActivityCat.READ_FILE -> "读取 $short"
                    ActivityCat.SEARCH, ActivityCat.WEB -> "搜索 $short"
                    else -> short
                }
            }
        }
    }
    return category.detailTitle
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    if (totalSec <= 0) return "0秒"
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return when { h > 0 -> "${h}小时 ${m}分钟"; m > 0 -> "${m}分钟 ${s}秒"; else -> "${s}秒" }
}

/** 结果状态判定（ZorvAI detectResultStatus：只扫前 200 字，避免误染红）。 */
fun detectResultFailed(result: String?): Boolean {
    if (result.isNullOrBlank()) return false
    val head = result.take(200)
    return result.startsWith("❌") || result.startsWith("✗") ||
        head.contains("失败") || head.contains("error", true) || head.startsWith("错误")
}

/** 汇总行文案（ZorvAI buildActivitySummary：类别计数 + 总耗时取 max）。 */
fun buildActivitySummary(entries: List<ActivityEntry>): String {
    val counts = LinkedHashMap<ActivityCat, Int>()
    entries.forEach { counts[it.category] = (counts[it.category] ?: 0) + 1 }
    if (counts.isEmpty()) return "处理了步骤"
    val parts = mutableListOf<String>()
    for (c in ActivityCat.entries) {
        val n = counts[c] ?: continue
        parts += when (c) {
            ActivityCat.THINK -> "思考"
            ActivityCat.COMMAND -> "运行了 $n 条命令"
            ActivityCat.READ_FILE -> "读取了 $n 个文件"
            ActivityCat.WRITE_FILE -> "写入了 $n 个文件"
            ActivityCat.SEARCH -> "搜索了 $n 次文件"
            ActivityCat.WEB -> "搜索了 $n 次网页"
            ActivityCat.PHONE -> "操作了 $n 次手机"
            ActivityCat.CALENDAR -> "读写了 $n 次日历"
            ActivityCat.ALARM -> "设置了 $n 次提醒"
            ActivityCat.DEVICE -> "控制了 $n 次设备"
            ActivityCat.MEDIA -> "读取了 $n 次媒体"
            ActivityCat.APPS -> "操作了 $n 次应用"
            ActivityCat.LOCATION -> "获取了 $n 次定位"
            ActivityCat.TOOL -> "调用了 $n 个工具"
        }
    }
    var out = parts.joinToString("、")
    val totalMs = entries.maxOfOrNull { it.durationMs } ?: 0L
    if (totalMs >= 1000) out += " · " + formatDurationMs(totalMs)
    return out
}

private fun activityIcon(c: ActivityCat) = when (c) {
    ActivityCat.THINK -> Icons.Rounded.AutoAwesome
    ActivityCat.COMMAND -> Icons.Rounded.Terminal
    ActivityCat.READ_FILE -> Icons.Rounded.Description
    ActivityCat.WRITE_FILE -> Icons.Rounded.EditNote
    ActivityCat.SEARCH -> Icons.Rounded.Search
    ActivityCat.WEB -> Icons.Rounded.Language
    ActivityCat.PHONE -> Icons.Rounded.Sms
    ActivityCat.CALENDAR -> Icons.Rounded.Event
    ActivityCat.ALARM -> Icons.Rounded.Alarm
    ActivityCat.DEVICE -> Icons.Rounded.Settings
    ActivityCat.MEDIA -> Icons.Rounded.Image
    ActivityCat.APPS -> Icons.Rounded.Apps
    ActivityCat.LOCATION -> Icons.Rounded.LocationOn
    ActivityCat.TOOL -> Icons.Rounded.Build
}

/* ---------- 活动流（汇总行 + 逐条，ZorvAI ActivityStream 同构） ---------- */

@Composable
fun ActivityStreamView(
    entries: List<ActivityEntry>,
    onOpenEntry: (ActivityEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    // 收起重置用条目数当 key（ZorvAI 教训：流式新 List 每帧都不同，不能当 remember key）
    var summaryOpen by remember(entries.size) { mutableStateOf(true) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable { summaryOpen = !summaryOpen }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildActivitySummary(entries),
                fontSize = 13.sp, color = Ink.copy(alpha = 0.86f),
                maxLines = if (summaryOpen) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            val chevronRot by animateFloatAsState(if (summaryOpen) 180f else 0f, tween(220), label = "chev")
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(14.dp).rotate(chevronRot), tint = Ink3)
        }
        val visible = if (summaryOpen) entries else entries.takeLast(3)
        AnimatedVisibility(
            visible = summaryOpen || entries.size <= 3,
            enter = fadeIn(tween(160)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            Column {
                visible.forEach { e -> ActivityRow(e) { onOpenEntry(e) } }
            }
        }
        if (!summaryOpen && entries.size > 3) {
            Text("还有 ${entries.size - 3} 条…", fontSize = 11.sp, color = Ink3,
                modifier = Modifier.clickable { summaryOpen = true }.padding(start = 24.dp, top = 2.dp))
        }
    }
}

@Composable
private fun ActivityRow(e: ActivityEntry, onClick: () -> Unit) {
    val tint = if (e.failed) Err else Ink.copy(alpha = 0.82f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(activityIcon(e.category), null, Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(9.dp))
        Text(e.title, fontSize = 13.sp, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (e.result.isNullOrBlank() && !e.isThink) {
            Spacer(Modifier.width(6.dp))
            Text("执行中", fontSize = 10.sp, color = Ink3)
        }
        Spacer(Modifier.weight(1f))
        Text("›", fontSize = 12.sp, color = Ink4)
    }
}

/* ---------- 详情弹层（ZorvAI ActivityDetailSheet 同构：按类别分派） ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailSheet(
    entry: ActivityEntry,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp).padding(bottom = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = if (entry.failed) Err else Ink,
                )
                TextButton(onClick = {
                    val detail = buildString {
                        appendLine(entry.title); appendLine()
                        appendLine("参数："); appendLine(prettifyArgs(entry.args))
                        if (!entry.result.isNullOrBlank()) { appendLine(); appendLine("结果："); appendLine(entry.result) }
                    }
                    clipboard.setText(AnnotatedString(detail))
                }) { Text("复制全部", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
            when (entry.category) {
                ActivityCat.THINK -> MonoBlock(entry.result.orEmpty().ifBlank { "这次思考没有留下文本内容。" }, 420)
                ActivityCat.COMMAND -> {
                    val o = runCatching { JSONObject(entry.args.ifBlank { "{}" }) }.getOrNull()
                    val cmd = o?.optString("command", null)?.takeIf { it.isNotBlank() }
                        ?: o?.optString("cmd", null)?.takeIf { it.isNotBlank() } ?: entry.args
                    StatusRow(entry)
                    Spacer(Modifier.height(8.dp))
                    MonoBlock("$ ${cmd.trim().ifEmpty { "（未记录命令）" }}", 120)
                    if (!entry.result.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); MonoBlock(entry.result!!, 260) }
                }
                else -> {
                    StatusRow(entry)
                    Spacer(Modifier.height(8.dp))
                    if (entry.args.isNotBlank()) {
                        Text("参数", fontSize = 12.sp, color = Ink4, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        MonoBlock(prettifyArgs(entry.args), 160)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("结果", fontSize = 12.sp, color = Ink4, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    MonoBlock(entry.result.orEmpty().ifBlank { "（执行中，尚无结果）" }, 260,
                        color = if (entry.failed) Err else null)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(entry: ActivityEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = when {
            entry.failed -> "失败" to Err
            entry.result.isNullOrBlank() -> "执行中" to Ink3
            else -> "完成" to Ok
        }
        Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
            Text(label, fontSize = 10.sp, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        if (entry.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatDurationMs(entry.durationMs), fontSize = 10.sp, color = Ink3)
        }
    }
}

/** 等宽文本块（可纵/横滚动，超长单行不裁尾）。 */
@Composable
private fun MonoBlock(text: String, maxHeight: Int, color: Color? = null) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = monoSafe(text),
            softWrap = false, color = color ?: Ink.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
    }
}

/** 参数 JSON 美化（键值对每行一个，ZorvAI FormattedResultContent 精简语义）。 */
fun prettifyArgs(args: String): String {
    val o = runCatching { JSONObject(args) }.getOrNull() ?: return args.ifBlank { "{}" }
    return buildString {
        appendLine("{")
        o.keys().forEach { k ->
            val v = o.optString(k, "")
            val shown = if (v.length > 120) v.take(117) + "…" else v
            appendLine("  \"$k\": $shown")
        }
        append("}")
    }
}
