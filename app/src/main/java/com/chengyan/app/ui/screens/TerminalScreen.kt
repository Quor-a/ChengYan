package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.LinuxBridge
import com.chengyan.app.ui.components.monoSafe
import com.chengyan.app.ui.theme.FontCJK
import com.chengyan.app.ui.theme.Ink
import com.chengyan.app.ui.theme.Ink2
import com.chengyan.app.ui.theme.Ink3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端视觉（澄言原创）：深墨玻璃面板 × 陶土橙点缀 × 等宽终端字。
 * 配色层次：页面底 < 面板底 < 输出底，三层递进；状态徽章/快捷键/输入各有独立色阶。
 */
private object TermTheme {
    val Page = Color(0xFF14110E)          // 页面最底
    val Panel = Color(0xFF211C17)         // 输出面板
    val PanelHi = Color(0xFF2C261F)       // 面板高光/快捷键底
    val Border = Color(0xFF3A3228)        // 面板描边
    val Fg = Color(0xFFEDE4D2)            // 主文字（高对比）
    val FgDim = Color(0xFFA89C86)         // 次文字
    val Prompt = Color(0xFFD99A5B)        // 提示符/强调橙
    val Ok = Color(0xFF8FBF7A)            // 运行中绿
    val Err = Color(0xFFE07A5F)           // 错误红
    val KeyBg = Color(0xFF352E24)         // 快捷键底
    val KeyFg = Color(0xFFC9BDA8)         // 快捷键字
    val DangerBg = Color(0xFF4A2A22)      // 危险操作底（重建）
    val Accent = Color(0xFFC96F3B)        // 运行按钮
}

/**
 * Linux 终端：Alpine Linux 3.24 用户空间 + 真 PTY。
 * UI 形态：顶栏 / 输出区 / 快捷键行 / 输入行（与既有形态一致，仅视觉重设计）。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var histIdx by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var clearAt by remember { mutableStateOf(0) }
    var probeOut by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val outScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // ensure 有解压 rootfs(waitFor 600s)+forkpty+sleep，阻塞 → IO 线程，否则 ANR。
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching { LinuxBridge.ensure() }.getOrElse {
                error = "会话启动失败：${it.javaClass.simpleName} ${it.message}"
                return@withContext -10L
            }
        }
        if (r <= 0) error = LinuxBridge.lastError.ifBlank { "会话启动失败（$r）" }
    }
    // 150ms 轮询：poll 读 PTY + 扫状态机，切 IO 避免大输出卡主线程。
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            withContext(Dispatchers.IO) {
                runCatching { LinuxBridge.poll() }.onFailure { error = it.message }
            }
        }
    }
    val st by LinuxBridge.stateFlow.collectAsState()
    LaunchedEffect(outScroll) {
        var prev = 0
        snapshotFlow { outScroll.value }.collect { v ->
            if (v < prev) follow = false
            if (v >= outScroll.maxValue) follow = true
            prev = v
        }
    }
    LaunchedEffect(st.output.length, follow) {
        // scrollTo（非 animate）：动画会在输出稳定后仍连续重组，触摸时抢主线程 → ANR。
        if (follow) outScroll.scrollTo(outScroll.maxValue)
    }

    fun send(cmd: String) {
        if (cmd.isBlank()) return
        history = history + cmd
        histIdx = -1
        scope.launch(Dispatchers.IO) {
            val r = LinuxBridge.run(cmd)
            error = if (r < 0) {
                when {
                    r == -3L -> "会话忙：上一条命令还在运行（等退出或点「重建」）"
                    else -> LinuxBridge.lastError.ifBlank { "启动失败（$r）" }
                }
            } else null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TermTheme.Page)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---------- 顶栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.clickable { onBack() }.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = TermTheme.FgDim, fontSize = 22.sp, fontFamily = FontCJK)
                Text("返回", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = FontCJK)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Linux 终端", color = TermTheme.Fg, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontCJK
            )
            Spacer(Modifier.weight(1f))
            // 状态徽章：运行中 / 就绪，带独立底色与圆点，不再用灰绿模糊表达
            StatusBadge(running = st.busy, exit = st.exit)
        }

        // ---------- 输出区 ----------
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            Surface(
                color = TermTheme.Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, TermTheme.Border, RoundedCornerShape(14.dp))
            ) {
                val from = clearAt.coerceAtMost(st.output.length)
                // 只渲染尾部窗口：3 万字符的 Text 测量/布局在主线程是重活（真机 ANR 帮凶）。
                // remember 缓存 renderOut：只有 window 真变化才重跑 ANSI 解析。
                val window = st.output.drop(from).let { if (it.length > 12_000) it.takeLast(12_000) else it }
                val rendered = remember(window) { renderOut(window) }
                val text = (probeOut?.let { it + "\n\n" } ?: "") + rendered
                Text(
                    text.ifBlank {
                        "┌─ ChengYan Terminal ─────────────\n" +
                        "│  Alpine Linux 3.24 · aarch64\n" +
                        "│  真 PTY 会话已就绪\n" +
                        "└────────────────────────────────\n\n" +
                        "输入命令回车执行，例如：ls / 或 apk --version"
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = TermTheme.Fg,
                    fontFamily = monoSafe(input),
                    modifier = Modifier
                        .verticalScroll(outScroll)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
            // 未跟随时的「回到底部」悬浮按钮
            if (!follow) {
                Surface(
                    onClick = { follow = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = TermTheme.Accent,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("↓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---------- 错误条 ----------
        error?.let {
            Surface(
                color = TermTheme.DangerBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()
            ) {
                Text(
                    it, color = TermTheme.Err, fontSize = 11.5.sp, fontFamily = FontCJK,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // ---------- 快捷键行（横向滚动，修挤压）----------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            QuickKey("清屏") { clearAt = st.output.length }
            QuickKey("Tab") { input += "    " }
            QuickKey("↑") {
                if (history.isNotEmpty()) {
                    histIdx = if (histIdx == -1) history.size - 1 else (histIdx - 1).coerceAtLeast(0)
                    input = history.getOrNull(histIdx) ?: input
                }
            }
            QuickKey("↓") {
                if (history.isNotEmpty() && histIdx >= 0) {
                    histIdx++
                    input = history.getOrNull(histIdx) ?: ""
                    if (histIdx >= history.size) histIdx = -1
                }
            }
            QuickKey("^C") { LinuxBridge.interrupt() }
            QuickKey("自检") { error = LinuxBridge.diag() }
            QuickKey("重建", danger = true) {
                scope.launch(Dispatchers.IO) {
                    LinuxBridge.restart()
                    clearAt = 0
                }
            }
            QuickKey("探针") {
                probeOut = "探测中…"
                // IO 线程跑探针，结果回主线程写 Compose 状态（子线程写状态不稳）。
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            com.chengyan.app.data.FdosProbe.summarize(
                                com.chengyan.app.data.FdosProbe.run(ctx)
                            )
                        }.getOrElse { "探针失败：${it.message}" }
                    }
                    probeOut = result
                }
            }
        }

        // ---------- 输入行 ----------
        Surface(
            color = TermTheme.Panel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .border(1.dp, TermTheme.Border, RoundedCornerShape(12.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$", color = TermTheme.Prompt, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TermTheme.Fg, fontFamily = monoSafe(input), fontSize = 14.sp),
                    cursorBrush = SolidColor(TermTheme.Prompt),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("输入 Alpine 命令…", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = FontCJK)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { send(input); input = "" },
                    shape = RoundedCornerShape(8.dp),
                    color = TermTheme.Accent
                ) {
                    Text(
                        "运行", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontCJK, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 状态徽章：运行中（绿点+绿字）/ 就绪（橙点+灰字）。 */
