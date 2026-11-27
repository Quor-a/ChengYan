package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.LinuxBridge
import com.chengyan.app.ui.components.monoSafe
import com.chengyan.app.ui.theme.FontCJK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端视觉（澄言原创，v1.9.19 重设计）：暖墨三层次深色 + 陶土橙强调。
 * 相对旧版：①输出真彩色渲染（SGR 前景色/加粗解析，不再剥掉颜色）；②顶栏加
 * Alpine 徽标与指纹行；③快捷键改胶囊芯片；④输入行加提示符底座与圆形发送键。
 */
private object TermTheme {
    val Page = Color(0xFF14110E)          // 页面最底
    val Panel = Color(0xFF1E1913)         // 输出面板
    val PanelHi = Color(0xFF2C261F)       // 芯片/徽章底
    val Border = Color(0xFF3A3228)        // 描边
    val Fg = Color(0xFFEDE4D2)            // 主文字
    val FgDim = Color(0xFFA89C86)         // 次文字
    val Prompt = Color(0xFFD99A5B)        // 提示符/强调橙
    val Ok = Color(0xFF8FBF7A)            // 运行中绿
    val Err = Color(0xFFE07A5F)           // 错误红
    val KeyBg = Color(0xFF332C22)         // 芯片底
    val KeyFg = Color(0xFFC9BDA8)         // 芯片字
    val DangerBg = Color(0xFF4A2A22)      // 危险操作底
    val Accent = Color(0xFFC96F3B)        // 发送键
    // ANSI 8+8 色映射（暖色化，与主题同族）
    val Ansi = listOf(
        Color(0xFF5A5044), // 30 black
        Color(0xFFE07A5F), // 31 red
        Color(0xFF8FBF7A), // 32 green
        Color(0xFFD9B45B), // 33 yellow
        Color(0xFF7FA6C9), // 34 blue
        Color(0xFFC9A0B8), // 35 magenta
        Color(0xFF7FBFB0), // 36 cyan
        Color(0xFFEDE4D2), // 37 white
    )
    val AnsiBright = listOf(
        Color(0xFF8A8069), Color(0xFFF0957A), Color(0xFFA8D68F), Color(0xFFE8CC7A),
        Color(0xFF9EC2E0), Color(0xFFDDB8CE), Color(0xFF9AD4C4), Color(0xFFFAF4E6),
    )
}

/**
 * Linux 终端：Alpine Linux 3.24 用户空间 + 真 PTY。
 * 逻辑骨架（ensure IO/150ms poll/贴底节流/历史）为已验证形态，仅视觉层重写。
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
        // ---------- 顶栏：返回 · 标题+指纹 · Alpine 徽标 · 状态 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.clickable { onBack() }.padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = TermTheme.FgDim, fontSize = 22.sp, fontFamily = FontCJK)
                Text("返回", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = FontCJK)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = TermTheme.Prompt, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Terminal", color = TermTheme.Fg, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    com.chengyan.app.BuildConfig.BUILD_STAMP,
                    color = TermTheme.FgDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 14.dp, top = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // Alpine 徽标
            Surface(
                color = TermTheme.PanelHi,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.border(1.dp, TermTheme.Border, RoundedCornerShape(6.dp))
            ) {
                Text(
                    "ALPINE 3.24", color = TermTheme.KeyFg, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(running = st.busy, exit = st.exit)
        }

        // ---------- 输出区（真彩色 ANSI 渲染） ----------
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
                // remember 缓存 rendered：只有 window 真变化才重跑 ANSI 解析。
                val window = st.output.drop(from).let { if (it.length > 12_000) it.takeLast(12_000) else it }
                val rendered = remember(window) { renderAnsi(window) }
                val text = if (probeOut != null)
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TermTheme.FgDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                            append(probeOut ?: "")
                        }
                        append("\n\n")
                        append(rendered)
                    } else rendered
                val body = if (text.isBlank()) welcomeBanner() else text
                Text(
                    body,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = TermTheme.Fg,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .verticalScroll(outScroll)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                )
            }
            // 未跟随时的「回到底部」悬浮按钮
            if (!follow) {
                Surface(
                    onClick = { follow = true },
                    shape = CircleShape,
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
                    "⚠ $it", color = TermTheme.Err, fontSize = 11.5.sp, fontFamily = FontCJK,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // ---------- 快捷键（胶囊芯片行） ----------
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
            QuickKey("重建", danger = true) {
                scope.launch(Dispatchers.IO) {
                    LinuxBridge.restart()
                    clearAt = 0
                }
            }
        }

        // ---------- 输入行：提示符底座 + 圆形发送键 ----------
        Surface(
            color = TermTheme.Panel,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .border(1.dp, TermTheme.Border, RoundedCornerShape(14.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = TermTheme.KeyBg,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        "$", color = TermTheme.Prompt, fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
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
                            if (input.isEmpty()) Text("输入命令…", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = FontCJK)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = { send(input); input = "" },
                    shape = CircleShape,
                    color = TermTheme.Accent,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("▶", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 空态欢迎卡（内嵌式，不再是假输出）。 */
