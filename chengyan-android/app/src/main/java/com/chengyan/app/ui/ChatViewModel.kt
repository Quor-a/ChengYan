package com.chengyan.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chengyan.app.data.ApiProfile
import com.chengyan.app.data.AssistantPreset
import com.chengyan.app.data.ChatStore
import com.chengyan.app.data.Conversation
import com.chengyan.app.data.Message
import com.chengyan.app.core.model.toProfile
import com.chengyan.app.data.ApiProviderType
import com.chengyan.app.data.ModelConfig
import com.chengyan.app.data.SettingsStore
import com.chengyan.app.net.OpenAiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(
    private val appContext: android.content.Context,
    private val settings: SettingsStore,
    private val chats: ChatStore,
) : ViewModel() {

    private val client = OpenAiClient()

    // ---- ZorvAI 云模型配置层（完整移植自 Quor-a/ZorvAI core/model） ----
    val quroConfigRepo = com.chengyan.app.core.model.QuroModelConfigRepository(appContext)
    val quroProfileRepo = com.chengyan.app.core.model.QuroSavedProfileRepository(appContext)
    val quroCustomProviderRepo = com.chengyan.app.core.model.QuroCustomProviderRepository(appContext)
    val quroMultiProviderRepo = com.chengyan.app.core.model.QuroMultiProviderRepository(appContext)

    /** ZorvAI 风格的活跃模型配置（与下方 ModelConfig 同步）。 */
    var quroConfig by mutableStateOf(com.chengyan.app.core.model.QuroModelConfig())
        private set
    /** ZorvAI 风格的已保存预设列表。 */
    var quroProfiles by mutableStateOf(quroProfileRepo.loadAll())
        private set
    /** ZorvAI 风格的用户自建供应商列表。 */
    var quroCustomProviders by mutableStateOf(quroCustomProviderRepo.loadAll())
        private set

    var config by mutableStateOf(ModelConfig())
        private set
    var profiles by mutableStateOf<List<ApiProfile>>(emptyList())
        private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var current by mutableStateOf<Conversation?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set
    /** 当前对话使用的助手（决定 system 提示词）。 */
    var assistant by mutableStateOf<AssistantPreset?>(null)
        private set

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun toastShown() { _toast.value = null }

    private var streamJob: Job? = null

    init {
        viewModelScope.launch { config = settings.config.first() }
        viewModelScope.launch { profiles = settings.profiles.first() }
        // 加载 ZorvAI 配置层并同步到既有 ModelConfig；同时把预设登记进多提供商仓库（故障转移用）
        viewModelScope.launch {
            quroConfig = quroConfigRepo.load()
            quroProfiles = quroProfileRepo.loadAll()
            quroCustomProviders = quroCustomProviderRepo.loadAll()
            syncFromQuro(quroConfig)
            quroProfiles.forEach { p ->
                quroMultiProviderRepo.addConfig(com.chengyan.app.core.model.QuroProviderConfig(
                    id = p.id, name = p.name,
                    providerType = com.chengyan.app.core.model.ApiProviderType.fromProviderTypeId(p.provider)
                        ?: com.chengyan.app.core.model.ApiProviderType.OPENAI_GENERIC,
                    baseUrl = p.baseUrl, apiKey = p.apiKey, defaultModel = p.model,
                    priority = quroProfiles.indexOfFirst { it.id == p.id }
                ))
            }
        }
        viewModelScope.launch {
            chats.conversations.collect { list ->
                conversations = list
                current?.let { c -> current = list.firstOrNull { it.id == c.id } ?: c }
            }
        }
    }

    fun saveConfig(c: ModelConfig) {
        config = c
        viewModelScope.launch { settings.save(c) }
    }

    /* ---------- ZorvAI 配置层：保存 / 同步 / 预设 / 自建供应商 ---------- */

    /** ZorvAI 语义下的有效上下文窗口：contextWindow / modelContextLength / maxInputTokens 取较小值（0 视为不设限）。 */
    fun effectiveContextWindow(q: com.chengyan.app.core.model.QuroModelConfig): Int =
        listOfNotNull(
            q.contextWindow.takeIf { it > 0 },
            q.modelContextLength.takeIf { it > 0 },
            q.maxInputTokens.takeIf { it > 0 },
        ).minOrNull() ?: 1048576

    /** 把 ZorvAI 活跃配置同步到澄言既有 ModelConfig（流式客户端消费）。 */
    private fun syncFromQuro(q: com.chengyan.app.core.model.QuroModelConfig) {
        config = config.copy(
            url = q.baseUrl, key = q.apiKey, model = q.model,
            temperature = q.temperature,
            maxTokens = q.maxTokens, enableTools = q.enableTools,
            contextWindow = effectiveContextWindow(q)
        )
        viewModelScope.launch { settings.save(config) }
    }

    /** 保存 ZorvAI 活跃配置（等价 QuroModelConfigRepository.save + 内存同步）。 */
    fun saveQuroConfig(q: com.chengyan.app.core.model.QuroModelConfig) {
        quroConfig = q
        quroConfigRepo.save(q)
        syncFromQuro(q)
    }

    /** 保存当前配置为命名预设（QurSavedProfile.toProfile）。 */
    fun saveQuroProfile(name: String) {
        val p = quroConfig.toProfile(name)
        quroProfileRepo.save(p)
        quroProfiles = quroProfileRepo.loadAll()
        quroMultiProviderRepo.addConfig(com.chengyan.app.core.model.QuroProviderConfig(
            id = p.id, name = p.name,
            providerType = com.chengyan.app.core.model.ApiProviderType.fromProviderTypeId(p.provider)
                ?: com.chengyan.app.core.model.ApiProviderType.OPENAI_GENERIC,
            baseUrl = p.baseUrl, apiKey = p.apiKey, defaultModel = p.model,
            priority = quroProfiles.indexOfFirst { it.id == p.id }
        ))
        _toast.value = "已保存档案「$name」"
    }

    /** 应用预设（QuroSavedProfileRepository.applyToConfig）。 */
    fun applyQuroProfile(p: com.chengyan.app.core.model.QuroSavedProfile) {
        quroProfileRepo.applyToConfig(p, quroConfigRepo)
        quroConfig = quroConfigRepo.load()
        syncFromQuro(quroConfig)
        _toast.value = "已切换到「${p.name}」"
    }

    fun deleteQuroProfile(id: String) {
        quroProfileRepo.delete(id)
        quroProfiles = quroProfileRepo.loadAll()
        quroMultiProviderRepo.removeConfig(id)
    }

    /** 添加用户自建供应商（QuroCustomProviderRepository.upsert）。 */
    fun addCustomProvider(name: String, baseUrl: String, defaultModel: String) {
        val p = com.chengyan.app.core.model.QuroCustomProvider(
            id = java.util.UUID.randomUUID().toString(),
            name = name, baseUrl = baseUrl.trim().trimEnd('/'),
            defaultModel = defaultModel.trim()
        )
        quroCustomProviderRepo.upsert(p)
        quroCustomProviders = quroCustomProviderRepo.loadAll()
        _toast.value = "已添加供应商「$name」"
    }

    fun deleteCustomProvider(id: String) {
        quroCustomProviderRepo.delete(id)
        quroCustomProviders = quroCustomProviderRepo.loadAll()
    }

    /** 选中模型后回填其真实上下文长度（等价 ZorvAI modelContextLength 回填逻辑）。 */
    fun applyModelContextLength(cl: Int) {
        if (cl > 0 && quroConfig.modelContextLength != cl) {
            saveQuroConfig(quroConfig.copy(modelContextLength = cl))
        }
    }

    /* ---------- 供应商档案 ---------- */
    fun saveProfile(name: String) {
        val p = ApiProfile(
            System.currentTimeMillis(), name,
            providerType = ApiProviderType.detectFromUrl(config.url).name,
            url = config.url, key = config.key, model = config.model,
            priority = profiles.size
        )
        profiles = profiles.filterNot { it.name == name } + p
        viewModelScope.launch { settings.saveProfiles(profiles) }
        _toast.value = "已保存档案「$name」"
    }

    fun deleteProfile(id: Long) {
        profiles = profiles.filterNot { it.id == id }
        viewModelScope.launch { settings.saveProfiles(profiles) }
    }

    fun applyProfile(p: ApiProfile) {
        saveConfig(ModelConfig(
            url = p.url, key = p.key, model = p.model,
            sysPrompt = config.sysPrompt, temperature = config.temperature,
            thinking = config.thinking, providerType = p.providerType,
            maxTokens = config.maxTokens, enableTools = config.enableTools,
            contextWindow = config.contextWindow
        ))
        _toast.value = "已切换到「${p.name}」"
    }

    /** 健康记录：成功/失败后更新对应提供商（QuroMultiProviderRepository.updateHealthStatus）。 */
    private fun markProfile(url: String, ok: Boolean) {
        val id = quroMultiProviderRepo.getAllConfigs().firstOrNull { it.baseUrl == url }?.id ?: return
        quroMultiProviderRepo.updateHealthStatus(
            id,
            if (ok) com.chengyan.app.core.model.QuroProviderConfig.HealthStatus.HEALTHY
            else com.chengyan.app.core.model.QuroProviderConfig.HealthStatus.FAILED,
            if (ok) "" else "请求失败"
        )
    }

    /** 故障转移候选（QuroMultiProviderRepository.getNextProvider，FAST_FAIL 策略）。 */
    private fun failoverCandidate(failedUrl: String): com.chengyan.app.core.model.QuroProviderConfig? {
        val curId = quroMultiProviderRepo.getAllConfigs().firstOrNull { it.baseUrl == failedUrl }?.id
        return quroMultiProviderRepo.getNextProvider(curId)
    }

    /* ---------- 助手 ---------- */
    fun startWithAssistant(a: AssistantPreset?) {
        assistant = a
        newConversation()
        current?.assistantName = a?.name
        if (a != null) current?.title = "新对话 · ${a.name}"
        persist()
    }

    /* ---------- 对话 ---------- */
    fun newConversation() {
        val now = System.currentTimeMillis()
        current = Conversation(id = now, title = "新对话", created = now)
        persist()
    }

    fun pick(id: Long) {
        current = conversations.firstOrNull { it.id == id }
        // 恢复该对话的助手
        val name = current?.assistantName
        assistant = com.chengyan.app.data.DEFAULT_ASSISTANTS.firstOrNull { it.name == name }
    }

    fun deleteConversation(id: Long) {
        conversations = conversations.filterNot { it.id == id }
        if (current?.id == id) current = null
        persist()
    }

    fun clearCurrent() {
        val c = current ?: return
        c.messages.clear()
        c.title = "新对话"
        c.updated = System.currentTimeMillis()
        persist()
        current = null
        current = c
    }

    fun deleteMessage(msg: Message) {
        val c = current ?: return
        c.messages.remove(msg)
        persist()
        current = null; current = c
    }

    /* ---------- 发送 ---------- */
    fun send(text: String, images: List<String> = emptyList()) {
        if (streaming) return
        if (!config.configured) { _toast.value = "请先在设置中完成模型配置"; return }
        val c = current ?: run { newConversation(); current!! }
        val now = System.currentTimeMillis()
        if (c.messages.isEmpty()) c.title = text.take(18)
        c.messages.add(Message(now, "user", text, now, images = images))
        c.updated = now
        request(c, historyUpTo(c, c.messages.lastIndex))
    }

    fun regenerate(msg: Message) {
        if (streaming) return
        if (!config.configured) { _toast.value = "请先在设置中完成模型配置"; return }
        val c = current ?: return
        val idx = c.messages.indexOf(msg)
        if (idx <= 0) return
        var j = idx
        while (j > 0 && c.messages[j].role != "user") j--
        while (c.messages.size > j + 1) c.messages.removeAt(c.messages.size - 1)
        request(c, historyUpTo(c, c.messages.lastIndex))
    }

    fun stop() { streamJob?.cancel(); streamJob = null }

    /* ---------- 编辑 / 重命名 / 导出 ---------- */
    fun editAndResend(msg: Message, newText: String) {
        if (streaming) return
        val c = current ?: return
        val idx = c.messages.indexOf(msg)
        if (idx < 0) return
        msg.content = newText
        while (c.messages.size > idx + 1) c.messages.removeAt(c.messages.size - 1)
        if (c.messages.size == 1) c.title = newText.take(18)
        c.updated = System.currentTimeMillis()
        request(c, historyUpTo(c, idx))
    }

    fun renameConversation(id: Long, title: String) {
        conversations.firstOrNull { it.id == id }?.let {
            it.title = title.ifBlank { "新对话" }
            it.updated = System.currentTimeMillis()
            persist()
            current = null; current = it
        }
    }

    fun exportCurrent(): String? {
        val c = current ?: return null
        return buildString {
            appendLine("# ${c.title}")
            appendLine()
            for (m in c.messages) {
                appendLine("## ${if (m.role == "user") "你" else "澄言"}${m.model?.let { "/$it" } ?: ""}")
                appendLine(m.content)
                appendLine()
            }
        }
    }

    /* ---------- 备份 / 恢复 ---------- */
    fun backupAll(): String = chats.export(conversations.toList())

    fun restoreBackup(text: String) {
        val restored = chats.restore(text)
        if (restored == null) {
            _toast.value = "恢复失败：文件格式不正确"
            return
        }
        // 合并：按 id 去重，恢复的优先
        val merged = (restored + conversations).distinctBy { it.id }.sortedBy { it.created }
        conversations = merged
        viewModelScope.launch { chats.save(merged) }
        _toast.value = "已恢复 ${restored.size} 条对话"
    }

    /* ---------- 内部 ---------- */
    private fun historyUpTo(c: Conversation, endIdx: Int): List<OpenAiClient.ChatTurn> {
        val turns = mutableListOf<OpenAiClient.ChatTurn>()
        val sys = assistant?.prompt?.takeIf { it.isNotBlank() } ?: config.sysPrompt
        if (sys.isNotBlank()) turns.add(OpenAiClient.ChatTurn("system", sys))
        for (i in 0..endIdx.coerceAtMost(c.messages.size - 1)) {
            val m = c.messages[i]
            if (!m.isError) turns.add(OpenAiClient.ChatTurn(m.role, m.content, m.images))
        }
        // 图片只保留最近两轮，避免上下文爆炸
        val cutoff = turns.size - 2
        val trimmed = turns.mapIndexed { i, t -> if (i < cutoff) t.copy(images = emptyList()) else t }
        // 上下文预算裁剪（移植自 ZorvAI 上下文压缩思路）：估算 token 超过 contextWindow 时从最旧的消息裁起，system 保留
        fun estimate(t: OpenAiClient.ChatTurn): Int = t.text.length / 2 + t.images.size * 800
        var budget = (config.contextWindow * (quroConfig.contextCompressThreshold.takeIf { it in 1..100 } ?: 80) / 100)
            .coerceAtLeast(4096)
        var used = trimmed.sumOf { estimate(it) }
        var start = 0
        while (used > budget && start < trimmed.size - 1 && trimmed[start].role != "user") {
            used -= estimate(trimmed[start]); start++
        }
        if (used > budget && start < trimmed.size - 1 && trimmed[start].role == "user") {
            // 最旧一条是 user 也裁（保留最后一条 user）
            used -= estimate(trimmed[start]); start++
        }
        return trimmed.drop(start)
    }

    private fun request(c: Conversation, history: List<OpenAiClient.ChatTurn>, allowFailover: Boolean = true) {
        val now = System.currentTimeMillis()
        val ai = Message(now, "assistant", "", now, model = config.model, streaming = true)
        c.messages.add(ai)
        streaming = true
        persist()
        current = null; current = c

        streamJob = viewModelScope.launch {
            try {
                client.stream(config, history).collect { ev ->
                    when (ev) {
                        is OpenAiClient.StreamEvent.Delta -> {
                            ai.content += ev.text
                            current = null; current = c
                        }
                        is OpenAiClient.StreamEvent.Failure -> {
                            ai.isError = true
                            ai.content = "请求失败：${ev.message}"
                        }
                        OpenAiClient.StreamEvent.Done -> {
                            if (ai.content.isBlank()) ai.content = "（模型返回了空回复）"
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                if (ai.content.isBlank()) ai.content = "（已停止生成）"
            } finally {
                ai.streaming = false
                streaming = false
                c.updated = System.currentTimeMillis()
                persist()
                current = null; current = c
            }
        }
    }

    private fun persist() {
        val snapshot = conversations.toList()
        viewModelScope.launch { chats.save(snapshot) }
    }
}
