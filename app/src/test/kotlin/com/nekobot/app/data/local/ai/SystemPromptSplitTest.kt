package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * system 提示词的"稳定核 / 动态尾"拆分与缓存断点。
 *
 * 背景：以前 system 被拼成一条消息，且里面夹着秒级当前时间，
 * 于是 Anthropic 的缓存断点（打在 system 最后一块）每轮都失效，
 * OpenAI/DeepSeek 的前缀缓存也会在时间变化处断掉。
 */
class SystemPromptSplitTest {

    private fun item(key: String, content: String, priority: Int = 100) =
        PromptStack.PromptInjection(key = key, content = content, priority = priority)

    @Test
    fun `volatile items move to the tail and stable items keep their order`() {
        val items = listOf(
            item("agent.core", "核心规则", 10),
            item("agent.env", "现在 12:00:01", 80),
            item("skills.available", "技能目录", 80),
            item("agent.todos", "- 任务一", 80)
        )
        val split = splitSystemItems(listOf("角色卡基础"), items, fused = false)

        assertTrue(split.stable.startsWith("角色卡基础"))
        assertTrue(split.stable.contains("核心规则"))
        assertTrue(split.stable.contains("技能目录"))
        assertFalse("时间不应留在稳定核", split.stable.contains("12:00:01"))
        assertFalse("待办不应留在稳定核", split.stable.contains("任务一"))

        assertTrue(split.volatile.contains("12:00:01"))
        assertTrue(split.volatile.contains("任务一"))
        assertEquals(split.stable + "\n\n" + split.volatile, split.composed)
        assertEquals(listOf(split.stable, split.volatile), split.systemTexts)
    }

    @Test
    fun `without volatile content the message shape stays as before`() {
        val split = splitSystemItems(listOf("角色卡基础"), listOf(item("agent.core", "核心规则", 10)), fused = false)
        assertEquals("", split.volatile)
        assertEquals(1, split.systemTexts.size)
        assertEquals(split.stable, split.systemTexts.single())
    }

    @Test
    fun `empty everything still yields one system text`() {
        val split = splitSystemItems(emptyList(), emptyList(), fused = false)
        assertEquals("", split.composed)
        assertEquals(listOf(""), split.systemTexts)
    }

    @Test
    fun `cache breakpoint goes to the stable prefix when system is split`() {
        val stable = "稳".repeat(4_000)
        val volatile = "动".repeat(200)
        val blocks = anthropicSystemBlocks(listOf(stable, volatile))

        assertEquals(2, blocks.size)
        assertEquals(true, blocks[0].containsKey("cache_control"))
        assertFalse("动态尾不打点", blocks[1].containsKey("cache_control"))
        assertEquals(volatile, blocks[1]["text"])
    }

    @Test
    fun `single system block keeps its breakpoint`() {
        val blocks = anthropicSystemBlocks(listOf("稳".repeat(4_000)))
        assertEquals(1, blocks.size)
        assertEquals(true, blocks.single().containsKey("cache_control"))
    }

    @Test
    fun `short system prompts are not marked cacheable`() {
        val blocks = anthropicSystemBlocks(listOf("短", "也很短"))
        assertEquals(2, blocks.size)
        assertTrue(blocks.none { it.containsKey("cache_control") })
    }

    @Test
    fun `trimMessages keeps every leading system message`() {
        val stable = mapOf("role" to "system", "content" to "稳定核".repeat(400))
        val volatile = mapOf("role" to "system", "content" to "动态尾" + "x".repeat(200))
        val messages = listOf(
            stable,
            volatile,
            mapOf("role" to "user", "content" to "第一个问题"),
            mapOf("role" to "assistant", "content" to "y".repeat(2_000)),
            mapOf("role" to "user", "content" to "最新的问题")
        )

        val trimmed = trimMessages(messages, maxTotalChars = 1_500)

        assertTrue("稳定核必须保留", trimmed.contains(stable))
        assertTrue("动态尾必须保留（含当前时间/待办）", trimmed.contains(volatile))
        assertEquals("最新的问题", trimmed.last()["content"])
    }
}
