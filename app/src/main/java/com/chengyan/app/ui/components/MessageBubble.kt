package com.chengyan.app.ui.components

/*
 * 消息行 —— 交互结构完整移植自 Quor-a/ZorvAI ChatScreen.kt 的 MessageRow：
 *   · 气泡壳已删除（ZorvAI 原注释「已删除聊天气泡，正文直接走 Markdown 渲染」）：
 *     正文不再被气泡压窄，颜色只保留文字本身区分（用户暖褐 / AI 墨色），版式交给 Markdown。
 *   · 头像悬浮在左上角（Box + align(TopStart)），不挤压正文宽度；名字行留头像避让位。
 *   · 名字行「作者 · 时间」combinedClickable 长按 → MessageHeaderMenu（复制/编辑/删除）。
 *   · 思考：流式期间内联展示（surfaceVariant 底、限高），完成后折叠为「思考了 Ns ›」行，
 *     点开全量弹层（ThinkFoldRow 语义）。
 *   · 工具调用：名字行右侧「· N 工具」TinyChip 开关，点开活动流逐条明细（失败染红），
 *     点击某条打开详情弹层 —— 不再把整轮过程全量铺在正文里。
 *   · 操作按钮（复制/重新生成/删除）：只在「最后一条 AI 消息且本轮已结束」平铺
 *     （ZorvAI showActions 门控；历史消息操作收进名字行长按菜单）。
 * 视觉仍为澄言原创暖纸×墨×陶土橙。
 */

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.Message
import com.chengyan.app.data.ToolCallInfo
import com.chengyan.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 用户正文暖褐色（与 ZorvAI 删除气泡壳后的文字区分一致）。 */
private val UserInk = Color(0xFF5A3322)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    showActions: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onQuote: () -> Unit = {},
    // 身份：AI = 当前灵魂角色（头像/名字跟随灵魂注入），用户 = 设置里的用户资料
    soulName: String = "",
    soulAvatarUri: String = "",
    userName: String = "",
    userAvatarUri: String = "",
    onEditSoul: () -> Unit = {},
    onEditUser: () -> Unit = {},
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    // 名字行长按菜单（ZorvAI MessageHeaderMenu：复制 / 编辑 / 删除）
    var headerMenu by remember { mutableStateOf(false) }
    // 「· N 工具」活动流开关
    var showActivity by remember { mutableStateOf(false) }
    // 思考全量弹层 / 工具详情弹层
    var thinkOpen by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<com.chengyan.app.ui.components.ActivityEntry?>(null) }
    // 活动流条目（思考 + 工具，真实时序混排，ZorvAI ActivityEntry 语义）
    val activityEntries = remember(message.toolCalls.size, message.thinking) {
        buildList {
            if (message.thinking.isNotBlank()) {
                add(ActivityEntry(
                    category = ActivityCat.THINK,
                    title = if (message.thinkingMs >= 1000) "思考了 ${formatDurationMs(message.thinkingMs)}" else "思考",
                    result = message.thinking,
                    durationMs = message.thinkingMs,
                ))
            }
            message.toolCalls.forEach { t ->
                val cat = activityCategoryOf(t.name)
                add(ActivityEntry(
                    category = cat,
                    title = resolveActivityTitle(cat, t.name, t.arguments),
                    toolName = t.name,
                    args = t.arguments,
                    result = t.result.takeIf { it.isNotBlank() },
                    failed = detectResultFailed(t.result),
                ))
            }
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // 头像悬浮布局（ZorvAI）：28dp 头像 → 名字行下移 3dp 对齐中线，正文再留 10dp
    val avatarSize = 28
    val avatarIndent = (avatarSize + 10).dp

    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // 🔴 头像：悬浮左上角，不是流式兄弟节点（两侧统一靠左，ZorvAI 同款）
        Box(Modifier.align(Alignment.TopStart)) {
            if (!isUser) AiAvatar(soulName, soulAvatarUri, onEditSoul) else UserAvatar(userName, userAvatarUri, onEditUser)
        }

        Column(Modifier.fillMaxWidth()) {
            // ── 名字行：作者 · 模型 · 时间（长按出菜单）+ 工具胶囊 ──
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = avatarIndent, top = 3.dp, bottom = 4.dp),
            ) {
                Box {
                    Text(
                        "${if (isUser) userName.ifBlank { "你" } else soulName.ifBlank { "澄言" }} · ${timeFmt.format(Date(message.time))}",
                        fontSize = 11.sp,
                        color = if (isUser) Ink3 else Ink2,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                headerMenu = true
                            },
                        ),
                    )
                    MessageHeaderMenu(
                        expanded = headerMenu,
                        onDismiss = { headerMenu = false },
                        showEdit = isUser,
                        onCopy = { headerMenu = false; clipboard.setText(AnnotatedString(message.content)); onCopy() },
                        onEdit = { headerMenu = false; onEdit() },
                        onDelete = { headerMenu = false; onDelete() },
                        onQuote = { headerMenu = false; onQuote() },
                    )
                }
                if (!isUser && message.model != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(message.model, fontSize = 10.sp, color = Ink4, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 「· N 工具」胶囊（ZorvAI TinyChip）：只数工具调用，思考有自己的一行
                if (!isUser && message.toolCalls.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    TinyChip(
                        onClick = { showActivity = !showActivity },
                        containerColor = Accent.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "${if (showActivity) "收起" else "· ${message.toolCalls.size} 工具" + (if (message.thinking.isNotBlank()) " · 思考" else "")}",
                            fontSize = 9.sp, color = AccentInk,
                        )
                    }
                }
            }

            // ── 活动流（ZorvAI ActivityStreamView 同构：汇总行 + 逐条明细 + 详情弹层）──
            if (showActivity && activityEntries.isNotEmpty()) {
                ActivityStreamView(
                    entries = activityEntries,
                    onOpenEntry = { e -> if (e.isThink) thinkOpen = true else detailEntry = e },
                    modifier = Modifier.padding(start = avatarIndent),
                )
                Spacer(Modifier.height(6.dp))
            }

            // ── 思考：流式内联 / 完成折叠行（ZorvAI ThinkFoldRow）──
            if (!isUser && message.thinking.isNotBlank()) {
                val active = message.streaming && message.content.isBlank()
                if (active) {
                    ThinkInlineContent(message.thinking)
                    Spacer(Modifier.height(6.dp))
                } else {
                    ThinkFoldRow(
                        durationMs = message.thinkingMs,
                        onClick = { thinkOpen = true },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ── 附件（图片 / 视频 / 文档 / 音频，用户与 AI 双向）──
            if (message.images.isNotEmpty() || message.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = avatarIndent, bottom = 6.dp)) {
                    message.images.forEach { b64 -> ImageThumb(b64) }
                    message.attachments.forEach { a -> AttachmentBubble(a) }
                }
            }

            // ── 正文（气泡壳已删除，直接渲染，满宽）──
            Box(Modifier.padding(top = 10.dp)) {
                if (message.isError) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(message.content, style = MaterialTheme.typography.bodySmall, color = Err, lineHeight = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                            Text("重试", fontSize = 11.sp, color = AccentInk)
                        }
                    }
                } else if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = UserInk,
                        lineHeight = 23.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        // ui-card 富卡片：从正文摘出围栏 JSON，渲染成结构化组件
                        val parsed = remember(message.content) {
                            com.chengyan.app.data.UiCardParser.parse(message.content)
                        }
                        MarkdownText(parsed.first)
                        if (parsed.second.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            parsed.second.forEach { card ->
                                UiCardView(card)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        if (message.streaming) StreamingCursor()
                    }
                }
            }

            // ── 操作按钮行（ZorvAI showActions 门控：只在最后一条 AI 消息且本轮已结束）──
            if (showActions && !message.streaming) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().padding(start = avatarIndent), horizontalArrangement = Arrangement.Start) {
                    BubbleActionButton("复制", Ink3) { clipboard.setText(AnnotatedString(message.content)); onCopy() }
                    if (!isUser) BubbleActionButton("重新生成", AccentInk, onRegenerate)
                    if (isUser) BubbleActionButton("编辑", Ink3, onEdit)
                    BubbleActionButton("删除", Ink3, onDelete)
                }
            }
        }
    }

    // 思考过程全量弹层
    if (thinkOpen) {
        AlertDialog(
            onDismissRequest = { thinkOpen = false },
            title = {
                Text(
                    if (message.thinkingMs > 0) "思考了 ${message.thinkingMs / 1000}s" else "思考过程",
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    message.thinking,
                    style = MaterialTheme.typography.bodySmall, color = Ink2, lineHeight = 20.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { thinkOpen = false }) { Text("关闭", color = Ink2) } },
        )
    }

    // 工具/思考详情弹层（ZorvAI ActivityDetailSheet 同构：按类别分派渲染）
    detailEntry?.let { e ->
        ActivityDetailSheet(entry = e, onDismiss = { detailEntry = null })
    }
}

