package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文占比的统一口径：系统提示词 / 工具定义 / 消息 / 工具轨迹只计一次，
 * 且总数等于各部分之和（圆环与分析行的分母必须一致）。
 */
class ContextUsageAccountingTest {

    private fun ContextUsageBreakdown.tokensOf(part: ContextUsagePart): Int =
        parts.firstOrNull { it.part == part }?.tokens ?: 0

    private fun ContextUsageBreakdown.countOf(part: ContextUsagePart): Int =
        parts.firstOrNull { it.part == part }?.itemCount ?: 0

    @Test
    fun `逐条落库的工具轨迹覆盖锚点消息上的折叠历史，不重复计入`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 0,
            messages = listOf(
                ContextUsageMessageRow("user", "帮我改一下这个函数"),
                ContextUsageMessageRow("assistant", "好的", toolHistoryTokens = 1_000, toolHistoryCount = 2)
            ),
            toolTrajectoryTokens = 1_200,
            toolTrajectoryCount = 3
        )

        assertEquals(1_200, breakdown.tokensOf(ContextUsagePart.TOOL_TRAJECTORY))
        assertEquals(3, breakdown.countOf(ContextUsagePart.TOOL_TRAJECTORY))
    }

    @Test
    fun `没有落库轨迹时改用消息上折叠的历史`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 0,
            messages = listOf(
                ContextUsageMessageRow("assistant", "上一轮回答", toolHistoryTokens = 1_000, toolHistoryCount = 2)
            )
        )

        assertEquals(1_000, breakdown.tokensOf(ContextUsagePart.TOOL_TRAJECTORY))
        assertEquals(2, breakdown.countOf(ContextUsagePart.TOOL_TRAJECTORY))
    }

    @Test
    fun `落库轨迹只覆盖最后一条助手消息，更早轮次的历史仍然计入`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 0,
            messages = listOf(
                ContextUsageMessageRow("assistant", "上一轮", toolHistoryTokens = 500, toolHistoryCount = 1),
                ContextUsageMessageRow("user", "继续"),
                ContextUsageMessageRow("assistant", "本轮", toolHistoryTokens = 7_000, toolHistoryCount = 9)
            ),
            toolTrajectoryTokens = 1_200,
            toolTrajectoryCount = 3
        )

        assertEquals(1_700, breakdown.tokensOf(ContextUsagePart.TOOL_TRAJECTORY))
        assertEquals(4, breakdown.countOf(ContextUsagePart.TOOL_TRAJECTORY))
    }

    @Test
    fun `系统提示词与工具定义都进入总数`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 4_321,
            messages = listOf(
                ContextUsageMessageRow("user", "你好"),
                ContextUsageMessageRow("assistant", "在的")
            ),
            toolDefinitionTokens = 31_738,
            toolDefinitionCount = 116
        )

        assertEquals(4_321, breakdown.tokensOf(ContextUsagePart.SYSTEM_PROMPT))
        assertEquals(31_738, breakdown.tokensOf(ContextUsagePart.TOOL_DEFINITIONS))
        assertEquals(116, breakdown.countOf(ContextUsagePart.TOOL_DEFINITIONS))
        assertEquals(breakdown.parts.sumOf { it.tokens }, breakdown.totalTokens)
    }

    @Test
    fun `消息按角色归类且压缩摘要单独成项`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 0,
            messages = listOf(
                ContextUsageMessageRow("system", "【历史对话摘要】早前发生的事", isSummary = true),
                ContextUsageMessageRow("user", "第一个问题"),
                ContextUsageMessageRow("assistant", "第一个回答"),
                ContextUsageMessageRow("user", "第二个问题")
            )
        )

        assertEquals(1, breakdown.countOf(ContextUsagePart.SUMMARY))
        assertEquals(2, breakdown.countOf(ContextUsagePart.USER_MESSAGES))
        assertEquals(1, breakdown.countOf(ContextUsagePart.ASSISTANT_MESSAGES))
        assertTrue(breakdown.tokensOf(ContextUsagePart.SUMMARY) > 0)
        assertTrue(breakdown.tokensOf(ContextUsagePart.USER_MESSAGES) > 0)
    }

    @Test
    fun `非 Agent 会话使用更小的消息开销且没有工具定义`() {
        val messages = listOf(ContextUsageMessageRow("user", "普通聊天"))
        val agent = buildContextUsageBreakdown(true, 0, messages)
        val standard = buildContextUsageBreakdown(false, 0, messages)

        assertEquals(0, standard.tokensOf(ContextUsagePart.TOOL_DEFINITIONS))
        assertEquals(
            CONTEXT_USAGE_AGENT_OVERHEAD_TOKENS - CONTEXT_USAGE_STANDARD_OVERHEAD_TOKENS,
            agent.totalTokens - standard.totalTokens
        )
    }

    @Test
    fun `空输入不产生任何构成`() {
        val breakdown = buildContextUsageBreakdown(
            isAgentSession = true,
            systemPromptTokens = 0,
            messages = emptyList()
        )

        assertTrue(breakdown.isEmpty)
        assertEquals(0, breakdown.totalTokens)
    }

    @Test
    fun `工具定义估算随定义数量增长，空列表为 0`() {
        assertEquals(0, estimateToolDefinitionsTokens(emptyList()))

        val oneTool = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "workspace_read_file",
                    "description" to "读取工作区文件内容",
                    "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                )
            )
        )
        val tokens = estimateToolDefinitionsTokens(oneTool)
        assertTrue(tokens > 0)
        assertTrue(estimateToolDefinitionsTokens(oneTool + oneTool) > tokens)
    }

    @Test
    fun `消息估算计入 tool_calls 参数`() {
        val base = mapOf<String, Any>("role" to "assistant", "content" to "写入文件")
        val arguments = """{"path":"a.txt","content":"${"数据".repeat(200)}"}"""
        val withCall = base + mapOf(
            "tool_calls" to listOf(
                mapOf(
                    "id" to "call-1",
                    "type" to "function",
                    "function" to mapOf("name" to "workspace_write_file", "arguments" to arguments)
                )
            )
        )

        val without = estimateLocalMessagesTokens(listOf(base))
        val with = estimateLocalMessagesTokens(listOf(withCall))

        assertTrue("工具参数是请求体的一部分，必须计入", with > without + 100)
    }
}
