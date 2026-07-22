package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTokenUsageTest {

    @Test
    fun `reported usage accepts compatible aliases and number types`() {
        val result = resolveLocalTokenUsage(
            usage = mapOf(
                "prompt_tokens" to 125.0,
                "completion_tokens" to 25L,
                "total_tokens" to 150
            ),
            messages = listOf(mapOf("role" to "user", "content" to "不会采用估算")),
            outputText = "回复"
        )

        assertEquals(125, result.inputTokens)
        assertEquals(25, result.outputTokens)
        assertFalse(result.estimated)
    }

    @Test
    fun `missing usage falls back to request and response estimate`() {
        val result = resolveLocalTokenUsage(
            usage = emptyMap<String, Any>(),
            messages = listOf(mapOf("role" to "user", "content" to "测试文本")),
            outputText = "abcd"
        )

        assertTrue(result.inputTokens > 0)
        assertEquals(1, result.outputTokens)
        assertTrue(result.estimated)
    }

    @Test
    fun `current context starts from latest measured prompt instead of summing every turn`() {
        val messages = listOf(
            LocalContextTokenMessage("第一轮问题", null, null),
            LocalContextTokenMessage("第一轮回答", 100, 20),
            LocalContextTokenMessage("abcd", null, null)
        )

        assertEquals(125L, currentLocalContextTokens(messages))
    }

    @Test
    fun `text estimate handles mixed chinese and latin`() {
        assertEquals(4, estimateLocalTextTokens("测试文本"))
        assertEquals(1, estimateLocalTextTokens("abcd"))
        assertEquals(3, estimateLocalTextTokens("测试abcd"))
    }
}