/* ---------- 名字行长按菜单（ZorvAI MessageHeaderMenu） ---------- */

@Composable
private fun MessageHeaderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showEdit: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit = {},
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("复制", fontSize = 14.sp) }, onClick = onCopy)
        DropdownMenuItem(text = { Text("引用回复", fontSize = 14.sp) }, onClick = onQuote)
        if (showEdit) DropdownMenuItem(text = { Text("编辑", fontSize = 14.sp) }, onClick = onEdit)
        DropdownMenuItem(text = { Text("删除", fontSize = 14.sp, color = Err) }, onClick = onDelete)
    }
}

/* ---------- TinyChip（ZorvAI 同款：20dp 高圆角胶囊） ---------- */

@Composable
private fun TinyChip(
    onClick: () -> Unit,
    containerColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}


/** 思考折叠行（ZorvAI ThinkFoldRow：✦ 思考了 Ns ›）。 */
@Composable
private fun ThinkFoldRow(durationMs: Long, onClick: () -> Unit) {
    val label = if (durationMs >= 1000) "思考了 ${formatDurationMs(durationMs)}" else "思考过程"
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✦", fontSize = 12.sp, color = Accent)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = Ink.copy(alpha = 0.82f))
        Spacer(Modifier.width(4.dp))
        Text("›", fontSize = 12.sp, color = Ink4)
    }
}

