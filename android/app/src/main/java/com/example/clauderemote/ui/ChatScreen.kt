package com.example.clauderemote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clauderemote.data.ChatViewModel
import com.example.clauderemote.data.ConnectionState
import kotlinx.coroutines.launch

/**
 * 聊天主界面。DeepSeek 风格：无标题栏，左上角菜单按钮呼出会话抽屉，右上角新对话。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 不在底部时显示「回到底部」按钮：以最后一个可见 item 是否到达列表末端判定。
    val showJumpBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible < info.totalItemsCount - 1
        }
    }

    Scaffold(modifier = modifier) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部控制条：仅菜单 / 新对话，无标题栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "会话")
                }
                IconButton(onClick = { vm.newChat() }) {
                    Icon(Icons.Filled.AddComment, contentDescription = "新对话")
                }
            }
            ConnectionBar(state.connection, state.connectionMsg)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessageList(
                    messages = state.messages,
                    thinking = state.thinking,
                    thinkingTokens = state.thinkingTokens,
                    listState = listState,
                    onSend = vm::send,
                )
                if (showJumpBottom) {
                    SmallFloatingActionButton(
                        onClick = {
                            val last = listState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) scope.launch { listState.animateScrollToItem(last) }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到底部")
                    }
                }
            }
            state.status?.let { status ->
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Composer(
                isBusy = state.isBusy,
                onSend = vm::send,
                onInterrupt = vm::interrupt,
            )
        }
    }

    state.activePermission?.let { p ->
        PermissionDialog(
            toolName = p.toolName,
            summary = p.summary,
            onRespond = { allow -> vm.respondPermission(p.requestId, allow) },
        )
    }
}

@Composable
private fun ConnectionBar(connection: ConnectionState, msg: String?) {
    val (text, color) = when (connection) {
        ConnectionState.Connected -> "已连接" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting -> "连接中…" to MaterialTheme.colorScheme.tertiary
        ConnectionState.Error -> "连接错误" to MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> "未连接" to MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connection == ConnectionState.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Text(
                if (msg.isNullOrBlank()) text else "$text: $msg",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
