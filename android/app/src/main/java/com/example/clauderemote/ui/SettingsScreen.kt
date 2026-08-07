package com.example.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.clauderemote.data.PermissionMode
import com.example.clauderemote.data.ServerSettings
import com.example.clauderemote.data.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    current: ServerSettings,
    onDone: () -> Unit,
    permissionMode: PermissionMode? = null,
    onPermissionModeChange: ((PermissionMode) -> Unit)? = null,
) {
    var url by remember(current.serverUrl) { mutableStateOf(current.serverUrl) }
    var token by remember(current.authToken) { mutableStateOf(current.authToken) }
    var showToken by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("服务器", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebSocket 地址") },
                leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("AUTH_TOKEN") },
                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showToken) "隐藏" else "显示",
                        )
                    }
                },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "• 局域网：ws://电脑IP:8787\n" +
                    "• 外网：wss://你的 cloudflare 隧道域名\n" +
                    "• token 与后端 .env 的 AUTH_TOKEN 保持一致\n" +
                    "• ws:// 连公网会明文传 token，能用 wss:// 更好",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (permissionMode != null && onPermissionModeChange != null) {
                Spacer(Modifier.height(4.dp))
                Text("权限模式", style = MaterialTheme.typography.titleMedium)
                val modes = listOf(
                    PermissionMode.default to "默认",
                    PermissionMode.acceptEdits to "接受编辑",
                    PermissionMode.plan to "计划模式",
                    PermissionMode.dontAsk to "不再询问",
                    PermissionMode.bypassPermissions to "跳过权限",
                )
                modes.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPermissionModeChange(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == permissionMode,
                            onClick = { onPermissionModeChange(mode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    "说明：仅原生 Anthropic 端点会真正弹窗审批；GLM 端点由服务端自动放行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        store.save(url.trim(), token.trim())
                        saving = false
                        onDone()
                    }
                },
                enabled = !saving && url.isNotBlank() && token.isNotBlank(),
            ) { Text(if (saving) "保存中…" else "保存并连接") }
        }
    }
}
