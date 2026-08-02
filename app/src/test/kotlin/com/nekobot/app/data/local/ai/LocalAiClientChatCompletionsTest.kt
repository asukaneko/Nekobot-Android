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
    fun refusalPlaceholdersAreRecognizedWithoutMatchingLongUsefulAnswers() {
        assertTrue(shouldFailoverForAssistantContent("你好，我无法给到相关内容。"))
        assertTrue(shouldFailoverForAssistantContent("很抱歉，我无法提供此类内容。"))
        assertTrue(shouldFailoverForAssistantContent("相关的内容"))
        assertFalse(
            shouldFailoverForAssistantContent(
                "下面是相关的内容与完整说明：" + "有效信息".repeat(100)
            )
        )
    }

    @Test
    fun streamedRefusalIsHiddenAndTriggersNextModel() = runBlocking {
        var requestCount = 0
        val client = LocalAiClient(
            sequentialSseClient {
                requestCount += 1
                if (requestCount == 1) "你好，我无法给到相关内容。" else "备用模型正常回答"
            }
        )

        val events = client.chatStreamWithFailover(
            listOf(chatModel("stream-refusal"), chatModel("stream-backup")),
            listOf(mapOf("role" to "user", "content" to "hello"))
        ).toList()

        assertEquals(2, requestCount)
        assertEquals(
            "备用模型正常回答",
            events.filterIsInstance<RealtimeEvent.StreamChunk>().joinToString("") { it.chunk }
        )
    }

    @Test
    fun agentCallbacksDoNotReceiveRejectedModelContent() = runBlocking {
        var requestCount = 0
        val relayedContent = StringBuilder()
        val client = LocalAiClient(
            sequentialSseClient {
                requestCount += 1
                if (requestCount == 1) "你好，我无法给到相关内容。" else "Agent 备用回答"
            }
        )

        val result = client.chatOnceWithFailover(
            models = listOf(chatModel("agent-refusal"), chatModel("agent-backup")),
            messages = listOf(mapOf("role" to "user", "content" to "hello")),
            streamCallbacks = LocalAiStreamCallbacks(
                onContentChunk = relayedContent::append
            )
        )

        assertEquals(2, requestCount)
        assertEquals("Agent 备用回答", result.content)
        assertEquals("Agent 备用回答", relayedContent.toString())
    }

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

    private fun sequentialSseClient(content: () -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        buildString {
                            appendLine(
                                "data: {\"choices\":[{\"delta\":{\"content\":${JsonParser.parseString(com.google.gson.Gson().toJson(content()))}}}]}"
                            )
                            appendLine()
                            appendLine("data: [DONE]")
                        }.toResponseBody("text/event-stream".toMediaType())
                    )
                    .build()
            })
            .build()

    private fun chatModel(id: String = "chat-test") = LocalAiModelEntity(
        id = id,
        name = "Chat Test",
        protocol = OpenAIChatProtocol.name,
        apiKey = "test-key",
        baseUrl = "https://api.example.com/v1",
        model = "custom-chat",
        createdAt = "2026-08-02T00:00:00Z"
    )
}
