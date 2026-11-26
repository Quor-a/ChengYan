package com.chengyan.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话协议单测 —— 验证哨兵退出码方案的正确性。
 * 无 Android 依赖，纯 JVM 运行。
 */
class SessionProtocolTest {

    @Test
    fun wrap_contains_marker_with_id() {
        val line = SessionProtocol.wrap("echo hi", 7L)
        assertTrue(line.contains("__CY_DONE_7_"))
        assertTrue(line.startsWith("echo hi"))
        assertTrue(line.contains("__cy_rc"))
    }

    @Test
    fun exitCode_success() {
        val out = "hello\n__CY_DONE_1_0__\n"
        assertEquals(0, SessionProtocol.exitCodeOf(out, 1L))
    }

    @Test
    fun exitCode_failure() {
        val out = "busybox: not found\n__CY_DONE_2_127__\n"
        assertEquals(127, SessionProtocol.exitCodeOf(out, 2L))
    }

    @Test
    fun exitCode_negative() {
        val out = "\n__CY_DONE_3_-1__\n"
        assertEquals(-1, SessionProtocol.exitCodeOf(out, 3L))
    }

    @Test
    fun exitCode_ignores_old_markers() {
        // 历史命令的标记不能误匹配当前序号
        val out = "__CY_DONE_1_0__\nreal output\n__CY_DONE_2_1__\n"
        assertEquals(1, SessionProtocol.exitCodeOf(out, 2L))
        assertNull(SessionProtocol.exitCodeOf(out, 99L))
    }

    @Test
    fun exitCode_split_across_reads() {
        // 标记被 PTY 拆到两块：累积输出里仍能扫到
        val chunk1 = "some output\n__CY_DO"
        val chunk2 = "NE_4_0__\n"
        val acc = chunk1 + chunk2
        assertEquals(0, SessionProtocol.exitCodeOf(acc, 4L))
    }

    @Test
    fun stripMarkers_removes_echo_only() {
        val raw = listOf(
            "real line 1",
            "$ echo hi ; __cy_rc=\$? ; printf '\\n__CY_DONE_5_%d__\\n' \"\$__cy_rc\"",
            "__CY_DONE_5_0__",
            "real line 2",
        ).joinToString("\n")
        val clean = SessionProtocol.stripMarkers(raw)
        assertTrue(clean.contains("real line 1"))
        assertTrue(clean.contains("real line 2"))
        assertFalse(clean.contains("__CY_DONE_"))
        assertFalse(clean.contains("__cy_rc="))
    }

    @Test
    fun stripMarkers_noop_without_markers() {
        val raw = "plain output\nwith lines\n"
        assertEquals(raw, SessionProtocol.stripMarkers(raw))
    }
}
