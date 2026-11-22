package com.chengyan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.ui.theme.*

/** 轻量 Markdown 解析结果节点。 */
sealed interface MdNode {
    data class Para(val spans: List<MdSpan>, val isHeading: Int = 0) : MdNode
    data class Quote(val lines: List<String>) : MdNode
    data class ListBlock(val ordered: Boolean, val items: List<List<MdSpan>>) : MdNode
    data class Code(val lang: String, val code: String) : MdNode
    data object Rule : MdNode
}

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val inner: String) : MdSpan
    data class Italic(val inner: String) : MdSpan
    data class Code(val inner: String) : MdSpan
    data class Link(val label: String, val url: String) : MdSpan
}

/** 先按代码块切分，其余行按行级语法解析（输入均为纯文本，不解析 HTML，天然安全）。 */
fun parseMarkdown(src: String): List<MdNode> {
    val nodes = mutableListOf<MdNode>()
    val lines = src.replace("\r", "").split('\n')
    var i = 0
    var para = mutableListOf<String>()

    fun flush() {
        if (para.isNotEmpty()) {
            nodes += MdNode.Para(joinSpans(para))
            para.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val fence = Regex("^```\\s*(\\w*)\\s*$").find(line)
        if (fence != null) {
            flush()
            val lang = fence.groupValues[1].ifBlank { "text" }
            val code = StringBuilder(); i++
            while (i < lines.size && !Regex("^```\\s*$").matches(lines[i])) {
                code.appendLine(lines[i]); i++
            }
            i++
            nodes += MdNode.Code(lang, code.toString().trimEnd('\n'))
            continue
        }
        val heading = Regex("^(#{1,3})\\s+(.*)").find(line)
        if (heading != null) {
            flush()
            nodes += MdNode.Para(joinSpans(listOf(heading.groupValues[2])), heading.groupValues[1].length)
            i++; continue
        }
        if (Regex("^\\s*(?:[-*_]\\s*){3,}$").matches(line)) { flush(); nodes += MdNode.Rule; i++; continue }
        if (Regex("^\\s*>\\s?").containsMatchIn(line)) {
            flush()
            val q = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*>\\s?").containsMatchIn(lines[i])) {
                q += lines[i].replace(Regex("^\\s*>\\s?"), ""); i++
            }
            nodes += MdNode.Quote(q); continue
        }
        val listItem = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)").find(line)
        if (listItem != null) {
            flush()
            val ordered = listItem.groupValues[1].first().isDigit()
            val items = mutableListOf(listItem.groupValues[2])
            i++
            while (i < lines.size) {
                val m2 = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)").find(lines[i]) ?: break
                items += m2.groupValues[2]; i++
            }
            nodes += MdNode.ListBlock(ordered, items.map { joinSpans(listOf(it)) }); continue
        }
        if (line.isBlank()) { flush(); i++; continue }
        para += line; i++
    }
    flush()
    return nodes
}

/** 行内解析：`code`、**bold**、*italic*、[text](url)。 */
fun joinSpans(lines: List<String>): List<MdSpan> {
    val text = lines.joinToString("\n")
    val spans = mutableListOf<MdSpan>()
    val regex = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*\\n]+\\*|\\[[^]]+\\]\\(https?://[^)\\s]+\\))")
    var last = 0
    for (m in regex.findAll(text)) {
        if (m.range.first > last) spans += MdSpan.Text(text.substring(last, m.range.first))
        val t = m.value
        spans += when {
            t.startsWith("`") -> MdSpan.Code(t.trim('`'))
            t.startsWith("**") -> MdSpan.Bold(t.trim('*'))
            t.startsWith("*") -> MdSpan.Italic(t.trim('*'))
            else -> {
                val mm = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)\\)").matchEntire(t)!!
                MdSpan.Link(mm.groupValues[1], mm.groupValues[2])
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) spans += MdSpan.Text(text.substring(last))
    return spans
}

@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        parseMarkdown(content).forEach { node -> MdNodeView(node) }
    }
}

@Composable
private fun MdNodeView(node: MdNode) {
    when (node) {
        is MdNode.Para -> {
            val style = when (node.isHeading) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                3 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyMedium
            }
            Text(
                buildAnnotated(node.spans),
                style = style,
                fontWeight = if (node.isHeading > 0) FontWeight.SemiBold else null,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
        is MdNode.Quote -> Row {
            Box(Modifier.width(3.dp).height(IntrinsicSize.Min).background(Accent2, RoundedCornerShape(2.dp)))
            Text(buildAnnotated(joinSpans(node.lines)), style = MaterialTheme.typography.bodyMedium,
                color = Ink2, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        is MdNode.ListBlock -> Column(Modifier.padding(vertical = 3.dp)) {
            node.items.forEachIndexed { idx, item ->
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text(if (node.ordered) "${idx + 1}. " else "•  ",
                        style = MaterialTheme.typography.bodyMedium, color = Accent)
                    Text(buildAnnotated(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is MdNode.Code -> CodeBlock(node.lang, node.code)
        MdNode.Rule -> Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(vertical = 6.dp).background(CodeBg, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().background(CodeHead, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(lang.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF8A8069))
            Text(
                if (copied) "已复制" else "复制",
                fontSize = 11.sp,
                color = if (copied) Ok else Color(0xFF8A8069),
                modifier = Modifier.chip().clickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        kotlinx.coroutines.delay(1500)
                        copied = false
                    }
                }
            )
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = CodeFg,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)
        )
    }
}

private fun Modifier.chip() = this.then(
    Modifier.background(Color(0xFF3A3428), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
)

@Composable
fun buildAnnotated(spans: List<MdSpan>): AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        spans.forEach { s ->
            when (s) {
                is MdSpan.Text -> append(s.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.inner) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.inner) }
                is MdSpan.Code -> withStyle(androidx.compose.ui.text.SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = Paper3,
                    color = AccentInk
                )) { append(s.inner) }
                is MdSpan.Link -> withStyle(androidx.compose.ui.text.SpanStyle(
                    color = AccentInk,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )) { append(s.label) }
            }
        }
    }
}

