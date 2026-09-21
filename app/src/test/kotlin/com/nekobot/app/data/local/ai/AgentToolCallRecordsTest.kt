package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具调用记录抽取：assistant tool_calls 与 tool 结果按 call id 配对。 */
class AgentToolCallRecordsTest {

    private fun assistantToolCall(vararg calls: Triple<String, String, String>): Map<String, Any> = mapOf(
        "role" to "assistant",
        "content" to "",
        "tool_calls" to calls.map { (id, name, arguments) ->
            mapOf(
                "id" to id,
                "type" to "function",
                "function" to mapOf("name" to name, "arguments" to arguments)
            )
        }
    )

    private fun toolResult(id: String, name: String, content: String): Map<String, Any> = mapOf(
        "role" to "tool",
        "tool_call_id" to id,
        "name" to name,
        "content" to content
    )

    @Test
    fun `调用与结果按 call id 配对并保留参数`() {
        val records = toolCallRecordsFromHistory(
            listOf(
                assistantToolCall(Triple("call-1", "workspace_read_file", """{"path":"a.txt"}""")),
                toolResult("call-1", "workspace_read_file", """{"success":true,"content":"ok"}""")
            ),
            messageId = "msg-1",
            createdAt = "2026-09-21T10:00:00"
        )

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("call-1", record.callId)
        assertEquals("workspace_read_file", record.name)
        assertEquals("""{"path":"a.txt"}""", record.arguments)
        assertEquals(LocalToolCallRecord.STATUS_DONE, record.status)
        assertEquals("msg-1", record.messageId)
        assertEquals("2026-09-21T10:00:00", record.createdAt)
        assertEquals(LocalToolCallRecord.SOURCE_HISTORY, record.source)
    }

    @Test
    fun `success 为 false 的结果标记为 error`() {
        val records = toolCallRecordsFromHistory(
            listOf(
                assistantToolCall(Triple("call-1", "shell", "{}")),
                toolResult("call-1", "shell", """{"success":false,"error":"拒绝执行"}""")
            )
        )

        assertEquals(LocalToolCallRecord.STATUS_ERROR, records.single().status)
    }

    @Test
    fun `尚无结果时保持 pending`() {
        val records = toolCallRecordsFromHistory(
            listOf(assistantToolCall(Triple("call-1", "web_search", """{"q":"猫"}""")))
        )

        val record = records.single()
        assertEquals(LocalToolCallRecord.STATUS_PENDING, record.status)
        assertNull(record.result)
    }

    @Test
    fun `一次 assistant 的多个调用按顺序配对`() {
        val records = toolCallRecordsFromHistory(
            listOf(
                assistantToolCall(
                    Triple("call-1", "a", """{"n":1}"""),
                    Triple("call-2", "b", """{"n":2}""")
                ),
                toolResult("call-2", "b", """{"success":true}"""),
                toolResult("call-1", "a", """{"success":true}""")
            )
        )

        assertEquals(listOf("a", "b"), records.map { it.name })
        assertEquals(listOf("call-1", "call-2"), records.map { it.callId })
        assertTrue(records.all { it.status == LocalToolCallRecord.STATUS_DONE })
    }

    @Test
    fun `无法配对的 tool 结果也会保留`() {
        val records = toolCallRecordsFromHistory(
            listOf(toolResult("orphan", "unknown_tool", "结果正文"))
        )

        val record = records.single()
        assertEquals("orphan", record.callId)
        assertEquals("unknown_tool", record.name)
        assertEquals("结果正文", record.result)
        assertEquals(LocalToolCallRecord.STATUS_DONE, record.status)
    }

    @Test
    fun `非工具角色与无 tool_calls 的 assistant 消息被忽略`() {
        val records = toolCallRecordsFromHistory(
            listOf(
                mapOf("role" to "user", "content" to "你好"),
                mapOf("role" to "assistant", "content" to "最终回复")
            )
        )

        assertTrue(records.isEmpty())
    }
}
