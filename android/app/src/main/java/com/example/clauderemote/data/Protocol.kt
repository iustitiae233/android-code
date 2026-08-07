package com.example.clauderemote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class PermissionMode {
    default, acceptEdits, plan, dontAsk, bypassPermissions
}

/** assistant 消息 content 数组里的一个块（text / tool_use / thinking） */
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val thinking: String? = null,
)

// ────────────── 客户端 → 服务端 ──────────────
@Serializable
sealed class ClientMessage {
    @Serializable @SerialName("auth")
    data class Auth(val token: String) : ClientMessage()

    @Serializable @SerialName("send_message")
    data class SendMessage(
        val prompt: String,
        @SerialName("sessionId") val sessionId: String? = null,
        @SerialName("permissionMode") val permissionMode: PermissionMode? = null,
    ) : ClientMessage()

    @Serializable @SerialName("interrupt")
    object Interrupt : ClientMessage()

    @Serializable @SerialName("permission_response")
    data class PermissionResponse(
        val requestId: String,
        val decision: String,
        val updatedInput: JsonElement? = null,
    ) : ClientMessage()

    @Serializable @SerialName("set_permission_mode")
    data class SetPermissionMode(val mode: PermissionMode) : ClientMessage()

    @Serializable @SerialName("set_cwd")
    data class SetCwd(val cwd: String) : ClientMessage()

    @Serializable @SerialName("list_sessions")
    data class ListSessions(val dir: String? = null) : ClientMessage()

    @Serializable @SerialName("load_session")
    data class LoadSession(val sessionId: String) : ClientMessage()
}

// ────────────── 服务端 → 客户端 ──────────────
@Serializable
sealed class ServerMessage {
    @Serializable @SerialName("hello")
    data class Hello(
        val ok: Boolean,
        val serverVersion: String,
        val cwd: String? = null,
        val availableCwds: List<String> = emptyList(),
    ) : ServerMessage()

    @Serializable @SerialName("error")
    data class Error(val message: String, val code: String? = null) : ServerMessage()

    @Serializable @SerialName("system")
    data class SystemInit(
        val sessionId: String,
        val tools: List<String>,
        val model: String,
        val cwd: String,
        val availableCwds: List<String> = emptyList(),
    ) : ServerMessage()

    @Serializable @SerialName("assistant")
    data class Assistant(val messageId: String, val content: List<ContentBlock>) : ServerMessage()

    @Serializable @SerialName("assistant_partial")
    data class AssistantPartial(val textDelta: String) : ServerMessage()

    @Serializable @SerialName("tool_use")
    data class ToolUse(val toolUseId: String, val toolName: String, val input: JsonElement) : ServerMessage()

    @Serializable @SerialName("tool_result")
    data class ToolResult(val toolUseId: String, val content: JsonElement, val isError: Boolean) : ServerMessage()

    @Serializable @SerialName("permission_request")
    data class PermissionRequest(
        val requestId: String,
        val toolName: String,
        val input: JsonElement,
        val summary: String,
    ) : ServerMessage()

    @Serializable @SerialName("thinking_progress")
    data class ThinkingProgress(val estimatedTokens: Int, val delta: Int) : ServerMessage()

    @Serializable @SerialName("result")
    data class Result(
        val subtype: String,
        val costUsd: Double? = null,
        val usage: JsonElement? = null,
        val terminalReason: String? = null,
        val isError: Boolean = false,
    ) : ServerMessage()

    @Serializable @SerialName("status")
    data class Status(val message: String) : ServerMessage()

    @Serializable @SerialName("session_list")
    data class SessionList(val sessions: List<JsonElement>) : ServerMessage()

    @Serializable @SerialName("session_messages")
    data class SessionMessages(val sessionId: String, val messages: List<JsonElement>) : ServerMessage()
}
