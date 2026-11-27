package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.chengyan.app.ui.theme.FontCJK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端视觉（v1.9.21 重设计，参考真实终端模拟器形态）：
 * 全屏平铺、无卡片/无圆角浮块/无彩色胶囊——只有背景、文字、发丝分隔线。
 * 一切元素等宽字体；唯一的强调色是陶土橙（提示符与路径）。
 * 无障碍：所有可点区域 ≥48dp、带 contentDescription、文字随系统 fontScale。
 */
private object TermTheme {
    val Bg = Color(0xFF171310)            // 全屏底（更深、更平）
    val BgAlt = Color(0xFF1F1A15)         // 功能行底（快捷键/输入行）
    val Line = Color(0xFF332C24)          // 发丝分隔线
    val Fg = Color(0xFFE9E0CE)            // 输出主文字
    val FgDim = Color(0xFF9C9080)         // 次文字
    val Prompt = Color(0xFFD99A5B)        // 提示符/强调
    val Ok = Color(0xFF8FBF7A)
    val Err = Color(0xFFE07A5F)
    val KeyFg = Color(0xFFC9BDA8)
    // ANSI 8+8 色（暖色化）
    val Ansi = listOf(
        Color(0xFF5A5044), Color(0xFFE07A5F), Color(0xFF8FBF7A), Color(0xFFD9B45B),
        Color(0xFF7FA6C9), Color(0xFFC9A0B8), Color(0xFF7FBFB0), Color(0xFFE9E0CE),
    )
    val AnsiBright = listOf(
        Color(0xFF8A8069), Color(0xFFF0957A), Color(0xFFA8D68F), Color(0xFFE8CC7A),
        Color(0xFF9EC2E0), Color(0xFFDDB8CE), Color(0xFF9AD4C4), Color(0xFFFAF4E6),
    )
}

private val Mono = FontFamily.Monospace

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var histIdx by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var clearAt by remember { mutableStateOf(0) }
    val outScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // ensure 有解压 rootfs(最长 600s)+forkpty，阻塞 → IO 线程。
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching { LinuxBridge.ensure() }.getOrElse {
                error = "会话启动失败：${it.javaClass.simpleName} ${it.message}"
                return@withContext -10L
            }
        }
        if (r <= 0) error = LinuxBridge.lastError.ifBlank { "会话启动失败（$r）" }
    }
    // 150ms 轮询读 PTY，切 IO。
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
        // scrollTo（非 animate）：动画会连续重组抢主线程。
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
            .background(TermTheme.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---------- 标题行：真实终端标签页形态 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹", color = TermTheme.FgDim, fontSize = 20.sp, fontFamily = Mono,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onBack() }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "返回上一页" }
            )
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("chengyan", color = TermTheme.Fg, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold)
                    Text("@localhost", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = Mono)
                    Text(" ~", color = TermTheme.Prompt, fontSize = 13.sp, fontFamily = Mono)
                }
                Text(
                    com.chengyan.app.BuildConfig.BUILD_STAMP,
                    color = TermTheme.FgDim, fontSize = 9.sp, fontFamily = Mono, lineHeight = 11.sp
                )
            }
            // 状态：终端模拟器的纯文本指示灯
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 10.dp)
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        if (st.busy) TermTheme.Ok else TermTheme.Prompt,
                        androidx.compose.foundation.shape.CircleShape
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (st.busy) "RUN" else "RDY",
                    color = if (st.busy) TermTheme.Ok else TermTheme.FgDim,
                    fontSize = 11.sp, fontFamily = Mono, letterSpacing = 1.sp
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TermTheme.Line))

        // ---------- 输出：全屏平铺文本，无容器 ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val from = clearAt.coerceAtMost(st.output.length)
            val window = st.output.drop(from).let { if (it.length > 12_000) it.takeLast(12_000) else it }
            val rendered = remember(window) { renderAnsi(window) }
            Text(
                rendered.ifBlank { motd() },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = TermTheme.Fg,
                fontFamily = Mono,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(outScroll)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = "终端输出区" }
            )
            if (!follow) {
                Text(
                    "⤓ 最新",
                    color = Color(0xFF171310),
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .background(TermTheme.Prompt)
                        .clickable { follow = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = "滚动到最新输出" }
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TermTheme.Line))

        // ---------- 错误条（平铺一行，不是浮卡） ----------
        error?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2E1A14))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("! ", color = TermTheme.Err, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Text(it, color = TermTheme.Err, fontSize = 11.sp, fontFamily = FontCJK)
            }
        }

        // ---------- 快捷键：终端附件条（紧凑方块，等宽字体） ----------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(TermTheme.BgAlt)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TermKey("清屏", fg = TermTheme.KeyFg) { clearAt = st.output.length }
            TermKey("Tab", fg = TermTheme.KeyFg) { input += "    " }
            TermKey("↑", fg = TermTheme.KeyFg) {
                if (history.isNotEmpty()) {
                    histIdx = if (histIdx == -1) history.size - 1 else (histIdx - 1).coerceAtLeast(0)
                    input = history.getOrNull(histIdx) ?: input
                }
            }
            TermKey("↓", fg = TermTheme.KeyFg) {
                if (history.isNotEmpty() && histIdx >= 0) {
                    histIdx++
                    input = history.getOrNull(histIdx) ?: ""
                    if (histIdx >= history.size) histIdx = -1
                }
            }
            TermKey("^C", fg = TermTheme.Err) { LinuxBridge.interrupt() }
            TermKey("自检", fg = TermTheme.KeyFg) { error = LinuxBridge.diag() }
            TermKey("重建", fg = TermTheme.Err) {
                scope.launch(Dispatchers.IO) {
                    LinuxBridge.restart()
                    clearAt = 0
                }
            }
        }

        // ---------- 输入行：与输出同构的终端行（提示符 + 输入） ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(TermTheme.BgAlt)
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#", color = TermTheme.Prompt, fontSize = 14.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Text(
                " ", fontSize = 14.sp, fontFamily = Mono
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(color = TermTheme.Fg, fontFamily = Mono, fontSize = 14.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(TermTheme.Prompt),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "命令输入框" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
                decorationBox = { inner ->
                    Box(Modifier.wrapContentHeight(Alignment.CenterVertically)) {
                        if (input.isEmpty()) Text(
                            "type a command…", color = TermTheme.FgDim, fontSize = 13.sp, fontFamily = Mono
                        )
                        inner()
                    }
                }
            )
            Text(
                "⏎", color = TermTheme.KeyFg, fontSize = 18.sp, fontFamily = Mono,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { send(input); input = "" }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "执行命令" }
            )
        }
    }
}

