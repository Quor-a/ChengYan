@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AttachFile
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit, onOpenPrefs: () -> Unit = {}, onOpenTerminal: () -> Unit = {}) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed, confirmStateChange = { true })
    val scope = rememberCoroutineScope()
    val openDrawer = { scope.launch { drawerState.open() } }
    val closeDrawer = { scope.launch { drawerState.close() } }
    val clipboard = LocalClipboardManager.current
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
    var memoryOpen by remember { mutableStateOf(false) }
    var tagManageOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 🔴 ZorvAI 同款结构：ModalNavigationDrawer 包在 Scaffold **外面**——
    // 抽屉全屏高、遮罩盖住顶栏；放 Scaffold 内部会被顶栏裁剪（已踩坑）。
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
                    activeSoulName = vm.souls.firstOrNull { it.id == vm.activeSoulId }?.name,
                    onPick = { vm.pick(it); closeDrawer() },
                    onDelete = { vm.deleteConversation(it) },
                    onNew = { vm.newConversation(); closeDrawer() },
                    onSoul = { soulSheetOpen = true },
                    onDeleteAll = { vm.deleteAllConversations() },
                    onClose = { closeDrawer() }
                )
            }
        }
    ) {
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
                    // 模型名已在标题副行显示，右侧胶囊删除（重复信息只会让顶栏更乱）；点 ⋮ 可进模型配置
                    // 次级操作收进「更多」菜单：顶栏只留一个入口
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
                                text = { Text("Linux 终端", fontSize = 14.sp) },
                                onClick = { menuOpen = false; onOpenTerminal() },
                                leadingIcon = { Text("⌨", fontSize = 13.sp, color = Ink3) },
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
        Column(Modifier.padding(pad).fillMaxSize()) {
            ChatArea(vm, Modifier.weight(1f), onEdit = { editing = it; editText = it.content })
            ComposerBar(vm)
        }
    }
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
            activeId = vm.current?.soulId?.takeIf { it.isNotBlank() }
                ?: com.chengyan.app.data.SoulRepository.BUILTIN_ID,
            onManageMemory = { soulSheetOpen = false; memoryOpen = true },
            onManageTags = { soulSheetOpen = false; tagManageOpen = true },
            onDelete = { vm.deleteSoul(it.id) },
            onPick = { card -> soulSheetOpen = false; vm.startWithSoul(card) },
        )
    }
    if (soulEditOpen) {
        SoulEditDialog(
            initial = soulEditing,
            tagRepo = vm.tagRepo,
            memRepo = vm.memRepo,
            onManageTags = { soulEditOpen = false; tagManageOpen = true },
            onSave = { vm.saveSoul(it) },
            onDelete = if (!soulEditIsNew) {
                { soulEditOpen = false; soulEditing?.let { vm.deleteSoul(it.id) } }
            } else null,
            onDismiss = { soulEditOpen = false },
        )
    }
    if (memoryOpen) {
        val activeSoulId = soulEditing?.id
            ?: vm.current?.soulId?.takeIf { it.isNotBlank() }
            ?: com.chengyan.app.data.SoulRepository.BUILTIN_ID
        MemoryDialog(
            personaId = activeSoulId,
            repo = vm.memRepo,
            onDismiss = { memoryOpen = false; vm.reloadMemories() },
        )
    }
    if (tagManageOpen) {
        TagManageScreen(
            repo = vm.tagRepo,
            onDismiss = { tagManageOpen = false },
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
    activeSoulName: String? = null,
    onPick: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
    onSoul: () -> Unit,
    onDeleteAll: () -> Unit,
    onClose: () -> Unit,
) {
    // 交互结构完整移植 ZorvAI HistoryDrawer：标题行(对话+清空全部+关闭) →
    // 一行两芯片(新对话+灵魂注入) → 搜索框 → 分组标题+会话行(两行文本, 右侧时间+每行删除) → 确认框
    var searchQuery by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxHeight().width(300.dp).background(Paper2)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(0.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("对话", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = Ink, fontFamily = FontFamily.Serif)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showDeleteAllConfirm = true }, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "清空全部", Modifier.size(20.dp), tint = Err)
            }
            IconButton(onClick = onClose, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, "关闭", Modifier.size(20.dp), tint = Ink)
            }
        }
        // 统计条：对话数 + 当前灵魂，一眼看到侧栏状态
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("共 ${conversations.size} 条对话", fontSize = 11.sp, color = Ink4)
            Spacer(Modifier.weight(1f))
            if (activeSoulName != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = AccentSoft) {
                    Text("灵魂 · $activeSoulName", fontSize = 10.sp, color = AccentInk,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AccentSoft)
                        .clickable(onClick = onNew).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = Accent)
                    Spacer(Modifier.width(10.dp))
                    Text("新对话", fontSize = 14.sp, color = AccentInk, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(Ink.copy(alpha = 0.06f)).clickable(onClick = onSoul).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(15.dp), tint = Ink2)
                    Spacer(Modifier.width(8.dp))
                    Text("灵魂注入", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索对话标题", fontSize = 13.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        // 🔴 LazyColumn：旧 Column+verticalScroll 无复用，会话多时全部 item 一次性组合
        val filtered = remember(conversations, searchQuery) {
            conversations.distinctBy { it.id }.sortedByDescending { it.updated }
                .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            var lastGroup = ""
            filtered.forEach { c ->
                val age = System.currentTimeMillis() - c.updated
                val group = when {
                    age < 24 * 3600_000L -> "今天"
                    age < 48 * 3600_000L -> "昨天"
                    age < 7 * 24 * 3600_000L -> "7 天内"
                    else -> "更早"
                }
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "hdr_$group") {
                        Text(group, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                }
                item(key = c.id) {
                val active = c.id == currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) AccentSoft else Paper2)
                        .clickable(onClick = { onPick(c.id) })
                        .padding(12.dp, 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.takeIf { it.isNotBlank() && it != "null" } ?: "新对话", fontSize = 14.sp,
                            color = Ink, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // 🔴 remember：流式每 tick 对全部会话各分配一个 replace 子串
                        val sub = remember(c.id, c.messages.size, c.messages.lastOrNull()?.content?.length) {
                            c.messages.lastOrNull()?.content?.replace("\n", " ")
                        }
                        if (sub != null) Text(sub, fontSize = 12.sp, color = Ink4, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(relTime(c.updated), fontSize = 11.sp, color = Ink4, modifier = Modifier.padding(start = 8.dp))
                    IconButton(onClick = { pendingDeleteId = c.id }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = Ink4)
                    }
                }
                }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text("没有匹配的对话", fontSize = 13.sp, color = Ink4,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除对话") },
            text = { Text("确定删除这条对话？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(pendingDeleteId!!); pendingDeleteId = null }) {
                    Text("删除", color = Err)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消", color = Ink2) }
            }
        )
    }
    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("清空全部对话") },
            text = { Text("确定删除全部对话记录？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDeleteAll(); showDeleteAllConfirm = false }) {
                    Text("清空", color = Err)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("取消", color = Ink2) }
            }
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
// 贴底用的大偏移：scrollToItem 超出内容会被钳制到列表末尾 → 稳定停在真正的底部
private const val SCROLL_BOTTOM_OFFSET = 1_000_000
@Composable
private fun ChatArea(vm: ChatViewModel, modifier: Modifier = Modifier, onEdit: (Message) -> Unit = {}) {
    val conv = vm.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = rememberLazyListState()
    val msgs = conv?.messages ?: emptyList()
    // 当前对话的灵魂角色（决定 AI 头像与名字）；点 AI 头像可编辑其资料
    // 显示 = 绝对跟随对话绑定的灵魂（prompt 同源，见 historyUpTo）；未绑定时跟随全局激活灵魂
    val currentSoul = conv?.soulId?.takeIf { it.isNotBlank() }?.let { id -> vm.souls.firstOrNull { it.id == id } }
        ?: vm.activeSoulId.takeIf { it.isNotBlank() }?.let { id -> vm.souls.firstOrNull { it.id == id } }
    var editSoulOpen by remember { mutableStateOf(false) }
    var editUserOpen by remember { mutableStateOf(false) }

    // 智能滚动：流式输出时自动贴底跟随；用户向上滑动即停止跟随；↓ 或手动滚回底部恢复跟随
    val autoFollow = remember { mutableStateOf(true) }
    var lastFollowScrollMs by remember { mutableStateOf(0L) }
    // 用户在流式跟随期间向上滑动 → 立即停止跟随（方向侦测，不用等滚动停下）
    LaunchedEffect(listState) {
        var lastFirst = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (listState.isScrollInProgress && idx < lastFirst) autoFollow.value = false
            lastFirst = idx
        }
    }
    // 滚动停止时：位于底部附近 → 恢复跟随
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last >= info.totalItemsCount - 2) autoFollow.value = true
        }
    }
    // 流式跟随：只要跟随开启，内容增长就无条件贴底（可见项计数在快速流式下不可靠，
    // 旧逻辑"距底部超过2条就不动"导致跟随卡死且 ↓ 永不出现——本次修正）
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) {
        // 🔴 贴底节流 ≥150ms：流式时 key 每 80ms 变，逐 tick 强制整列表重布局是重组风暴的一部分
        val now = System.currentTimeMillis()
        if (autoFollow.value && msgs.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0 &&
            now - lastFollowScrollMs >= 150) {
            lastFollowScrollMs = now
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, SCROLL_BOTTOM_OFFSET)
        }
    }

    if (conv == null || msgs.isEmpty()) {
        Box(modifier) { WelcomeView(vm.config, vm::send, Modifier.fillMaxSize()) }
        return
    }
    Box(modifier) {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp, start = 14.dp, end = 14.dp),
            modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 🔴 外提：原来每个 item lambda 内 O(n) 扫一遍 msgs（整体 O(n²)/次重组）
            val lastAiIdx = msgs.indexOfLast { it.role != "user" }
            itemsIndexed(msgs, key = { i, m -> "msg_${m.id}_$i" }) { i, m ->
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
                    soulName = currentSoul?.name ?: "",
                    soulAvatarUri = currentSoul?.avatarUri ?: "builtin",
                    userName = vm.userProfile.name,
                    userAvatarUri = vm.userProfile.avatarUri,
                    onEditSoul = { editSoulOpen = true },
                    onEditUser = { editUserOpen = true },
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
                        if (target >= 0) listState.animateScrollToItem(target, SCROLL_BOTTOM_OFFSET)
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
    // 点 AI 头像 → 编辑当前灵魂角色；点自己的头像 → 编辑用户资料
    if (editSoulOpen) {
        SoulEditDialog(
            initial = currentSoul,
            onSave = { vm.saveSoul(it); editSoulOpen = false },
            onDelete = null,
            onDismiss = { editSoulOpen = false },
        )
    }
    if (editUserOpen) {
        UserProfileDialog(
            initial = vm.userProfile,
            onSave = { vm.updateUserProfile(it) },
            onDismiss = { editUserOpen = false },
        )
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
    val prefill by vm.composerPrefill.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let { text = it; vm.composerPrefill.value = null }
    }
    // 统一附件列表：图片/视频/文档/音频都走 Attachment，发送时一次性交给 VM。
    var pending by remember { mutableStateOf<List<com.chengyan.app.data.Attachment>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun addUri(uri: android.net.Uri) {
        val a = com.chengyan.app.data.AttachmentStore.fromUri(context, uri) ?: return
        if (pending.size < 8) pending = pending + a
    }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) addUri(uri) }
    val pickVideo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) addUri(uri) }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) addUri(uri) }

    fun doSend() {
        val imgs = pending.filter { it.type == "image" && it.base64 != null }.map { it.base64!! }
        val body = (text.trim() + "\n\n" + pending.filter { it.type != "image" }
            .mapNotNull { it.text }.joinToString("\n\n")).trim()
        if (body.isNotBlank() || imgs.isNotEmpty()) {
            vm.send(body.ifBlank { "请看附件" }, imgs, pending)
            text = ""; pending = emptyList()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp), color = Card,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Line)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // 统一附件预览栏
                    if (pending.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pending.forEachIndexed { i, a ->
                                AttachmentChip(a, onRemove = { pending = pending.filterIndexed { j, _ -> j != i } })
                            }
                        }
                    }
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp, max = 150.dp),
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
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
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Icon(Icons.Rounded.AddPhotoAlternate, "添加图片", tint = Ink3) }
                        IconButton(onClick = { pickVideo.launch("video/*") }) {
                            Text("▶", fontSize = 20.sp, color = Ink3)
                        }
                        IconButton(onClick = { pickFile.launch("*/*") }) {
                            Icon(Icons.Rounded.AttachFile, "添加文档/文件", tint = Ink3)
                        }
                        Text("Enter 发送", style = MaterialTheme.typography.labelSmall, color = Ink4)
                        Spacer(Modifier.weight(1f))
                        if (vm.streaming) {
                            Text("生成中…", style = MaterialTheme.typography.labelSmall, color = Ink3)
                            Spacer(Modifier.width(8.dp))
                            FilledTonalIconButton(
                                onClick = { vm.stop() },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Ink2, contentColor = Paper)
                            ) { Icon(Icons.Rounded.Stop, "停止", Modifier.size(18.dp)) }
                        } else {
                            IconButton(onClick = { doSend() }, enabled = text.isNotBlank() || pending.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Rounded.Send, "发送",
                                    tint = if (text.isNotBlank() || pending.isNotEmpty()) Accent else Ink4)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 附件预览 chip：图片缩略图、视频/音频图标、文档名称。 */
@Composable
private fun AttachmentChip(a: com.chengyan.app.data.Attachment, onRemove: () -> Unit) {
    Box {
        Surface(shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            color = Paper3) {
            when (a.type) {
                "image" -> {
                    if (a.base64 != null) {
                        // 🔴 remember：ComposerBar 每次重组（每敲一个字/每流式 tick）不再重复
                        //    Base64+BitmapFactory 解码整图（主线程可达数 MB 位图分配）
                        val bmp = remember(a.base64) { com.chengyan.app.data.AttachmentStore.decodeBase64Bitmap(a.base64).asImageBitmap() }
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = a.name, modifier = Modifier.size(64.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    } else Text("🖼", fontSize = 22.sp, modifier = Modifier.size(64.dp).padding(20.dp))
                }
                "video" -> Text("▶", fontSize = 24.sp, modifier = Modifier.size(64.dp).padding(18.dp))
                "audio" -> Text("♪", fontSize = 24.sp, modifier = Modifier.size(64.dp).padding(18.dp))
                else -> Column(Modifier.size(64.dp).padding(6.dp), verticalArrangement = Arrangement.Center) {
                    Text("📄", fontSize = 16.sp)
                    Text(a.name.take(8), fontSize = 9.sp, color = Ink2, maxLines = 2)
                }
            }
        }
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Ink,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clickable { onRemove() }
        ) { Text("×", color = Paper, fontSize = 11.sp, modifier = Modifier.padding(2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
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
