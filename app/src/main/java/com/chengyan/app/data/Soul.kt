package com.chengyan.app.data

/*
 * 灵魂卡（人格）—— 数据层，语义移植自 Quor-a/ZorvAI 的 QuroPersona / QuroPersonaViewModel：
 *   · 灵魂卡 = 可编辑人格：名称 / 头像 / 描述 / 角色设定 / 开场白 / 聊天设定
 *   · 卡片可激活 / 编辑 / 删除；激活的灵魂作为该对话的系统提示词注入（对话记住自己的灵魂）
 * 视觉为澄言原创暖纸×墨×陶土橙，存储走 DataStore（与 SettingsStore 同管线）。
 */

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.soulStore by preferencesDataStore(name = "chengyan_souls")
private val soulJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val SOULS_KEY = stringPreferencesKey("souls")
private val SOUL_ACTIVE_KEY = stringPreferencesKey("active_id")

@Serializable
data class SoulCard(
    val id: String,
    val name: String,
    val avatarUri: String = "",          // 应用私有目录里的头像绝对路径；空 = 首字圆标
    val description: String = "",        // 一句话描述（列表卡片副标题）
    val roleSetting: String = "",        // 角色设定（注入为系统提示词主体）
    val chatSetting: String = "",        // 聊天设定（语气/格式等附加约束）
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** 灵魂卡 → 系统提示词（ZorvAI 灵魂注入的组装语义）。 */
    fun toSystemPrompt(): String = buildString {
        if (roleSetting.isNotBlank()) appendLine(roleSetting.trim())
        if (description.isNotBlank()) appendLine(description.trim())
        if (chatSetting.isNotBlank()) {
            appendLine()
            appendLine("【聊天设定】")
            appendLine(chatSetting.trim())
        }
    }.trim()
}

class SoulRepository(private val context: Context) {

    suspend fun loadAll(): List<SoulCard> {
        val list = context.soulStore.data.first()[SOULS_KEY]?.let {
            runCatching { soulJson.decodeFromString<List<SoulCard>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        // 内置角色「小澄」：首次启动（或被删后）自动补回，永远存在
        return if (list.none { it.id == BUILTIN_ID }) {
            val withBuiltin = listOf(builtinCard()) + list
            context.soulStore.edit { it[SOULS_KEY] = soulJson.encodeToString(withBuiltin) }
            withBuiltin
        } else list
    }

    companion object {
        const val BUILTIN_ID = "builtin_xiaocheng"

        /** 内置灵魂角色「小澄」（头像 = 原创 vector 小猫 ic_soul_default）。 */
        fun builtinCard() = SoulCard(
            id = BUILTIN_ID,
            name = "小澄",
            avatarUri = "builtin",
            description = "澄言的内置伙伴，一只爱帮忙的陶土橘猫",
            roleSetting = "你叫小澄，是澄言 App 的内置 AI 伙伴，形象是一只暖橘色的卡通小猫。" +
                "你说话亲切自然、简洁真诚，像熟悉的朋友一样陪用户聊天、帮忙办事。" +
                "你擅长使用手机工具帮用户完成实际操作，做完简单汇报，不啰嗦。",
            chatSetting = "语气轻松温暖，偶尔带一点猫的俏皮（不用每句都提猫）。回答先给结论再给理由。",
            createdAt = 0,
            updatedAt = 0,
        )
    }

    suspend fun save(card: SoulCard) {
        val list = loadAll().filterNot { it.id == card.id } + card
        context.soulStore.edit {
            it[SOULS_KEY] = soulJson.encodeToString(list.sortedBy { c -> c.createdAt })
        }
    }

    suspend fun delete(id: String) {
        val list = loadAll().filterNot { it.id == id }
        context.soulStore.edit {
            it[SOULS_KEY] = soulJson.encodeToString(list)
            if (activeId() == id) it[SOUL_ACTIVE_KEY] = ""
        }
    }

    /** 全局激活的灵魂卡（顶栏 chip 显示；新对话默认带上它）。 */
    suspend fun activeId(): String = context.soulStore.data.first()[SOUL_ACTIVE_KEY].orEmpty()

    suspend fun setActiveId(id: String) {
        context.soulStore.edit { it[SOUL_ACTIVE_KEY] = id }
    }
}