/** 流式期间内联展示实时思考（surfaceVariant 底 + 细边框，限高滚动）。 */
@Composable
private fun ThinkInlineContent(text: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, Line.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(10.dp, 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", fontSize = 11.sp, color = AccentInk)
            Spacer(Modifier.width(4.dp))
            Text("思考中…", fontSize = 10.sp, color = AccentInk, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text.takeLast(600),
            fontSize = 11.sp, color = Ink3, lineHeight = 17.sp,
            modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
        )
    }
}

/* ---------- 操作按钮（ZorvAI BubbleActionButton：28dp 高文字按钮） ---------- */

@Composable
private fun BubbleActionButton(label: String, tint: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(label, fontSize = 11.sp, color = tint)
    }
}

/* ---------- 头像（澄言原创视觉，布局同 ZorvAI 悬浮位） ---------- */

@Composable
private fun AiAvatar(name: String, avatarUri: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).background(AccentSoft, RoundedCornerShape(9.dp))
            .border(1.dp, Color(0xFFEBD2B8), RoundedCornerShape(9.dp))
            // 点 AI 头像 = 编辑当前灵魂角色资料
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AvatarImageOrChar(avatarUri, name.ifBlank { "澄言" })
    }
}

@Composable
private fun UserAvatar(name: String, avatarUri: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).background(Paper3, RoundedCornerShape(9.dp))
            .border(1.dp, Line2, RoundedCornerShape(9.dp))
            // 点自己的头像 = 编辑用户资料
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AvatarImageOrChar(avatarUri, name.ifBlank { "你" })
    }
}

