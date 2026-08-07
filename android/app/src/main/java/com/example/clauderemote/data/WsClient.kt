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
        // 只硬拦格式错误的地址；明文 ws:// 是否连公网由用户自行决定（其自有后端可能就是
        // ws://）。公网明文不拦截，只在连接条提示一句「token 未加密」。
        val (check, msg) = classifyUrl(url)
        if (check == UrlCheck.Invalid) {
            shouldReconnect = false
            onState(ConnectionState.Error, msg)
            return
        }
        shouldReconnect = true
        val warn = if (check == UrlCheck.InsecurePublic) "⚠ 明文公网：token 未加密，建议 wss://" else null
        onState(ConnectionState.Connecting, warn)
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

/** URL 校验结果：Ok=可连；InsecurePublic=明文公网（硬拦）；Invalid=不可放行。 */
private enum class UrlCheck { Ok, InsecurePublic, Invalid }

/**
 * 分类 URL 的安全性。规则：wss/https 一律 Ok；ws/http 指向本机/局域网=Ok，
 * 指向公网=InsecurePublic（硬拦）；解析不了=Invalid（硬拦）。
 *
 * 注意：OkHttp 的 HttpUrl 解析器不认 ws:// / wss://（只有 Request.Builder.url() 会做
 * ws→http、wss→https 改写），所以这里先复刻该改写，再解析、判断明文与否。
 */
private fun classifyUrl(rawUrl: String): Pair<UrlCheck, String?> {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return UrlCheck.Invalid to "地址为空"
    val lower = trimmed.lowercase()
    val isCleartext = lower.startsWith("ws://") || lower.startsWith("http://")
    val forParse = when {
        lower.startsWith("ws://") -> "http://" + trimmed.substring(5)
        lower.startsWith("wss://") -> "https://" + trimmed.substring(6)
        else -> trimmed
    }
    val u = forParse.toHttpUrlOrNull()
        ?: return UrlCheck.Invalid to "地址格式无效：$trimmed"
    if (!isCleartext) return UrlCheck.Ok to null // wss/https 加密
    val host = u.host
    return if (isPrivateHost(host)) UrlCheck.Ok to null
    else UrlCheck.InsecurePublic to "明文 ws:// 指向公网主机「$host」，token 会被窃听。"
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
