package com.chengyan.app.core.model

/*
 * 完整移植自 Quor-a/ZorvAI
 * app/src/main/java/com/ai/assistance/quro/core/model/QuroFeatureModelConfig.kt
 * 仅改包名；原版使用 @StringRes 引用 ZorvAI 的字符串资源，澄言无对应资源，
 * 改为等价中文文案内联（其余逻辑逐行保留）。
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 功能级模型绑定配置（原创，参考 FunctionalConfigManager 设计、移植）。
 *
 * 设计要点（与对齐）：
 * - 每个 [QuroFunctionType] 可「跟随主模型」(useGlobal=true) 或指定独立模型 (useGlobal=false, model=具体模型名)。
 * - Zorv AI 当前为单接入点架构（全局只有一个 baseUrl/apiKey/provider），因此「功能级配置」在
 *   语义上等价于 FunctionType→(configId, modelIndex)：configId 退化为全局主配置，
 *   modelIndex 退化为 model 字符串覆写。该约束下这是与接口规范一致的忠实实现。
 * - 引擎消费入口统一为 [QuroFunctionModelConfigRepository.resolveConfig]：跟随主模型时原样返回
 *   全局 QuroModelConfig，否则返回替换了 model 字段的副本。主对话 (CHAT) 恒用主模型，行为不变。
 */
enum class QuroFunctionType(val label: String, val desc: String) {
    CHAT("主对话", "日常对话使用的主模型"),
    SUMMARY("总结", "对话/文章总结使用的模型"),
    MEMORY("记忆", "记忆提取与整理使用的模型"),
    UI_CONTROL("界面控制", "理解并执行界面指令的模型"),
    TRANSLATION("翻译", "翻译功能使用的模型"),
    GREP("检索", "内容检索使用的模型"),
    PERSONA_INCUBATE("人格孵化", "人格养成使用的模型"),
    IMAGE_RECOGNITION("图像识别", "图片理解使用的模型"),
    AUDIO_RECOGNITION("语音识别", "语音转写使用的模型"),
    VIDEO_RECOGNITION("视频识别", "视频理解使用的模型"),
    VIDEO_CALL("视频通话", "视频通话实时对话使用的模型"),
    IMAGE_GEN("图像生成", "图片生成使用的模型"),
    VIDEO_GEN("视频生成", "视频生成使用的模型"),
}

data class QuroFunctionModelBinding(
    val useGlobal: Boolean = true, // true=跟随主模型；false=使用下方独立模型
    val model: String = "",        // 独立模型名（useGlobal=false 时有效）
    val baseUrl: String = "",      // 独立基础URL（useGlobal=false 时有效）
    val apiKey: String = "",       // 独立API密钥（useGlobal=false 时有效）
)

class QuroFunctionModelConfigRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_function_model_config", Context.MODE_PRIVATE)

    /** 读取全部功能的绑定（缺省即「跟随主模型」） */
    fun load(): Map<QuroFunctionType, QuroFunctionModelBinding> =
        QuroFunctionType.values().associateWith { key ->
            QuroFunctionModelBinding(
                useGlobal = prefs.getBoolean("${key.name}_use_global", true),
                model = prefs.getString("${key.name}_model", "") ?: "",
                baseUrl = prefs.getString("${key.name}_base_url", "") ?: "",
                apiKey = prefs.getString("${key.name}_api_key", "") ?: "",
            )
        }

    fun save(map: Map<QuroFunctionType, QuroFunctionModelBinding>) = prefs.edit {
        map.forEach { (k, b) ->
            putBoolean("${k.name}_use_global", b.useGlobal)
            putString("${k.name}_model", b.model)
            putString("${k.name}_base_url", b.baseUrl)
            putString("${k.name}_api_key", b.apiKey)
        }
    }

    fun getBinding(type: QuroFunctionType): QuroFunctionModelBinding =
        QuroFunctionModelBinding(
            useGlobal = prefs.getBoolean("${type.name}_use_global", true),
            model = prefs.getString("${type.name}_model", "") ?: "",
            baseUrl = prefs.getString("${type.name}_base_url", "") ?: "",
            apiKey = prefs.getString("${type.name}_api_key", "") ?: "",
        )

    fun setBinding(type: QuroFunctionType, binding: QuroFunctionModelBinding) = prefs.edit {
        putBoolean("${type.name}_use_global", binding.useGlobal)
        putString("${type.name}_model", binding.model)
        putString("${type.name}_base_url", binding.baseUrl)
        putString("${type.name}_api_key", binding.apiKey)
    }

    fun resetAll() = prefs.edit {
        QuroFunctionType.values().forEach { k ->
            putBoolean("${k.name}_use_global", true)
            putString("${k.name}_model", "")
            putString("${k.name}_base_url", "")
            putString("${k.name}_api_key", "")
        }
    }

    /**
     * 解析某功能最终使用的配置：
     * - 跟随主模型 / 未指定模型 → 原样返回全局配置（不影响现有行为）；
     * - 指定了独立模型 → 返回替换了 model 字段的全局配置副本。
     *
     * 调用方（如 QuroAssistant.ask）据此决定实际下发给 API 的模型名，
     * 从而在不引入多接入点体系的前提下实现「按功能路由模型」。
     */
    fun resolveConfig(type: QuroFunctionType, global: QuroModelConfig): QuroModelConfig {
        val b = getBinding(type)
        return if (b.useGlobal || (b.model.isBlank() && b.baseUrl.isBlank() && b.apiKey.isBlank())) {
            global
        } else {
            global.copy(
                model = if (b.model.isNotBlank()) b.model else global.model,
                baseUrl = if (b.baseUrl.isNotBlank()) b.baseUrl else global.baseUrl,
                apiKey = if (b.apiKey.isNotBlank()) b.apiKey else global.apiKey,
            )
        }
    }
}
