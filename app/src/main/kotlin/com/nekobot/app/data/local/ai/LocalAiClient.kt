package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(500)
                    return@use LocalAiResult("", error = "HTTP ${resp.code}: $errBody", statusCode = resp.code)
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val (content, usage) = protocol.parseNonStreamResponse(data)
                LocalAiResult(content, usage, usedModelId = model.id, usedModelName = model.name)
            }
        } catch (e: Exception) {
            Log.e("LocalAiClient", "chatOnce failed: ${e.message}")
            LocalAiResult("", error = e.message ?: "请求异常")
        }
    }

    /**
     * 测试模型连通性，返回成功/失败 + 提示。
     */
    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult {
        val testMessages = listOf(
            mapOf("role" to "user", "content" to "说一句“你好”，不超过 10 个字。")
        )
        return chatOnce(model, testMessages)
    }

    // ==================== 带故障转移的调用 ====================

    /**
     * 带故障转移的非流式聊天：按 [models] 顺序尝试，遇到可恢复错误（429/5xx/超时）自动切换下一个。
     * - config 错误（401/400 等）也切换，避免单模型配置错误阻塞整个请求
     * - 全部失败时返回最后一个错误
     * - 通过 [FailoverState] 跟踪健康状态，冷却中的模型会跳过
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
        // 最多尝试 models.size 次（每个模型一次）
        for (i in models.indices) {
            // 通过 FailoverState 选择最佳可用模型（跳过冷却中的）
            val modelConfigs = models.map { mapOf("model_id" to it.id) }
            val selected = failover.selectModel(modelConfigs, exclude)
            val modelId = (selected?.get("model_id") as? String)
            val model = models.firstOrNull { it.id == modelId } ?: models[i]
            exclude.add(model.id)

            val result = chatOnce(model, messages, extra)
            if (result.error == null && result.content.isNotEmpty()) {
                failover.recordSuccess(model.id)
                return result.copy(usedModelId = model.id, usedModelName = model.name)
            }
            // 失败：记录健康状态
            val code = result.statusCode
            failover.recordFailure(model.id, code)
            Log.w("LocalAiClient", "模型 ${model.name} 调用失败 (HTTP $code)，尝试下一个: ${result.error?.take(120)}")
            lastError = result
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
     */
    suspend fun describeImage(
        model: LocalAiModelEntity,
        imageUrl: String,
        question: String = "请描述这张图片的内容。"
    ): LocalAiResult {
        // 仅支持 OpenAI 兼容协议；Anthropic 的 vision 格式略有不同，但 chat 端点也支持
        val url = if (model.appendBaseUrlPath) {
            model.baseUrl.trimEnd('/') + "/chat/completions"
        } else {
            model.baseUrl.trimEnd('/')
        }
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
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer ${model.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    return@use LocalAiResult("", error = "HTTP ${resp.code}: $err")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val choices = data["choices"] as? List<Map<String, Any>> ?: emptyList()
                val msg = choices.firstOrNull()?.get("message") as? Map<String, Any>
                val content = (msg?.get("content") as? String).orEmpty()
                LocalAiResult(content)
            }
        } catch (e: Exception) {
            Log.e("LocalAiClient", "describeImage failed: ${e.message}")
            LocalAiResult("", error = e.message ?: "图片理解请求异常")
        }
    }

    /**
     * TTS 语音合成（purpose = tts）。
     * 调用 OpenAI 兼容 /audio/speech 端点，返回音频字节。
     * 语音格式：mp3。
     */
    suspend fun synthesizeSpeech(
        model: LocalAiModelEntity,
        text: String,
        voice: String = "alloy",
        speed: Float = 1.0f
    ): Pair<ByteArray?, String?> {
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
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    return@use Pair(null, "HTTP ${resp.code}: $err")
                }
                val bytes = resp.body?.bytes()
                Pair(bytes, null)
            }
        } catch (e: Exception) {
            Log.e("LocalAiClient", "synthesizeSpeech failed: ${e.message}")
            Pair(null, e.message ?: "TTS 请求异常")
        }
    }

    /**
     * STT 语音识别（purpose = stt）。
     * 调用 OpenAI 兼容 /audio/transcriptions 端点。
     * audioBytes 为音频字节数组（mp3/wav/m4a 等），返回识别文本。
     */
    suspend fun transcribeSpeech(
        model: LocalAiModelEntity,
        audioBytes: ByteArray,
        filename: String = "audio.mp3",
        language: String? = null
    ): LocalAiResult {
        val url = resolveAudioUrl(model.baseUrl, model.appendBaseUrlPath, "audio/transcriptions")
        // 根据文件后缀选择正确的 MIME 类型，避免某些代理因类型不匹配返回 404/415
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
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(500)
                    Log.e("LocalAiClient", "STT 失败: HTTP ${resp.code} url=$url resp=$err")
                    return@use LocalAiResult("", error = "HTTP ${resp.code} [POST $url]: $err", statusCode = resp.code)
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                LocalAiResult(data["text"] as? String ?: "")
            }
        } catch (e: Exception) {
            Log.e("LocalAiClient", "transcribeSpeech failed: ${e.message}")
            LocalAiResult("", error = e.message ?: "STT 请求异常")
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
