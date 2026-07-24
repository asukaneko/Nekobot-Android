package com.nekobot.app.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SocketMessageParserTest {

    private val gson = Gson()

    @Test
    fun parseRealtimeMessagePayload_unwrapsAiResponseMessage() {
        val payload = """
            {
              "session_id": "session-1",
              "message": {
                "id": "assistant-1",
                "role": "assistant",
                "content": "工具处理完成后的最终回复",
                "timestamp": "2026-07-18T12:00:00+08:00"
              }
            }
        """.trimIndent()

        val message = parseRealtimeMessagePayload(gson, payload)

        assertNotNull(message)
        assertEquals("assistant-1", message?.id)
        assertEquals("assistant", message?.role)
        assertEquals("工具处理完成后的最终回复", message?.content)
        assertEquals("session-1", message?.sessionId)
    }

    @Test
    fun parseRealtimeMessagePayload_keepsDirectNewMessageFormat() {
        val payload = """
            {
              "id": "assistant-2",
              "role": "assistant",
              "content": "直接消息"
            }
        """.trimIndent()

        val message = parseRealtimeMessagePayload(gson, payload)

        assertNotNull(message)
        assertEquals("assistant-2", message?.id)
        assertEquals("直接消息", message?.content)
    }

    @Test
    fun parseRealtimeMessageEnvelope_keepsDirectWebUiSessionId() {
        val payload = """
            {
              "id": "user-web-1",
              "role": "user",
              "content": "从 WebUI 发出",
              "session_id": "session-web"
            }
        """.trimIndent()

        val envelope = parseRealtimeMessageEnvelope(gson, payload)

        assertNotNull(envelope)
        assertEquals("session-web", envelope?.sessionId)
        assertEquals("session-web", envelope?.message?.sessionId)
        assertEquals("user", envelope?.message?.role)
    }

    @Test
    fun targetSessionId_routesEveryRemoteChatEventToItsRoom() {
        val message = com.nekobot.app.data.model.Message(
            id = "assistant-1",
            role = "assistant",
            sessionId = "session-a"
        )

        assertEquals(
            "session-a",
            RealtimeEvent.NewMessage(message).targetSessionId()
        )
        assertEquals(
            "session-b",
            RealtimeEvent.StreamChunk("片段", "session-b").targetSessionId()
        )
    }

    @Test
    fun parseExecConfirmationPayload_readsAuthorizationRequest() {
        val payload = """
            {
              "request_id": "request-12345678",
              "command": "git status --short",
              "main_command": "git",
              "message": "该命令需要您的确认",
              "session_id": "session-1"
            }
        """.trimIndent()

        val request = parseExecConfirmationPayload(payload)

        assertNotNull(request)
        assertEquals("request-12345678", request?.requestId)
        assertEquals("git status --short", request?.command)
        assertEquals("git", request?.mainCommand)
        assertEquals("该命令需要您的确认", request?.message)
        assertEquals("session-1", request?.sessionId)
    }

    @Test
    fun parseExecConfirmationPayload_rejectsMissingRequestId() {
        assertNull(parseExecConfirmationPayload("""{"command":"git status"}"""))
    }

    @Test
    fun buildExecConfirmationPayload_encodesAlwaysAuthorization() {
        val payload = buildExecConfirmationPayload(
            requestId = "request-1",
            authorization = ExecAuthorization.Always,
            sessionId = "session-1"
        )

        assertEquals("request-1", payload["request_id"])
        assertEquals(true, payload["approved"])
        assertEquals("always", payload["permission"])
        assertEquals("session-1", payload["session_id"])
    }

    @Test
    fun buildChatMessagePayload_keepsRemoteImageAttachment() {
        val attachment = mapOf<String, Any>(
            "name" to "角色图.png",
            "type" to "image/png",
            "path" to "C:\\server\\workspace\\角色图.png",
            "source" to "web"
        )

        val payload = buildChatMessagePayload(
            sessionId = "session-1",
            content = "看看这张图",
            attachments = listOf(attachment)
        )

        assertEquals("session-1", payload["session_id"])
        assertEquals("看看这张图", payload["content"])
        assertEquals(listOf(attachment), payload["attachments"])
    }
}
