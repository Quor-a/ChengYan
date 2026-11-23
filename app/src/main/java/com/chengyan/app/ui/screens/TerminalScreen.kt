package com.chengyan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.chengyan.app.ui.components.monoSafe
import com.chengyan.app.ui.theme.FontCJK
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.LinuxBridge
import com.chengyan.app.ui.theme.Ink
import com.chengyan.app.ui.theme.AccentInk
import com.chengyan.app.ui.theme.Ok
import com.chengyan.app.ui.theme.Card
import com.chengyan.app.ui.theme.Ink2
import com.chengyan.app.ui.theme.Ink3
import com.chengyan.app.ui.theme.Ok
import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

/** 终端配色（深墨底 × 暖纸字，澄言原创）。 */
private val TermBg = Color(0xFF191613)
private val TermFg = Color(0xFFD8D2C4)
private val TermDim = Color(0xFF7A7264)

/**
 * Linux 终端（澄言原创风格）：进程内真实 Linux（busybox × ARM64 二进制翻译，无 root）。
 * 功能：输入命令回车执行 / 运行状态实时轮询 / 输出跟随滚动（上滑停随、↓ 回底恢复）/
 * 快捷键（Tab ↑ ↓ ^C）/ 硬中断重建 / 清屏 / 输入历史。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) } // 用户输入历史（↑↓ 翻）
    var histIdx by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var clearAt by remember { mutableStateOf(0) } // 清屏游标（本地显示截断）
    val outScroll = rememberScrollState()

    // 150ms 轮询 native 输出（会话全局唯一，终端页与 linux 工具共享同一状态）
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            runCatching { LinuxBridge.poll() }.onFailure { error = it.message }
        }
    }
    val st = LinuxBridge.current()
    // 上滑停止跟随；↓ 或触底恢复
    LaunchedEffect(outScroll) {
        var prev = 0
        snapshotFlow { outScroll.value }.collect { v ->
            if (v < prev) follow = false
            if (v >= outScroll.maxValue) follow = true
            prev = v
        }
    }
    LaunchedEffect(st.output.length, follow) {
        if (follow) outScroll.animateScrollTo(outScroll.maxValue)
    }

    fun send(cmd: String) {
        if (cmd.isBlank()) return
        history = history + cmd
        histIdx = -1
        val r = LinuxBridge.run(cmd)
        if (r < 0) error = if (r == -3L) "会话忙：上一个命令还在运行（等退出或点「重建会话」）" else "启动失败（$r）"
        else error = null
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹ 返回", color = Ink2, fontSize = 14.sp, fontFamily = FontCJK, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("Linux 终端", color = TermFg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontCJK)
            Spacer(Modifier.weight(1f))
            Text(
                if (st.busy) "● 运行中" else if (st.exit != null) "○ 退出 ${st.exit}" else "○ 空闲",
                color = if (st.busy) Ok else Ink3, fontSize = 12.sp, fontFamily = FontCJK
            )
        }
        // 输出区
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            Surface(color = TermBg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxSize()) {
                val text = renderOut(st.output.drop(clearAt), st.busy, st.exit)
                Text(
                    text.ifBlank {
                        "澄言 Linux · 进程内 busybox（ARM64 二进制翻译，无 root）\n输入命令回车执行。试：ls / 或 echo hello"
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = TermFg,
                    fontFamily = FontCJK,
                    modifier = Modifier.verticalScroll(outScroll).padding(12.dp)
                )
            }
            if (!follow) {
                Surface(
                    onClick = { follow = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Card, shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(32.dp)
                ) { Box(contentAlignment = Alignment.Center) { Text("↓", color = Ink2, fontSize = 14.sp) } }
            }
        }
        error?.let {
            Text(it, color = Color(0xFFE07050), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        // 快捷键行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            QuickKey("^C") { LinuxBridge.restart(); clearAt = 0 }
            Spacer(Modifier.weight(1f))
            QuickKey("重建") { LinuxBridge.restart(); clearAt = 0 }
        }
        // 输入行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$ ", color = AccentInk, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(color = TermFg, fontFamily = monoSafe(input), fontSize = 14.sp),
                cursorBrush = SolidColor(AccentInk),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
                decorationBox = { inner ->
                    Box { if (input.isEmpty()) Text("输入 busybox 命令…", color = Ink3, fontSize = 13.sp, fontFamily = FontCJK); inner() }
                }
            )
            Surface(
                onClick = { send(input); input = "" },
                shape = RoundedCornerShape(10.dp), color = AccentInk,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text("运行", color = Color(0xFF26221C), fontSize = 13.sp, fontFamily = FontCJK, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun QuickKey(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = Ink2, fontFamily = FontCJK,
        modifier = Modifier
            .background(Color(0xFF3A3428), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** 输出渲染：剥 ANSI 转义序列（CSI/OSC），保留可见文本，附退出状态行。 */
private fun renderOut(out: String, busy: Boolean, exit: Int?): String {
    val sb = StringBuilder(out.length)
    var i = 0
    while (i < out.length) {
        val c = out[i]
        if (c == '') {
            i++
            if (i < out.length && out[i] == '[') {
                i++
                while (i < out.length && !out[i].isLetter()) i++
                if (i < out.length) i++
            } else if (i < out.length && out[i] == ']') {
                i++
                while (i < out.length && out[i] != '') i++
                if (i < out.length) i++
            }
        } else { sb.append(c); i++ }
    }
    val status = if (busy) "" else if (exit != null) "\n—— 已退出（$exit）——" else ""
    return sb.toString() + status
}
