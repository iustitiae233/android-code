package com.example.clauderemote.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clauderemote.data.ChatUiState
import com.example.clauderemote.data.ChatViewModel
import com.example.clauderemote.data.SessionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DeepSeek 风格抽屉覆盖层：从左滑出占屏 80%，背后聊天界面被遮罩虚化。
 * 内含：新对话、历史会话列表、底部「设置」入口。
 */
@Composable
fun ChatDrawerOverlay(
    open: Boolean,
    vm: ChatViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(open) { if (open) vm.listSessions() }

    val drawerWidth = LocalConfiguration.current.screenWidthDp.dp * 0.8f
    val scrimAlpha by animateFloatAsState(
        targetValue = if (open) 0.5f else 0f,
        animationSpec = tween(250),
        label = "scrim",
    )
    val offsetX by animateDpAsState(
        targetValue = if (open) 0.dp else -drawerWidth,
        animationSpec = tween(250),
        label = "offset",
    )

    // 半透明遮罩：点击关闭
    if (scrimAlpha > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(enabled = open, onClick = onClose),
        )
    }

    // 抽屉面板
    Surface(
        modifier = Modifier
            .offset(x = offsetX)
            .width(drawerWidth)
            .fillMaxHeight(),
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
    ) {
        DrawerContent(state, vm, onClose, onOpenSettings)
    }
}

@Composable
private fun DrawerContent(
    state: ChatUiState,
    vm: ChatViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 顶部品牌 + 模型
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        ) {
            Text("Claude Remote", style = MaterialTheme.typography.titleMedium)
            val model = state.model
            if (!model.isNullOrBlank()) {
                Text(
                    model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // 新对话
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable { vm.newChat(); onClose() },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "新对话",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "历史会话",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            IconButton(onClick = { vm.listSessions() }, enabled = !state.loadingSessions) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新会话列表",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        when {
            state.loadingSessions && state.sessions.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

            state.sessions.isEmpty() -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "还没有历史会话",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.sessions, key = { it.sessionId }) { s ->
                    SessionRow(s) { vm.loadSession(s.sessionId); onClose() }
                }
            }
        }

        HorizontalDivider()
        ListItem(
            headlineContent = { Text("设置") },
            supportingContent = { Text("服务器 · 权限模式") },
            leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable { onOpenSettings() },
        )
    }
}

@Composable
private fun SessionRow(s: SessionInfo, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                s.sessionId.take(12),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            s.timestamp?.let {
                Text(
                    fmtTime(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun fmtTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
