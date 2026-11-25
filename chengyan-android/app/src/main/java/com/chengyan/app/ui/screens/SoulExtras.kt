package com.chengyan.app.ui.screens

/*
 * 灵魂注入配套界面 —— 语义移植自 Quor-a/ZorvAI QuroSoulUi.kt：
 *   · MemoryDialog：全屏记忆库（搜索 / 增删改 / 导出导入 JSON）
 *   · MemoryEditDialog：记忆条目编辑（标题 / 内容 / 分组 / 标签）
 *   · TagManageScreen：全屏全局标签管理（创建 / 重命名 / 删除）
 *   · PersonaPackageExport / Import：灵魂卡人格包（JSON，含卡与记忆）分享与导入
 * 视觉为澄言原创暖纸×墨×陶土橙。
 */

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chengyan.app.data.SoulCard
import com.chengyan.app.data.SoulMemory
import com.chengyan.app.data.SoulMemoryRepository
import com.chengyan.app.data.TagPoolRepository
import com.chengyan.app.ui.theme.Accent
import com.chengyan.app.ui.theme.AccentInk
import com.chengyan.app.ui.theme.AccentSoft
import com.chengyan.app.ui.theme.Ink
import com.chengyan.app.ui.theme.Ink2
import com.chengyan.app.ui.theme.Ink3
import com.chengyan.app.ui.theme.Ink4
import com.chengyan.app.ui.theme.Line
import com.chengyan.app.ui.theme.Paper
import kotlinx.coroutines.launch

