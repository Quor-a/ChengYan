package com.chengyan.app.data

import java.util.concurrent.atomic.AtomicLong

/**
 * 澄言 Linux 桥（App 侧单例）：负责资产提取（busybox）、会话生命周期与状态缓存。
 * 线程模型：native exec 在 Rust 工作线程跑；Kotlin 侧只调用 poll。
 */
object LinuxBridge {

    /** 终端页与 `linux` 工具共用的输出镜像（native poll 的 JSON 解析结果缓存）。 */
    data class State(
        val busy: Boolean = false,
        val exit: Int? = null,
        val output: String = "",
        val outputLen: Int = 0,
    )

    private val machine = AtomicLong(0)
    private val state = java.util.concurrent.atomic.AtomicReference(State())
    private var ctx: android.content.Context? = null
    private val lock = Any()

    fun init(appContext: android.content.Context) {
        if (ctx != null) return
        synchronized(lock) {
            if (ctx != null) return
            ctx = appContext.applicationContext
        }
    }

    private fun dir(): java.io.File {
        val d = java.io.File(ctx!!.filesDir, "chlinux").apply { mkdirs() }
        val busy = java.io.File(d, "busybox")
        if (!busy.exists() || busy.length() == 0L) {
            ctx!!.assets.open("busybox").use { input ->
                busy.outputStream().use { input.copyTo(it) }
            }
            busy.setExecutable(true)
        }
        return d
    }

    /** 确保会话可用；返回 machine 句柄（<0 失败）。 */
    fun ensure(): Long {
        initNeeded()
        var m = machine.get()
        if (m > 0) return m
        synchronized(lock) {
            m = machine.get()
            if (m > 0) return m
            m = LinuxNative.start(dir().absolutePath)
            machine.set(m)
            return m
        }
    }

    private fun initNeeded() {
        if (ctx == null) throw IllegalStateException("LinuxBridge.init 未调用")
    }

    /** 启动命令；返回任务号（-3 = 忙）。 */
    fun run(cmd: String): Long {
        val m = ensure()
        if (m <= 0) return -10
        return LinuxNative.exec(m, cmd)
    }

    /** 轮询并刷新本地状态缓存。 */
    fun poll(): State {
        val m = machine.get()
        if (m <= 0) return state.get()
        val json = LinuxNative.poll(m) ?: return state.get()
        val s = parseState(json)
        state.set(s)
        return s
    }

    fun current(): State = state.get()

    /** 重建会话（硬中断：杀掉旧 machine，新的空白会话）。 */
    fun restart(): Boolean {
        synchronized(lock) {
            val m = machine.get()
            if (m > 0) LinuxNative.stop(m)
            machine.set(0)
            state.set(State())
            return ensure() > 0
        }
    }

    private fun parseState(json: String): State {
        val busy = json.contains("\"busy\":true")
        val exit = Regex("\"exit\":(-?\\d+)").find(json)?.let { it.groupValues[1].toIntOrNull() }
        val out = Regex("\"output\":\"(.*)\"\\s*$", RegexOption.DOT_MATCHES_ALL).find(json)?.let { it.groupValues[1] } ?: ""
        val outLen = Regex("\"len\":(\\d+)").find(json)?.let { it.groupValues[1].toIntOrNull() } ?: 0
        val sb = StringBuilder(out.length)
        var i = 0
        while (i < out.length) {
            val c = out[i]
            if (c == '\\' && i + 1 < out.length) {
                when (val n = out[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        val hex = out.getOrNull(i + 2)?.let { out.substring(i + 2, (i + 6).coerceAtMost(out.length)) }
                        val v = hex?.toIntOrNull(16)
                        if (v != null && hex?.length == 4) { sb.append(v.toChar()); i += 6 } else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return State(busy, exit, sb.toString(), outLen)
    }
}
