package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Token 用量统计管理器，对应原仓库 nbot/core/token_stats.py。
 *
 * 线程安全的统计管理器，记录每次 AI 调用的 token 用量，
 * 支持按 today/7d/30d/自定义范围查询，提供会话/模型/用户/用途排行榜。
 */
class TokenStatsManager {
    companion object {
        // 用途常量
        const val PURPOSE_CHAT = "chat"
        const val PURPOSE_DECISION = "decision"
        const val PURPOSE_MEMORY = "memory"
        const val PURPOSE_VISION = "vision"
        const val PURPOSE_UTILITY = "utility"
        const val PURPOSE_REACT = "react"
        const val PURPOSE_PLOT = "plot"
        const val PURPOSE_HEARTBEAT = "heartbeat"
        const val PURPOSE_EMBEDDING = "embedding"
        const val PURPOSE_TTS = "tts"
        const val PURPOSE_IMAGE_GEN = "image_gen"

        val PURPOSE_LABELS = mapOf(
            PURPOSE_CHAT to "对话",
            PURPOSE_DECISION to "决策",
            PURPOSE_MEMORY to "记忆",
            PURPOSE_VISION to "视觉",
            PURPOSE_UTILITY to "工具",
            PURPOSE_REACT to "ReAct",
            PURPOSE_PLOT to "剧情",
            PURPOSE_HEARTBEAT to "心跳",
            PURPOSE_EMBEDDING to "向量",
            PURPOSE_TTS to "TTS",
            PURPOSE_IMAGE_GEN to "图片生成"
        )

        // 模型定价（$/M tokens）
        private val MODEL_PRICING = mapOf(
            "claude-opus-4-7" to (15.0 to 75.0),
            "claude-sonnet-4-5" to (3.0 to 15.0),
            "gpt-4o" to (2.5 to 10.0),
            "gpt-4o-mini" to (0.15 to 0.6),
            "deepseek-v3" to (0.27 to 1.10),
            "deepseek-r1" to (0.55 to 2.19),
            "gemini-2.0-flash" to (0.1 to 0.4),
            "qwen-max" to (0.4 to 1.2)
        )

        private const val MAX_HISTORY_DAYS = 90
        private const val MAX_RECORDS = 50000
    }

    private val gson = Gson()
    private val lock = Any()

    // 聚合统计
    private var todayTokens = 0
    private var monthTokens = 0
    private var totalTokens = 0
    private var estimatedCost = 0.0
    private val history = mutableListOf<HistoryEntry>()
    private val sessions = ConcurrentHashMap<String, SessionStats>()
    private val models = ConcurrentHashMap<String, ModelStats>()
    private val users = ConcurrentHashMap<String, Int>()
    private val records = mutableListOf<UsageRecord>()
    private var todayDate = LocalDate.now().toString()
    private var monthDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

    // 数据类
    data class HistoryEntry(
        val date: String,
        var inputTokens: Int = 0,
        var outputTokens: Int = 0,
        var totalTokens: Int = 0,
        var messageCount: Int = 0,
        var cost: Double = 0.0
    )

    data class SessionStats(
        var inputTokens: Int = 0,
        var outputTokens: Int = 0,
        var totalTokens: Int = 0,
        var type: String = "",
        var messageCount: Int = 0
    )

    data class ModelStats(
        var inputTokens: Int = 0,
        var outputTokens: Int = 0,
        var totalTokens: Int = 0,
        var cost: Double = 0.0
    )

    data class UsageRecord(
        val id: String,
        val timestamp: String,
        val date: String,
        val model: String,
        val sessionId: String,
        val channelType: String,
        val userId: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val cost: Double,
        val source: String,
        val purpose: String,
        val durationMs: Double? = null,
        val ttftMs: Double? = null
    )

