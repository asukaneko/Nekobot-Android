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
    val error: String? = null
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
                    return@use LocalAiResult("", error = "HTTP ${resp.code}: $errBody")
                }
                val raw = resp.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val data = (gson.fromJson(raw, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                val (content, usage) = protocol.parseNonStreamResponse(data)
                LocalAiResult(content, usage)
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

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 流式可能较慢
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
