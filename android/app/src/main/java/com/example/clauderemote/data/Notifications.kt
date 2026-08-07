package com.example.clauderemote.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.clauderemote.MainActivity
import com.example.clauderemote.R

private const val CHANNEL_ID = "turn_done"
private const val NOTIF_ID = 1001

/** 创建通知渠道（API 26+ 必需），幂等。 */
fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "任务完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Claude 一轮任务在后台完成时通知"
            }
            nm.createNotificationChannel(ch)
        }
    }
}

/**
 * 应用在后台时一轮任务完成 → 弹本地通知；点按回到 app（CLEAR_TOP|SINGLE_TOP 复用现有实例）。
 * 调用方负责确认已获 POST_NOTIFICATIONS 权限（API 33+）；无权限时系统静默丢弃，不会崩。
 */
fun notifyTurnDone(ctx: Context, summary: String, isError: Boolean) {
    ensureChannel(ctx)
    val intent = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pi = PendingIntent.getActivity(
        ctx,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val title = if (isError) "Claude 任务出错" else "Claude 任务完成"
    val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(summary.ifBlank { if (isError) "查看详情" else "已收到回复" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
        .setAutoCancel(true)
        .setContentIntent(pi)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
}
