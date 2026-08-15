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
    val isRoleplay: Boolean
        get() = sessionMode.isBlank() ||
            sessionMode.equals("character", ignoreCase = true) ||
            sessionMode.equals("group", ignoreCase = true)
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
 * 带分项得分的模型评分结果，用于路由可解释性。
 * score 越低越好（与 [SmartModelScore] 一致）。
 */
data class SmartModelScoreBreakdown(
    val model: LocalAiModelEntity,
    val score: Double,
    val priceScore: Double = 0.0,
    val speedScore: Double = 0.0,
    val failurePenalty: Double = 0.0,
    val contextBonus: Double = 0.0,
    val capabilityBonus: Double = 0.0,
    val priorityBonus: Double = 0.0,
    val noHistoryPenalty: Double = 0.0,
    val reasons: List<String> = emptyList()
)

/**
 * 面向本地多模型队列的纯评分器。故障转移仍由 FailoverCoordinator 执行，
 * 此处只决定首选模型与后续尝试顺序。
 */
object SmartModelRouter {
    private const val AGENT_GPT_CLAUDE_BONUS = 75.0
    private const val ROLEPLAY_PRICE_WEIGHT_MULTIPLIER = 2.5

    fun score(
        models: List<LocalAiModelEntity>,
        request: SmartRoutingRequest,
        metrics: Map<String, SmartModelMetric> = emptyMap()
    ): List<SmartModelScore> = scoreDetailed(models, request, metrics)
        .map { SmartModelScore(it.model, it.score) }

    /**
     * 与 [score] 逻辑完全相同，但返回带分项得分和原因列表的 [SmartModelScoreBreakdown]，
     * 供路由决策日志记录可解释性信息。
     */
    fun scoreDetailed(
        models: List<LocalAiModelEntity>,
        request: SmartRoutingRequest,
        metrics: Map<String, SmartModelMetric> = emptyMap()
    ): List<SmartModelScoreBreakdown> {
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
        val standardCostWeight = 30.0 + budgetPressure * 45.0 + if (request.isSimple) 25.0 else 0.0
        val costWeight = if (request.isRoleplay) {
            standardCostWeight * ROLEPLAY_PRICE_WEIGHT_MULTIPLIER
        } else {
            standardCostWeight
        }

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
                        (if (model.supportsReasoning) 12.0 else 0.0) +
                        (if (model.isGptOrClaude()) AGENT_GPT_CLAUDE_BONUS else 0.0)
                request.isComplex -> if (model.supportsReasoning) 18.0 else 0.0
                else -> 0.0
            }
            val noHistoryPenalty = if (metric.recentRequests == 0) 3.0 else 0.0
            val priorityBonus = model.priority.coerceAtLeast(0) * 3.0
            val score =
                priorityBonus +
                    priceScore * costWeight +
                    speedScore * 24.0 +
                    failurePenalty +
                    noHistoryPenalty -
                    contextBonus -
                    capabilityBonus -
                    if (model.active) 2.0 else 0.0

            // 生成可解释性原因列表
            val reasons = mutableListOf<String>()
            if (priceScore < 0.3) reasons.add("价格较低")
            if (speedScore < 0.3) reasons.add("响应较快")
            if (capabilityBonus > 10) reasons.add("能力匹配")
            if (failurePenalty > 0) reasons.add("近期有失败")
            if (noHistoryPenalty > 0) reasons.add("无历史数据")
            if (model.active) reasons.add("已激活模型")

            SmartModelScoreBreakdown(
                model = model,
                score = score,
                priceScore = priceScore,
                speedScore = speedScore,
                failurePenalty = failurePenalty,
                contextBonus = contextBonus,
                capabilityBonus = capabilityBonus,
                priorityBonus = priorityBonus,
                noHistoryPenalty = noHistoryPenalty,
                reasons = reasons
            )
        }.sortedWith(compareBy(SmartModelScoreBreakdown::score, { it.model.priority }, { it.model.createdAt }))
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

    private fun LocalAiModelEntity.isGptOrClaude(): Boolean =
        listOf(model, name).any { value ->
            val normalized = value.lowercase()
            normalized.contains("gpt") || normalized.contains("claude")
        }
}
