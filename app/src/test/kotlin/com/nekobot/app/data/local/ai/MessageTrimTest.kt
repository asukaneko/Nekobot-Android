package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文裁剪的安全性：工具调用与工具结果必须同生共死。
 *
 * 只保留 tool 结果会让 OpenAI/Anthropic 直接拒绝请求（缺少对应 tool_call），
 * 只保留 tool_calls 则缺少工具响应，两者都是协议级错误。
 */
class MessageTrimTest {

    private fun toolCall(arguments: String): Map<String, Any> = mapOf(
        "id" to "call-1",
        "type" to "function",
        "function" to mapOf("name" to "file_read", "arguments" to arguments)
    )

    private fun conversation(): List<Map<String, Any>> = listOf(
        mapOf("role" to "system", "content" to "S".repeat(100)),
        mapOf("role" to "user", "content" to "U".repeat(4000)),
        mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(toolCall("A".repeat(3000)))),
        mapOf("role" to "tool", "tool_call_id" to "call-1", "name" to "file_read", "content" to "T".repeat(2000)),
        mapOf("role" to "user", "content" to "当前问题")
    )

    @Test
    fun `未超限时原样返回`() {
        val messages = conversation()
        assertSameContent(messages, trimMessages(messages, maxTotalChars = 1_000_000))
    }

    @Test
    fun `裁剪保留完整的工具调用对`() {
        // 预算只够丢掉最早的 user 块，工具调用对必须完整保留
        val trimmed = trimMessages(conversation(), maxTotalChars = 6000)

        val assistantIndex = trimmed.indexOfFirst { it["role"] == "assistant" }
        val toolIndex = trimmed.indexOfFirst { it["role"] == "tool" }
        assertTrue("带 tool_calls 的 assistant 应保留", assistantIndex >= 0)
        assertTrue("对应的 tool 结果应保留", toolIndex >= 0)
        assertTrue("tool 结果必须紧跟其 assistant", toolIndex == assistantIndex + 1)
        assertEquals("最后一块用户消息必须保留", "当前问题", trimmed.last()["content"])
        assertEquals("system 消息必须保留", "system", trimmed.first()["role"])
    }

    @Test
    fun `裁剪不会留下孤立的工具结果`() {
        // 预算很小，工具调用对被整块丢弃，不允许只剩 tool 结果
        val trimmed = trimMessages(conversation(), maxTotalChars = 2500)

        assertFalse("不应出现没有 assistant 的 tool 消息", trimmed.any { it["role"] == "tool" })
        assertFalse(
            "不应出现没有 tool 结果的 assistant(tool_calls)",
            trimmed.any { it["role"] == "assistant" && it["tool_calls"] != null }
        )
        assertEquals("最后一块用户消息必须保留", "当前问题", trimmed.last()["content"])
    }

    @Test
    fun `裁剪结果不会以工具消息开头`() {
        val messages = listOf(
            mapOf("role" to "system", "content" to "sys"),
            mapOf("role" to "tool", "tool_call_id" to "orphan", "content" to "T".repeat(5000)),
            mapOf("role" to "user", "content" to "第二个问题")
        )
        val trimmed = trimMessages(messages, maxTotalChars = 10)

        assertEquals("system", trimmed.first()["role"])
        assertFalse("首条非 system 消息不能是 tool", trimmed.getOrNull(1)?.get("role") == "tool")
    }

    @Test
    fun `单条超长消息也不会被裁空`() {
        val messages = listOf(
            mapOf("role" to "system", "content" to "sys"),
            mapOf("role" to "user", "content" to "X".repeat(50_000))
        )
        val trimmed = trimMessages(messages, maxTotalChars = 100)

        assertEquals("即使单条超限也必须保留，否则模型收不到问题", 2, trimmed.size)
    }

    @Test
    fun `体积计量包含工具调用参数`() {
        val withArgs = mapOf(
            "role" to "assistant",
            "content" to "",
            "tool_calls" to listOf(toolCall("A".repeat(500)))
        )
        val withoutArgs = mapOf("role" to "assistant", "content" to "")

        assertTrue(
            "tool_calls 参数必须计入体积，否则裁剪阈值失真",
            messageSizeChars(withArgs) > 500
        )
        assertEquals(0, messageSizeChars(withoutArgs))
    }

    @Test
    fun `体积计量包含多模态分块与推理内容`() {
        val multimodal = mapOf(
            "role" to "user",
            "content" to listOf(
                mapOf("type" to "text", "text" to "T".repeat(30)),
                mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,xxx"))
            )
        )
        val reasoning = mapOf("role" to "assistant", "content" to "abc", "reasoning_content" to "R".repeat(40))

        assertEquals(30, messageSizeChars(multimodal))
        assertEquals(43, messageSizeChars(reasoning))
    }

    @Test
    fun `分块把助手工具调用与其后续结果归为一组`() {
        val blocks = groupMessageBlocks(conversation().drop(1))

        assertEquals(3, blocks.size)
        assertEquals(2, blocks[1].size)
        assertEquals("assistant", blocks[1][0]["role"])
        assertEquals("tool", blocks[1][1]["role"])
    }

    private fun assertSameContent(expected: List<Map<String, Any>>, actual: List<Map<String, Any>>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a["role"], b["role"])
            assertEquals(a["content"], b["content"])
        }
    }
}
