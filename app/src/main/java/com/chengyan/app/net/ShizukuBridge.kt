package com.chengyan.app.net

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Shizuku 真实集成桥：授权后用 shell 级权限 `pm grant` 给自身授予被系统限制的权限
 * （READ_SMS/SEND_SMS 等 Android 13+ 对侧装应用特殊限制的权限）。
 * 不硬编码包名，统一用运行时 packageName。
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 9201

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 发起授权弹窗；结果经 onResult 回调（监听器线程回调，UI 侧自行处理线程）。 */
    fun request(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * 通过 Shizuku 远程 shell 执行 `pm grant`（IShizukuService.newProcess，13.1.5 公开路径）。
     * 返回 (权限, 是否成功) 列表。仅在 Shizuku 已授权时可用。
     */
    fun grantSelf(packageName: String, permissions: List<String>): List<Pair<String, Boolean>> =
        permissions.map { p ->
            val ok = runCatching {
                val binder = Shizuku.getBinder() ?: throw RuntimeException("Shizuku binder 未连接")
                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(
                    ShizukuBinderWrapper(binder)
                )
                val remote = service.newProcess(arrayOf("pm", "grant", packageName, p), null, null)
                remote.waitFor() == 0
            }.getOrDefault(false)
            p to ok
        }
}
