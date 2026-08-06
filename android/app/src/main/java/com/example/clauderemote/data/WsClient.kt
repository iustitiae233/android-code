package com.example.clauderemote.data

import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

/** WebSocket 关闭码 1008 = Policy Violation，后端鉴权失败时用它，客户端据此停止重连。 */
private const val CLOSE_AUTH_FAILED = 1008

/** OkHttp WebSocket 客户端：连接后端、收发协议消息、断线指数退避重连。 */
class WsClient(
    private val url: String,
    private val token: String,
    private val onMessage: (ServerMessage) -> Unit,
    private val onState: (ConnectionState, String?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var shouldReconnect = false
    private var attempts = 0

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        // 安全闸：明文 ws:// 只允许指向局域网/本机，避免 token 明文走公网。
        val issue = insecureUrlIssue(url)
        if (issue != null) {
            shouldReconnect = false
            onState(ConnectionState.Error, issue)
            return
        }

        shouldReconnect = true
        onState(ConnectionState.Connecting, null)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        ws = client.newWebSocket(req, listener)
    }

    fun send(msg: ClientMessage): Boolean {
        val s = json.encodeToString(ClientMessage.serializer(), msg)
        return ws?.send(s) ?: false
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            onState(ConnectionState.Connected, null)
            send(ClientMessage.Auth(token))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = json.decodeFromString(ServerMessage.serializer(), text)
                onMessage(msg)
            } catch (e: Exception) {
                onState(ConnectionState.Error, "解析消息失败: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 鉴权失败：不要重连，否则会拿错误 token 无限重试。
            if (code == CLOSE_AUTH_FAILED) {
                shouldReconnect = false
                onState(ConnectionState.Error, "Token 无效或被服务端拒绝，已停止重连")
                return
            }
            onState(ConnectionState.Disconnected, "连接关闭 ($code)")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onState(ConnectionState.Error, t.message)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        attempts++
        val delay = minOf(1000L * attempts, 10_000L)
        handler.postDelayed({ if (shouldReconnect) connect() }, delay)
    }
}

/**
 * 检查明文 URL 的安全性。返回 null=安全可连；非 null=问题描述（应阻止连接）。
 * 规则：wss:// 一律放行；ws:// 仅当主机是本机/局域网/内网域名时放行。
 */
private fun insecureUrlIssue(rawUrl: String): String? {
    val u = rawUrl.toHttpUrlOrNull() ?: return "地址格式无效"
    val isCleartext = u.scheme == "ws" || u.scheme == "http"
    if (!isCleartext) return null // wss/https 加密，放行
    val host = u.host
    return if (isPrivateHost(host)) null
    else "明文 ws:// 指向公网主机「$host」，token 会被窃听。请改用 wss://（cloudflare 隧道）或局域网 IP。"
}

/** 判断主机是否属于本机/局域网。支持 IPv4/IPv6 字面量与常见内网域名后缀。 */
private fun isPrivateHost(host: String): Boolean {
    if (host.isBlank()) return false
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h == "ip6-localhost") return true
    // IPv4 字面量
    if (h.contains('.') && !h.contains(':')) {
        val parts = h.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return when {
                a == 10 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                a == 127 -> true
                a == 169 && b == 254 -> true // link-local
                else -> false
            }
        }
    }
    // IPv6：本机 / 唯一本地地址
    if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd")
    return false
}
