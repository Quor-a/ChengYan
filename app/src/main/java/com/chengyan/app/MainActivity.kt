package com.chengyan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chengyan.app.data.ChatStore
import com.chengyan.app.data.SettingsStore
import com.chengyan.app.ui.ChatViewModel
import com.chengyan.app.ui.screens.ChatPrefsScreen
import com.chengyan.app.ui.screens.ChatScreen
import com.chengyan.app.ui.screens.SettingsScreen
import com.chengyan.app.ui.screens.TerminalScreen
import com.chengyan.app.ui.theme.ChengYanTheme

/** 界面栈节点：根页 chat 永远在栈底，其余逐层压入/弹出。 */
private enum class Route { CHAT, SETTINGS, PREFS, TERMINAL }

class MainActivity : ComponentActivity() {
    override fun onDestroy() {
        super.onDestroy()
        // 🔴 onDestroy 在转屏/深色模式/窗口尺寸变化（Activity 重建）时也会触发：
        //    无 isFinishing 判断会把正在跑的终端会话整组 SIGKILL。
        if (!isFinishing) return
        // App 退出：整组强杀 proot/ash + 孤儿清扫，杜绝终端运行时进程泄漏（load 累积根因）。
        // destroyForcibly 阻塞最多 3s，放后台线程避免阻塞主线程销毁流程。
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            runCatching { com.chengyan.app.data.LinuxBridge.shutdown() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        val chats = ChatStore(this)
        val appContext = applicationContext
        setContent {
            ChengYanTheme {
                // 单一界面栈：系统返回键 / 返回手势只 pop 一层，绝不直接 finish Activity。
                // 旧实现用三个独立 boolean 且无 BackHandler → 返回直接回桌面（严重 bug）。
                val backStack = remember { mutableStateListOf(Route.CHAT) }
                val current = backStack.last()

                fun push(route: Route) {
                    if (backStack.last() != route) backStack.add(route)
                }
                fun pop() {
                    // 栈底 CHAT 不再弹出：在根页按返回才交给系统退出。
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                    else finish()
                }

                // BackHandler 注册在栈根。对话框/DropdownMenu/ModalBottomSheet 会注册
                // 更靠近焦点的 BackHandler 并先消费返回键关掉自己，不会触发这里的 pop。
                BackHandler { pop() }

                val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(appContext, settings, chats))
                com.chengyan.app.data.LinuxBridge.init(appContext)

                when (current) {
                    Route.TERMINAL -> TerminalScreen(onBack = { pop() })
                    Route.PREFS -> ChatPrefsScreen(
                        config = vm.config,
                        vm = vm,
                        onSave = { vm.saveConfig(it) },
                        onBack = { pop() }
                    )
                    Route.SETTINGS -> SettingsScreen(
                        config = vm.config,
                        vm = vm,
                        onSave = { vm.saveConfig(it) },
                        onBack = { pop() },
                        onOpenPrefs = { push(Route.PREFS) }
                    )
                    Route.CHAT -> ChatScreen(
                        vm,
                        onOpenSettings = { push(Route.SETTINGS) },
                        onOpenPrefs = { push(Route.PREFS) },
                        onOpenTerminal = { push(Route.TERMINAL) }
                    )
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val appContext: android.content.Context,
    private val settings: SettingsStore,
    private val chats: ChatStore,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(appContext, settings, chats) as T
}
