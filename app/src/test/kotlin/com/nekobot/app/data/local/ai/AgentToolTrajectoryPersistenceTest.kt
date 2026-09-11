package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 工具轨迹落库：逐条 JSON 化、体积兜底、检查点摘要、以及中断恢复时的尾块裁剪。
 */
class AgentToolTrajectoryPersistenceTest {

    private fun assistantToolCall(id: String, content: String = ""): Map<String, Any> = mapOf(
        "role" to "assistant",
        "content" to content,
        "tool_calls" to listOf(
            mapOf(
                "id" to id,
                "type" to "function",
                "function" to mapOf("name" to "workspace_read_file", "arguments" to """{"path":"a.txt"}""")
            )
        )
    )

    private fun toolResult(id: String, content: String): Map<String, Any> = mapOf(
        "role" to "tool",
        "tool_call_id" to id,
        "name" to "workspace_read_file",
        "content" to content
    )

    @Test
    fun `工具消息逐条序列化后可原样恢复`() {
        val message = toolResult("call-1", "文件正文")

        val decoded = decodeAgentToolMessageRow(encodeAgentToolMessageRow(message))

        assertEquals(message, decoded)
    }

    @Test
    fun `非工具角色的行不会进入恢复轨迹`() {
        val encoded = encodeAgentToolMessageRow(mapOf("role" to "user", "content" to "你好"))

        assertNull(decodeAgentToolMessageRow(encoded))
    }

    @Test
    fun `超大工具结果落库时被压缩为占位说明`() {
        val huge = "x".repeat(MAX_AGENT_TOOL_MESSAGE_ROW_CHARS + 100)
        val message = toolResult("call-big", huge)

        val encoded = encodeAgentToolMessageRow(message)

        assertTrue(
            "落库行必须受体积上限约束，避免单行超过 SQLite CursorWindow",
            encoded.length <= MAX_AGENT_TOOL_MESSAGE_ROW_CHARS + 2_000
        )
        val decoded = decodeAgentToolMessageRow(encoded)
        assertNotNull(decoded)
        assertTrue((decoded!!["content"] as String).contains("原始长度"))
    }

    @Test
    fun `检查点摘要只记录进度且可被识别`() {
        val history = listOf(
            assistantToolCall("call-1"),
            toolResult("call-1", "内容" .repeat(50))
        )

        val encoded = encodeAgentCheckpointSummary(history)!!
        val summary = decodeAgentCheckpointSummary(encoded)!!

        assertEquals(1, summary.toolCalls)
        assertTrue("摘要必须远小于整轮工具正文", encoded.length < 200)
        assertTrue(summary.tokens > 0)
    }

    @Test
    fun `旧版整轮 JSON 检查点不会被误判为摘要`() {
        val legacy = encodeToolCallHistory(listOf(assistantToolCall("call-1"), toolResult("call-1", "正文")))!!

        assertNull(decodeAgentCheckpointSummary(legacy))
        assertTrue("旧版检查点仍应能被历史解码器读出", decodeToolCallHistory(legacy).isNotEmpty())
    }

    @Test
    fun `空历史不产生检查点`() {
        assertNull(encodeAgentCheckpointSummary(emptyList()))
    }

    @Test
    fun `未完成的尾部工具块在恢复时被裁剪`() {
        val complete = listOf(
            assistantToolCall("call-1"),
            toolResult("call-1", "第一批结果"),
            assistantToolCall("call-2"),
            toolResult("call-2", "第二批结果")
        )

        // 第三批只写入了 assistant tool_calls（进程在工具执行中被回收）
        val partial = complete + assistantToolCall("call-3")

        assertEquals(complete, dropIncompleteAgentTail(partial))
    }

    @Test
    fun `缺少部分工具响应的尾块也要丢弃`() {
        val complete = listOf(
            assistantToolCall("call-1"),
            toolResult("call-1", "第一批结果")
        )
        val partialCall = mapOf<String, Any>(
            "role" to "assistant",
            "content" to "",
            "tool_calls" to listOf(
                mapOf("id" to "call-2a", "type" to "function", "function" to mapOf("name" to "a", "arguments" to "{}")),
                mapOf("id" to "call-2b", "type" to "function", "function" to mapOf("name" to "b", "arguments" to "{}"))
            )
        )

        val restored = dropIncompleteAgentTail(complete + partialCall + toolResult("call-2a", "只有一半"))

        assertEquals(complete, restored)
    }

    @Test
    fun `完整的尾块与结尾文本消息都保留`() {
        val complete = listOf(
            assistantToolCall("call-1"),
            toolResult("call-1", "结果"),
            mapOf<String, Any>("role" to "assistant", "content" to "任务完成")
        )

        assertEquals(complete, dropIncompleteAgentTail(complete))
    }

    @Test
    fun `消息行上的工具历史超限时保留最新轮次并标注`() {
        val history = (1..40).flatMap { index ->
            listOf(
                assistantToolCall("call-$index"),
                toolResult("call-$index", "第 $index 轮结果".repeat(2_000))
            )
        }

        val encoded = boundAgentToolHistoryJson(history, maxChars = 60_000)!!

        assertTrue("写库体积必须受控", encoded.length <= 70_000)
        val decoded = decodeToolCallHistory(encoded)
        assertTrue(decoded.isNotEmpty())
        assertTrue(
            "被丢弃的更早轮次要有说明",
            (decoded.first()["content"] as? String)?.contains("未随本条消息保存") == true
        )
        assertTrue(
            "最新一轮必须完整保留",
            (decoded.last()["content"] as String).contains("第 40 轮结果")
        )
    }

    @Test
    fun `体积未超限时工具历史原样写入`() {
        val history = listOf(assistantToolCall("call-1"), toolResult("call-1", "短结果"))

        assertEquals(encodeToolCallHistory(history), boundAgentToolHistoryJson(history, maxChars = 60_000))
    }

    @Test
    fun `单块即超限时只保留说明，绝不写出超限 TEXT 行`() {
        val history = listOf(
            assistantToolCall("call-1"),
            toolResult("call-1", "超大结果".repeat(20_000))
        )

        val encoded = boundAgentToolHistoryJson(history, maxChars = 1_000)!!

        assertTrue("单行体积必须硬性受控", encoded.length <= 1_000)
        val decoded = decodeToolCallHistory(encoded)
        assertEquals(1, decoded.size)
        assertTrue((decoded.first()["content"] as String).contains("超出单条消息体积上限"))
    }
}