/** 空态 motd：真实登录横幅形态。 */
private fun motd(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = TermTheme.FgDim, fontSize = 12.sp, fontFamily = Mono)) {
        append("Welcome to ChengYan terminal (Alpine 3.24, aarch64)\n")
        append("Kernel-type musl · shell bash/ash · real PTY via proot\n\n")
    }
    withStyle(SpanStyle(color = TermTheme.Fg, fontSize = 12.sp, fontFamily = Mono)) {
        append("* 文档: ")
    }
    withStyle(SpanStyle(color = TermTheme.Prompt, fontSize = 12.sp, fontFamily = Mono)) {
        append("ls / · bash --version · apk --version\n")
    }
    withStyle(SpanStyle(color = TermTheme.FgDim, fontSize = 12.sp, fontFamily = Mono)) {
        append("* 首次联网启动自动安装 bash；python3 用 apk add")
    }
}

/** 快捷键方块：46-48dp 触控目标，等宽字，无圆角浮块。 */
@Composable
private fun RowScope.TermKey(label: String, fg: Color, onClick: () -> Unit) {
    Text(
        label,
        color = fg,
        fontSize = 12.sp,
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .clickable(onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .semantics { contentDescription = "快捷键 $label" }
    )
}

/**
 * 输出渲染：SGR 前景色/加粗 → AnnotatedString 真彩色；其余序列剥除。
 * 协议噪音行（LOGIN_SUCCESSFUL/TERMINAL_READY）不进入显示层。
 */
private fun renderAnsi(out: String): AnnotatedString {
    // 先滤掉协议标记行
    val cleaned = out.replace(Regex("(?m)^(LOGIN_SUCCESSFUL|TERMINAL_READY|PREPARE_DONE)\\s*$"), "")
    return buildAnnotatedString {
        var color: Color? = null
        var bold = false
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isEmpty()) return
            withStyle(SpanStyle(
                color = color ?: TermTheme.Fg,
                fontWeight = if (bold) FontWeight.SemiBold else null
            )) { append(plain.toString()) }
            plain.setLength(0)
        }
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (c == '\u001B') {
                flush()
                i++
                when {
                    i < cleaned.length && cleaned[i] == '[' -> {
                        i++
                        val params = StringBuilder()
                        while (i < cleaned.length && cleaned[i].code in 0x20..0x3F) { params.append(cleaned[i]); i++ }
                        val final = if (i < cleaned.length) cleaned[i] else ' '
                        if (i < cleaned.length) i++
                        if (final == 'm') {
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
                    i < cleaned.length && cleaned[i] == ']' -> {
                        i++
                        while (i < cleaned.length) {
                            if (cleaned[i] == '\u0007') { i++; break }
                            if (cleaned[i] == '\u001B' && i + 1 < cleaned.length && cleaned[i + 1] == '\\') { i += 2; break }
                            i++
                        }
                    }
                    i < cleaned.length && cleaned[i].code in 0x40..0x5F -> i++
                }
            } else {
                plain.append(c)
                i++
            }
        }
        flush()
    }
}
