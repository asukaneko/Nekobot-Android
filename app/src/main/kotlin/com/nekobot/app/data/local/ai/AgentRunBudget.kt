package com.nekobot.app.data.local.ai

/**
 * 单轮 Agent 任务的运行预算。
 *
 * 改造前只有"最多多少轮工具调用"和"连续失败几次"，没有任何成本边界：
 * 一个跑偏的任务可以持续消耗 token、费用与电量，直到用户主动点停止。
 * 这里给"一轮任务"（一次用户请求触发的整段工具循环）加上 token / 时间 / 费用三条上限，
 * 超限时不是硬中断，而是**带着进度收尾**：把已完成的结论说清楚，
 * 并提示用户可以回复「继续」接着跑。
 *
 * 默认值刻意保守：token 与费用不限（避免误伤长任务），时间 2 小时兜底。
 */
data class AgentRunBudget(
    /** 本轮的输入+输出 token 上限；0 表示不限。 */
    val maxTokens: Long = 0L,
    /** 本轮允许的最长墙钟时间（毫秒）；0 表示不限。 */
    val maxDurationMs: Long = 0L,
    /** 本轮的预估费用上限（美元）；0 表示不限。只有提供 [costEstimator] 时才会触发。 */
    val maxCostUsd: Double = 0.0,
    /**
     * 费用估算：由调用方按当前模型的单价把 token 换成美元。
     *
     * 默认返回 0，表示"这条链路没有价格信息"，费用上限因此不会被触发——
     * 宁可少一个保险，也不要因为猜价格而误停用户的正常任务。
     */
    val costEstimator: (inputTokens: Long, outputTokens: Long) -> Double = { _, _ -> 0.0 }
) {
    val isUnlimited: Boolean get() = maxTokens <= 0L && maxDurationMs <= 0L && maxCostUsd <= 0.0

    /** 当前累计费用（美元）。没有估算器时恒为 0。 */
    fun costUsd(inputTokens: Long, outputTokens: Long): Double {
        if (maxCostUsd <= 0.0) return 0.0
        return costEstimator(inputTokens.coerceAtLeast(0L), outputTokens.coerceAtLeast(0L)).coerceAtLeast(0.0)
    }

    /** 判断当前是否已经超出预算；未超限返回 null。 */
    fun breach(
        inputTokens: Long,
        outputTokens: Long,
        elapsedMs: Long
    ): AgentBudgetBreach? {
        val spentTokens = inputTokens.coerceAtLeast(0L) + outputTokens.coerceAtLeast(0L)
        return when {
            maxTokens > 0L && spentTokens >= maxTokens -> AgentBudgetBreach.TOKENS
            maxDurationMs > 0L && elapsedMs >= maxDurationMs -> AgentBudgetBreach.DURATION
            maxCostUsd > 0.0 && costUsd(inputTokens, outputTokens) >= maxCostUsd -> AgentBudgetBreach.COST
            else -> null
        }
    }

    companion object {
        val UNLIMITED = AgentRunBudget()
    }
}

/** 触发预算收尾的原因。 */
enum class AgentBudgetBreach { TOKENS, DURATION, COST }

/** 读取当前配置的运行预算（设置缺省时回落到默认值）。 */
internal fun resolveAgentRunBudget(): AgentRunBudget = runCatching {
    val prefs = com.nekobot.app.ServiceContainer.prefs
    AgentRunBudget(
        maxTokens = prefs.agentRunTokenBudget.toLong().coerceAtLeast(0L),
        maxDurationMs = prefs.agentRunTimeoutMinutes.toLong().coerceAtLeast(0L) * 60_000L,
        maxCostUsd = prefs.agentRunCostBudgetUsd.coerceAtLeast(0.0)
    )
}.getOrDefault(AgentRunBudget.UNLIMITED)

/**
 * 超预算时的收尾文案。
 *
 * 措辞要求：先给结论位置，再说清楚为什么停、还差什么、怎么继续，
 * 不能让用户以为任务失败了。
 */
internal fun agentBudgetStopContent(
    breach: AgentBudgetBreach,
    inputTokens: Long,
    outputTokens: Long,
    elapsedMs: Long,
    iteration: Int,
    costUsd: Double = 0.0
): String {
    val spentTokens = inputTokens + outputTokens
    val reason = when (breach) {
        AgentBudgetBreach.TOKENS -> "本轮已用约 $spentTokens tokens，达到设置的单轮预算"
        AgentBudgetBreach.DURATION -> "本轮已运行 ${formatBudgetDuration(elapsedMs)}，达到设置的单轮时限"
        AgentBudgetBreach.COST -> "本轮已产生约 ${formatBudgetCost(costUsd)}，达到设置的单轮费用上限"
    }
    return "【已按预算收尾】$reason（共 $iteration 轮工具调用）。已完成的部分与工具记录都已保存，" +
        "未完成的部分需要继续时，回复「继续」即可接着执行；如需更长的预算，可在 Agent 设置里调整单轮上限。"
}

internal fun formatBudgetDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} 小时 ${minutes} 分钟" else "$minutes 分钟"
}

/** 费用展示：小额保留 4 位小数，避免"$0.00"看起来像没花钱。 */
internal fun formatBudgetCost(usd: Double): String {
    val safe = usd.coerceAtLeast(0.0)
    return if (safe < 0.01) "\$${String.format(java.util.Locale.US, "%.4f", safe)}"
    else "\$${String.format(java.util.Locale.US, "%.2f", safe)}"
}
