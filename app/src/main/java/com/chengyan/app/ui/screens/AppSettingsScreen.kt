package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置主页（澄言原创）：侧边栏左下角设置图标进入。
 * 层级栈：设置主页 → 系统能力 / 连接器 / MCP，每层左上角 ← 逐层返回。
 */

@Composable
fun AppSettingsSheet(
    context: android.content.Context,
    onOpenModelConfig: () -> Unit,
    onOpenChatPrefs: () -> Unit,
    onCompactConversation: () -> Unit,
    onClose: () -> Unit,
) {
    // 返回栈：main → syscap / connectors / mcp（← 逐层返回）
    val stack = remember { mutableStateListOf("main") }
    val layer = stack.lastOrNull() ?: "main"
    val title = when (layer) {
        "syscap" -> "系统能力与权限"
        "connectors" -> "连接器"
        "mcp" -> "MCP 服务"
        "skills" -> "Skills"
        "workspace" -> "工作区"
        "adb" -> "无线调试 ADB"
        "vscreen" -> "后台虚拟屏"
        else -> "设置"
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) {
                // 顶栏：非主页时 ← 返回上一层
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (layer != "main") {
                        androidx.compose.material3.IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(20.dp), tint = Ink2)
                        }
                        Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text("CHENGYAN · SETTINGS", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                            Text("设置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Ink)
                        }
                        TextButton(onClick = onClose) { Text("完成", color = AccentInk) }
                    }
                }
                Spacer(Modifier.height(10.dp))

                when (layer) {
                    "main" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SettingsRow("模型配置", "服务商 / 密钥 / 模型档案 / 参数") { onOpenModelConfig() }
                        SettingsRow("对话设置", "系统提示词 / 思考模式 / 温度") { onOpenChatPrefs() }
                        SettingsRow("压缩上下文", "把当前对话旧消息摘要成笔记") { onCompactConversation() }
                        SettingsRow("系统能力与权限", "手机权限 / 无障碍屏幕服务 / Shizuku") { stack.add("syscap") }
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = Line2)
                        Spacer(Modifier.height(6.dp))
                        SettingsRow("连接器", "GitHub 授权登录 / 粘贴 JSON 清单添加，零代码") { stack.add("connectors") }
                        SettingsRow("MCP 服务", "接入 MCP 服务器，勾选暴露给 AI 的工具") { stack.add("mcp") }
                        SettingsRow("Skills", "技能规程：SKILL.md 注入提示词，让 AI 按你的流程干活") { stack.add("skills") }
                        SettingsRow("工作区", "AI 文件的根目录：选手机任意文件夹，默认应用内") { stack.add("workspace") }
                        SettingsRow("无线调试 ADB", "配对码配对 → adb shell 级控制（Android 11+）") { stack.add("adb") }
                        SettingsRow("后台虚拟屏", "AI 在看不见的独立屏上操作应用，Shizuku/ADB 双通道") { stack.add("vscreen") }
                        Spacer(Modifier.height(24.dp))
                    }
                    "skills" -> SkillsContent(context)
                    "workspace" -> WorkspaceContent(context)
                    "adb" -> AdbContent(context)
                    "vscreen" -> VScreenContent(context)
                    "syscap" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SystemCapabilitySection(context)
                        Spacer(Modifier.height(24.dp))
                    }
                    "connectors" -> ConnectorContent(context) { stack.removeAt(stack.lastIndex) }
                    "mcp" -> McpContent(context)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = Ink3, lineHeight = 16.sp)
        }
        Text("›", fontSize = 18.sp, color = Ink4)
    }
}

/* ==================== 连接器（对标 Dify：目录 → 详情，动态凭据表单 + 逐工具勾选） ==================== */

