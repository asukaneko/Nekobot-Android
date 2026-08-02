package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiClientChatCompletionsTest {

    @Test
    fun chatCompletions400RetriesWithoutOptionalParametersOrStreamOptions() = runBlocking {
        val requestBodies = mutableListOf<String>()
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val requestBody = Buffer().also { buffer ->
                    chain.request().body?.writeTo(buffer)
                }.readUtf8()
                requestBodies += requestBody
                val isFirstRequest = requestBodies.size == 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isFirstRequest) 400 else 200)
                    .message(if (isFirstRequest) "Bad Request" else "OK")
                    .body(
                        if (isFirstRequest) {
                            """{"error":{"message":"Unsupported parameter: stream_options"}}"""
                                .toResponseBody("application/json".toMediaType())
                        } else {
                            buildString {
                                appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"retried\"}}]}")
                                appendLine()
                                appendLine("data: [DONE]")
                            }.toResponseBody("text/event-stream".toMediaType())
                        }
                    )
                    .build()
            })
            .build()
        val client = LocalAiClient(okHttpClient)

        val events = client.chatStream(
            chatModel(),
            listOf(mapOf("role" to "user", "content" to "hello")),
            extra = mapOf(
                "temperature" to 0.7,
                "max_tokens" to 2048,
                "top_p" to 1.0,
                "reasoning_effort" to "high",
                "tools" to listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "lookup",
                            "parameters" to mapOf("type" to "object")
                        )
                    )
                )
            )
        ).toList()

        assertEquals(
            "retried",
            events.filterIsInstance<RealtimeEvent.StreamChunk>().joinToString("") { it.chunk }
        )
        assertEquals(2, requestBodies.size)
        val first = JsonParser.parseString(requestBodies[0]).asJsonObject
        val retried = JsonParser.parseString(requestBodies[1]).asJsonObject
        assertTrue(first.has("stream_options"))
        assertTrue(first.has("temperature"))
        assertTrue(first.has("max_tokens"))
        assertTrue(first.has("top_p"))
        assertTrue(first.has("reasoning_effort"))
        assertFalse(retried.has("stream_options"))
        assertFalse(retried.has("temperature"))
        assertFalse(retried.has("max_tokens"))
        assertFalse(retried.has("top_p"))
        assertFalse(retried.has("reasoning_effort"))
        assertTrue(retried.has("tools"))
    }

    private fun chatModel() = LocalAiModelEntity(
        id = "chat-test",
        name = "Chat Test",
        protocol = OpenAIChatProtocol.name,
        apiKey = "test-key",
        baseUrl = "https://api.example.com/v1",
        model = "custom-chat",
        createdAt = "2026-08-02T00:00:00Z"
    )
}
