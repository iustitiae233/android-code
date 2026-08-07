package com.example.clauderemote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.clauderemote.data.ToolCallInfo
import com.example.clauderemote.data.UiMessage
import com.example.clauderemote.ui.markdown.MarkdownText
import com.example.clauderemote.ui.theme.SuccessColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun MessageList(
    messages: List<UiMessage>,
    thinking: Boolean,
    thinkingTokens: Int,
    listState: LazyListState,
    onSend: (String) -> Unit,
) {
    if (messages.isEmpty() && !thinking) {
        EmptyState(onSend)
        return
    }

    LaunchedEffect(messages.size, thinking) {
        if (messages.isEmpty()) return@LaunchedEffect
        // 思考指示是 messages 之后的额外 item；有思考时滚到它，否则滚到最后一条消息。
        val last = if (thinking) messages.size else messages.lastIndex
        listState.animateScrollToItem(last)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            when (m) {
                is UiMessage.User -> UserBubble(m)
                is UiMessage.Assistant -> AssistantMessage(m)
            }
        }
        if (thinking) item { ThinkingIndicator(thinkingTokens) }
    }
}

@Composable
private fun EmptyState(onSend: (String) -> Unit) {
    val suggestions = listOf("用一句话介绍你自己", "列出当前目录的文件", "写一个 Python 快速排序")
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text("和 Claude 开始对话", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            "发消息让它读写文件、跑命令，远程帮你写代码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(16.dp))
        suggestions.forEach { s ->
            AssistChip(onClick = { onSend(s) }, label = { Text(s) })
            Spacer(Modifier.size(6.dp))
        }
    }
}

@Composable
private fun UserBubble(m: UiMessage.User) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 300.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(m.text)) },
            ) {
                Text(
                    m.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Timestamp(m.timestamp)
        }
        Spacer(Modifier.width(6.dp))
        Avatar(Icons.Filled.Person, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun AssistantMessage(m: UiMessage.Assistant) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        Avatar(
            Icons.Filled.AutoAwesome,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (m.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                    modifier = Modifier.clickable { clipboard.setText(AnnotatedString(m.text)) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // 流式进行中用纯文本：m.text 每个 token 都变，若每次都跑 parseBlocks
                        // 会对整段已收文字重排版，长回答会严重掉帧。结束后再切回 Markdown。
                        if (m.streaming) {
                            Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            MarkdownText(m.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (m.streaming) StreamingCaret()
                    }
                }
                Timestamp(m.timestamp)
            }
            if (!m.thinking.isNullOrBlank()) ThinkingCard(m.thinking)
            if (m.toolCalls.size >= 2) {
                ToolGroupCard(m.toolCalls)
            } else {
                for (tc in m.toolCalls) ToolCallCard(tc)
            }
            if (m.costUsd != null || m.tokensIn != null || m.tokensOut != null ||
                m.cacheRead != null || m.cacheCreate != null
            ) {
                Spacer(Modifier.size(4.dp))
                CostChip(m.costUsd, m.tokensIn, m.tokensOut, m.cacheRead, m.cacheCreate)
            }
        }
    }
}

@Composable
private fun StreamingCaret() {
    val t = rememberInfiniteTransition(label = "caret")
    val alpha by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Text("▍", color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
}

@Composable
private fun ThinkingCard(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    thinking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一轮里多个工具调用的折叠分组：默认收起成一行「执行了 N 个操作 + 计数」，
 * 展开后才列出各 ToolCallCard。避免手机上连续 Read/Bash 刷屏。
 */
@Composable
private fun ToolGroupCard(toolCalls: List<ToolCallInfo>) {
    var expanded by remember { mutableStateOf(false) }
    val anyError = toolCalls.any { it.isError }
    val allDone = toolCalls.all { it.done }
    val (icon, tint) = when {
        anyError -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        allDone -> Icons.Filled.CheckCircle to SuccessColor
        else -> Icons.Filled.HourglassTop to MaterialTheme.colorScheme.tertiary
    }
    val counts = toolCalls
        .groupingBy { it.toolName }
        .eachCount()
        .entries
        .joinToString(" · ") { (name, n) -> "$name ×$n" }
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("执行了 ${toolCalls.size} 个操作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        counts,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tc in toolCalls) ToolCallCard(tc)
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(tc: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = when {
        tc.isError -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        tc.done -> Icons.Filled.CheckCircle to SuccessColor
        else -> Icons.Filled.HourglassTop to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = tc.result != null) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tc.toolName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (tc.summary.isNotBlank()) {
                        Text(
                            tc.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (tc.result != null) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded && tc.result != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    tc.result,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tc.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CostChip(
    cost: Double?,
    tokensIn: Int?,
    tokensOut: Int?,
    cacheRead: Int? = null,
    cacheCreate: Int? = null,
) {
    val parts = buildList {
        cost?.let { add("$%.4f".format(it)) }
        val tok = buildList {
            tokensIn?.let { add("${it}↑") }
            tokensOut?.let { add("${it}↓") }
            cacheRead?.let { add("${it}⚡") }
            cacheCreate?.let { add("${it}✎") }
        }.joinToString("·")
        if (tok.isNotBlank()) add("${tok} tok")
    }
    if (parts.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            parts.joinToString(" · "),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThinkingIndicator(tokens: Int) {
    val t = rememberInfiniteTransition(label = "think")
    val alpha by t.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "thinkAlpha",
    )
    // 计时：每秒 +1，让指示器一直在动，避免长时间无事件时看起来像「卡死」。
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            Icons.Filled.AutoAwesome,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        val label = buildString {
            append("思考中… ${elapsed}s")
            if (tokens > 0) append(" · $tokens tokens")
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = alpha),
        )
    }
}

@Composable
private fun Avatar(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Timestamp(ts: Long) {
    Text(
        timeLabel(ts),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

private fun timeLabel(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
