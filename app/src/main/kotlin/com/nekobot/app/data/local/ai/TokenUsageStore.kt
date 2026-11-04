package com.nekobot.app.data.local.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Token 用量记录的保留策略。
 *
 * 记录以 JSON 数组形式存在 `token_usage_<db>` 这份 SharedPreferences 里，
 * 备份 / 恢复 / 导入都在用这个格式，因此不能改存储形态；这里只解决两件事：
 *
 * 1. **不再按"5000 条"硬切**：条数相同但内容长度差别巨大（很长的模型名、
 *    带 cache 明细的记录），按条数截断既可能过早丢历史，也压不住体积。
 *    改成按序列化字符数给预算，同时保证最少保留条数。
 * 2. **按比例丢弃**：每次超预算时丢掉最旧的一部分，而不是逐条裁剪，
 *    避免"每写一条就重排整份历史"的开销。
 */
internal const val TOKEN_USAGE_MIN_KEEP = 500

/** 用量记录的字符预算（约 6MB）。超过后按比例丢弃最旧记录。 */
internal const val TOKEN_USAGE_BUDGET_CHARS = 6_000_000

/** 待写入记录的批量阈值：达到后立即落盘，不等防抖延迟。 */
internal const val TOKEN_USAGE_PENDING_FLUSH_THRESHOLD = 32

/** 写盘防抖延迟（毫秒）：一轮 Agent 任务可能产生上百条记录，合并成少数几次提交。 */
internal const val TOKEN_USAGE_FLUSH_DELAY_MS = 250L

/**
 * 按字符预算裁剪记录数组：保留最新的记录，超预算时按比例丢弃最旧的。
 *
 * 数组本身原地修改。返回是否发生了裁剪。
 */
internal fun trimTokenUsageArray(
    records: JsonArray,
    budgetChars: Int = TOKEN_USAGE_BUDGET_CHARS,
    minKeep: Int = TOKEN_USAGE_MIN_KEEP
): Boolean {
    if (records.size() <= minKeep) return false
    if (budgetChars <= 0) return false
    var size = records.toString().length
    if (size <= budgetChars) return false
    var trimmed = false
    while (size > budgetChars && records.size() > minKeep) {
        val droppable = records.size() - minKeep
        // 每次丢 20%（至少 1 条），避免逐条裁剪导致反复序列化整份历史。
        val dropCount = minOf(droppable, maxOf(1, records.size() / 5))
        repeat(dropCount) { records.remove(0) }
        trimmed = true
        size = records.toString().length
    }
    return trimmed
}

/** 累加一组记录的 token 总量（成就统计与汇总展示共用同一口径）。 */
internal fun tokenUsageTotalTokens(records: JsonArray): Long {
    var total = 0L
    records.forEach { element ->
        if (element.isJsonObject) total += element.asJsonObject.totalTokensOrZero()
    }
    return total
}

/** 从一条记录里读 `total_tokens`，缺失或非法按 0 处理。 */
internal fun JsonObject.totalTokensOrZero(): Long =
    runCatching {
        get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
    }.getOrDefault(0L)
