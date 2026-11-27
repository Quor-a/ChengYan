@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

import com.chengyan.app.net.ShizukuBridge

/*
 * 模型配置页 —— 界面结构与交互完整移植自（云端模式；ZorvAI 的「01 导入模型」为本地引擎专区，澄言纯云端故自 02 起编号） Quor-a/ZorvAI ui/QuroModelConfigScreen.kt（云端模式部分）
 * 与 ui/QuroSettingsComponents.kt：
 * - 章节序号排版（ChapterLabel）：陶土色衬线序号 + 衬线标题 + 分隔线
 * - 服务商为「选择器行 + 搜索式弹窗」（ApiProviderDialogWithCustom：内置 33 家 + 自建供应商分组）
 * - 下划线字段（UnderlineField / QuroField / ApiKeyField）
 * - 温度滑块（0..1）+ maxTokens 字段与工具轮次步进器并排 + 上下文窗口/压缩/软精简阀值
 * - 保存为预设（toProfile）+ 预设卡片（首字母头像 + 编辑/应用/删除）
 * - 模型列表弹窗：选中后回填 modelContextLength 并把 contextWindow 写为真实上下文长度
 * 视觉配色使用澄言原创主题（暖纸 × 墨色 × 陶土橙）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.core.model.ApiProviderConfigs
import com.chengyan.app.core.model.ApiProviderType
import com.chengyan.app.core.model.QuroCustomProvider
import com.chengyan.app.core.model.QuroModelConfig
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
    onOpenPrefs: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // 本页直接编辑 ZorvAI 配置（QuroModelConfig），保存时同步到流式客户端
    var cfg by remember { mutableStateOf(vm.quroConfig) }
    fun update(f: (QuroModelConfig) -> QuroModelConfig) { cfg = f(cfg) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var modelListResult by remember { mutableStateOf<QuroModelListResult?>(null) }

    var showProviderDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<com.chengyan.app.core.model.QuroSavedProfile?>(null) }
    var editProfileName by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val profiles = vm.quroProfiles

    val selectedProvider = ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI
    val selectedName = if (cfg.provider == "OTHER" && cfg.customProviderName.isNotBlank()) {
        cfg.customProviderName
    } else {
        getProviderDisplayName(selectedProvider)
    }
    val providerHealth = remember(vm.quroProfiles) {
        vm.quroMultiProviderRepo.getAllConfigs().associate { it.id to it.healthStatus.name }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 顶栏：返回 + 眉标 + 衬线大标题（QuroModelConfigScreen 同款结构）
            TopAppBar(
                title = {
                    Column {
                        Text("CHENGYAN · MODEL", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                        Text("模型配置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    TextButton(onClick = onOpenPrefs) { Text("对话设置", color = AccentInk) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            // ====== 01 服务商（SettingsSelectorRow + ApiProviderDialogWithCustom） ======
            ChapterLabel("02", "服务商")
            SettingsSelectorRow(
                title = "服务商",
                subtitle = "内置 33 家预设与自建供应商，点击选择",
                value = selectedName,
                onClick = { showProviderDialog = true },
            )
            Spacer(Modifier.height(18.dp))

            // ====== 02 连接 ======
            ChapterLabel("03", "连接")
            QuroField("Base URL", cfg.baseUrl, KeyboardOptions.Default) { v -> update { c -> c.copy(baseUrl = v) } }
            Spacer(Modifier.height(12.dp))
            ApiKeyField(value = cfg.apiKey) { v -> update { c -> c.copy(apiKey = v) } }
            Text(
                "本地回环地址（localhost / 10.0.2.2）无需密钥",
                style = MaterialTheme.typography.bodySmall, color = Ink4,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))

            // ====== 03 参数 ======
            ChapterLabel("04", "参数")
            QuroField("模型名称 Model", cfg.model, KeyboardOptions.Default) { v -> update { c -> c.copy(model = v) } }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (cfg.baseUrl.isBlank()) { return@TextButton }
                        isFetchingModels = true
                        scope.launch {
                            // 先读缓存（QuroModelConfigRepository.loadModelListCache，按 baseUrl 失效）
                            val cached = vm.quroConfigRepo.loadModelListCache(cfg.baseUrl.trim().trimEnd('/'))
                            if (cached != null) { modelListResult = cached; isFetchingModels = false; return@launch }
                            modelListResult = com.chengyan.app.core.network.QuroModelListFetcher()
                                .fetch(cfg.baseUrl.trim().trimEnd('/'), cfg.apiKey)
                            (modelListResult as? QuroModelListResult.Success)?.let {
                                vm.quroConfigRepo.saveModelListCache(cfg.baseUrl.trim().trimEnd('/'), it)
                            }
                            isFetchingModels = false
                        }
                    },
                    enabled = !isFetchingModels && cfg.baseUrl.isNotBlank()
                ) { Text(if (isFetchingModels) "获取中…" else "获取服务端模型列表") }
                if (isFetchingModels) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(14.dp))

            // 温度（滑块 0..1，QuroModelConfigScreen 同款）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("温度", fontSize = 14.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("%.2f".format(cfg.temperature), fontSize = 13.sp, color = AccentInk, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = cfg.temperature.coerceIn(0f, 1f),
                onValueChange = { v -> update { c -> c.copy(temperature = v) } },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line2),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuroField(
                    "输出上限 tokens", cfg.maxTokens.toString(),
                    KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
                ) { v -> update { c -> c.copy(maxTokens = v.toIntOrNull() ?: 65536) } }
                StepperField(
                    "工具轮次上限", cfg.maxToolRounds,
                    { v -> update { c -> c.copy(maxToolRounds = v) } }, Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "上下文窗口（输入 token 预算）：0=不限制；非 0 时按预算从最旧轮次裁剪历史，始终保留 system",
                style = MaterialTheme.typography.bodySmall, color = Ink4,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            QuroField(
                "上下文窗口", cfg.contextWindow.toString(),
                KeyboardOptions(keyboardType = KeyboardType.Number),
            ) { v -> update { c -> c.copy(contextWindow = v.toIntOrNull() ?: 262144) } }
            Spacer(Modifier.height(10.dp))
            Text(
                "上下文双阀值（#22）：压缩 = 达到窗口该比例时从最旧裁剪；软精简 = 就地精简超长工具输出",
                style = MaterialTheme.typography.bodySmall, color = Ink4,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuroField(
                    "压缩阀值 %", cfg.contextCompressThreshold.toString(),
                    KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
                ) { v -> update { c -> c.copy(contextCompressThreshold = (v.toIntOrNull() ?: 80).coerceIn(10, 100)) } }
                QuroField(
                    "软精简阀值 %", cfg.contextSoftTrimThreshold.toString(),
                    KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
                ) { v -> update { c -> c.copy(contextSoftTrimThreshold = (v.toIntOrNull() ?: 50).coerceIn(0, 100)) } }
            }
            Spacer(Modifier.height(12.dp))
            // 云端工具调用开关（v453 补回逻辑，QuroModelConfigScreen 同款）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.enableTools, onCheckedChange = { v -> update { c -> c.copy(enableTools = v) } })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("工具调用", style = MaterialTheme.typography.bodyMedium)
                    Text("关闭后模型无法调用时间/计算器/随机数等内置工具",
                        style = MaterialTheme.typography.bodySmall, color = Ink4)
                }
            }
            Spacer(Modifier.height(18.dp))

            // 保存配置（陶土强调色块，QuroModelConfigScreen 同款）
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent)
                    .clickable {
                        vm.saveQuroConfig(cfg)
                        onSave(config.copy(url = cfg.baseUrl, key = cfg.apiKey, model = cfg.model, temperature = cfg.temperature))
                        onBack()
                    }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存配置", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(22.dp))

            // ====== 已保存预设（QuroSavedProfileRepository） ======
            if (profiles.isNotEmpty()) {
                ChapterLabel("05", "已保存预设", profiles.size.toString())
                profiles.forEach { profile ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = Paper2)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderAvatar(profile.name.ifBlank { "?" }.firstOrNull()?.uppercase() ?: "?", 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val dot = when (providerHealth[profile.id]) { "HEALTHY" -> Ok; "FAILED" -> Err; else -> Ink4 }
                                    Box(Modifier.size(7.dp).background(dot, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    "${profile.provider} · ${profile.model.ifBlank { "?" }} · ${profile.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                editingProfile = profile
                                editProfileName = profile.name
                                showEditProfileDialog = true
                            }) { Icon(Icons.Filled.Edit, "编辑", Modifier.size(18.dp), tint = AccentInk) }
                            TextButton(onClick = { vm.applyQuroProfile(profile) }) { Text("应用") }
                            IconButton(onClick = { vm.deleteQuroProfile(profile.id) }) {
                                Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = Err)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ====== 06 系统能力（无障碍 / Shizuku）======
            ChapterLabel("06", "系统能力")
            SystemCapabilitySection(context)

            Spacer(Modifier.height(16.dp))
        }
    }

    // ====== 服务商选择弹窗（ApiProviderDialogWithCustom 完整移植） ======
    if (showProviderDialog) {
        ApiProviderDialogWithCustom(
            currentCustomName = cfg.customProviderName,
            customProviders = vm.quroCustomProviders,
            onDismissRequest = { showProviderDialog = false },
            onProviderSelected = { type ->
                val defaultModel = ApiProviderConfigs.getDefaultModelName(type)
                val defaultEndpoint = ApiProviderConfigs.getDefaultApiEndpoint(type)
                update {
                    var c = it.copy(provider = type.name, customProviderName = "")
                    if (defaultModel.isNotEmpty()) c = c.copy(model = defaultModel)
                    if (defaultEndpoint.isNotEmpty()) c = c.copy(baseUrl = defaultEndpoint)
                    c
                }
                showProviderDialog = false
            },
            onCustomSelected = { cp ->
                update {
                    it.copy(provider = "OTHER", baseUrl = cp.baseUrl, customProviderName = cp.name,
                        model = if (cp.defaultModel.isNotBlank()) cp.defaultModel else it.model)
                }
                showProviderDialog = false
            },
            onCustomSaved = { name, url, model ->
                vm.addCustomProvider(name, url, model)
            },
            onCustomDeleted = { vm.deleteCustomProvider(it) },
        )
    }

    // ====== 保存预设弹窗 ======
    if (showSaveDialog) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            confirmButton = {
                Button(onClick = {
                    val name = presetName.trim().ifBlank { "预设 ${profiles.size + 1}" }
                    vm.saveQuroProfile(name)
                    showSaveDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } },
            title = { Text("保存为预设") },
            text = {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it },
                    label = { Text("预设名称") }, placeholder = { Text("如「我的 DeepSeek」") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
        )
    }

    // ====== 编辑预设弹窗（QuroModelConfigScreen 同款） ======
    if (showEditProfileDialog && editingProfile != null) {
        val ep = editingProfile!!
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false; editingProfile = null },
            confirmButton = {
                Button(onClick = {
                    val updated = ep.copy(name = editProfileName.trim().ifBlank { ep.name })
                    vm.quroProfileRepo.save(updated)
                    showEditProfileDialog = false
                    editingProfile = null
                }, enabled = editProfileName.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditProfileDialog = false; editingProfile = null }) { Text("取消") } },
            title = { Text("编辑预设", style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editProfileName, onValueChange = { editProfileName = it },
                        label = { Text("预设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("服务商：${ep.provider}", style = MaterialTheme.typography.bodySmall, color = Ink4)
                    Text("模型：${ep.model.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Ink4)
                    Text("地址：${ep.baseUrl.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Ink4, maxLines = 1)
                    Text("API Key 不在此处修改，切换预设时会一并应用", style = MaterialTheme.typography.bodySmall, color = Ink4)
                }
            },
        )
    }

    // ====== 拉取结果弹窗（选中回填 modelContextLength + contextWindow，QuroModelConfigScreen 同款） ======
    val ml = modelListResult
    if (ml != null) {
        when (ml) {
            is QuroModelListResult.Success -> {
                AlertDialog(onDismissRequest = { modelListResult = null }, confirmButton = {},
                    title = { Text("选择模型（${ml.models.size}）") },
                    text = {
                        if (ml.models.isEmpty()) Text("接入点未返回任何模型")
                        else LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(ml.models) { info ->
                                TextButton(onClick = {
                                    update {
                                        // 总开关：把从 /models 接口取到的真实上下文长度写回 contextWindow，
                                        // 不再硬编码；并记入 modelContextLength 作为单次输入硬上限来源。
                                        it.copy(
                                            model = info.id,
                                            modelContextLength = info.contextLength,
                                            contextWindow = if (info.contextLength > 0) info.contextLength else it.contextWindow,
                                        )
                                    }
                                    modelListResult = null
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (info.contextLength > 0) "${info.id}  (ctx ${info.contextLength})" else info.id,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                        }
                    })
            }
            is QuroModelListResult.Error -> {
                AlertDialog(onDismissRequest = { modelListResult = null },
                    confirmButton = { TextButton(onClick = { modelListResult = null }) { Text("确定") } },
                    title = { Text("拉取失败") }, text = { Text(ml.message) })
            }
        }
    }
}

/* ==================== 组件（移植自 QuroSettingsComponents.kt，配色换澄言主题） ==================== */

