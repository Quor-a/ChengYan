@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit, onOpenPrefs: () -> Unit = {}) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed, confirmStateChange = { true })
    val scope = rememberCoroutineScope()
    val openDrawer = { scope.launch { drawerState.open() } }
    val closeDrawer = { scope.launch { drawerState.close() } }
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var phoneDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!vm.phoneAsked) phoneDialog = true }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.markPhoneAsked() }
    var editing by remember { mutableStateOf<Message?>(null) }
    var editText by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    // 灵魂注入弹层（ZorvAI SoulInjectionSheet）：抽屉「新对话」与 ⋮ 菜单共用入口
    var soulSheetOpen by remember { mutableStateOf(false) }
    var soulEditing by remember { mutableStateOf<com.chengyan.app.data.SoulCard?>(null) }
    var soulEditOpen by remember { mutableStateOf(false) }
    var soulEditIsNew by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.widthIn(max = 200.dp)) {
                        // 单行省略：会话标题可能很长（旧版多行标题把整个顶栏撑乱）
                        Text(
                            vm.current?.title?.takeIf { it.isNotBlank() && it != "null" } ?: "新对话",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.config.model.ifBlank { "未配置模型" },
                            style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                            color = Ink4, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { openDrawer() }) {
                        Icon(Icons.Rounded.Menu, "对话列表", tint = Ink2)
                    }
                },
                actions = {
                    ModelChip(vm.config, onOpenSettings)
                    // 次级操作收进「更多」菜单：顶栏只留状态胶囊 + 一个入口（成熟客户端惯例）
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "更多", tint = Ink2)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("导出分享", fontSize = 14.sp) },
                                enabled = vm.current?.messages?.isNotEmpty() == true,
                                onClick = {
                                    menuOpen = false
                                    vm.exportCurrent()?.let { md ->
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/markdown"
                                            putExtra(android.content.Intent.EXTRA_TEXT, md)
                                            putExtra(android.content.Intent.EXTRA_TITLE, vm.current?.title ?: "对话")
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                            DropdownMenuItem(
                                text = { Text("重命名", fontSize = 14.sp) },
                                enabled = vm.current != null,
                                onClick = { menuOpen = false; renameText = vm.current?.title ?: ""; renaming = true },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                            DropdownMenuItem(
                                text = { Text("清空当前对话", fontSize = 14.sp) },
                                enabled = vm.current?.messages?.isNotEmpty() == true,
                                onClick = { menuOpen = false; confirmClear = true },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                            DropdownMenuItem(
                                text = { Text("灵魂注入", fontSize = 14.sp) },
                                onClick = { menuOpen = false; soulSheetOpen = true },
                                leadingIcon = { Text("✦", fontSize = 15.sp, color = AccentInk) },
                            )
                            DropdownMenuItem(
                                text = { Text("对话设置（提示词/思考）", fontSize = 14.sp) },
                                onClick = { menuOpen = false; onOpenPrefs() },
                                leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                            DropdownMenuItem(
                                text = { Text("手机能力授权", fontSize = 14.sp) },
                                onClick = { menuOpen = false; phoneDialog = true },
                                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                            DropdownMenuItem(
                                text = { Text("模型配置", fontSize = 14.sp) },
                                onClick = { menuOpen = false; onOpenSettings() },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null, Modifier.size(17.dp), tint = Ink3) },
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        // 🔴 标准 ModalNavigationDrawer：层级/遮罩/手势由框架保证，不再手搓浮层
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Paper2,
                    drawerTonalElevation = 8.dp,
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                ) {
                    ConversationDrawer(
                        conversations = vm.conversations,
                        currentId = vm.current?.id,
                        onPick = { vm.pick(it); closeDrawer() },
                        onDelete = { confirmDelete = it },
                        onNew = { closeDrawer(); soulSheetOpen = true },
                        onClose = { closeDrawer() }
                    )
                }
            }
        ) {
            Column(Modifier.padding(pad).fillMaxSize()) {
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空当前对话") },
            text = { Text("将删除当前对话的全部消息，对话本身保留。确定继续？") },
            confirmButton = {
                TextButton(onClick = { vm.clearCurrent(); confirmClear = false }) { Text("清空", color = Err) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = Ink2) } }
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

    // 手机能力授权引导（完整权限按领域聚合为 6 个工具）
    if (phoneDialog) {
        AlertDialog(
            onDismissRequest = { phoneDialog = false; vm.markPhoneAsked() },
            title = { Text("启用手机能力？", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
            text = {
                Column {
                    Text("澄言可以把以下手机能力作为工具交给 AI 调用（按领域聚合，共 11 个工具）：", fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "设备状态 — 电量/网络/内存/时间/剪贴板/音量/勿扰",
                        "通讯录 — 搜索、最近联系人、新建联系人",
                        "短信 — 收件箱、指定号码往来、发短信",
                        "通话 — 通话记录、拨打电话",
                        "日历 — 查询与创建日程",
                        "媒体库 — 照片/视频/音频，可把照片发给模型看（多模态）",
                        "位置 — 最近已知位置（经纬度+精度）",
                        "闹钟 — 设置系统闹钟与计时器",
                        "应用 — 列出/启动/卸载已安装应用",
                        "设备控制 — 音量/手电筒/震动/勿扰/亮度/网络面板",
                        "文件 — 保存到下载目录、应用私有笔记",
                    ).forEach { Text("• $it", fontSize = 12.sp, color = Ink2, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text("现在可以拒绝，之后随时在顶栏 ⋮「手机能力授权」开启；工具在权限未授予时会明确告知。", fontSize = 11.sp, color = Ink3, lineHeight = 17.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    phoneDialog = false
                    vm.markPhoneAsked()
                    permLauncher.launch(com.chengyan.app.net.PhoneTools.allPermissions.toTypedArray())
                }) { Text("授权并启用", color = AccentInk) }
            },
            dismissButton = {
                TextButton(onClick = { phoneDialog = false; vm.markPhoneAsked() }) { Text("暂不", color = Ink2) }
            }
        )
    }

    // 灵魂注入：底部弹层（选卡开新对话）+ 全屏编辑器
    if (soulSheetOpen) {
        SoulInjectionSheet(
            souls = vm.souls,
            onDismiss = { soulSheetOpen = false },
            onCreate = { soulSheetOpen = false; soulEditIsNew = true; soulEditing = null; soulEditOpen = true },
            onEdit = { soulSheetOpen = false; soulEditIsNew = false; soulEditing = it; soulEditOpen = true },
            onDelete = { vm.deleteSoul(it.id) },
            onPick = { card -> soulSheetOpen = false; vm.startWithSoul(card) },
        )
    }
    if (soulEditOpen) {
        SoulEditDialog(
            initial = soulEditing,
            onSave = { vm.saveSoul(it) },
            onDelete = if (!soulEditIsNew) {
                { soulEditOpen = false; soulEditing?.let { vm.deleteSoul(it.id) } }
            } else null,
            onDismiss = { soulEditOpen = false },
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
    Surface(color = Paper2) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.size(34.dp).background(Accent, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("言", color = Color.White, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("澄言", fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("CHENGYAN", fontSize = 9.sp, letterSpacing = 3.sp, color = Ink3, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Text("✕", fontSize = 15.sp, color = Ink3)
                }
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
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentSoft, contentColor = AccentInk),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("新对话", letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.height(2.dp))
            // 日期分组（今天 / 昨天 / 7 天内 / 更早）
            // 🔴 分组区间必须互斥（下界闭、上界开），否则同一条对话命中两组 → LazyColumn key 撞车闪退
            val filtered = remember(conversations, query) {
                conversations.distinctBy { it.id }
                    .filter { query.isBlank() || it.title.contains(query) || it.messages.any { m -> m.content.contains(query) } }
                    .sortedByDescending { it.updated }
            }
            val groups = remember(filtered) {
                val now = System.currentTimeMillis()
                val day = 24 * 3600_000L
                val age = { c: Conversation -> now - c.updated }
                listOf(
                    "今天" to { c: Conversation -> age(c) in 0 until day },
                    "昨天" to { c: Conversation -> age(c) in day until 2 * day },
                    "7 天内" to { c: Conversation -> age(c) in 2 * day until 7 * day },
                    "更早" to { c: Conversation -> age(c) >= 7 * day },
                )
            }
            LazyColumn(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for ((label, pred) in groups) {
                    val items = filtered.filter(pred)
                    if (items.isEmpty()) continue
                    item(key = "sec_$label") {
                        Text(
                            label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            color = Ink4, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 5.dp),
                        )
                    }
                    itemsIndexed(items, key = { i, c -> "conv_${c.id}_$i" }) { _, c ->
                        ConversationRow(c, c.id == currentId, onPick, onDelete)
                    }
                }
                if (filtered.isEmpty()) {
                    item { Text("没有匹配的对话", style = MaterialTheme.typography.bodySmall, color = Ink4,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: Conversation, active: Boolean, onPick: (Long) -> Unit, onDelete: (Long) -> Unit) {
    // 活动态：左侧陶土竖条 + 淡陶土底；删除按钮只在活动行显示，历史行长按弹删除（避免每行都挂一个垃圾桶的杂乱感）
    var longPress by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) AccentSoft.copy(alpha = 0.55f) else Color.Transparent,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .combinedClickable(
                onClick = { onPick(c.id) },
                onLongClick = { if (!active) longPress = true else onDelete(c.id) },
            )
    ) {
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).height(34.dp)
                    .background(if (active) Accent else Color.Transparent, RoundedCornerShape(2.dp))
            )
            Column(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title.takeIf { it.isNotBlank() && it != "null" } ?: "新对话", style = MaterialTheme.typography.bodySmall,
                        color = if (active) Ink else Ink2,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(relTime(c.updated), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                        color = Ink4, fontFamily = FontFamily.Monospace)
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.DeleteOutline, "删除", Modifier.size(15.dp).clickable { onDelete(c.id) }, tint = Ink3)
                    }
                }
                val last = c.messages.lastOrNull()?.content?.replace("\n", " ")
                if (last != null) Text(last, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = Ink3,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (longPress) {
        AlertDialog(
            onDismissRequest = { longPress = false },
            title = { Text("删除对话") },
            text = { Text("确定删除「${c.title.takeIf { it.isNotBlank() && it != "null" } ?: "新对话"}」？删除后不可恢复。") },
            confirmButton = { TextButton(onClick = { longPress = false; onDelete(c.id) }) { Text("删除", color = Err) } },
            dismissButton = { TextButton(onClick = { longPress = false }) { Text("取消", color = Ink2) } }
        )
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
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp, start = 14.dp, end = 14.dp),
            modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(msgs, key = { i, m -> "msg_${m.id}_$i" }) { i, m ->
                val lastAiIdx = msgs.indexOfLast { it.role != "user" }
                MessageBubble(
                    message = m,
                    // ZorvAI showActions 门控：只在最后一条 AI 消息且本轮已结束时平铺操作行
                    showActions = i == lastAiIdx && !vm.streaming,
                    onCopy = { clipboard.setText(AnnotatedString(m.content)); vm.showToast("已复制到剪贴板") },
                    onQuote = { vm.quoteReply(m) },
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
    // 引用回复预填：长按消息 → 引用回复 → 输入框填入引用块
    val prefill by vm.composerPrefill.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let {
            text = it
            vm.composerPrefill.value = null
        }
    }
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
