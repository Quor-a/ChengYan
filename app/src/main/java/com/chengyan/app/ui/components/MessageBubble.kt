package com.chengyan.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.Message
import com.chengyan.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun MessageBubble(
    message: Message,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            AiAvatar(Modifier.padding(top = 26.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 312.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isUser) "你" else "澄言", style = MaterialTheme.typography.labelSmall, color = Ink2)
                if (!isUser && message.model != null)
                    Text(message.model, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Ink3, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(timeFmt.format(Date(message.time)), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Ink4, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 18.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 18.dp,
                    bottomStart = 18.dp, bottomEnd = 18.dp
                ),
                color = if (message.isError) Color(0xFFFDF0EC) else Card,
                border = BorderStroke(1.dp, if (message.isError) Color(0xFFE0B3A6) else Line),
                shadowElevation = 1.dp
            ) {
            if (!message.isError) {
                if (!isUser) {
                    if (message.thinking.isNotBlank())
                        ThinkingCard(message.thinking, message.thinkingMs, message.streaming && message.content.isBlank())
                    message.toolCalls.forEach { ToolCallCard(it) }
                }
                if (message.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        message.images.forEach { b64 -> ImageThumb(b64) }
                    }
                }
                if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                    )
                } else {
                    Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                        MarkdownText(message.content)
                        if (message.streaming) StreamingCursor()
                    }
                }
            } else {
                Column(Modifier.padding(14.dp)) {
                    Text(message.content, style = MaterialTheme.typography.bodySmall, color = Err)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                        Text("重试", color = AccentInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (!message.streaming) {
                Row(Modifier.padding(top = 4.dp)) {
                    ActionChip(Icons.Rounded.ContentCopy, "复制") {
                        onCopy()
                    }
                    if (isUser) ActionChip(Icons.Rounded.Edit, "编辑", onEdit)
                    if (!isUser) ActionChip(Icons.Rounded.Refresh, "重新生成", onRegenerate)
                    ActionChip(Icons.Rounded.DeleteOutline, "删除", onDelete)
                }
            }
        }
        }
        if (isUser) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.padding(top = 26.dp).size(30.dp)
                    .background(Paper3, RoundedCornerShape(9.dp))
                    .border(1.dp, Line2, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("你", style = MaterialTheme.typography.labelSmall, color = Ink2)
            }
        }
    }
}

@Composable
private fun ThinkingCard(text: String, ms: Long, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Paper2,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (active) {
                    Box(Modifier.size(8.dp).background(Accent, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (active) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) AccentInk else Ink3,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                if (!active && ms > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("· ${ms / 1000}s", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Ink4, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = Ink3)
            }
            if (expanded) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ToolCallCard(call: com.chengyan.app.data.ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    val label = mapOf(
        "get_current_time" to "查询当前时间",
        "calculator" to "计算器",
        "random_number" to "生成随机数",
    )[call.name] ?: call.name
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF3EEE1),
        border = BorderStroke(1.dp, Line2),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Build, null, Modifier.size(13.dp), tint = AccentInk)
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = AccentInk,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("· 已执行", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Ok)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall, color = Ink3)
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    Text("参数", style = MaterialTheme.typography.labelSmall, color = Ink3)
                    Text(call.arguments.ifBlank { "{}" }, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp, color = Ink2, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("结果", style = MaterialTheme.typography.labelSmall, color = Ink3)
                    Text(call.result, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp, color = Ink2, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ImageThumb(base64: String) {
    val bmp = remember(base64) {
        runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "附图",
            modifier = Modifier.size(110.dp).background(Paper3, RoundedCornerShape(10.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
}

@Composable
private fun AiAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).background(AccentSoft, RoundedCornerShape(9.dp))
            .border(1.dp, Color(0xFFEBD2B8), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        // 原创星形笔触图标
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.18f, h * 0.62f)
                cubicTo(w * 0.45f, h * 0.52f, w * 0.57f, h * 0.38f, w * 0.57f, h * 0.08f)
                cubicTo(w * 0.57f, h * 0.38f, w * 0.69f, h * 0.52f, w * 0.96f, h * 0.62f)
                cubicTo(w * 0.69f, h * 0.72f, w * 0.57f, h * 0.86f, w * 0.57f, h * 0.98f)
                cubicTo(w * 0.57f, h * 0.86f, w * 0.45f, h * 0.72f, w * 0.18f, h * 0.62f)
                close()
            }
            drawPath(path, AccentInk)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    var clicked by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { clicked = true; onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (clicked) Ok else Ink3)
        Spacer(Modifier.width(4.dp))
        Text(if (clicked) "已复制" else label, style = MaterialTheme.typography.labelSmall, color = if (clicked) Ok else Ink3)
    }
    LaunchedEffect(clicked) { if (clicked) { kotlinx.coroutines.delay(1600); clicked = false } }
}

@Composable
fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )
    Box(
        Modifier.padding(start = 3.dp).size(width = 2.dp, height = 16.dp)
            .background(Accent.copy(alpha = alpha), RoundedCornerShape(1.dp))
    )
}