@Composable
private fun ConnectorContent(context: android.content.Context, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    val providers = remember(tick) { com.chengyan.app.net.ConnectorManager.catalog(context) }
    val saved = remember(tick) { com.chengyan.app.net.ConnectorManager.loadAll(context) }

    val open = openId?.let { id -> providers.find { it.id == id } }
    if (open != null) {
        ConnectorDetail(context, open, saved[open.id]) { openId = null }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "连接器目录：选择服务 → 填凭据连接 → 勾选暴露给 AI 的工具。连接后工具以 conn_ 前缀进入 AI 工具表；也可在对话框 🔧 里定向指定。",
                fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            var lastCat by remember { mutableStateOf("") }
            providers.forEach { p ->
                if (p.category != lastCat) {
                    lastCat = p.category
                    Text(p.category, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                val st = saved[p.id]
                val ready = com.chengyan.app.net.ConnectorManager.isReady(p, st)
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openId = p.id },
                    shape = RoundedCornerShape(12.dp),
                    color = Paper2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(38.dp).background(if (ready) AccentSoft else Ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(p.name.firstOrNull()?.uppercase() ?: "?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ready) AccentInk else Ink3)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(if (ready) Ok else Ink4, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                Spacer(Modifier.width(8.dp))
                                Text("${p.tools.size} 个工具", fontSize = 11.sp, color = Ink4)
                            }
                            Text(
                                if (ready) "已连接" else if (st != null && st.creds.isNotEmpty()) "凭据已填 · 未启用" else p.desc.take(40),
                                fontSize = 11.sp, color = Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (com.chengyan.app.net.ConnectorManager.isCustom(context, p.id)) {
                            Text("删除", fontSize = 12.sp, color = Err, modifier = Modifier.clickable {
                                com.chengyan.app.net.ConnectorManager.removeCustom(context, p.id); tick++
                            })
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("›", fontSize = 18.sp, color = Ink4)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // ＋ 添加连接器：粘贴/导入声明式 JSON 清单（零代码）
            var showAdd by remember { mutableStateOf(false) }
            var addErr by remember { mutableStateOf("") }
            if (showAdd) {
                var draft by remember { mutableStateOf("") }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Paper2, border = androidx.compose.foundation.BorderStroke(1.dp, Line2)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("添加连接器 · 粘贴 JSON 清单", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("schema：id/name/category/desc/auth/credentials/tools（参考内置清单格式）", fontSize = 11.sp, color = Ink4, modifier = Modifier.padding(top = 2.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Ink, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)).border(1.dp, Line2, RoundedCornerShape(8.dp)).padding(10.dp).height(140.dp),
                        )
                        if (addErr.isNotBlank()) Text(addErr, fontSize = 12.sp, color = Err, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            Text("取消", fontSize = 13.sp, color = Ink3, modifier = Modifier.clickable { showAdd = false; addErr = "" }.padding(8.dp))
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val err = com.chengyan.app.net.ConnectorManager.addCustom(context, draft)
                                if (err == null) { showAdd = false; addErr = ""; tick++ } else addErr = err
                            }) { Text("保存", color = AccentInk) }
                        }
                    }
                }
            } else {
                Surface(
                    Modifier.fillMaxWidth().clickable { showAdd = true },
                    shape = RoundedCornerShape(12.dp), color = Paper2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(AccentSoft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("＋", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AccentInk)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("添加连接器", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AccentInk)
                            Text("粘贴声明式 JSON 清单，保存后立即出现在目录与 AI 工具表", fontSize = 11.sp, color = Ink3)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectorDetail(context: android.content.Context, p: com.chengyan.app.net.ConnectorManager.Provider, st: com.chengyan.app.net.ConnectorManager.Connector?, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val cur = remember(tick) { com.chengyan.app.net.ConnectorManager.loadAll(context)[p.id] ?: com.chengyan.app.net.ConnectorManager.Connector(id = p.id) }
    val ready = com.chengyan.app.net.ConnectorManager.isReady(p, cur)
    val scope = rememberCoroutineScope()
    // OAuth 授权登录状态机
    var flow by remember { mutableStateOf<com.chengyan.app.net.ConnectorManager.DeviceFlow?>(null) }
    var flowStatus by remember { mutableStateOf("") }

    fun startOauth() {
        scope.launch {
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val df = com.chengyan.app.net.ConnectorManager.startDeviceFlow(p)
                    flow = df; flowStatus = "等待授权"
                    // 打开浏览器
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(df.verificationUri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    // 轮询直到结果（上限 ~3 分钟）
                    repeat((180 / df.intervalSec).coerceAtMost(60)) {
                        kotlinx.coroutines.delay(df.intervalSec * 1000L)
                        val r = com.chengyan.app.net.ConnectorManager.pollDeviceFlow(context, p, df)
                        if (r != "pending") { flowStatus = r; tick++; return@withContext }
                    }
                    flowStatus = "expired"
                }
            }.onFailure { flowStatus = "error：" + it.message }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("‹ 返回目录", fontSize = 13.sp, color = AccentInk, modifier = Modifier.clickable { onBack() })
        }
        // 头卡：身份 + 状态
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Paper2, border = androidx.compose.foundation.BorderStroke(1.dp, Line2)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(if (ready) AccentSoft else Ink.copy(alpha = 0.06f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(p.name.firstOrNull()?.uppercase() ?: "?", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (ready) AccentInk else Ink3)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(if (ready) "已连接" else "未连接", fontSize = 12.sp, color = if (ready) Ok else Ink4)
                }
            }
        }
        Text(p.desc, fontSize = 12.sp, color = Ink3, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(14.dp))

        if (p.oauth != null) {
            // ---- OAuth：授权登录即用 ----
            androidx.compose.material3.Button(
                onClick = { startOauth() },
                enabled = flow == null,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(if (ready) "重新授权登录" else "授权登录", fontSize = 14.sp) }
            if (flowStatus == "error" || flowStatus.startsWith("error")) Text(flowStatus, fontSize = 12.sp, color = Err, modifier = Modifier.padding(top = 6.dp))
            if (flowStatus == "expired") Text("验证码已过期，请重新授权", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 6.dp))
            if (flowStatus == "denied") Text("已取消授权", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 6.dp))
            var manual by remember(p.id) { mutableStateOf(false) }
            Text("手动填 Token（备用）", fontSize = 12.sp, color = AccentInk,
                modifier = Modifier.padding(top = 8.dp).clickable { manual = !manual })
            if (manual) KeyForm(context, p, cur, onSaved = { tick++ })
        } else {
            KeyForm(context, p, cur, onSaved = { tick++ })
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Line2)

        // 工具清单（逐工具勾选暴露）
        Text("暴露给 AI 的工具", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(vertical = 8.dp))
        if (!ready) Text("连接后可勾选", fontSize = 12.sp, color = Ink4)
        p.tools.forEach { t ->
            val full = "conn_${p.id}_${t.name}"
            val on = ready && !cur.disabledTools.contains(full)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = on, onCheckedChange = {
                    com.chengyan.app.net.ConnectorManager.toggleTool(context, p.id, full, !it); tick++
                }, enabled = ready)
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
                    Text(t.desc, fontSize = 11.sp, color = Ink3, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // 授权弹窗：大号用户码 + 自动等待
    flow?.let { df ->
        if (flowStatus == "pending" || flowStatus == "等待授权") {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { flow = null; flowStatus = "" },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(df.verificationUri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("打开授权页", color = AccentInk) }
                },
                dismissButton = { TextButton(onClick = { flow = null; flowStatus = "" }) { Text("取消", color = Ink3) } },
                title = { Text("${p.name} 授权登录", fontSize = 16.sp) },
                text = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("请在浏览器输入此验证码：", fontSize = 12.sp, color = Ink3)
                        Spacer(Modifier.height(8.dp))
                        Text(df.userCode, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AccentInk,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Text("已自动打开授权页，正在等待你完成授权…", fontSize = 12.sp, color = Ink3)
                    }
                },
            )
        }
    }
}

/** 凭据表单（非 OAuth 连接器 / 手动备用）。 */
@Composable
private fun KeyForm(context: android.content.Context, p: com.chengyan.app.net.ConnectorManager.Provider, cur: com.chengyan.app.net.ConnectorManager.Connector, onSaved: () -> Unit) {
    Text("凭据", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    var draft by remember(p.id) { mutableStateOf(cur.creds.toMutableMap()) }
    p.credentials.forEach { f ->
        Text(f.label + if (f.required) " *" else "", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 10.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = draft[f.key] ?: f.def,
            onValueChange = { draft[f.key] = it; draft = draft.toMutableMap() },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)).border(1.dp, Line2, RoundedCornerShape(8.dp)).padding(10.dp)) {
                    if ((draft[f.key] ?: f.def).isBlank()) Text(f.placeholder, fontSize = 12.sp, color = Ink4)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            val clean = draft.filterValues { it.isNotBlank() }
            com.chengyan.app.net.ConnectorManager.upsert(context, cur.copy(creds = clean, enabled = clean.isNotEmpty()))
            onSaved()
        }) { Text("保存并启用", color = AccentInk) }
    }
}

