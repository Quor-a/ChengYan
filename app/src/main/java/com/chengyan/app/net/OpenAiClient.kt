package com.chengyan.app.net

import com.chengyan.app.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** OpenAI 兼容接口的流式客户端（SSE），支持思考模型与工具调用循环。 */
class OpenAiClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    sealed interface StreamEvent {
        data class Delta(val text: String) : StreamEvent
        data class ThinkingDelta(val text: String) : StreamEvent
        /** 模型发起的工具调用增量（index, id, name, 参数片段）。 */
        data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argsFragment: String) : StreamEvent
        data class Failure(val message: String) : StreamEvent
        data object Done : StreamEvent
    }

    /** 一次工具调用（本地执行后回传给模型）。 */
    data class ToolCallData(
        var id: String,
        var name: String,
        val arguments: String,
        var result: String = ""
    )

    /**
     * 多模态输入：给模型的一张图片或一段文档文本。
     * type="image" → image_url(data URL)；type="file" → 内联文本块。
     */
    data class InputPart(val type: String, val data: String, val name: String = "")

    /** 一轮对话内容。role 可为 system/user/assistant/tool。 */
    data class ChatTurn(
        val role: String,
        val text: String,
        val images: List<String> = emptyList(),
        /** 结构化多模态输入（图片/文档），与 images 合并发送。 */
        val parts: List<InputPart> = emptyList(),
        val toolCalls: List<ToolCallData> = emptyList(),   // assistant 发起的调用
        val toolCallId: String = "",                        // role=tool 时对应的调用 id
    )

    fun stream(
        config: ModelConfig,
        history: List<ChatTurn>,
        context: android.content.Context? = null,
        enabledTools: Set<String>? = null, // 对话内定向选择（非空时 tools 只下发这些）
    ): Flow<StreamEvent> = callbackFlow {
        // 🔴 丢字修复见下方 onEvent：trySend 改 trySendBlocking（默认 channel 容量 64，
        //    消费慢时 trySend 静默失败 → 长回复掉 token）
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("temperature", config.temperature.toDouble())
            // 思考开关/强度：off → 关闭思考（Qwen/硅基流动系）；low/high → reasoning_effort（OpenAI o 系/兼容实现）
            when (config.thinking) {
                "off" -> put("enable_thinking", false)
                "low" -> put("reasoning_effort", "low")
                "high" -> put("reasoning_effort", "high")
            }
            // 内置工具定义（可在设置中关闭 —— 对齐 ZorvAI QuroModelConfig.enableTools）
            if (config.enableTools) put("tools", kotlinx.serialization.json.buildJsonArray {
                val all = buildList {
                    Tools.definitionsJson().forEach { add(it) }
                    PhoneTools.definitionsJson().forEach { add(it) }
                    McpManager.definitionsJson(context ?: return@buildJsonArray).forEach { add(it) }
                    ConnectorManager.definitionsJson(context ?: return@buildJsonArray).forEach { add(it) }
                }
                // 定向选择：用户在对话框勾选了工具 → 只暴露这些（对标 LibreChat agent / LobeChat 工具选择器）
                val allowed = enabledTools
                all.forEach { d ->
                    if (allowed == null) add(d)
                    else {
                        val n = (d as? kotlinx.serialization.json.JsonObject)
                            ?.get("function")?.let { f -> (f as? kotlinx.serialization.json.JsonObject)?.get("name") }
                            ?.let { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        if (n != null && n in allowed) add(d)
                    }
                }
            })
            // 输出 token 上限（>0 时下发，本地/部分兼容端点可设为 0 关闭）
            if (config.maxTokens > 0) put("max_tokens", config.maxTokens)
            put("messages", turnsJson(history))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(config.url.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.key}")
            .post(body)
            .build()

        var es: EventSource? = null
        es = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { trySendBlocking(StreamEvent.Done); close(); return }
                runCatching {
                    val obj = json.parseToJsonElement(data) as? kotlinx.serialization.json.JsonObject ?: return
                    val delta = obj["choices"]?.let { c ->
                        (c as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                    }?.let { ch -> (ch as? kotlinx.serialization.json.JsonObject)?.get("delta") }
                        ?.let { d -> (d as? kotlinx.serialization.json.JsonObject) } ?: return

                    // 思考内容：reasoning_content（DeepSeek/Qwen/硅基流动）或 reasoning
                    val thinking = (delta["reasoning_content"] ?: delta["reasoning"])?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    if (!thinking.isNullOrEmpty()) trySendBlocking(StreamEvent.ThinkingDelta(thinking))

                    // 正文
                    val content = delta["content"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    if (!content.isNullOrEmpty()) trySendBlocking(StreamEvent.Delta(content))

                    // 工具调用增量
                    (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                        val tc = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val idx = (tc["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        val fn = tc["function"] as? kotlinx.serialization.json.JsonObject
                        trySendBlocking(StreamEvent.ToolCallDelta(
                            index = idx,
                            id = (tc["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                            name = fn?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                            argsFragment = fn?.get("arguments")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                        ))
                    }
                }
            }
            override fun onFailure(es: EventSource, t: Throwable?, resp: okhttp3.Response?) {
                val msg = when {
                    resp != null && resp.code == 401 -> "认证失败（HTTP 401）：API Key 不正确"
                    resp != null && resp.code == 404 -> "接口不存在（HTTP 404）：请检查 Base URL 是否以 /v1 结尾"
                    resp != null -> "请求失败（HTTP ${resp.code}）"
                    t?.message?.contains("cleartext") == true -> "明文 HTTP 被系统拦截：本地服务请使用 10.0.2.2（模拟器）或配置网络安全策略"
                    else -> t?.message ?: "未知网络错误"
                }
                trySendBlocking(StreamEvent.Failure(msg))
                close()
            }
            override fun onClosed(es: EventSource) {
                trySendBlocking(StreamEvent.Done); close()
            }
        })
        awaitClose { es?.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun turnsJson(history: List<ChatTurn>): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.buildJsonArray {
            history.forEach { turn ->
                when {
                    turn.role == "tool" -> add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", turn.toolCallId)
                        put("content", turn.text)
                    })
                    turn.toolCalls.isNotEmpty() -> add(buildJsonObject {
                        put("role", "assistant")
                        if (turn.text.isNotBlank()) put("content", turn.text)
                        put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                            turn.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments.ifBlank { "{}" })
                                    })
                                })
                            }
                        })
                    })
                    turn.images.isEmpty() && turn.parts.isEmpty() -> add(buildJsonObject {
                        put("role", turn.role); put("content", turn.text)
                    })
                    else -> add(buildJsonObject {
                        put("role", turn.role)
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", turn.text) })
                            turn.images.forEach { b64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b64") })
                                })
                            }
                            // 结构化附件：图片走 data URL，文档走内联文本块。
                            turn.parts.forEach { part ->
                                when (part.type) {
                                    "image" -> add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", part.data) })
                                    })
                                    "file", "text" -> add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "[附件：${part.name}]\n${part.data}")
                                    })
                                }
                            }
                        })
                    })
                }
            }
        }

    /** 模型信息 —— 移植自 ZorvAI QuroModelInfo：id + 服务端返回的真实上下文长度（0=未知）。 */
    data class ModelInfo(val id: String, val contextLength: Int = 0)

    /**
     * 获取服务端可用模型列表 —— 移植自 ZorvAI QuroModelListFetcher：
     * OpenAI 兼容端点用 GET /models；Ollama 自动回退 /api/tags；本地回环无 key 时不带鉴权头。
     */
    suspend fun listModels(config: ModelConfig): Result<List<ModelInfo>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val base = config.url.trim().trimEnd('/')
                val likelyOllama = base.lowercase().contains(":11434") || base.lowercase().contains("ollama")
                val url = if (likelyOllama) "$base/api/tags" else "$base/models"

                fun req(u: String): Request {
                    val b = Request.Builder().url(u).header("Content-Type", "application/json")
                    if (config.key.isNotBlank()) b.header("Authorization", "Bearer ${config.key}")
                    return b.get().build()
                }
                val resp = client.newCall(req(url)).execute()
                // 🔴 use{}：响应体必须关闭，否则连接池泄漏
                var text = ""
                resp.use { r ->
                    text = r.body?.string().orEmpty()
                    if (!r.isSuccessful && !likelyOllama && (base.contains("localhost") || base.contains("127.0.0.1"))) {
                        // 回环地址回退 Ollama 格式（🔴 原条件 &&/|| 优先级错误，非回环失败也走此分支）
                        client.newCall(req("$base/api/tags")).execute().use { r2 ->
                            if (r2.isSuccessful) text = r2.body?.string().orEmpty() else throw IllegalStateException("HTTP ${r.code}: ${text.take(200)}")
                        }
                    } else if (!r.isSuccessful) {
                        throw IllegalStateException("HTTP ${r.code}: ${text.take(200)}")
                    }
                } // resp.use

                if (text.contains("\"models\"") && !text.contains("\"object\":\"list\"")) {
                    // Ollama /api/tags：{"models":[{"name":"..","...}]}
                    val obj = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                    val arr = obj["models"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
                    arr.mapNotNull { el ->
                        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                        ModelInfo(id, (o["context_length"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                    }.sortedBy { it.id }
                } else {
                    val obj = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                    val arr = obj["data"] as? kotlinx.serialization.json.JsonArray ?: emptyList<kotlinx.serialization.json.JsonElement>()
                    arr.mapNotNull { el ->
                        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                        // OpenRouter 等会在模型对象上带 context_length
                        val ctx = (o["context_length"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        ModelInfo(id, ctx)
                    }.sortedBy { it.id }
                }
            }
        }

    /** 简单的一次性连通性测试。 */
    suspend fun test(config: ModelConfig): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("model", config.model)
                put("max_tokens", 8)
                put("messages", turnsJson(listOf(ChatTurn("user", "回复\"好\""))))
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(config.url.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${config.key}")
                .post(body).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) "连接成功，模型 ${config.model} 可用"
                else throw IllegalStateException("HTTP ${resp.code}：${resp.body?.string()?.take(160) ?: ""}")
            }
        }
    }

    /** 非流式一次性补全（自动起标题等轻量后台任务用：小 max_tokens，不进聊天流）。 */
    suspend fun complete(config: ModelConfig, prompt: String, maxTokens: Int = 32): Result<String> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("model", config.model)
                    put("temperature", 0.3)
                    put("max_tokens", maxTokens)
                    put("messages", turnsJson(listOf(ChatTurn("user", prompt))))
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(config.url.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer ${config.key}")
                    .post(body).build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                    obj?.get("choices")?.let { c ->
                        (c as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                    }?.let { ch -> (ch as? kotlinx.serialization.json.JsonObject)?.get("message") }
                        ?.let { m -> (m as? kotlinx.serialization.json.JsonObject)?.get("content") }
                        ?.let { c ->
                            val prim = c as? kotlinx.serialization.json.JsonPrimitive
                            // content 为 JSON null 时返回 null 字符串会污染调用方（曾把标题写成 "null"）
                            if (prim != null && !prim.isString && prim.content == "null") null else prim?.content
                        }
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: throw IllegalStateException("模型返回了空内容")
                }
            }
        }
}
