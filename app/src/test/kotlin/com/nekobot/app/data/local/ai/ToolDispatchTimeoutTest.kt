package com.nekobot.app.data.local.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分派层工具超时。
 *
 * 关注三件事：
 * 1. 工具 id → 超时值的映射（长任务给足、未知工具走默认兜底）；
 * 2. 工具挂住时循环能拿到结构化超时结果并继续推进，而不是整轮卡死；
 * 3. 超时结果字段足够让模型/日志区分"超时"和"业务失败"。
 */
class ToolDispatchTimeoutTest {

    private fun toolCall(id: String, name: String, arguments: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("id" to id, "name" to name, "arguments" to arguments)

    @Test
    fun `dispatch timeout falls back to the default for unknown tools`() {
        assertEquals(AgentToolLimits.DEFAULT_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("grep"))
        assertEquals(AgentToolLimits.DEFAULT_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("future_tool"))
        assertEquals(AgentToolLimits.DEFAULT_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("  "))
    }

    @Test
    fun `long running and waiting tools get their own budgets`() {
        assertEquals(AgentToolLimits.LONG_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("browser_use"))
        assertEquals(AgentToolLimits.LONG_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("workspace_parse_file"))
        assertEquals(AgentToolLimits.LONG_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("mcp__server01__run"))
        assertEquals(AgentToolLimits.WAITING_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("exec_command"))
        assertEquals(AgentToolLimits.WAITING_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("ask_user_question"))
        assertEquals(AgentToolLimits.WAITING_TOOL_TIMEOUT_MS, AgentToolLimits.toolTimeoutMs("subagent_run"))
        // 命令类要覆盖授权等待 + 命令自身上限，必须大于两者之和的一半以上
        assertTrue(AgentToolLimits.WAITING_TOOL_TIMEOUT_MS > AgentToolLimits.LONG_TOOL_TIMEOUT_MS)
    }

    @Test
    fun `timeout result is structured and retryable`() {
        val result = toolTimeoutResult("browser_use", 300_000)
        assertEquals(false, result["success"])
        assertEquals(AgentToolLimits.TIMEOUT_ERROR_CODE, result["error_code"])
        assertEquals(300_000L, result["timeout_ms"])
        assertEquals(true, result["retryable"])
        assertTrue((result["error"] as String).contains("browser_use"))
        assertTrue((result["error"] as String).contains("300"))
    }

    @Test
    fun `a hanging tool is interrupted and the loop keeps going`() = runBlocking {
        var callIndex = 0
        val modelCall: ModelCall = { _, _ ->
            callIndex += 1
            if (callIndex == 1) {
                mapOf(
                    "content" to "",
                    "tool_calls" to listOf(toolCall("call-1", "browser_use", mapOf("action" to "open"))),
                    "finish_reason" to "tool_calls"
                )
            } else {
                mapOf("content" to "已改用其它方式完成", "finish_reason" to "stop")
            }
        }
        val hangingExecutor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> =
            { _, _, _, _ ->
                delay(30_000)
                mapOf("success" to true)
            }

        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "打开网页")),
            modelCall = modelCall,
            toolExecutor = hangingExecutor,
            maxIterations = 4,
            toolTimeoutMs = { 50L }
        )

        assertEquals("已改用其它方式完成", result.finalContent)
        assertFalse(result.stopped)
        val toolMessage = result.toolMessages.last { it["role"] == "tool" }
        val content = toolMessage["content"] as String
        assertTrue("工具结果应带超时错误码: $content", content.contains(AgentToolLimits.TIMEOUT_ERROR_CODE))
        assertTrue(content.contains("browser_use"))
        assertEquals(2, result.iterations)
    }

    @Test
    fun `zero timeout keeps the previous behaviour`() = runBlocking {
        var callIndex = 0
        var executed = false
        val modelCall: ModelCall = { _, _ ->
            callIndex += 1
            if (callIndex == 1) {
                mapOf(
                    "content" to "",
                    "tool_calls" to listOf(toolCall("call-1", "grep", mapOf("pattern" to "TODO"))),
                    "finish_reason" to "tool_calls"
                )
            } else {
                mapOf("content" to "ok", "finish_reason" to "stop")
            }
        }
        val executor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> =
            { _, _, _, _ ->
                executed = true
                mapOf("success" to true, "content" to "hit")
            }

        runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "搜索")),
            modelCall = modelCall,
            toolExecutor = executor,
            maxIterations = 4,
            toolTimeoutMs = { 0L }
        )

        assertTrue(executed)
    }
}
