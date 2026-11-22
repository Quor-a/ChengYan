@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.Conversation
import com.chengyan.app.data.Message
import com.chengyan.app.data.SUGGESTIONS
import com.chengyan.app.ui.ChatViewModel
import com.chengyan.app.ui.components.MessageBubble
import com.chengyan.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("M月d日", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawerOpen = remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var editText by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var assistantOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.current?.title ?: "新对话", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen.value = true }) {
                        Icon(Icons.Rounded.Menu, "对话列表")
                    }
                },
                actions = {
                    ModelChip(vm.config, onOpenSettings)
                    IconButton(
                        enabled = vm.current?.messages?.isNotEmpty() == true,
                        onClick = {
                            vm.exportCurrent()?.let { md ->
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(android.content.Intent.EXTRA_TEXT, md)
                                    putExtra(android.content.Intent.EXTRA_TITLE, vm.current?.title ?: "对话")
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
                            }
                        }
                    ) { Icon(Icons.Rounded.Share, "导出分享", tint = Ink2) }
                    IconButton(
                        enabled = vm.current != null,
                        onClick = { renameText = vm.current?.title ?: ""; renaming = true }
                    ) { Icon(Icons.Rounded.Edit, "重命名对话", tint = Ink2) }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, "设置", tint = Ink2)
                    }
                    Spacer(Modifier.width(6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (drawerOpen.value) {
                ConversationDrawer(
                    conversations = vm.conversations,
                    currentId = vm.current?.id,
                    onPick = { vm.pick(it); drawerOpen.value = false },
                    onDelete = { confirmDelete = it },
                    onNew = { drawerOpen.value = false; assistantOpen = true },
                    onClose = { drawerOpen.value = false }
                )
            }
            Column(Modifier.weight(1f)) {
                ChatArea(vm, Modifier.weight(1f), onEdit = { editing = it; editText = it.content })
                ComposerBar(vm)
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除对话") },
            text = { Text("确定删除这条对话？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteConversation(id); confirmDelete = null }) {
                    Text("删除", color = Err)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消", color = Ink2) } }
        )
    }

    editing?.let { m ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editAndResend(m, editText.trim()); editing = null
                }) { Text("重发", color = AccentInk) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消", color = Ink2) } }
        )
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.current?.let { vm.renameConversation(it.id, renameText.trim()) }
                    renaming = false
                }) { Text("保存", color = AccentInk) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消", color = Ink2) } }
        )
    }

    if (assistantOpen) {
        AlertDialog(
            onDismissRequest = { assistantOpen = false },
            title = { Text("选择助手开始新对话") },
            text = {
                Column {
                    com.chengyan.app.data.DEFAULT_ASSISTANTS.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.startWithAssistant(a); assistantOpen = false }
                                .padding(vertical = 10.dp)
                        ) {
                            Box(Modifier.size(34.dp).background(AccentSoft, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(a.icon, color = AccentInk, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(a.prompt.take(24) + "…", style = MaterialTheme.typography.labelSmall, color = Ink3, maxLines = 1)
                            }
                        }
                    }
                    Text(
                        "不使用角色（无系统提示词）",
                        style = MaterialTheme.typography.bodySmall, color = Ink3,
                        modifier = Modifier.fillMaxWidth().clickable { vm.newConversation(); assistantOpen = false }
                            .padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {}
        )
    }

    vm.toast.collectAsState().value?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2200)
            vm.toastShown()
        }
        // 简易 toast 条
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(50), color = Ink, shadowElevation = 6.dp,
                modifier = Modifier.padding(bottom = 90.dp)) {
                Text(msg, color = Paper, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ModelChip(config: com.chengyan.app.data.ModelConfig, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        onClick = onClick
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(if (config.configured) Ok else Err, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(config.model.ifBlank { "未配置模型" }, style = MaterialTheme.typography.labelSmall, color = Ink2)
        }
    }
    Spacer(Modifier.width(8.dp))
}

/* ---------------- 抽屉：对话列表 ---------------- */
@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    currentId: Long?,
    onPick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxHeight().width(280.dp),
        color = Paper2,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).background(Accent, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("言", color = Color.White, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("澄言", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("CHENGYAN", fontSize = 9.sp, letterSpacing = 3.sp, color = Ink3, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("收起", style = MaterialTheme.typography.labelSmall, color = Ink3) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索对话…", style = MaterialTheme.typography.bodySmall, color = Ink3) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(16.dp), tint = Ink3) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Card, focusedContainerColor = Card,
                    unfocusedBorderColor = Line, focusedBorderColor = Accent2
                ),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentSoft, contentColor = AccentInk)
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("新对话", letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("最近对话", style = MaterialTheme.typography.labelSmall, color = Ink3, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp))
            val filtered = conversations
                .filter { query.isBlank() || it.title.contains(query) || it.messages.any { m -> m.content.contains(query) } }
                .sortedByDescending { it.updated }
            LazyColumn {
                itemsIndexed(filtered, key = { i, c -> "conv_${c.id}_$i" }) { _, c ->
                    ConversationRow(c, c.id == currentId, onPick, onDelete)
                }
                if (filtered.isEmpty()) {
                    item { Text("没有匹配的对话", style = MaterialTheme.typography.bodySmall, color = Ink4,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, active: Boolean, onPick: (Long) -> Unit, onDelete: (Long) -> Unit) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (active) Card else Color.Transparent,
        shadowElevation = if (active) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().clickable { onPick(c.id) }
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.bodySmall,
                    color = if (active) Ink else Ink2,
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val last = c.messages.lastOrNull()?.content
                if (last != null) Text(last, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = Ink3,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(relTime(c.updated), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Ink4, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.DeleteOutline, "删除", Modifier.size(14.dp).clickable { onDelete(c.id) }, tint = Ink3)
        }
    }
}

private fun relTime(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        else -> dayFmt.format(Date(t))
    }
}

/* ---------------- 对话区 ---------------- */
@Composable
private fun ChatArea(vm: ChatViewModel, modifier: Modifier = Modifier, onEdit: (Message) -> Unit = {}) {
    val conv = vm.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = rememberLazyListState()
    val msgs = conv?.messages ?: emptyList()

    // 智能滚动：仅当用户位于底部附近才自动跟随
    val autoFollow = remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            autoFollow.value = last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) {
        if (autoFollow.value && msgs.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    if (conv == null || msgs.isEmpty()) {
        Box(modifier) { WelcomeView(vm.config, vm::send, Modifier.fillMaxSize()) }
        return
    }
    Box(modifier) {
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
            modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(msgs, key = { i, m -> "msg_${m.id}_$i" }) { _, m ->
                MessageBubble(
                    message = m,
                    onCopy = { clipboard.setText(AnnotatedString(m.content)) },
                    onRegenerate = { vm.regenerate(m) },
                    onDelete = { vm.deleteMessage(m) },
                    onRetry = { vm.regenerate(m) },
                    onEdit = { onEdit(m) },
                )
            }
        }
        if (!autoFollow.value) {
            val scope = rememberCoroutineScope()
            Surface(
                onClick = {
                    autoFollow.value = true
                    scope.launch {
                        val target = listState.layoutInfo.totalItemsCount - 1
                        if (target >= 0) listState.animateScrollToItem(target)
                    }
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Card, shadowElevation = 4.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↓", color = Ink2, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun WelcomeView(config: com.chengyan.app.data.ModelConfig, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(64.dp).background(AccentSoft, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFEBD2B8), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("言", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 30.sp, color = AccentInk)
        }
        Spacer(Modifier.height(16.dp))
        Text("澄 言", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 6.sp)
        Text("CHENGYAN · CLARITY IN WORDS", fontSize = 10.sp, letterSpacing = 4.sp, color = Ink3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 6.dp))
        Text(
            if (config.configured) "一片澄澈，落笔成言。\n从下面的常用场景开始，或直接输入你的问题。"
            else "一片澄澈，落笔成言。\n先在右上角「设置」中填入接口地址与密钥。",
            style = MaterialTheme.typography.bodySmall, color = Ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        SUGGESTIONS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { s ->
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                        onClick = { onSend(s.prefix) }
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Column {
                                Text(s.title, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(s.desc, style = MaterialTheme.typography.labelSmall, color = Ink3)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 输入区 ---------------- */
@Composable
private fun ComposerBar(vm: ChatViewModel) {
    var text by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val b64 = readImageAsBase64(context, uri)
                if (b64 != null && pendingImages.size < 4) pendingImages = pendingImages + b64
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp), color = Card,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Line)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (pendingImages.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            pendingImages.forEachIndexed { i, b64 ->
                                Box {
                                    Surface(shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                                        androidx.compose.foundation.Image(
                                            bitmap = decodeBase64Bitmap(b64).asImageBitmap(),
                                            contentDescription = "待发送图片",
                                            modifier = Modifier.size(64.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                    Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape, color = Ink,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp)
                                            .clickable { pendingImages = pendingImages.filterIndexed { j, _ -> j != i } }
                                    ) { Text("×", color = Paper, fontSize = 11.sp, modifier = Modifier.padding(2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                }
                            }
                        }
                    }
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp, max = 150.dp),
                        keyboardOptions = KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            val t = text.trim()
                            if (t.isNotBlank()) { vm.send(t); text = "" }
                        }),
                        maxLines = 6,
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text("写下你想说的话…", color = Ink3, style = MaterialTheme.typography.bodyMedium)
                            inner()
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            ))
                        }) {
                            Icon(Icons.Rounded.AddPhotoAlternate, "添加图片", tint = Ink3)
                        }
                        Text("Enter 发送", style = MaterialTheme.typography.labelSmall, color = Ink4)
                        Spacer(Modifier.weight(1f))
                        if (vm.streaming) {
                            Text("生成中…", style = MaterialTheme.typography.labelSmall, color = Ink3)
                            Spacer(Modifier.width(8.dp))
                            FilledTonalIconButton(
                                onClick = { vm.stop() },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Ink2, contentColor = Paper
                                )
                            ) { Icon(Icons.Rounded.Stop, "停止", Modifier.size(18.dp)) }
                        } else {
                            IconButton(
                                onClick = {
                                    val t = text.trim()
                                    if (t.isNotBlank() || pendingImages.isNotEmpty()) {
                                        vm.send(t.ifBlank { "请看图回答" }, pendingImages)
                                        text = ""; pendingImages = emptyList()
                                    }
                                },
                                enabled = text.isNotBlank() || pendingImages.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, "发送",
                                    tint = if (text.isNotBlank() || pendingImages.isNotEmpty()) Accent else Ink4)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 图片工具 ---------------- */
/** 读取相册图片，缩放至最长边 ≤1024px 后压成 JPEG base64。 */
fun readImageAsBase64(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    bmp.recycle()
    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}.getOrNull()

fun decodeBase64Bitmap(base64: String): android.graphics.Bitmap = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrDefault(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
