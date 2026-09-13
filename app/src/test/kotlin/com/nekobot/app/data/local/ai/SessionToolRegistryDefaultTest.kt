package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新会话默认工具集」语义：会话没有单独自定义时回退到全局默认，
 * 两者都没有记录时保持既有行为（全部工具启用）。
 */
class SessionToolRegistryDefaultTest {

    private val enabledRecords = mutableMapOf<String, Set<String>>()
    private val touchedRecords = mutableMapOf<String, Set<String>>()

    private fun registry(
        default: Set<String>?,
        defaultTouched: Set<String>? = null
    ): SessionToolRegistry = SessionToolRegistry(
        loadEnabled = { sessionId -> enabledRecords[sessionId] },
        saveEnabled = { sessionId, ids -> enabledRecords[sessionId] = ids },
        clearEnabled = { sessionId -> enabledRecords.remove(sessionId) },
        loadTouchedCategories = { sessionId -> touchedRecords[sessionId] },
        saveTouchedCategories = { sessionId, ids -> touchedRecords[sessionId] = ids },
        loadDefaultEnabled = { default },
        loadDefaultTouchedCategories = { defaultTouched }
    )

    private fun definition(id: String): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to id,
            "description" to id,
            "parameters" to emptyMap<String, Any>()
        )
    )

    private fun filtered(reg: SessionToolRegistry, vararg ids: String): List<String> =
        reg.filterDefinitions("s1", ids.map(::definition)).mapNotNull { toolNameOf(it) }

    @Test
    fun `未设置默认时保持全部启用`() {
        val reg = registry(default = null)

        assertNull(reg.enabledToolIds("s1"))
        assertFalse(reg.hasDefault())
        assertFalse(reg.isCustomized("s1"))
        assertEquals(SessionToolCatalog.ALL_TOOL_IDS, reg.effectiveEnabledToolIds("s1"))
    }

    @Test
    fun `未自定义的会话跟随新会话默认工具集`() {
        val default = setOf("get_weather", "browser_use")
        val reg = registry(default = default)

        assertEquals(default, reg.enabledToolIds("s1"))
        assertTrue(reg.hasDefault())
        assertFalse("跟随默认不等于会话已自定义", reg.isCustomized("s1"))
        // 默认里没有的静态工具必须被排除
        assertFalse(reg.isToolEnabled("s1", "db_list_characters"))
        assertTrue(reg.isToolEnabled("s1", "browser_use"))
    }

    @Test
    fun `会话单独自定义后优先于默认`() {
        val reg = registry(default = setOf("get_weather", "browser_use"))
        reg.setToolEnabled("s1", "browser_use", false)

        val enabled = reg.enabledToolIds("s1")
        assertTrue(reg.isCustomized("s1"))
        assertFalse(enabled!!.contains("browser_use"))
        assertTrue(enabled.contains("get_weather"))
    }

    @Test
    fun `恢复默认会清除会话级自定义并回到默认工具集`() {
        val default = setOf("get_weather")
        val reg = registry(default = default)

        reg.setCategoryEnabled("s1", "browser", true)
        assertTrue(reg.enabledToolIds("s1")!!.contains("browser_use"))

        reg.resetToAll("s1")
        assertFalse(reg.isCustomized("s1"))
        assertEquals(default, reg.enabledToolIds("s1"))
    }

    @Test
    fun `工具定义按默认工具集过滤`() {
        val reg = registry(default = setOf("get_weather"))

        assertEquals(listOf("get_weather"), filtered(reg, "get_weather", "browser_use"))
        // 未归类工具不受工具集管辖，始终保留
        assertEquals(listOf("vendor_custom_tool"), filtered(reg, "vendor_custom_tool"))
    }

    @Test
    fun `默认工具集关闭过的动态大类不会再被默认放行`() {
        val mcpToolId = "mcp__unit_default__ping"
        SessionToolCatalog.registerDynamicTools(SessionToolCatalog.MCP_CATEGORY_ID, listOf(mcpToolId))

        // 默认工具集显式改动过 MCP 大类：新会话不得因为“动态默认启用”而拿回该工具
        val off = registry(default = setOf("get_weather"), defaultTouched = setOf("mcp"))
        assertTrue(filtered(off, mcpToolId).isEmpty())

        // 默认工具集从未改动过 MCP 大类：保持既有“动态工具默认可用”的语义
        val untouched = registry(default = setOf("get_weather"), defaultTouched = null)
        assertTrue(filtered(untouched, mcpToolId).contains(mcpToolId))
    }
}
