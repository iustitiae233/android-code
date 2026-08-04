package com.example.clauderemote.data

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

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
