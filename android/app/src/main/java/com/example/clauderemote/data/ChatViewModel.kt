package com.example.clauderemote.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 工具调用展示信息 */
data class ToolCallInfo(
    val toolName: String,
    val summary: String,
    val input: JsonElement?,
    val toolUseId: String? = null,
    val result: String? = null,
    val isError: Boolean = false,
    val done: Boolean = false,
)

/** 列表里的消息（用户 / assistant 文本+工具 / 思考） */
sealed class UiMessage {
    abstract val id: String
    abstract val timestamp: Long

    data class User(
        val text: String,
        override val id: String = nextId(),
        override val timestamp: Long = System.currentTimeMillis(),
    ) : UiMessage()

    data class Assistant(
        val text: String = "",
        val thinking: String? = null,
        val toolCalls: List<ToolCallInfo> = emptyList(),
        val streaming: Boolean = false,
        val costUsd: Double? = null,
        val tokensIn: Int? = null,
        val tokensOut: Int? = null,
        override val id: String = nextId(),
        override val timestamp: Long = System.currentTimeMillis(),
    ) : UiMessage()
}

/** 历史会话条目 */
data class SessionInfo(
    val sessionId: String,
    val title: String,
    val timestamp: Long?,
)

/** 待审批的工具调用 */
data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val summary: String,
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectionMsg: String? = null,
    val sessionId: String? = null,
    val model: String? = null,
    val cwd: String? = null,
    val tools: List<String> = emptyList(),
    val thinking: Boolean = false,
    val thinkingTokens: Int = 0,
    val lastCost: Double? = null,
    val isBusy: Boolean = false,
    val status: String? = null,
    val permissionMode: PermissionMode = PermissionMode.default,
    val pendingPermission: PendingPermission? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val loadingSessions: Boolean = false,
)

