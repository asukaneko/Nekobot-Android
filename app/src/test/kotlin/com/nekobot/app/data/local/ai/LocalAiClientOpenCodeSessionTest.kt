package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAiClientOpenCodeSessionTest {

    @Test
    fun `opencode go requests send the conversation session header`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = LocalAiClient(recordingClient(requests))

        client.chatOnce(openCodeGoModel(), chatMessages(), requestTag = "session-abc")

        assertEquals(1, requests.size)
        assertEquals("session-abc", requests[0].header("x-opencode-session"))
        assertEquals("Nekobot-Android", requests[0].header("User-Agent"))
    }

    @Test
    fun `opencode requests without a session id fall back to a stable id`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = LocalAiClient(recordingClient(requests))

        client.chatOnce(openCodeGoModel(), chatMessages())
        client.chatOnce(openCodeGoModel(), chatMessages())

        val first = requests[0].header("x-opencode-session")
        val second = requests[1].header("x-opencode-session")
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `opencode sessions are also detected from the endpoint`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = LocalAiClient(recordingClient(requests))

        client.chatOnce(openCodeByBaseUrlModel(), chatMessages(), requestTag = "session-xyz")

        assertEquals("session-xyz", requests[0].header("x-opencode-session"))
    }

    @Test
    fun `other providers do not receive the opencode session header`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = LocalAiClient(recordingClient(requests))

        client.chatOnce(chatModel(), chatMessages())

        assertNull(requests[0].header("x-opencode-session"))
    }

    private fun recordingClient(requests: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

    private fun chatMessages() = listOf(mapOf("role" to "user", "content" to "hello"))

    private fun openCodeGoModel() = LocalAiModelEntity(
        id = "opencode-go-test",
        name = "OpenCode Go Test",
        protocol = OpenAIChatProtocol.name,
        provider = "opencode-go",
        apiKey = "test-key",
        baseUrl = "https://opencode.ai/zen/go/v1",
        model = "glm-5.2",
        createdAt = "2026-09-10T00:00:00Z"
    )

    private fun openCodeByBaseUrlModel() = LocalAiModelEntity(
        id = "opencode-endpoint-test",
        name = "OpenCode Endpoint Test",
        protocol = OpenAIChatProtocol.name,
        apiKey = "test-key",
        baseUrl = "https://opencode.ai/zen/go/v1",
        model = "kimi-k2.6",
        createdAt = "2026-09-10T00:00:00Z"
    )

    private fun chatModel() = LocalAiModelEntity(
        id = "plain-chat",
        name = "Plain Chat",
        protocol = OpenAIChatProtocol.name,
        apiKey = "test-key",
        baseUrl = "https://api.example.com/v1",
        model = "custom-chat",
        createdAt = "2026-09-10T00:00:00Z"
    )
}
