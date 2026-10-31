package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.diagnostics.wrap
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.oauth.LocalOAuthProviders
import com.nekobot.app.data.local.oauth.OAuthRuntimeCredential
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.BufferedReader
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "LocalAiClient"
/** 辅助作业要求一次请求时，不因 HTTP 400 自动删参数再调用；各协议不会发送这个内部标记。 */
internal const val NO_COMPATIBILITY_RETRY_EXTRA = "_no_compatibility_retry"
private const val IMAGE_GENERATION_TEST_PROMPT = "a test image"

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
    val thinkingContent: String = "",
    /**
     * 思考块签名（Anthropic 扩展思考）。必须在下一轮请求里与 thinking 一起回传，
     * 否则带工具调用的后续请求会被服务端拒绝。
     */
    val thinkingSignature: String = ""
)

/** 模型单次请求的实时回调；Agent 工具循环用它把最终文本直接推到聊天气泡。 */
data class LocalAiStreamCallbacks(
    val onStart: () -> Unit = {},
    val onContentChunk: (String) -> Unit = {},
    val onThinkingChunk: (String) -> Unit = {},
    val onStreamObserved: () -> Unit = {}
)

internal class LocalToolCallStreamAccumulator {
    private data class Part(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
        var initialArgumentsJson: String = ""
    )

    private val gson = Gson()
    private val parts = linkedMapOf<Int, Part>()

    fun add(delta: LocalToolCallDelta) {
        val part = parts.getOrPut(delta.index) { Part() }
        part.id.append(delta.idChunk)
        part.name.append(delta.nameChunk)
        part.arguments.append(delta.argumentsChunk)
        if (delta.initialArgumentsJson.isNotBlank()) {
            part.initialArgumentsJson = delta.initialArgumentsJson
        }
    }

    fun build(): List<Map<String, Any>> = parts.values.mapNotNull { part ->
        val name = part.name.toString()
        if (name.isBlank()) return@mapNotNull null
        val rawArguments = part.arguments.toString()
            .ifBlank { part.initialArgumentsJson }
            .ifBlank { "{}" }
        val arguments = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(rawArguments, Map::class.java) as Map<String, Any>
        }.getOrDefault(emptyMap())
        mapOf(
            "id" to part.id.toString(),
            "name" to name,
            "arguments" to arguments
        )
    }
}

/**
 * 流式聊天期间的「正文标记 → 思考」适配器。
 *
 * 思考字段先到时按「字段优先」放弃标记提取，并把尚未决定归属的缓存原样还给正文。
 */
private class ThinkingMarkerStreamState(config: ThinkingSourceConfig) {
    private var extractor: ThinkingMarkerExtractor? =
        if (config.markerEnabled) ThinkingMarkerExtractor(config) else null

    /** 本回复是否已从正文标记里提取到思考。 */
    val didExtract: Boolean get() = extractor?.didExtract == true

    fun feedContent(chunk: String): List<ExtractEvent> =
        extractor?.feed(chunk) ?: listOf(ExtractEvent.Content(chunk))

    fun feedThinkingField(chunk: String): List<ExtractEvent> {
        val active = extractor ?: return listOf(ExtractEvent.Thinking(chunk))
        if (active.didExtract) return listOf(ExtractEvent.Thinking(chunk))
        extractor = null
        return active.abandon() + ExtractEvent.Thinking(chunk)
    }

    fun finish(): List<ExtractEvent> {
        val active = extractor ?: return emptyList()
        extractor = null
        return active.finish()
    }
}

/** 非流式响应：字段没取到思考时，从正文标记里补提取（字段优先）。 */
private fun applyThinkingMarkerExtraction(response: LocalModelResponse): LocalModelResponse {
    if (response.thinkingContent.isNotBlank()) return response
    val config = ThinkingSourceSettings.current()
    if (!config.markerEnabled) return response
    val (content, thinking) = extractThinkingFromMarkedContent(
        config,
        response.content,
        response.thinkingContent
    )
    if (content == response.content && thinking == response.thinkingContent) return response
    return response.copy(content = content, thinkingContent = thinking)
}

/** 合并 Responses API 的多个终态事件（item.done / text.done / response.completed）。 */
internal fun mergeStreamTerminalResponse(
    current: LocalModelResponse?,
    update: LocalModelResponse
): LocalModelResponse {
    if (current == null) return update

    fun mergeText(old: String, new: String): String = when {
        new.isBlank() -> old
        old.isBlank() -> new
        new.contains(old) -> new
        old.contains(new) -> old
        else -> old + new
    }

    val toolCalls = (current.toolCalls + update.toolCalls).distinctBy { call ->
        val id = call["id"]?.toString().orEmpty()
        if (id.isNotBlank()) id else listOf(call["name"], call["arguments"]).toString()
    }
    return LocalModelResponse(
        content = mergeText(current.content, update.content),
        usage = update.usage.takeIf(Map<*, *>::isNotEmpty) ?: current.usage,
        toolCalls = toolCalls,
        finishReason = update.finishReason.ifBlank { current.finishReason },
        thinkingContent = mergeText(current.thinkingContent, update.thinkingContent)
    )
}

/** 识别短篇、明显的供应商拒答占位文本，避免误判正常长回复。 */
internal fun shouldFailoverForAssistantContent(content: String): Boolean {
    val compact = compactAssistantContent(content)
    if (compact.isBlank() || compact.length > FAILOVER_REFUSAL_MAX_CHARS) return false
    if (compact == "相关内容" || compact == "相关的内容") return true
    return FAILOVER_REFUSAL_PREFIXES.any(compact::startsWith)
}

/** 只等待仍可能是拒答占位的前缀；普通短回答不再统一攒满 240 字或等流结束。 */
internal fun shouldBufferAssistantPrefix(content: String): Boolean {
    if (content.length > FAILOVER_REFUSAL_MAX_CHARS) return false
    val compact = compactAssistantContent(content)
    return compact.isBlank() || shouldFailoverForAssistantContent(content) ||
        (FAILOVER_REFUSAL_PREFIXES + listOf("相关内容", "相关的内容")).any { it.startsWith(compact) }
}

private fun compactAssistantContent(content: String): String = content.trim()
    .replace(Regex("""[\s，,。.!！：:；;\"'“”‘’`*_#>\-]"""), "")

private const val FAILOVER_REFUSAL_MAX_CHARS = 240

/**
 * OpenCode Zen / Go 网关要求客户端标识自身并携带稳定的会话 ID
 * （x-opencode-session），否则返回 400 MissingSessionID 无法路由。
 * 参考 https://opencode.ai/docs/go/#where-can-i-use-it
 */
private const val OPENCODE_USER_AGENT = "Nekobot-Android"

private val FAILOVER_REFUSAL_PREFIXES = listOf(
    "你好我无法给到相关内容",
    "你好我无法给到相关的内容",
    "我无法给到相关内容",
    "我无法给到相关的内容",
    "你好我无法提供相关内容",
    "很抱歉我无法提供",
    "抱歉我无法提供",
    "对不起我无法提供",
    "我无法提供此类内容",
    "我无法提供这类内容",
    "我无法提供该内容",
    "我不能提供此类内容",
    "我不能提供这类内容",
    "我不能提供该内容",
    "我不能协助处理"
)

/** 图片生成结果：url 或 bytes 二选一 */
data class GeneratedImage(
    val url: String?,
    val bytes: ByteArray?
)

/** 图生图参考图。字节内容在任务执行时读取，避免把临时 URI 直接交给供应商。 */
data class ImageGenerationReference(
    val bytes: ByteArray,
    val mimeType: String = "image/png"
)

