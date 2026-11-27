package com.chengyan.app.data

/**
 * 终端会话协议 —— 独立设计的哨兵退出码方案。
 *
 * 常驻 shell 里，宿主写入一条命令后必须知道「它什么时候跑完、退出码是多少」。
 * 不猜提示符（PS1 会变、程序输出会伪装成提示符），而是宿主自己注入一个
 * 带序号的退出码标记，命令完成后由 shell 打印出来。
 *
 * 协议：
 *   写入  <cmd> ; __cy_rc=$? ; printf '\n__CY_DONE_<id>_<rc>__\n' "$__cy_rc"
 *   读回  ...业务输出...\n__CY_DONE_<id>_<rc>__\n
 *   <id> 是递增序号（防历史输出误匹配），<rc> 是命令真实退出码。
 */
object SessionProtocol {

    private val MARKER = Regex("""__CY_DONE_(\d+)_(\w+)_(-?\d+)__""")

    /**
     * 构造写入 PTY 的命令行。salt 为本次命令随机数：
     * 防止业务输出恰好含 __CY_DONE_<当前id>_<数字>__ 被误判为完成标记（cat/echo 即可撞上）。
     */
    fun wrap(cmd: String, id: Long, salt: String): String =
        "$cmd ; __cy_rc=\$? ; printf '\\n__CY_DONE_${id}_${salt}_%d__\\n' \"\$__cy_rc\"\n"

    /**
     * 从累积输出里找指定序号+盐的完成标记，返回退出码；没有则返回 null。
     * 累积扫描（不是只看最新一块）：标记可能被 PTY 拆到多次 read。
     */
    fun exitCodeOf(output: String, id: Long, salt: String): Int? {
        var found: Int? = null
        MARKER.findAll(output).forEach { m ->
            if (m.groupValues[1].toLongOrNull() == id && m.groupValues[2] == salt) {
                found = m.groupValues[3].toIntOrNull()
            }
        }
        return found
    }

    /** 显示用：剥掉哨兵命令的回显行（PTY ECHO 会把注入的 printf 原样吐回）。 */
    fun stripMarkers(output: String): String {
        if (!output.contains("__CY_DONE_") && !output.contains("__cy_rc=")) return output
        val sb = StringBuilder(output.length)
        output.lineSequence().forEach { line ->
            if (!line.contains("__CY_DONE_") && !line.contains("__cy_rc=")) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
