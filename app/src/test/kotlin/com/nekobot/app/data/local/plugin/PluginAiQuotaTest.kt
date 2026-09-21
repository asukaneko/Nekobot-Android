package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 插件 AI 调用的频率与用量配额。 */
class PluginAiQuotaTest {

    private var now = 0L

    private fun quota(callsPerMinute: Int = 3, tokensPerHour: Int = 100) = PluginAiQuota(
        maxCallsPerMinute = callsPerMinute,
        maxTokensPerHour = tokensPerHour,
        nowMillis = { now }
    )

    @Test
    fun callRateLimitIsEnforced() {
        val quota = quota()
        repeat(3) { quota.ensureCallAllowed("demo.plugin") }
        val error = assertThrows(PluginApiException::class.java) {
            quota.ensureCallAllowed("demo.plugin")
        }
        assertEquals("ai_rate_limited", error.code)
    }

    @Test
    fun callRateLimitWindowSlides() {
        val quota = quota()
        repeat(3) { quota.ensureCallAllowed("demo.plugin") }
        now += 60_000L
        quota.ensureCallAllowed("demo.plugin")
    }

    @Test
    fun quotasAreIsolatedPerPlugin() {
        val quota = quota()
        repeat(3) { quota.ensureCallAllowed("a") }
        quota.ensureCallAllowed("b")
    }

    @Test
    fun tokenBudgetIsEnforced() {
        val quota = quota()
        quota.recordTokens("demo.plugin", 100)
        val error = assertThrows(PluginApiException::class.java) {
            quota.ensureCallAllowed("demo.plugin")
        }
        assertEquals("ai_budget_exceeded", error.code)
    }

    @Test
    fun tokenBudgetWindowResets() {
        val quota = quota()
        quota.recordTokens("demo.plugin", 100)
        now += 3_600_000L
        quota.ensureCallAllowed("demo.plugin")
    }
}
