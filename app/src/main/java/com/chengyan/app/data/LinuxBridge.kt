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

    /**
     * Compose 可观察的状态镜像。AtomicReference 本身不触发重组——终端页必须收集这个
     * StateFlow 才能看到 poll 结果。每次 state 更新后同步 emit。
     */
    private val _stateFlow = kotlinx.coroutines.flow.MutableStateFlow(State())
    val stateFlow: kotlinx.coroutines.flow.StateFlow<State> get() = _stateFlow

    /** 统一更新缓存 + 通知 Compose。所有 state.set 都改走这里。 */
    private fun publish(s: State) {
        state.set(s)
        _stateFlow.value = s
    }

    fun init(appContext: android.content.Context) {
        if (ctx != null) return
        synchronized(lock) {
            if (ctx != null) return
            ctx = appContext.applicationContext
        }
    }

    /** 供 [NativeLib] 取 Context 用（它需要 context 才能走 APK 解包通路）。 */
    internal fun appContext(): android.content.Context? = ctx

    /**
     * 最近一次失败的完整原因（终端页与 `linux` 工具都会原样显示）。
     *
     * 真机事故教训：`run()` 只回一个 -10，模型只能转述「启动失败（task=-10）」，
     * 真正的原因（so 加载失败 / rootfs 解包失败）被吞在状态缓存里，来回猜了十几轮。
     * 现在任何失败都必须把全文写进这里。
     */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(msg: String): Long {
        lastError = msg
        publish(State(output = msg))
        return -10
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
        Rootfs.ensure(ctx!!)
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
            // 0) so 必须先在。放在 LinuxNative 的 <clinit> 之外，失败也不会把类弄脏。
            if (!NativeLib.ensure(ctx)) {
                return fail(NativeLib.failureText())
            }
            // 首启解包 rootfs：失败必须显式报错，否则 native start 会在空 rootfs 上"成功"，
            // 之后每条命令都静默失败，表现为"整个终端都不行"。
            val dataDir = try {
                dir()
            } catch (t: Throwable) {
                return fail("[ch-rootfs] 解包 rootfs 失败：${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTraceToString().take(2000))
            }
            val rootfs = java.io.File(dataDir, "rootfs")
            if (!rootfs.isDirectory || !java.io.File(rootfs, "bin/busybox.static").isFile) {
                return fail("[ch-rootfs] rootfs 解包失败：缺少 $rootfs/bin/busybox.static。\n" +
                    "请清 App 数据后重试（设置 → 应用 → 澄言 → 存储 → 清除数据）。")
            }
            m = try {
                LinuxNative.start(dataDir.absolutePath)
            } catch (t: Throwable) {
                return fail("[ch-native] start() 抛异常（so 已加载，调用失败）：\n" +
                    "${t.javaClass.simpleName}: ${t.message}\n" + t.stackTraceToString().take(2000))
            }
            if (m > 0) {
                machine.set(m)
                lastError = ""
            } else {
                // 不要把失败句柄缓存进 machine，允许下次重试；给出可读原因。
                val why = when (m) {
                    -1L -> "参数错误"
                    -2L -> "无法创建 rootfs 目录"
                    -3L -> "引擎会话创建失败（busybox ELF 加载失败）"
                    else -> "未知错误"
                }
                return fail("[ch-native] 会话启动失败（$m）：$why\n${nativeInfo()}")
            }
            return m
        }
    }

    private fun initNeeded() {
        if (ctx == null) throw IllegalStateException("LinuxBridge.init 未调用")
    }

    /** 启动命令；返回任务号（-3 = 忙）。异常时写诊断到状态缓存。 */
    fun run(cmd: String): Long {
        val m = try { ensure() } catch (t: Throwable) {
            return fail("[ch-diag] ensure 异常 ${t::class.simpleName}: ${t.message}\n" +
                t.stackTraceToString().take(4000) + "\nheap: ${usedHeap()}")
        }
        if (m <= 0) return -10
        return try { LinuxNative.exec(m, cmd) } catch (t: Throwable) {
            // 拿到无效句柄（会话被 native 侧丢弃）时清掉句柄，下次自动重建
            machine.compareAndSet(m, 0)
            fail("[ch-diag] exec 异常 ${t::class.simpleName}: ${t.message}\n" +
                t.stackTraceToString().take(4000) + "\nheap: ${usedHeap()}")
            -11
        }
    }

    /** 轮询并刷新本地状态缓存。OOM/异常时把 Java 调用栈写进输出（终端页可见），便于真机定位。 */
    fun poll(): State {
        val m = machine.get()
        if (m <= 0) return state.get()
        val json = try { LinuxNative.poll(m) } catch (t: Throwable) {
            machine.compareAndSet(m, 0)
            val s = State(output = "[ch-diag] poll 异常 ${t::class.simpleName}: ${t.message}\n" +
                t.stackTraceToString().take(4000) + "\nheap: ${usedHeap()}")
            publish(s); return s
        } ?: return state.get()
        // "{}" = native 侧没有这个句柄（会话已失效）：清掉句柄，让下一条命令自动重建，
        // 而不是一直对着一个死句柄空转。
        if (json.length <= 2) {
            if (machine.compareAndSet(m, 0)) {
                val s = State(output = state.get().output +
                    "\n[ch-session] 会话句柄失效，下次执行会自动重建。\n")
                publish(s); return s
            }
            return state.get()
        }
        if (json.length > 8 * 1024 * 1024) {
            val s = State(output = "[ch-diag] poll JSON ${json.length} 字符超限，跳过解析。heap: used=${usedHeap()}")
            publish(s); return s
        }
        val s = try { parseState(json) } catch (t: Throwable) {
            State(output = "[ch-diag] parseState 异常 ${t::class.simpleName}: ${t.message}\n" +
                "sizes: $diagSizes\n" +
                t.stackTraceToString().take(4000) + "\nheap: used=${usedHeap()}")
        }
        publish(s)
        return s
    }

    private fun usedHeap(): String = diagHeap()

    @Volatile private var diagSizes: String = ""

    /** 诊断信息：Java 堆 / native 占用（真机 OOM 定位用）。 */
    fun diagHeap(): String = try {
        val rt = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(rt)
        "javaHeap=${rt.dalvikPrivateDirty}KB native=${rt.nativePrivateDirty}KB"
    } catch (e: Exception) { "?" }

    fun current(): State = state.get()

    /** 重建会话（硬中断：杀掉旧 machine，新的空白会话）。 */
    fun restart(): Boolean {
        synchronized(lock) {
            val m = machine.get()
            if (m > 0) {
                runCatching { LinuxNative.stop(m) }
            }
            machine.set(0)
            publish(State())
            lastError = ""
            return ensure() > 0
        }
    }

    /** 原生侧自检（so 已加载才有意义）：window 能否预留、Tier1 命中数等。 */
    fun nativeInfo(): String = try {
        if (!NativeLib.ensure(ctx)) NativeLib.failureText() else LinuxNative.info() ?: "（info 返回 null）"
    } catch (t: Throwable) {
        "[ch-native] info 异常 ${t::class.simpleName}: ${t.message}"
    }

    /** 一条命令给出全部现场：so 加载通路、原生自检、会话句柄、内存。 */
    fun diag(): String = buildString {
        append("[ch-diag] 澄言 Linux 运行现场\n")
        append("so: ").append(NativeLib.summary()).append('\n')
        append("machine: ").append(machine.get()).append('\n')
        append("busy: ").append(state.get().busy).append("｜exit: ").append(state.get().exit).append('\n')
        append("heap: ").append(usedHeap()).append('\n')
        append("native: ").append(nativeInfo()).append('\n')
        if (lastError.isNotBlank()) append("lastError:\n").append(lastError).append('\n')
        if (!NativeLib.loaded) append('\n').append(NativeLib.detail).append('\n')
    }

    private fun parseState(json: String): State {
        val busy = json.contains("\"busy\":true")
        val exit = Regex("\"exit\":(-?\\d+)").find(json)?.let { it.groupValues[1].toIntOrNull() }
        val outLen = Regex("\"len\":(\\d+)").find(json)?.let { it.groupValues[1].toIntOrNull() } ?: 0
        // output 是 JSON 最后一个字段：用 indexOf 有界提取，硬上限 2MB——
        // 贪婪正则 + groupValues 在异常大 json 上会产生巨型字符串拷贝（真机 OOM 268435472 的直接嫌疑）。
        val out = run {
            val key = "\"output\":\""
            val s0 = json.indexOf(key)
            if (s0 < 0) "" else {
                val s = s0 + key.length
                val e = json.lastIndexOf('"').coerceAtLeast(s)
                val raw = json.substring(s, e)
                if (raw.length > 2 * 1024 * 1024) raw.takeLast(2 * 1024 * 1024) else raw
            }
        }
        // json/out 尺寸随诊断输出（OOM 定位用）
        diagSizes = "json=${json.length} out=${out.length}"
        return State(busy, exit, decodeOutput(out), outLen)
    }

    /** JSON 转义输出 → 原始字节 → UTF-8 文本。纯函数，JVM 单元测试覆盖。 */
    internal fun decodeOutput(out: String): String {
        val bytes = java.io.ByteArrayOutputStream(out.length)
        var i = 0
        while (i < out.length) {
            val c = out[i]
            if (c == '\\' && i + 1 < out.length) {
                when (val n = out[i + 1]) {
                    'n' -> { bytes.write(0x0A); i += 2 }
                    'r' -> { bytes.write(0x0D); i += 2 }
                    't' -> { bytes.write(0x09); i += 2 }
                    '"' -> { bytes.write(0x22); i += 2 }
                    '\\' -> { bytes.write(0x5C); i += 2 }
                    'u' -> {
                        val hex = out.getOrNull(i + 2)?.let { out.substring(i + 2, (i + 6).coerceAtMost(out.length)) }
                        val v = hex?.toIntOrNull(16)
                        when {
                            v != null && hex?.length == 4 -> {
                                // native 逐字节转义：\u00XX 即原字节；≥0x100 的码点按 UTF-8 写入兜底
                                if (v in 0x00..0xFF) bytes.write(v)
                                else bytes.write(String(Character.toChars(v)).toByteArray(Charsets.UTF_8))
                                i += 6
                            }
                            else -> { bytes.write(n.code); i += 2 }
                        }
                    }
                    else -> { bytes.write(n.code); i += 2 }
                }
            } else { bytes.write(c.code); i += 1 }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
