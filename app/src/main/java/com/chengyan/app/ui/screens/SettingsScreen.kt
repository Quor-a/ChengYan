@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.chengyan.app.ui.screens

/*
 * 模型配置页 —— 云模型配置层完整移植自 Quor-a/ZorvAI：
 * - ApiProviderConfigs（33 家服务商预设 + URL 自动识别 + 端点选项 + 回环免 Key）
 * - QuroModelListFetcher（/models + Ollama /api/tags 回退 + context_length 解析）
 * - QuroModelConfigRepository（配置持久化 + 模型列表缓存，按 baseUrl 失效）
 * - QuroSavedProfileRepository（命名预设保存/切换/删除）
 * - QuroCustomProviderRepository（用户自建供应商）
 * - QuroMultiProviderRepository（健康状态，列表绿/红点）
 * 界面视觉为澄言原创（暖纸 × 墨色 × 陶土橙），交互逻辑对齐 ZorvAI。
 */

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
import com.chengyan.app.core.model.ApiProviderConfigs
import com.chengyan.app.core.model.ApiProviderType
import com.chengyan.app.core.network.QuroModelInfo
import com.chengyan.app.core.network.QuroModelListFetcher
import com.chengyan.app.core.network.QuroModelListResult
import com.chengyan.app.data.ModelConfig
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
    var modelList by remember { mutableStateOf<List<QuroModelInfo>>(emptyList()) }
    var modelFilter by remember { mutableStateOf("") }
    var fetchingModels by remember { mutableStateOf(false) }

    // 生成参数（QuroModelConfig 字段）
    var maxTokens by remember { mutableStateOf(vm.quroConfig.maxTokens.toString()) }
    var contextWindow by remember { mutableStateOf(vm.quroConfig.contextWindow.toString()) }
    var compressThreshold by remember { mutableStateOf(vm.quroConfig.contextCompressThreshold.toString()) }
    var softTrimThreshold by remember { mutableStateOf(vm.quroConfig.contextSoftTrimThreshold.toString()) }
    var maxToolRounds by remember { mutableStateOf(vm.quroConfig.maxToolRounds.toString()) }
    var enableTools by remember { mutableStateOf(vm.quroConfig.enableTools) }

    // 预设 / 自建供应商
    var profileName by remember { mutableStateOf("") }
    var addProviderOpen by remember { mutableStateOf(false) }
    var newProvName by remember { mutableStateOf("") }
    var newProvUrl by remember { mutableStateOf("") }
    var newProvModel by remember { mutableStateOf("") }

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

    /** 当前 provider 枚举（跟随 quroConfig，URL 变化时自动识别 —— detectProviderFromUrl）。 */
    val providerType = ApiProviderConfigs.detectProviderFromUrl(url)
    val providerHealth = remember(vm.quroProfiles) {
        vm.quroMultiProviderRepo.getAllConfigs().associate { it.id to it.healthStatus.name }
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
            Text("兼容 OpenAI 接口格式 · 配置仅保存在本机 · 配置层移植自 ZorvAI", style = MaterialTheme.typography.bodySmall, color = Ink3, modifier = Modifier.padding(bottom = 16.dp))

            // ---- 服务商（ApiProviderConfigs，点击自动填入默认端点与默认模型） ----
            Text("服务商（点击自动填入端点与默认模型）", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ApiProviderType.values()
                    .filter { it != ApiProviderType.MNN && it != ApiProviderType.LLAMA_CPP } // 澄言为纯云模型客户端，无本地推理引擎
                    .forEach { pt ->
                        val cfg = ApiProviderConfigs.get(pt)
                        val selected = providerType == pt && (cfg.defaultApiEndpoint.isBlank() || url == cfg.defaultApiEndpoint)
                        PresetChip(pt.name.lowercase().replace('_', ' '), selected) {
                            val pc = ApiProviderConfigs.get(pt)
                            if (pc.defaultApiEndpoint.isNotBlank()) url = pc.defaultApiEndpoint
                            if (pc.defaultModelName.isNotBlank()) model = pc.defaultModelName
                            testResult = null
                        }
                    }
            }
            Spacer(Modifier.height(18.dp))

            Field("接口地址 Base URL", url, { url = it.trim(); testResult = null }, "https://api.deepseek.com/v1")
            Text(
                "已识别：${providerType.name} · 通常以 /v1 结尾，不带尾斜杠" +
                        if (!ApiProviderConfigs.requiresApiKey(providerType.name, url)) " · 本地回环免密钥" else "",
                style = MaterialTheme.typography.labelSmall, color = Ink4, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

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
                    if (!url.startsWith("http") || (key.isBlank() && ApiProviderConfigs.requiresApiKey(providerType.name, url))) {
                        testResult = false to "请先填写接口地址和 API Key 再获取模型列表"
                        return@TextButton
                    }
                    fetchingModels = true
                    scope.launch {
                        // 先读缓存（QuroModelConfigRepository.loadModelListCache，按 baseUrl 失效）
                        val cached = vm.quroConfigRepo.loadModelListCache(url.trim().trimEnd('/'))
                        if (cached is QuroModelListResult.Success) {
                            modelList = cached.models; modelsOpen = true; fetchingModels = false
                            return@launch
                        }
                        // 缓存失效 → QuroModelListFetcher.fetch（/models + Ollama /api/tags 回退）
                        val result = QuroModelListFetcher().fetch(url.trim().trimEnd('/'), key.trim())
                        when (result) {
                            is QuroModelListResult.Success -> {
                                vm.quroConfigRepo.saveModelListCache(url.trim().trimEnd('/'), result)
                                modelList = result.models; modelsOpen = true
                            }
                            is QuroModelListResult.Error -> testResult = false to "获取模型列表失败：${result.message}"
                        }
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

            // ---- 生成参数（QuroModelConfig 全套字段） ----
            Text("生成参数", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = maxTokens, onValueChange = { maxTokens = it.filter { ch -> ch.isDigit() } },
                    label = { Text("输出上限 tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
                OutlinedTextField(
                    value = maxToolRounds, onValueChange = { maxToolRounds = it.filter { ch -> ch.isDigit() } },
                    label = { Text("工具轮次上限(0=不限)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = contextWindow, onValueChange = { contextWindow = it.filter { ch -> ch.isDigit() } },
                    label = { Text("上下文窗口 tokens", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
                OutlinedTextField(
                    value = compressThreshold, onValueChange = { compressThreshold = it.filter { ch -> ch.isDigit() } },
                    label = { Text("压缩阀值 %", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = softTrimThreshold, onValueChange = { softTrimThreshold = it.filter { ch -> ch.isDigit() } },
                    label = { Text("软精简阀值 %", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("工具调用", style = MaterialTheme.typography.labelSmall, color = Ink2, modifier = Modifier.weight(1f))
                    Switch(checked = enableTools, onCheckedChange = { enableTools = it }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
            }
            Text(
                "上下文用量达压缩阀值时从最旧消息裁剪（保留 system 与最新对话）；达软精简阀值时就地精简超长工具输出 · 输出上限设 0 表示不下发",
                style = MaterialTheme.typography.labelSmall, color = Ink4, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))

            // ---- 配置档案（QuroSavedProfileRepository） ----
            Text("配置档案", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profileName, onValueChange = { profileName = it },
                    placeholder = { Text("档案名，如「DeepSeek 主力」", style = MaterialTheme.typography.bodySmall, color = Ink4) },
                    modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp), singleLine = true, colors = fieldColors()
                )
                Button(
                    onClick = { vm.saveQuroProfile(profileName.trim()); profileName = "" },
                    enabled = profileName.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("保存当前") }
            }
            vm.quroProfiles.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    val dot = when (providerHealth[p.id]) { "HEALTHY" -> Ok; "FAILED" -> Err; else -> Ink4 }
                    Box(Modifier.padding(end = 8.dp).size(8.dp).background(dot, RoundedCornerShape(50)))
                    Column(Modifier.weight(1f).clickable { vm.applyQuroProfile(p) }) {
                        Text("${p.name} · ${p.provider.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.baseUrl == url && p.model == model) AccentInk else Ink)
                        Text("${p.model} · ${p.baseUrl.removePrefix("https://").removePrefix("http://").take(30)}",
                            style = MaterialTheme.typography.labelSmall, color = Ink3)
                    }
                    TextButton(onClick = { vm.deleteQuroProfile(p.id) }) {
                        Text("删除", style = MaterialTheme.typography.labelSmall, color = Err)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ---- 自建供应商（QuroCustomProviderRepository） ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text("我的供应商", style = MaterialTheme.typography.labelMedium, color = Ink2, modifier = Modifier.weight(1f))
                TextButton(onClick = { addProviderOpen = true }) { Text("+ 添加", style = MaterialTheme.typography.labelSmall, color = AccentInk) }
            }
            vm.quroCustomProviders.forEach { cp ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.weight(1f).clickable {
                        url = cp.baseUrl
                        if (cp.defaultModel.isNotBlank()) model = cp.defaultModel
                        testResult = null
                    }) {
                        Text(cp.name, style = MaterialTheme.typography.bodySmall, color = Ink)
                        Text("${cp.defaultModel.ifBlank { "未设默认模型" }} · ${cp.baseUrl.removePrefix("https://").removePrefix("http://").take(30)}",
                            style = MaterialTheme.typography.labelSmall, color = Ink3)
                    }
                    TextButton(onClick = { vm.deleteCustomProvider(cp.id) }) {
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
                        // 先保存 ZorvAI 配置层（QuroModelConfigRepository），再保存澄言侧（系统提示词/思考强度）
                        vm.saveQuroConfig(vm.quroConfig.copy(
                            provider = providerType.name,
                            baseUrl = url.trim().trimEnd('/'),
                            apiKey = key.trim(),
                            model = model.trim(),
                            temperature = temp,
                            maxTokens = maxTokens.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 65536,
                            maxToolRounds = maxToolRounds.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            contextWindow = contextWindow.toIntOrNull()?.coerceAtLeast(0) ?: 1048576,
                            contextCompressThreshold = compressThreshold.toIntOrNull()?.coerceIn(1, 100) ?: 80,
                            contextSoftTrimThreshold = softTrimThreshold.toIntOrNull()?.coerceIn(1, 100) ?: 50,
                            enableTools = enableTools,
                        ))
                        onSave(ModelConfig(
                            url = url.trim().trimEnd('/'), key = key.trim(), model = model.trim(),
                            sysPrompt = sys.trim(), temperature = temp, thinking = thinking
                        ))
                        onBack()
                    },
                    enabled = url.isNotBlank() && model.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("保存", letterSpacing = 2.sp) }
            }
        }
    }

    // ---- 模型选择（QuroModelInfo：id + context_length） ----
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
                    val shown = modelList.filter { it.id.contains(modelFilter, ignoreCase = true) }.take(200)
                    if (shown.isEmpty()) {
                        Text("无匹配模型", style = MaterialTheme.typography.bodySmall, color = Ink3,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { mi ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        model = mi.id
                                        // 选中模型时回填真实上下文长度（QuroModelConfig.modelContextLength）
                                        vm.applyModelContextLength(mi.contextLength)
                                        modelsOpen = false
                                    }
                                    .padding(horizontal = 6.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    mi.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (mi.id == model) AccentInk else Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                if (mi.contextLength > 0) {
                                    Text(
                                        "${mi.contextLength / 1000}k",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Ink4, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelsOpen = false }) { Text("关闭", color = Ink2) }
            }
        )
    }

    // ---- 添加自建供应商（QuroCustomProvider） ----
    if (addProviderOpen) {
        AlertDialog(
            onDismissRequest = { addProviderOpen = false },
            title = { Text("添加供应商") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProvName, onValueChange = { newProvName = it },
                        label = { Text("名称", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newProvUrl, onValueChange = { newProvUrl = it },
                        label = { Text("Base URL（不含 /chat/completions）", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newProvModel, onValueChange = { newProvModel = it },
                        label = { Text("默认模型（可空）", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newProvName.isNotBlank() && newProvUrl.isNotBlank(),
                    onClick = {
                        vm.addCustomProvider(newProvName.trim(), newProvUrl.trim(), newProvModel.trim())
                        newProvName = ""; newProvUrl = ""; newProvModel = ""
                        addProviderOpen = false
                    }
                ) { Text("添加", color = AccentInk) }
            },
            dismissButton = {
                TextButton(onClick = { addProviderOpen = false }) { Text("取消", color = Ink2) }
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
