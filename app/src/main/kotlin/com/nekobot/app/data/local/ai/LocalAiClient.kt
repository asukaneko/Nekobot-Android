package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.oauth.OAuthRuntimeCredential
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AI 请求结果（非流式）。
 */
data class LocalAiResult(
    val content: String,
    val usage: Map<String, Int> = emptyMap(),
    val error: String? = null,
    val statusCode: Int = 0,
    val usedModelId: String? = null,
    /** 用户为该模型配置的名称（LocalAiModelEntity.name），用于 Token 记录展示 */
    val usedModelName: String? = null,
    /** 实际请求的模型标识（LocalAiModelEntity.model，如 gpt-4o），用于模型排行榜按模型聚合 */
    val usedModelActualName: String? = null,
    val toolCalls: List<Map<String, Any>> = emptyList(),
    val finishReason: String = "",
    val thinkingContent: String = ""
)

/** 图片生成结果：url 或 bytes 二选一 */
data class GeneratedImage(
    val url: String?,
    val bytes: ByteArray?
)

/** OpenAI 兼容 Embeddings 结果。 */
data class EmbeddingResult(
    val vectors: List<FloatArray>,
    val inputTokens: Int = 0
)

/**
 * 本地 AI 客户端：直接通过 OkHttp 调用 OpenAI 兼容 / Anthropic 端点。
 *
 * 流式响应通过 Flow<RealtimeEvent> 推送，复用 SocketManager 的事件类型，
 * 让 UI 层无需区分本地/远程模式。
 *
 * 对应后端 `nbot/services/ai.py:AIClient.chat_completion`。
 */
