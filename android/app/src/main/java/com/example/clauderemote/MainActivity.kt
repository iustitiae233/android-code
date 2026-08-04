package com.example.clauderemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clauderemote.data.ChatViewModel
import com.example.clauderemote.data.SettingsStore
import com.example.clauderemote.ui.ChatDrawerOverlay
import com.example.clauderemote.ui.ChatScreen
import com.example.clauderemote.ui.SettingsScreen
import com.example.clauderemote.ui.theme.ClaudeRemoteTheme

private enum class Nav { Chat, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClaudeRemoteTheme {
                val store = remember { SettingsStore(applicationContext) }
                val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
                var screen by remember { mutableStateOf(Nav.Chat) }

                when (val s = settings) {
                    null -> LoadingScreen()
                    else -> if (s.authToken.isBlank()) {
                        // 首次：必须先配置服务器与 token
                        SettingsScreen(store = store, current = s, onDone = { })
                    } else {
                        val vm: ChatViewModel = viewModel(
                            key = "${s.serverUrl}|${s.authToken}",
                        ) { ChatViewModel(s.serverUrl, s.authToken) }
                        val chatState by vm.state.collectAsStateWithLifecycle()
                        var drawerOpen by remember { mutableStateOf(false) }

                        when (screen) {
                            Nav.Chat -> Box(Modifier.fillMaxSize()) {
                                // 聊天主界面；抽屉打开时整页虚化
                                ChatScreen(
                                    vm = vm,
                                    onOpenDrawer = { drawerOpen = true },
                                    modifier = if (drawerOpen) Modifier.blur(8.dp) else Modifier,
                                )
                                // 抽屉覆盖层（遮罩 + 80% 面板）
                                ChatDrawerOverlay(
                                    open = drawerOpen,
                                    vm = vm,
                                    onClose = { drawerOpen = false },
                                    onOpenSettings = {
                                        drawerOpen = false
                                        screen = Nav.Settings
                                    },
                                )
                            }
                            Nav.Settings -> SettingsScreen(
                                store = store,
                                current = s,
                                onDone = { screen = Nav.Chat },
                                permissionMode = chatState.permissionMode,
                                onPermissionModeChange = vm::setPermissionMode,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Claude Remote", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
    }
}