/** 头像渲染：builtin = 内置小澄 vector；文件路径 = 用户上传图；空 = 首字圆标。 */
@Composable
private fun AvatarImageOrChar(avatarUri: String, fallback: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(avatarUri) {
        when {
            avatarUri == "builtin" -> null
            avatarUri.isBlank() -> null
            else -> runCatching {
                android.graphics.BitmapFactory.decodeFile(avatarUri)
            }.getOrNull()
        }
    }
    when {
        avatarUri == "builtin" -> Image(
            painter = androidx.compose.ui.res.painterResource(com.chengyan.app.R.drawable.ic_soul_default),
            contentDescription = fallback,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = fallback,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        else -> Text(fallback.take(1), fontSize = 11.sp, color = Ink2)
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
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "附图",
            modifier = Modifier.size(110.dp).background(Paper3, RoundedCornerShape(10.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
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

/** 消息附件卡片：视频可点播放、文档/文件/音频可点用系统应用打开。 */
@Composable
private fun AttachmentBubble(a: com.chengyan.app.data.Attachment) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasUri = !a.uri.isNullOrBlank()
    fun open() {
        if (!hasUri) return
        runCatching {
            val uri = android.net.Uri.parse(a.uri)
            val mime = a.mime.ifBlank { "*/*" }
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    when (a.type) {
        "image" -> {
            if (a.base64 != null) {
                ImageThumb(a.base64!!)
            } else if (hasUri) {
                // 无 coil 依赖：用 ContentResolver 解码本地 URI 显示缩略图
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(android.net.Uri.parse(a.uri))
                    )
                }.getOrNull()
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = a.name,
                        modifier = Modifier
                            .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { open() },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
        }
        "video" -> {
            Surface(shape = RoundedCornerShape(10.dp), color = androidx.compose.ui.graphics.Color(0xFF2A2620),
                modifier = Modifier.clickable { open() }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", fontSize = 20.sp, color = androidx.compose.ui.graphics.Color(0xFFD99A5B))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("视频 · ${a.name}", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFEDE4D2), maxLines = 1)
                        Text("${a.size / 1024}KB · 点击播放", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFFA89C86))
                    }
                }
            }
        }
        else -> {
            Surface(shape = RoundedCornerShape(10.dp), color = Paper3,
                modifier = Modifier.clickable { open() }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (a.type == "audio") "♪" else "📄", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(a.name, fontSize = 12.sp, color = Ink, maxLines = 1)
                        Text(
                            "${if (a.type == "audio") "音频" else "文档"} · ${a.size / 1024}KB" +
                                if (hasUri) " · 点击打开" else " · 已内联",
                            fontSize = 10.sp, color = Ink2,
                        )
                    }
                }
            }
        }
    }
}

/* ==================== ui-card 富卡片渲染 ==================== */
/** 卡片渲染：note/list/kv/action/code —— 与 parseComponentSpec 协议对应。 */
@Composable
private fun UiCardView(card: com.chengyan.app.data.UiCardParser.Card) {
    val accent = if (card.tone == "accent" || card.tone == "warn") AccentInk else Ink2
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFFBF7EE),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (card.title.isNotBlank()) {
                Text(card.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = accent)
                Spacer(Modifier.height(6.dp))
            }
            when (card.kind) {
                com.chengyan.app.data.UiCardParser.Kind.NOTE,
                com.chengyan.app.data.UiCardParser.Kind.CODE -> {
                    if (card.body.isNotBlank()) {
                        Text(card.body, fontSize = 13.sp, color = Ink,
                            fontFamily = if (card.kind == com.chengyan.app.data.UiCardParser.Kind.CODE) FontFamily.Monospace else FontFamily.Default,
                            lineHeight = 20.sp)
                    }
                }
                com.chengyan.app.data.UiCardParser.Kind.LIST -> {
                    card.items.forEach { item ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("· ", fontSize = 13.sp, color = accent)
                            Text(item, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
                com.chengyan.app.data.UiCardParser.Kind.KV -> {
                    card.kv.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, fontSize = 12.sp, color = Ink2, modifier = Modifier.weight(1f))
                            Text(v, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                com.chengyan.app.data.UiCardParser.Kind.ACTION -> {
                    if (card.body.isNotBlank()) Text(card.body, fontSize = 13.sp, color = Ink)
                    card.actions.forEach { a ->
                        Text("▸ $a", fontSize = 12.sp, color = accent, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> if (card.body.isNotBlank()) Text(card.body, fontSize = 13.sp, color = Ink)
            }
        }
    }
}