/**
 * 手机能力权限状态卡（真实数据）：列出 AI 手机工具实际用到的运行时权限的授予状态。
 * 🔴 旧版「无障碍/Shizuku」卡已删：App 未声明 AccessibilityService、也未集成 Shizuku SDK，
 *    那两张卡是纯检测型死 UI——检测了永远不会被使用的能力，即「根本不能用」。
 */
@Composable
private fun SystemCapabilitySection(context: android.content.Context) {
    var states by remember { mutableStateOf(mapOf<String, Boolean>()) }
    fun refresh() {
        states = listOf(
            "短信读取" to android.Manifest.permission.READ_SMS,
            "短信发送" to android.Manifest.permission.SEND_SMS,
            "通讯录读取" to android.Manifest.permission.READ_CONTACTS,
            "麦克风" to android.Manifest.permission.RECORD_AUDIO,
            "相机" to android.Manifest.permission.CAMERA,
            "精确定位" to android.Manifest.permission.ACCESS_FINE_LOCATION,
        ).associate { (label, p) ->
            label to (runCatching {
                context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false))
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Paper2)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- 无障碍屏幕服务（真实能力：屏幕读取 + 全局动作） ----
            var a11yOn by remember { mutableStateOf(com.chengyan.app.service.ChengYanAccessibilityService.instance != null) }
            CapabilityRow(
                icon = "♿", name = "屏幕服务（无障碍）",
                desc = if (a11yOn) "已启用 · AI 可读取屏幕文本并执行返回/主页"
                       else "未启用 · 启用后 AI 的 phone_screen 工具可读屏与执行全局动作",
                on = a11yOn,
                actionLabel = "去启用",
                onAction = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
            androidx.compose.material3.HorizontalDivider(color = Line)
            // ---- Shizuku（真实集成：shell 级 pm grant 解锁受限权限） ----
            var shizukuOk by remember { mutableStateOf(ShizukuBridge.granted()) }
            var shizukuAvail by remember { mutableStateOf(ShizukuBridge.available()) }
            CapabilityRow(
                icon = "⚙", name = "Shizuku",
                desc = when {
                    shizukuOk -> "已授权 · 可一键授予短信/通话记录等受限权限"
                    shizukuAvail -> "已运行 · 待授权本应用"
                    else -> "未安装或未启动 · 安装 Shizuku 后点「去授权」"
                },
                on = shizukuOk,
                actionLabel = "去授权",
                onAction = {
                    if (ShizukuBridge.available()) {
                        ShizukuBridge.request { _ -> refresh() }
                    } else {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setData(android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                }
            )
            androidx.compose.material3.HorizontalDivider(color = Line)
            Text(
                "以下权限由「手机能力」工具按需申请；拒绝不影响其他功能。",
                style = MaterialTheme.typography.bodySmall, color = Ink3
            )
            states.forEach { (label, granted) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink, modifier = Modifier.weight(1f))
                    Text(
                        if (granted) "已授予" else "未授予",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (granted) Ok else Ink3
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(color = Line)
            CapabilityRow(
                icon = "⚙", name = "权限设置",
                desc = "前往系统设置手动管理本应用的全部权限",
                on = true,
                actionLabel = "去设置",
                onAction = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    icon: String, name: String, desc: String, on: Boolean,
    actionLabel: String, onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = Ink)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).background(if (on) Ok else Ink4, CircleShape))
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ink3)
        }
        TextButton(onClick = onAction) { Text(actionLabel, color = AccentInk) }
    }
}

