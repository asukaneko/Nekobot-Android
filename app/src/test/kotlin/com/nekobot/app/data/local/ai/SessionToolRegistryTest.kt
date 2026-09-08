package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionToolRegistryTest {

    private class InMemoryStore {
        val map = mutableMapOf<String, Set<String>>()
        fun load(id: String): Set<String>? = map[id]
        fun save(id: String, enabled: Set<String>) { map[id] = enabled }
        fun clear(id: String) { map.remove(id) }
    }

    private fun registry(): Pair<SessionToolRegistry, InMemoryStore> {
        val store = InMemoryStore()
        val reg = SessionToolRegistry(
            loadEnabled = store::load,
            saveEnabled = store::save,
            clearEnabled = store::clear
        )
        return reg to store
    }

    @Test
    fun defaultIsAllToolsEnabled_whenNotCustomized() {
        val (reg, _) = registry()
        val all = SessionToolCatalog.ALL_TOOL_IDS
        assertNull(reg.enabledToolIds("s1"))
        assertFalse(reg.isCustomized("s1"))
        assertEquals(all, reg.effectiveEnabledToolIds("s1"))
    }

    @Test
    fun disablingCategoryRemovesItsTools() {
        val (reg, store) = registry()
        reg.setCategoryEnabled("s1", "browser", false)
        assertTrue(reg.isCustomized("s1"))
        assertFalse(reg.isToolEnabled("s1", "browser_use"))
        assertTrue(reg.isToolEnabled("s1", "get_weather"))
        // 已持久化
        assertFalse(store.map.getValue("s1").contains("browser_use"))
    }

    @Test
    fun categoryEnabledRequiresAllToolsEnabled() {
        val (reg, _) = registry()
        reg.setToolEnabled("s1", "todo_write", false)
        assertFalse(reg.isCategoryEnabled("s1", "task"))
        reg.setToolEnabled("s1", "todo_write", true)
        assertTrue(reg.isCategoryEnabled("s1", "task"))
    }

    @Test
    fun resetToAllClearsCustomization() {
        val (reg, store) = registry()
        reg.setCategoryEnabled("s1", "db", false)
        reg.resetToAll("s1")
        assertNull(store.map["s1"])
        assertNull(reg.enabledToolIds("s1"))
        assertEquals(SessionToolCatalog.ALL_TOOL_IDS, reg.effectiveEnabledToolIds("s1"))
    }

    @Test
    fun filterDefinitionsRemovesDisabledToolsAndKeepsUncategorized() {
        val (reg, _) = registry()
        reg.setCategoryEnabled("s1", "browser", false)
        val defs = listOf(
            mapOf("function" to mapOf("name" to "browser_use")),
            mapOf("function" to mapOf("name" to "get_weather")),
            // 未归类工具始终保留
            mapOf("function" to mapOf("name" to "mcp_custom_tool"))
        )
        val filtered = reg.filterDefinitions("s1", defs)
        val names = filtered.mapNotNull { toolNameOf(it) }
        assertFalse(names.contains("browser_use"))
        assertTrue(names.contains("get_weather"))
        assertTrue(names.contains("mcp_custom_tool"))
    }

    @Test
    fun filterDefinitionsReturnsAllWhenNotCustomized() {
        val (reg, _) = registry()
        val defs = listOf(
            mapOf("function" to mapOf("name" to "search_web")),
            mapOf("function" to mapOf("name" to "browser_use"))
        )
        assertEquals(defs, reg.filterDefinitions("s2", defs))
    }

    @Test
    fun toolNameOfExtractsFromBothStyles() {
        assertEquals("a", toolNameOf(mapOf("function" to mapOf("name" to "a"))))
        assertEquals("b", toolNameOf(mapOf("name" to "b")))
        assertNull(toolNameOf(mapOf("other" to "x")))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val ids = setOf("get_weather", "browser_use", "todo_write")
        val encoded = encodeToolSet(ids)
        assertEquals(ids, decodeToolSet(encoded))
        assertNull(decodeToolSet(null))
        assertNull(decodeToolSet(""))
        assertNull(decodeToolSet("not-json"))
    }
}