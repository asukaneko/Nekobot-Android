package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.BuiltinTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具注册一致性：定义（schema）、可执行实现、会话工具集目录三方必须对齐。
 *
 * 历史上出现过两类漂移：
 * 1. `android_step` 有实现、有开关，但没有 schema —— 模型永远看不到它；
 * 2. `save_to_memory` / `read_memory` / `workspace_skill_copy` 有 schema、有开关，
 *    但没有执行分派 —— 用户关掉它没有任何效果。
 */
class SessionToolCatalogConsistencyTest {

    private fun executableIds(): Set<String> = buildSet {
        addAll(localExecutableToolIds)
        addAll(localDbToolIds)
        addAll(localSkillToolIds)
        addAll(subagentToolIds)
    }

    @Test
    fun `工具集目录里的工具都有可执行实现`() {
        val dead = SessionToolCatalog.staticToolIds - executableIds()
        assertTrue("工具集目录存在没有实现的死条目（开关无效）: $dead", dead.isEmpty())
    }

    @Test
    fun `可执行工具都有 function-calling 定义`() {
        val definedIds = buildLocalAgentToolDefinitions()
            .mapNotNull { toolNameOf(it) }
            .toSet()
        val missing = localExecutableToolIds - definedIds
        assertTrue("以下可执行工具缺少 BuiltinTools 定义，模型看不到它: $missing", missing.isEmpty())
    }

    @Test
    fun `会话工具集目录覆盖全部会话工具`() {
        val unmanaged = executableIds() - SessionToolCatalog.staticToolIds
        assertTrue("以下工具不在目录中，用户无法关闭它们: $unmanaged", unmanaged.isEmpty())
    }

    @Test
    fun `工具 id 在各大类之间不重复`() {
        val all = SessionToolCatalog.categories.flatMap { it.toolIds }
        assertEquals("工具 id 不应同时出现在多个大类", all.size, all.toSet().size)
    }

    @Test
    fun `内置工具定义都带必填参数与合法 schema`() {
        buildLocalAgentToolDefinitions().forEach { definition ->
            val function = definition["function"] as Map<*, *>
            val name = function["name"]
            assertTrue("工具定义缺少名称", name is String && name.isNotBlank())
            val parameters = function["parameters"]
            assertTrue("工具 $name 的 parameters 不是对象", parameters is Map<*, *>)
            assertEquals("工具 $name 的 parameters.type 必须是 object", "object", (parameters as Map<*, *>)["type"])
        }
    }

    @Test
    fun `MCP 工具按前缀归入 MCP 大类并受工具集管辖`() {
        val toolId = "mcp__deadbeef__search_docs"
        assertEquals(SessionToolCatalog.MCP_CATEGORY_ID, SessionToolCatalog.categoryIdOf(toolId))
        assertTrue("MCP 工具必须受会话工具集管辖", SessionToolCatalog.isManagedTool(toolId))
    }

    @Test
    fun `注册后的动态工具会出现在目录中`() {
        val toolId = "mcp__cafebabe__unit_test_tool"
        SessionToolCatalog.registerDynamicTools(SessionToolCatalog.MCP_CATEGORY_ID, listOf(toolId))

        val category = SessionToolCatalog.categoryById(SessionToolCatalog.MCP_CATEGORY_ID)
        assertTrue("动态注册的工具应出现在 MCP 大类里", category?.toolIds?.contains(toolId) == true)
        assertTrue("动态工具必须计入全部工具集合", toolId in SessionToolCatalog.ALL_TOOL_IDS)
        assertTrue(SessionToolCatalog.allCategoryIds.contains(SessionToolCatalog.MCP_CATEGORY_ID))
    }
}
