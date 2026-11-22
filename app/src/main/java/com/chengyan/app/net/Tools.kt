package com.chengyan.app.net

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
    }

    /** 执行工具调用，返回结果文本（错误也作为文本回传给模型）。 */
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
            else -> "未知工具：$name"
        }
    }.getOrElse { "工具执行出错：${it.message}" }
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
