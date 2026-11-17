package com.nekobot.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageModelUsageTest {
    @Test
    fun jsonRoundTripKeepsEveryModelAndFailover() {
        val usage = MessageModelUsage(
            models = listOf(
                MessageModelUsageEntry(
                    model = "主模型", actualModel = "model-a",
                    inputTokens = 1200, outputTokens = 80,
                    cachedInputTokens = 300, cacheWriteTokens = 0, calls = 2
                ),
                MessageModelUsageEntry(
                    model = "备用模型", actualModel = "model-b",
                    inputTokens = 50, outputTokens = 5, calls = 1, fallback = true
                )
            ),
            failovers = listOf(MessageModelFailoverEntry(model = "主模型", actualModel = "model-a", statusCode = 429))
        )
        val parsed = parseMessageModelUsage(encodeMessageModelUsage(usage))
        assertEquals(usage, parsed)
    }

    @Test
    fun missingAndCorruptRecordsNeverThrow() {
        assertNull(parseMessageModelUsage(null))
        assertNull(parseMessageModelUsage(""))
        assertNull(parseMessageModelUsage("not json"))
        assertNull(parseMessageModelUsage("[]"))
        assertNull(parseMessageModelUsage("{}"))
        // 只有未知字段时不产生可展示内容
        assertNull(parseMessageModelUsage("""{"models":[],"failovers":[]}"""))
    }

    @Test
    fun unknownFieldsAndMissingValuesFallBackToDefaults() {
        val parsed = parseMessageModelUsage(
            """{"models":[{"model":"模型 A","input_tokens":12}],"failovers":[{"status_code":500}],"extra":1}"""
        )
        assertEquals(1, parsed!!.models.size)
        val entry = parsed.models.single()
        assertEquals("模型 A", entry.model)
        assertEquals("", entry.actualModel)
        assertEquals(12, entry.inputTokens)
        assertEquals(0, entry.outputTokens)
        assertEquals(0, entry.calls)
        assertTrue(!entry.fallback)
        assertEquals(500, parsed.failovers.single().statusCode)
    }

    @Test
    fun emptyUsageIsNotPersisted() {
        assertNull(encodeMessageModelUsage(null))
        assertNull(encodeMessageModelUsage(MessageModelUsage()))
        assertTrue(MessageModelUsage().isEmpty)
    }
}
