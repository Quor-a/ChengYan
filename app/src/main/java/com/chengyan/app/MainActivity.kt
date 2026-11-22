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
import com.chengyan.app.ui.theme.ChengYanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        val chats = ChatStore(this)
        setContent {
            ChengYanTheme {
                var showSettings by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(settings, chats))
                if (showSettings) {
                    SettingsScreen(
                        config = vm.config,
                        vm = vm,
                        onSave = { vm.saveConfig(it) },
                        onBack = { showSettings = false }
                    )
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val settings: SettingsStore,
    private val chats: ChatStore,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(settings, chats) as T
}