private const val QWEN_IMAGE_3_MODEL = "qwen-image-3.0"

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
    private val taggedCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val activeHttpCalls = ConcurrentHashMap.newKeySet<Call>()
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
        extra: Map<String, Any?> = emptyMap(),
        requestTag: String? = null
    ): Flow<RealtimeEvent> = flow {
        emit(RealtimeEvent.StreamStart(null))

        val fullContent = StringBuilder()
        val fullThinking = StringBuilder()
        var terminal: LocalModelResponse? = null
        var response: Response? = null
        val markerState = ThinkingMarkerStreamState(ThinkingSourceSettings.current())
        var sawContentChunk = false
        var sawThinkingChunk = false

        suspend fun emitThinkingText(text: String) {
            if (text.isEmpty()) return
            fullThinking.append(text)
            emit(RealtimeEvent.ReasoningChunk(text))
        }

        suspend fun emitContentText(text: String) {
            if (text.isEmpty()) return
            fullContent.append(text)
            emit(RealtimeEvent.StreamChunk(text))
        }

        suspend fun emitMarkerEvents(events: List<ExtractEvent>) {
            events.forEach { event ->
                when (event) {
                    is ExtractEvent.Content -> emitContentText(event.text)
                    is ExtractEvent.Thinking -> emitThinkingText(event.text)
                }
            }
        }

        suspend fun onStreamContent(chunk: String) {
            if (chunk.isEmpty()) return
            sawContentChunk = true
            emitMarkerEvents(markerState.feedContent(chunk))
        }

        suspend fun onStreamThinkingField(chunk: String) {
            if (chunk.isEmpty()) return
            sawThinkingChunk = true
            emitMarkerEvents(markerState.feedThinkingField(chunk))
        }

        try {
            val (runtimeModel, credential) = resolveRuntimeModel(model)
            val protocol = LocalProtocols.get(runtimeModel.protocol)
            val url = protocol.resolveUrl(
                runtimeModel.baseUrl,
                runtimeModel.model,
                runtimeModel.appendBaseUrlPath,
                stream = true,
                apiKey = runtimeModel.apiKey
            )
            val headers = mergeRuntimeHeaders(
                runtimeModel,
                protocol.buildHeaders(runtimeModel.apiKey, stream = true, endpoint = url),
                credential,
                requestTag
            )
            response = executeChatHttpRequest(
                model = runtimeModel,
                protocol = protocol,
                url = url,
                headers = headers,
                messages = messages,
                stream = true,
                extra = extra
            )
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty().take(500)
                emit(RealtimeEvent.Error("HTTP ${response.code}: $errBody"))
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            }

            parseBufferedJsonResponse(response, protocol)?.let { rawParsed ->
                val parsed = applyThinkingMarkerExtraction(rawParsed)
                parsed.thinkingContent.takeIf(String::isNotBlank)?.let {
                    emitThinkingText(it)
                }
                parsed.content.takeIf(String::isNotBlank)?.let {
                    emitContentText(it)
                }
                parsed.usage.takeIf { it.isNotEmpty() }?.let { usage ->
                    val detail = usageMapToDetail(usage)
                    emit(
                        RealtimeEvent.Usage(
                            inputTokens = detail.promptTokens,
                            outputTokens = detail.completionTokens,
                            model = runtimeModel.model,
                            modelDisplayName = runtimeModel.name,
                            cachedInputTokens = detail.cachedPromptTokens,
                            cacheWriteTokens = detail.cacheWriteTokens,
                                cacheReported = detail.cacheReported
                        )
                    )
                }
                if (parsed.content.isBlank() && parsed.toolCalls.isEmpty() &&
                    !(extra[CHARACTER_THINKING_EXTRA] == true && parsed.thinkingContent.isNotBlank())) {
                    emit(RealtimeEvent.Error("模型返回了空响应"))
                }
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            }

            val src = response.body?.byteStream()
                ?: run {
                    emit(RealtimeEvent.Error("响应体为空"))
                    emit(RealtimeEvent.StreamEnd(null))
                    return@flow
                }

            var streamErrorMessage: String? = null
            val completion = LocalStreamCompletion(protocol)
            BufferedReader(InputStreamReader(src, Charsets.UTF_8)).use { reader ->
                readSseEvents(reader) { data ->
                    completion.observe(data)
                    if (data == "[DONE]") return@readSseEvents false
                    // 尝试解析 usage（OpenAI 在最后 chunk、Anthropic 在 message_delta）
                    protocol.parseStreamUsageDetail(data)?.let { detail ->
                        emit(
                            RealtimeEvent.Usage(
                                inputTokens = detail.promptTokens,
                                outputTokens = detail.completionTokens,
                                model = runtimeModel.model,
                                modelDisplayName = runtimeModel.name,
                                cachedInputTokens = detail.cachedPromptTokens,
                                cacheWriteTokens = detail.cacheWriteTokens,
                                cacheReported = detail.cacheReported
                            )
                        )
                    }
                    protocol.parseStreamError(data)?.let { message ->
                        streamErrorMessage = message
                        return@readSseEvents false
                    }
                    protocol.parseStreamThinkingChunk(data)?.takeIf(String::isNotEmpty)?.let {
                        onStreamThinkingField(it)
                    }
                    protocol.parseStreamFinalResponse(data)?.let { parsed ->
                        terminal = mergeStreamTerminalResponse(terminal, parsed)
                        parsed.thinkingContent
                            .takeIf { it.isNotBlank() && !sawThinkingChunk }
                            ?.let { onStreamThinkingField(it) }
                    }
                    protocol.parseStreamChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                        onStreamContent(chunk)
                    }
                    true
                }
            }
            streamErrorMessage?.let { message ->
                emit(RealtimeEvent.Error(message))
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            }
            completion.requireComplete()
            terminal?.content
                ?.takeIf { it.isNotBlank() && !sawContentChunk }
                ?.let { onStreamContent(it) }
            emitMarkerEvents(markerState.finish())
            if (fullContent.isEmpty() && !(extra[CHARACTER_THINKING_EXTRA] == true && fullThinking.isNotBlank())) {
                emit(RealtimeEvent.Error("模型返回了空响应"))
            }
            emit(RealtimeEvent.StreamEnd(null))
        } catch (e: Exception) {
            LocalLogger.e(TAG, R.string.log_laic_stream_failed, e.message)
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
        requestTag: String? = null,
        streamCallbacks: LocalAiStreamCallbacks? = null
    ): LocalAiResult {
        val (runtimeModel, credential) = resolveRuntimeModel(model)
        val protocol = LocalProtocols.get(runtimeModel.protocol)
        if (protocol.requiresStreaming || streamCallbacks != null) {
            return chatOnceViaStream(
                model = runtimeModel,
                credential = credential,
                messages = messages,
                extra = extra,
                requestTag = requestTag,
                streamCallbacks = streamCallbacks
            )
        }
        val url = protocol.resolveUrl(
            runtimeModel.baseUrl,
            runtimeModel.model,
            runtimeModel.appendBaseUrlPath,
            stream = false,
            apiKey = runtimeModel.apiKey
        )
        val headers = mergeRuntimeHeaders(
            runtimeModel,
            protocol.buildHeaders(runtimeModel.apiKey, stream = false, endpoint = url),
            credential,
            requestTag
        )
        return try {
            executeChatHttpRequest(
                model = runtimeModel,
                protocol = protocol,
                url = url,
                headers = headers,
                messages = messages,
                stream = false,
                extra = extra,
                requestTag = requestTag
            ).use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $errBody")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val parsed = applyThinkingMarkerExtraction(protocol.parseNonStreamResponse(data))
                if (
                    parsed.toolCalls.isEmpty() &&
                    shouldFailoverForAssistantContent(parsed.content)
                ) {
                    throw FailoverRejectedContentException()
                }
                LocalAiResult(
                    content = parsed.content,
                    usage = parsed.usage,
                    usedModelId = runtimeModel.id,
                    usedModelName = runtimeModel.name,
                    usedModelActualName = runtimeModel.model,
                    toolCalls = parsed.toolCalls,
                    finishReason = parsed.finishReason,
                    thinkingContent = parsed.thinkingContent,
                    thinkingSignature = parsed.thinkingSignature
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: FailoverRejectedContentException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (extra[CompanionRelationshipRequest.EVALUATION_EXTRA] == true) {
                LocalLogger.w(TAG, "关系评估请求失败：异常=${e.javaClass.simpleName}")
            } else LocalLogger.e(TAG, R.string.log_laic_chat_once_failed, e.message)
            throw e
        }
    }

    private suspend fun chatOnceViaStream(
        model: LocalAiModelEntity,
        credential: OAuthRuntimeCredential?,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?>,
        requestTag: String?,
        streamCallbacks: LocalAiStreamCallbacks? = null
    ): LocalAiResult {
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(
            model.baseUrl,
            model.model,
            model.appendBaseUrlPath,
            stream = true,
            apiKey = model.apiKey
        )
        val headers = mergeRuntimeHeaders(
            model,
            protocol.buildHeaders(model.apiKey, stream = true, endpoint = url),
            credential,
            requestTag
        )
        return executeChatHttpRequest(
            model = model,
            protocol = protocol,
            url = url,
            headers = headers,
            messages = messages,
            stream = true,
            extra = extra,
            requestTag = requestTag
        ).use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty().take(500)
                throw FailoverHttpException(response.code, "HTTP ${response.code}: $errorBody")
            }
            val content = StringBuilder()
            val thinking = StringBuilder()
            val thinkingSignature = StringBuilder()
            val toolCallAccumulator = LocalToolCallStreamAccumulator()
            var usage = emptyMap<String, Int>()
            var terminal: LocalModelResponse? = null
            var finishReason = ""
            // 思考正文与正文分开放行：
            // - 思考内容不参与「短篇拒答占位」的故障转移判定，到达即转发给 UI（思考卡片实时增长）；
            // - 正文仅暂存仍可能是拒答占位的前缀，正常内容立即放行。
            var thinkingReleased = false
            var callbacksReleased = false
            var callbacksStarted = false
            /** onStart 只允许触发一次，避免重复的 StreamStart 把 UI 已累积的思考/正文清空。 */
            fun ensureCallbacksStarted() {
                if (callbacksStarted || streamCallbacks == null) return
                callbacksStarted = true
                streamCallbacks.onStart()
            }
            fun releaseThinkingCallbacks() {
                if (streamCallbacks == null) return
                ensureCallbacksStarted()
                if (thinkingReleased) return
                thinkingReleased = true
                thinking.takeIf { it.isNotEmpty() }?.let {
                    streamCallbacks.onThinkingChunk(it.toString())
                }
            }
            fun releaseBufferedCallbacks() {
                if (callbacksReleased || streamCallbacks == null) return
                callbacksReleased = true
                ensureCallbacksStarted()
                // 思考已逐块放行时不再整段补发，否则 UI 会把同一段思考重复累积
                if (!thinkingReleased) {
                    thinkingReleased = true
                    thinking.takeIf { it.isNotEmpty() }?.let {
                        streamCallbacks.onThinkingChunk(it.toString())
                    }
                }
                content.takeIf { it.isNotEmpty() }?.let {
                    streamCallbacks.onContentChunk(it.toString())
                }
            }
            val markerState = ThinkingMarkerStreamState(ThinkingSourceSettings.current())
            var markerExtractedThinking = false

            fun deliverMarkerEvents(events: List<ExtractEvent>) {
                events.forEach { event ->
                    when (event) {
                        is ExtractEvent.Content -> {
                            if (event.text.isEmpty()) return@forEach
                            content.append(event.text)
                            if (callbacksReleased) {
                                streamCallbacks?.onContentChunk?.invoke(event.text)
                            } else if (!shouldBufferAssistantPrefix(content.toString())) {
                                releaseBufferedCallbacks()
                            }
                        }
                        is ExtractEvent.Thinking -> {
                            if (event.text.isEmpty()) return@forEach
                            thinking.append(event.text)
                            if (thinkingReleased) {
                                streamCallbacks?.onThinkingChunk?.invoke(event.text)
                            } else {
                                releaseThinkingCallbacks()
                            }
                        }
                    }
                }
            }

            fun onStreamContent(chunk: String) {
                if (chunk.isEmpty()) return
                val events = markerState.feedContent(chunk)
                if (events.any { it is ExtractEvent.Thinking }) markerExtractedThinking = true
                deliverMarkerEvents(events)
            }

            fun onStreamThinkingField(chunk: String) {
                if (chunk.isEmpty()) return
                deliverMarkerEvents(markerState.feedThinkingField(chunk))
            }

            val rawBuffered = parseBufferedJsonResponse(response, protocol)
            if (rawBuffered != null) {
                val buffered = applyThinkingMarkerExtraction(rawBuffered)
                if (buffered.content.isBlank() && buffered.toolCalls.isEmpty() &&
                    !(extra[CHARACTER_THINKING_EXTRA] == true && buffered.thinkingContent.isNotBlank())) {
                    throw IllegalStateException("模型返回了空响应")
                }
                if (
                    buffered.toolCalls.isEmpty() &&
                    shouldFailoverForAssistantContent(buffered.content)
                ) {
                    throw FailoverRejectedContentException()
                }
                ensureCallbacksStarted()
                buffered.thinkingContent.takeIf(String::isNotBlank)?.let {
                    streamCallbacks?.onThinkingChunk?.invoke(it)
                }
                buffered.content.takeIf(String::isNotBlank)?.let {
                    streamCallbacks?.onContentChunk?.invoke(it)
                }
                return@use LocalAiResult(
                    content = buffered.content,
                    usage = buffered.usage,
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model,
                    toolCalls = buffered.toolCalls,
                    finishReason = buffered.finishReason.ifBlank {
                        if (buffered.toolCalls.isNotEmpty()) "tool_calls" else "stop"
                    },
                    thinkingContent = buffered.thinkingContent,
                    thinkingSignature = buffered.thinkingSignature
                )
            }
            val source = response.body?.byteStream()
                ?: throw IllegalStateException("响应体为空")
            val completion = LocalStreamCompletion(protocol)
            BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                readSseEvents(reader) { data ->
                    streamCallbacks?.onStreamObserved?.invoke()
                    completion.observe(data)
                    if (data == "[DONE]") return@readSseEvents false
                    protocol.parseStreamUsageDetail(data)?.let { detail ->
                        usage = detail.toUsageMap()
                    }
                    protocol.parseStreamError(data)?.let {
                        throw IllegalStateException(it)
                    }
                    protocol.parseStreamFinalResponse(data)?.let {
                        terminal = mergeStreamTerminalResponse(terminal, it)
                    }
                    protocol.parseStreamFinishReason(data)?.let { finishReason = it }
                    protocol.parseStreamToolCallDeltas(data).forEach(toolCallAccumulator::add)
                    protocol.parseStreamThinkingChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                        // 思考正文到达即放行：首个分片触发 onStart 并交付已累积思考，
                        // 之后每个分片都必须立即转发，思考卡片才能逐字增长。
                        // 旧实现只在 callbacksReleased（正文超过阈值）之后才逐块转发，
                        // 于是首个分片之后思考停更，直到正文放行或流结束才整段出现。
                        onStreamThinkingField(chunk)
                    }
                    protocol.parseStreamThinkingSignature(data)?.takeIf(String::isNotEmpty)?.let {
                        thinkingSignature.append(it)
                    }
                    protocol.parseStreamChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                        onStreamContent(chunk)
                    }
                    true
                }
            }
            completion.requireComplete()
            deliverMarkerEvents(markerState.finish())
            val parsed = terminal
            val streamedToolCalls = toolCallAccumulator.build()
            var finalContent = parsed?.content?.takeIf(String::isNotEmpty) ?: content.toString()
            var finalThinking = parsed?.thinkingContent?.takeIf(String::isNotEmpty) ?: thinking.toString()
            val markerConfig = ThinkingSourceSettings.current()
            if (markerConfig.markerEnabled) {
                if (markerExtractedThinking) {
                    // 流式阶段已剪过标记，终态正文可能是同一份原文，再剪一次避免标记回显。
                    finalContent = stripThinkingMarkers(markerConfig, finalContent)
                } else if (finalThinking.isBlank()) {
                    val extracted = extractThinkingFromMarkedContent(
                        markerConfig, finalContent, finalThinking
                    )
                    finalContent = extracted.first
                    finalThinking = extracted.second
                }
            }
            val toolCalls = parsed?.toolCalls?.takeIf(List<*>::isNotEmpty) ?: streamedToolCalls
            if (finalContent.isBlank() && toolCalls.isEmpty() &&
                !(extra[CHARACTER_THINKING_EXTRA] == true && finalThinking.isNotBlank())) {
                throw IllegalStateException("模型返回了空响应")
            }
            if (toolCalls.isEmpty() && shouldFailoverForAssistantContent(finalContent)) {
                throw FailoverRejectedContentException()
            }
            if (!callbacksReleased) {
                ensureCallbacksStarted()
                // 已逐块放行的思考不再整段补发，避免 UI 重复累积
                if (!thinkingReleased) {
                    thinkingReleased = true
                    finalThinking.takeIf(String::isNotBlank)?.let {
                        streamCallbacks?.onThinkingChunk?.invoke(it)
                    }
                }
                finalContent.takeIf(String::isNotBlank)?.let {
                    streamCallbacks?.onContentChunk?.invoke(it)
                }
            }
            LocalAiResult(
                content = finalContent,
                usage = parsed?.usage?.takeIf(Map<*, *>::isNotEmpty) ?: usage,
                usedModelId = model.id,
                usedModelName = model.name,
                usedModelActualName = model.model,
                toolCalls = toolCalls,
                finishReason = parsed?.finishReason?.takeIf(String::isNotBlank)
                    ?: finishReason.ifBlank { if (toolCalls.isNotEmpty()) "tool_calls" else "stop" },
                thinkingContent = finalThinking,
                thinkingSignature = parsed?.thinkingSignature?.takeIf(String::isNotEmpty)
                    ?: thinkingSignature.toString()
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
        model: LocalAiModelEntity,
        protocolHeaders: Map<String, String>,
        credential: OAuthRuntimeCredential?,
        requestTag: String? = null
    ): Map<String, String> {
        val merged = protocolHeaders.toMutableMap()
        if (credential != null) {
            credential.removeHeaders.forEach(merged::remove)
            merged.putAll(credential.extraHeaders)
        }
        applyOpenCodeRoutingHeaders(merged, model, requestTag)
        return merged
    }

    /**
     * OpenCode Zen / Go 网关要求每个对话使用稳定的 x-opencode-session，
     * 并建议客户端使用自有 User-Agent（而非 okhttp 等通用库名）。
     * 聊天请求使用会话 ID；辅助任务（标题、评分等）退回到账号/模型级别的稳定 ID。
     */
    private fun applyOpenCodeRoutingHeaders(
        headers: MutableMap<String, String>,
        model: LocalAiModelEntity,
        requestTag: String?
    ) {
        if (!isOpenCodeEndpoint(model)) return
        headers["User-Agent"] = OPENCODE_USER_AGENT
        headers["x-opencode-session"] = requestTag
            ?.takeIf(String::isNotBlank)
            ?: stableOpenCodeSessionId(model)
    }

    private fun isOpenCodeEndpoint(model: LocalAiModelEntity): Boolean {
        val provider = model.provider.orEmpty().lowercase()
        if (
            provider == LocalOAuthProviders.OPENCODE_ZEN ||
            provider == LocalOAuthProviders.OPENCODE_GO
        ) {
            return true
        }
        return model.baseUrl.lowercase().contains("opencode.ai/zen")
    }

    private fun stableOpenCodeSessionId(model: LocalAiModelEntity): String {
        val seed = model.oauthAccountId?.takeIf(String::isNotBlank) ?: model.id
        return UUID.nameUUIDFromBytes("opencode-session:$seed".toByteArray()).toString()
    }

    /**
     * 解析模型配置里的停止字符串。
     *
     * 优先按 JSON 数组解析（`["<|endoftext|>","\n用户:"]`），失败时兼容用户直接输入
     * 的换行 / 逗号 / 分号分隔文本。返回 null 表示不发送 stop 字段。
     */
    private fun parseStopSequences(raw: String?): List<String>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fromJson = runCatching {
            JsonParser.parseString(text)
                .takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element -> element.takeIf { !it.isJsonNull }?.asString?.trim() }
                ?.filter { it.isNotEmpty() }
        }.getOrNull()
        if (!fromJson.isNullOrEmpty()) return fromJson
        return text.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    private fun normalizeProtocolExtra(
        model: LocalAiModelEntity,
        extra: Map<String, Any?>
    ): Map<String, Any?> {
        // 模型级停止字符串统一以 extra["stop"] 传入，由各协议翻译成自己的字段名：
        // openai → stop / anthropic → stop_sequences / gemini → generationConfig.stopSequences。
        // OpenAI Responses API 不支持停止字符串，其 payload 是白名单构建，不会带上该键。
        val withStops = if (extra.containsKey("stop")) {
            extra
        } else {
            parseStopSequences(model.stopSequences)?.let { extra + ("stop" to it) } ?: extra
        }
        val isCodexSubscription = model.provider == "openai-codex" ||
            model.baseUrl.contains("chatgpt.com/backend-api/codex")
        val protocolExtra = if (isCodexSubscription && model.protocol == OpenAIResponsesProtocol.name) {
            withStops - setOf("temperature", "max_tokens", "top_p")
        } else {
            withStops
        }
        val normalized = protocolExtra
        val effort = ReasoningEffort.fromValue(normalized["reasoning_effort"] as? String)
        if (effort == ReasoningEffort.CUSTOM) {
            // 自定义思考参数：不做服务商自动换算，由 buildRequest 原样写入请求体。
            val custom = normalized[com.nekobot.app.data.model.CustomReasoningParam.EXTRA_KEY] as? com.nekobot.app.data.model.CustomReasoningParam
            return normalized.toMutableMap().apply {
                remove("reasoning_effort")
                if (custom == null || !custom.isValid) {
                    remove(com.nekobot.app.data.model.CustomReasoningParam.EXTRA_KEY)
                    runCatching { LocalLogger.w(TAG, "自定义思考参数无效，已不发送思考参数：" + (custom?.validationError() ?: "未设置")) }
                }
            }
        }
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
            // Responses 的默认推理强度由模型决定；未指定时不能强行发送 none。
            val useResponsesDefault = model.protocol == OpenAIResponsesProtocol.name &&
                !normalized.containsKey("reasoning_effort")
            if (!useResponsesDefault && (effort != ReasoningEffort.NONE || knownReasoningTarget)) {
                put("reasoning_effort", mappedEffort)
            } else {
                remove("reasoning_effort")
            }
            if (deepSeek) put("deepseek_thinking", true)
        }
    }

    /**
     * OpenAI 兼容端点经常只实现最小请求子集。正式聊天会携带模型采样参数，
     * 而连通性测试和会话辅助任务不会，因此同一模型可能出现“测试成功、聊天 400”。
     * 首次 400 时按测试请求的参数基线重试一次，同时保留 tools 等结构化能力；
     * Chat Completions 流式重试还会省略部分兼容端点不支持的 stream_options。
     */
    private suspend fun executeChatHttpRequest(
        model: LocalAiModelEntity,
        protocol: LocalProtocol,
        url: String,
        headers: Map<String, String>,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?>,
        requestTag: String? = null
    ): Response {
        fun buildRequest(normalizedExtra: Map<String, Any?>): Request {
            val payload = protocol.buildPayload(
                model.model,
                messages,
                stream = stream,
                extra = normalizedExtra
            ).let { built ->
                (normalizedExtra[com.nekobot.app.data.model.CustomReasoningParam.EXTRA_KEY]
                    as? com.nekobot.app.data.model.CustomReasoningParam)?.applyTo(built) ?: built
            }
            return Request.Builder()
                .url(url)
                .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    requestTag?.let { tag(String::class.java, it) }
                }
                .build()
        }

        val traceContext = kotlin.coroutines.coroutineContext[com.nekobot.app.data.local.diagnostics.DiagnosticTraceContext]
        suspend fun send(transport: OkHttpClient, request: Request, bufferBody: Boolean = false): Response {
            val secrets = listOf(model.apiKey) + headers.filterKeys {
                it.contains(Regex("(?i)authorization|api.?key|cookie|token|secret"))
            }.values
            val trace = traceContext?.request(mapOf(
                "model" to model.model, "model_name" to model.name, "provider" to model.provider,
                "protocol" to protocol.name, "endpoint" to url, "stream" to stream,
                "max_context" to model.maxContextLength, "configured_max_tokens" to model.maxTokens,
                "timeout_ms" to transport.callTimeoutMillis,
                "content_type" to request.body?.contentType()?.toString()
            ), JsonParser.parseString(okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()), secrets)
            return try {
                val response = transport.newCall(request).awaitResponse(bufferBody = bufferBody)
                val body = response.body
                if (trace == null) response else if (body == null) {
                    trace.finish(response.code, "", true)
                    response
                } else response.newBuilder().body(trace.wrap(body, response.code)).build()
            } catch (error: Exception) {
                trace?.finish(null, "", false, error.javaClass.simpleName + ": " + error.message.orEmpty())
                throw error
            }
        }
        val normalizedExtra = normalizeProtocolExtra(model, extra)
        val evaluation = extra[CompanionRelationshipRequest.EVALUATION_EXTRA] == true
        val transport = if (evaluation) clientFor(model).newBuilder()
            .callTimeout((extra[CompanionRelationshipRequest.CALL_TIMEOUT_EXTRA] as? Number)?.toLong()
                ?.coerceIn(1L, CompanionRelationshipRecovery.TIMEOUT_SECONDS * 1000L)
                ?: CompanionRelationshipRecovery.TIMEOUT_SECONDS * 1000L, TimeUnit.MILLISECONDS)
            .eventListener(RelationshipHttpDiagnostics(requestTag)).build() else clientFor(model)
        val request = buildRequest(normalizedExtra)
        if (evaluation) {
            val body = protocol.buildPayload(model.model, messages, stream, normalizedExtra)
            val budgetField = when {
                body.containsKey("max_completion_tokens") -> "max_completion_tokens"
                body.containsKey("max_output_tokens") -> "max_output_tokens"
                body.containsKey("generationConfig") -> "maxOutputTokens"
                else -> "max_tokens"
            }
            val safeModel = model.model.filter { it.isLetterOrDigit() || it in "-._/:" }.take(100)
            LocalLogger.i("RelationshipHttp", "tag=$requestTag，model=$safeModel，协议=${protocol.name}，输出字段=$budgetField，输出上限=${extra["max_tokens"]}，关闭思考=${extra["_disable_thinking"] == true || extra["reasoning_effort"] == "none" || extra["enable_thinking"] == false}，stream=$stream，硬超时毫秒=${transport.callTimeoutMillis}")
        }
        val response = send(transport, request, bufferBody = evaluation)
        if (response.code != 400 || protocol.name !in OPENAI_COMPATIBILITY_PROTOCOLS || extra[NO_COMPATIBILITY_RETRY_EXTRA] == true) {
            return response
        }

        // 角色视角模式的推理参数保留，避免采样参数 400 导致回退请求意外关闭原生思考。
        val optionalKeys = if (extra[CHARACTER_THINKING_EXTRA] == true) {
            OPENAI_OPTIONAL_TUNING_KEYS - "reasoning_effort"
        } else OPENAI_OPTIONAL_TUNING_KEYS
        val fallbackSource = (extra - optionalKeys).toMutableMap().apply {
            if (stream && protocol.name == OpenAIChatProtocol.name) {
                put("omit_stream_options", true)
            }
        }
        val fallbackExtra = normalizeProtocolExtra(model, fallbackSource)
        if (fallbackExtra == normalizedExtra) return response

        val firstError = response.body?.string().orEmpty().take(500)
        response.close()
        runCatching {
            LocalLogger.w(TAG, R.string.log_laic_400_minimal_retry, firstError.take(160))
        }
        // 自定义思考参数在兼容重试中保留（不在可省略参数内），并记录便于排查。
        (fallbackExtra[com.nekobot.app.data.model.CustomReasoningParam.EXTRA_KEY] as? com.nekobot.app.data.model.CustomReasoningParam)?.let {
            runCatching { LocalLogger.w(TAG, "400 重试仍保留自定义思考参数：${it.path}") }
        }
        return send(clientFor(model), buildRequest(fallbackExtra))
    }

    /** 某些兼容端点即使收到 stream=true 仍返回完整 JSON；不能按 SSE 逐行直接丢弃。 */
    private fun parseBufferedJsonResponse(
        response: Response,
        protocol: LocalProtocol
    ): LocalModelResponse? {
        val contentType = response.body?.contentType()?.toString().orEmpty().lowercase()
        if (!contentType.contains("json") || contentType.contains("event-stream")) return null
        val raw = response.body?.string().orEmpty()
        if (raw.isBlank()) throw IllegalStateException("响应体为空")
        protocol.parseStreamError(raw)?.let { throw IllegalStateException(it) }
        protocol.parseStreamFinalResponse(raw)?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val data = gson.fromJson(raw, Map::class.java) as? Map<String, Any>
            ?: throw IllegalStateException("响应 JSON 格式无效")
        return protocol.parseNonStreamResponse(data)
    }

    /**
     * 测试模型连通性，返回成功/失败 + 提示。
     * 捕获 [FailoverHttpException] 等异常，转换为 [LocalAiResult]。
     */
    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult {
        if (model.purpose == "embedding") {
            return try {
                val result = createEmbeddings(model, listOf("你好"))
                LocalAiResult("向量连接成功，返回 ${result.vectors.single().size} 维向量")
            } catch (e: FailoverHttpException) {
                LocalAiResult("", error = e.message ?: "HTTP ${e.statusCode}", statusCode = e.statusCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalAiResult("", error = e.message ?: "向量请求异常")
            }
        }
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
        if (model.purpose == "image_generation") {
            return try {
                val images = generateImage(
                    baseUrl = model.baseUrl,
                    apiKey = model.apiKey,
                    modelName = model.model,
                    prompt = IMAGE_GENERATION_TEST_PROMPT,
                    additionalPrompt = model.promptTemplate,
                    n = 1,
                    appendBaseUrlPath = model.appendBaseUrlPath,
                    proxyUrl = model.proxyUrl,
                    provider = MultimodalProviderSupport.imageProvider(model)
                )
                LocalAiResult(
                    content = images.size.toString(),
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model
                )
            } catch (e: FailoverHttpException) {
                LocalAiResult(
                    "",
                    error = e.message ?: "HTTP ${e.statusCode}",
                    statusCode = e.statusCode,
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalAiResult(
                    "",
                    error = e.message ?: "图片生成请求异常",
                    usedModelId = model.id,
                    usedModelName = model.name,
                    usedModelActualName = model.model
                )
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
        protocolName: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String = ""
    ): List<String> {
        if (protocolName == GeminiNativeProtocol.name) {
            return fetchGeminiModels(baseUrl, apiKey, proxyUrl)
        }
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

    private fun fetchGeminiModels(
        baseUrl: String,
        apiKey: String,
        proxyUrl: String
    ): List<String> {
        val supplied = baseUrl.trim().trimEnd('/')
        require(supplied.isNotBlank()) { "Gemini base URL is required" }
        val path = supplied.substringBefore('?')
        val listUrl = when {
            path.endsWith("/models") -> path
            path.contains("/models/") -> "${path.substringBefore("/models/")}/models"
            else -> "$path/models"
        }
        // 官方端点只用 x-goog-api-key 头；API key 不进 URL query，避免泄露到日志请求行。
        // Authorization Bearer 仅对第三方代理保留（无效 Bearer 会被官方端点优先校验导致 401）。
        val officialGoogle = listUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)
        val request = Request.Builder()
            .url(listUrl)
            .get()
            .header("x-goog-api-key", apiKey)
            .apply {
                if (!officialGoogle) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .build()
        clientFor(proxyUrl).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "HTTP ${response.code}: ${raw.take(500)}")
            }
            val models = JsonParser.parseString(raw)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonArray("models")
                ?: return emptyList()
            return models.mapNotNull { item ->
                item.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("name")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.removePrefix("models/")
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
        shouldStop: () -> Boolean = { false },
        streamCallbacks: LocalAiStreamCallbacks? = null,
        requiredContextTokens: Int? = null
    ): LocalAiResult {
        if (models.isEmpty()) return LocalAiResult("", error = "无可用模型")
        if (shouldStop()) throw CancellationException("生成已停止")
        val failover = getFailoverState()
        val exclude = mutableSetOf<String>()
        var lastError: LocalAiResult? = null
        val primaryModelId = models.firstOrNull()?.id
        for (attempt in models.indices) {
            if (shouldStop()) throw CancellationException("生成已停止")
            val candidates = models.filter {
                it.id == primaryModelId || it.canHandleContext(requiredContextTokens)
            }
            if (candidates.isEmpty()) break
            val modelConfigs = candidates.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = candidates.firstOrNull { it.id == modelId } ?: candidates.firstOrNull { it.id !in exclude }
                ?: break
            exclude.add(model.id)

            try {
                val result = chatOnce(model, messages, extra, requestTag, streamCallbacks)
                failover.recordSuccess(model.id)
                return result.copy(usedModelId = model.id, usedModelName = model.name, usedModelActualName = model.model)
            } catch (e: FailoverHttpException) {
                failover.recordFailure(model.id, e.statusCode)
                LocalLogger.w(TAG, R.string.log_laic_model_http_failover, model.name, e.statusCode, e.message?.take(120))
                lastError = LocalAiResult("", error = e.message, statusCode = e.statusCode)
            } catch (e: FailoverRejectedContentException) {
                failover.recordFailure(model.id)
                lastError = LocalAiResult("", error = e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldStop()) throw CancellationException("生成已停止").apply { initCause(e) }
                val code = extractStatusCode(e)
                failover.recordFailure(model.id, code)
                LocalLogger.w(TAG, R.string.log_laic_model_exception_failover, model.name, e.message?.take(120))
                lastError = LocalAiResult("", error = e.message ?: "请求异常", statusCode = code)
            }
        }
        return lastError ?: LocalAiResult("", error = "所有模型均不可用")
    }

    /** 响应头返回后仍跟踪读取中的响应体，停止操作可以立即中断长流。 */
    private suspend fun Call.awaitResponse(bufferBody: Boolean = false): Response = suspendCancellableCoroutine { cont ->
        activeHttpCalls.add(this)
        val tag = request().tag(String::class.java)
        if (tag != null) taggedCalls.compute(tag) { _, calls ->
            (calls ?: ConcurrentHashMap.newKeySet()).apply { add(this@awaitResponse) }
        }
        fun release() {
            activeHttpCalls.remove(this)
            if (tag != null) taggedCalls.computeIfPresent(tag) { _, calls ->
            calls.remove(this)
            calls.takeUnless { it.isEmpty() }
        } }
        cont.invokeOnCancellation { cancel(); release() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) {
                release()
                if (cont.isActive) cont.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                if (bufferBody) {
                    // 评估在可取消的挂起期间读完整份正文。取消钩子一直保留到body关闭，
                    // 外层协程取消会立即Call.cancel()，硬超时也覆盖停住/滴流的正文。
                    try {
                        val buffered = response.use {
                            val body = it.body
                            it.newBuilder().body(body?.bytes()?.toResponseBody(body.contentType())).build()
                        }
                        release()
                        if (cont.isActive) cont.resume(buffered) { _, _, _ -> buffered.close() }
                        else buffered.close()
                    } catch (error: Exception) {
                        release()
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                    return
                }
                val body = response.body
                val tracked = if (body == null) response else {
                    val source = object : okio.ForwardingSource(body.source()) {
                        override fun close() { try { super.close() } finally { release() } }
                    }.buffer()
                    response.newBuilder().body(object : okhttp3.ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = source
                    }).build()
                }
                if (body == null) release()
                if (cont.isActive) cont.resume(tracked) { _, _, _ -> tracked.close(); release() }
                else { tracked.close(); release() }
            }
        })
    }

    /** 取消指定会话标签下正在排队或执行的非流式模型请求。 */
    fun cancelRequests(requestTag: String) {
        val calls = (listOf(client) + proxyClients.values).flatMap {
            it.dispatcher.queuedCalls() + it.dispatcher.runningCalls()
        }.filter { it.request().tag(String::class.java) == requestTag } + taggedCalls[requestTag].orEmpty()
        calls.distinct().forEach { call -> runCatching { call.cancel() } }
    }

    fun cancelAllRequests() {
        activeHttpCalls.toList().forEach { runCatching { it.cancel() } }
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
        extra: Map<String, Any?> = emptyMap(),
        requiredContextTokens: Int? = null,
        requestTag: String? = null,
        roleQueueCoordinator: FailoverCoordinator? = null
    ): Flow<RealtimeEvent> = flow {
        if (roleQueueCoordinator != null) {
            characterQueueStream(models, messages, extra, requiredContextTokens, requestTag, roleQueueCoordinator).collect { emit(it) }
            return@flow
        }
        if (models.isEmpty()) {
            emit(RealtimeEvent.Error("无可用模型"))
            emit(RealtimeEvent.StreamEnd(null))
            return@flow
        }
        val failover = getFailoverState()
        val exclude = mutableSetOf<String>()
        var lastErrorMsg: String? = null
        val primaryModelId = models.firstOrNull()?.id

        emit(RealtimeEvent.StreamStart(null))

        for (attempt in models.indices) {
            val candidates = models.filter {
                it.id == primaryModelId || it.canHandleContext(requiredContextTokens)
            }
            if (candidates.isEmpty()) break
            val modelConfigs = candidates.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = candidates.firstOrNull { it.id == modelId } ?: candidates.firstOrNull { it.id !in exclude }
                ?: break
            exclude.add(model.id)
            val (runtimeModel, credential) = resolveRuntimeModel(model)

            // 标签与暂存原生思考均属于单次请求，备用模型不能继承失败请求的状态。
            if (attempt > 0 && extra[CHARACTER_THINKING_EXTRA] == true) {
                emit(RealtimeEvent.StreamStart(null))
            }
            val fullContent = StringBuilder()
            val fullThinking = StringBuilder()
            var terminal: LocalModelResponse? = null
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var cachedInputTokens: Int? = null
            var cacheReported = false
            var cacheWriteTokens: Int? = null
            var failed = false
            var httpCode = 0
            var contentReleased = false
            // 思考正文已在流式期间逐块转发（不参与拒答占位判定），结束时不再整段补发
            var thinkingReleased = false
            var streamErrorMessage: String? = null
            val markerState = ThinkingMarkerStreamState(ThinkingSourceSettings.current())
            var sawThinkingChunk = false
            var sawContentChunk = false

            suspend fun appendContentDelta(text: String) {
                if (text.isEmpty()) return
                fullContent.append(text)
                if (contentReleased) {
                    emit(RealtimeEvent.StreamChunk(text))
                } else if (!shouldBufferAssistantPrefix(fullContent.toString())) {
                    // 思考若已逐块转发，这里不再整段补发，避免 UI 重复累积
                    if (!thinkingReleased) {
                        fullThinking.takeIf { it.isNotEmpty() }?.let {
                            emit(RealtimeEvent.ReasoningChunk(it.toString()))
                        }
                    }
                    emit(RealtimeEvent.StreamChunk(fullContent.toString()))
                    contentReleased = true
                }
            }

            suspend fun deliverMarkerEvents(events: List<ExtractEvent>) {
                events.forEach { event ->
                    when (event) {
                        is ExtractEvent.Content -> appendContentDelta(event.text)
                        is ExtractEvent.Thinking -> {
                            if (event.text.isEmpty()) return@forEach
                            fullThinking.append(event.text)
                            // 思考正文到达即转发，不再等正文阈值放行：
                            // 思考内容不参与「短篇拒答占位」判定，实时展示不影响故障转移。
                            thinkingReleased = true
                            emit(RealtimeEvent.ReasoningChunk(event.text))
                        }
                    }
                }
            }

            suspend fun onStreamContent(chunk: String) {
                if (chunk.isEmpty()) return
                sawContentChunk = true
                val events = markerState.feedContent(chunk)
                deliverMarkerEvents(events)
            }

            suspend fun onStreamThinkingField(chunk: String) {
                if (chunk.isEmpty()) return
                sawThinkingChunk = true
                deliverMarkerEvents(markerState.feedThinkingField(chunk))
            }

            // 直接复用 chatStream 的内部逻辑（避免嵌套 Flow）
            val protocol = LocalProtocols.get(runtimeModel.protocol)
            val url = protocol.resolveUrl(
                runtimeModel.baseUrl,
                runtimeModel.model,
                runtimeModel.appendBaseUrlPath,
                stream = true,
                apiKey = runtimeModel.apiKey
            )
            val headers = mergeRuntimeHeaders(
                runtimeModel,
                protocol.buildHeaders(runtimeModel.apiKey, stream = true, endpoint = url),
                credential,
                requestTag
            )
            var response: Response? = null
            try {
                response = executeChatHttpRequest(
                    model = runtimeModel,
                    protocol = protocol,
                    url = url,
                    headers = headers,
                    messages = messages,
                    stream = true,
                    extra = extra
                )
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty().take(500)
                    httpCode = response.code
                    lastErrorMsg = "HTTP ${response.code}: $errBody"
                    failed = true
                    failover.recordFailure(model.id, httpCode)
                    LocalLogger.w(TAG, R.string.log_laic_stream_http_failover, model.name, httpCode)
                    response.close()
                    continue
                }
                val rawBuffered = parseBufferedJsonResponse(response, protocol)
                if (rawBuffered != null) {
                    val buffered = applyThinkingMarkerExtraction(rawBuffered)
                    if (buffered.content.isBlank() && buffered.toolCalls.isEmpty() &&
                        !(extra[CHARACTER_THINKING_EXTRA] == true && buffered.thinkingContent.isNotBlank())) {
                        lastErrorMsg = "模型返回了空响应"
                        failed = true
                        failover.recordFailure(model.id)
                        response.close()
                        continue
                    }
                    if (
                        buffered.toolCalls.isEmpty() &&
                        shouldFailoverForAssistantContent(buffered.content)
                    ) {
                        lastErrorMsg = "模型返回拒答占位内容，已触发故障转移"
                        failed = true
                        failover.recordFailure(model.id)
                        response.close()
                        continue
                    }
                    buffered.thinkingContent.takeIf(String::isNotBlank)?.let {
                        fullThinking.append(it)
                        emit(RealtimeEvent.ReasoningChunk(it))
                    }
                    buffered.content.takeIf(String::isNotBlank)?.let {
                        fullContent.append(it)
                        emit(RealtimeEvent.StreamChunk(it))
                    }
                    failover.recordSuccess(model.id)
                    buffered.usage.takeIf { it.isNotEmpty() }?.let { usage ->
                        val detail = usageMapToDetail(usage)
                        emit(
                            RealtimeEvent.Usage(
                                inputTokens = detail.promptTokens,
                                outputTokens = detail.completionTokens,
                                model = runtimeModel.model,
                                modelDisplayName = runtimeModel.name,
                                cachedInputTokens = detail.cachedPromptTokens,
                                cacheWriteTokens = detail.cacheWriteTokens,
                                cacheReported = detail.cacheReported
                            )
                        )
                    }
                    response.close()
                    emit(RealtimeEvent.StreamEnd(null))
                    return@flow
                }
                val src = response.body?.byteStream()
                if (src == null) {
                    lastErrorMsg = "响应体为空"
                    failed = true
                    response.close()
                    continue
                }
                val completion = LocalStreamCompletion(protocol)
                BufferedReader(InputStreamReader(src, Charsets.UTF_8)).use { reader ->
                    readSseEvents(reader) { data ->
                        completion.observe(data)
                        if (data == "[DONE]") return@readSseEvents false
                        protocol.parseStreamUsageDetail(data)?.let { detail ->
                            inputTokens = detail.promptTokens
                            outputTokens = detail.completionTokens
                            cachedInputTokens = detail.cachedPromptTokens
                            cacheWriteTokens = detail.cacheWriteTokens
                            cacheReported = cacheReported || detail.cacheReported
                        }
                        protocol.parseStreamError(data)?.let { message ->
                            streamErrorMessage = message
                            return@readSseEvents false
                        }
                        protocol.parseStreamThinkingChunk(data)?.takeIf(String::isNotEmpty)?.let {
                            onStreamThinkingField(it)
                        }
                        protocol.parseStreamFinalResponse(data)?.let { parsed ->
                            terminal = mergeStreamTerminalResponse(terminal, parsed)
                            parsed.thinkingContent
                                .takeIf { it.isNotBlank() && !sawThinkingChunk }
                                ?.let { onStreamThinkingField(it) }
                        }
                        protocol.parseStreamChunk(data)?.takeIf(String::isNotEmpty)?.let { chunk ->
                            onStreamContent(chunk)
                        }
                        true
                    }
                }
                val streamFailure = streamErrorMessage
                if (streamFailure != null) {
                    lastErrorMsg = streamFailure
                    failed = true
                    failover.recordFailure(model.id)
                    response.close()
                    continue
                }
                completion.requireComplete()
                terminal?.content
                    ?.takeIf { it.isNotBlank() && !sawContentChunk }
                    ?.let { onStreamContent(it) }
                deliverMarkerEvents(markerState.finish())
                if (fullContent.isEmpty() && !(extra[CHARACTER_THINKING_EXTRA] == true && fullThinking.isNotBlank())) {
                    lastErrorMsg = "模型返回了空响应"
                    failed = true
                    failover.recordFailure(model.id)
                    response.close()
                    continue
                }
                if (shouldFailoverForAssistantContent(fullContent.toString())) {
                    lastErrorMsg = "模型返回拒答占位内容，已触发故障转移"
                    failed = true
                    failover.recordFailure(model.id)
                    response.close()
                    continue
                }
                response.close()
                // 成功完成
                failover.recordSuccess(model.id)
                if (!contentReleased) {
                    // 思考正文已在流式期间逐块转发，这里只补发尚未转发的部分
                    if (!thinkingReleased) {
                        fullThinking.takeIf { it.isNotEmpty() }?.let {
                            emit(RealtimeEvent.ReasoningChunk(it.toString()))
                        }
                    }
                    emit(RealtimeEvent.StreamChunk(fullContent.toString()))
                }
                if (inputTokens != null || outputTokens != null) {
                    emit(
                        RealtimeEvent.Usage(
                            inputTokens = inputTokens ?: 0,
                            outputTokens = outputTokens ?: 0,
                            model = runtimeModel.model,
                            modelDisplayName = runtimeModel.name,
                            cachedInputTokens = cachedInputTokens ?: 0,
                            cacheWriteTokens = cacheWriteTokens ?: 0,
                            cacheReported = cacheReported
                        )
                    )
                }
                emit(RealtimeEvent.StreamEnd(null))
                return@flow
            } catch (e: Exception) {
                if (contentReleased) {
                    emit(RealtimeEvent.Error(e.message ?: "流式请求异常"))
                    emit(RealtimeEvent.StreamEnd(null))
                    response?.close()
                    return@flow
                }
                LocalLogger.w(TAG, R.string.log_laic_stream_exception_failover, model.name, e.message)
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
        val url = protocol.resolveUrl(
            model.baseUrl,
            model.model,
            model.appendBaseUrlPath,
            stream = false,
            apiKey = model.apiKey
        )
        val headers = protocol.buildHeaders(model.apiKey, stream = false, endpoint = url)
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
            LocalLogger.e(TAG, R.string.log_laic_describe_image_failed, e.message)
            throw e
        }
    }

    private fun buildVisionPayload(
        model: LocalAiModelEntity,
        protocol: LocalProtocol,
        imageUrl: String,
        question: String
    ): Map<String, Any> {
        if (protocol.name == GeminiNativeProtocol.name) {
            return protocol.buildPayload(
                model = model.model,
                messages = listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to question),
                            mapOf("type" to "image_url", "image_url" to mapOf("url" to imageUrl))
                        )
                    )
                ),
                stream = false,
                extra = mapOf("max_tokens" to (model.maxTokens ?: 1024))
            )
        }
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
        val provider = MultimodalProviderSupport.ttsProvider(model)
        return when (provider) {
            "elevenlabs", "deepgram", "siliconflow" -> NativeMediaProviders {
                clientFor(model).newCall(it).awaitResponse()
            }.speech(model, text, voice, speed, volume)
            "xiaomi", "mimo" -> synthesizeSpeechXiaomi(model, text, voice)
            "doubao", "volcengine", "bytedance" ->
                synthesizeSpeechDoubao(model, text, voice, speed, volume)
            "gemini", "google", "google_gemini" -> synthesizeSpeechGemini(model, text, voice)
            "glm", "zhipu", "bigmodel" -> synthesizeSpeechGlm(model, text, voice, speed, volume)
            "minimax" -> synthesizeSpeechMiniMax(model, text, voice, speed, pitch, volume)
            "qwen", "dashscope", "tongyi" -> synthesizeSpeechQwen(model, text, voice)
            else -> synthesizeSpeechOpenAi(model, text, voice, speed, pitch, volume)
        }
    }

    private suspend fun synthesizeSpeechGemini(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?
    ): ByteArray {
        val url = model.ttsUrl.takeIf { it.isNotBlank() }
            ?: MultimodalProviderSupport.endpoint(
                model.baseUrl,
                "/models/${model.ttsModel.ifBlank { model.model.ifBlank { "gemini-2.5-flash-preview-tts" } }}:generateContent",
                "https://generativelanguage.googleapis.com/v1beta/models/${model.ttsModel.ifBlank { model.model.ifBlank { "gemini-2.5-flash-preview-tts" } }}:generateContent"
            )
        val payload = mapOf(
            "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to text)))),
            "generationConfig" to mapOf(
                "responseModalities" to listOf("AUDIO"),
                "speechConfig" to mapOf(
                    "voiceConfig" to mapOf(
                        "prebuiltVoiceConfig" to mapOf(
                            "voiceName" to ((voiceOverride ?: model.ttsVoice).takeUnless { it.isBlank() || it == "default" }
                                ?: "Kore")
                        )
                    ),
                    "languageCode" to model.language.takeIf { it.isNotBlank() && it != "auto" }
                        ?.let { if (it == "zh") "cmn-CN" else it }
                )
            )
        )
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("x-goog-api-key", model.apiKey)
            .header("Content-Type", "application/json")
            .build()
        return executeTtsRequest(model, request, "Gemini").let { MultimodalProviderSupport.pcmToWav(it) }
    }

    private suspend fun synthesizeSpeechGlm(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?,
        speedOverride: Float?,
        volumeOverride: Float?
    ): ByteArray {
        val url = if (model.ttsUrl.isNotBlank()) {
            model.ttsUrl
        } else {
            val base = model.baseUrl.trimEnd('/')
            if (base.isNotBlank()) "$base/audio/speech"
            else "https://open.bigmodel.cn/api/paas/v4/audio/speech"
        }
        val payload = linkedMapOf<String, Any>(
            "model" to model.ttsModel.ifBlank { model.model.ifBlank { "glm-tts" } },
            "input" to text,
            "voice" to ((voiceOverride ?: model.ttsVoice).takeUnless { it.isBlank() || it == "default" } ?: "tongtong"),
            "response_format" to model.ttsFormat.ifBlank { "wav" },
            "speed" to (speedOverride ?: model.ttsSpeed),
            "volume" to (volumeOverride ?: model.ttsVolume)
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return executeTtsRequest(model, request, "GLM")
    }

    private suspend fun synthesizeSpeechMiniMax(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?,
        speedOverride: Float?,
        pitchOverride: Float?,
        volumeOverride: Float?
    ): ByteArray {
        val url = model.ttsUrl.takeIf { it.isNotBlank() }
            ?: MultimodalProviderSupport.endpoint(
                model.baseUrl,
                "/t2a_v2",
                "https://api.minimaxi.com/v1/t2a_v2"
            )
        val payload = mapOf(
            "model" to model.ttsModel.ifBlank { model.model.ifBlank { "speech-2.8-hd" } },
            "text" to text,
            "stream" to false,
            "output_format" to "hex",
            "voice_setting" to mapOf(
                "voice_id" to ((voiceOverride ?: model.ttsVoice).takeUnless { it.isBlank() || it == "default" } ?: "male-qn-qingse"),
                "speed" to (speedOverride ?: model.ttsSpeed),
                "vol" to (volumeOverride ?: model.ttsVolume),
                "pitch" to (((pitchOverride ?: model.ttsPitch) - 1f) * 12).toInt().coerceIn(-12, 12)
            ),
            "audio_setting" to mapOf(
                "sample_rate" to 32_000,
                "bitrate" to 128_000,
                "format" to model.ttsFormat.ifBlank { "mp3" },
                "channel" to 1
            )
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return executeTtsRequest(model, request, "MiniMax")
    }

    private suspend fun synthesizeSpeechQwen(
        model: LocalAiModelEntity,
        text: String,
        voiceOverride: String?
    ): ByteArray {
        val url = model.ttsUrl.takeIf { it.isNotBlank() }
            ?: MultimodalProviderSupport.dashscopeEndpoint(
                model.baseUrl,
                "/services/aigc/multimodal-generation/generation",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
            )
        val language = when (model.language.lowercase()) {
            "zh" -> "Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            else -> "Auto"
        }
        val payload = mapOf(
            "model" to model.ttsModel.ifBlank { model.model.ifBlank { "qwen3-tts-flash" } },
            "input" to mapOf(
                "text" to text,
                "voice" to ((voiceOverride ?: model.ttsVoice).takeUnless { it.isBlank() || it == "default" } ?: "Cherry"),
                "language_type" to language
            )
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return executeTtsRequest(model, request, "Qwen")
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

    /** 上传兼容 SiliconFlow 的自定义音色；仅在提供商支持时由试验场开放。 */
    suspend fun uploadTtsVoice(
        model: LocalAiModelEntity,
        file: MultipartBody.Part,
        customName: okhttp3.RequestBody,
        text: okhttp3.RequestBody
    ): String {
        val url = model.ttsUploadUrl.ifBlank {
            "${model.baseUrl.trimEnd('/')}/uploads/audio/voice"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(file)
            .addFormDataPart("customName", null, customName)
            .addFormDataPart("text", null, text)
            .addFormDataPart("model", model.ttsModel.ifBlank { model.model })
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${model.apiKey}")
            .post(body).build()
        return clientFor(model).newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "TTS HTTP ${response.code}: ${raw.take(500)}")
            }
            val json = JsonParser.parseString(raw).asJsonObject
            sequenceOf("uri", "voice_id", "id")
                .mapNotNull { json.get(it)?.takeUnless { value -> value.isJsonNull }?.asString }
                .firstOrNull { it.isNotBlank() }
                ?: throw IllegalStateException("TTS 服务未返回音色 ID")
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
            json.getAsJsonObject("base_resp")?.let { status ->
                if (status.get("status_code")?.asInt?.let { it != 0 } == true) {
                    error("$provider TTS: ${status.get("status_msg")?.asString.orEmpty()}")
                }
            }
            val audioUrl = sequenceOf(
                json.get("audio_url"),
                json.get("url"),
                json.get("output")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("audio")?.takeIf { it.isJsonObject }?.asJsonObject?.get("url"),
                json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("audio")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
            ).firstNotNullOfOrNull { it?.takeIf { value -> !value.isJsonNull }?.asString }
            val audioData = sequenceOf(
                json.get("data"),
                json.get("audio"),
                json.get("output")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("audio")?.takeIf { it.isJsonObject }?.asJsonObject?.get("data"),
                json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("audio")
            ).firstNotNullOfOrNull { it?.takeIf { value -> value.isJsonPrimitive }?.asString }
                ?: json.getAsJsonArray("candidates")
                    ?.flatMap { candidate ->
                        candidate.asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")
                            ?.mapNotNull { part ->
                                val inline = part.asJsonObject.getAsJsonObject("inlineData")
                                    ?: part.asJsonObject.getAsJsonObject("inline_data")
                                inline?.get("data")?.takeIf { !it.isJsonNull }?.asString
                            }
                            .orEmpty()
                    }
                    ?.firstOrNull()
                ?: json.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
                    ?.takeIf { it.isJsonObject }?.asJsonObject?.let { choice ->
                        val message = choice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                        val delta = choice.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                        sequenceOf(message, delta).filterNotNull().firstNotNullOfOrNull { item ->
                            sequenceOf(item.get("audio"), item.get("content"))
                                .firstOrNull { value -> value?.isJsonPrimitive == true }
                                ?.asString
                        }
                    }
            when {
                !audioUrl.isNullOrBlank() -> downloadAudio(model, audioUrl)
                !audioData.isNullOrBlank() && provider == "MiniMax" -> {
                    if (audioData.startsWith("https://") || audioData.startsWith("http://")) downloadAudio(model, audioData)
                    else decodeMiniMaxAudioHex(audioData)
                }
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
        val provider = MultimodalProviderSupport.sttProvider(model)
        return when (provider) {
            "elevenlabs", "deepgram" -> NativeMediaProviders {
                clientFor(model).newCall(it).awaitResponse()
            }.transcribe(model, audioBytes, filename, language)
            "groq", "siliconflow", "glm" -> transcribeSpeechOpenAI(
                model.copy(baseUrl = model.baseUrl.ifBlank {
                    when (provider) {
                        "groq" -> "https://api.groq.com/openai/v1"
                        "glm" -> "https://open.bigmodel.cn/api/paas/v4"
                        else -> "https://api.siliconflow.cn/v1"
                    }
                }, sttModel = model.sttModel.ifBlank { model.model.ifBlank {
                    when (provider) {
                        "groq" -> "whisper-large-v3-turbo"
                        "glm" -> "glm-asr-2512"
                        else -> "FunAudioLLM/SenseVoiceSmall"
                    }
                } }), audioBytes, filename, language
            )
            "xiaomi", "mimo" -> transcribeSpeechXiaomi(model, audioBytes, filename, language)
            "gemini", "google", "google_gemini" -> transcribeSpeechGemini(model, audioBytes, filename, language)
            "qwen", "dashscope", "tongyi" -> transcribeSpeechQwen(model, audioBytes, filename, language)
            else -> transcribeSpeechOpenAI(model, audioBytes, filename, language)
        }
    }

    private suspend fun transcribeSpeechGemini(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String,
        language: String?
    ): LocalAiResult {
        val modelName = model.sttModel.ifBlank { model.model.ifBlank { "gemini-2.5-flash" } }
        val url = model.sttUrl.takeIf { it.isNotBlank() }
            ?: MultimodalProviderSupport.endpoint(
                model.baseUrl,
                "/models/$modelName:generateContent",
                "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            )
        val languageHint = language?.takeIf { it.isNotBlank() && it != "auto" }?.let { " in $it" }.orEmpty()
        val payload = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(
                        mapOf("text" to "请准确转录这段音频，只输出转录文本，不要解释。$languageHint"),
                        mapOf(
                            "inlineData" to mapOf(
                                "mimeType" to guessAudioMediaType(filename),
                                "data" to Base64.getEncoder().encodeToString(audioBytes)
                            )
                        )
                    )
                )
            )
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
            .header("x-goog-api-key", model.apiKey)
            .header("Content-Type", "application/json")
            .build()
        return executeTextRequest(model, request, "Gemini STT")
    }

    private suspend fun transcribeSpeechQwen(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String,
        language: String?
    ): LocalAiResult {
        val url = model.sttUrl.takeIf { it.isNotBlank() }
            ?: MultimodalProviderSupport.endpoint(
                model.baseUrl.replace("/api/v1", "/compatible-mode/v1"),
                "/chat/completions",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model.appendBaseUrlPath
            )
        val format = filename.substringAfterLast('.', "wav").lowercase()
        val audioData = Base64.getEncoder().encodeToString(audioBytes)
        val inputAudio = mapOf(
            "data" to "data:${guessAudioMediaType(filename)};base64,$audioData",
            "format" to format
        )
        val content = mutableListOf<Map<String, Any>>(mapOf("type" to "input_audio", "input_audio" to inputAudio))
        val body = linkedMapOf<String, Any>(
            "model" to model.sttModel.ifBlank { model.model.ifBlank { "qwen3-asr-flash" } },
            "messages" to listOf(mapOf("role" to "user", "content" to content)),
            "stream" to false
        )
        language?.takeIf { it.isNotBlank() && it != "auto" }?.let { body["asr_options"] = mapOf("language" to it) }
        val request = Request.Builder().url(url)
            .post(gson.toJson(body).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return executeTextRequest(model, request, "Qwen STT")
    }

    private suspend fun executeTextRequest(
        model: LocalAiModelEntity,
        request: Request,
        provider: String
    ): LocalAiResult = clientFor(model).newCall(request).awaitResponse().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw FailoverHttpException(response.code, "$provider HTTP ${response.code}: ${raw.take(500)}")
        }
        val text = runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            root.get("text")?.takeIf { !it.isJsonNull }?.asString
                ?: root.getAsJsonArray("candidates")?.firstOrNull()
                    ?.asJsonObject?.getAsJsonObject("content")?.getAsJsonArray("parts")
                    ?.mapNotNull { it.asJsonObject.get("text")?.takeIf { value -> !value.isJsonNull }?.asString }
                    ?.joinToString("")
                ?: root.getAsJsonArray("choices")?.firstOrNull()
                    ?.asJsonObject?.getAsJsonObject("message")?.get("content")
                    ?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull().orEmpty().ifBlank { raw.trim() }
        LocalAiResult(text, usedModelId = model.id, usedModelName = model.name, usedModelActualName = model.model)
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
        val url = model.sttUrl.takeIf { it.isNotBlank() }
            ?: if (MultimodalProviderSupport.sttProvider(model) == "glm") {
                MultimodalProviderSupport.endpoint(model.baseUrl, "/audio/transcriptions",
                    "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions", model.appendBaseUrlPath)
            } else resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/transcriptions")
        val audioMediaType = guessAudioMediaType(filename).toMediaType()
        val sttModel = model.sttModel.ifBlank { model.model }
        LocalLogger.i(TAG, R.string.log_laic_stt_openai_request, url, model.model, filename, audioBytes.size, audioMediaType)
        val audioPart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", sttModel)
            .addFormDataPart("file", filename, audioBytes.toRequestBody(audioMediaType))
            .apply {
                if (!language.isNullOrBlank() && !language.equals("auto", true) &&
                    MultimodalProviderSupport.sttProvider(model) !in setOf("siliconflow", "glm")) {
                    addFormDataPart("language", language)
                }
                if (MultimodalProviderSupport.sttProvider(model) == "glm") addFormDataPart("stream", "false")
            }
            .build()
        val reqBuilder = Request.Builder().url(url).post(audioPart)
            .header("Authorization", "Bearer ${model.apiKey}")
        parseTtsHeaders(model.sttHeaders).forEach { (name, value) -> reqBuilder.header(name, value) }
        val req = reqBuilder.build()
        return try {
            clientFor(model).newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    LocalLogger.e(TAG, R.string.log_laic_stt_openai_failed, resp.code, url, err)
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
            LocalLogger.e(TAG, R.string.log_laic_stt_openai_error, e.message)
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
                LocalLogger.w(TAG, R.string.log_laic_stt_xiaomi_transcode_failed, ext, e.message)
                Triple(audioBytes, filename, guessAudioMediaType(filename))
            }
        }

        val b64 = Base64.getEncoder().encodeToString(effectiveBytes)
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

        LocalLogger.i(TAG, R.string.log_laic_stt_xiaomi_request, url, model.model, filename, effectiveFilename, effectiveBytes.size, effectiveMime, lang)

        val req = Request.Builder().url(url).post(jsonBody)
            .header("api-key", model.apiKey)
            .header("Content-Type", "application/json")
            .build()
        return try {
            clientFor(model).newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    LocalLogger.e(TAG, R.string.log_laic_stt_xiaomi_failed, resp.code, url, err)
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
            LocalLogger.e(TAG, R.string.log_laic_stt_xiaomi_error, e.message)
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
        n: Int = 1,
        referenceImage: ImageGenerationReference? = null
    ): List<GeneratedImage> {
        val images = generateImage(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            modelName = model.model,
            prompt = prompt,
            additionalPrompt = model.promptTemplate,
            size = size,
            n = n,
            referenceImage = referenceImage,
            appendBaseUrlPath = model.appendBaseUrlPath,
            proxyUrl = model.proxyUrl,
            provider = MultimodalProviderSupport.imageProvider(model)
        )
        return images.map { image ->
            val url = image.url
            if (image.bytes != null || url.isNullOrBlank()) {
                image
            } else {
                val bytes = try {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("下载生成图片失败", e)
                }
                image.copy(bytes = bytes)
            }
        }
    }

    suspend fun downloadGeneratedImage(url: String, proxyUrl: String = ""): ByteArray =
        clientFor(proxyUrl).newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw FailoverHttpException(response.code, "图片下载 HTTP ${response.code}")
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("图片下载返回空内容")
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
        val dimensions = requestedEmbeddingDimensions(runtimeModel)
        if (dimensions != null) payload["dimensions"] = dimensions
        val headers = mergeRuntimeHeaders(
            runtimeModel,
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
            require(vectors.all { vector ->
                vector.isNotEmpty() && vector.size <= MAX_EMBEDDING_DIMENSIONS &&
                    vector.all { it.isFinite() } && vector.any { it != 0f }
            }) { "Embedding 返回了无效向量" }
            require(dimensions == null || vectors.all { it.size == dimensions }) {
                "Embedding 返回维度与请求不一致：期望 $dimensions，实际 ${vectors.map { it.size }.distinct()}"
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
        additionalPrompt: String = "",
        size: String = "1024x1024",
        n: Int = 1,
        appendBaseUrlPath: Boolean = true,
        proxyUrl: String = "",
        provider: String = "",
        referenceImage: ImageGenerationReference? = null
    ): List<GeneratedImage> {
        val finalPrompt = appendImagePrompt(prompt, additionalPrompt)
        val normalizedProvider = MultimodalProviderSupport.normalize(provider)
        require(referenceImage == null || MultimodalProviderSupport.supportsImageReference(provider, modelName)) {
            com.nekobot.app.ServiceContainer.getString(R.string.playground_reference_unsupported)
                .ifBlank { "当前图片生成接口不支持参考图" }
        }
        // 原生单图接口按用户要求串行生成，避免数量选项被静默忽略。
        if (n > 1 && normalizedProvider in setOf("gemini", "google", "google_gemini", "minimax")) {
            return buildList {
                repeat(n) {
                    addAll(generateImage(baseUrl, apiKey, modelName, prompt, additionalPrompt, size, 1,
                        appendBaseUrlPath, proxyUrl, provider, referenceImage))
                }
            }
        }
        when (normalizedProvider) {
            "siliconflow", "grok", "glm", "stability" -> return NativeMediaProviders {
                clientFor(proxyUrl).newCall(it).awaitResponse()
            }.images(normalizedProvider, baseUrl, apiKey, modelName, finalPrompt, size, n, appendBaseUrlPath, referenceImage)
            "gemini", "google", "google_gemini" -> return generateImageGemini(
                baseUrl, apiKey, modelName, finalPrompt, appendBaseUrlPath, proxyUrl, referenceImage
            )
            "minimax" -> return generateImageMiniMax(
                baseUrl, apiKey, modelName, finalPrompt, size, appendBaseUrlPath, proxyUrl
            )
            "qwen", "dashscope", "tongyi" -> {
                if (modelName.trim().equals(QWEN_IMAGE_3_MODEL, ignoreCase = true)) {
                    return generateImageQwenImage3(
                        baseUrl, apiKey, modelName, finalPrompt, n, appendBaseUrlPath, proxyUrl,
                        referenceImage
                    )
                }
                return generateImageQwenLegacy(
                    baseUrl, apiKey, modelName, finalPrompt, size, n, appendBaseUrlPath, proxyUrl
                )
            }
            "doubao", "seed", "seedream", "volcengine", "ark", "volces" -> return generateImageDoubao(
                baseUrl, apiKey, modelName, finalPrompt, size, n, appendBaseUrlPath, proxyUrl, referenceImage
            )
        }
        if (referenceImage != null) {
            return generateImageEditOpenAi(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelName = modelName,
                prompt = finalPrompt,
                size = size,
                n = n,
                appendBaseUrlPath = appendBaseUrlPath,
                proxyUrl = proxyUrl,
                referenceImage = referenceImage
            )
        }
        val url = resolveImageUrl(baseUrl, appendBaseUrlPath)
        val payload = mapOf(
            "model" to modelName,
            "prompt" to finalPrompt,
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
                        GeneratedImage(url = null, bytes = Base64.getDecoder().decode(b64))
                    } else null
                }
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalLogger.e(TAG, R.string.log_laic_generate_image_failed, e.message)
            throw e
        }
    }

    /** 将模型配置的附加提示词置于用户图片描述之后。 */
    private fun appendImagePrompt(prompt: String, additionalPrompt: String): String =
        listOf(prompt.trim(), additionalPrompt.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private suspend fun generateImageGemini(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String,
        referenceImage: ImageGenerationReference? = null
    ): List<GeneratedImage> {
        val model = modelName.ifBlank { "gemini-3.1-flash-image" }
        val url = MultimodalProviderSupport.endpoint(
            baseUrl,
            "/models/$model:generateContent",
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            appendBaseUrlPath
        )
        val parts = buildList<Map<String, Any>> {
            add(mapOf("text" to prompt))
            referenceImage?.let { reference ->
                add(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to reference.mimeType,
                            "data" to Base64.getEncoder().encodeToString(reference.bytes)
                        )
                    )
                )
            }
        }
        val body = mapOf(
            "contents" to listOf(mapOf("parts" to parts)),
            "generationConfig" to mapOf("responseModalities" to listOf("IMAGE"))
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(body).toRequestBody(JSON_TYPE))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .build()
        return executeImageJsonRequest(request, proxyUrl, "Gemini image")
    }

    /** OpenAI 兼容图生图端点，使用 multipart /images/edits。 */
    private suspend fun generateImageEditOpenAi(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String,
        n: Int,
        appendBaseUrlPath: Boolean,
        proxyUrl: String,
        referenceImage: ImageGenerationReference
    ): List<GeneratedImage> {
        val imageType = referenceImage.mimeType.toMediaTypeOrNull() ?: "image/png".toMediaType()
        val extension = referenceImage.mimeType.substringAfter('/', "png")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "png"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", modelName)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("size", size)
            .addFormDataPart("n", n.toString())
            .addFormDataPart(
                "image",
                "character-reference.$extension",
                referenceImage.bytes.toRequestBody(imageType)
            )
            .build()
        val request = Request.Builder()
            .url(resolveImageEditUrl(baseUrl, appendBaseUrlPath))
            .post(body)
            .header("Authorization", "Bearer $apiKey")
            .build()
        return clientFor(proxyUrl).newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "Image edit HTTP ${response.code}: ${raw.take(500)}")
            }
            parseImageJson(
                runCatching { JsonParser.parseString(raw).asJsonObject }
                    .getOrElse { throw IllegalStateException("图生图返回了无法解析的 JSON") }
            )
        }
    }

    private suspend fun generateImageMiniMax(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String
    ): List<GeneratedImage> {
        val url = MultimodalProviderSupport.endpoint(
            baseUrl,
            "/image_generation",
            "https://api.minimaxi.com/v1/image_generation",
            appendBaseUrlPath
        )
        val body = mapOf(
            "model" to modelName.ifBlank { "image-01" },
            "prompt" to prompt,
            "aspect_ratio" to MultimodalProviderSupport.imageAspectRatio(size),
            "response_format" to "base64"
        )
        val request = Request.Builder().url(url)
            .post(gson.toJson(body).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        return executeImageJsonRequest(request, proxyUrl, "MiniMax image")
    }

    private suspend fun generateImageDoubao(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String,
        n: Int,
        appendBaseUrlPath: Boolean,
        proxyUrl: String,
        referenceImage: ImageGenerationReference? = null
    ): List<GeneratedImage> {
        val url = MultimodalProviderSupport.endpoint(
            baseUrl,
            "/images/generations",
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
            appendBaseUrlPath
        )
        val normalizedSize = when (size.uppercase()) {
            "2K", "4K" -> size.uppercase()
            else -> if (size.contains("x")) "2K" else size
        }
        val body = linkedMapOf<String, Any>(
            "model" to modelName,
            "prompt" to prompt,
            "size" to normalizedSize,
            "sequential_image_generation" to "disabled",
            "stream" to false,
            "response_format" to "url",
            "watermark" to false
        )
        referenceImage?.let {
            body["image"] = "data:${it.mimeType};base64," + Base64.getEncoder().encodeToString(it.bytes)
        }
        val request = Request.Builder().url(url)
            .post(gson.toJson(body).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        return buildList {
            repeat(n.coerceIn(1, 4)) {
                addAll(executeImageJsonRequest(request, proxyUrl, "豆包 Seed image"))
            }
        }
    }

    /** Qwen Image 3.0 使用同步多模态生成端点，不走旧版异步文生图任务接口。 */
    private suspend fun generateImageQwenImage3(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        n: Int,
        appendBaseUrlPath: Boolean,
        proxyUrl: String,
        referenceImage: ImageGenerationReference? = null
    ): List<GeneratedImage> {
        val url = MultimodalProviderSupport.dashscopeEndpoint(
            baseUrl,
            "/services/aigc/multimodal-generation/generation",
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            appendBaseUrlPath
        )
        val payload = mapOf(
            "model" to modelName.ifBlank { QWEN_IMAGE_3_MODEL },
            "input" to mapOf(
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to buildList<Map<String, String>> {
                            referenceImage?.let {
                                add(
                                    mapOf(
                                        "image" to "data:${it.mimeType};base64," +
                                            Base64.getEncoder().encodeToString(it.bytes)
                                    )
                                )
                            }
                            add(mapOf("text" to prompt))
                        }
                    )
                )
            ),
            "parameters" to mapOf("prompt_extend" to true)
        )
        return buildList {
            repeat(n.coerceIn(1, 4)) {
                val request = Request.Builder().url(url)
                    .post(gson.toJson(payload).toRequestBody(JSON_TYPE))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()
                val images = parseQwenImage3Json(
                    executeJson(request, proxyUrl, "Qwen Image 3.0")
                )
                if (images.isEmpty()) {
                    throw IllegalStateException("Qwen Image 3.0 未返回图片")
                }
                addAll(images)
            }
        }
    }

    private suspend fun generateImageQwenLegacy(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String,
        n: Int,
        appendBaseUrlPath: Boolean,
        proxyUrl: String
    ): List<GeneratedImage> {
        val submitUrl = MultimodalProviderSupport.dashscopeEndpoint(
            baseUrl,
            "/services/aigc/text2image/image-synthesis",
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
            appendBaseUrlPath
        )
        val body = mapOf(
            "model" to modelName,
            "input" to mapOf("prompt" to prompt),
            "parameters" to mapOf(
                "size" to size.replace('x', '*'),
                "n" to n.coerceIn(1, 4),
                "watermark" to false
            )
        )
        val request = Request.Builder().url(submitUrl)
            .post(gson.toJson(body).toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
            .header("Content-Type", "application/json")
            .build()
        val task = executeJson(request, proxyUrl, "Qwen image submit")
        val taskId = task.getAsJsonObject("output")?.get("task_id")?.asString
            ?: throw IllegalStateException("Qwen image 未返回 task_id")
        val base = baseUrl.trim().trimEnd('/').replace("/compatible-mode/v1", "/api/v1")
            .ifBlank { "https://dashscope.aliyuncs.com/api/v1" }
        val taskUrl = task.getAsJsonObject("output")?.get("task_url")?.asString
            ?: "$base/tasks/$taskId"
        repeat(90) {
            delay(2_000)
            val pollRequest = Request.Builder().url(taskUrl)
                .get()
                .header("Authorization", "Bearer $apiKey")
                .build()
            val result = executeJson(pollRequest, proxyUrl, "Qwen image query")
            val output = result.getAsJsonObject("output") ?: return@repeat
            when (output.get("task_status")?.asString?.uppercase()) {
                "SUCCEEDED" -> return parseImageJson(output)
                "FAILED", "CANCELED", "UNKNOWN" ->
                    throw IllegalStateException("Qwen image 任务失败: ${output.get("message")?.asString.orEmpty()}")
            }
        }
        throw IllegalStateException("Qwen image 任务轮询超时")
    }

    private fun parseQwenImage3Json(root: com.google.gson.JsonObject): List<GeneratedImage> {
        val images = mutableListOf<GeneratedImage>()
        root.getAsJsonObject("output")
            ?.getAsJsonArray("choices")
            ?.forEach { choice ->
                val content = choice.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")
                when {
                    content?.isJsonArray == true -> content.asJsonArray.forEach { part ->
                        appendQwenImagePart(images, part)
                    }
                    content?.isJsonObject == true -> appendQwenImagePart(images, content)
                }
            }
        return images
    }

    private fun appendQwenImagePart(
        images: MutableList<GeneratedImage>,
        part: com.google.gson.JsonElement
    ) {
        val objectPart = part.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val image = objectPart.get("image") ?: objectPart.get("image_url") ?: return
        when {
            image.isJsonPrimitive && !image.isJsonNull -> {
                images += GeneratedImage(url = image.asString, bytes = null)
            }
            image.isJsonObject -> {
                val imageObject = image.asJsonObject
                val url = imageObject.get("url")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?: imageObject.get("image_url")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                val base64 = imageObject.get("b64_json")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                when {
                    !url.isNullOrBlank() -> images += GeneratedImage(url = url, bytes = null)
                    !base64.isNullOrBlank() -> images += GeneratedImage(
                        url = null,
                        bytes = Base64.getDecoder().decode(base64)
                    )
                }
            }
        }
    }

    private suspend fun executeImageJsonRequest(
        request: Request,
        proxyUrl: String,
        provider: String
    ): List<GeneratedImage> = parseImageJson(executeJson(request, proxyUrl, provider))

    private suspend fun executeJson(request: Request, proxyUrl: String, provider: String): com.google.gson.JsonObject =
        clientFor(proxyUrl).newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FailoverHttpException(response.code, "$provider HTTP ${response.code}: ${raw.take(500)}")
            }
            runCatching { JsonParser.parseString(raw).asJsonObject }
                .getOrElse { throw IllegalStateException("$provider 返回了无法解析的 JSON") }
        }

    private fun parseImageJson(root: com.google.gson.JsonObject): List<GeneratedImage> {
        val result = mutableListOf<GeneratedImage>()
        root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
            val b64 = obj.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
            when {
                !url.isNullOrBlank() -> result += GeneratedImage(url, null)
                !b64.isNullOrBlank() -> result += GeneratedImage(null, Base64.getDecoder().decode(b64))
            }
        }
        root.get("images")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
            val b64 = obj.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
            when {
                !url.isNullOrBlank() -> result += GeneratedImage(url, null)
                !b64.isNullOrBlank() -> result += GeneratedImage(null, Base64.getDecoder().decode(b64))
            }
        }
        root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("image_base64")?.forEach { value ->
            if (!value.isJsonNull) result += GeneratedImage(null, Base64.getDecoder().decode(value.asString))
        }
        root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            obj.get("url")?.takeIf { !it.isJsonNull }?.asString?.let { result += GeneratedImage(it, null) }
        }
        root.getAsJsonArray("candidates")?.forEach { candidate ->
            candidate.asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { part ->
                val inline = part.asJsonObject.getAsJsonObject("inlineData")
                    ?: part.asJsonObject.getAsJsonObject("inline_data")
                inline?.get("data")?.takeIf { !it.isJsonNull }?.let {
                    result += GeneratedImage(null, Base64.getDecoder().decode(it.asString))
                }
            }
        }
        return result
    }

    private fun clientFor(model: LocalAiModelEntity): OkHttpClient =
        clientFor(model.proxyUrl)

    private fun LocalAiModelEntity.canHandleContext(requiredContextTokens: Int?): Boolean {
        val limit = maxContextLength ?: return true
        return requiredContextTokens == null || limit <= 0 || limit >= requiredContextTokens
    }

    private fun clientFor(proxyUrl: String): OkHttpClient {
        val normalized = proxyUrl.trim()
        if (normalized.isEmpty()) return client
        return proxyClients.getOrPut(normalized) {
            client.withModelProxy(normalized)
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OPENAI_COMPATIBILITY_PROTOCOLS = setOf(
            OpenAIChatProtocol.name,
            OpenAIResponsesProtocol.name
        )
        private val OPENAI_OPTIONAL_TUNING_KEYS = setOf(
            "temperature",
            "max_tokens",
            "top_p",
            "reasoning_effort"
        )

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 流式可能较慢
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

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

        /** 解析 OpenAI 兼容图生图端点 URL（/images/edits）。 */
        private fun resolveImageEditUrl(baseUrl: String, appendBaseUrlPath: Boolean): String {
            val base = baseUrl.trimEnd('/')
            if (base.contains("/images/edits")) return base
            if (base.contains("/images/generations")) {
                return base.replace("/images/generations", "/images/edits")
            }
            if (base.contains("/chat/completions")) {
                return base.replace("/chat/completions", "/images/edits")
            }
            if (base.contains("/chatcompletion")) {
                return base.replace("/chatcompletion", "/images/edits")
            }
            if (!appendBaseUrlPath) return base
            if (base.endsWith("/v1")) return "$base/images/edits"
            return "$base/v1/images/edits"
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
