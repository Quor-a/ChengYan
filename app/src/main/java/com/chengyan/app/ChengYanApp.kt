package com.chengyan.app

import android.app.Application
import com.chengyan.app.data.LinuxBridge

/**
 * 进程初始化。
 *
 * `LinuxBridge.init(context)` 以前是写在 `setContent { }` 里的——只有界面真正组合时
 * 才会执行。任何「界面还没画出来就要跑命令」的路径（进程被系统回收后由工具/服务
 * 拉起、通知动作、深链）都会拿到 `ctx == null`，`ensure()` 直接抛
 * `IllegalStateException: LinuxBridge.init 未调用`，表现为 `linux` 工具回
 * 「启动失败（task=-10）」而终端里只有一句看不懂的错。
 *
 * 放到 Application.onCreate 里，进程起来就一定有 Context，和界面是否组合无关。
 */
class ChengYanApp : Application() {
    companion object {
        /** 全局实例：adb 等无 Context 工具类取 filesDir 用。 */
        @JvmStatic lateinit var instance: ChengYanApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LinuxBridge.init(this)
    }
}