/* ==================== MCP 服务（协议级：URL + 可选 API Key/鉴权头 → 勾选工具） ==================== */

@Composable
private fun McpContent(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh(c: com.chengyan.app.net.McpManager.Connector) {
        scope.launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.chengyan.app.net.McpManager.refresh(context, c)
            }
            tick++
            toast = if (err.isBlank()) "「${c.name}」已连接" else "连接失败：$err"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "接入 MCP 服务器（Streamable HTTP JSON-RPC）。支持可选 API Key（Bearer）或自定义鉴权头；连接后勾选暴露给 AI 的工具。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        val connectors = remember(tick) { com.chengyan.app.net.McpManager.loadAll(context) }
        connectors.forEach { c ->
            val toolCount = runCatching { org.json.JSONArray(c.toolsJson).length() }.getOrDefault(0)
            var expanded by remember(c.id) { mutableStateOf(false) }
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                shape = RoundedCornerShape(12.dp),
                color = Paper2,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if (toolCount > 0) expanded = !expanded }) {
                        Box(
                            Modifier.size(40.dp).background(if (toolCount > 0 && c.enabled) AccentSoft else Ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(c.name.firstOrNull()?.uppercase() ?: "M", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (toolCount > 0 && c.enabled) AccentInk else Ink3)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(
                                    when {
                                        c.lastError.isNotBlank() -> Err
                                        toolCount > 0 && c.enabled -> Ok
                                        else -> Ink4
                                    }, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            }
                            Text(
                                when {
                                    c.lastError.isNotBlank() -> "连接失败"
                                    !c.enabled -> "已停用"
                                    toolCount > 0 -> "已连接 · $toolCount 个工具"
                                    else -> "未连接"
                                },
                                fontSize = 11.sp, color = Ink3, maxLines = 1,
                            )
                        }
                        TextButton(onClick = { refresh(c) }) { Text("连接", fontSize = 13.sp, color = AccentInk) }
                        TextButton(onClick = { com.chengyan.app.net.McpManager.delete(context, c.id); tick++ }) {
                            Text("删除", fontSize = 13.sp, color = Err)
                        }
                    }
                    if (expanded && toolCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Line2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { com.chengyan.app.net.McpManager.upsert(context, c.copy(enabled = !c.enabled)); tick++ }) {
                                Text(if (c.enabled) "停用全部" else "启用", fontSize = 13.sp, color = Ink3)
                            }
                        }
                        Text("暴露给 AI 的工具", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(vertical = 4.dp))
                        val tools = runCatching {
                            val arr = org.json.JSONArray(c.toolsJson)
                            (0 until arr.length()).map { i ->
                                val o = arr.getJSONObject(i)
                                Triple("mcp_" + com.chengyan.app.net.McpManager.sanitizePublic(c.name) + "_" + com.chengyan.app.net.McpManager.sanitizePublic(o.optString("name")), o.optString("name"), o.optString("description"))
                            }
                        }.getOrDefault(emptyList())
                        tools.forEach { (full, name, desc) ->
                            val on = c.enabled && !c.disabledTools.contains(full)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(checked = on, onCheckedChange = {
                                    if (!c.enabled) com.chengyan.app.net.McpManager.upsert(context, c.copy(enabled = true))
                                    com.chengyan.app.net.McpManager.toggleTool(context, c.id, full, !it); tick++
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
                                    if (desc.isNotBlank()) Text(desc, fontSize = 11.sp, color = Ink3, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        var showAdd by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text("添加 MCP 服务器", fontSize = 14.sp, color = AccentInk)
        }
        toast?.let { Text(it, fontSize = 12.sp, color = AccentInk, modifier = Modifier.padding(top = 8.dp)) }

        if (showAdd) {
            var name by remember { mutableStateOf("") }
            var url by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf("") }
            var hName by remember { mutableStateOf("") }
            var hValue by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAdd = false },
                confirmButton = {
                    TextButton(onClick = {
                        if (name.isNotBlank() && url.isNotBlank()) {
                            // API Key → 默认 Bearer 到 Authorization；自定义头优先
                            var hn = hName.trim(); var hv = hValue.trim()
                            if (apiKey.isNotBlank()) {
                                if (hn.isBlank()) hn = "Authorization"
                                if (hv.isBlank()) hv = "Bearer ${apiKey.trim()}"
                            }
                            val nc = com.chengyan.app.net.McpManager.Connector(
                                id = java.util.UUID.randomUUID().toString(),
                                name = name.trim(), url = url.trim(),
                                headerName = hn, headerValue = hv,
                            )
                            com.chengyan.app.net.McpManager.upsert(context, nc); tick++
                            refresh(nc)
                            showAdd = false
                        }
                    }) { Text("保存并连接", color = AccentInk) }
                },
                dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消", color = Ink3) } },
                title = { Text("添加 MCP 服务器", fontSize = 16.sp) },
                text = {
                    Column {
                        McpField("名称", name) { name = it }
                        McpField("URL（如 https://…/mcp）", url) { url = it }
                        McpField("API Key（可选，自动作 Bearer）", apiKey) { apiKey = it }
                        McpField("自定义鉴权头名（可选，优先于 API Key）", hName) { hName = it }
                        McpField("自定义鉴权头值（可选）", hValue) { hValue = it }
                    }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun McpField(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 8.dp))
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(Paper2, RoundedCornerShape(8.dp)).padding(10.dp)) { inner() }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ==================== Skills（完整架构：SKILL.md + 启用开关 + 脚本目录） ==================== */

@Composable
private fun SkillsContent(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    val skills = remember(tick) { com.chengyan.app.data.SkillManager.list(context) }
    var editing by remember(tick) { mutableStateOf<com.chengyan.app.data.SkillManager.Skill?>(null) }
    var adding by remember { mutableStateOf(false) }

    if (adding || editing != null) {
        val existing = editing
        var id by remember(existing) { mutableStateOf(existing?.id ?: "") }
        var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
        var desc by remember(existing) { mutableStateOf(existing?.desc ?: "") }
        var body by remember(existing) { mutableStateOf(existing?.body ?: "") }
        var err by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("‹ 返回列表", fontSize = 13.sp, color = AccentInk,
                    modifier = Modifier.clickable { adding = false; editing = null })
            }
            OutlinedField("ID（英文/数字，做目录名）", id) { if (existing == null) id = it }
            OutlinedField("名称", name) { name = it }
            OutlinedField("一句话描述（何时用）", desc) { desc = it }
            Text("规程正文（Markdown；任务匹配时注入系统提示词）", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 10.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = body, onValueChange = { body = it },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .border(1.dp, Line2, RoundedCornerShape(8.dp)).padding(10.dp).height(220.dp),
            )
            if (err.isNotBlank()) Text(err, fontSize = 12.sp, color = Err, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    Text("删除", fontSize = 13.sp, color = Err, modifier = Modifier.clickable {
                        com.chengyan.app.data.SkillManager.delete(context, existing.id); editing = null; tick++
                    }.padding(8.dp))
                }
                TextButton(onClick = {
                    val e = com.chengyan.app.data.SkillManager.save(context, id, name, desc, body)
                    if (e == null) { adding = false; editing = null; tick++ } else err = e
                }) { Text("保存", color = AccentInk) }
            }
            if (existing?.hasScripts == true) {
                Text("此技能目录含 scripts/，可让 AI 用 linux 工具直接运行", fontSize = 11.sp, color = Ink4)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Skill = 一份 SKILL.md 规程（name/desc/正文）。启用后任务匹配时注入系统提示词，AI 照规程执行；scripts/ 子目录里的脚本可被 AI 直接运行。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        skills.forEach { s ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = s },
                shape = RoundedCornerShape(12.dp), color = Paper2,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (s.enabled) Ok else Ink4, androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            if (s.hasScripts) { Spacer(Modifier.width(6.dp)); Text("含脚本", fontSize = 10.sp, color = AccentInk) }
                        }
                        if (s.desc.isNotBlank()) Text(s.desc, fontSize = 11.sp, color = Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = s.enabled, onCheckedChange = {
                        com.chengyan.app.data.SkillManager.setEnabled(context, s.id, it); tick++
                    })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            Modifier.fillMaxWidth().clickable { adding = true },
            shape = RoundedCornerShape(12.dp), color = Paper2,
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(AccentSoft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("＋", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AccentInk)
                }
                Spacer(Modifier.width(12.dp))
                Text("新建 Skill", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AccentInk)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OutlinedField(label: String, value: String, onValue: (String) -> Unit) {
    Text(label, fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 10.dp))
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValue,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
            .border(1.dp, Line2, RoundedCornerShape(8.dp)).padding(10.dp),
    )
}

