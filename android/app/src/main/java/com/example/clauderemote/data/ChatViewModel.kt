package com.example.clauderemote.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** 工具调用展示信息 */
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val summary: String,
    val input: JsonElement? = null,
    val toolUseId: String? = null,
    val result: String? = null,
    val isError: Boolean = false,
    val done: Boolean = false,
)

/** 列表里的消息（用户 / assistant 文本+工具 / 思考） */
@Serializable
sealed class UiMessage {
    abstract val id: String
    abstract val timestamp: Long

    @Serializable @SerialName("user")
    data class User(
        val text: String,
        override val id: String = nextId(),
        override val timestamp: Long = System.currentTimeMillis(),
    ) : UiMessage()

    @Serializable @SerialName("assistant")
    data class Assistant(
        val text: String = "",
        val thinking: String? = null,
        val toolCalls: List<ToolCallInfo> = emptyList(),
        val streaming: Boolean = false,
        val costUsd: Double? = null,
        val tokensIn: Int? = null,
        val tokensOut: Int? = null,
        val cacheRead: Int? = null,
        val cacheCreate: Int? = null,
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
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val loadingSessions: Boolean = false,
) {
    /** 当前要弹窗的那个审批（队列首）。 */
    val activePermission: PendingPermission? get() = pendingPermissions.firstOrNull()
}

class ChatViewModel(
    private val serverUrl: String,
    private val token: String,
    private val store: MessageStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var client: WsClient? = null

    init {
        loadPersisted()
        connect()
        startPersisting()
    }

    fun connect() {
        client?.disconnect()
        client = WsClient(serverUrl, token, ::onMessage, ::onState)
        client?.connect()
    }

    fun send(prompt: String) {
        if (prompt.isBlank()) return
        // 客户端并发守卫：服务端也有 busy 拦截，这里是体验层面的防抖。
        if (_state.value.isBusy) {
            _state.update { it.copy(status = "上一轮还在进行，请先中断") }
            return
        }
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
                pendingPermissions = emptyList(),
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
        _state.update { s ->
            s.copy(pendingPermissions = s.pendingPermissions.filterNot { it.requestId == requestId })
        }
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
                // 后端已把 tool_use 剥离为独立事件；这里只处理 text/thinking。
                // 完整 assistant 文本是权威值：替换（而非追加）流式草稿，避免与 partial 重复。
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val newText = m.content.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
                    val thinkText = m.content.filter { it.type == "thinking" }.joinToString("\n") { it.thinking ?: "" }
                    if (newText.isNotBlank() || thinkText.isNotBlank()) {
                        val lastStreaming = msgs.indexOfLast { it is UiMessage.Assistant && it.streaming }
                        when {
                            lastStreaming >= 0 -> {
                                val prev = msgs[lastStreaming] as UiMessage.Assistant
                                val mergedThink = listOfNotNull(prev.thinking, thinkText.ifBlank { null })
                                    .joinToString("\n").ifBlank { null }
                                msgs[lastStreaming] = prev.copy(
                                    text = newText.ifBlank { prev.text },
                                    thinking = mergedThink,
                                    streaming = false,
                                )
                            }
                            newText.isNotBlank() -> {
                                // 可见回答（无前置流式草稿）：单独成泡。
                                msgs.add(UiMessage.Assistant(text = newText, thinking = thinkText.ifBlank { null }))
                            }
                            else -> {
                                // 只有 thinking：并入最后一条「无文本」工作气泡，
                                // 避免工具调用之间每次中间思考都各自成泡。
                                val lastWork = msgs.indexOfLast { it is UiMessage.Assistant && it.text.isBlank() }
                                if (lastWork >= 0) {
                                    val prev = msgs[lastWork] as UiMessage.Assistant
                                    val mergedThink = listOfNotNull(prev.thinking, thinkText.ifBlank { null })
                                        .joinToString("\n").ifBlank { null }
                                    msgs[lastWork] = prev.copy(thinking = mergedThink)
                                } else {
                                    msgs.add(UiMessage.Assistant(thinking = thinkText.ifBlank { null }))
                                }
                            }
                        }
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
                    val info = ToolCallInfo(
                        m.toolName,
                        summarizeTool(m.toolName, m.input),
                        m.input,
                        toolUseId = m.toolUseId,
                        done = false,
                    )
                    // 连续的工具调用合并进同一个「工作中」气泡（无可见文本的那条），
                    // 避免一轮里 N 次工具调用变成 N 个独立气泡、在手机上重复刷屏。
                    val last = msgs.lastOrNull()
                    if (last is UiMessage.Assistant && last.text.isBlank()) {
                        msgs[msgs.lastIndex] = last.copy(toolCalls = last.toolCalls + info)
                    } else {
                        msgs.add(UiMessage.Assistant(toolCalls = listOf(info)))
                    }
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
                val usage = parseUsage(m.usage)
                val cost = m.costUsd
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val idx = msgs.indexOfLast { it is UiMessage.Assistant }
                    if (idx >= 0) {
                        val a = msgs[idx] as UiMessage.Assistant
                        msgs[idx] = a.copy(
                            costUsd = cost ?: a.costUsd,
                            tokensIn = usage.input ?: a.tokensIn,
                            tokensOut = usage.output ?: a.tokensOut,
                            cacheRead = usage.cacheRead ?: a.cacheRead,
                            cacheCreate = usage.cacheCreate ?: a.cacheCreate,
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

            is ServerMessage.PermissionRequest -> _state.update { s ->
                val p = PendingPermission(m.requestId, m.toolName, m.summary)
                // 同一 requestId 不重复入队
                if (s.pendingPermissions.any { it.requestId == m.requestId }) s
                else s.copy(pendingPermissions = s.pendingPermissions + p)
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

    // ── 持久化 ──

    private fun loadPersisted() {
        val s = store ?: return
        viewModelScope.launch {
            val saved = s.data.first()
            if (_state.value.messages.isEmpty() && (saved.messages.isNotEmpty() || saved.sessionId != null)) {
                // 恢复时清掉残留的 streaming 标记，避免界面一直显示输入光标。
                val sanitized = saved.messages.map {
                    if (it is UiMessage.Assistant && it.streaming) it.copy(streaming = false) else it
                }
                _state.update { it.copy(messages = sanitized, sessionId = saved.sessionId) }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    private fun startPersisting() {
        val s = store ?: return
        viewModelScope.launch {
            _state
                .debounce(600)
                .collect { st -> s.save(SavedConversation(st.sessionId, st.messages)) }
        }
    }
}

// ── 解析辅助 ──

private val prettyJson = Json { prettyPrint = true }

private data class Usage(
    val input: Int? = null,
    val output: Int? = null,
    val cacheRead: Int? = null,
    val cacheCreate: Int? = null,
)

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

private fun parseUsage(usage: JsonElement?): Usage {
    val o = usage as? JsonObject ?: return Usage()
    return Usage(
        input = (o["input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
        output = (o["output_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
        cacheRead = (o["cache_read_input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
        cacheCreate = (o["cache_creation_input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
    )
}

private fun parseSession(el: JsonElement): SessionInfo? {
    val o = el as? JsonObject ?: return null
    val id = o.strAny("sessionId", "session_id", "id")
    if (id.isBlank()) return null
    val title = o.strAny("summary", "customTitle", "firstPrompt", "title", "name", "path")
        .ifBlank { "（无摘要）" }
    val ts = o.longAny("lastModified", "timestamp", "createdAt", "updatedAt", "time")
    return SessionInfo(id, title, ts)
}

private fun rebuildMessage(el: JsonElement): UiMessage? {
    val o = el as? JsonObject ?: return null
    // SDK 的 SessionMessage 把真正的 { role, content } 嵌在 `message` 字段里，
    // 顶层只有 type/uuid/session_id 等。从 message.content 取文本与工具调用，
    // 否则读到的永远是空 → 载入会话一片空白。
    val inner = o["message"] as? JsonObject
    val content = inner?.get("content") ?: o["content"]
    return when (o.strAny("role", "type")) {
        "user" -> {
            val text = contentText(content)
            if (text.isBlank()) null else UiMessage.User(text)
        }
        "assistant" -> {
            val text = contentText(content)
            val tools = contentToolUses(content)
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

private val idCounter = AtomicLong(0)
private fun nextId(): String = "m${idCounter.incrementAndGet()}"
