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

class LocalAiClientResponsesTest {

    @Test
    fun doneOnlySseStillEmitsVisibleContent() = runBlocking {
        val body = buildString {
            appendLine("data: {\"type\":\"response.output_text.done\",\"text\":\"done-only text\"}")
            appendLine()
            appendLine("data: [DONE]")
        }
        val client = LocalAiClient(clientReturning(body, "text/event-stream"))

        val events = client.chatStream(
            responsesModel(),
            listOf(mapOf("role" to "user", "content" to "hello"))
        ).toList()

        val content = events.filterIsInstance<RealtimeEvent.StreamChunk>().joinToString("") { it.chunk }
        assertEquals("done-only text", content)
    }

    @Test
    fun fullJsonResponseIsAcceptedWhenStreamingWasRequested() = runBlocking {
        val body = """
            {
              "status": "completed",
              "output": [
                {"type":"message","content":[{"type":"output_text","text":"buffered text"}]}
              ]
            }
        """.trimIndent()
        val client = LocalAiClient(clientReturning(body, "application/json"))

        val result = client.chatOnce(
            responsesModel(),
            listOf(mapOf("role" to "user", "content" to "hello"))
        )

        assertEquals("buffered text", result.content)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun responses400RetriesWithoutOptionalTuningAndKeepsTools() = runBlocking {
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
                            """{"error":{"message":"Unsupported parameter: max_output_tokens"}}"""
                                .toResponseBody("application/json".toMediaType())
                        } else {
                            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"retried"}]}]}"""
                                .toResponseBody("application/json".toMediaType())
                        }
                    )
                    .build()
            })
            .build()
        val client = LocalAiClient(okHttpClient)

        val result = client.chatOnce(
            responsesModel(),
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
        )

        assertEquals("retried", result.content)
        assertEquals(2, requestBodies.size)
        val first = JsonParser.parseString(requestBodies[0]).asJsonObject
        val retried = JsonParser.parseString(requestBodies[1]).asJsonObject
        assertTrue(first.has("temperature"))
        assertTrue(first.has("max_output_tokens"))
        assertTrue(first.has("top_p"))
        assertTrue(first.has("reasoning"))
        assertFalse(retried.has("temperature"))
        assertFalse(retried.has("max_output_tokens"))
        assertFalse(retried.has("top_p"))
        assertEquals(
            "none",
            retried.getAsJsonObject("reasoning").get("effort").asString
        )
        assertTrue(retried.has("tools"))
    }

    private fun clientReturning(body: String, contentType: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            })
            .build()

    private fun responsesModel() = LocalAiModelEntity(
        id = "responses-test",
        name = "Responses Test",
        protocol = OpenAIResponsesProtocol.name,
        apiKey = "test-key",
        baseUrl = "https://api.example.com/v1",
        model = "gpt-test",
        createdAt = "2026-08-02T00:00:00Z"
    )
}
