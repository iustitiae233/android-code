package com.example.clauderemote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.msgDataStore by preferencesDataStore(name = "claude_remote_messages")
private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

/** 持久化的对话快照：当前 sessionId + 消息列表。进程被杀后可据此恢复。 */
@Serializable
data class SavedConversation(
    val sessionId: String? = null,
    val messages: List<UiMessage> = emptyList(),
)

/** 用 DataStore 持久化最近一次对话（JSON 字符串），便于进程重启后恢复。 */
class MessageStore(private val context: Context) {
    private val KEY = stringPreferencesKey("conversation_json")

    val data: Flow<SavedConversation> = context.msgDataStore.data.map { p ->
        try {
            val s = p[KEY] ?: return@map SavedConversation()
            json.decodeFromString(SavedConversation.serializer(), s)
        } catch (e: Exception) {
            SavedConversation()
        }
    }

    suspend fun save(c: SavedConversation) {
        context.msgDataStore.edit { p ->
            p[KEY] = json.encodeToString(SavedConversation.serializer(), c)
        }
    }

    suspend fun clear() {
        context.msgDataStore.edit { it.remove(KEY) }
    }
}
