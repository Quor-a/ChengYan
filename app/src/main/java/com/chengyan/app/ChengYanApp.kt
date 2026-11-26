package com.chengyan.app

import android.app.Application

/** 进程初始化（内置 Linux 用户空间已移除；保留 Application 供后续能力使用）。 */
class ChengYanApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
