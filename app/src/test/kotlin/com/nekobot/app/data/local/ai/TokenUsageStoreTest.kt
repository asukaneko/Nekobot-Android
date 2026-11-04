package com.nekobot.app.data.local.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token 用量记录的保留策略与汇总口径。
 *
 * 旧实现按"最多 5000 条"硬切：条数相同但记录长度差别很大时体积压不住，
 * 而且每次写入都要全量 parse + commit。现在改成按字符预算按比例丢弃，
 * 并把"刚产生、还没落盘"的记录一起计入统计。
 */
class TokenUsageStoreTest {

    private fun record(index: Int, padding: Int = 0): JsonObject = JsonObject().apply {
        addProperty("id", "r$index")
        addProperty("session_id", "s1")
        addProperty("model", "demo-model")
        addProperty("input_tokens", 10)
        addProperty("output_tokens", index)
        addProperty("total_tokens", 10 + index)
        addProperty("timestamp", "2026-01-01T00:00:00")
        addProperty("date", "2026-01-01")
        if (padding > 0) addProperty("purpose", "p".repeat(padding))
    }

    private fun arrayOf(count: Int, padding: Int = 0): JsonArray =
        JsonArray().also { arr -> (1..count).forEach { arr.add(record(it, padding)) } }

    @Test
    fun `records under the budget are not touched`() {
        val arr = arrayOf(20)
        assertFalse(trimTokenUsageArray(arr, budgetChars = 1_000_000, minKeep = 5))
        assertEquals(20, arr.size())
    }

    @Test
    fun `records below minKeep are kept even when over budget`() {
        val arr = arrayOf(10, padding = 200)
        assertFalse(trimTokenUsageArray(arr, budgetChars = 10, minKeep = 10))
        assertEquals(10, arr.size())
    }

    @Test
    fun `over-budget records are trimmed from the oldest end`() {
        val arr = arrayOf(100, padding = 200)
        val newest = arr.last().toString()
        assertTrue(trimTokenUsageArray(arr, budgetChars = 3_000, minKeep = 10))
        assertTrue("至少保留最少条数", arr.size() >= 10)
        assertTrue("应丢弃最旧的一部分", arr.size() < 100)
        assertEquals("最新记录必须保留", newest, arr.last().toString())
    }

    @Test
    fun `total token helper sums only well-formed records`() {
        val arr = JsonArray().apply {
            add(record(1))
            add(record(2))
            add(com.google.gson.JsonPrimitive("not a record"))
            add(JsonObject().apply { addProperty("id", "no-total") })
        }
        // (10+1) + (10+2) = 23；非对象与缺字段按 0 计
        assertEquals(23L, tokenUsageTotalTokens(arr))
    }

    @Test
    fun `missing total_tokens reads as zero`() {
        assertEquals(0L, JsonObject().totalTokensOrZero())
        assertEquals(0L, JsonObject().apply { addProperty("total_tokens", "abc") }.totalTokensOrZero())
    }
}
