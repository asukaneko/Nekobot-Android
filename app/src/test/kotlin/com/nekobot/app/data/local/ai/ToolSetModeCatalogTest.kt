package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具集模式目录：内置模式的构成、模式匹配（含自定义优先）与自定义模式编解码。
 */
class ToolSetModeCatalogTest {

    private val modes = ToolSetModeCatalog.builtinModes()

    private fun mode(id: String): ToolSetMode = modes.first { it.id == id }

    private fun toolsOf(id: String): Set<String> = mode(id).toolIds

    @Test
    fun `内置模式的工具 id 都来自当前工具目录`() {
        val known = SessionToolCatalog.staticToolIds
        modes.forEach { mode ->
            assertTrue("模式 ${mode.id} 不应为空", mode.toolIds.isNotEmpty())
            val unknown = mode.toolIds - known
            assertTrue("模式 ${mode.id} 含目录外的工具：$unknown", unknown.isEmpty())
        }
    }

    @Test
    fun `极简是标准的子集且不联网不碰文件`() {
        val minimal = toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID)
        val standard = toolsOf(ToolSetModeCatalog.STANDARD_MODE_ID)

        assertTrue("极简必须完全包含在标准内", standard.containsAll(minimal))
        assertFalse(minimal.contains("search_web"))
        assertFalse(minimal.contains("file_read"))
        assertFalse(minimal.any { it.startsWith("android_") })
        assertFalse(minimal.any { it.startsWith("db_") })
    }

    @Test
    fun `标准排除安卓数据库与命令执行但保留文件工具`() {
        val standard = toolsOf(ToolSetModeCatalog.STANDARD_MODE_ID)

        assertFalse(standard.any { it.startsWith("android_") })
        assertFalse(standard.any { it.startsWith("db_") })
        assertFalse(standard.contains("exec_command"))
        assertFalse(standard.contains("shell_job"))
        assertTrue(standard.contains("file_read"))
        assertTrue(standard.contains("search_web"))
        assertTrue(mode(ToolSetModeCatalog.STANDARD_MODE_ID).includeDynamic)
    }

    @Test
    fun `安卓模式包含屏幕操作且不含数据库`() {
        val android = toolsOf(ToolSetModeCatalog.ANDROID_MODE_ID)

        assertTrue(android.contains("android_ui_click"))
        assertTrue(android.contains("android_open_app"))
        assertTrue(android.contains("android_screenshot"))
        assertTrue(android.contains("search_web"))
        assertFalse(android.any { it.startsWith("db_") })
        assertFalse(mode(ToolSetModeCatalog.ANDROID_MODE_ID).includeDynamic)
    }

    @Test
    fun `角色卡模式在标准基础上增加数据管理工具`() {
        val standard = toolsOf(ToolSetModeCatalog.STANDARD_MODE_ID)
        val character = toolsOf(ToolSetModeCatalog.CHARACTER_MODE_ID)

        // 角色卡 = 标准 + 数据管理（db 大类），可以编辑/新建角色卡与世界书
        assertTrue("角色卡必须包含全部标准工具", character.containsAll(standard))
        assertTrue(character.contains("db_create_character"))
        assertTrue(character.contains("db_update_character"))
        assertTrue(character.contains("db_delete_character"))
        assertTrue(character.contains("db_create_world_book"))
        assertTrue(character.contains("db_upsert_world_book_entry"))
        assertTrue(character.containsAll(SessionToolCatalog.categoryById("db")!!.toolIds))
        // 依然不含安卓自动化与命令执行
        assertFalse(character.any { it.startsWith("android_") })
        assertFalse(character.contains("exec_command"))
        assertFalse(character.contains("shell_job"))
        // 比全能少：安卓自动化 + 命令执行
        assertTrue(character.size < toolsOf(ToolSetModeCatalog.ALL_MODE_ID).size)
        assertFalse(character.contains("android_screenshot"))
        assertTrue(mode(ToolSetModeCatalog.CHARACTER_MODE_ID).includeDynamic)
    }

    @Test
    fun `极简模式只保留记忆时间与待办`() {
        val minimal = toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID)

        assertTrue(minimal.contains("agent_memory_read"))
        assertTrue(minimal.contains("agent_memory_update"))
        assertTrue(minimal.contains("get_date_time"))
        assertFalse(minimal.any { it.startsWith("db_") })
        assertFalse(mode(ToolSetModeCatalog.MINIMAL_MODE_ID).includeDynamic)
    }

    @Test
    fun `全能模式覆盖全部静态工具并放行动态大类`() {
        val all = mode(ToolSetModeCatalog.ALL_MODE_ID)

        assertEquals(SessionToolCatalog.staticToolIds, all.toolIds)
        assertTrue(all.includeDynamic)
    }

    @Test
    fun `模式匹配按内容精确判断且自定义优先`() {
        // 全部静态工具 + 动态放行 == 全能
        assertEquals(
            ToolSetModeCatalog.ALL_MODE_ID,
            ToolSetModeCatalog.matchModeId(modes, SessionToolCatalog.staticToolIds, dynamicOn = true)
        )
        assertEquals(
            ToolSetModeCatalog.MINIMAL_MODE_ID,
            ToolSetModeCatalog.matchModeId(modes, toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID), dynamicOn = false)
        )
        // 内容相同但动态大类策略不同，不算匹配
        assertNull(
            ToolSetModeCatalog.matchModeId(modes, toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID), dynamicOn = true)
        )
        // 自定义模式与前缀优先：同内容时展示用户自己起的名字
        val custom = ToolSetModeCatalog.customMode(
            CustomToolSetModeRecord(
                id = "custom:abcd1234",
                name = "我的极简",
                toolIds = toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID).toList(),
                includeDynamic = false
            )
        )
        assertEquals(
            custom.id,
            ToolSetModeCatalog.matchModeId(
                listOf(custom) + modes,
                toolsOf(ToolSetModeCatalog.MINIMAL_MODE_ID),
                dynamicOn = false
            )
        )
        // 完全对不上就是“自定义”
        assertNull(ToolSetModeCatalog.matchModeId(modes, setOf("get_weather"), dynamicOn = false))
    }

    @Test
    fun `自定义模式编解码往返且容错`() {
        val records = listOf(
            CustomToolSetModeRecord("custom:1", "写作助手", listOf("get_weather", "todo_write"), false),
            CustomToolSetModeRecord("custom:2", "代码审查", listOf("file_read"), true)
        )
        assertEquals(records, ToolSetModeCatalog.decodeCustomModes(ToolSetModeCatalog.encodeCustomModes(records)))
        assertTrue(ToolSetModeCatalog.decodeCustomModes(null).isEmpty())
        assertTrue(ToolSetModeCatalog.decodeCustomModes("").isEmpty())
        assertTrue(ToolSetModeCatalog.decodeCustomModes("not-json").isEmpty())
        // 缺字段/空名字的记录会被丢弃而不是让面板崩掉
        assertTrue(ToolSetModeCatalog.decodeCustomModes("""[{"id":"custom:3","name":"","toolIds":[]}]""").isEmpty())
    }

    @Test
    fun `新建的自定义模式 id 带前缀且不重复`() {
        val first = ToolSetModeCatalog.newCustomModeId()
        val second = ToolSetModeCatalog.newCustomModeId()

        assertTrue(first.startsWith(ToolSetModeCatalog.CUSTOM_MODE_PREFIX))
        assertTrue(first != second)
    }
}