private fun welcomeBanner(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = TermTheme.Prompt, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)) {
        append("ChengYan Terminal\n")
    }
    withStyle(SpanStyle(color = TermTheme.FgDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)) {
        append("Alpine Linux 3.24 · aarch64 · musl · 真 PTY 会话\n")
        append("proot 用户态隔离 · bash/ash 可用\n\n")
    }
    withStyle(SpanStyle(color = TermTheme.KeyFg, fontSize = 12.sp, fontFamily = FontCJK)) {
        append("输入命令回车执行，例如：")
    }
    withStyle(SpanStyle(color = TermTheme.Prompt, fontSize = 12.sp, fontFamily = FontFamily.Monospace)) {
        append("ls /  ·  bash --version  ·  apk --version")
    }
}

/** 状态徽章：运行中（绿点+绿字）/ 就绪（橙点+灰字）。 */
@Composable
private fun StatusBadge(running: Boolean, exit: Int?) {
    val dot = if (running) TermTheme.Ok else TermTheme.Prompt
    val text = when {
        running -> "RUN"
        exit != null -> "RDY·$exit"
        else -> "RDY"
    }
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
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Text(text, color = fg, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun QuickKey(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val bg = if (danger) TermTheme.DangerBg else TermTheme.KeyBg
    val fg = if (danger) TermTheme.Err else TermTheme.KeyFg
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        modifier = Modifier.border(1.dp, if (danger) TermTheme.Err.copy(alpha = 0.4f) else TermTheme.Border, RoundedCornerShape(20.dp))
    ) {
        Text(
            label, fontSize = 12.sp, color = fg, fontFamily = FontCJK,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/**
 * 输出渲染：解析 ANSI CSI 序列——SGR 前景色（30-37/90-97）与加粗映射为
 * AnnotatedString 样式（真彩色终端），其余序列（OSC/光标控制等）剥除。
 */
private fun renderAnsi(out: String): AnnotatedString {
    val sb = buildAnnotatedString {
        var color: Color? = null
        var bold = false
        var i = 0
        fun flushSpan(text: String) {
            if (text.isEmpty()) return
            val style = SpanStyle(
                color = color ?: TermTheme.Fg,
                fontWeight = if (bold) FontWeight.SemiBold else null
            )
            withStyle(style) { append(text) }
        }
        val plain = StringBuilder()
        while (i < out.length) {
            val c = out[i]
            if (c == '\u001B') {
                // 先落袋当前纯文本段
                flushSpan(plain.toString()); plain.setLength(0)
                i++
                when {
                    i < out.length && out[i] == '[' -> {
                        i++
                        val params = StringBuilder()
                        while (i < out.length && out[i].code in 0x20..0x3F) { params.append(out[i]); i++ }
                        val final = if (i < out.length) out[i] else ' '; if (i < out.length) i++
                        if (final == 'm') {
                            // SGR：解析前景色与加粗（按 ; 分段，空段视为 0）
                            val ps = params.toString().ifBlank { "0" }
                            for (seg in ps.split(';')) {
                                when (seg.trim().toIntOrNull()) {
                                    0 -> { color = null; bold = false }
                                    1 -> bold = true
                                    22 -> bold = false
                                    39 -> color = null
                                    in 30..37 -> color = TermTheme.Ansi[seg.trim().toInt() - 30]
                                    in 90..97 -> color = TermTheme.AnsiBright[seg.trim().toInt() - 90]
                                    else -> {}
                                }
                            }
                        }
                    }
                    i < out.length && out[i] == ']' -> {
                        // OSC 到 BEL 或 ST
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
                plain.append(c)
                i++
            }
        }
        flushSpan(plain.toString())
    }
    return sb
}
