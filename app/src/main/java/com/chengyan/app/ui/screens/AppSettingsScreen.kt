package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 设置主页（澄言原创）：侧边栏「设置」进入。
 * 分区：模型 / 对话 / 系统能力与权限 / 连接器 / MCP 服务。⋮ 菜单只保留对话动作。
 */

@Composable
fun AppSettingsSheet(
    context: android.content.Context,
    onOpenModelConfig: () -> Unit,
    onOpenChatPrefs: () -> Unit,
    onCompactConversation: () -> Unit,
    onClose: () -> Unit,
) {
    var showConnectors by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("CHENGYAN · SETTINGS", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                        Text("设置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Ink)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("完成", color = AccentInk) }
                }
                Spacer(Modifier.height(14.dp))

                SettingsRow("模型配置", "服务商 / 密钥 / 模型档案 / 参数") { onOpenModelConfig() }
                SettingsRow("对话设置", "系统提示词 / 思考模式 / 温度") { onOpenChatPrefs() }
                SettingsRow("压缩上下文", "把当前对话旧消息摘要成笔记") { onCompactConversation() }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Line2)
                Spacer(Modifier.height(10.dp))
                SettingsRow("连接器", "GitHub / 高德地图 等应用账号，填 key 即用") { showConnectors = true }
                SettingsRow("MCP 服务", "接入 MCP 服务器（Streamable HTTP），勾选暴露给 AI 的工具") { showMcp = true }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Line2)
                Spacer(Modifier.height(10.dp))
                Text("系统能力与权限", fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                SystemCapabilitySection(context)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showConnectors) {
        ConnectorScreen(context = context, onBack = { showConnectors = false })
    }
    if (showMcp) {
        McpScreen(context = context, onBack = { showMcp = false })
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
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = Ink4)
    }
}

/* ==================== 连接器独立界面 ==================== */

