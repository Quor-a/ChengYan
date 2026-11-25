package com.chengyan.app.data

import java.io.File

/**
 * FD-OS S0 能力探针（原创，见 fdos/probe/）。
 * 在真机上判定 memfd+execveat、pidfd、SCM_RIGHTS、seccomp 叠加、
 * ASHMEM、bionic dlopen-fd、jniLibs exec 等原语是否可用，产出 capmap JSON。
 * 结果同时落 <filesDir>/capmap.json，供诊断导出与降级决策。
 */
object FdosProbe {
    init { System.loadLibrary("fdosprobe") }

    external fun runAll(dataDir: String, libDir: String, debugAppdata: Boolean): String

    /** 跑探针并写 capmap.json；返回 JSON 文本。 */
    fun run(context: android.content.Context, debugAppdata: Boolean = false): String {
        val dir = context.applicationInfo.nativeLibraryDir
        val files = context.filesDir.absolutePath
        val json = runAll(files, dir, debugAppdata)
        // VPN_TUN 位（表3）：prepare() 不弹窗；null=已授权,非 null=需用户同意
        val vpn = runCatching {
            val intent = android.net.VpnService.prepare(context)
            if (intent == null) "\"name\":\"VPN_TUN\",\"result\":\"ok\",\"ms\":0,\"note\":\"VpnService 已授权（establish 可得 tun fd）\""
            else "\"name\":\"VPN_TUN\",\"result\":\"consent\",\"ms\":0,\"note\":\"需用户在 UI 授权 VpnService（establish 时弹窗）\""
        }.getOrNull() ?: "\"name\":\"VPN_TUN\",\"result\":\"denied\",\"ms\":0,\"note\":\"VpnService 不可用\""
        val merged = json.trimEnd().trimEnd('}') + ",$vpn]}"
        runCatching {
            File(files, "capmap.json").writeText(merged)
        }
        return merged
    }

    /** 把 JSON 转成可读摘要（探针名 结果 耗时 备注）。 */
    fun summarize(json: String): String = buildString {
        val name = Regex("\"name\":\"([^\"]+)\"")
        val res = Regex("\"result\":\"([^\"]+)\"")
        val ms = Regex("\"ms\":(\\d+)")
        val note = Regex("\"note\":\"([^\"]*)\"")
        val items = name.findAll(json).toList()
        items.forEachIndexed { i, m ->
            val r = res.findAll(json).drop(i).firstOrNull()?.groupValues?.get(1) ?: "?"
            val t = ms.findAll(json).drop(i).firstOrNull()?.groupValues?.get(1) ?: "?"
            val n = note.findAll(json).drop(i).firstOrNull()?.groupValues?.get(1) ?: ""
            appendLine("${m.groupValues[1].padEnd(16)} ${r.padEnd(8)} ${t}ms  $n")
        }
    }
}
