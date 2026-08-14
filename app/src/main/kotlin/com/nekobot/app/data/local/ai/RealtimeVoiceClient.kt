package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.AiModel
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class RealtimeModelConfig(
    val id: String,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val appendBaseUrlPath: Boolean = true,
    val proxyUrl: String = "",
    val voice: String = "marin",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
    val language: String = "zh",
    val maxOutputTokens: Int = 4096
)

data class RealtimeContextMessage(
    val role: String,
    val content: String
)

sealed interface RealtimeVoiceEvent {
    data object Connected : RealtimeVoiceEvent
    data class UserTranscript(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent
    data class AssistantTranscript(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent
    data class AudioDelta(val pcm16: ByteArray) : RealtimeVoiceEvent
    data class Completed(
        val userTranscript: String,
        val assistantTranscript: String
    ) : RealtimeVoiceEvent
    data class Failure(val message: String) : RealtimeVoiceEvent
}

fun LocalAiModelEntity.toRealtimeModelConfig(): RealtimeModelConfig = RealtimeModelConfig(
    id = id,
    name = name,
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = model.ifBlank { "gpt-realtime" },
    appendBaseUrlPath = appendBaseUrlPath,
    proxyUrl = proxyUrl,
    voice = ttsVoice.takeUnless { it.isBlank() || it == "default" } ?: "marin",
    transcriptionModel = sttModel.ifBlank { "gpt-4o-mini-transcribe" },
    language = language,
    maxOutputTokens = (maxTokens ?: 4096).coerceIn(1, 4096)
)

fun AiModel.toRealtimeModelConfig(): RealtimeModelConfig = RealtimeModelConfig(
    id = id.orEmpty(),
    name = displayName,
    apiKey = apiKey.orEmpty(),
    baseUrl = baseUrl.orEmpty(),
    model = model.orEmpty().ifBlank { "gpt-realtime" },
    appendBaseUrlPath = appendBaseUrlPath ?: true,
    voice = ttsVoice.takeUnless { it.isNullOrBlank() || it == "default" } ?: "marin",
    transcriptionModel = sttModel.orEmpty().ifBlank { "gpt-4o-mini-transcribe" },
    language = sttLanguage ?: language ?: "zh",
    maxOutputTokens = (maxTokens ?: 4096).coerceIn(1, 4096)
)

class RealtimeVoiceClient(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    fun streamTurn(
        config: RealtimeModelConfig,
        instructions: String,
        context: List<RealtimeContextMessage>,
        pcm16: ByteArray
    ): Flow<RealtimeVoiceEvent> = callbackFlow {
        require(config.apiKey.isNotBlank() && config.apiKey != "********") {
            "Realtime 模型缺少可用的 API Key"
        }
        require(pcm16.isNotEmpty()) { "没有可发送的语音数据" }

        val client = baseClient.withModelProxy(config.proxyUrl)
        val request = Request.Builder()
            .url(buildRealtimeWebSocketUrl(config.baseUrl, config.appendBaseUrlPath, config.model))
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        val userTranscript = StringBuilder()
        val assistantTranscript = StringBuilder()
        val terminal = AtomicBoolean(false)
        var responseDone = false
        var completionJob: Job? = null
        lateinit var socket: WebSocket

        fun finishAfter(delayMs: Long) {
            completionJob?.cancel()
            completionJob = launch {
                delay(delayMs)
                if (terminal.compareAndSet(false, true)) {
                    trySend(
                        RealtimeVoiceEvent.Completed(
                            userTranscript = userTranscript.toString().trim(),
                            assistantTranscript = assistantTranscript.toString().trim()
                        )
                    )
                    socket.close(1000, "turn completed")
                    close()
                }
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(gson.toJson(buildRealtimeSessionUpdate(config, instructions)))
                buildRealtimeContextEvents(context).forEach { event ->
                    webSocket.send(gson.toJson(event))
                }
                var offset = 0
                while (offset < pcm16.size) {
                    val end = minOf(offset + REALTIME_INPUT_CHUNK_BYTES, pcm16.size)
                    val bytes = pcm16.copyOfRange(offset, end)
                    webSocket.send(
                        gson.toJson(JsonObject().apply {
                            addProperty("type", "input_audio_buffer.append")
                            addProperty("audio", Base64.getEncoder().encodeToString(bytes))
                        })
                    )
                    offset = end
                }
                webSocket.send("{\"type\":\"input_audio_buffer.commit\"}")
                webSocket.send(
                    gson.toJson(JsonObject().apply {
                        addProperty("type", "response.create")
                        add("response", JsonObject().apply {
                            add("output_modalities", JsonArray().apply { add("audio") })
                        })
                    })
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                when (event.string("type")) {
                    "session.created", "session.updated" -> trySend(RealtimeVoiceEvent.Connected)

                    "conversation.item.input_audio_transcription.delta" -> {
                        val delta = event.string("delta").orEmpty()
                        if (delta.isNotEmpty()) {
                            userTranscript.append(delta)
                            trySend(RealtimeVoiceEvent.UserTranscript(userTranscript.toString(), false))
                        }
                    }

                    "conversation.item.input_audio_transcription.completed" -> {
                        val transcript = event.string("transcript").orEmpty().trim()
                        if (transcript.isNotEmpty()) {
                            userTranscript.clear()
                            userTranscript.append(transcript)
                            trySend(RealtimeVoiceEvent.UserTranscript(transcript, true))
                        }
                        if (responseDone) finishAfter(80)
                    }

                    "response.output_audio.delta", "response.audio.delta" -> {
                        event.string("delta")
                            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { trySend(RealtimeVoiceEvent.AudioDelta(it)) }
                    }

                    "response.output_audio_transcript.delta", "response.audio_transcript.delta",
                    "response.output_text.delta", "response.text.delta" -> {
                        val delta = event.string("delta").orEmpty()
                        if (delta.isNotEmpty()) {
                            assistantTranscript.append(delta)
                            trySend(RealtimeVoiceEvent.AssistantTranscript(assistantTranscript.toString(), false))
                        }
                    }

                    "response.output_audio_transcript.done", "response.audio_transcript.done",
                    "response.output_text.done", "response.text.done" -> {
                        val transcript = event.string("transcript") ?: event.string("text")
                        if (!transcript.isNullOrBlank()) {
                            assistantTranscript.clear()
                            assistantTranscript.append(transcript)
                        }
                        if (assistantTranscript.isNotBlank()) {
                            trySend(
                                RealtimeVoiceEvent.AssistantTranscript(
                                    assistantTranscript.toString(),
                                    true
                                )
                            )
                        }
                    }

                    "response.done" -> {
                        val responseObject = event.objectOrNull("response")
                        val status = responseObject?.string("status")
                        if (status == "failed" || status == "cancelled") {
                            val message = responseObject.objectOrNull("status_details")
                                ?.objectOrNull("error")
                                ?.string("message")
                                ?: "Realtime 响应失败"
                            if (terminal.compareAndSet(false, true)) {
                                trySend(RealtimeVoiceEvent.Failure(message))
                                webSocket.close(1011, message.take(100))
                                close()
                            }
                            return
                        }
                        responseDone = true
                        finishAfter(if (userTranscript.isBlank()) 1_500 else 80)
                    }

                    "error" -> {
                        val message = event.objectOrNull("error")?.string("message")
                            ?: event.string("message")
                            ?: "Realtime 服务返回错误"
                        if (terminal.compareAndSet(false, true)) {
                            trySend(RealtimeVoiceEvent.Failure(message))
                            webSocket.close(1011, message.take(100))
                            close()
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (terminal.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(throwable.message ?: "Realtime 连接失败"))
                    close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get() && terminal.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(reason.ifBlank { "Realtime 连接已关闭" }))
                    close()
                }
            }
        }

        socket = client.newWebSocket(request, listener)
        awaitClose {
            completionJob?.cancel()
            terminal.set(true)
            socket.cancel()
        }
    }

    suspend fun testConnection(config: RealtimeModelConfig): Result<Unit> = runCatching {
        require(config.apiKey.isNotBlank() && config.apiKey != "********") {
            "Realtime 模型缺少可用的 API Key"
        }
        withTimeout(15_000) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val client = baseClient.withModelProxy(config.proxyUrl)
                val request = Request.Builder()
                    .url(buildRealtimeWebSocketUrl(config.baseUrl, config.appendBaseUrlPath, config.model))
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .build()
                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val type = runCatching {
                            JsonParser.parseString(text).asJsonObject.string("type")
                        }.getOrNull()
                        if (type == "session.created" && completed.compareAndSet(false, true)) {
                            webSocket.close(1000, "test completed")
                            continuation.resume(Unit)
                        } else if (type == "error" && completed.compareAndSet(false, true)) {
                            val message = runCatching {
                                JsonParser.parseString(text).asJsonObject
                                    .objectOrNull("error")?.string("message")
                            }.getOrNull() ?: "Realtime 服务返回错误"
                            webSocket.close(1011, message.take(100))
                            continuation.resumeWith(Result.failure(IllegalStateException(message)))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWith(Result.failure(throwable))
                        }
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        }
    }
}

internal fun buildRealtimeSessionUpdate(
    config: RealtimeModelConfig,
    instructions: String
): JsonObject = JsonObject().apply {
    addProperty("type", "session.update")
    add("session", JsonObject().apply {
        addProperty("type", "realtime")
        addProperty(
            "instructions",
            instructions.trim().take(REALTIME_MAX_INSTRUCTIONS_CHARS).ifBlank {
                "自然地进行简洁的语音对话，并使用用户当前使用的语言回答。"
            }
        )
        add("output_modalities", JsonArray().apply { add("audio") })
        addProperty("max_output_tokens", config.maxOutputTokens.coerceIn(1, 4096))
        add("audio", JsonObject().apply {
            add("input", JsonObject().apply {
                add("format", realtimePcmFormat())
                add("transcription", JsonObject().apply {
                    addProperty(
                        "model",
                        config.transcriptionModel.ifBlank { "gpt-4o-mini-transcribe" }
                    )
                    config.language.takeUnless { it.isBlank() || it.equals("auto", true) }
                        ?.let { addProperty("language", it) }
                })
                add("turn_detection", null)
            })
            add("output", JsonObject().apply {
                add("format", realtimePcmFormat())
                addProperty("voice", config.voice.ifBlank { "marin" })
            })
        })
    })
}

internal fun buildRealtimeContextEvents(
    context: List<RealtimeContextMessage>
): List<JsonObject> = trimRealtimeContext(context).map { message ->
    val user = message.role.equals("user", true) || message.role.equals("human", true)
    JsonObject().apply {
        addProperty("type", "conversation.item.create")
        add("item", JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", if (user) "user" else "assistant")
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", if (user) "input_text" else "output_text")
                    addProperty("text", message.content.trim())
                })
            })
        })
    }
}

