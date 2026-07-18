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
    fun parseExecConfirmationPayload_readsAuthorizationRequest() {
        val payload = """
            {
              "request_id": "request-12345678",
              "command": "git status --short",
              "message": "该命令需要您的确认",
              "session_id": "session-1"
            }
        """.trimIndent()

        val request = parseExecConfirmationPayload(payload)

        assertNotNull(request)
        assertEquals("request-12345678", request?.requestId)
        assertEquals("git status --short", request?.command)
        assertEquals("该命令需要您的确认", request?.message)
        assertEquals("session-1", request?.sessionId)
    }

    @Test
    fun parseExecConfirmationPayload_rejectsMissingRequestId() {
        assertNull(parseExecConfirmationPayload("""{"command":"git status"}"""))
    }
}
