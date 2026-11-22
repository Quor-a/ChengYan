package com.chengyan.app.data

/*
 * 灵魂注入的配套能力 —— 语义移植自 Quor-a/ZorvAI QuroSoulUi / QuroMemoryRepository / 全局标签池：
 *   · 记忆库：每张灵魂卡的长期记忆条目（标题/内容/标签），注入为该角色的系统提示词补充
 *   · 全局标签池：灵魂卡可从池中选标签；标签池可增删改
 * 存储走 DataStore；实现为澄言原创。
 */

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.memoryStore by preferencesDataStore(name = "chengyan_memories")
private val Context.tagStore by preferencesDataStore(name = "chengyan_tags")
private val memJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
private val MEM_KEY = stringPreferencesKey("entries")
private val TAGS_KEY = stringPreferencesKey("tags")

@Serializable
data class SoulMemory(
    val id: String,
    val personaId: String = "",
    val title: String = "",
    val content: String = "",
    val group: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

class SoulMemoryRepository(private val context: Context) {

    suspend fun loadAll(): List<SoulMemory> =
        context.memoryStore.data.first()[MEM_KEY]?.let {
            runCatching { memJson.decodeFromString<List<SoulMemory>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

    suspend fun loadForPersona(personaId: String): List<SoulMemory> =
        loadAll().filter { it.personaId == personaId }

    suspend fun add(e: SoulMemory) = save(e)

    suspend fun update(e: SoulMemory) = save(e)

    private suspend fun save(e: SoulMemory) {
        val list = loadAll().filterNot { it.id == e.id } + e
        context.memoryStore.edit { it[MEM_KEY] = memJson.encodeToString(list) }
    }

    suspend fun delete(id: String) {
        context.memoryStore.edit { it[MEM_KEY] = memJson.encodeToString(loadAll().filterNot { it.id == id }) }
    }

    suspend fun deleteForPersona(personaId: String) {
        context.memoryStore.edit { it[MEM_KEY] = memJson.encodeToString(loadAll().filterNot { it.personaId == personaId }) }
    }

    suspend fun search(query: String): List<SoulMemory> =
        loadAll().filter { it.title.contains(query, true) || it.content.contains(query, true) }

    /** 记忆注入片段（ZorvAI 记忆库语义：追加在灵魂提示词之后）。 */
    fun injectBlock(entries: List<SoulMemory>): String = if (entries.isEmpty()) "" else buildString {
        appendLine()
        appendLine("【记忆库】（你与用户之间沉淀的长期记忆，回答时可自然引用）")
        entries.forEach { e ->
            val head = if (e.title.isNotBlank()) "${e.title}：" else ""
            appendLine("- $head${e.content}")
        }
    }.trimEnd()

    // ---- 导出 / 导入（JSON，SAF）----
    suspend fun exportJson(): String = memJson.encodeToString(loadAll())

    /** 返回解析出的条目数；按 id 去重合并（导入优先）。 */
    suspend fun mergeImport(text: String): Int {
        val imported = runCatching { memJson.decodeFromString<List<SoulMemory>>(text) }.getOrNull() ?: return 0
        if (imported.isEmpty()) return 0
        val merged = (imported + loadAll()).distinctBy { it.id }
        context.memoryStore.edit { it[MEM_KEY] = memJson.encodeToString(merged) }
        return imported.size
    }
}

/** 全局标签池（灵魂卡从池里选标签；池可增删改）。 */
class TagPoolRepository(private val context: Context) {

    suspend fun loadAll(): List<String> =
        context.tagStore.data.first()[TAGS_KEY]?.let {
            runCatching { memJson.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

    suspend fun saveAll(tags: List<String>) {
        context.tagStore.edit { it[TAGS_KEY] = memJson.encodeToString(tags.distinct()) }
    }

    suspend fun add(name: String) = saveAll(loadAll() + name.trim())

    suspend fun remove(name: String) = saveAll(loadAll().filterNot { it == name })

    suspend fun rename(old: String, new: String) = saveAll(loadAll().map { if (it == old) new else it })
}
