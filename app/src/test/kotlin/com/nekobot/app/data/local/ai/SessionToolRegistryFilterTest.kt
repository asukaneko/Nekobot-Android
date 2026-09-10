package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话工具集过滤语义：静态工具按勾选过滤，运行期出现的 MCP 工具默认可用且可被关闭。
 */
class SessionToolRegistryFilterTest {

    private val enabledRecords = mutableMapOf<String, Set<String>>()
    private val touchedRecords = mutableMapOf<String, Set<String>>()

    private val registry = SessionToolRegistry(
        loadEnabled = { sessionId -> enabledRecords[sessionId] },
        saveEnabled = { sessionId, ids -> enabledRecords[sessionId] = ids },
        clearEnabled = { sessionId -> enabledRecords.remove(sessionId) },
        loadTouchedCategories = { sessionId -> touchedRecords[sessionId] },
        saveTouchedCategories = { sessionId, ids -> touchedRecords[sessionId] = ids }
    )

    private val mcpToolId = "mcp__0badf00d__unit_filter_tool"
    private val sessionId = "session-1"

    init {
        SessionToolCatalog.registerDynamicTools(SessionToolCatalog.MCP_CATEGORY_ID, listOf(mcpToolId))
    }

    private fun definition(id: String): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to id,
            "description" to id,
            "parameters" to emptyMap<String, Any>()
        )
    )

    private fun filtered(vararg ids: String): List<String> =
        registry.filterDefinitions(sessionId, ids.map(::definition))
            .mapNotNull { toolNameOf(it) }

    @Test
    fun `未自定义时全部保留`() {
        val kept = filtered("file_read", "exec_command", mcpToolId, "vendor_custom_tool")

        assertEquals(listOf("file_read", "exec_command", mcpToolId, "vendor_custom_tool"), kept)
    }

    @Test
    fun `自定义后按勾选过滤静态工具`() {
        registry.setToolEnabled(sessionId, "exec_command", false)

        val kept = filtered("file_read", "exec_command", mcpToolId)

        assertEquals("被关闭的静态工具必须过滤掉", listOf("file_read", mcpToolId), kept)
        assertTrue(registry.isCustomized(sessionId))
    }

    @Test
    fun `未归类工具始终保留`() {
        registry.setToolEnabled(sessionId, "exec_command", false)

        assertTrue(filtered("vendor_custom_tool").contains("vendor_custom_tool"))
    }

    @Test
    fun `未被改动的 MCP 大类默认启用`() {
        // 用户先自定义了静态工具，随后才接入 MCP —— 新出现的 MCP 工具不能因此被静默禁用
        registry.setToolEnabled(sessionId, "exec_command", false)

        assertTrue("未改动过的 MCP 大类应保持默认启用", filtered(mcpToolId).contains(mcpToolId))
        assertTrue(registry.isToolEnabled(sessionId, mcpToolId))
    }

    @Test
    fun `关闭 MCP 大类后其工具被过滤且新工具同样禁用`() {
        registry.setCategoryEnabled(sessionId, SessionToolCatalog.MCP_CATEGORY_ID, false)

        assertFalse(registry.isToolEnabled(sessionId, mcpToolId))
        assertTrue(filtered(mcpToolId).isEmpty())

        // 之后 MCP 服务器又暴露了新工具：大类已被显式关闭，新工具同样不注入
        val laterToolId = "mcp__0badf00d__later_tool"
        SessionToolCatalog.registerDynamicTools(SessionToolCatalog.MCP_CATEGORY_ID, listOf(laterToolId))
        assertTrue(filtered(laterToolId).isEmpty())
    }

    @Test
    fun `重新开启 MCP 大类后工具恢复可用`() {
        registry.setCategoryEnabled(sessionId, SessionToolCatalog.MCP_CATEGORY_ID, false)
        registry.setCategoryEnabled(sessionId, SessionToolCatalog.MCP_CATEGORY_ID, true)

        assertTrue(filtered(mcpToolId).contains(mcpToolId))
        assertTrue(registry.isCategoryEnabled(sessionId, SessionToolCatalog.MCP_CATEGORY_ID))
    }

    @Test
    fun `恢复全部启用会清除自定义与改动记录`() {
        registry.setCategoryEnabled(sessionId, SessionToolCatalog.MCP_CATEGORY_ID, false)
        registry.resetToAll(sessionId)

        assertFalse("恢复全部启用后不应再保留自定义记录", registry.isCustomized(sessionId))
        assertTrue(registry.touchedCategoryIds(sessionId).isEmpty())
        assertTrue(filtered(mcpToolId).contains(mcpToolId))
    }
}