class ChatViewModel(
    private val serverUrl: String,
    private val token: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var client: WsClient? = null

    init {
        connect()
    }

    fun connect() {
        client?.disconnect()
        client = WsClient(serverUrl, token, ::onMessage, ::onState)
        client?.connect()
    }

    fun send(prompt: String) {
        if (prompt.isBlank()) return
        _state.update {
            it.copy(
                messages = it.messages + UiMessage.User(prompt),
                isBusy = true,
                thinking = true,
                thinkingTokens = 0,
                status = null,
            )
        }
        client?.send(ClientMessage.SendMessage(prompt = prompt, sessionId = _state.value.sessionId))
    }

    fun interrupt() {
        client?.send(ClientMessage.Interrupt)
    }

    /** 新建会话：清空当前消息，下一次 send 不带 sessionId → 后端开新会话 */
    fun newChat() {
        _state.update {
            it.copy(
                messages = emptyList(),
                sessionId = null,
                status = null,
                lastCost = null,
                thinking = false,
                thinkingTokens = 0,
                isBusy = false,
            )
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode) }
        client?.send(ClientMessage.SetPermissionMode(mode))
    }

    fun listSessions() {
        _state.update { it.copy(loadingSessions = true) }
        client?.send(ClientMessage.ListSessions())
    }

    fun loadSession(sessionId: String) {
        _state.update { it.copy(status = "载入会话…") }
        client?.send(ClientMessage.LoadSession(sessionId))
    }

    fun respondPermission(requestId: String, allow: Boolean) {
        client?.send(ClientMessage.PermissionResponse(requestId, if (allow) "allow" else "deny"))
        _state.update { it.copy(pendingPermission = null) }
    }

    private fun onState(s: ConnectionState, msg: String?) {
        _state.update { it.copy(connection = s, connectionMsg = msg) }
    }

    private fun onMessage(m: ServerMessage) {
        when (m) {
            is ServerMessage.Hello -> _state.update { it.copy(connection = ConnectionState.Connected) }

            is ServerMessage.SystemInit -> _state.update {
                it.copy(sessionId = m.sessionId, model = m.model.ifBlank { it.model }, cwd = m.cwd, tools = m.tools)
            }

            is ServerMessage.Assistant -> {
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val newText = m.content.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
                    val thinkText = m.content.filter { it.type == "thinking" }.joinToString("\n") { it.thinking ?: "" }
                    if (newText.isNotBlank() || thinkText.isNotBlank()) {
                        val last = msgs.indexOfLast { it is UiMessage.Assistant && it.streaming }
                        if (last >= 0) {
                            val prev = msgs[last] as UiMessage.Assistant
                            val mergedThink = listOfNotNull(prev.thinking, thinkText.ifBlank { null }).joinToString("\n")
                            msgs[last] = prev.copy(
                                text = prev.text + newText,
                                thinking = mergedThink.ifBlank { null },
                                streaming = false,
                            )
                        } else {
                            msgs.add(UiMessage.Assistant(text = newText, thinking = thinkText.ifBlank { null }))
                        }
                    }
                    for (tu in m.content.filter { it.type == "tool_use" }) {
                        msgs.add(
                            UiMessage.Assistant(
                                toolCalls = listOf(
                                    ToolCallInfo(
                                        toolName = tu.name ?: "?",
                                        summary = summarizeTool(tu.name, tu.input),
                                        input = tu.input,
                                        toolUseId = tu.id,
                                        done = false,
                                    )
                                )
                            )
                        )
                    }
                    s.copy(messages = msgs)
                }
            }

            is ServerMessage.AssistantPartial -> {
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val last = msgs.indexOfLast { it is UiMessage.Assistant && it.streaming }
                    if (last >= 0) {
                        val prev = msgs[last] as UiMessage.Assistant
                        msgs[last] = prev.copy(text = prev.text + m.textDelta)
                    } else {
                        msgs.add(UiMessage.Assistant(text = m.textDelta, streaming = true))
                    }
                    s.copy(messages = msgs)
                }
            }

            is ServerMessage.ToolUse -> {
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    msgs.add(
                        UiMessage.Assistant(
                            toolCalls = listOf(
                                ToolCallInfo(
                                    m.toolName,
                                    summarizeTool(m.toolName, m.input),
                                    m.input,
                                    toolUseId = m.toolUseId,
                                    done = false,
                                )
                            )
                        )
                    )
                    s.copy(messages = msgs)
                }
            }

            is ServerMessage.ToolResult -> {
                val resultStr = jsonElementToText(m.content)
                _state.update { s ->
                    val msgs = s.messages.map { msg ->
                        if (msg is UiMessage.Assistant && msg.toolCalls.any { it.toolUseId == m.toolUseId }) {
                            msg.copy(
                                toolCalls = msg.toolCalls.map {
                                    if (it.toolUseId == m.toolUseId) it.copy(result = resultStr, isError = m.isError, done = true)
                                    else it
                                }
                            )
                        } else {
                            msg
                        }
                    }
                    s.copy(messages = msgs)
                }
            }

            is ServerMessage.ThinkingProgress -> {
                _state.update { it.copy(thinking = true, thinkingTokens = m.estimatedTokens) }
            }

            is ServerMessage.Result -> {
                val (ti, to) = parseUsage(m.usage)
                val cost = m.costUsd
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val idx = msgs.indexOfLast { it is UiMessage.Assistant }
                    if (idx >= 0) {
                        val a = msgs[idx] as UiMessage.Assistant
                        msgs[idx] = a.copy(
                            costUsd = cost ?: a.costUsd,
                            tokensIn = ti ?: a.tokensIn,
                            tokensOut = to ?: a.tokensOut,
                            streaming = false,
                        )
                    }
                    s.copy(
                        messages = msgs,
                        isBusy = false,
                        thinking = false,
                        lastCost = cost ?: s.lastCost,
                        status = if (m.isError) "出错: ${m.subtype}" else s.status,
                    )
                }
            }

            is ServerMessage.PermissionRequest -> _state.update {
                it.copy(pendingPermission = PendingPermission(m.requestId, m.toolName, m.summary))
            }

            is ServerMessage.Status -> _state.update { it.copy(status = m.message) }

            is ServerMessage.Error -> _state.update {
                it.copy(status = "错误: ${m.message}", isBusy = false, thinking = false, loadingSessions = false)
            }

            is ServerMessage.SessionList -> _state.update {
                it.copy(sessions = m.sessions.mapNotNull { parseSession(it) }, loadingSessions = false)
            }

            is ServerMessage.SessionMessages -> {
                val rebuilt = m.messages.mapNotNull { rebuildMessage(it) }
                _state.update {
                    it.copy(
                        messages = rebuilt,
                        sessionId = m.sessionId,
                        status = "已载入会话 ${m.sessionId.take(8)}",
                        isBusy = false,
                        thinking = false,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client?.disconnect()
    }
}

// ── 解析辅助 ──

private val prettyJson = Json { prettyPrint = true }

private fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.content ?: ""

private fun JsonObject.strAny(vararg keys: String): String {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.content
        if (!v.isNullOrBlank()) return v
    }
    return ""
}

