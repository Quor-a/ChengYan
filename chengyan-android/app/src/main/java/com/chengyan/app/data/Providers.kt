package com.chengyan.app.data

/**
 * 云模型厂商注册表 —— 移植自 ZorvAI（Quor-a/ZorvAI）的 ApiProviderType / ApiProviderConfigCollect，
 * 取 OpenAI 兼容协议子集，配置数据结构与端点表保持一致。
 */

enum class ApiProviderType(val label: String) {
    OPENAI("OpenAI"),
    OPENAI_GENERIC("OpenAI 通用（自定义端点）"),
    DEEPSEEK("DeepSeek"),
    SILICONFLOW("硅基流动"),
    ALIYUN("通义千问（阿里云）"),
    ZHIPU("智谱 GLM"),
    MOONSHOT("月之暗面 Kimi"),
    BAICHUAN("百川"),
    MIMO("小米 MiMo"),
    MISTRAL("Mistral"),
    OPENROUTER("OpenRouter"),
    NOUS_PORTAL("Nous Portal"),
    INFINIAI("无问芯穹"),
    DOUBAO("豆包（火山）"),
    NVIDIA("NVIDIA NIM"),
    IFLOW("iFlow"),
    FOUR_ROUTER("4Router"),
    NOVITA("Novita AI"),
    PPINFRA("派欧云"),
    LMSTUDIO("LM Studio（本地）"),
    OLLAMA("Ollama（本地）"),
    OPENAI_LOCAL("OpenAI 兼容（本地）"),
    OTHER("其他（自定义）");

    companion object {
        /** 根据 URL 自动识别服务商 —— 移植自 ZorvAI detectProviderFromUrl。 */
        fun detectFromUrl(url: String): ApiProviderType {
            val u = url.lowercase()
            return when {
                u.contains("api.openai.com") -> OPENAI
                u.contains("api.deepseek.com") -> DEEPSEEK
                u.contains("api.siliconflow.cn") -> SILICONFLOW
                u.contains("dashscope.aliyuncs.com") -> ALIYUN
                u.contains("open.bigmodel.cn") || u.contains("api.z.ai") -> ZHIPU
                u.contains("api.moonshot.cn") || u.contains("api.moonshot.ai") || u.contains("api.kimi.com") -> MOONSHOT
                u.contains("api.baichuan-ai.com") -> BAICHUAN
                u.contains("api.xiaomimimo.com") -> MIMO
                u.contains("codestral.mistral.ai") -> MISTRAL
                u.contains("openrouter.ai") -> OPENROUTER
                u.contains("4router.net") -> FOUR_ROUTER
                u.contains("inference-api.nousresearch.com") -> NOUS_PORTAL
                u.contains("cloud.infini-ai.com") -> INFINIAI
                u.contains("ark.cn-beijing.volces.com") -> DOUBAO
                u.contains("integrate.api.nvidia.com") -> NVIDIA
                u.contains("apis.iflow.cn") -> IFLOW
                u.contains("api.novita.ai") -> NOVITA
                u.contains("api.ppinfra.com") -> PPINFRA
                u.contains("localhost:1234") || u.contains("127.0.0.1:1234") -> LMSTUDIO
                u.contains("localhost:11434") || u.contains("127.0.0.1:11434") || u.contains(":11434") -> OLLAMA
                u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2") -> OPENAI_LOCAL
                else -> OTHER
            }
        }
    }
}

/** 单个服务商的内置默认配置 —— 移植自 ZorvAI ProviderApiConfig。 */
data class ProviderApiConfig(
    val type: ApiProviderType,
    val defaultModel: String = "",
    val endpoint: String = "",
    val requiresApiKey: Boolean = true,
)

/** 各服务商默认端点/模型表 —— 端点数据与 ZorvAI ApiProviderConfigs 一致。 */
val PROVIDER_REGISTRY: List<ProviderApiConfig> = listOf(
    ProviderApiConfig(ApiProviderType.OPENAI, "gpt-4o", "https://api.openai.com/v1"),
    ProviderApiConfig(ApiProviderType.DEEPSEEK, "deepseek-chat", "https://api.deepseek.com/v1"),
    ProviderApiConfig(ApiProviderType.SILICONFLOW, "deepseek-ai/DeepSeek-V3", "https://api.siliconflow.cn/v1"),
    ProviderApiConfig(ApiProviderType.ALIYUN, "qwen-max", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ProviderApiConfig(ApiProviderType.ZHIPU, "glm-4.5", "https://open.bigmodel.cn/api/paas/v4"),
    ProviderApiConfig(ApiProviderType.MOONSHOT, "moonshot-v1-128k", "https://api.moonshot.cn/v1"),
    ProviderApiConfig(ApiProviderType.BAICHUAN, "baichuan4", "https://api.baichuan-ai.com/v1"),
    ProviderApiConfig(ApiProviderType.MIMO, "mimo-v2.5-pro", "https://api.xiaomimimo.com/v1"),
    ProviderApiConfig(ApiProviderType.MISTRAL, "codestral-latest", "https://codestral.mistral.ai/v1"),
    ProviderApiConfig(ApiProviderType.OPENROUTER, "google/gemini-pro", "https://openrouter.ai/api/v1"),
    ProviderApiConfig(ApiProviderType.NOUS_PORTAL, "", "https://inference-api.nousresearch.com/v1"),
    ProviderApiConfig(ApiProviderType.INFINIAI, "infini-mini", "https://cloud.infini-ai.com/maas/v1"),
    ProviderApiConfig(ApiProviderType.DOUBAO, "Doubao-pro-4k", "https://ark.cn-beijing.volces.com/api/v3"),
    ProviderApiConfig(ApiProviderType.NVIDIA, "nvidia/nemotron-3-nano-30b-a3b", "https://integrate.api.nvidia.com/v1"),
    ProviderApiConfig(ApiProviderType.IFLOW, "TBStars2-200B-A13B", "https://apis.iflow.cn/v1"),
    ProviderApiConfig(ApiProviderType.FOUR_ROUTER, "gpt-5.4-mini", "https://4router.net/v1"),
    ProviderApiConfig(ApiProviderType.NOVITA, "", "https://api.novita.ai/v3/openai"),
    ProviderApiConfig(ApiProviderType.PPINFRA, "", "https://api.ppinfra.com/v3/openai"),
    ProviderApiConfig(ApiProviderType.LMSTUDIO, "", "http://10.0.2.2:1234/v1", requiresApiKey = false),
    ProviderApiConfig(ApiProviderType.OLLAMA, "", "http://10.0.2.2:11434/v1", requiresApiKey = false),
    ProviderApiConfig(ApiProviderType.OPENAI_LOCAL, "", "http://10.0.2.2:8000/v1", requiresApiKey = false),
    ProviderApiConfig(ApiProviderType.OPENAI_GENERIC, "", ""),
    ProviderApiConfig(ApiProviderType.OTHER, "", ""),
)

fun registryOf(type: ApiProviderType): ProviderApiConfig =
    PROVIDER_REGISTRY.firstOrNull { it.type == type } ?: ProviderApiConfig(type)
