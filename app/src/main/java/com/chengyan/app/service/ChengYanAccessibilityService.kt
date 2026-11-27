package com.chengyan.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 澄言无障碍服务：真实能力（不是摆设）——
 * ①记录前台应用包名 + 当前窗口可见文本（供 AI 的 phone_screen read 读取屏幕）；
 * ②执行全局动作：返回/主页/最近任务（供 phone_screen back/home/recents）。
 * 生命周期=设置页状态卡的真实数据源（instance != null 即已连接）。
 */
class ChengYanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ChengYanAccessibilityService? = null
            private set
        @Volatile var lastPkg: String? = null
        @Volatile var lastText: String = ""
        private const val MAX_CHARS = 3000
        private const val MAX_DEPTH = 12

        fun perform(a: String): Boolean = instance?.let { svc ->
            when (a) {
                "back" -> svc.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
                else -> false
            }
        } ?: false
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        event.packageName?.toString()?.let { lastPkg = it }
        runCatching {
            val root = rootInActiveWindow ?: return
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || sb.length >= MAX_CHARS || depth > MAX_DEPTH) return
                node.text?.let { if (it.isNotBlank()) { sb.append(it); sb.append('\n') } }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(root, 0)
            lastText = sb.toString().take(MAX_CHARS)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() { if (instance === this) instance = null }
}
