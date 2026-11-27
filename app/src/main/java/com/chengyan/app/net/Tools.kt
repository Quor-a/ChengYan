package com.chengyan.app.net

import com.chengyan.app.data.LinuxBridge

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 内置可执行工具：模型通过 function calling 调用，客户端本地执行。 */
object Tools {

    /** 供 /chat/completions 的 tools 字段使用。 */
    fun definitionsJson(): kotlinx.serialization.json.JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "get_current_time")
                put("description", "获取用户设备当前的日期和时间")
                put("parameters", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "calculator")
                put("description", "精确计算数学表达式，支持 + - * / % ^ 和括号")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("expression", buildJsonObject {
                            put("type", "string")
                            put("description", "数学表达式，如 (3+4)*12/5")
                        })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("expression")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "random_number")
                put("description", "生成 [min, max] 范围内的随机整数")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("min", buildJsonObject { put("type", "integer") })
                        put("max", buildJsonObject { put("type", "integer") })
                    })
                    put("required", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("min"))
                        add(kotlinx.serialization.json.JsonPrimitive("max"))
                    })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "web_search")
                put("description", "联网网页搜索，返回带标题/链接/摘要的结果列表。用于查资料、新闻、技术文档、事实核实。中文查询质量最佳；英文查询可用。需要更新或具体网页内容时配合 linux 工具 busybox wget 抓取全文。")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string"); put("description", "搜索关键词（中文或英文）")
                        })
                        put("count", buildJsonObject {
                            put("type", "number"); put("description", "返回条数 1-10，默认 6")
                        })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "linux")
                put("description", "操控设备内置的 Alpine Linux 3.24 容器（musl libc + busybox 工具集，proot 用户态隔离，无 root）。" +
                    "注意发行版差异：不是 Ubuntu——①默认 shell 是 busybox ash；bash 会在首次联网启动时自动安装（终端横幅见 BASH_OK），可用 bash 数组/[[ ]]；" +
                    "②默认没有 curl/python3/git/gcc，下载用 busybox `wget`，需要时 `apk add python3 gcc` 安装（需网络）；" +
                    "③busybox ps 输出格式与 procps 不同，查进程用 `ps` 或 `ps w`。支持文件读写、文本处理、管道等。" +
                    "AI 自主编排长程任务：run 启动后立即返回，用 poll 跟踪输出，用 wait 等退出，restart 硬中断。" +
                    "guest 的 / 即持久工作区（App 私有目录，重启保留）；写文件用 `echo 内容 > /a.txt`，读回用 `cat /a.txt`。")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject { put("type", "string"); put("description", "run=启动命令（立即返回）/ poll=查状态与输出 / wait=等退出（限时）/ restart=重建会话 / info=现场自检（so 加载通路、原生窗口、会话句柄、内存）") })
                        put("command", buildJsonObject { put("type", "string"); put("description", "run 时的命令（busybox ash 语法，POSIX 而非 bash），如 `ls /`、`echo hi > a.txt`、`wc -c a.txt`；支持引号包裹参数" ) })
                        put("timeout_secs", buildJsonObject { put("type", "number"); put("description", "wait 的最长等待秒数（默认 60，上限 600）" ) })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("action")) })
                })
            })
        })
    }
    fun execute(name: String, argumentsJson: String): String = runCatching {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val args = json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject
        fun str(key: String) = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun num(key: String): Double = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            ?: (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            } ?: 0.0

        when (name) {
            "get_current_time" -> {
                val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEE", java.util.Locale.CHINA)
                "当前时间：${f.format(java.util.Date())}（设备时区）"
            }
            "calculator" -> {
                val expr = str("expression") ?: throw IllegalArgumentException("缺少 expression")
                val v = Calculator.eval(expr)
                val fmt = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
                "计算结果：$expr = $fmt"
            }
            "random_number" -> {
                val min = num("min").toLong(); val max = num("max").toLong()
                "随机数（$min~$max）：${(min..max).random()}"
            }
            "linux" -> LinuxTool.run(str("action") ?: "run", str("command"), num("timeout_secs"))
            "web_search" -> WebSearch.search(
                str("query") ?: throw IllegalArgumentException("缺少 query"),
                (num("count").takeIf { it > 0 } ?: 6.0).toInt()
            )
            else -> "未知工具：$name"
        }
    }.getOrElse { "工具执行出错：${it.message}" }
}

/**
 * 单工具 `linux`：完整操控内置 Linux（busybox + ARM64 二进制翻译引擎，进程内无 root 运行）。
 * AI 自主编排长程任务：action=run 启动命令立即返回（不阻塞长任务）→ poll 轮询输出与状态
 * → wait 等退出（限时）→ restart 硬中断重来。没有内置固定流程。
 */
object LinuxTool {

    private const val POLL_MS = 150L
    private const val MAX_OUTPUT_CHARS = 8000

