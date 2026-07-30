package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity

data class SmartRoutingRequest(
    val promptChars: Int = 0,
    val estimatedContextTokens: Int = 0,
    val sessionMode: String = "character",
    val hasAttachments: Boolean = false,
    val dailyBudgetUsd: Double = 0.0,
    val dailySpentUsd: Double = 0.0
) {
    val isAgent: Boolean get() = sessionMode.equals("agent", ignoreCase = true)
    val isComplex: Boolean
        get() = isAgent || hasAttachments || promptChars >= 4_000 || estimatedContextTokens >= 24_000
    val isSimple: Boolean
        get() = !isComplex && promptChars in 1..800 && estimatedContextTokens < 8_000
}

data class SmartModelMetric(
    val averageTtftMs: Double? = null,
    val averageDurationMs: Double? = null,
    val recentRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val dailyFailures: Int = 0,
    val coolingDown: Boolean = false
)

data class SmartModelScore(
    val model: LocalAiModelEntity,
    val score: Double
)

/**
 * 面向本地多模型队列的纯评分器。故障转移仍由 FailoverCoordinator 执行，
 * 此处只决定首选模型与后续尝试顺序。
 */
object SmartModelRouter {
    fun score(
        models: List<LocalAiModelEntity>,
        request: SmartRoutingRequest,
        metrics: Map<String, SmartModelMetric> = emptyMap()
    ): List<SmartModelScore> {
        if (models.isEmpty()) return emptyList()
        val requiredContext = request.estimatedContextTokens.coerceAtLeast(0) + 2_048
        val contextCapable = models.filter { model ->
            model.maxContextLength == null || model.maxContextLength >= requiredContext
        }.ifEmpty { models }
        val candidates = if (request.isAgent && contextCapable.any(LocalAiModelEntity::supportsTools)) {
            contextCapable.filter(LocalAiModelEntity::supportsTools)
        } else {
            contextCapable
        }

        val pricingCatalog = ModelPricingCatalog.current()
        val knownPrices = candidates.mapNotNull { effectivePrice(it, pricingCatalog) }.sorted()
        val fallbackPrice = knownPrices.getOrNull(knownPrices.size / 2) ?: 0.0
        val prices = candidates.associate {
            it.id to (effectivePrice(it, pricingCatalog) ?: fallbackPrice)
        }
        val minPrice = prices.values.minOrNull() ?: 0.0
        val maxPrice = prices.values.maxOrNull() ?: minPrice
        val knownTtft = candidates.mapNotNull { metrics[it.id]?.averageTtftMs }.sorted()
        val fallbackTtft = knownTtft.getOrNull(knownTtft.size / 2) ?: 3_000.0
        val minTtft = candidates.minOfOrNull { metrics[it.id]?.averageTtftMs ?: fallbackTtft } ?: fallbackTtft
        val maxTtft = candidates.maxOfOrNull { metrics[it.id]?.averageTtftMs ?: fallbackTtft } ?: fallbackTtft
        val budgetPressure = when {
            request.dailyBudgetUsd <= 0.0 -> 0.0
            else -> (request.dailySpentUsd / request.dailyBudgetUsd).coerceIn(0.0, 1.5)
        }
        val costWeight = 30.0 + budgetPressure * 45.0 + if (request.isSimple) 25.0 else 0.0

        return candidates.map { model ->
            val metric = metrics[model.id] ?: SmartModelMetric()
            val priceScore = normalize(prices.getValue(model.id), minPrice, maxPrice)
            val speedScore = normalize(metric.averageTtftMs ?: fallbackTtft, minTtft, maxTtft)
            val failurePenalty =
                metric.consecutiveFailures * 18.0 +
                    metric.dailyFailures.coerceAtMost(5) * 4.0 +
                    if (metric.coolingDown) 500.0 else 0.0
            val contextBonus = model.maxContextLength
                ?.let { (it.coerceAtMost(256_000) / 256_000.0) * 12.0 }
                ?: 4.0
            val capabilityBonus = when {
                request.isAgent ->
                    (if (model.supportsTools) 18.0 else -120.0) +
                        (if (model.supportsReasoning) 12.0 else 0.0)
                request.isComplex -> if (model.supportsReasoning) 18.0 else 0.0
                else -> 0.0
            }
            val noHistoryPenalty = if (metric.recentRequests == 0) 3.0 else 0.0
            val score =
                model.priority.coerceAtLeast(0) * 3.0 +
                    priceScore * costWeight +
                    speedScore * 24.0 +
                    failurePenalty +
                    noHistoryPenalty -
                    contextBonus -
                    capabilityBonus -
                    if (model.active) 2.0 else 0.0
            SmartModelScore(model, score)
        }.sortedWith(compareBy(SmartModelScore::score, { it.model.priority }, { it.model.createdAt }))
    }

    fun route(
        models: List<LocalAiModelEntity>,
        request: SmartRoutingRequest,
        metrics: Map<String, SmartModelMetric> = emptyMap()
    ): List<LocalAiModelEntity> = score(models, request, metrics).map(SmartModelScore::model)

    private fun effectivePrice(
        model: LocalAiModelEntity,
        pricingCatalog: ModelPricingSnapshot
    ): Double? {
        val (input, output) = ModelPricingCatalog.resolvePrices(
            modelName = model.model,
            provider = model.provider,
            inputPrice = model.inputPrice,
            outputPrice = model.outputPrice,
            catalog = pricingCatalog
        )
        if (input == null && output == null) return null
        return ((input ?: output ?: 0.0) + (output ?: input ?: 0.0) * 2.0) / 3.0
    }

    private fun normalize(value: Double, min: Double, max: Double): Double =
        if (max <= min) 0.0 else ((value - min) / (max - min)).coerceIn(0.0, 1.0)
}
