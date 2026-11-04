package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB 工具的分页与输出预算。
 *
 * 之前 `db_list_*` / `db_get_world_book` 会把整库正文一次性塞进上下文，
 * 一条工具结果就足以撑爆窗口。这里锁定新的行为：默认一页、可翻页、
 * 单条结果有硬上限。
 */
class DbToolPaginationTest {

    private fun items(count: Int, padding: Int = 0): List<Map<String, Any>> = (1..count).map { index ->
        mapOf("id" to "id-$index", "name" to "name-$index", "content" to "x".repeat(padding))
    }

    @Test
    fun `limit defaults to one page and is clamped`() {
        assertEquals(DB_TOOL_DEFAULT_PAGE_SIZE, dbPageLimit(emptyMap()))
        assertEquals(5, dbPageLimit(mapOf("limit" to 5)))
        assertEquals(5, dbPageLimit(mapOf("limit" to "5")))
        assertEquals(DB_TOOL_MAX_PAGE_SIZE, dbPageLimit(mapOf("limit" to 9_999)))
        assertEquals(1, dbPageLimit(mapOf("limit" to 0)))
        assertEquals(1, dbPageLimit(mapOf("limit" to -3)))
    }

    @Test
    fun `offset defaults to zero and never goes negative`() {
        assertEquals(0, dbPageOffset(emptyMap()))
        assertEquals(40, dbPageOffset(mapOf("offset" to 40)))
        assertEquals(40, dbPageOffset(mapOf("offset" to "40")))
        assertEquals(0, dbPageOffset(mapOf("offset" to -10)))
    }

    @Test
    fun `page reports total and next offset for the rest of the library`() {
        val all = items(45)
        val first = dbToolPage(mapOf("limit" to 20), all, budgetChars = 1_000_000)
        assertEquals(20, first.items.size)
        assertEquals(45, first.total)
        assertEquals(0, first.offset)
        assertTrue(first.hasMore)
        assertEquals(20, first.nextOffset)

        val last = dbToolPage(mapOf("limit" to 20, "offset" to 40), all, budgetChars = 1_000_000)
        assertEquals(5, last.items.size)
        assertFalse(last.hasMore)
        assertNull(last.nextOffset)

        val beyond = dbToolPage(mapOf("limit" to 20, "offset" to 500), all, budgetChars = 1_000_000)
        assertTrue(beyond.items.isEmpty())
        assertEquals(45, beyond.total)
        assertFalse(beyond.hasMore)
    }

    @Test
    fun `a page larger than the output budget shrinks but keeps paging semantics`() {
        val all = items(20, padding = 5_000)
        val page = dbToolPage(mapOf("limit" to 20), all, budgetChars = 20_000)
        assertTrue("应触发预算收缩", page.truncatedByBudget)
        assertTrue("收缩后至少保留 1 条", page.items.isNotEmpty())
        assertTrue("收缩后条数应少于 20", page.items.size < 20)
        assertEquals(20, page.total)
        assertEquals(0, page.offset)
        assertTrue(page.hasMore)
    }

    @Test
    fun `result keeps count and key for backward compatibility`() {
        val page = dbToolPage(mapOf("limit" to 2), items(5), budgetChars = 1_000_000)
        val result = dbPagedResult("characters", page)
        assertEquals(true, result["success"])
        assertEquals(2, result["count"])
        assertEquals(5, result["total"])
        assertEquals(0, result["offset"])
        assertEquals(2, result["limit"])
        assertEquals(true, result["has_more"])
        assertEquals(2, result["next_offset"])
        assertTrue(result["characters"] is List<*>)
    }

    @Test
    fun `single object payloads are clipped instead of flooding the context`() {
        val small = mapOf("success" to true, "character" to mapOf("name" to "角色"))
        assertTrue(boundDbToolResult(small, budgetChars = 1_000) === small)

        val big = mapOf(
            "success" to true,
            "character" to mapOf("system_prompt" to "提示".repeat(30_000), "name" to "角色")
        )
        val bounded = boundDbToolResult(big, budgetChars = 10_000)
        assertEquals(true, bounded["output_clipped"])
        assertEquals(true, bounded["success"])
        // 结构还在，长字段被截断
        @Suppress("UNCHECKED_CAST")
        val character = bounded["character"] as Map<String, Any>
        assertTrue((character["system_prompt"] as String).length < 30_000)
        assertEquals("角色", character["name"])
    }

    @Test
    fun `payloads that cannot be clipped are rejected with a structured error`() {
        val payload = mapOf(
            "success" to true,
            "memories" to (1..500).map { mapOf("id" to "m-$it", "content" to "y".repeat(2_000)) }
        )
        val bounded = boundDbToolResult(payload, budgetChars = 1_000)
        assertEquals(false, bounded["success"])
        assertEquals("OUTPUT_TOO_LARGE", bounded["error_code"])
        assertTrue((bounded["error"] as String).contains("缩小查询范围"))
    }

    @Test
    fun `clipLongText keeps structure and marks truncated strings`() {
        val clipped = clipLongText(
            mapOf("items" to listOf(mapOf("text" to "z".repeat(50))), "empty" to null),
            perFieldChars = 10
        ) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val first = (clipped["items"] as List<*>).first() as Map<*, *>
        val text = first["text"] as String
        assertTrue(text.startsWith("zzzzzzzzzz"))
        assertTrue(text.contains("共 50 字符"))
        assertNull(clipped["empty"])
    }
}