    /**
     * 记录一次 AI 调用的 token 用量。
     */
    fun recordUsage(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int = promptTokens + completionTokens,
        model: String = "",
        sessionId: String = "",
        channelType: String = "local",
        userId: String = "",
        source: String = "local",
        purpose: String = PURPOSE_CHAT,
        durationMs: Double? = null,
        ttftMs: Double? = null
    ) {
        if (promptTokens < 0 || completionTokens < 0) return
        val (inputPrice, outputPrice) = MODEL_PRICING[model] ?: (0.0 to 0.0)
        val cost = estimateCost(model, promptTokens, completionTokens, inputPrice, outputPrice)
        val now = LocalDateTime.now()
        val nowDate = now.toLocalDate().toString()
        val nowMonth = now.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM"))

        synchronized(lock) {
            // 跨天/跨月重置
            if (todayDate != nowDate) {
                todayDate = nowDate
                todayTokens = 0
            }
            if (monthDate != nowMonth) {
                monthDate = nowMonth
                monthTokens = 0
            }

            // 顶层聚合
            todayTokens += totalTokens
            monthTokens += totalTokens
            this.totalTokens += totalTokens
            estimatedCost += cost

            // 每日 history
            val todayEntry = history.find { it.date == nowDate }
            if (todayEntry != null) {
                todayEntry.inputTokens += promptTokens
                todayEntry.outputTokens += completionTokens
                todayEntry.totalTokens += totalTokens
                todayEntry.messageCount += 1
                todayEntry.cost += cost
            } else {
                history.add(HistoryEntry(
                    date = nowDate,
                    inputTokens = promptTokens,
                    outputTokens = completionTokens,
                    totalTokens = totalTokens,
                    messageCount = 1,
                    cost = cost
                ))
            }
            // 保留 90 天
            while (history.size > MAX_HISTORY_DAYS) history.removeAt(0)

            // sessions 维度
            val sessStats = sessions.getOrPut(sessionId) { SessionStats(type = channelType) }
            sessStats.inputTokens += promptTokens
            sessStats.outputTokens += completionTokens
            sessStats.totalTokens += totalTokens
            sessStats.messageCount += 1

            // models 维度
            val modelStats = models.getOrPut(model) { ModelStats() }
            modelStats.inputTokens += promptTokens
            modelStats.outputTokens += completionTokens
            modelStats.totalTokens += totalTokens
            modelStats.cost += cost

            // users 维度
            users[userId] = (users[userId] ?: 0) + totalTokens

            // records 明细
            records.add(UsageRecord(
                id = UUID.randomUUID().toString(),
                timestamp = now.toString(),
                date = nowDate,
                model = model,
                sessionId = sessionId,
                channelType = channelType,
                userId = userId,
                inputTokens = promptTokens,
                outputTokens = completionTokens,
                totalTokens = totalTokens,
                cost = cost,
                source = source,
                purpose = purpose,
                durationMs = durationMs,
                ttftMs = ttftMs
            ))
            // 保留上限
            while (records.size > MAX_RECORDS) records.removeAt(0)
        }
    }

    /** 费用估算 */
    private fun estimateCost(model: String, promptTokens: Int, completionTokens: Int, inputPrice: Double, outputPrice: Double): Double {
        return (promptTokens / 1_000_000.0) * inputPrice + (completionTokens / 1_000_000.0) * outputPrice
    }

    /**
     * 获取统计汇总。
     *
     * @param dateRange today/7d/30d 或空（全部）
     */
    fun getStats(dateRange: String? = null, startDate: String? = null, endDate: String? = null): Map<String, Any> {
        synchronized(lock) {
            val filteredRecords = filterRecords(dateRange, startDate, endDate)
            val filteredHistory = filterHistory(dateRange, startDate, endDate)

            val totalTokens = filteredRecords.sumOf { it.totalTokens }
            val totalInput = filteredRecords.sumOf { it.inputTokens }
            val totalOutput = filteredRecords.sumOf { it.outputTokens }
            val messageCount = filteredRecords.size
            val totalCost = filteredRecords.sumOf { it.cost }
            val avgTokensPerMsg = if (messageCount > 0) totalTokens / messageCount else 0

            // 用途维度
            val purposes = filteredRecords.groupBy { it.purpose }
                .mapValues { (_, recs) -> recs.sumOf { it.totalTokens } }

            // 按用途带 label
            val purposesLabeled = purposes.mapKeys { (key, _) ->
                PURPOSE_LABELS[key] ?: key
            }

            return mapOf(
                "total_tokens" to totalTokens,
                "today_input" to totalInput,
                "today_output" to totalOutput,
                "message_count" to messageCount,
                "avg_tokens_per_msg" to avgTokensPerMsg,
                "estimated_cost" to totalCost,
                "active_sessions" to filteredRecords.map { it.sessionId }.distinct().size,
                "history" to filteredHistory,
                "recent_records" to filteredRecords.takeLast(100).reversed(),
                "records" to filteredRecords,
                "sessions" to sessions.filterKeys { sid -> filteredRecords.any { it.sessionId == sid } },
                "models" to models.filterKeys { m -> filteredRecords.any { it.model == m } },
                "users" to users.filterKeys { uid -> filteredRecords.any { it.userId == uid } },
                "purposes" to purposesLabeled
            )
        }
    }

