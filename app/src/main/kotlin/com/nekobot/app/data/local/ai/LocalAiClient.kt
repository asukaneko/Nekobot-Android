package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
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
import java.io.InputStreamReader
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
    val usedModelName: String? = null
)

/** 图片生成结果：url 或 bytes 二选一 */
data class GeneratedImage(
    val url: String?,
    val bytes: ByteArray?
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
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
        val headers = protocol.buildHeaders(model.apiKey, stream = true)
        val payload = protocol.buildPayload(model.model, messages, stream = true, extra = extra)
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)

        val reqBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        emit(RealtimeEvent.StreamStart(null))

        val fullContent = StringBuilder()
        var response: Response? = null
        try {
            response = client.newCall(reqBuilder.build()).execute()
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
                        emit(RealtimeEvent.Usage(input, output, model.model))
                    }
                    val chunk = protocol.parseStreamChunk(data) ?: continue
                    if (chunk.isNotEmpty()) {
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
        extra: Map<String, Any?> = emptyMap()
    ): LocalAiResult {
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
        val headers = protocol.buildHeaders(model.apiKey, stream = false)
        val payload = protocol.buildPayload(model.model, messages, stream = false, extra = extra)
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)

        val reqBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return try {
            client.newCall(reqBuilder.build()).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $errBody")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val (content, usage) = protocol.parseNonStreamResponse(data)
                LocalAiResult(content, usage, usedModelId = model.id, usedModelName = model.name)
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

    /**
     * 测试模型连通性，返回成功/失败 + 提示。
     * 捕获 [FailoverHttpException] 等异常，转换为 [LocalAiResult]。
     */
    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult {
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

    // ==================== 带故障转移的调用 ====================

    /**
     * 带故障转移的非流式聊天：按 [models] 顺序尝试，遇到错误自动切换下一个。
     * 兼容旧 API：捕获 [FailoverHttpException] 转换为 [LocalAiResult.error]。
     * 新代码应通过 [FailoverCoordinator] + [chatOnce] 实现。
     */
    suspend fun chatOnceWithFailover(
        models: List<LocalAiModelEntity>,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap()
    ): LocalAiResult {
        if (models.isEmpty()) return LocalAiResult("", error = "无可用模型")
        val failover = getFailoverState()
        val exclude = mutableSetOf<String>()
        var lastError: LocalAiResult? = null
        for (i in models.indices) {
            val modelConfigs = models.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = models.firstOrNull { it.id == modelId } ?: models[i]
            exclude.add(model.id)

            try {
                val result = chatOnce(model, messages, extra)
                failover.recordSuccess(model.id)
                return result.copy(usedModelId = model.id, usedModelName = model.name)
            } catch (e: FailoverHttpException) {
                failover.recordFailure(model.id, e.statusCode)
                Log.w("LocalAiClient", "模型 ${model.name} 调用失败 (HTTP ${e.statusCode})，尝试下一个: ${e.message?.take(120)}")
                lastError = LocalAiResult("", error = e.message, statusCode = e.statusCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = extractStatusCode(e)
                failover.recordFailure(model.id, code)
                Log.w("LocalAiClient", "模型 ${model.name} 调用异常，尝试下一个: ${e.message?.take(120)}")
                lastError = LocalAiResult("", error = e.message ?: "请求异常", statusCode = code)
            }
        }
        return lastError ?: LocalAiResult("", error = "所有模型均不可用")
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

            val fullContent = StringBuilder()
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var failed = false
            var httpCode = 0

            // 直接复用 chatStream 的内部逻辑（避免嵌套 Flow）
            val protocol = LocalProtocols.get(model.protocol)
            val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
            val headers = protocol.buildHeaders(model.apiKey, stream = true)
            val payload = protocol.buildPayload(model.model, messages, stream = true, extra = extra)
            val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
            val reqBuilder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            var response: Response? = null
            try {
                response = client.newCall(reqBuilder.build()).execute()
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
                        val chunk = protocol.parseStreamChunk(data) ?: continue
                        if (chunk.isNotEmpty()) {
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
                    emit(RealtimeEvent.Usage(inputTokens ?: 0, outputTokens ?: 0, model.model))
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
        question: String = "请描述这张图片的内容。"
    ): LocalAiResult {
        val protocol = LocalProtocols.get(model.protocol)
        val url = protocol.resolveUrl(model.baseUrl, model.model, model.appendBaseUrlPath)
        val headers = protocol.buildHeaders(model.apiKey, stream = false)
        // vision 使用 chat/completions 端点，消息体包含 image_url
        val payload = mapOf(
            "model" to model.model,
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
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
        val reqBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        return try {
            client.newCall(reqBuilder.build()).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $err")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val choices = data["choices"] as? List<Map<String, Any>> ?: emptyList()
                val msg = choices.firstOrNull()?.get("message") as? Map<String, Any>
                val content = (msg?.get("content") as? String).orEmpty()
                LocalAiResult(content, usedModelId = model.id, usedModelName = model.name)
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

    /**
     * TTS 语音合成（purpose = tts）。
     * 调用 OpenAI 兼容 /audio/speech 端点，返回音频字节。
     * 语音格式：mp3。非 2xx 抛出 [FailoverHttpException]。
     */
    suspend fun synthesizeSpeech(
        model: LocalAiModelEntity,
        text: String,
        voice: String = "alloy",
        speed: Float = 1.0f
    ): ByteArray {
        val url = resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/speech")
        val payload = mapOf(
            "model" to model.model,
            "input" to text,
            "voice" to voice,
            "speed" to speed,
            "response_format" to "mp3"
        )
        val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return try {
            client.newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code}: $err")
                }
                resp.body?.bytes() ?: throw FailoverHttpException(resp.code, "TTS 响应体为空")
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "synthesizeSpeech failed: ${e.message}")
            throw e
        }
    }

    /**
     * STT 语音识别（purpose = stt）。
     * 调用 OpenAI 兼容 /audio/transcriptions 端点。
     * audioBytes 为音频字节数组（mp3/wav/m4a 等），返回识别文本。
     * 非 2xx 抛出 [FailoverHttpException] 供协调器捕获。
     */
    suspend fun transcribeSpeech(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String = "audio.mp3",
        language: String? = null
    ): LocalAiResult {
        val url = resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/transcriptions")
        val audioMediaType = guessAudioMediaType(filename).toMediaType()
        Log.i("LocalAiClient", "STT 请求: url=$url, model=${model.model}, file=$filename, size=${audioBytes.size}, mime=$audioMediaType")
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
            client.newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    Log.e("LocalAiClient", "STT 失败: HTTP ${resp.code} url=$url resp=$err")
                    throw FailoverHttpException(resp.code, "HTTP ${resp.code} [POST $url]: $err")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                LocalAiResult(
                    data["text"] as? String ?: "",
                    usedModelId = model.id,
                    usedModelName = model.name
                )
            }
        } catch (e: FailoverHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LocalAiClient", "transcribeSpeech failed: ${e.message}")
            throw e
        }
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
    ): List<GeneratedImage> = generateImage(
        baseUrl = model.baseUrl,
        apiKey = model.apiKey,
        modelName = model.model,
        prompt = prompt,
        size = size,
        n = n,
        appendBaseUrlPath = model.appendBaseUrlPath
    )

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
        appendBaseUrlPath: Boolean = true
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
            client.newCall(req).awaitResponse().use { resp ->
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
