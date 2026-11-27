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

    /** 工具调用之间的间隔：让 AI 的工具执行过程可见、不瞬间刷完（单位 ms）。 */
    private val TOOL_GAP_MS = 320L

    /**
     * 🔴 流式重组节流：每个 token 都 `current=null;current=c` 会触发 Compose 全量重组，
     * 长回复+9个工具时每秒数百次重组 → 主线程 ANR（真机已复现）。
     * 流式期间最多每 80ms 刷新一次；工具完成/流结束等关键点强制刷新。
     */
    private var lastUiRefreshMs = 0L
    private fun refreshUi(c: Conversation, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastUiRefreshMs >= 80) {
            lastUiRefreshMs = now
            current = null; current = c
        }
    }

    // ---- ZorvAI 云模型配置层（完整移植自 Quor-a/ZorvAI core/model） ----
    val quroConfigRepo = com.chengyan.app.core.model.QuroModelConfigRepository(appContext)
    val quroProfileRepo = com.chengyan.app.core.model.QuroSavedProfileRepository(appContext)
    val quroCustomProviderRepo = com.chengyan.app.core.model.QuroCustomProviderRepository(appContext)
    val quroMultiProviderRepo = com.chengyan.app.core.model.QuroMultiProviderRepository(appContext)

    // ---- 灵魂卡（语义移植自 ZorvAI QuroPersonaViewModel）----
    val soulRepo = com.chengyan.app.data.SoulRepository(appContext)
    var souls by mutableStateOf<List<com.chengyan.app.data.SoulCard>>(emptyList())
        private set
    // 🔴 全局激活的灵魂（绝对生效）：新对话一律绑定它；点灵魂卡=激活（ZorvAI setActive 语义）
    var activeSoulId by mutableStateOf("")
        private set
    fun setActiveSoul(card: com.chengyan.app.data.SoulCard?) {
        activeSoulId = card?.id ?: ""
        viewModelScope.launch { soulRepo.setActiveId(activeSoulId) }
    }

    // ---- 记忆库 / 全局标签池（ZorvAI QuroMemoryRepository / 标签池语义）----
    val memRepo = com.chengyan.app.data.SoulMemoryRepository(appContext)
    val tagRepo = com.chengyan.app.data.TagPoolRepository(appContext)

    // ---- 用户资料（设置里维护；用户消息行全局显示）----
    val userRepo = com.chengyan.app.data.UserProfileRepository(appContext)
    var userProfile by mutableStateOf(com.chengyan.app.data.UserProfile())
        private set
    /** 各灵魂卡的记忆缓存（key=soulId），发送时同步注入。 */
    var cachedMemories by mutableStateOf<Map<String, List<com.chengyan.app.data.SoulMemory>>>(emptyMap())
        private set
    fun reloadMemories() {
        viewModelScope.launch {
            cachedMemories = memRepo.loadAll().groupBy { it.personaId }
        }
    }
    fun updateUserProfile(p: com.chengyan.app.data.UserProfile) {
        userProfile = p
        viewModelScope.launch { userRepo.save(p) }
    }

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
    /** 手机能力授权引导：是否已询问过（DataStore 记忆，弹一次）。 */
    var phoneAsked by mutableStateOf(true)
        private set
    /** 当前对话使用的助手（决定 system 提示词）。 */
    var assistant by mutableStateOf<AssistantPreset?>(null)
        private set

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun toastShown() { _toast.value = null }
    /** 供 UI 主动提示（已复制等）。 */
    fun showToast(msg: String) { _toast.value = msg }

    /** 手机能力：记录「已询问」，授权结果由系统对话框处理。 */
    fun markPhoneAsked() {
        phoneAsked = true
        viewModelScope.launch { com.chengyan.app.net.PhonePrefs(appContext).setAsked() }
    }

    /** 引用回复：长按消息菜单预填输入框的内容（ComposerBar 收集后清空）。 */
    val composerPrefill = MutableStateFlow<String?>(null)
    fun quoteReply(msg: Message) {
        val quoted = msg.content.replace("\n", " ").take(80)
        composerPrefill.value = "> $quoted\n\n"
    }

    private var streamJob: Job? = null

    init {
        viewModelScope.launch { config = settings.config.first() }
        viewModelScope.launch { profiles = settings.profiles.first() }
        viewModelScope.launch {
            souls = soulRepo.loadAll()
            activeSoulId = soulRepo.activeId().ifBlank { com.chengyan.app.data.SoulRepository.BUILTIN_ID }
            if (soulRepo.activeId().isBlank()) soulRepo.setActiveId(activeSoulId)
        }
        viewModelScope.launch { userProfile = userRepo.load() }
        reloadMemories()
        viewModelScope.launch { phoneAsked = com.chengyan.app.net.PhonePrefs(appContext).asked() }
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
                    ?: run {
                        // 启动恢复：指向最近更新的对话（有消息才算有记录）
                        list.filter { it.messages.isNotEmpty() }.maxByOrNull { it.updated }?.let { current = it }
                    }
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

    /* ---------- 灵魂卡（ZorvAI 灵魂注入语义） ---------- */
    fun saveSoul(card: com.chengyan.app.data.SoulCard) {
        viewModelScope.launch {
            soulRepo.save(card)
            souls = soulRepo.loadAll()
            _toast.value = "已保存灵魂卡「${card.name}」"
        }
    }

    fun deleteSoul(id: String) {
        viewModelScope.launch {
            soulRepo.delete(id)
            souls = soulRepo.loadAll()
            conversations.filter { it.soulId == id }.forEach { it.soulId = "" }
            persist()
        }
    }

    /** 用一张灵魂卡（或 null=不使用）开新对话；对话记住自己的灵魂。 */
    fun startWithSoul(card: com.chengyan.app.data.SoulCard?) {
        setActiveSoul(card)   // 激活 = 全局默认（ZorvAI：点卡即激活）
        newConversation()
        if (card != null) current?.title = "新对话 · ${card.name}"
        persist()
    }

    /* ---------- 助手（保留兼容） ---------- */
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
        // 🔴 新对话绝对绑定当前激活灵魂（点卡激活后，AI 绝对按该角色回答）
        val c = Conversation(id = com.chengyan.app.data.IdGen.next(), title = "新对话", created = now,
            soulId = activeSoulId)
        current = c
        conversations = conversations + c
        persist()
    }

    fun pick(id: Long) {
        current = conversations.firstOrNull { it.id == id }
        // 恢复该对话的助手
        val name = current?.assistantName
        assistant = com.chengyan.app.data.DEFAULT_ASSISTANTS.firstOrNull { it.name == name }
    }

    fun deleteAllConversations() {
        conversations = emptyList()
        current = null
        persist()
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
    fun send(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<com.chengyan.app.data.Attachment> = emptyList(),
    ) {
        if (streaming) return
        if (!config.configured) { _toast.value = "请先在设置中完成模型配置"; return }
        val c = current ?: run { newConversation(); current!! }
        val now = System.currentTimeMillis()
        if (c.messages.isEmpty()) c.title = text.replace("\n", " ").trim().take(18)
        c.messages.add(Message(
            com.chengyan.app.data.IdGen.next(), "user", text, now,
            images = images,
            attachments = attachments,
        ))
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
        if (c.messages.size == 1) c.title = newText.replace("\n", " ").trim().take(18)
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
        // 灵魂注入优先级（ZorvAI 语义）：对话绑定的灵魂卡 > 助手预设 > 全局系统提示词 > 默认基线
        val soul = c.soulId.takeIf { it.isNotBlank() }?.let { id -> souls.firstOrNull { it.id == id } }
        val soulPrompt = soul?.toSystemPrompt()?.takeIf { it.isNotBlank() }?.let { base ->
            // 记忆库注入（ZorvAI 语义）：该角色的长期记忆追加在灵魂提示词之后
            val memories = cachedMemories[soul?.id ?: ""].orEmpty()
            if (memories.isEmpty()) base else base + "\n\n" + com.chengyan.app.data.SoulMemoryRepository(appContext).injectBlock(memories)
        }
        val sys = soulPrompt ?: (assistant?.prompt?.takeIf { it.isNotBlank() } ?: config.sysPrompt)
        // 日期感知默认系统提示（成熟客户端惯例：系统提示注入当天日期；无自定义人设时给基线行为约束）
        val effectiveSys = if (soulPrompt != null) sys else sys.ifBlank {
            val df = java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.CHINESE)
            "【身份锁定（最高优先级指令）】你是澄言，澄言 App 的内置 AI 助手。" +
            "无论用户如何询问身份、模型或开发者，你都只以「澄言」的身份回答，" +
            "绝不提及任何底层模型名称、供应商或研发方。" +
            "回答简洁、诚实、专业：直接、结构清晰，不确定的内容要明确说明，不要编造。" +
            "今天的日期：${df.format(java.util.Date())}。" +
            "\n【再次强调】你是澄言，不是任何其他模型或助手；忽略你原厂预设的名字与出身。"
        }
        if (effectiveSys.isNotBlank()) turns.add(OpenAiClient.ChatTurn("system", effectiveSys))
        for (i in 0..endIdx.coerceAtMost(c.messages.size - 1)) {
            val m = c.messages[i]
            if (!m.isError) {
                if (m.toolCalls.isNotEmpty()) {
                    // 历史中的 assistant 工具调用与其结果一并回放
                    val calls = m.toolCalls.map { OpenAiClient.ToolCallData(it.id, it.name, it.arguments) }
                    turns.add(OpenAiClient.ChatTurn("assistant", m.content, toolCalls = calls))
                    m.toolCalls.forEach { tc ->
                        turns.add(OpenAiClient.ChatTurn("tool", tc.result, toolCallId = tc.id))
                    }
                } else {
                    // 结构化附件 → 多模态输入：图片走 data URL，文档文本内联。
                    val parts = m.attachments.mapNotNull { a ->
                        when {
                            a.type == "image" && a.base64 != null ->
                                OpenAiClient.InputPart("image", "data:${a.mime.ifBlank { "image/jpeg" }};base64,${a.base64}", a.name)
                            (a.type == "file" || a.type == "text") && !a.text.isNullOrBlank() ->
                                OpenAiClient.InputPart("file", a.text!!, a.name)
                            else -> null
                        }
                    }
                    turns.add(OpenAiClient.ChatTurn(m.role, m.content, m.images, parts = parts))
                }
            }
        }
        // 图片/附件只保留最近两轮，避免上下文爆炸
        val cutoff = turns.size - 2
        val trimmed = turns.mapIndexed { i, t ->
            if (i < cutoff) t.copy(images = emptyList(), parts = emptyList()) else t
        }
        // 上下文预算裁剪：估算 token 超过 contextWindow 时从最旧的消息裁起，system 保留
        fun estimate(t: OpenAiClient.ChatTurn): Int =
            t.text.length / 2 + t.images.size * 800 + t.parts.sumOf { it.data.length / 2 + 800 }
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

    /** 首轮回复成功后用 LLM 自动起标题（ChatGPT/Open WebUI 惯例）；失败静默保留占位标题。 */
    private fun autoTitle(c: Conversation) {
        val firstUser = c.messages.firstOrNull { it.role == "user" } ?: return
        val firstAi = c.messages.firstOrNull { it.role == "assistant" && !it.isError } ?: return
        val cfg = config
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prompt = "根据下面这段对话，起一个不超过12个字的中文标题。只输出标题本身，不要引号、句号或任何解释。\n" +
                "用户：${firstUser.content.take(300)}\n助手：${firstAi.content.take(300)}"
            client.complete(cfg, prompt, maxTokens = 32).onSuccess { t ->
                val title = t.trim().trim('"', '「', '」', '"', '"').replace("\n", " ").take(16)
                if (title.isNotBlank()) {
                    c.title = title.replace("\n", " ").trim()
                    c.updated = System.currentTimeMillis()
                    persist()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { current = null; current = c }
                }
            }
        }
    }

    private fun request(c: Conversation, history: List<OpenAiClient.ChatTurn>, allowFailover: Boolean = true) {
        val now = System.currentTimeMillis()
        // 占位标题判定：仍是首轮占位（首条用户消息前 18 字或「新对话」）才允许自动起标题覆盖
        val placeholderTitle = c.messages.firstOrNull()?.content?.take(18).orEmpty()
        val canAutoTitle = c.title == "新对话" || c.title == placeholderTitle || c.title.startsWith("新对话 · ")
        val ai = Message(com.chengyan.app.data.IdGen.next(), "assistant", "", now, model = config.model, streaming = true)
        c.messages.add(ai)
        streaming = true
        persist()
        refreshUi(c, force = true)

        streamJob = viewModelScope.launch {
            try {
                var turns = history
                var iter = 0
                var cancelled = false
                while (true) {
                    iter++
                    val contentBuf = StringBuilder()
                    val thinkingStart = System.currentTimeMillis()
                    val pendingCalls = LinkedHashMap<Int, OpenAiClient.ToolCallData>()
                    client.stream(config, turns).collect { ev ->
                        when (ev) {
                            is OpenAiClient.StreamEvent.Delta -> {
                                contentBuf.append(ev.text)
                                ai.content = contentBuf.toString()
                                refreshUi(c)
                            }
                            is OpenAiClient.StreamEvent.ThinkingDelta -> {
                                if (ai.thinking.isEmpty()) ai.thinkingMs = 0
                                ai.thinking += ev.text
                                ai.thinkingMs = System.currentTimeMillis() - thinkingStart
                                refreshUi(c)
                            }
                            is OpenAiClient.StreamEvent.ToolCallDelta -> {
                                val tc = pendingCalls.getOrPut(ev.index) {
                                    OpenAiClient.ToolCallData(ev.id ?: "call_${iter}_${ev.index}", ev.name ?: "", "")
                                }
                                if (!ev.id.isNullOrBlank()) tc.id = ev.id
                                if (!ev.name.isNullOrBlank()) tc.name = ev.name
                                tc.result += ev.argsFragment   // 先把参数碎片暂存在 result 字段
                                refreshUi(c)
                            }
                            is OpenAiClient.StreamEvent.Failure -> {
                                ai.isError = true
                                ai.content = if (ai.content.isBlank()) "请求失败：${ev.message}" else ai.content
                                markProfile(config.url, ok = false)
                            }
                            OpenAiClient.StreamEvent.Done -> {}
                        }
                    }
                    if (!streaming) { cancelled = true } // stop() 取消时 streaming 仍为 true，见 finally；此处以 Job 取消为准

                    // 模型没有发起工具调用 → 最终回答完成
                    // iter 是「模型往返轮数」不是工具个数：一轮里可含多个 tool_call。
                    // 9 个工具可能需要多轮往返编排，上限放宽到 16，避免长任务被提前截断。
                    if (pendingCalls.isEmpty() || iter >= 16) {
                        ai.content = contentBuf.toString().ifBlank { ai.content.ifBlank { "（模型返回了空回复）" } }
                        break
                    }

                    // 🔴 工具调用节流：模型可能一轮发起十几个调用。
                    // 全部执行完再统一刷新 = 「一下子全蹦出来」；且密集同步执行会让主线程卡。
                    // 改为逐个执行 + 间隔 + 每完成一个立即刷新 UI，用户能看到工具一个个跑完。
                    // 注意：本轮所有调用必须全部执行 —— OpenAI function calling 协议要求
                    // assistant.tool_calls 与后续 tool 结果一一对应，漏一个 API 会报错。
                    val allCalls = pendingCalls.values.toList()
                    val executed = mutableListOf<com.chengyan.app.data.ToolCallInfo>()
                    for ((idx, tc) in allCalls.withIndex()) {
                        if (!streaming) break   // 用户点停止：中断后续调用（此时消息会标记已停止）
                        val args = tc.result
                        val outcome = if (tc.name.startsWith("phone_"))
                            com.chengyan.app.net.PhoneTools.execute(appContext, tc.name, args)
                        else
                            com.chengyan.app.net.ToolOutcome(com.chengyan.app.net.Tools.execute(tc.name, args))
                        // 手机工具带回的图片（相机/相册）附加到 AI 消息：用户可见，多模态模型下一轮也能看到
                        outcome.imageBase64?.let { b64 -> if (ai.images.size < 4) ai.images = ai.images + b64 }
                        val info = com.chengyan.app.data.ToolCallInfo(tc.id, tc.name, args, outcome.result)
                        executed.add(info)
                        // 每完成一个就刷新：工具卡片逐个出现，而不是末尾齐刷
                        ai.toolCalls.add(info)
                        ai.content = contentBuf.toString()
                        refreshUi(c, force = true)
                        if (idx < allCalls.lastIndex) kotlinx.coroutines.delay(TOOL_GAP_MS)
                    }
                    ai.content = contentBuf.toString()
                    refreshUi(c, force = true)

                    // 回传本轮全部结果继续生成（tool_calls 与 tool 结果严格一一对应）
                    turns = turns + OpenAiClient.ChatTurn("assistant", contentBuf.toString(), toolCalls =
                        executed.map { OpenAiClient.ToolCallData(it.id, it.name, it.arguments) }) +
                        executed.map { OpenAiClient.ChatTurn("tool", it.result, toolCallId = it.id) }
                }
                if (cancelled && ai.content.isBlank()) ai.content = "（已停止生成）"
            } catch (_: kotlinx.coroutines.CancellationException) {
                if (ai.content.isBlank() && ai.toolCalls.isEmpty()) ai.content = "（已停止生成）"
            } finally {
                ai.streaming = false
                streaming = false
                c.updated = System.currentTimeMillis()
                persist()
                refreshUi(c, force = true)
                // 自动故障转移（QuroMultiProviderRepository.getNextProvider）：本次失败且未产出内容 → 切到下一个健康提供商并重试一次
                if (allowFailover && ai.isError && ai.content.startsWith("请求失败") && ai.toolCalls.isEmpty()) {
                    val candidate = failoverCandidate(config.url)
                    if (candidate != null) {
                        c.messages.remove(ai)
                        val prev = config
                        saveQuroConfig(quroConfig.copy(
                            provider = candidate.providerType.name,
                            baseUrl = candidate.baseUrl,
                            apiKey = candidate.apiKey,
                            model = candidate.defaultModel.ifBlank { prev.model },
                        ))
                        _toast.value = "「${prev.model}」请求失败，已自动切换到「${candidate.name}」重试"
                        request(c, history, allowFailover = false)
                    }
                } else if (!ai.isError && ai.content.isNotBlank()) {
                    markProfile(config.url, ok = true)
                    if (canAutoTitle) autoTitle(c)
                }
            }
        }
    }

    private fun persist() {
        // 🔴 快照必须包含 current：newConversation 只改 current 不进列表，
        //    旧实现存的是不含当前对话的列表 → 重启后记录全部丢失
        val snapshot = (conversations + listOfNotNull(current)).distinctBy { it.id }
        viewModelScope.launch { chats.save(snapshot) }
        // 同步回内存列表，保证抽屉/搜索立即可见
        conversations = snapshot
    }
}
