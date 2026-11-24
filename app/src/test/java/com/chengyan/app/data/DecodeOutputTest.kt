package com.chengyan.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * decodeOutput（JSON 转义输出→UTF-8 文本）单元测试。
 * 历史教训：外层 else 丢 i++ 的死循环（任何输出必 OOM）本可被 10 行测试在本地抓住——
 * 此后该函数的任何改动必须先过这里。
 */
class DecodeOutputTest {

    @Test fun plainAscii() {
        assertEquals("hello\n", LinuxBridge.decodeOutput("hello\\n"))
    }

    @Test fun empty() {
        assertEquals("", LinuxBridge.decodeOutput(""))
    }

    @Test fun escapeBundles() {
        assertEquals("\n\r\t\"\\", LinuxBridge.decodeOutput("\\n\\r\\t\\\"\\\\"))
    }

    @Test fun unicodeByteEscapes() {
        // native 逐字节 \u00XX：中文「你」= E4 BD A0
        assertEquals("你", LinuxBridge.decodeOutput("\\u00e4\\u00bd\\u00a0"))
    }

    @Test fun mixed() {
        assertEquals("a你b\nc", LinuxBridge.decodeOutput("a\\u00e4\\u00bd\\u00a0b\\nc"))
    }

    @Test fun trailingBackslash() {
        // 结尾孤立反斜杠：不应死循环，按字面处理
        assertEquals("ab\\", LinuxBridge.decodeOutput("ab\\"))
    }

    @Test fun trailingBackslashU() {
        assertEquals("au", LinuxBridge.decodeOutput("a\\u"))
    }

    @Test fun badHexFallsBack() {
        // \u 后跟非 4 位 hex：按字面写 'u'，游标 +2
        assertEquals("auzz", LinuxBridge.decodeOutput("a\\uzz"))
    }

    @Test fun performance_50kChars() {
        // 5 万字符普通输出应在 1 秒内完成（死循环版本会在这里直接卡死/超时）
        val input = ("hello world\\n" + "\\u00e4\\u00bd\\u00a0").let { unit -> unit.repeat(50000 / unit.length + 1) }.take(50000)
        val t0 = System.currentTimeMillis()
        val r = LinuxBridge.decodeOutput(input)
        val ms = System.currentTimeMillis() - t0
        assertEquals('h', r.first())
        org.junit.Assert.assertTrue("decode took ${ms}ms (>1000ms 意味着死循环回归)", ms < 1000)
    }

    @Test fun multiByteUtf8Sentence() {
        // 中文整句 + ASCII 混排
        val raw = "目录 /etc 共 42 项".toByteArray(Charsets.UTF_8)
        val esc = raw.joinToString("") { String.format("\\u00%02x", it) }
        assertEquals("目录 /etc 共 42 项", LinuxBridge.decodeOutput(esc))
    }
}
