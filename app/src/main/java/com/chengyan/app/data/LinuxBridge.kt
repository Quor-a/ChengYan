package com.chengyan.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 澄言 Linux 桥（兼容层）：对外 API 保持不变（终端 UI 与 linux 工具都依赖它），
 * 内部切到 [TtyBridge] —— proot 承载自己交叉编译的 aarch64 用户空间。
 */
object LinuxBridge {

    data class State(
        val busy: Boolean = false,
        val exit: Int? = null,
        val output: String = "",
        val outputLen: Int = 0,
    )

    private val _flow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> get() = _flow

    val lastError: String get() = TtyBridge.lastError

    private var appCtx: android.content.Context? = null

    fun init(appContext: android.content.Context) {
        appCtx = appContext.applicationContext
        TtyBridge.init(appContext)
    }

    /** 供 NativeLib 诊断 nativeLibraryDir 用。 */
    fun appContext(): android.content.Context? = appCtx

    fun ensure(): Long = TtyBridge.ensure()

    fun run(cmd: String): Long = TtyBridge.run(cmd)

    /** poll 同时刷新 Compose 可观察的 StateFlow。 */
    fun poll(): State {
        val p = TtyBridge.poll()
        val s = State(p.busy, p.exit, p.output, p.outputLen)
        _flow.value = s
        return s
    }

    fun current(): State = poll()

    fun restart(): Boolean = TtyBridge.restart().also { poll() }

    /** ^C：真 PTY 线路规程发 SIGINT 给前台进程组（比整机重建更正确）。 */
    fun interrupt() = TtyBridge.interrupt()

    fun diag(): String = TtyBridge.diag()

    fun diagHeap(): String = "heap diagnostics n/a (proot runtime)"

    fun nativeLastError(): String = TtyBridge.lastError

    fun nativeLogTail(max: Int = 8192): String = "native log n/a (proot runtime)"

    fun nativeInfo(): String = TtyBridge.diag()
}
