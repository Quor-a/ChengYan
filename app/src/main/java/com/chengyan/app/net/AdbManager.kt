package com.chengyan.app.net

import android.content.Context
import com.chengyan.app.net.adb.AdbClient
import com.chengyan.app.net.adb.AdbKeys
import com.chengyan.app.net.adb.AdbPairClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PrivateKey

/**
 * 无线调试 ADB（Android 11+ 官方协议）：
 * 配对 = TLS1.3 + SPAKE2（6 位配对码）→ 设备把本机公钥写入 adb_keys 白名单；
 * 连接 = adb 协议（CNXN + RSA 签名认证）→ shell / push。Shizuku 与 ADB 双通道互为备份。
 */
object AdbManager {

    private const val PREF = "adb_wireless"

    data class State(val paired: Boolean, val host: String?, val port: Int, val lastError: String?)

    @Volatile private var client: AdbClient? = null

    fun state(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return State(p.getBoolean("paired", false), p.getString("host", null), p.getInt("port", 0), p.getString("err", null))
    }

    /** 无线调试配对（开发者选项 → 无线调试 → 使用配对码配对设备）。阻塞，后台线程调用。 */
    suspend fun pair(ctx: Context, host: String, pairPort: Int, code: String): String = withContext(Dispatchers.IO) {
        val r = AdbPairClient.pair(host, pairPort, code)
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (r.ok) {
            p.putBoolean("paired", true); p.putString("host", host); p.putString("err", null)
        } else {
            p.putString("err", r.message)
        }
        p.apply()
        if (r.ok) "配对成功：本机公钥已写入设备白名单（指纹 ${AdbKeys.fingerprint(ctx)}）" else "配对失败：${r.message}"
    }

    /** 连接 adb 端口并复用连接。 */
    suspend fun connect(ctx: Context, host: String, port: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            client?.close(); client = null
            val key: PrivateKey = AdbKeys.privateKey(ctx)
            val c = AdbClient()
            c.connect(host, port, key)
            client = c
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("paired", true).putString("host", host).putInt("port", port).apply()
            "已连接 $host:$port"
        }.getOrElse { "连接失败：${it.message}" }
    }

    /** 执行 shell 命令（连接已建则复用，否则按保存的地址自动重连）。返回输出或错误。 */
    suspend fun shell(ctx: Context, cmd: String): String = withContext(Dispatchers.IO) {
        val st = state(ctx)
        if (!st.paired) return@withContext "ERR: 未配对/连接（设置 › 系统能力 › 无线调试 ADB）"
        runCatching {
            val c = client ?: throw IllegalStateException("未连接")
            c.shell(cmd)
        }.getOrElse {
            // 断线重连一次
            runCatching {
                client?.close(); client = null
                val c = AdbClient(); c.connect(st.host!!, st.port, AdbKeys.privateKey(ctx))
                client = c
                c.shell(cmd)
            }.getOrElse { "ERR: ADB 连接失败（设备无线调试端口可能已变，请到设置重连）：" + it.message }
        }
    }

    fun connected(): Boolean = runCatching { client != null && state(com.chengyan.app.ChengYanApp.instance).paired }.getOrDefault(false)
}
