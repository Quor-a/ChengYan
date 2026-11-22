@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.ModelConfig
import com.chengyan.app.data.PRESETS
import com.chengyan.app.net.OpenAiClient
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: ModelConfig,
    vm: com.chengyan.app.ui.ChatViewModel,
    onSave: (ModelConfig) -> Unit,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(config.url) }
    var key by remember { mutableStateOf(config.key) }
    var model by remember { mutableStateOf(config.model) }
    var sys by remember { mutableStateOf(config.sysPrompt) }
    var temp by remember { mutableStateOf(config.temperature) }
    var thinking by remember { mutableStateOf(config.thinking) }
    var keyVisible by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelFilter by remember { mutableStateOf("") }
    var fetchingModels by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    vm.restoreBackup(it.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("模型配置", fontFamily = FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Text("兼容 OpenAI 接口格式 · 配置仅保存在本机", style = MaterialTheme.typography.bodySmall, color = Ink3, modifier = Modifier.padding(bottom = 16.dp))

            Text("常用服务商", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PRESETS.take(3).forEach { p ->
                    PresetChip(p.name, url == p.url && model == p.model) {
                        url = p.url; model = p.model; testResult = null
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 7.dp)) {
                PRESETS.drop(3).forEach { p ->
                    PresetChip(p.name, url == p.url && model == p.model) {
                        url = p.url; model = p.model; testResult = null
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            Field("接口地址 Base URL", url, { url = it }, "https://api.deepseek.com/v1")
            Text("通常以 /v1 结尾，不带尾斜杠", style = MaterialTheme.typography.labelSmall, color = Ink4, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

            Field("API Key", key, { key = it; testResult = null }, "sk-…",
                visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall, color = Ink3)
                    }
                }
            )
            Spacer(Modifier.height(12.dp))

            Field("模型名称 Model", model, { model = it; testResult = null }, "deepseek-chat")
            TextButton(
                onClick = {
                    val c = ModelConfig(url.trim().trimEnd('/'), key.trim(), model.trim())
                    if (!c.url.startsWith("http") || c.key.isBlank()) {
                        testResult = false to "请先填写接口地址和 API Key 再获取模型列表"
                        return@TextButton
                    }
                    fetchingModels = true
                    scope.launch {
                        OpenAiClient().listModels(c).onSuccess { modelList = it; modelsOpen = true }
                            .onFailure { testResult = false to "获取模型列表失败：${it.message ?: "网络错误"}" }
                        fetchingModels = false
                    }
                },
                enabled = !fetchingModels
            ) { Text(if (fetchingModels) "获取中…" else "获取服务端模型列表") }
            Spacer(Modifier.height(12.dp))

            Text("系统提示词（可选）", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
            OutlinedTextField(
                value = sys, onValueChange = { sys = it },
                placeholder = { Text("例如：你是一位简洁专业的中文助手。", style = MaterialTheme.typography.bodySmall, color = Ink4) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("温度 Temperature", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.weight(1f))
                Text("%.1f".format(temp), style = MaterialTheme.typography.labelMedium, color = AccentInk, fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = temp, onValueChange = { temp = (it * 10).toInt() / 10f },
                valueRange = 0f..2f, steps = 19,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent2, inactiveTrackColor = Paper3)
            )
            Spacer(Modifier.height(6.dp))

            Spacer(Modifier.height(18.dp))

            Text("配置档案", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
            val profileName = remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profileName.value, onValueChange = { profileName.value = it },
                    placeholder = { Text("档案名，如「DeepSeek 主力」", style = MaterialTheme.typography.bodySmall, color = Ink4) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
                Button(
                    onClick = { vm.saveProfile(profileName.value.trim()); profileName.value = "" },
                    enabled = profileName.value.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("保存当前") }
            }
            vm.profiles.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.weight(1f).clickable { vm.applyProfile(p) }) {
                        Text(p.name, style = MaterialTheme.typography.bodySmall,
                            color = if (p.url == config.url && p.model == config.model) AccentInk else Ink)
                        Text("${p.model} · ${p.url.removePrefix("https://").removePrefix("http://").take(30)}",
                            style = MaterialTheme.typography.labelSmall, color = Ink3)
                    }
                    TextButton(onClick = { vm.deleteProfile(p.id) }) {
                        Text("删除", style = MaterialTheme.typography.labelSmall, color = Err)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            Text("备份与恢复", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val json = vm.backupAll()
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "澄言备份")
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "导出备份"))
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2)
                ) { Text("导出全部对话") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2)
                ) { Text("从文件恢复") }
            }
            Spacer(Modifier.height(6.dp))

            Text("思考强度", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Text(
                "关闭：直接回答（需模型支持）· 默认：由模型决定 · 较低/较高：更快的推理或更深入的推理",
                style = MaterialTheme.typography.labelSmall, color = Ink4, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("off" to "关闭", "default" to "默认", "low" to "较低", "high" to "较高").forEach { (value, label) ->
                    PresetChip(label, thinking == value) { thinking = value }
                }
            }
            Spacer(Modifier.height(6.dp))

            testResult?.let { (ok, msg) ->
                Surface(shape = RoundedCornerShape(10.dp), color = if (ok) Color(0xFFEEF3EA) else Color(0xFFFDF0EC),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = if (ok) Ok else Err, modifier = Modifier.padding(12.dp), lineHeight = 20.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(Modifier.padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val c = ModelConfig(url.trim().trimEnd('/'), key.trim(), model.trim(), sys.trim(), temp)
                        if (!c.url.startsWith("http") || model.isBlank()) {
                            testResult = false to "请先填写正确的接口地址和模型名称"
                            return@OutlinedButton
                        }
                        testing = true; testResult = null
                        scope.launch {
                            val client = OpenAiClient()
                            testResult = client.test(c).fold({ true to "✓ $it" }, { false to "✗ ${it.message ?: "连接失败"}" })
                            testing = false
                        }
                    },
                    enabled = !testing,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2)
                ) { Text(if (testing) "测试中…" else "测试连接") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(ModelConfig(url.trim().trimEnd('/'), key.trim(), model.trim(), sys.trim(), temp, thinking))
                        onBack()
                    },
                    enabled = url.isNotBlank() && model.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("保存", letterSpacing = 2.sp) }
            }
        }
    }

    if (modelsOpen) {
        AlertDialog(
            onDismissRequest = { modelsOpen = false },
            title = { Text("选择模型") },
            text = {
                Column {
                    OutlinedTextField(
                        value = modelFilter, onValueChange = { modelFilter = it },
                        placeholder = { Text("筛选…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    val shown = modelList.filter { it.contains(modelFilter, ignoreCase = true) }.take(200)
                    if (shown.isEmpty()) {
                        Text("无匹配模型", style = MaterialTheme.typography.bodySmall, color = Ink3,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (name == model) AccentInk else Ink,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { model = name; modelsOpen = false }
                                    .padding(horizontal = 6.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelsOpen = false }) { Text("关闭", color = Ink2) }
            }
        )
    }
}

@Composable
private fun PresetChip(name: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) AccentSoft else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Accent2 else Line),
        onClick = onClick
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall,
            color = if (on) AccentInk else Ink2,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Ink4) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Paper, focusedContainerColor = Card,
    unfocusedBorderColor = Line, focusedBorderColor = Accent2
)
