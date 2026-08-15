package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartModelRouterTest {
    @Test
    fun simpleRequestPrefersCheapFastModel() {
        val strong = model("strong", price = 20.0, reasoning = true)
        val cheap = model("cheap", price = 0.2)
        val routed = SmartModelRouter.route(
            listOf(strong, cheap),
            SmartRoutingRequest(promptChars = 120, estimatedContextTokens = 500),
            mapOf(
                strong.id to SmartModelMetric(averageTtftMs = 3_000.0, recentRequests = 5),
                cheap.id to SmartModelMetric(averageTtftMs = 300.0, recentRequests = 5)
            )
        )
        assertEquals("cheap", routed.first().id)
    }

    @Test
    fun agentRequestRequiresToolsAndRewardsReasoning() {
        val basic = model("basic", price = 0.1, tools = false)
        val agent = model("agent", price = 5.0, tools = true, reasoning = true)
        val routed = SmartModelRouter.route(
            listOf(basic, agent),
            SmartRoutingRequest(promptChars = 1_000, sessionMode = "agent")
        )
        assertEquals("agent", routed.first().id)
    }

    @Test
    fun agentRequestPrioritizesGptAndClaudeModels() {
        val generic = model("generic", price = 0.1, reasoning = true)
        val gpt = model("gpt-5", price = 20.0)
        val claude = model("claude-sonnet", price = 20.0)
        val routed = SmartModelRouter.route(
            listOf(generic, gpt, claude),
            SmartRoutingRequest(promptChars = 1_000, sessionMode = "agent"),
            mapOf(
                generic.id to SmartModelMetric(averageTtftMs = 300.0, recentRequests = 5),
                gpt.id to SmartModelMetric(averageTtftMs = 1_000.0, recentRequests = 5),
                claude.id to SmartModelMetric(averageTtftMs = 1_000.0, recentRequests = 5)
            )
        )

        assertEquals(setOf("gpt-5", "claude-sonnet"), routed.take(2).map { it.id }.toSet())
    }

    @Test
    fun roleplayRequestGivesPriceMoreWeight() {
        val capable = model("capable", price = 10.0, reasoning = true)
        val affordable = model("affordable", price = 0.1)
        val metrics = mapOf(
            capable.id to SmartModelMetric(averageTtftMs = 300.0, recentRequests = 5),
            affordable.id to SmartModelMetric(averageTtftMs = 3_000.0, recentRequests = 5)
        )

        val standard = SmartModelRouter.route(
            listOf(capable, affordable),
            SmartRoutingRequest(promptChars = 4_000, sessionMode = "utility"),
            metrics
        )
        val roleplay = SmartModelRouter.route(
            listOf(capable, affordable),
            SmartRoutingRequest(promptChars = 4_000, sessionMode = "character"),
            metrics
        )

        assertEquals("capable", standard.first().id)
        assertEquals("affordable", roleplay.first().id)
    }

    @Test
    fun longContextFiltersModelsThatCannotFit() {
        val short = model("short", price = 0.1, context = 8_000)
        val long = model("long", price = 2.0, context = 128_000)
        val routed = SmartModelRouter.route(
            listOf(short, long),
            SmartRoutingRequest(promptChars = 10_000, estimatedContextTokens = 40_000)
        )
        assertEquals(listOf("long"), routed.map { it.id })
    }

    @Test
    fun catalogPriceRoutesModelsWhosePriceWasNotFilledManually() {
        val expensive = model("gpt-4o", price = null)
        val cheap = model("gpt-4o-mini", price = null)
        val routed = SmartModelRouter.route(
            listOf(expensive, cheap),
            SmartRoutingRequest(promptChars = 120, estimatedContextTokens = 500),
            mapOf(
                expensive.id to SmartModelMetric(averageTtftMs = 500.0, recentRequests = 5),
                cheap.id to SmartModelMetric(averageTtftMs = 500.0, recentRequests = 5)
            )
        )

        assertEquals("gpt-4o-mini", routed.first().id)
    }

    private fun model(
        id: String,
        price: Double?,
        tools: Boolean = true,
        reasoning: Boolean = false,
        context: Int = 32_000
    ) = LocalAiModelEntity(
        id = id,
        name = id,
        protocol = "openai_chat",
        apiKey = "test",
        baseUrl = "https://example.com/v1",
        model = id,
        inputPrice = price,
        outputPrice = price,
        supportsTools = tools,
        supportsReasoning = reasoning,
        maxContextLength = context,
        createdAt = "2026-07-30T00:00:00Z"
    )
}
