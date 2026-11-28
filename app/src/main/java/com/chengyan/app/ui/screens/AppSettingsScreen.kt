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
                        SettingsRow("连接器", "GitHub / 高德地图 等应用账号，填 key 即用") { stack.add("connectors") }
                        SettingsRow("MCP 服务", "接入 MCP 服务器，勾选暴露给 AI 的工具") { stack.add("mcp") }
                        Spacer(Modifier.height(24.dp))
                    }
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

/* ==================== 连接器（应用账号型：选应用 → 填 key → 勾选工具） ==================== */

@Composable
private fun ConnectorContent(context: android.content.Context, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val states = remember(tick) { com.chengyan.app.net.ConnectorManager.loadAll(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "连接器 = 连接第三方应用的账号。选择应用 → 填 key → 启用 → 勾选暴露给 AI 的工具。",
            fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        com.chengyan.app.net.ConnectorManager.catalog().forEach { item ->
            val st = states[item.id] ?: com.chengyan.app.net.ConnectorManager.Connector(id = item.id)
            val connected = st.enabled && st.keyValue.isNotBlank()
            var expanded by remember(item.id) { mutableStateOf(connected) }
            var showKey by remember(item.id) { mutableStateOf(false) }
            var keyText by remember(item.id) { mutableStateOf(st.keyValue) }
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                shape = RoundedCornerShape(12.dp),
                color = Paper2,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
            ) {
                Column(Modifier.padding(14.dp)) {
                    // 头行：点卡片展开工具勾选
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    ) {
                        // 应用头像位：首字母圆徽
                        Box(
                            Modifier.size(40.dp).background(if (connected) AccentSoft else Ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.name.firstOrNull()?.uppercase() ?: "?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (connected) AccentInk else Ink3)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(if (connected) Ok else Ink4, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            }
                            Text(
                                if (connected) "已连接 · ${item.tools.size} 个工具" else if (st.keyValue.isNotBlank()) "已填 key · 未启用" else "未连接 · ${item.desc.take(26)}",
                                fontSize = 11.sp, color = Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = AccentInk)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Line2)
                        // key 输入（应用内直接填，不用弹窗）
                        Text(item.keyLabel, fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 10.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = keyText,
                            onValueChange = { keyText = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)).border(1.dp, Line2, RoundedCornerShape(8.dp)).padding(10.dp)) { inner() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                com.chengyan.app.net.ConnectorManager.upsert(context, st.copy(keyValue = keyText.trim(), enabled = keyText.isNotBlank()))
                                tick++
                            }) { Text("保存 key", color = AccentInk) }
                            TextButton(
                                onClick = { com.chengyan.app.net.ConnectorManager.upsert(context, st.copy(enabled = !st.enabled)); tick++ },
                                enabled = st.keyValue.isNotBlank(),
                            ) { Text(if (st.enabled) "停用" else "启用", color = Ink3) }
                        }
                        if (connected) {
                            Text("暴露给 AI 的工具", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(vertical = 4.dp))
                            item.tools.forEach { (tool, desc, _) ->
                                val full = "conn_${item.id}_$tool"
                                val on = !st.disabledTools.contains(full)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(checked = on, onCheckedChange = {
                                        com.chengyan.app.net.ConnectorManager.toggleTool(context, item.id, full, !it); tick++
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(tool, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
                                        Text(desc, fontSize = 11.sp, color = Ink3, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
