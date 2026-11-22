package com.chengyan.app.net

import com.chengyan.app.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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

    /** 一轮对话内容。role 可为 system/user/assistant/tool。 */
    data class ChatTurn(
        val role: String,
        val text: String,
        val images: List<String> = emptyList(),
        val toolCalls: List<ToolCallData> = emptyList(),   // assistant 发起的调用
        val toolCallId: String = "",                        // role=tool 时对应的调用 id
    )

    fun stream(
        config: ModelConfig,
        history: List<ChatTurn>,
    ): Flow<StreamEvent> = callbackFlow {
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
            // 内置工具定义
            put("tools", Tools.definitionsJson())
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
                if (data == "[DONE]") { trySend(StreamEvent.Done); close(); return }
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
                    if (!thinking.isNullOrEmpty()) trySend(StreamEvent.ThinkingDelta(thinking))

                    // 正文
                    val content = delta["content"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    if (!content.isNullOrEmpty()) trySend(StreamEvent.Delta(content))

                    // 工具调用增量
                    (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                        val tc = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val idx = (tc["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        val fn = tc["function"] as? kotlinx.serialization.json.JsonObject
                        trySend(StreamEvent.ToolCallDelta(
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
                trySend(StreamEvent.Failure(msg))
                close()
            }
            override fun onClosed(es: EventSource) {
                trySend(StreamEvent.Done); close()
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
                    turn.images.isEmpty() -> add(buildJsonObject {
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
                        })
                    })
                }
            }
        }

    /** 获取服务端可用模型列表（GET /models）。 */
    suspend fun listModels(config: ModelConfig): Result<List<String>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.url.trimEnd('/') + "/models")
                    .header("Authorization", "Bearer ${config.key}")
                    .get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw IllegalStateException("空响应")
                    val obj = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                    val arr = obj["data"] as? kotlinx.serialization.json.JsonArray ?: emptyList<kotlinx.serialization.json.JsonElement>()
                    arr.mapNotNull { el ->
                        (el as? kotlinx.serialization.json.JsonObject)?.get("id")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    }.sorted()
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
}