    /** 排行榜 */
    fun getRankings(limit: Int = 10): Map<String, List<Map<String, Any>>> {
        synchronized(lock) {
            val sessionRanking = sessions.entries
                .map { mapOf("id" to it.key, "tokens" to it.value.totalTokens, "messages" to it.value.messageCount) }
                .sortedByDescending { (it["tokens"] as Int) }
                .take(limit)

            val modelRanking = models.entries
                .map { mapOf("name" to it.key, "tokens" to it.value.totalTokens, "cost" to it.value.cost) }
                .sortedByDescending { (it["tokens"] as Int) }
                .take(limit)

            val userRanking = users.entries
                .map { mapOf("id" to it.key, "tokens" to it.value) }
                .sortedByDescending { (it["tokens"] as Int) }
                .take(limit)

            val purposeRanking = records.groupBy { it.purpose }
                .mapValues { (_, recs) -> recs.sumOf { it.totalTokens } }
                .entries
                .map { mapOf("purpose" to (PURPOSE_LABELS[it.key] ?: it.key), "tokens" to it.value) }
                .sortedByDescending { (it["tokens"] as Int) }
                .take(limit)

            return mapOf(
                "sessions" to sessionRanking,
                "models" to modelRanking,
                "users" to userRanking,
                "purposes" to purposeRanking
            )
        }
    }

    /** 重置今日计数 */
    fun resetDaily() {
        synchronized(lock) {
            todayTokens = 0
            todayDate = LocalDate.now().toString()
        }
    }

    /** 重置本月计数 */
    fun resetMonthly() {
        synchronized(lock) {
            monthTokens = 0
            monthDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
    }

    /** 过滤 records */
    private fun filterRecords(dateRange: String?, startDate: String?, endDate: String?): List<UsageRecord> {
        if (dateRange == null && startDate == null) return records.toList()
        val now = LocalDate.now()
        val (start, end) = when (dateRange) {
            "today" -> now to now
            "7d" -> now.minusDays(7) to now
            "30d" -> now.minusDays(30) to now
            else -> {
                val s = startDate?.let { LocalDate.parse(it) } ?: now.minusDays(30)
                val e = endDate?.let { LocalDate.parse(it) } ?: now
                s to e
            }
        }
        return records.filter { rec ->
            try {
                val recDate = LocalDate.parse(rec.date)
                !recDate.isBefore(start) && !recDate.isAfter(end)
            } catch (e: Exception) { true }
        }
    }

    /** 过滤 history */
    private fun filterHistory(dateRange: String?, startDate: String?, endDate: String?): List<HistoryEntry> {
        if (dateRange == null && startDate == null) return history.toList()
        val now = LocalDate.now()
        val (start, end) = when (dateRange) {
            "today" -> now to now
            "7d" -> now.minusDays(7) to now
            "30d" -> now.minusDays(30) to now
            else -> {
                val s = startDate?.let { LocalDate.parse(it) } ?: now.minusDays(30)
                val e = endDate?.let { LocalDate.parse(it) } ?: now
                s to e
            }
        }
        return history.filter { entry ->
            try {
                val entryDate = LocalDate.parse(entry.date)
                !entryDate.isBefore(start) && !entryDate.isAfter(end)
            } catch (e: Exception) { true }
        }
    }
}

// ============================================================================
// 全局单例
// ============================================================================

private val globalTokenStatsManager = TokenStatsManager()

fun getGlobalTokenStatsManager(): TokenStatsManager = globalTokenStatsManager