/* ==================== 工作区（SAF 选任意目录 / 默认应用内） ==================== */

@Composable
private fun WorkspaceContent(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && com.chengyan.app.data.WorkspaceManager.onPicked(context, uri)) tick++
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "工作区是 AI 文件操作的根目录：workspace 工具的 list/read/write/mkdir/delete 都落在这里。默认在应用内部；选择手机任意文件夹后，AI 的产出直接进入你选的目录（SAF 授权，重启仍有效）。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(16.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Paper2, border = androidx.compose.foundation.BorderStroke(1.dp, Line2)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(AccentSoft, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Text("作", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AccentInk)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前工作区", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(remember(tick) { com.chengyan.app.data.WorkspaceManager.displayName(context) }, fontSize = 12.sp, color = Ink3)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(46.dp)) {
            Text("选择手机文件夹…", fontSize = 14.sp)
        }
        TextButton(onClick = { com.chengyan.app.data.WorkspaceManager.reset(context); tick++ }, modifier = Modifier.align(Alignment.End)) {
            Text("恢复默认（应用内）", color = Ink3)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ==================== 无线调试 ADB（配对码 → 白名单 → shell 控制） ==================== */

@Composable
private fun AdbContent(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    val st = remember(tick) { com.chengyan.app.net.AdbManager.state(context) }
    val scope = rememberCoroutineScope()
    var pairAddr by remember { mutableStateOf(st.host ?: "") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connHost by remember { mutableStateOf(st.host ?: "") }
    var connPort by remember { mutableStateOf(st.port.takeIf { it > 0 }?.toString() ?: "") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Android 11+ 官方无线调试：开发者选项 → 无线调试 → 「使用配对码配对设备」→ 填下方地址与 6 位码配对；配对后填「无线调试」主界面的地址端口连接，即可 shell 级控制本机（后台虚拟屏/无障碍之外的第三通道）。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text("步骤一：配对（一次性）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        OutlinedField("配对地址（如 192.168.1.5）", pairAddr) { pairAddr = it }
        OutlinedField("配对端口（配对页显示的端口）", pairPort) { pairPort = it }
        OutlinedField("6 位配对码", code) { code = it }
        TextButton(
            enabled = !busy && pairAddr.isNotBlank() && pairPort.isNotBlank() && code.length == 6,
            onClick = {
                busy = true
                scope.launch {
                    msg = com.chengyan.app.net.AdbManager.pair(context, pairAddr.trim(), pairPort.trim().toIntOrNull() ?: 0, code.trim())
                    busy = false; tick++
                }
            },
        ) { Text(if (busy) "配对中…" else "配对", color = AccentInk) }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Line2)
        Spacer(Modifier.height(10.dp))
        Text("步骤二：连接（端口每次开关无线调试会变）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        OutlinedField("连接地址", connHost) { connHost = it }
        OutlinedField("连接端口（无线调试页显示的端口）", connPort) { connPort = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy && connHost.isNotBlank() && connPort.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    msg = com.chengyan.app.net.AdbManager.connect(context, connHost.trim(), connPort.trim().toIntOrNull() ?: 0)
                    busy = false; tick++
                }
            }) { Text("连接", color = AccentInk) }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    msg = com.chengyan.app.net.AdbManager.shell(context, "echo adb-ok \$(whoami) \$(id -u)")
                    busy = false
                }
            }) { Text("测试 shell", color = AccentInk) }
        }
        if (st.paired) Text("已配对 · 公钥指纹 ${com.chengyan.app.net.adb.AdbKeys.fingerprint(context)}", fontSize = 11.sp, color = Ok, modifier = Modifier.padding(top = 8.dp))
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Ink2, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
    }
}

