package com.chengyan.app.net

import android.content.Context
import com.chengyan.app.ChengYanApp
import com.chengyan.app.net.adb.AdbClient
import com.chengyan.app.net.adb.AdbKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台虚拟屏（对标 zorv AI 的后台虚拟屏，授权通道改为 Shizuku + 无线调试 ADB 双方案）：
 * shell uid 经 app_process 跑 VdHelper 建「看不见的独立屏」→ AI 用 am start --display 在其上启动 App，
 * input/screencap 同样带 --display 操作，全程用户主屏不受干扰。
 * 执行通道优先级：Shizuku（已授权）→ 无线调试 ADB（已配对）→ 报错引导。
 */
object VirtualScreenManager {

    data class Info(val id: Int, val w: Int, val h: Int, val dpi: Int)

    // ---------- shell 通道（Shizuku 优先，ADB 兜底） ----------

    private fun channel(ctx: Context): String = when {
        ShizukuBridge.available() -> "shizuku"
        AdbManager.connected() -> "adb"
        else -> ""
    }

    /** 在 shell 通道执行命令；返回输出（失败抛异常）。 */
    private suspend fun shell(ctx: Context, cmd: String): String = withContext(Dispatchers.IO) {
        when (channel(ctx)) {
            "shizuku" -> ShizukuBridge.exec(cmd) ?: throw IllegalStateException("Shizuku 执行失败")
            "adb" -> {
                val out = AdbManager.shell(ctx, cmd)
                if (out.startsWith("ERR")) throw IllegalStateException(out)
                out
            }
            else -> throw IllegalStateException("需要 Shizuku 或 无线调试 ADB（设置 › 系统能力）任一授权")
        }
    }

    // ---------- 虚拟屏生命周期 ----------

    /** 创建虚拟屏；返回 Info。 */
    suspend fun create(ctx: Context, w: Int = 720, h: Int = 1600, dpi: Int = 320): Info = withContext(Dispatchers.IO) {
        val apk = ctx.packageCodePath
        val cmd = "app_process -Djava.class.path=$apk /system/bin --nice-name=chengyan-vscreen com.chengyan.app.shell.VdHelper create $w $h $dpi"
        val out = shell(ctx, cmd).trim()
        val ok = Regex("^OK (\\d+)").find(out) ?: throw IllegalStateException("创建虚拟屏失败：$out")
        Info(id = ok.groupValues[1].toInt(), w = w, h = h, dpi = dpi)
    }

    /** 当前虚拟屏信息（读 /data/local/tmp 标记 + dumpsys 校验存活）。 */
    suspend fun info(ctx: Context): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val line = shell(ctx, "cat /data/local/tmp/chengyan_vscreen 2>/dev/null").trim()
            if (line.isBlank()) return@withContext null
            val p = line.split(" ")
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@withContext null
            val live = shell(ctx, "dumpsys display | grep -E 'mDisplayId=$id' | head -1").isNotBlank() ||
                    shell(ctx, "dumpsys display").contains("mDisplayId=$id")
            if (live) Info(id, p.getOrNull(1)?.toIntOrNull() ?: 720, p.getOrNull(2)?.toIntOrNull() ?: 1600, p.getOrNull(3)?.toIntOrNull() ?: 320) else null
        }.getOrNull()
    }

    suspend fun destroy(ctx: Context): String = withContext(Dispatchers.IO) {
        val cmd = "app_process -Djava.class.path=${ctx.packageCodePath} /system/bin com.chengyan.app.shell.VdHelper destroy"
        runCatching { shell(ctx, cmd) }.getOrElse { "销毁标记已清理（display 会在进程退出时回收）" }
        "虚拟屏已销毁"
    }

    // ---------- 虚拟屏上的操作 ----------

    suspend fun launchApp(ctx: Context, pkg: String, activity: String? = null): String {
        val info = info(ctx) ?: throw IllegalStateException("虚拟屏未创建，先 create")
        val comp = if (activity.isNullOrBlank()) " $pkg" else " -n $pkg/${activity.removePrefix("$pkg/")}"
        return shell(ctx, "am start --display ${info.id}$comp").trim().lines().firstOrNull() ?: "已发送启动"
    }

    suspend fun tap(ctx: Context, x: Int, y: Int): String {
        val info = info(ctx) ?: throw IllegalStateException("虚拟屏未创建")
        return runCatching { shell(ctx, "input --display ${info.id} tap $x $y") }
            .recover { shell(ctx, "input tap $x $y") } // 老版本 input 无 --display
            .getOrDefault("").trim().ifBlank { "已点击 ($x,$y)" }
    }

    suspend fun swipe(ctx: Context, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300): String {
        val info = info(ctx) ?: throw IllegalStateException("虚拟屏未创建")
        return runCatching { shell(ctx, "input --display ${info.id} swipe $x1 $y1 $x2 $y2 $ms") }
            .recover { shell(ctx, "input swipe $x1 $y1 $x2 $y2 $ms") }
            .getOrDefault("").trim().ifBlank { "已滑动" }
    }

    suspend fun type(ctx: Context, text: String): String {
        info(ctx) ?: throw IllegalStateException("虚拟屏未创建")
        val safe = text.replace("\\", "\\\\").replace(" ", "%s").replace("\"", "\\\"")
        return shell(ctx, "input text \"$safe\"").trim().ifBlank { "已输入文本" }
    }

    suspend fun key(ctx: Context, keycode: String): String {
        val info = info(ctx) ?: throw IllegalStateException("虚拟屏未创建")
        return runCatching { shell(ctx, "input --display ${info.id} keyevent $keycode") }
            .recover { shell(ctx, "input keyevent $keycode") }
            .getOrDefault("").trim().ifBlank { "已发送按键 $keycode" }
    }

    suspend fun screenshot(ctx: Context): String {
        val info = info(ctx) ?: throw IllegalStateException("虚拟屏未创建")
        val path = "/data/local/tmp/chengyan_vs.png"
        val out = runCatching { shell(ctx, "screencap -d ${info.id} -p $path").trim() }.getOrElse { "" }
        return if (out.contains("invalid") || out.contains("unknown") || out.contains("Error", true))
            "截图失败：本 ROM screencap 不支持 -d 显示器参数（Android 14+ 才支持按 display 截屏）"
        else {
            runCatching {
                shell(ctx, "cp $path /sdcard/Download/chengyan_vs.png 2>/dev/null || cp $path /storage/emulated/0/Download/chengyan_vs.png")
            }
            "已截图 → Download/chengyan_vs.png"
        }
    }
}
