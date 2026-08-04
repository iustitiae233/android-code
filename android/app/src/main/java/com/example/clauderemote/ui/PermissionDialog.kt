package com.example.clauderemote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 工具调用审批弹窗（GLM 端点不触发；原生 Anthropic 会触发）。 */
@Composable
fun PermissionDialog(
    toolName: String,
    summary: String,
    onRespond: (allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
        title = { Text("工具调用审批") },
        text = {
            Column {
                Text(
                    "工具：$toolName",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        summary.ifBlank { "（无详情）" },
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRespond(true) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("拒绝") } },
    )
}