/** 章节小标题（编辑排版风）：陶土色衬线序号 + 衬线标题 + 分隔线 */
@Composable
private fun ChapterLabel(num: String, title: String, sub: String = "") {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(num, fontSize = 18.sp, color = Accent, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Serif)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
            if (sub.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("($sub)", fontSize = 12.sp, color = Ink4)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

/** 选择入口行：AccentSoft 图标盒 + 标题/副标题 + 值 + 箭头（模型配置同款） */
@Composable
private fun SettingsSelectorRow(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, if (value.isNotBlank()) Accent else Line, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Public, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, color = Ink4, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotBlank()) {
            Text(value, fontSize = 14.sp, color = AccentInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = Ink4)
    }
}

/** 下划线输入框（编辑排版风，QuroSettingsComponents 同款） */
@Composable
private fun UnderlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    isSecret: Boolean = false,
    showSecret: Boolean = false,
    onToggleSecret: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                ),
                visualTransformation = if (isSecret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { it -> if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Ink4); it() },
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            )
            if (isSecret) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clickable(onClick = onToggleSecret)) {
                    Icon(
                        if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        "切换可见", Modifier.size(18.dp), tint = Ink4,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

/** 普通字段（包装 UnderlineField，签名与 QuroSettingsComponents 保持一致） */
@Composable
private fun QuroField(
    label: String,
    value: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    UnderlineField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        placeholder = "",
        modifier = modifier,
        numeric = keyboardOptions.keyboardType == KeyboardType.Number,
    )
}

/** 密钥字段（可切换可见，QuroSettingsComponents 同款） */
@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    UnderlineField(
        label = "API Key",
        value = value,
        onValueChange = onValueChange,
        placeholder = "sk-••••••••",
        isSecret = true,
        showSecret = visible,
        onToggleSecret = { visible = !visible },
    )
}