internal fun trimRealtimeContext(
    context: List<RealtimeContextMessage>,
    maxChars: Int = REALTIME_MAX_CONTEXT_CHARS
): List<RealtimeContextMessage> {
    val kept = ArrayDeque<RealtimeContextMessage>()
    var used = 0
    context.asReversed().forEach { message ->
        val normalized = message.content.trim()
        if (normalized.isBlank()) return@forEach
        val remaining = maxChars - used
        if (remaining <= 0) return@forEach
        val content = if (normalized.length <= remaining) normalized else normalized.takeLast(remaining)
        kept.addFirst(message.copy(content = content))
        used += content.length
    }
    return kept.toList()
}

internal fun buildRealtimeWebSocketUrl(
    baseUrl: String,
    appendRealtimePath: Boolean,
    model: String
): String {
    val raw = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
    val websocketBase = when {
        raw.startsWith("https://", true) -> "wss://${raw.substringAfter("://")}" 
        raw.startsWith("http://", true) -> "ws://${raw.substringAfter("://")}" 
        raw.startsWith("wss://", true) || raw.startsWith("ws://", true) -> raw
        else -> "wss://$raw"
    }
    val hashSplit = websocketBase.substringBefore('#')
    val path = hashSplit.substringBefore('?')
    val existingQuery = hashSplit.substringAfter('?', "")
    val endpoint = if (appendRealtimePath && !path.endsWith("/realtime", true)) {
        "$path/realtime"
    } else {
        path
    }
    val queryParts = existingQuery.split('&').filter { it.isNotBlank() && !it.startsWith("model=") }
        .toMutableList()
    queryParts += "model=${URLEncoder.encode(model.ifBlank { "gpt-realtime" }, Charsets.UTF_8.name())}"
    return "$endpoint?${queryParts.joinToString("&")}" 
}

private fun realtimePcmFormat(): JsonObject = JsonObject().apply {
    addProperty("type", "audio/pcm")
    addProperty("rate", REALTIME_SAMPLE_RATE)
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

const val REALTIME_SAMPLE_RATE = 24_000
private const val REALTIME_INPUT_CHUNK_BYTES = 24_000
private const val REALTIME_MAX_INSTRUCTIONS_CHARS = 12_000
private const val REALTIME_MAX_CONTEXT_CHARS = 48_000
