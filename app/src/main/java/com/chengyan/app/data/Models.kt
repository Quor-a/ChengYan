package com.chengyan.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ModelConfig(
    val url: String = "",
    val key: String = "",
    val model: String = "",
    val sysPrompt: String = "",
    val temperature: Float = 0.7f,
    val thinking: String = "default"   // off | default | low | high
) {
    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank() && model.isNotBlank()
}

/** 一次工具调用（用于 UI 展示与持久化）。 */
@Serializable
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String,
    var result: String = ""
)

/** 多套供应商档案，可保存并一键切换。 */
@Serializable
data class ApiProfile(
    val id: Long,
    val name: String,
    val url: String,
    val key: String,
    val model: String
)

/** 助手/角色预设。 */
@Serializable
data class AssistantPreset(
    val id: Long,
    val name: String,
    val icon: String,   // 单字图标
    val prompt: String
)

val DEFAULT_ASSISTANTS = listOf(
    AssistantPreset(1, "通用助手", "言", "你是一位简洁专业的中文助手，先给结论再给理由。"),
    AssistantPreset(2, "翻译官", "译", "你是专业翻译。用户输入中文则译成英文，输入其他语言则译成中文。只输出译文，不要解释。"),
    AssistantPreset(3, "程序员", "码", "你是资深软件工程师。回答代码问题时给出可运行的完整代码与简要说明，指出常见坑。"),
    AssistantPreset(4, "写作润色", "文", "你是文字编辑。把用户提供的文字润色得更自然流畅，保持原意，可适度提升文采。"),
    AssistantPreset(5, "头脑风暴", "想", "你是创意顾问。针对用户的问题给出 5 个以上差异化想法，每个附一句理由。"),
)

@Serializable
data class Message(
    val id: Long,
    val role: String,            // "user" | "assistant"
    var content: String,
    val time: Long,
    val model: String? = null,
    var isError: Boolean = false,
    var streaming: Boolean = false,
    var images: List<String> = emptyList(),   // base64（视觉输入）
    var thinking: String = "",                // 模型思考过程
    var thinkingMs: Long = 0,                 // 思考耗时
    var toolCalls: MutableList<ToolCallInfo> = mutableListOf()  // 本轮的工具调用记录
)

@Serializable
data class Conversation(
    val id: Long,
    var title: String,
    var created: Long,
    var updated: Long = created,
    var assistantName: String? = null,
    val messages: MutableList<Message> = mutableListOf()
)

// 服务商预设（接口均为 OpenAI 兼容格式）
data class ProviderPreset(val name: String, val url: String, val model: String)

val PRESETS = listOf(
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ProviderPreset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
    ProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    ProviderPreset("Ollama 本地", "http://10.0.2.2:11434/v1", "qwen2.5:7b"),
)

data class Suggestion(val title: String, val desc: String, val prefix: String)

val SUGGESTIONS = listOf(
    Suggestion("润色一段文字", "把一段生硬的草稿改得自然流畅", "帮我把下面这段话润色得更自然流畅："),
    Suggestion("解释一个概念", "用通俗的比喻讲清楚", "用通俗易懂的语言和一个生活化的比喻，解释一下什么是："),
    Suggestion("写一封邮件", "告诉我目的，我来起草", "请帮我写一封邮件，目的是："),
    Suggestion("调试一段代码", "贴上代码，我来找问题", "请帮我检查这段代码的问题：\n```\n\n```"),
)
