package com.chengyan.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "chengyan")
private val Context.convStore by preferencesDataStore(name = "chengyan_convs")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val CFG_KEY = stringPreferencesKey("config")
private val CONVS_KEY = stringPreferencesKey("conversations")
private val PROFILES_KEY = stringPreferencesKey("profiles")

class SettingsStore(private val context: Context) {
    val config: Flow<ModelConfig> = context.dataStore.data.map { p ->
        // 🔴 存取均走 CryptoBox（Keystore AES/GCM）：save 加密，read 解密；历史明文透明兼容
        p[CFG_KEY]?.let { s ->
            runCatching { json.decodeFromString<ModelConfig>(CryptoBox.decrypt(s)) }.getOrNull()
        } ?: ModelConfig()
    }

    suspend fun save(config: ModelConfig) {
        context.dataStore.edit { it[CFG_KEY] = CryptoBox.encrypt(json.encodeToString(config)) }
    }

    val profiles: Flow<List<ApiProfile>> = context.dataStore.data.map { p ->
        p[PROFILES_KEY]?.let {
            runCatching { json.decodeFromString<List<ApiProfile>>(CryptoBox.decrypt(it)) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun saveProfiles(list: List<ApiProfile>) {
        context.dataStore.edit { it[PROFILES_KEY] = CryptoBox.encrypt(json.encodeToString(list)) }
    }
}

class ChatStore(private val context: Context) {
    val conversations: Flow<List<Conversation>> = context.convStore.data.map { p ->
        p[CONVS_KEY]?.let {
            runCatching { json.decodeFromString<List<Conversation>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun save(list: List<Conversation>) {
        context.convStore.edit { it[CONVS_KEY] = json.encodeToString(list) }
    }

    /** 备份：全部会话序列化为 JSON。 */
    fun export(list: List<Conversation>): String = json.encodeToString(list)

    /** 恢复：从 JSON 反序列化（失败返回 null）。 */
    fun restore(text: String): List<Conversation>? =
        runCatching { json.decodeFromString<List<Conversation>>(text) }.getOrNull()
}
