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
    private val settings: SettingsStore,
    private val chats: ChatStore,
) : ViewModel() {

    private val client = OpenAiClient()

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

    /** 健康记录：成功/失败后更新对应档案（移植自 ZorvAI QuroMultiProviderRepository 健康检查）。 */
    private fun markProfile(url: String, ok: Boolean) {
        var changed = false
        profiles = profiles.map { p ->
            if (p.url == url) {
                changed = true
                if (ok) p.copy(health = "HEALTHY", consecutiveFailures = 0)
                else p.copy(health = "FAILED", consecutiveFailures = p.consecutiveFailures + 1)
            } else p
        }
        if (changed) viewModelScope.launch { settings.saveProfiles(profiles) }
    }

    /** 故障转移候选：按优先级找下一个健康档案（FAST_FAIL 策略，移植自 ZorvAI FailoverConfig）。 */
    private fun failoverCandidate(failedUrl: String): ApiProfile? =
        profiles.filter { it.url != failedUrl && it.key.isNotBlank() && it.model.isNotBlank() }
            .sortedBy { it.priority }.firstOrNull { it.health != "FAILED" || it.consecutiveFailures < 3 }

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
            if (!m.isError) {
                if (m.toolCalls.isNotEmpty()) {
                    // 历史中的 assistant 工具调用与其结果一并回放
                    val calls = m.toolCalls.map { OpenAiClient.ToolCallData(it.id, it.name, it.arguments) }
                    turns.add(OpenAiClient.ChatTurn("assistant", m.content, toolCalls = calls))
                    m.toolCalls.forEach { tc ->
                        turns.add(OpenAiClient.ChatTurn("tool", tc.result, toolCallId = tc.id))
                    }
                } else {
                    turns.add(OpenAiClient.ChatTurn(m.role, m.content, m.images))
                }
            }
        }
        // 图片只保留最近两轮，避免上下文爆炸
        val cutoff = turns.size - 2
        val trimmed = turns.mapIndexed { i, t -> if (i < cutoff) t.copy(images = emptyList()) else t }
        // 上下文预算裁剪（移植自 ZorvAI 上下文压缩思路）：估算 token 超过 contextWindow 时从最旧的消息裁起，system 保留
        fun estimate(t: OpenAiClient.ChatTurn): Int = t.text.length / 2 + t.images.size * 800
        var budget = (config.contextWindow * 0.85).toInt()
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
                                current = null; current = c
                            }
                            is OpenAiClient.StreamEvent.ThinkingDelta -> {
                                if (ai.thinking.isEmpty()) ai.thinkingMs = 0
                                ai.thinking += ev.text
                                ai.thinkingMs = System.currentTimeMillis() - thinkingStart
                                current = null; current = c
                            }
                            is OpenAiClient.StreamEvent.ToolCallDelta -> {
                                val tc = pendingCalls.getOrPut(ev.index) {
                                    OpenAiClient.ToolCallData(ev.id ?: "call_${iter}_${ev.index}", ev.name ?: "", "")
                                }
                                if (!ev.id.isNullOrBlank()) tc.id = ev.id
                                if (!ev.name.isNullOrBlank()) tc.name = ev.name
                                tc.result += ev.argsFragment   // 先把参数碎片暂存在 result 字段
                                current = null; current = c
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
                    if (pendingCalls.isEmpty() || iter >= 6) {
                        ai.content = contentBuf.toString().ifBlank { ai.content.ifBlank { "（模型返回了空回复）" } }
                        break
                    }

                    // 执行每个工具调用（参数已在 result 暂存字段中拼好）
                    val executed = pendingCalls.values.map { tc ->
                        val args = tc.result
                        com.chengyan.app.data.ToolCallInfo(tc.id, tc.name, args, com.chengyan.app.net.Tools.execute(tc.name, args))
                    }
                    ai.toolCalls.addAll(executed)
                    ai.content = contentBuf.toString()
                    current = null; current = c

                    // 回传结果继续生成
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
                current = null; current = c
                // 自动故障转移：本次失败且未产出内容 → 切到下一个健康档案并重试一次
                if (allowFailover && ai.isError && ai.content.startsWith("请求失败") && ai.toolCalls.isEmpty()) {
                    val candidate = failoverCandidate(config.url)
                    if (candidate != null) {
                        c.messages.remove(ai)
                        val prev = config
                        saveConfig(ModelConfig(
                            url = candidate.url, key = candidate.key, model = candidate.model,
                            sysPrompt = prev.sysPrompt, temperature = prev.temperature,
                            thinking = prev.thinking, providerType = candidate.providerType,
                            maxTokens = prev.maxTokens, enableTools = prev.enableTools,
                            contextWindow = prev.contextWindow
                        ))
                        _toast.value = "「${prev.model}」请求失败，已自动切换到「${candidate.name}」重试"
                        request(c, history, allowFailover = false)
                    }
                } else if (!ai.isError && ai.content.isNotBlank()) {
                    markProfile(config.url, ok = true)
                }
            }
        }
    }

    private fun persist() {
        val snapshot = conversations.toList()
        viewModelScope.launch { chats.save(snapshot) }
    }
}
