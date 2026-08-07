package com.example.clauderemote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "claude_remote_settings")

data class ServerSettings(
    val serverUrl: String = "ws://192.168.1.100:8787",
    val authToken: String = "",
)

/** 用 DataStore 持久化服务器地址与 token。 */
class SettingsStore(private val context: Context) {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val AUTH_TOKEN = stringPreferencesKey("auth_token")

    val settings: Flow<ServerSettings> = context.dataStore.data.map { p ->
        ServerSettings(
            serverUrl = p[SERVER_URL] ?: "ws://192.168.1.100:8787",
            authToken = p[AUTH_TOKEN] ?: "",
        )
    }

    suspend fun save(serverUrl: String, authToken: String) {
        context.dataStore.edit { p ->
            p[SERVER_URL] = serverUrl
            p[AUTH_TOKEN] = authToken
        }
    }
}
