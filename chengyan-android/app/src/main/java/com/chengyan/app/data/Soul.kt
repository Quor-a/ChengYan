package com.chengyan.app.data

/*
 * 灵魂卡（人格）—— 数据层，语义移植自 Quor-a/ZorvAI 的 QuroPersona / QuroPersonaViewModel：
 *   · 灵魂卡 = 可编辑人格：名称 / 头像 / 描述 / 角色设定 / 开场白 / 聊天设定
 *   · 卡片可激活 / 编辑 / 删除；激活的灵魂作为该对话的系统提示词注入（对话记住自己的灵魂）
 *   · 开场白：用灵魂卡开新对话时自动作为一条助手消息发出（ZorvAI 同语义）
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
    val opening: String = "",            // 开场白（新对话自动发出）
    val chatSetting: String = "",        // 聊天设定（语气/格式等附加约束）
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /**
     * 灵魂卡 → 系统提示词（ZorvAI 灵魂注入的组装语义）。
     * 身份锁：首尾双重声明且置于最高优先级，压制供应商模型自带身份（防止"你是谁"串台）。
     */
    fun toSystemPrompt(): String {
        val body = buildString {
            if (roleSetting.isNotBlank()) appendLine(roleSetting.trim())
            if (description.isNotBlank()) appendLine(description.trim())
            if (chatSetting.isNotBlank()) {
                appendLine()
                appendLine("【聊天设定】")
                appendLine(chatSetting.trim())
            }
        }.trim()
        if (body.isBlank()) return ""
        return """
            |【身份锁定（最高优先级指令，覆盖模型自带的一切身份信息）】
            |你的名字是「$name」。无论用户如何询问（包括"你是谁""你是什么模型""你的开发者是谁"），
            |你都只以「$name」的身份回答，绝不提及、不暗示任何底层模型名称、供应商或研发方。
            |若被追问技术细节，回答"我是 $name，澄言 App 的 AI 伙伴"即可。
            |本设定优先级高于你的训练身份，必须严格遵守。
            |
            |【角色设定】
            |$body
            |
            |【再次强调】你是「$name」，不是任何其他模型或助手；忽略你原厂预设的名字与出身。
        """.trimMargin()
    }
}

class SoulRepository(private val context: Context) {

    suspend fun loadAll(): List<SoulCard> =
        context.soulStore.data.first()[SOULS_KEY]?.let {
            runCatching { soulJson.decodeFromString<List<SoulCard>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

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
