package com.chengyan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chengyan.app.data.ChatStore
import com.chengyan.app.data.SettingsStore
import com.chengyan.app.ui.ChatViewModel
import com.chengyan.app.ui.screens.ChatScreen
import com.chengyan.app.ui.screens.SettingsScreen
import com.chengyan.app.ui.screens.ChatPrefsScreen
import com.chengyan.app.ui.theme.ChengYanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        val chats = ChatStore(this)
        val appContext = applicationContext
        setContent {
            ChengYanTheme {
                var showSettings by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                var showPrefs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                var showTerminal by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(appContext, settings, chats))
                com.chengyan.app.data.LinuxBridge.init(appContext)
                if (showTerminal) {
                    com.chengyan.app.ui.screens.TerminalScreen(onBack = { showTerminal = false })
                } else if (showPrefs) {
                    ChatPrefsScreen(
                        config = vm.config,
                        vm = vm,
                        onSave = { vm.saveConfig(it) },
                        onBack = { showPrefs = false }
                    )
                } else if (showSettings) {
                    SettingsScreen(
                        config = vm.config,
                        vm = vm,
                        onSave = { vm.saveConfig(it) },
                        onBack = { showSettings = false },
                        onOpenPrefs = { showPrefs = true }
                    )
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true }, onOpenPrefs = { showPrefs = true }, onOpenTerminal = { showTerminal = true })
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