/* ==================== 后台虚拟屏（Shizuku / ADB 双通道） ==================== */

@Composable
private fun VScreenContent(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var info by remember(tick) { mutableStateOf<com.chengyan.app.net.VirtualScreenManager.Info?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() { info = com.chengyan.app.net.VirtualScreenManager.info(context) }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "AI 在一个看不见的独立屏（720x1600@320）上打开并操作应用，不弹到你面前、不干扰你用手机。通道：Shizuku（已授权即可）或 无线调试 ADB，二者有其一即可；AI 侧工具为 virtual_screen。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(14.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Paper2, border = androidx.compose.foundation.BorderStroke(1.dp, Line2)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(if (info != null) AccentSoft else Ink.copy(alpha = 0.06f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Text("屏", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (info != null) AccentInk else Ink3)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (info != null) "虚拟屏运行中" else "虚拟屏未创建", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(info?.let { "displayId=${it.id} · ${it.w}x${it.h}@${it.dpi}" } ?: "点下方按钮创建", fontSize = 12.sp, color = Ink3)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Button(
                enabled = !busy && info == null,
                onClick = { busy = true; scope.launch {
                    runCatching { com.chengyan.app.net.VirtualScreenManager.create(context) }.onSuccess { info = it; msg = "" }
                        .onFailure { msg = "创建失败：${it.message}" }
                    busy = false; tick++
                } },
                modifier = Modifier.weight(1f),
            ) { Text("创建虚拟屏", fontSize = 13.sp) }
            OutlinedButton(
                enabled = !busy && info != null,
                onClick = { busy = true; scope.launch {
                    msg = runCatching { com.chengyan.app.net.VirtualScreenManager.destroy(context) }.getOrElse { "销毁失败：${it.message}" }
                    refresh(); busy = false; tick++
                } },
                modifier = Modifier.weight(1f),
            ) { Text("销毁", color = Err) }
        }
        Text(
            "也可以直接对 AI 说：「用后台虚拟屏打开微信给我抢个红包」——它会自动 create/launch/操作/截图。",
            fontSize = 11.sp, color = Ink4, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp),
        )
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Ink2, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
    }
}