private fun JsonObject.longAny(vararg keys: String): Long? {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        return p.content.toDoubleOrNull()?.toLong() ?: p.content.toLongOrNull()
    }
    return null
}

private fun jsonElementToText(el: JsonElement?): String {
    if (el == null) return ""
    val s = when (el) {
        is JsonPrimitive -> el.content
        else -> prettyJson.encodeToString(JsonElement.serializer(), el)
    }
    return if (s.length > 4000) s.take(4000) + "\n…（截断）" else s
}

private fun parseUsage(usage: JsonElement?): Pair<Int?, Int?> {
    val o = usage as? JsonObject ?: return null to null
    val ti = (o["input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
    val to = (o["output_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
    return ti to to
}

private fun parseSession(el: JsonElement): SessionInfo? {
    val o = el as? JsonObject ?: return null
    val id = o.strAny("sessionId", "session_id", "id")
    if (id.isBlank()) return null
    val title = o.strAny("summary", "title", "name", "path").ifBlank { "（无摘要）" }
    val ts = o.longAny("timestamp", "createdAt", "updatedAt", "time")
    return SessionInfo(id, title, ts)
}

private fun rebuildMessage(el: JsonElement): UiMessage? {
    val o = el as? JsonObject ?: return null
    return when (o.strAny("role", "type")) {
        "user" -> {
            val text = contentText(o["content"])
            if (text.isBlank()) null else UiMessage.User(text)
        }
        "assistant" -> {
            val text = contentText(o["content"])
            val tools = contentToolUses(o["content"])
            if (text.isBlank() && tools.isEmpty()) null
            else UiMessage.Assistant(text = text, toolCalls = tools)
        }
        else -> null
    }
}

private fun contentText(content: JsonElement?): String {
    if (content == null) return ""
    return when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            if (o.strAny("type") == "text") o.strAny("text") else null
        }.joinToString("")

        else -> ""
    }
}

private fun contentToolUses(content: JsonElement?): List<ToolCallInfo> {
    if (content !is JsonArray) return emptyList()
    return content.mapNotNull { b ->
        val o = b as? JsonObject ?: return@mapNotNull null
        if (o.strAny("type") != "tool_use") return@mapNotNull null
        val name = o.strAny("name")
        val input = o["input"]
        ToolCallInfo(
            toolName = name,
            summary = summarizeTool(name, input),
            input = input,
            toolUseId = o.strAny("id"),
            done = true,
        )
    }
}

fun summarizeTool(name: String?, input: JsonElement?): String {
    val obj = input as? JsonObject
    return when (name) {
        "Bash", "PowerShell" -> "$ ${obj.str("command")}"
        "Write" -> "写入 ${obj.str("file_path")}"
        "Edit" -> "编辑 ${obj.str("file_path")}"
        "Read" -> "读取 ${obj.str("file_path")}"
        "Glob" -> "查找 ${obj.str("pattern")}"
        "Grep" -> "搜索 ${obj.str("pattern")}"
        "Task", "Agent" -> "子 agent: ${obj.str("subagent_type").ifBlank { obj.str("description") }}"
        else -> name ?: ""
    }
}

private var idCounter = 0
private fun nextId(): String = "m${idCounter++}"
