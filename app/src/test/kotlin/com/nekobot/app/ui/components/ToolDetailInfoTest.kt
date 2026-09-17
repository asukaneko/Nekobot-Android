package com.nekobot.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具详情（进度卡片步骤详情 / 工具详情弹窗）的数据来源测试。
 *
 * 验证「工具说明 + 输入参数」对本地工具都能解析出来：
 * 内置工具、Skill 工具、数据库工具都要有定义，避免详情里出现空参数或没有说明。
 */
class ToolDetailInfoTest {

    @Test
    fun `skill read resolves description and parameters`() {
        val description = localToolDefinitionDescription("skill_read")
        assertFalse("skill_read 应有工具说明", description.isNullOrBlank())

        val params = builtinToolParameterEntries("skill_read")
        assertEquals(listOf("skill_name", "file_path", "start_line", "end_line"), params.map { it.name })
        assertEquals(
            listOf("skill_name", "file_path"),
            params.filter { it.required }.map { it.name }
        )
    }

    @Test
    fun `skill tools without parameters resolve to empty list`() {
        assertEquals(emptyList<String>(), builtinToolParameterEntries("skill_list").map { it.name })
        assertEquals(emptyList<String>(), builtinToolParameterEntries("skill_get_info").map { it.name })
    }

    @Test
    fun `builtin tool parameters follow declaration order and required flags`() {
        val params = builtinToolParameterEntries("grep")
        assertEquals(
            listOf("pattern", "root", "path", "glob", "max_results", "case_sensitive"),
            params.map { it.name }
        )
        assertEquals(listOf("pattern"), params.filter { it.required }.map { it.name })
    }

    @Test
    fun `db tool parameters are resolvable`() {
        val params = builtinToolParameterEntries("db_get_character")
        assertEquals(listOf("character_id"), params.map { it.name })
        assertTrue(params.single().required)
        assertFalse(localToolDefinitionDescription("db_get_character").isNullOrBlank())
    }

    @Test
    fun `unknown dynamic tool has no local definition`() {
        assertEquals(emptyList<String>(), builtinToolParameterEntries("mcp__demo__unknown").map { it.name })
        assertEquals(null, localToolDefinitionDescription("mcp__demo__unknown"))
    }
}
