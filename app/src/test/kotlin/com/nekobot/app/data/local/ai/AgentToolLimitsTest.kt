package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具输出与进度卡预览的统一上限契约。
 *
 * 这些用例锁定「所有截断数字只有一个来源」这件事：默认值、硬上限语义、
 * 以及子代理进度卡不再绕过上限。
 */
class AgentToolLimitsTest {

    @Test
    fun `未初始化偏好时回落到统一默认值`() {
        assertEquals(AgentToolLimits.DEFAULT_TOOL_OUTPUT_CHARS, AgentToolLimits.toolOutputChars())
        assertEquals(
            AgentToolLimits.DEFAULT_PROGRESS_PREVIEW_CHARS,
            AgentToolLimits.progressPreviewChars()
        )
    }

    @Test
    fun `max_chars 缺省或非正数都按上限处理且无法突破上限`() {
        val limit = 5_000
        assertEquals(limit, AgentToolLimits.resolveRequestedMaxChars(0, limit))
        assertEquals(limit, AgentToolLimits.resolveRequestedMaxChars(-1, limit))
        assertEquals(2_000, AgentToolLimits.resolveRequestedMaxChars(2_000, limit))
        assertEquals(limit, AgentToolLimits.resolveRequestedMaxChars(999_999, limit))
    }

    @Test
    fun `默认上限落在可调范围内`() {
        assertTrue(
            AgentToolLimits.DEFAULT_TOOL_OUTPUT_CHARS in
                AgentToolLimits.MIN_TOOL_OUTPUT_CHARS..AgentToolLimits.MAX_TOOL_OUTPUT_CHARS
        )
        assertTrue(
            AgentToolLimits.DEFAULT_PROGRESS_PREVIEW_CHARS in
                AgentToolLimits.MIN_PROGRESS_PREVIEW_CHARS..AgentToolLimits.MAX_PROGRESS_PREVIEW_CHARS
        )
    }

    @Test
    fun `子代理进度卡结果同样受统一预览上限约束`() {
        val collector = SubagentProgressCollector("测试子代理")
        collector.onToolStart(
            mapOf(
                "name" to "workspace_read_file",
                "arguments" to mapOf("path" to "a".repeat(50_000))
            ),
            ""
        )
        collector.onToolResult(
            mapOf("name" to "workspace_read_file"),
            mapOf("content" to "x".repeat(500_000))
        )

        val toolStep = collector.steps().single { it.type == "tool" }
        // 修复前这里是 fullResult = result（未截断），子代理卡片会整体携带全量工具结果。
        assertTrue(
            toolStep.fullResult.toString().length <= AgentToolLimits.progressPreviewChars()
        )
        assertTrue(
            toolStep.arguments?.get("preview").toString().length <=
                AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS
        )
    }
}