/** 步进器（编辑排版风，QuroSettingsComponents 同款） */
@Composable
private fun StepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).border(1.dp, Line, CircleShape)
                    .clickable { if (value > 0) onValueChange(value - 1) },
                contentAlignment = Alignment.Center,
            ) { Text("−", fontSize = 18.sp, color = cs.onSurface) }
            Spacer(Modifier.width(12.dp))
            Text(value.toString(), fontSize = 15.sp, color = cs.onSurface,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 20.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Accent).clickable { onValueChange(value + 1) },
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 信息/状态展示盒（纸感，QuroSettingsComponents 同款） */
@Composable
private fun InfoBox(text: String, tone: Color = AccentInk) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(AccentSoft.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        Text(text, fontSize = 13.sp, color = tone, lineHeight = 18.sp)
    }
}

/** 供应商首字母头像（QuroModelConfigScreen.ProviderAvatar，头像图片选择略） */
@Composable
private fun ProviderAvatar(mark: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
        Text(mark.ifBlank { "?" }, fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
    }
}

/** 服务商展示名（QuroModelConfigScreen 硬编码中文表，字符串资源改为内联） */
private fun getProviderDisplayName(provider: ApiProviderType): String =
    when (provider) {
        ApiProviderType.OPENAI -> "OpenAI"
        ApiProviderType.OPENAI_RESPONSES -> "OpenAI Responses"
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> "OpenAI Responses 通用（自定义端点）"
        ApiProviderType.OPENAI_GENERIC -> "OpenAI 通用（自定义端点）"
        ApiProviderType.ANTHROPIC -> "Anthropic Claude"
        ApiProviderType.ANTHROPIC_GENERIC -> "Anthropic 通用（自定义端点）"
        ApiProviderType.GOOGLE -> "Google Gemini"
        ApiProviderType.GEMINI_GENERIC -> "Gemini 通用（自定义端点）"
        ApiProviderType.BAIDU -> "百度 文心一言"
        ApiProviderType.ALIYUN -> "阿里云 通义千问"
        ApiProviderType.XUNFEI -> "讯飞 星火认知"
        ApiProviderType.ZHIPU -> "智谱AI ChatGLM"
        ApiProviderType.BAICHUAN -> "百川大模型"
        ApiProviderType.MOONSHOT -> "月之暗面 Kimi"
        ApiProviderType.MIMO -> "小米 MiMo"
        ApiProviderType.DEEPSEEK -> "DeepSeek"
        ApiProviderType.MISTRAL -> "Mistral AI"
        ApiProviderType.SILICONFLOW -> "硅基流动"
        ApiProviderType.IFLOW -> "iFlow"
        ApiProviderType.OPENROUTER -> "OpenRouter"
        ApiProviderType.FOUR_ROUTER -> "4Router"
        ApiProviderType.NOUS_PORTAL -> "Nous Portal"
        ApiProviderType.INFINIAI -> "无问芯穹"
        ApiProviderType.ALIPAY_BAILING -> "支付宝百灵"
        ApiProviderType.DOUBAO -> "豆包（火山）"
        ApiProviderType.NVIDIA -> "NVIDIA NIM"
        ApiProviderType.LMSTUDIO -> "LM Studio 本地"
        ApiProviderType.OLLAMA -> "Ollama 本地"
        ApiProviderType.OPENAI_LOCAL -> "OpenAI 兼容本地"
        ApiProviderType.MNN -> "MNN 本地引擎"
        ApiProviderType.LLAMA_CPP -> "llama.cpp 本地引擎"
        ApiProviderType.PPINFRA -> "派欧云"
        ApiProviderType.NOVITA -> "Novita AI"
        ApiProviderType.OPENCODE_GO -> "OpenCode Go"
        ApiProviderType.OTHER -> "其他供应商（自定义端点）"
    }

