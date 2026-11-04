package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单轮 Agent 运行预算。
 *
 * 目标：任务不会无边界地烧 token / 占设备，超限时**带进度收尾**（不是失败），
 * 用户回复「继续」可以接着跑。
 */
class AgentRunBudgetTest {

    @Test
    fun `unlimited budget never triggers`() {
        val budget = AgentRunBudget.UNLIMITED
        assertTrue(budget.isUnlimited)
        assertNull(budget.breach(inputTokens = 10_000_000, outputTokens = 5_000, elapsedMs = 10 * 60 * 60 * 1000L))
    }

    @Test
    fun `token budget triggers at the configured amount`() {
        val budget = AgentRunBudget(maxTokens = 1_000)
        assertNull(budget.breach(inputTokens = 999, outputTokens = 0, elapsedMs = 0))
        assertEquals(AgentBudgetBreach.TOKENS, budget.breach(inputTokens = 990, outputTokens = 10, elapsedMs = 0))
    }

    @Test
    fun `duration budget triggers after the configured time`() {
        val budget = AgentRunBudget(maxDurationMs = 60_000)
        assertNull(budget.breach(inputTokens = 0, outputTokens = 0, elapsedMs = 59_999))
        assertEquals(AgentBudgetBreach.DURATION, budget.breach(inputTokens = 0, outputTokens = 0, elapsedMs = 60_000))
    }

    @Test
    fun `tokens are reported first when both limits are hit`() {
        val budget = AgentRunBudget(maxTokens = 10, maxDurationMs = 1_000)
        assertEquals(AgentBudgetBreach.TOKENS, budget.breach(inputTokens = 10, outputTokens = 0, elapsedMs = 5_000))
    }

    @Test
    fun `cost budget needs a price source and then triggers`() {
        // 没有价格来源：费用上限不会触发（避免用猜测的价格误停任务）
        val noPrices = AgentRunBudget(maxCostUsd = 1.0)
        assertNull(noPrices.breach(inputTokens = 5_000_000, outputTokens = 1_000_000, elapsedMs = 0))
        assertEquals(0.0, noPrices.costUsd(5_000_000, 1_000_000), 0.0)

        // 有价格来源：按 token 折算触发
        val priced = AgentRunBudget(
            maxCostUsd = 0.5,
            costEstimator = { input, output -> input / 1_000_000.0 * 3.0 + output / 1_000_000.0 * 15.0 }
        )
        val small = priced.costUsd(10_000, 1_000)
        assertTrue(small < 0.5)
        assertNull(priced.breach(inputTokens = 10_000, outputTokens = 1_000, elapsedMs = 0))
        assertEquals(
            AgentBudgetBreach.COST,
            priced.breach(inputTokens = 100_000, outputTokens = 20_000, elapsedMs = 0)
        )
    }

    @Test
    fun `stop content explains the situation and how to continue`() {
        val tokenStop = agentBudgetStopContent(AgentBudgetBreach.TOKENS, 12_345, 0, 60_000, 7)
        assertTrue(tokenStop.contains("预算"))
        assertTrue(tokenStop.contains("12345"))
        assertTrue(tokenStop.contains("继续"))

        val timeStop = agentBudgetStopContent(AgentBudgetBreach.DURATION, 0, 0, 90 * 60_000L, 3)
        assertTrue(timeStop.contains("1 小时 30 分钟"))

        val costStop = agentBudgetStopContent(AgentBudgetBreach.COST, 10, 10, 1_000, 2, costUsd = 0.1234)
        assertTrue(costStop.contains("费用"))
        assertTrue("常规金额保留两位小数", costStop.contains("0.12"))
        assertTrue(costStop.contains("继续"))

        val tinyCostStop = agentBudgetStopContent(AgentBudgetBreach.COST, 10, 10, 1_000, 2, costUsd = 0.00432)
        assertTrue("小额费用保留四位小数，避免显示成 0.00", tinyCostStop.contains("0.0043"))
    }

    @Test
    fun `loop wraps up with progress instead of failing when the token budget is spent`() = runBlocking {
        var callIndex = 0
        val modelCall: ModelCall = { _, _ ->
            callIndex += 1
            mapOf(
                "content" to "",
                "tool_calls" to listOf(
                    mapOf("id" to "call-$callIndex", "name" to "grep", "arguments" to mapOf("pattern" to "x"))
                ),
                "finish_reason" to "tool_calls",
                "usage" to mapOf("total_tokens" to 4_000)
            )
        }
        val executor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> =
            { _, _, _, _ -> mapOf("success" to true, "content" to "hit") }

        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "找点东西")),
            modelCall = modelCall,
            toolExecutor = executor,
            maxIterations = 10,
            budget = AgentRunBudget(maxTokens = 5_000)
        )

        assertEquals("TOKENS", result.budgetBreach)
        assertTrue(result.finalContent.contains("预算"))
        assertTrue(result.finalContent.contains("继续"))
        // 至少完成了一轮工具调用，说明是"收尾"而不是一开始就拒绝执行
        assertTrue(result.toolMessages.any { it["role"] == "tool" })
        // 第二轮模型调用消耗后才发现超预算：此时不再执行新工具，直接收尾
        assertEquals(1, result.iterations)
    }

    @Test
    fun `loop wraps up when the cost budget is reached`() = runBlocking {
        var callIndex = 0
        val modelCall: ModelCall = { _, _ ->
            callIndex += 1
            mapOf(
                "content" to "",
                "tool_calls" to listOf(
                    mapOf("id" to "call-$callIndex", "name" to "grep", "arguments" to mapOf("pattern" to "x"))
                ),
                "finish_reason" to "tool_calls",
                // 每次调用 100k 输入 / 20k 输出；单价 3/15 美元每百万 → 每次约 0.6 美元
                "usage" to mapOf("prompt_tokens" to 100_000, "completion_tokens" to 20_000)
            )
        }
        val executor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> =
            { _, _, _, _ -> mapOf("success" to true, "content" to "hit") }

        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "找点东西")),
            modelCall = modelCall,
            toolExecutor = executor,
            maxIterations = 10,
            budget = AgentRunBudget(
                maxCostUsd = 0.5,
                costEstimator = { input, output -> input / 1_000_000.0 * 3.0 + output / 1_000_000.0 * 15.0 }
            )
        )

        assertEquals("COST", result.budgetBreach)
        assertTrue(result.finalContent.contains("费用"))
        assertTrue(result.finalContent.contains("继续"))
    }
}