@Composable
private fun StatusBadge(running: Boolean, exit: Int?) {
    val dot = if (running) TermTheme.Ok else TermTheme.Prompt
    val text = if (running) "运行中" else if (exit != null) "就绪 · $exit" else "就绪"
    val fg = if (running) TermTheme.Ok else TermTheme.FgDim
    Surface(
        color = TermTheme.PanelHi,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, TermTheme.Border, RoundedCornerShape(20.dp))
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(50)))
            Text(text, color = fg, fontSize = 11.5.sp, fontFamily = FontCJK)
        }
    }
}

@Composable
private fun QuickKey(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val bg = if (danger) TermTheme.DangerBg else TermTheme.KeyBg
    val fg = if (danger) TermTheme.Err else TermTheme.KeyFg
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.border(1.dp, TermTheme.Border, RoundedCornerShape(8.dp))
    ) {
        Text(
            label, fontSize = 12.sp, color = fg, fontFamily = FontCJK,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
        )
    }
}

/** 输出渲染：剥 ANSI 转义序列（CSI/OSC），保留可见文本。 */
private fun renderOut(out: String): String {
    val sb = StringBuilder(out.length)
    var i = 0
    while (i < out.length) {
        val c = out[i]
        if (c == '\u001B') {
            i++
            when {
                i < out.length && out[i] == '[' -> {
                    i++
                    while (i < out.length && out[i].code in 0x20..0x3F) i++
                    if (i < out.length && out[i].code in 0x40..0x7E) i++
                }
                i < out.length && out[i] == ']' -> {
                    i++
                    while (i < out.length) {
                        if (out[i] == '\u0007') { i++; break }
                        if (out[i] == '\u001B' && i + 1 < out.length && out[i + 1] == '\\') { i += 2; break }
                        i++
                    }
                }
                i < out.length && out[i].code in 0x40..0x5F -> i++
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
