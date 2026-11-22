@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

/*
 * 对话设置页（澄言自有功能，视觉沿用 ZorvAI QuroSettingsComponents 的编辑排版风组件）：
 * 系统提示词 / 思考强度 / 测试连接 / 备份与恢复。
 * 与「模型配置」（ZorvAI 移植）分离，避免混排。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.ModelConfig
import com.chengyan.app.net.OpenAiClient
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPrefsScreen(
    config: ModelConfig,
    vm: com.chengyan.app.ui.ChatViewModel,
    onSave: (ModelConfig) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var sys by remember { mutableStateOf(config.sysPrompt) }
    var thinking by remember { mutableStateOf(config.thinking) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
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
                title = {
                    Column {
                        Text("CHENGYAN · CHAT", fontSize = 11.sp, color = Ink4, letterSpacing = 0.5.sp)
                        Text("对话设置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
            // ====== 01 对话 ======
            ChapterLabel("01", "对话")
            Text("系统提示词（可选）", fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(bottom = 4.dp))
            BasicTextField(
                value = sys, onValueChange = { sys = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                decorationBox = { inner ->
                    Column {
                        if (sys.isEmpty()) Text("例如：你是一位简洁专业的中文助手。", fontSize = 15.sp, color = Ink4)
                        inner()
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("思考强度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("off" to "关", "default" to "默认", "low" to "低", "high" to "高").forEach { (v, label) ->
                        Surface(
                            shape = CircleShape,
                            color = if (thinking == v) AccentSoft else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { thinking = v }
                        ) {
                            Text(label, fontSize = 12.sp, color = if (thinking == v) AccentInk else Ink2,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
            }
            Text(
                "关闭：直接回答（需模型支持）· 默认：由模型决定 · 低/高：reasoning_effort",
                style = MaterialTheme.typography.bodySmall, color = Ink4,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))

            // 保存（陶土色块）
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent)
                    .clickable {
                        onSave(config.copy(sysPrompt = sys.trim(), thinking = thinking))
                        onBack()
                    }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存配置", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(22.dp))

            // ====== 02 测试连接 ======
            ChapterLabel("02", "测试连接")
            testResult?.let { (ok, msg) ->
                InfoBox(msg, tone = if (ok) Ok else Err)
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = {
                    if (config.url.isBlank() || config.model.isBlank()) {
                        testResult = false to "请先在模型配置中完成接入点设置"
                        return@OutlinedButton
                    }
                    testing = true; testResult = null
                    scope.launch {
                        val client = OpenAiClient()
                        testResult = client.test(config).fold({ true to "✓ $it" }, { false to "✗ ${it.message ?: "连接失败"}" })
                        testing = false
                    }
                },
                enabled = !testing,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2)
            ) { Text(if (testing) "测试中…" else "测试当前接入点") }
            Spacer(Modifier.height(18.dp))

            // ====== 03 备份与恢复 ======
            ChapterLabel("03", "备份与恢复")
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------- 与 SettingsScreen 同款的编辑排版组件（视觉沿用 ZorvAI QuroSettingsComponents） ---------- */

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

@Composable
private fun InfoBox(text: String, tone: Color = AccentInk) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(AccentSoft.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        Text(text, fontSize = 13.sp, color = tone, lineHeight = 18.sp)
    }
}
