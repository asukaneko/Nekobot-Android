package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环内上下文预算管理：超预算时按"最旧优先"裁剪工具结果正文，且不改动消息结构。
 */
class InLoopContextBudgetTest {

    private fun bigContent(repeat: Int) = "x".repeat(repeat)

    private fun toolMessage(id: String, content: String): MutableMap<String, Any> = mutableMapOf(
        "role" to "tool",
        "tool_call_id" to id,
        "name" to "file_read",
        "content" to content
    )

    private fun assistantToolCall(id: String): MutableMap<String, Any> = mutableMapOf(
        "role" to "assistant",
        "content" to "",
        "tool_calls" to listOf(
            mapOf(
                "id" to id,
                "type" to "function",
                "function" to mapOf("name" to "file_read", "arguments" to "{}")
            )
        )
    )

    private fun conversation(toolResultCount: Int, contentSize: Int): MutableList<MutableMap<String, Any>> {
        val messages = mutableListOf<MutableMap<String, Any>>()
        messages.add(mutableMapOf("role" to "system", "content" to "系统提示"))
        messages.add(mutableMapOf("role" to "user", "content" to "读取这些文件"))
        repeat(toolResultCount) { index ->
            messages.add(assistantToolCall("call-$index"))
            messages.add(toolMessage("call-$index", bigContent(contentSize)))
        }
        return messages
    }

    @Test
    fun `预算为 0 时不做任何裁剪`() {
        val messages = conversation(toolResultCount = 8, contentSize = 20_000)

        assertNull(manageInLoopContextBudget(messages, budgetTokens = 0))
    }

    @Test
    fun `未超预算时不做任何裁剪`() {
        val messages = conversation(toolResultCount = 4, contentSize = 100)

        assertNull(manageInLoopContextBudget(messages, budgetTokens = 100_000))
    }

    @Test
    fun `超预算时从最旧的工具结果开始裁剪`() {
        val messages = conversation(toolResultCount = 20, contentSize = 8_000)
        val notice = manageInLoopContextBudget(messages, budgetTokens = 20_000)

        assertTrue("应报告裁剪情况: $notice", notice != null && notice.contains("裁剪"))
        val firstTool = messages.first { (it["role"] as? String) == "tool" }
        assertTrue(
            "最旧的工具结果正文应被替换为裁剪提示",
            (firstTool["content"] as? String)?.startsWith("[历史工具结果已因上下文预算裁剪") == true
        )
    }

    @Test
    fun `裁剪保留消息结构不被破坏`() {
        val messages = conversation(toolResultCount = 20, contentSize = 8_000)
        val sizeBefore = messages.size

        manageInLoopContextBudget(messages, budgetTokens = 20_000)

        assertEquals("裁剪不得增删消息", sizeBefore, messages.size)
        messages.filter { (it["role"] as? String) == "tool" }.forEach { message ->
            assertTrue("tool 消息必须保留 tool_call_id", message["tool_call_id"] is String)
            assertTrue("tool 消息必须保留非空 content", (message["content"] as? String)?.isNotEmpty() == true)
        }
    }

    @Test
    fun `最近的消息始终保留原文`() {
        val messages = conversation(toolResultCount = 20, contentSize = 8_000)

        manageInLoopContextBudget(messages, budgetTokens = 20_000)

        val lastTool = messages.last { (it["role"] as? String) == "tool" }
        assertEquals(
            "最新的工具结果必须保留原文，否则模型会失去当前进度",
            bigContent(8_000),
            lastTool["content"]
        )
    }

    @Test
    fun `结构消息不受裁剪影响`() {
        val messages = conversation(toolResultCount = 20, contentSize = 8_000)

        manageInLoopContextBudget(messages, budgetTokens = 20_000)

        assertEquals("系统提示不能被裁剪", "系统提示", messages.first()["content"])
        assertEquals("用户消息不能被裁剪", "读取这些文件", messages[1]["content"])
        assertTrue(
            "assistant tool_calls 必须保留，否则工具结果会成为孤儿",
            messages.any { (it["tool_calls"] as? List<*>)?.isNotEmpty() == true }
        )
    }
}