@Composable
fun ConnectorScreen(context: android.content.Context, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("连接器", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("完成", color = AccentInk) }
                }
                Text(
                    "连接第三方应用（填 key 即用），AI 可调用其真实能力。勾选暴露给 AI 的工具。",
                    fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                val states = remember(tick) { com.chengyan.app.net.ConnectorManager.loadAll(context) }
                com.chengyan.app.net.ConnectorManager.catalog().forEach { item ->
                    val st = states[item.id] ?: com.chengyan.app.net.ConnectorManager.Connector(id = item.id)
                    val connected = st.enabled && st.keyValue.isNotBlank()
                    var expanded by remember(item.id) { mutableStateOf(connected) }
                    var showKey by remember { mutableStateOf(false) }
                    var keyText by remember(item.id) { mutableStateOf(st.keyValue) }
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Paper2,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (connected) "已连接 · ${item.tools.size} 个工具" else "未连接",
                                            fontSize = 11.sp, color = if (connected) Ok else Ink4,
                                        )
                                    }
                                    Text(item.desc, fontSize = 12.sp, color = Ink3, lineHeight = 16.sp)
                                }
                                TextButton(onClick = { showKey = true }) {
                                    Text(if (st.keyValue.isBlank()) "填 key" else "改 key", fontSize = 13.sp, color = AccentInk)
                                }
                                TextButton(
                                    onClick = {
                                        com.chengyan.app.net.ConnectorManager.upsert(context, st.copy(enabled = !st.enabled)); tick++
                                    },
                                    enabled = st.keyValue.isNotBlank(),
                                ) { Text(if (st.enabled) "停用" else "启用", fontSize = 13.sp, color = Ink3) }
                            }
                            if (expanded && connected) {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = Line2)
                                Text("暴露给 AI 的工具", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(vertical = 6.dp))
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
                    if (showKey) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showKey = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.chengyan.app.net.ConnectorManager.upsert(context, st.copy(keyValue = keyText.trim(), enabled = keyText.isNotBlank()))
                                    tick++; showKey = false
                                }) { Text("保存", color = AccentInk) }
                            },
                            dismissButton = { TextButton(onClick = { showKey = false }) { Text("取消", color = Ink3) } },
                            title = { Text("连接 ${item.name}", fontSize = 16.sp) },
                            text = {
                                Column {
                                    Text(item.keyLabel, fontSize = 12.sp, color = Ink3)
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = keyText,
                                        onValueChange = { keyText = it },
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                                        decorationBox = { inner ->
                                            Box(Modifier.fillMaxWidth().background(Paper2, RoundedCornerShape(8.dp)).padding(10.dp)) { inner() }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/* ==================== MCP 服务独立界面 ==================== */

@Composable
fun McpScreen(context: android.content.Context, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh(c: com.chengyan.app.net.McpManager.Connector, after: () -> Unit = {}) {
        scope.launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.chengyan.app.net.McpManager.refresh(context, c)
            }
            tick++
            toast = if (err.isBlank()) "「${c.name}」已连接" else "连接失败：$err"
            after()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("MCP 服务", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("完成", color = AccentInk) }
                }
                Text(
                    "接入 MCP 服务器（Streamable HTTP JSON-RPC）。连接后展开可勾选暴露给 AI 的工具。",
                    fontSize = 12.sp, color = Ink3, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                val connectors = remember(tick) { com.chengyan.app.net.McpManager.loadAll(context) }
                connectors.forEach { c ->
                    val toolCount = runCatching { org.json.JSONArray(c.toolsJson).length() }.getOrDefault(0)
                    var expanded by remember(c.id) { mutableStateOf(false) }
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Paper2,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f).clickable { if (toolCount > 0) expanded = !expanded }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            when {
                                                c.lastError.isNotBlank() -> "连接失败"
                                                !c.enabled -> "已停用"
                                                toolCount > 0 -> "$toolCount 个工具"
                                                else -> "未连接"
                                            },
                                            fontSize = 11.sp,
                                            color = when {
                                                c.lastError.isNotBlank() -> Err
                                                toolCount > 0 -> Ok
                                                else -> Ink4
                                            },
                                        )
                                    }
                                    Text(c.url, fontSize = 11.sp, color = Ink4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { refresh(c) }) { Text("连接", fontSize = 13.sp, color = AccentInk) }
                                TextButton(onClick = {
                                    com.chengyan.app.net.McpManager.upsert(context, c.copy(enabled = !c.enabled)); tick++
                                }) { Text(if (c.enabled) "停用" else "启用", fontSize = 13.sp, color = Ink3) }
                                TextButton(onClick = { com.chengyan.app.net.McpManager.delete(context, c.id); tick++ }) {
                                    Text("删除", fontSize = 13.sp, color = Err)
                                }
                            }
                            if (expanded && toolCount > 0) {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = Line2)
                                Text("暴露给 AI 的工具", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(vertical = 6.dp))
                                val tools = runCatching {
                                    val arr = org.json.JSONArray(c.toolsJson)
                                    (0 until arr.length()).map { i ->
                                        val o = arr.getJSONObject(i)
                                        "mcp_" + com.chengyan.app.net.McpManager.sanitizePublic(c.name) + "_" + com.chengyan.app.net.McpManager.sanitizePublic(o.optString("name")) to o.optString("description")
                                    }
                                }.getOrDefault(emptyList())
                                tools.forEach { (full, desc) ->
                                    val on = !c.disabledTools.contains(full)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Checkbox(checked = on, onCheckedChange = {
                                            com.chengyan.app.net.McpManager.toggleTool(context, c.id, full, !it); tick++
                                        })
                                        Column(Modifier.weight(1f)) {
                                            Text(full.substringAfterLast('_'), fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
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
                    var hName by remember { mutableStateOf("") }
                    var hValue by remember { mutableStateOf("") }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showAdd = false },
                        confirmButton = {
                            TextButton(onClick = {
                                if (name.isNotBlank() && url.isNotBlank()) {
                                    val nc = com.chengyan.app.net.McpManager.Connector(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = name.trim(), url = url.trim(),
                                        headerName = hName.trim(), headerValue = hValue.trim(),
                                    )
                                    com.chengyan.app.net.McpManager.upsert(context, nc); tick++
                                    refresh(nc) { }
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
                                McpField("鉴权头名（可选）", hName) { hName = it }
                                McpField("鉴权头值（可选）", hValue) { hValue = it }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
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
