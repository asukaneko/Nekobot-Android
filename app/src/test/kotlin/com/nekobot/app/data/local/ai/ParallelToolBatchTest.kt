package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并行工具批次与全局记忆检索：只读工具可并行，长期记忆按相关性注入。
 */
class ParallelToolBatchTest {

    private fun call(name: String): Map<String, Any> = mapOf(
        "id" to name,
        "name" to name,
        "arguments" to emptyMap<String, Any>()
    )

    @Test
    fun `只读工具判定为可并行`() {
        assertTrue(isParallelSafeTool("file_read"))
        assertTrue(isParallelSafeTool("workspace_read_file"))
        assertTrue(isParallelSafeTool("search_web"))
        assertTrue(isParallelSafeTool("db_list_characters"))
        assertTrue(isParallelSafeTool("db_get_character"))
        assertTrue(isParallelSafeTool("db_token_stats"))
    }

    @Test
    fun `有副作用的工具不可并行`() {
        assertFalse(isParallelSafeTool("exec_command"))
        assertFalse(isParallelSafeTool("file_write"))
        assertFalse(isParallelSafeTool("workspace_delete_file"))
        assertFalse(isParallelSafeTool("browser_use"))
        assertFalse(isParallelSafeTool("subagent"))
        assertFalse(isParallelSafeTool("ask_user_question"))
        assertFalse(isParallelSafeTool("db_delete_character"))
        assertFalse(isParallelSafeTool("mcp__deadbeef__anything"))
        assertFalse(isParallelSafeTool(""))
    }

    @Test
    fun `连续只读调用合并为一个批次`() {
        val calls = listOf(call("file_read"), call("file_read"), call("db_list_hooks"), call("file_write"))

        assertEquals(3, nextParallelToolBatch(calls, 0).size)
        assertEquals(1, nextParallelToolBatch(calls, 3).size)
    }

    @Test
    fun `批次有条数上限`() {
        val calls = List(10) { call("file_read") }

        assertEquals(MAX_PARALLEL_TOOL_CALLS_FOR_TEST, nextParallelToolBatch(calls, 0).size)
    }

    @Test
    fun `副作用工具独占一个批次`() {
        val calls = listOf(call("exec_command"), call("file_read"))

        assertEquals(1, nextParallelToolBatch(calls, 0).size)
        assertEquals(1, nextParallelToolBatch(calls, 1).size)
    }

    private companion object {
        const val MAX_PARALLEL_TOOL_CALLS_FOR_TEST = 4
    }
}

/**
 * 全局记忆检索：整份记忆超预算时，按与当前请求的相关性挑选小节。
 */
class GlobalAgentMemorySelectionTest {

    private val memory = buildString {
        appendLine("# 沟通偏好")
        appendLine("用户喜欢简洁的中文回复，不要客套话。")
        appendLine()
        appendLine("# 项目背景")
        appendLine("正在开发一个 Android 应用 Nekobot，使用 Kotlin 与 Compose。")
        appendLine()
        appendLine("# 部署流程")
        appendLine("发布 APK 前需要先跑单元测试，然后构建 release 包并用脚本发送。")
        appendLine()
        appendLine("# 无关内容")
        appendLine("用户养了两只猫，分别叫小白和小黑。")
    }

    @Test
    fun `预算内的记忆原样保留`() {
        val small = "# 偏好\n喜欢简洁回复"

        assertEquals(small, selectRelevantMemorySections(small, query = "回复", budgetChars = 1_000))
    }

    @Test
    fun `结果不会超过预算`() {
        val selected = selectRelevantMemorySections(memory, query = "APK 发布", budgetChars = 60)

        assertTrue("预算 60 字符时结果不应超限: ${selected.length}", selected.length <= 60)
    }

    @Test
    fun `相关小节优先于无关小节`() {
        val selected = selectRelevantMemorySections(
            memory,
            query = "APK 发布前要做什么？",
            budgetChars = 120
        )

        assertTrue("应命中部署流程小节: $selected", selected.contains("单元测试"))
        assertFalse("无关小节不应挤掉相关小节: $selected", selected.contains("两只猫"))
    }

    @Test
    fun `没有查询时按原文顺序注入前面的小节`() {
        val selected = selectRelevantMemorySections(memory, query = "", budgetChars = 60)

        assertTrue("应按顺序保留开头小节: $selected", selected.contains("沟通偏好"))
        assertTrue(selected.length <= 60)
    }
}