    fun run(action: String, command: String?, timeoutSecs: Double?): String {
        return try {
            when (action) {
                "run" -> {
                    val cmd = command?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: return "错误：run 需要 command（busybox 命令，如 `ls /rootfs`、`cat 文件`）"
                    val task = LinuxBridge.run(cmd)
                    when {
                        task == -3L -> "会话忙：上一个命令还在跑。先 poll 看状态，或 wait 等退出。"
                        // 失败必须带全文原因：只回一个 -10 等于让模型瞎猜（真机事故教训）。
                        task < 0 -> "启动失败（task=$task）\n" +
                            LinuxBridge.lastError.ifBlank { "（无详细原因，改用 action=info 看现场）" }
                        else -> {
                            // 短命令通常 <1s：等退出再返回（上限 5s），长任务立即返回让 AI poll
                            val deadline = System.currentTimeMillis() + 5000
                            var s = LinuxBridge.poll()
                            while (s.busy && System.currentTimeMillis() < deadline) {
                                Thread.sleep(100)
                                s = LinuxBridge.poll()
                            }
                            format(task, s)
                        }
                    }
                }
                "poll" -> {
                    val s = LinuxBridge.poll()
                    if (!s.busy && s.exit == null) "会话空闲：还没有任务在跑。"
                    else format(0, s)
                }
                "wait" -> {
                    val timeout = ((timeoutSecs ?: 60.0).coerceIn(1.0, 600.0) * 1000).toLong()
                    val deadline = System.currentTimeMillis() + timeout
                    var s = LinuxBridge.poll()
                    while (s.busy && System.currentTimeMillis() < deadline) {
                        Thread.sleep(POLL_MS)
                        s = LinuxBridge.poll()
                    }
                    if (s.busy) "仍在运行（等待超时 ${timeout / 1000}s）。继续用 poll 跟踪。" else format(0, s)
                }
                "restart" -> if (LinuxBridge.restart()) "会话已重建（硬中断，一切清空）。"
                    else "重建失败\n" + LinuxBridge.lastError.ifBlank { "（无详细原因，改用 action=info 看现场）" }
                // 现场自检：so 加载通路 / 原生窗口 / 会话句柄 / 内存。真机出问题时先跑这个。
                "info", "diag" -> LinuxBridge.diag()
                else -> "未知 action：$action（可用：run / poll / wait / restart / info）"
            }
        } catch (e: Throwable) {
            val st = if (e is OutOfMemoryError || e is StackOverflowError)
                "\n" + e.stackTraceToString().take(3000) + "\n" + LinuxBridge.diagHeap() else ""
            "linux 工具出错：${e::class.simpleName} ${e.message}$st"
        }
    }

    private fun format(task: Long, s: LinuxBridge.State): String {
        val head = if (task > 0) "任务 $task 已启动。" else ""
        // 措辞很重要：「已退出」会让人以为会话死了，其实只是上一条命令跑完了。
        val status = if (s.busy) "状态：运行中" else "状态：空闲（上一条命令 exit code=${s.exit}）"
        // 终端序列（颜色/光标控制等）对模型无意义，剥离后再给文本
        val text = stripAnsi(s.output)
        val body = when {
            text.isNotBlank() ->
                if (text.length > MAX_OUTPUT_CHARS) "…[截断，仅显示末尾 ${MAX_OUTPUT_CHARS} 字符]\n" + text.takeLast(MAX_OUTPUT_CHARS)
                else text
            s.outputLen > 0 -> "（输出缓冲有 ${s.outputLen} 字节，但解不出可显示文本——可能全是控制序列/二进制；终端页可见）"
            else -> "（无输出；命令可能只做了重定向/副作用，exit code=${s.exit}）"
        }
        return "[ch-linux build v1.9.11 | ${com.chengyan.app.data.NativeLib.summary()}]\n$head\n$status\n输出（stdout 与 stderr 合并）:\n$body"
    }

    /** 剥离 ANSI/VT100 转义序列：CSI 序列、OSC 及其他 ESC 引导的控制序列。 */
    private fun stripAnsi(s: String): String = s
        .replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")   // CSI（颜色、光标等）
        .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "") // OSC
        .replace(Regex("\u001B[@-_]"), "")                    // 其余单字符转义
        .replace("\r", "")
}

/** 递归下降算术表达式求值（+ - * / % ^ 和括号），无外部依赖。 */
object Calculator {
    fun eval(expr: String): Double {
        val s = expr.replace(Regex("\\s+"), "")
        val p = Parser(s)
        val v = p.parseExpr()
        if (p.pos < s.length) throw IllegalArgumentException("无法解析的表达式：$expr")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun parseExpr(): Double {
            var v = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]; val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun parseTerm(): Double {
            var v = parsePow()
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val op = s[pos++]; val r = parsePow()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }

        fun parsePow(): Double {
            val base = parseUnary()
            if (peek() == '^') { pos++; val e = parsePow(); return Math.pow(base, e) }
            return base
        }

        fun parseUnary(): Double {
            if (peek() == '-') { pos++; return -parseUnary() }
            if (peek() == '+') { pos++; return parseUnary() }
            return parseAtom()
        }

        fun parseAtom(): Double {
            if (peek() == '(') {
                pos++
                val v = parseExpr()
                if (peek() != ')') throw IllegalArgumentException("缺少右括号")
                pos++
                return v
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            if (start == pos) throw IllegalArgumentException("位置 $pos 处无法解析")
            return s.substring(start, pos).toDouble()
        }
    }
}
