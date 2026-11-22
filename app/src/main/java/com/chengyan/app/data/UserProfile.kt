package com.chengyan.app.data

/*
 * 用户资料 —— 设置里的「用户资料」：头像 + 用户名称。
 * 设置后全局显示：用户消息行的头像与名字替换「你」占位。
 * 存储走 DataStore（与 SettingsStore/SoulRepository 同管线）。
 */

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.userStore by preferencesDataStore(name = "chengyan_user")
private val userJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val USER_KEY = stringPreferencesKey("profile")

@Serializable
data class UserProfile(
    val name: String = "",               // 空 = 显示「你」
    val avatarUri: String = "",          // 应用私有目录里的头像绝对路径；空 = 「你」字圆标
)

class UserProfileRepository(private val context: Context) {
    suspend fun load(): UserProfile =
        context.userStore.data.first()[USER_KEY]?.let {
            runCatching { userJson.decodeFromString<UserProfile>(it) }.getOrDefault(UserProfile())
        } ?: UserProfile()

    suspend fun save(p: UserProfile) {
        context.userStore.edit { it[USER_KEY] = userJson.encodeToString(p) }
    }
}