/* ==================== 记忆库（全屏） ==================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDialog(
    personaId: String,
    repo: SoulMemoryRepository,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SoulMemory>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SoulMemory?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entries = repo.loadAll() }
    fun reload() { scope.launch { entries = repo.loadAll() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                runCatching { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(repo.exportJson().toByteArray()) } }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = runCatching { ctx.contentResolver.openInputStream(it)?.use { s -> s.bufferedReader().readText() } }.getOrNull().orEmpty()
            if (text.isNotBlank()) scope.launch { repo.mergeImport(text); entries = repo.loadAll() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("CHENGYAN · MEMORY", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                            Text("记忆库", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) } },
                    actions = {
                        IconButton(onClick = { exportLauncher.launch("chengyan_memory_export.json") }) { Icon(Icons.Rounded.FileDownload, "导出", tint = Ink2) }
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Icon(Icons.Rounded.FileUpload, "导入", tint = Ink2) }
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, "新增", tint = Ink2) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
                )
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("搜索记忆", fontSize = 13.sp, color = Ink4) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = Ink4) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Ink),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) {
                        item {
                            Text("还没有记忆条目\n点右上角 + 添加这条灵魂卡需要记住的事",
                                fontSize = 13.sp, color = Ink4, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    val shown = if (query.isBlank()) entries else entries.filter {
                        it.title.contains(query, true) || it.content.contains(query, true)
                    }
                    items(shown, key = { it.id }) { e ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth().clickable { editing = e },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (e.title.isNotBlank()) Text(e.title, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
                                    Text(e.content, fontSize = 13.sp, color = Ink2)
                                    val meta = buildList {
                                        if (e.group.isNotBlank()) add("分组：" + e.group)
                                        if (e.tags.isNotEmpty()) add(e.tags.joinToString(","))
                                    }
                                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 11.sp, color = Ink4)
                                }
                                IconButton(onClick = { scope.launch { repo.delete(e.id) }; reload() }) {
                                    Icon(Icons.Rounded.Delete, "删除", tint = Accent)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showAdd) {
        MemoryEditDialog(
            initial = SoulMemory(id = java.util.UUID.randomUUID().toString(), personaId = personaId, updatedAt = System.currentTimeMillis()),
            onDismiss = { showAdd = false },
            onConfirm = { entry -> scope.launch { repo.add(entry) }; reload(); showAdd = false },
        )
    }
    editing?.let { e ->
        MemoryEditDialog(
            initial = e,
            onDismiss = { editing = null },
            onConfirm = { entry -> scope.launch { repo.update(entry) }; reload(); editing = null },
        )
    }
}

/** 记忆条目编辑（新增 / 修改通用）：标题、内容、分组、标签。 */
@Composable
fun MemoryEditDialog(
    initial: SoulMemory,
    onDismiss: () -> Unit,
    onConfirm: (SoulMemory) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var content by remember { mutableStateOf(initial.content) }
    var group by remember { mutableStateOf(initial.group) }
    var tags by remember { mutableStateOf(initial.tags.joinToString(",")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆条目", fontSize = 17.sp) },
        text = {
            Column {
                listOf("标题（可选）" to title, "内容" to content, "分组（可选）" to group, "标签（逗号分隔）" to tags).forEach { (label, value) ->
                    Text(label, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            when (label) {
                                "标题（可选）" -> title = it
                                "内容" -> content = it
                                "分组（可选）" -> group = it
                                else -> tags = it
                            }
                        },
                        singleLine = label != "内容",
                        textStyle = TextStyle(fontSize = 14.sp, color = Ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (label == "内容") 80.dp else 0.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (content.isNotBlank()) {
                    onConfirm(initial.copy(
                        title = title.trim(), content = content.trim(), group = group.trim(),
                        tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }) { Text("保存", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Ink2) } },
    )
}

/* ==================== 全局标签管理（全屏） ==================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(
    repo: TagPoolRepository,
    onDismiss: () -> Unit,
) {
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { tags = repo.loadAll() } }
    LaunchedEffect(Unit) { reload() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("CHENGYAN · TAGS", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                            Text("标签管理", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            newName, { newName = it },
                            placeholder = { Text("新标签名称", fontSize = 13.sp, color = Ink4) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = Ink),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { if (newName.isNotBlank()) scope.launch { repo.add(newName) }; newName = ""; reload() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                            enabled = newName.isNotBlank(),
                        ) { Text("创建") }
                    }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tags.isEmpty()) item {
                        Text("标签池是空的\n创建标签后可在灵魂卡编辑里选用",
                            fontSize = 13.sp, color = Ink4, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    items(tags, key = { it }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .clickable { editingTag = t; editName = t }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { repo.remove(t) }; reload() }, Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Delete, "删除", Modifier.size(18.dp), tint = Accent)
                            }
                        }
                    }
                }
            }
        }
    }

    editingTag?.let { t ->
        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text("重命名标签", fontSize = 17.sp) },
            text = {
                OutlinedTextField(editName, { editName = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = Ink))
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.rename(t, editName.trim()) }; editingTag = null; reload() }) { Text("保存", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editingTag = null }) { Text("取消", color = Ink2) } },
        )
    }
}

/* ==================== 人格包（JSON：灵魂卡 + 记忆） ==================== */

/** 构造人格包 JSON（卡 + 该卡的记忆）。 */
suspend fun buildPersonaPackageJson(soul: SoulCard, memRepo: SoulMemoryRepository): String {
    val memories = memRepo.loadForPersona(soul.id)
    val json = kotlinx.serialization.json.Json { encodeDefaults = true; prettyPrint = true }
    return json.encodeToString(PersonaPackage(cards = listOf(soul), memories = memories))
}

/** 从人格包 JSON 导入：返回插入的灵魂卡数。 */
suspend fun importPersonaPackage(text: String, memRepo: SoulMemoryRepository): SoulCard? {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val pkg = runCatching { json.decodeFromString<PersonaPackage>(text) }.getOrNull() ?: return null
    val card = pkg.cards.firstOrNull() ?: return null
    pkg.memories.forEach { memRepo.add(it.copy(personaId = card.id)) }
    return card
}

@kotlinx.serialization.Serializable
data class PersonaPackage(
    val type: String = "chengyan_persona_package",
    val cards: List<SoulCard> = emptyList(),
    val memories: List<SoulMemory> = emptyList(),
)