/**
 * 服务商选择弹窗 —— 完整移植自 QuroModelConfigScreen.ApiProviderDialogWithCustom：
 * 搜索框 + 自建供应商分组（首字母头像 + 删除）+ 内置 33 家列表 + 「添加供应商」表单。
 */
@Composable
private fun ApiProviderDialogWithCustom(
    currentCustomName: String,
    customProviders: List<QuroCustomProvider>,
    onDismissRequest: () -> Unit,
    onProviderSelected: (ApiProviderType) -> Unit,
    onCustomSelected: (QuroCustomProvider) -> Unit,
    onCustomSaved: (name: String, url: String, model: String) -> Unit,
    onCustomDeleted: (String) -> Unit,
) {
    val builtIn = remember {
        ApiProviderType.values().filter {
            it != ApiProviderType.OTHER && it != ApiProviderType.MNN && it != ApiProviderType.LLAMA_CPP
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var showCustomForm by remember { mutableStateOf(false) }

    val filtered = remember(searchQuery) {
        if (searchQuery.isEmpty()) builtIn
        else builtIn.filter {
            getProviderDisplayName(it).contains(searchQuery, ignoreCase = true) || it.name.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredCustom = remember(searchQuery, customProviders) {
        if (searchQuery.isEmpty()) customProviders
        else customProviders.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    if (showCustomForm) {
        var cName by remember { mutableStateOf("") }
        var cUrl by remember { mutableStateOf("") }
        var cModel by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomForm = false },
            confirmButton = {
                Button(enabled = cName.trim().isNotEmpty() && cUrl.trim().isNotEmpty(), onClick = {
                    onCustomSaved(cName.trim(), cUrl.trim(), cModel.trim())
                    onCustomSelected(QuroCustomProvider(name = cName.trim(), baseUrl = cUrl.trim(), defaultModel = cModel.trim()))
                    showCustomForm = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showCustomForm = false }) { Text("取消") } },
            title = { Text("添加供应商", style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderAvatar(cName.firstOrNull()?.uppercase() ?: "+", 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("保存后出现在供应商列表，点击即回填其端点与默认模型", fontSize = 12.sp, color = Ink4)
                    }
                    OutlinedTextField(value = cName, onValueChange = { cName = it },
                        label = { Text("名称") }, placeholder = { Text("如「我的私有服务」") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cUrl, onValueChange = { cUrl = it },
                        label = { Text("Base URL（不含 /chat/completions）") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cModel, onValueChange = { cModel = it },
                        label = { Text("默认模型（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(onClick = { showCustomForm = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加供应商")
                }
            },
            dismissButton = { TextButton(onClick = onDismissRequest) { Text("取消") } },
            title = { Text("选择服务商") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索服务商…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }, Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Clear, null)
                            }
                        },
                    )
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 380.dp)) {
                        if (filteredCustom.isNotEmpty()) {
                            item {
                                Text("我的供应商", style = MaterialTheme.typography.labelMedium, color = AccentInk,
                                    modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(filteredCustom) { cp ->
                                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { onCustomSelected(cp) }, shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                                    Row(Modifier.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        ProviderAvatar(cp.name.firstOrNull()?.uppercase() ?: "?", 32.dp)
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            Text(cp.name, style = MaterialTheme.typography.bodyLarge)
                                            Text(cp.baseUrl, style = MaterialTheme.typography.bodySmall,
                                                color = Ink3, maxLines = 1)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { onCustomDeleted(cp.id) }) {
                                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Err)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Text("内置服务商", style = MaterialTheme.typography.labelMedium, color = AccentInk,
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                        items(filtered) { pt ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { onProviderSelected(pt) }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(getProviderDisplayName(pt), style = MaterialTheme.typography.bodyLarge)
                                    ApiProviderConfigs.getDefaultApiEndpoint(pt).takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = Ink3, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