class LocalAiClient(
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()
    private val proxyClients = ConcurrentHashMap<String, OkHttpClient>()
    private var oauthCredentialResolver: (suspend (String) -> OAuthRuntimeCredential)? = null

    fun setOAuthCredentialResolver(
        resolver: suspend (String) -> OAuthRuntimeCredential
    ) {
        oauthCredentialResolver = resolver
    }

    /**
     * 流式聊天。推送顺序：
     *   StreamStart → StreamChunk*N → StreamEnd / Error
     * 完整文本通过 [StreamEnd] 的 sessionId 携带返回。
     */
    fun chatStream(
        model: LocalAiModelEntity,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap()
    ): Flow<RealtimeEvent> = flow {
        emit(RealtimeEvent.StreamStart(null))

        val fullContent = StringBuilder()
        val fullThinking = StringBuilder()
        var response: Response? = null
        try {
            val (runtimeModel, credential) = resolveRuntimeModel(model)
            val protocol = LocalProtocols.get(runtimeModel.protocol)
            val url = protocol.resolveUrl(
                runtimeModel.baseUrl,
                runtimeModel.model,
                runtimeModel.appendBaseUrlPath
            )
            val headers = mergeRuntimeHeaders(
                protocol.buildHeaders(runtimeModel.apiKey, stream = true),
                credential
            )
            val payload = protocol.buildPayload(
                runtimeModel.model,
                messages,
                stream = true,
                extra = normalizeProtocolExtra(runtimeModel, extra)
            )
            val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
            val reqBuilder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            response = clientFor(runtimeModel).newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty().take(500)
                emit(RealtimeEvent.Error("HTTP ${response.code}: $errBody"))
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            }

            val src = response.body?.byteStream()
                ?: run {
                    emit(RealtimeEvent.Error("响应体为空"))
                    emit(RealtimeEvent.StreamEnd(null))
                    return@flow
                }

            BufferedReader(InputStreamReader(src, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("event:")) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    // 尝试解析 usage（OpenAI 在最后 chunk、Anthropic 在 message_delta）
                    protocol.parseStreamUsage(data)?.let { (input, output, _) ->
                        emit(RealtimeEvent.Usage(input, output, runtimeModel.model, runtimeModel.name))
                    }
                    protocol.parseStreamError(data)?.let { message ->
                        emit(RealtimeEvent.Error(message))
                        emit(RealtimeEvent.StreamEnd(null))
                        return@flow
                    }
                    protocol.parseStreamThinkingChunk(data)?.takeIf(String::isNotEmpty)?.let {
                        fullThinking.append(it)
                        emit(RealtimeEvent.ReasoningChunk(it))
                    }
                    protocol.parseStreamFinalResponse(data)?.thinkingContent
                        ?.takeIf { it.isNotBlank() && fullThinking.isEmpty() }
                        ?.let {
                            fullThinking.append(it)
                            emit(RealtimeEvent.ReasoningChunk(it))
                        }
                    protocol.parseStreamChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                        fullContent.append(chunk)
                        emit(RealtimeEvent.StreamChunk(chunk))
                    }
                }
            }
            emit(RealtimeEvent.StreamEnd(null))
        } catch (e: Exception) {
            Log.e("LocalAiClient", "stream failed: ${e.message}")
            emit(RealtimeEvent.Error(e.message ?: "流式请求异常"))
            emit(RealtimeEvent.StreamEnd(null))
        } finally {
            response?.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式聊天（用于压缩上下文等辅助任务）。
     * 非 2xx 响应抛出 [FailoverHttpException] 供协调器捕获。
     */
    suspend fun chatOnce(
        model: LocalAiModelEntity,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap(),
        requestTag: String? = null
    ): LocalAiResult {
        val (runtimeModel, credential) = resolveRuntimeModel(model)
        val protocol = LocalProtocols.get(runtimeModel.protocol)
        if (protocol.requiresStreaming) {
            return chatOnceViaStream(
                model = runtimeModel,
                credential = credential,
                messages = messages,
                extra = extra,
                requestTag = requestTag
            )
        }
        val url = protocol.resolveUrl(
            runtimeModel.baseUrl,
            runtimeModel.model,
            runtimeModel.appendBaseUrlPath
        )
        val headers = mergeRuntimeHeaders(
            protocol.buildHeaders(runtimeModel.apiKey, stream = false),
            credential
        )
        val payload = protocol.buildPayload(
            runtimeModel.model,
            messages,
            stream = false,
            extra = normalizeProtocolExtra(runtimeModel, extra)
        )
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)

        val reqBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        requestTag?.let { reqBuilder.tag(String::class.java, it) }

        return try {
            clientFor(runtimeModel).newCall(reqBuilder.build()).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $errBody")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val parsed = protocol.parseNonStreamResponse(data)
                LocalAiResult(
                    content = parsed.content,
                    usage = parsed.usage,
                    usedModelId = runtimeModel.id,
                    usedModelName = runtimeModel.name,
                    usedModelActualName = runtimeModel.model,
                    toolCalls = parsed.toolCalls,
                    finishReason = parsed.finishReason,
                    thinkingContent = parsed.thinkingContent
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "chatOnce failed: ${e.message}")
            throw e
        }
    }

    private suspend fun chatOnceViaStream(
        model: LocalAiModelEntity,
        credential: OAuthRuntimeCredential?,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?>,
        requestTag: String?
    ): LocalAiResult {
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
        val headers = mergeRuntimeHeaders(
            protocol.buildHeaders(model.apiKey, stream = true),
            credential
        )
        val payload = protocol.buildPayload(
            model.model,
            messages,
            stream = true,
            extra = normalizeProtocolExtra(model, extra)
        )
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                requestTag?.let { tag(String::class.java, it) }
            }
            .build()

        return clientFor(model).newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty().take(500)
                throw FailoverHttpException(response.code, "HTTP ${response.code}: $errorBody")
            }
            val content = StringBuilder()
            val thinking = StringBuilder()
            var usage = emptyMap<String, Int>()
            var terminal: LocalModelResponse? = null
            val source = response.body?.byteStream()
                ?: throw IllegalStateException("响应体为空")
            BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    protocol.parseStreamUsage(data)?.let { (input, output, total) ->
                        usage = mapOf(
                            "prompt" to input,
                            "completion" to output,
                            "total" to total
                        )
                    }
                    protocol.parseStreamError(data)?.let {
                        throw IllegalStateException(it)
                    }
                    protocol.parseStreamFinalResponse(data)?.let { terminal = it }
                    protocol.parseStreamThinkingChunk(data)?.let(thinking::append)
                    protocol.parseStreamChunk(data)?.let(content::append)
                }
            }
            val parsed = terminal
            LocalAiResult(
                content = parsed?.content?.takeIf(String::isNotEmpty) ?: content.toString(),
                usage = parsed?.usage?.takeIf(Map<*, *>::isNotEmpty) ?: usage,
                usedModelId = model.id,
                usedModelName = model.name,
                usedModelActualName = model.model,
                toolCalls = parsed?.toolCalls.orEmpty(),
                finishReason = parsed?.finishReason.orEmpty(),
                thinkingContent = parsed?.thinkingContent?.takeIf(String::isNotEmpty) ?: thinking.toString()
            )
        }
    }

    private suspend fun resolveRuntimeModel(
        model: LocalAiModelEntity
    ): Pair<LocalAiModelEntity, OAuthRuntimeCredential?> {
        val accountId = model.oauthAccountId ?: return model to null
        val resolver = oauthCredentialResolver
            ?: error("OAuth 凭据解析器未初始化")
        val credential = resolver(accountId)
        return model.copy(apiKey = credential.accessToken) to credential
    }

    private fun mergeRuntimeHeaders(
        protocolHeaders: Map<String, String>,
        credential: OAuthRuntimeCredential?
    ): Map<String, String> {
        if (credential == null) return protocolHeaders
        return protocolHeaders.toMutableMap().apply {
            credential.removeHeaders.forEach(::remove)
            putAll(credential.extraHeaders)
        }
    }

    private fun normalizeProtocolExtra(
        model: LocalAiModelEntity,
        extra: Map<String, Any?>
    ): Map<String, Any?> {
        val isCodexSubscription = model.provider == "openai-codex" ||
            model.baseUrl.contains("chatgpt.com/backend-api/codex")
        val normalized = if (isCodexSubscription && model.protocol == OpenAIResponsesProtocol.name) {
            extra - setOf("temperature", "max_tokens", "top_p")
        } else {
            extra
        }
        val effort = ReasoningEffort.fromValue(normalized["reasoning_effort"] as? String)
        val provider = model.provider.orEmpty().lowercase()
        val endpoint = model.baseUrl.lowercase()
        val deepSeek = provider.contains("deepseek") || endpoint.contains("deepseek")
        val mappedEffort = when {
            deepSeek -> when (effort) {
                ReasoningEffort.NONE -> "none"
                ReasoningEffort.MAX -> "max"
                else -> "high"
            }
            model.protocol == AnthropicMessagesProtocol.name -> when (effort) {
                ReasoningEffort.MINIMAL -> "low"
                else -> effort.wireValue
            }
            model.protocol == OpenAIResponsesProtocol.name -> when (effort) {
                ReasoningEffort.MAX -> "xhigh"
                else -> effort.wireValue
            }
            provider.contains("google") || provider.contains("gemini") || endpoint.contains("googleapis") ->
                if (effort == ReasoningEffort.MAX) "high" else effort.wireValue
            provider.contains("openai") ->
                if (effort == ReasoningEffort.MAX) "xhigh" else effort.wireValue
            else -> if (effort == ReasoningEffort.MAX) "high" else effort.wireValue
        }
        val modelName = model.model.lowercase()
        val knownReasoningTarget = deepSeek ||
            model.protocol == AnthropicMessagesProtocol.name ||
            model.protocol == OpenAIResponsesProtocol.name ||
            provider.contains("google") || provider.contains("gemini") || endpoint.contains("googleapis") ||
            modelName.startsWith("o1") || modelName.startsWith("o3") || modelName.startsWith("o4") ||
            modelName.startsWith("gpt-5")
        return normalized.toMutableMap().apply {
            if (effort != ReasoningEffort.NONE || knownReasoningTarget) {
                put("reasoning_effort", mappedEffort)
            } else {
                remove("reasoning_effort")
            }
            if (deepSeek) put("deepseek_thinking", true)
        }
    }

    /**
     * 测试模型连通性，返回成功/失败 + 提示。
     * 捕获 [FailoverHttpException] 等异常，转换为 [LocalAiResult]。
     */
    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult {
        if (model.purpose == "tts") {
            return try {
                val audio = synthesizeSpeech(model, "你好")
                if (audio.isEmpty()) {
                    LocalAiResult("", error = "TTS 未返回音频")
                } else {
                    LocalAiResult("TTS 连接成功，返回 ${audio.size} 字节音频")
                }
            } catch (e: FailoverHttpException) {
                LocalAiResult("", error = e.message ?: "HTTP ${e.statusCode}", statusCode = e.statusCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalAiResult("", error = e.message ?: "TTS 请求异常")
            }
        }
        val testMessages = listOf(
            mapOf("role" to "user", "content" to "说一句“你好”，不超过 10 个字。")
        )
        return try {
            chatOnce(model, testMessages)
        } catch (e: FailoverHttpException) {
            LocalAiResult("", error = e.message ?: "HTTP ${e.statusCode}", statusCode = e.statusCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalAiResult("", error = e.message ?: "请求异常")
        }
    }

    /**
     * 从 OpenAI 兼容端点获取模型列表，供本地模式模型编辑器使用。
     * 兼容 {"data":[{"id":"..."}]}、{"models":[...]} 和字符串数组。
     */
    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String = ""
    ): List<String> {
        val base = baseUrl.trimEnd('/')
        val url = when {
            base.endsWith("/models") -> base
            base.contains("/chat/completions") -> base.replace("/chat/completions", "/models")
            base.contains("/responses") -> base.replace("/responses", "/models")
            !appendBaseUrlPath -> "$base/models"
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("api-key", apiKey)
            .build()
        clientFor(proxyUrl).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "HTTP ${response.code}: ${raw.take(500)}")
            }
            val root = JsonParser.parseString(raw)
            val array = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("data") ->
                    root.asJsonObject.getAsJsonArray("data")
                root.isJsonObject && root.asJsonObject.has("models") ->
                    root.asJsonObject.getAsJsonArray("models")
                else -> return emptyList()
            }
            return array.mapNotNull { item ->
                when {
                    item.isJsonPrimitive -> item.asString
                    item.isJsonObject -> item.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                        ?: item.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString
                    else -> null
                }
            }.distinct().sorted()
        }
    }

    // ==================== 带故障转移的调用 ====================

    /**
     * 带故障转移的非流式聊天：按 [models] 顺序尝试，遇到错误自动切换下一个。
     * 兼容旧 API：捕获 [FailoverHttpException] 转换为 [LocalAiResult.error]。
     * 新代码应通过 [FailoverCoordinator] + [chatOnce] 实现。
     */
    suspend fun chatOnceWithFailover(
        models: List<LocalAiModelEntity>,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap(),
        requestTag: String? = null,
        shouldStop: () -> Boolean = { false }
    ): LocalAiResult {
        if (models.isEmpty()) return LocalAiResult("", error = "无可用模型")
        if (shouldStop()) throw CancellationException("生成已停止")
        val failover = getFailoverState()
        val exclude = mutableSetOf<String>()
        var lastError: LocalAiResult? = null
        for (i in models.indices) {
            if (shouldStop()) throw CancellationException("生成已停止")
            val modelConfigs = models.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = models.firstOrNull { it.id == modelId } ?: models[i]
            exclude.add(model.id)

            try {
                val result = chatOnce(model, messages, extra, requestTag)
                failover.recordSuccess(model.id)
                return result.copy(usedModelId = model.id, usedModelName = model.name, usedModelActualName = model.model)
            } catch (e: FailoverHttpException) {
                failover.recordFailure(model.id, e.statusCode)
                Log.w("LocalAiClient", "模型 ${model.name} 调用失败 (HTTP ${e.statusCode})，尝试下一个: ${e.message?.take(120)}")
                lastError = LocalAiResult("", error = e.message, statusCode = e.statusCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldStop()) throw CancellationException("生成已停止").apply { initCause(e) }
                val code = extractStatusCode(e)
                failover.recordFailure(model.id, code)
                Log.w("LocalAiClient", "模型 ${model.name} 调用异常，尝试下一个: ${e.message?.take(120)}")
                lastError = LocalAiResult("", error = e.message ?: "请求异常", statusCode = code)
            }
        }
        return lastError ?: LocalAiResult("", error = "所有模型均不可用")
    }

    /** 取消指定会话标签下正在排队或执行的非流式模型请求。 */
    fun cancelRequests(requestTag: String) {
        val calls = client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()
        calls
            .filter { call -> call.request().tag(String::class.java) == requestTag }
            .forEach { call -> runCatching { call.cancel() } }
    }

    /**
     * 带故障转移的流式聊天：按 [models] 顺序尝试，遇到可恢复错误自动切换。
     *
     * 推送顺序与 [chatStream] 一致，但失败时会自动切换下一个模型。
     * 如果第一个模型在流式过程中失败（如 401/500），会切换到下一个模型重试。
     */
    fun chatStreamWithFailover(
        models: List<LocalAiModelEntity>,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap()
    ): Flow<RealtimeEvent> = flow {
        if (models.isEmpty()) {
            emit(RealtimeEvent.Error("无可用模型"))
            emit(RealtimeEvent.StreamEnd(null))
            return@flow
        }
        val failover = getFailoverState()
        val exclude = mutableSetOf<String>()
        var streamStarted = false
        var lastErrorMsg: String? = null

        emit(RealtimeEvent.StreamStart(null))

        for (i in models.indices) {
            val modelConfigs = models.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = models.firstOrNull { it.id == modelId } ?: models[i]
            exclude.add(model.id)
            val (runtimeModel, credential) = resolveRuntimeModel(model)

            val fullContent = StringBuilder()
            val fullThinking = StringBuilder()
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var failed = false
            var httpCode = 0

            // 直接复用 chatStream 的内部逻辑（避免嵌套 Flow）
            val protocol = LocalProtocols.get(runtimeModel.protocol)
            val url = protocol.resolveUrl(
                runtimeModel.baseUrl,
                runtimeModel.model,
                runtimeModel.appendBaseUrlPath
            )
            val headers = mergeRuntimeHeaders(
                protocol.buildHeaders(runtimeModel.apiKey, stream = true),
                credential
            )
            val payload = protocol.buildPayload(
                runtimeModel.model,
                messages,
                stream = true,
                extra = normalizeProtocolExtra(runtimeModel, extra)
            )
            val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
            val reqBuilder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            var response: Response? = null
            try {
                response = clientFor(runtimeModel).newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty().take(500)
                    httpCode = response.code
                    lastErrorMsg = "HTTP ${response.code}: $errBody"
                    failed = true
                    failover.recordFailure(model.id, httpCode)
                    Log.w("LocalAiClient", "模型 ${model.name} 流式失败 (HTTP $httpCode)，尝试下一个")
                    response.close()
                    continue
                }
                val src = response.body?.byteStream()
                if (src == null) {
                    lastErrorMsg = "响应体为空"
                    failed = true
                    response.close()
                    continue
                }
                BufferedReader(InputStreamReader(src, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        if (line.startsWith("event:")) continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        protocol.parseStreamUsage(data)?.let { (input, output, _) ->
                            inputTokens = input
                            outputTokens = output
                        }
                        protocol.parseStreamThinkingChunk(data)?.takeIf(String::isNotEmpty)?.let {
                            fullThinking.append(it)
                            emit(RealtimeEvent.ReasoningChunk(it))
                        }
                        protocol.parseStreamFinalResponse(data)?.thinkingContent
                            ?.takeIf { it.isNotBlank() && fullThinking.isEmpty() }
                            ?.let {
                                fullThinking.append(it)
                                emit(RealtimeEvent.ReasoningChunk(it))
                            }
                        protocol.parseStreamChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                            if (!streamStarted) {
                                streamStarted = true
                            }
                            fullContent.append(chunk)
                            emit(RealtimeEvent.StreamChunk(chunk))
                        }
                    }
                }
                response.close()
                // 成功完成
                failover.recordSuccess(model.id)
                if (inputTokens != null || outputTokens != null) {
                    emit(
                        RealtimeEvent.Usage(
                            inputTokens ?: 0,
                            outputTokens ?: 0,
                            runtimeModel.model,
                            runtimeModel.name
                        )
                    )
                }
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            } catch (e: Exception) {
                Log.w("LocalAiClient", "模型 ${model.name} 流式异常: ${e.message}，尝试下一个")
                lastErrorMsg = e.message ?: "流式请求异常"
                failed = true
                failover.recordFailure(model.id, extractStatusCode(e))
                response?.close()
                continue
            }
        }

        // 所有模型都失败
        emit(RealtimeEvent.Error(lastErrorMsg ?: "所有模型均不可用"))
        emit(RealtimeEvent.StreamEnd(null))
    }.flowOn(Dispatchers.IO)

    /**
     * 视觉模型：理解图片内容（purpose = vision）。
     * 支持 OpenAI vision API 兼容格式：messages.content 为数组，含 image_url + text。
     * 非 2xx 响应抛出 [FailoverHttpException] 供协调器捕获。
     */
    suspend fun describeImage(
        model: LocalAiModelEntity,
        imageUrl: String,
        question: String = "请描述这张图片的内容。",
        requestTag: String? = null
    ): LocalAiResult {
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
        val headers = protocol.buildHeaders(model.apiKey, stream = false)
        val payload = buildVisionPayload(model, protocol, imageUrl, question)
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
        val reqBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        requestTag?.let { reqBuilder.tag(String::class.java, it) }

        return try {
            clientFor(model).newCall(reqBuilder.build()).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $err")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val parsed = protocol.parseNonStreamResponse(data)
                if (parsed.content.isBlank()) {
                    throw IllegalStateException(
                        "视觉模型返回空描述（protocol=${protocol.name}, responseKeys=${data.keys.joinToString()}）"
                    )
                }
                LocalAiResult(
                    content = parsed.content.trim(),
                    usage = parsed.usage,
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model,
                    finishReason = parsed.finishReason,
                    thinkingContent = parsed.thinkingContent
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "describeImage failed: ${e.message}")
            throw e
        }
    }

    private fun buildVisionPayload(
        model: LocalAiModelEntity,
        protocol: LocalProtocol,
        imageUrl: String,
        question: String
    ): Map<String, Any> {
        if (protocol.name == AnthropicMessagesProtocol.name) {
            val source = if (imageUrl.startsWith("data:")) {
                val mediaType = imageUrl.substringAfter("data:", "").substringBefore(';')
                val data = imageUrl.substringAfter("base64,", "")
                require(mediaType.isNotBlank() && data.isNotBlank()) { "无效的图片 data URI" }
                mapOf(
                    "type" to "base64",
                    "media_type" to mediaType,
                    "data" to data
                )
            } else {
                mapOf(
                    "type" to "url",
                    "url" to imageUrl
                )
            }
            return mapOf(
                "model" to model.model,
                "max_tokens" to (model.maxTokens ?: 1024),
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "image", "source" to source),
                            mapOf("type" to "text", "text" to question)
                        )
                    )
                )
            )
        }

        return mapOf(
            "model" to model.model,
            "stream" to false,
            "max_tokens" to (model.maxTokens ?: 1024),
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to question),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to imageUrl))
                    )
                )
            )
        )
    }

    /**
     * TTS 语音合成（purpose = tts）。
     * 与原仓库一致，按 tts_provider 路由 OpenAI 兼容、小米 MiMo 或豆包 TTS。
     */
    suspend fun synthesizeSpeech(
        model: LocalAiModelEntity,
        text: String,
        voice: String? = null,
        speed: Float? = null,
        pitch: Float? = null,
        volume: Float? = null
    ): ByteArray {
        val provider = model.ttsProvider.ifBlank {
            model.provider?.takeIf { it.isNotBlank() } ?: model.protocol
        }.lowercase()
        return when (provider) {
            "xiaomi", "mimo" -> synthesizeSpeechXiaomi(model, text, voice)
            "doubao", "volcengine", "bytedance" ->
                synthesizeSpeechDoubao(model, text, voice, speed, volume)
            else -> synthesizeSpeechOpenAi(model, text, voice, speed, pitch, volume)
        }
    }

    private suspend fun synthesizeSpeechOpenAi(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?,
        speedOverride: Float?,
        pitchOverride: Float?,
        volumeOverride: Float?
    ): ByteArray {
        val url = model.ttsUrl.takeIf { it.isNotBlank() }
            ?: resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/speech")
        val ttsModel = model.ttsModel.ifBlank { model.model.ifBlank { "gpt-4o-mini-tts" } }
        val voice = voiceOverride?.takeIf { it.isNotBlank() }
            ?: model.ttsVoice.takeIf { it.isNotBlank() && it != "default" }
            ?: "alloy"
        val speed = speedOverride ?: model.ttsSpeed
        val pitch = pitchOverride ?: model.ttsPitch
        val volume = volumeOverride ?: model.ttsVolume
        val format = model.ttsFormat.ifBlank { "mp3" }
        val variables = mapOf(
            "model" to ttsModel,
            "voice" to voice,
            "text" to text,
            "speed" to speed,
            "pitch" to pitch,
            "volume" to volume,
            "response_format" to format
        )
        val requestJson = if (model.ttsBodyTemplate.isNotBlank()) {
            renderTtsTemplate(model.ttsBodyTemplate, variables)
        } else {
            val payload = linkedMapOf<String, Any>(
                "model" to ttsModel,
                "input" to text,
                "voice" to voice,
                "speed" to speed,
                "response_format" to format
            )
            if (pitch != 1.0f) payload["pitch"] = pitch
            if (volume != 1.0f) payload["volume"] = volume
            gson.toJson(payload)
        }
        val builder = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
        parseTtsHeaders(model.ttsHeaders).forEach { (name, value) -> builder.header(name, value) }
        return executeTtsRequest(model, builder.build(), "OpenAI")
    }

    private suspend fun synthesizeSpeechXiaomi(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?
    ): ByteArray {
        val url = model.ttsUrl.takeIf { it.isNotBlank() }
            ?: resolveChatCompletionsUrl(model.baseUrl, model.appendBaseUrlPath)
        val voice = model.ttsRefAudio.takeIf { it.isNotBlank() }
            ?: voiceOverride?.takeIf { it.isNotBlank() }
            ?: model.ttsVoice.takeIf { it.isNotBlank() && it != "default" }
            ?: "mimo_default"
        val messages = buildList<Map<String, String>> {
            if (model.ttsUser.isNotBlank()) {
                add(mapOf("role" to "user", "content" to model.ttsUser))
            }
            add(mapOf("role" to "assistant", "content" to text))
        }
        val payload = mapOf(
            "model" to model.ttsModel.ifBlank { model.model.ifBlank { "mimo-v2.5-tts" } },
            "messages" to messages,
            "audio" to mapOf(
                "format" to model.ttsFormat.ifBlank { "mp3" },
                "voice" to voice
            )
        )
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("api-key", model.apiKey)
            .header("Content-Type", "application/json")
            .build()
        return clientFor(model).newCall(request).awaitResponse().use { response ->
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw FailoverHttpException(
                    response.code,
                    "小米 TTS HTTP ${response.code}: ${responseBytes.toString(Charsets.UTF_8).take(500)}"
                )
            }
            if (isAudioContentType(response.header("Content-Type"))) return@use responseBytes
            val root = runCatching {
                JsonParser.parseString(responseBytes.toString(Charsets.UTF_8)).asJsonObject
            }.getOrNull() ?: throw FailoverHttpException(response.code, "小米 TTS 未返回音频数据")
            val audio = root.getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("audio")
                ?: throw FailoverHttpException(response.code, "小米 TTS 响应缺少 choices[0].message.audio")
            when {
                audio.isJsonPrimitive -> decodeAudioBase64(audio.asString)
                audio.isJsonObject -> {
                    val obj = audio.asJsonObject
                    val audioUrl = obj.get("url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val audioData = obj.get("data")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    when {
                        audioUrl.isNotBlank() -> downloadAudio(model, audioUrl)
                        audioData.isNotBlank() -> decodeAudioBase64(audioData)
                        else -> throw FailoverHttpException(response.code, "小米 TTS 音频字段为空")
                    }
                }
                else -> throw FailoverHttpException(response.code, "小米 TTS 音频字段格式不受支持")
            }
        }
    }

    private suspend fun synthesizeSpeechDoubao(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?,
        speedOverride: Float?,
        volumeOverride: Float?
    ): ByteArray {
        val voice = voiceOverride?.takeIf { it.isNotBlank() }
            ?: model.ttsVoice.takeIf { it.isNotBlank() && it != "default" }
            ?: "zh_female_shuangkuaisisi_moon_bigtts"
        val speed = speedOverride ?: model.ttsSpeed
        val volume = volumeOverride ?: model.ttsVolume
        val payload = mapOf(
            "user" to mapOf("uid" to "neko_bot"),
            "req_params" to mapOf(
                "text" to text,
                "speaker" to voice,
                "audio_params" to mapOf(
                    "format" to model.ttsFormat.ifBlank { "mp3" },
                    "sample_rate" to 24000,
                    "speech_rate" to speed.toInt(),
                    "loudness_rate" to volume.toInt()
                ),
                "additions" to mapOf(
                    "disable_markdown_filter" to true,
                    "disable_emoji_filter" to true,
                    "explicit_language" to "zh-cn"
                )
            )
        )
        val request = Request.Builder()
            .url(model.ttsUrl.ifBlank { "https://openspeech.bytedance.com/api/v3/tts/unidirectional" })
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("X-Api-Key", model.apiKey)
            .header("X-Api-Resource-Id", model.ttsResourceId.ifBlank { "seed-tts-2.0" })
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .build()
        return clientFor(model).newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "豆包 TTS HTTP ${response.code}: ${raw.take(500)}")
            }
            val output = ByteArrayOutputStream()
            raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val chunk = runCatching {
                    JsonParser.parseString(line.removePrefix("data:").trim()).asJsonObject
                }.getOrNull() ?: return@forEach
                val code = chunk.get("code")?.asInt ?: -1
                when {
                    code == 0 -> chunk.get("data")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.let { output.write(decodeAudioBase64(it)) }
                    code == 20000000 -> Unit
                    else -> throw FailoverHttpException(
                        response.code,
                        "豆包 TTS 错误 code=$code: ${chunk.get("message")?.asString.orEmpty()}"
                    )
                }
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
                ?: throw FailoverHttpException(response.code, "豆包 TTS 未返回音频数据")
        }
    }

    private suspend fun executeTtsRequest(
        model: LocalAiModelEntity,
        request: Request,
        provider: String
    ): ByteArray =
        clientFor(model).newCall(request).awaitResponse().use { response ->
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw FailoverHttpException(
                    response.code,
                    "$provider TTS HTTP ${response.code}: ${responseBytes.toString(Charsets.UTF_8).take(500)}"
                )
            }
            if (isAudioContentType(response.header("Content-Type"))) return@use responseBytes
            val json = runCatching {
                JsonParser.parseString(responseBytes.toString(Charsets.UTF_8)).asJsonObject
            }.getOrNull() ?: throw FailoverHttpException(
                response.code,
                "$provider TTS 未返回音频: ${responseBytes.toString(Charsets.UTF_8).take(200)}"
            )
            val audioUrl = listOf("audio_url", "url")
                .firstNotNullOfOrNull { key -> json.get(key)?.takeIf { !it.isJsonNull }?.asString }
            val audioData = listOf("audio", "data")
                .firstNotNullOfOrNull { key -> json.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            when {
                !audioUrl.isNullOrBlank() -> downloadAudio(model, audioUrl)
                !audioData.isNullOrBlank() -> decodeAudioBase64(audioData)
                else -> throw FailoverHttpException(response.code, "$provider TTS 响应缺少音频数据")
            }
        }

    private suspend fun downloadAudio(model: LocalAiModelEntity, url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return clientFor(model).newCall(request).awaitResponse().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful || bytes.isEmpty()) {
                throw FailoverHttpException(response.code, "下载 TTS 音频失败: HTTP ${response.code}")
            }
            bytes
        }
    }

    private fun decodeAudioBase64(value: String): ByteArray {
        val payload = value.substringAfter("base64,", value).trim()
        return try {
            Base64.getDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("TTS 返回的音频 Base64 无效", error)
        }
    }

    private fun isAudioContentType(contentType: String?): Boolean {
        val value = contentType.orEmpty().lowercase()
        return value.contains("audio") || value.contains("octet-stream")
    }

    private fun parseTtsHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet().associate { (key, value) ->
                key to value.asString
            }
        }.getOrDefault(emptyMap())
    }

    private fun renderTtsTemplate(template: String, values: Map<String, Any>): String =
        Regex("""\{\{(\w+)}}""").replace(template) { match ->
            values[match.groupValues[1]]?.toString() ?: match.value
        }

    /**
     * STT 语音识别（purpose = stt）。
     * 根据 [LocalAiModelEntity.provider] 字段路由：
     * - `openai` / `openai_compatible` / 空 → OpenAI 兼容 /audio/transcriptions（multipart）
     * - `xiaomi` / `mimo` → 小米 MiMo /v1/chat/completions（JSON + base64 data URL）
     *
     * audioBytes 为音频字节数组（mp3/wav/m4a 等），返回识别文本。
     * 非 2xx 抛出 [FailoverHttpException] 供协调器捕获。
     */
    suspend fun transcribeSpeech(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String = "audio.mp3",
        language: String? = null
    ): LocalAiResult {
        val provider = (model.provider ?: "").trim().lowercase()
        return when (provider) {
            "xiaomi", "mimo" -> transcribeSpeechXiaomi(model, audioBytes, filename, language)
            else -> transcribeSpeechOpenAI(model, audioBytes, filename, language)
        }
    }

    /**
     * OpenAI 兼容 STT：POST /audio/transcriptions（multipart/form-data）。
     * 响应：JSON 中 `text` 字段（或 response_format=text 时为纯文本）。
     */
    private suspend fun transcribeSpeechOpenAI(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String,
        language: String?
    ): LocalAiResult {
        val url = resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/transcriptions")
        val audioMediaType = guessAudioMediaType(filename).toMediaType()
        Log.i("LocalAiClient", "STT(openai) 请求: url=$url, model=${model.model}, file=$filename, size=${audioBytes.size}, mime=$audioMediaType")
        val audioPart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model.model)
            .addFormDataPart("file", filename, audioBytes.toRequestBody(audioMediaType))
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
            }
            .build()
        val req = Request.Builder().url(url).post(audioPart)
            .header("Authorization", "Bearer ${model.apiKey}")
            .build()
        return try {
            clientFor(model).newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    Log.e("LocalAiClient", "STT(openai) 失败: HTTP ${resp.code} url=$url resp=$err")
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code} [POST $url]: $err")
                }
                val raw = resp.body?.string().orEmpty()
                // 兼容两种响应格式：JSON {"text": "..."} 或纯文本
                val text = if (raw.trimStart().startsWith("{")) {
                    @Suppress("UNCHECKED_CAST")
                    val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                    data["text"] as? String ?: ""
                } else {
                    raw.trim()
                }
                LocalAiResult(
                    text,
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "transcribeSpeechOpenAI failed: ${e.message}")
            throw e
        }
    }

    /**
     * 小米 MiMo STT：POST /v1/chat/completions（application/json）。
     *
     * 与 OpenAI 的关键差异：
     * - 端点路径：`/v1/chat/completions`（非 /audio/transcriptions）
     * - 认证头：`api-key: {key}`（非 Authorization: Bearer）
     * - 请求体：JSON，音频以 base64 data URL 形式放在 messages[0].content[0].input_audio.data
     * - 语言字段：放在顶层 `asr_options.language`（非 language）
     * - 响应：Chat Completions JSON，文本在 choices[0].message.content
     *
     * 音频格式：仅支持 wav / mp3；其他格式（m4a/aac/webm/ogg/flac）会自动用
     * Android 原生 MediaCodec 转码为 16kHz 单声道 WAV（与原仓库 ffmpeg 参数对齐）。
     * 文件大小上限：10MB。
     */
    private suspend fun transcribeSpeechXiaomi(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String,
        language: String?
    ): LocalAiResult {
        // 拼接 chat/completions URL（复用 resolveAudioUrl 会使路径变成 audio/transcriptions，需独立处理）
        val url = resolveChatCompletionsUrl(model.baseUrl, model.appendBaseUrlPath)

        // 小米 API 仅支持 wav/mp3；其他格式（m4a/aac/webm/ogg/flac）需先转码为 WAV。
        // 用 Android 原生 MediaExtractor + MediaCodec 解码 + 重采样到 16kHz 单声道，对齐原仓库 ffmpeg 参数。
        val ext = filename.substringAfterLast('.', "").lowercase()
        val (effectiveBytes, effectiveFilename, effectiveMime) = when (ext) {
            "wav", "mp3" -> Triple(audioBytes, filename, guessAudioMediaType(filename))
            else -> try {
                val wav = AudioConverter.toWav(audioBytes, targetSampleRate = 16000, targetChannels = 1)
                Triple(wav, "audio.wav", "audio/wav")
            } catch (e: Exception) {
                Log.w("LocalAiClient", "STT(xiaomi) 转码失败 ext=$ext，回退原字节直传: ${e.message}")
                Triple(audioBytes, filename, guessAudioMediaType(filename))
            }
        }

        val b64 = android.util.Base64.encodeToString(effectiveBytes, android.util.Base64.NO_WRAP)
        val dataUrl = "data:$effectiveMime;base64,$b64"
        val lang = language?.takeIf { it.isNotBlank() } ?: "auto"

        val bodyMap = mapOf(
            "model" to model.model,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "input_audio",
                            "input_audio" to mapOf("data" to dataUrl)
                        )
                    )
                )
            ),
            "asr_options" to mapOf("language" to lang)
        )
        val jsonBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())

        Log.i("LocalAiClient", "STT(xiaomi) 请求: url=$url, model=${model.model}, origFile=$filename, effectiveFile=$effectiveFilename, size=${effectiveBytes.size}, mime=$effectiveMime, lang=$lang")

        val req = Request.Builder().url(url).post(jsonBody)
            .header("api-key", model.apiKey)
            .header("Content-Type", "application/json")
            .build()
        return try {
            clientFor(model).newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    Log.e("LocalAiClient", "STT(xiaomi) 失败: HTTP ${resp.code} url=$url resp=$err")
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code} [POST $url]: $err")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                // choices[0].message.content
                val choices = data["choices"] as? List<*> ?: emptyList<Any>()
                val text = choices.firstOrNull()?.let { c ->
                    @Suppress("UNCHECKED_CAST")
                    ((c as? Map<String, Any>)?.get("message") as? Map<String, Any>)?.get("content") as? String
                } ?: raw.trim()
                LocalAiResult(
                    text,
                    usedModelId = model.id,
                    usedModelName = model.name
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "transcribeSpeechXiaomi failed: ${e.message}")
            throw e
        }
    }

    /**
     * 解析 chat/completions 端点 URL（供小米 MiMo STT 使用）。
     * - 已含 /chat/completions → 直接返回
     * - 已含 /audio/transcriptions → 替换为 /chat/completions（用户从 OpenAI 配置复制的情况）
     * - appendBaseUrlPath=false → 直接用 baseUrl
     * - 已含 /v1 → 追加 /chat/completions
     * - 其他 → 追加 /v1/chat/completions
     */
    private fun resolveChatCompletionsUrl(baseUrl: String, appendBaseUrlPath: Boolean): String {
        val base = baseUrl.trimEnd('/')
        if (base.contains("/chat/completions")) return base
        if (base.contains("/audio/transcriptions")) {
            return base.replace("/audio/transcriptions", "/chat/completions")
        }
        if (!appendBaseUrlPath) return base
        if (base.endsWith("/v1")) return "$base/chat/completions"
        return "$base/v1/chat/completions"
    }

    /**
     * 图片生成（purpose = image_generation）。
     * 调用 OpenAI 兼容 /images/generations 端点，返回图片字节 + MIME 类型。
     * 支持 URL 和 b64_json 两种响应格式。非 2xx 抛出 [FailoverHttpException]。
     */
    suspend fun generateImage(
        model: LocalAiModelEntity,
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): List<GeneratedImage> {
        val images = generateImage(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            modelName = model.model,
            prompt = prompt,
            size = size,
            n = n,
            appendBaseUrlPath = model.appendBaseUrlPath,
            proxyUrl = model.proxyUrl
        )
        return images.map { image ->
            val url = image.url
            if (image.bytes != null || url.isNullOrBlank()) {
                image
            } else {
                val bytes = runCatching {
                    val request = Request.Builder().url(url).get().build()
                    clientFor(model).newCall(request).awaitResponse().use { response ->
                        if (!response.isSuccessful) {
                            throw FailoverHttpException(
                                response.code,
                                "下载生成图片失败: HTTP ${response.code}"
                            )
                        }
                        response.body?.bytes()
                    }
                }.getOrNull()
                image.copy(bytes = bytes)
            }
        }
    }

    /**
     * 调用 OpenAI 兼容 `/embeddings` 接口。
     *
     * Embedding 模型配置复用本地模型的 baseUrl/apiKey/proxy/OAuth 字段；
     * 输入按批次发送，返回顺序按响应 index 还原。
     */
    suspend fun createEmbeddings(
        model: LocalAiModelEntity,
        inputs: List<String>
    ): EmbeddingResult {
        require(inputs.isNotEmpty()) { "Embedding 输入不能为空" }
        val (runtimeModel, credential) = resolveRuntimeModel(model)
        val url = resolveEmbeddingUrl(runtimeModel.baseUrl, runtimeModel.appendBaseUrlPath)
        val payload = linkedMapOf<String, Any>(
            "model" to runtimeModel.model,
            "input" to inputs
        )
        val headers = mergeRuntimeHeaders(
            mapOf(
                "Authorization" to "Bearer ${runtimeModel.apiKey}",
                "api-key" to runtimeModel.apiKey,
                "Content-Type" to "application/json"
            ),
            credential
        )
        val builder = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
        headers.forEach { (name, value) -> builder.header(name, value) }
        clientFor(runtimeModel).newCall(builder.build()).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(
                    response.code,
                    "Embedding HTTP ${response.code}: ${raw.take(500)}"
                )
            }
            val root = JsonParser.parseString(raw).asJsonObject
            val vectors = root.getAsJsonArray("data")
                ?.mapNotNull { item ->
                    val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val index = obj.get("index")?.asInt ?: 0
                    val vector = obj.getAsJsonArray("embedding")
                        ?.map { it.asFloat }
                        ?.toFloatArray()
                        ?: return@mapNotNull null
                    index to vector
                }
                ?.sortedBy { it.first }
                ?.map { it.second }
                .orEmpty()
            if (vectors.size != inputs.size) {
                error("Embedding 响应数量不匹配：期望 ${inputs.size}，实际 ${vectors.size}")
            }
            val usage = root.getAsJsonObject("usage")
            val inputTokens = usage?.get("prompt_tokens")?.asInt
                ?: usage?.get("total_tokens")?.asInt
                ?: 0
            return EmbeddingResult(vectors = vectors, inputTokens = inputTokens)
        }
    }

    /**
     * 图片生成（基本参数重载）：供远程模式直接调用，无需构造 [LocalAiModelEntity]。
     * 逻辑同上，调用 OpenAI 兼容 /images/generations 端点。
     */
    suspend fun generateImage(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1,
        appendBaseUrlPath: Boolean = true,
        proxyUrl: String = ""
    ): List<GeneratedImage> {
        val url = resolveImageUrl(baseUrl, appendBaseUrlPath)
        val payload = mapOf(
            "model" to modelName,
            "prompt" to prompt,
            "size" to size,
            "n" to n
        )
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        return try {
            clientFor(proxyUrl).newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $err")
                }
                val raw = resp.body?.string().orEmpty()
                val root = JsonParser.parseString(raw).asJsonObject
                val dataArr = root.getAsJsonArray("data") ?: return@use emptyList()
                dataArr.mapNotNull { item ->
                    val obj = item.asJsonObject
                    val urlStr = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
                    val b64 = obj.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
                    if (urlStr != null) {
                        GeneratedImage(url = urlStr, bytes = null)
                    } else if (b64 != null) {
                        GeneratedImage(url = null, bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    } else null
                }
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "generateImage failed: ${e.message}")
            throw e
        }
    }

    private fun clientFor(model: LocalAiModelEntity): OkHttpClient =
        clientFor(model.proxyUrl)

    private fun clientFor(proxyUrl: String): OkHttpClient {
        val normalized = proxyUrl.trim()
        if (normalized.isEmpty()) return client
        return proxyClients.getOrPut(normalized) {
            client.withModelProxy(normalized)
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 流式可能较慢
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * 可取消的 OkHttp Call 执行：协程取消时自动调用 [Call.cancel]，
         * 中断阻塞的 HTTP 请求。避免用户点"停止"后底层请求仍在进行。
         */
        private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response) { _, _, _ -> response.close() }
                    } else {
                        response.close()
                    }
                }
            })
        }

        /**
         * 解析图片生成端点 URL（/images/generations）。
         * 逻辑同 [resolveAudioUrl]：智能处理各种 baseUrl 格式。
         */
        private fun resolveImageUrl(baseUrl: String, appendBaseUrlPath: Boolean): String {
            val base = baseUrl.trimEnd('/')
            if (base.contains("/images/generations")) return base
            if (base.contains("/chat/completions")) {
                return base.replace("/chat/completions", "/images/generations")
            }
            if (base.contains("/chatcompletion")) {
                return base.replace("/chatcompletion", "/images/generations")
            }
            if (!appendBaseUrlPath) return base
            if (base.endsWith("/v1")) return "$base/images/generations"
            return "$base/v1/images/generations"
        }

        /** 解析 OpenAI 兼容 Embedding 端点。 */
        private fun resolveEmbeddingUrl(baseUrl: String, appendBaseUrlPath: Boolean): String {
            val base = baseUrl.trimEnd('/')
            if (base.contains("/embeddings")) return base
            if (base.contains("/chat/completions")) {
                return base.replace("/chat/completions", "/embeddings")
            }
            if (base.contains("/responses")) {
                return base.replace("/responses", "/embeddings")
            }
            if (!appendBaseUrlPath) return base
            if (base.endsWith("/v1")) return "$base/embeddings"
            return "$base/v1/embeddings"
        }

        /**
         * 解析 audio 端点 URL（STT: audio/transcriptions, TTS: audio/speech）。
         *
         * 智能处理各种 baseUrl 格式：
         * - 已含 /audio/transcriptions 或 /audio/speech → 直接返回
         * - 已含 /chat/completions → 替换为 /audio/xxx（用户从 chat 模型复制配置的常见情况）
         * - 已含 /v1 → 拼接 /audio/xxx
         * - appendBaseUrlPath=true → 拼接 /audio/xxx
         * - appendBaseUrlPath=false → 直接用 baseUrl（假设用户填了完整路径）
         */
        private fun resolveAudioUrl(baseUrl: String, appendBaseUrlPath: Boolean, audioPath: String): String {
            val base = baseUrl.trimEnd('/')
            // 已是完整 audio 端点
            if (base.contains("/audio/transcriptions") || base.contains("/audio/speech")) return base
            // 从 chat 配置复制过来的 baseUrl（如 https://xxx/v1/chat/completions）→ 替换为 audio 端点
            if (base.contains("/chat/completions")) {
                return base.replace("/chat/completions", "/$audioPath")
            }
            if (base.contains("/chatcompletion")) {
                return base.replace("/chatcompletion", "/$audioPath")
            }
            // appendBaseUrlPath=false：用户填的应该是完整 URL
            if (!appendBaseUrlPath) return base
            // 已含 /v1 → 直接拼接
            if (base.endsWith("/v1")) return "$base/$audioPath"
            // 其他情况拼接 /v1/audio/xxx（兼容 base 形如 https://api.openai.com 的写法）
            return "$base/v1/$audioPath"
        }

        /** 根据文件名后缀猜测音频 MIME 类型 */
        private fun guessAudioMediaType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "audio/mpeg"
            }
        }
    }
}
