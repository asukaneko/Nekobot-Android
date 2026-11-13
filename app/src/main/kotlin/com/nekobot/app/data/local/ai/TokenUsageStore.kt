package com.nekobot.app.data.local.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

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
 *
 * 预算从 6MB 降到 1MB 的原因：SharedPreferences 首次访问会把整份 XML 解析进内存，
 * 单个值的字符串在解析阶段还要再复制一份，6MB 的数组在 256MB 堆的低内存机型上
 * 会直接卡在 `loadFromDisk` 里 OOM（真机崩溃栈就是这么来的）。
 */
internal const val TOKEN_USAGE_MIN_KEEP = 500

/** 用量记录的字符预算（约 1MB）。超过后按比例丢弃最旧记录。 */
internal const val TOKEN_USAGE_BUDGET_CHARS = 1_000_000

/** `token_usage_<db>.xml` 的体积上限；更大的历史文件会在首次访问前直接丢弃。 */
internal const val TOKEN_USAGE_PREFS_MAX_BYTES = 4L * 1024 * 1024

/** 损坏备份最多保留的原始字符数：备份本身也塞进同一份 prefs，太长会继续撑爆启动。 */
internal const val TOKEN_USAGE_CORRUPT_BACKUP_CHARS = 64 * 1024

/** 待写入记录的批量阈值：达到后立即落盘，不等防抖延迟。 */
internal const val TOKEN_USAGE_PENDING_FLUSH_THRESHOLD = 32

/** 写盘防抖延迟（毫秒）：一轮 Agent 任务可能产生上百条记录，合并成少数几次提交。 */
internal const val TOKEN_USAGE_FLUSH_DELAY_MS = 250L

/**
 * 丢弃体积异常的 token 用量 SharedPreferences 文件。
 *
 * 必须在该文件的第一次 `getSharedPreferences` 之前调用。超长 XML 一旦开始解析，
 * 进程可能直接 OOM，此后连 `edit().clear()` 都无法执行；而删掉文件后，
 * 用量记录会由 Room 消息重新回补，不会留下不可恢复的状态。
 *
 * @return 是否真的删掉了文件
 */
internal fun discardOversizedTokenUsagePrefs(
    sharedPrefsDir: File,
    prefsName: String,
    maxBytes: Long = TOKEN_USAGE_PREFS_MAX_BYTES
): Boolean {
    if (maxBytes <= 0L) return false
    var discarded = false
    listOf(
        File(sharedPrefsDir, "$prefsName.xml"),
        File(sharedPrefsDir, "$prefsName.xml.bak")
    ).forEach { file ->
        if (!file.isFile || file.length() <= maxBytes) return@forEach
        if (file.delete()) discarded = true
    }
    return discarded
}

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
    // 每次 toString() 都会复制出一份完整 JSON，循环次数必须封顶；
    // 正常情况下一两轮就能落到预算内，留 6 轮纯粹是防御单条记录特别大的异常数据。
    var passes = 0
    while (size > budgetChars && records.size() > minKeep && passes < 6) {
        passes++
        val perRecord = maxOf(1, size / records.size())
        val excess = size - budgetChars
        // 一次丢够（外加 20% 余量），避免逐条裁剪反复序列化整份历史。
        val dropCount = minOf(
            records.size() - minKeep,
            maxOf(excess / perRecord + 1, records.size() / 5)
        )
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
