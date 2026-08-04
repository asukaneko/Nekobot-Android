package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.db.RoutingDecisionLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 路由决策日志记录器。
 *
 * 在每次模型路由完成后记录决策快照（候选得分、选中原因、估算成本等），
 * 并在请求结束后回填实际结果（耗时、TTFT、成功与否、质量评分），
 * 为后续的路由可解释性分析和 A/B 测试提供数据支撑。
 */
class RoutingDecisionLogger(
    private val db: NekobotDatabase,
    private val gson: Gson = Gson()
) {

    /**
     * 记录一次路由决策。
     *
     * @param request 路由请求上下文
     * @param candidates 候选模型的分项得分列表（已排序）
     * @param selectedId 最终选中的模型 ID
     * @param sessionId 会话 ID
     * @param estimatedCostUsd 估算成本（USD）
     * @param abTestConfig A/B 测试配置（可选，非空且 enabled 时记录分组信息）
     * @return 日志 ID，供后续回填实际结果
     */
    suspend fun log(
        request: SmartRoutingRequest,
        candidates: List<SmartModelScoreBreakdown>,
        selectedId: String,
        sessionId: String,
        estimatedCostUsd: Double,
        abTestConfig: AbTestConfig? = null
    ): String {
        val logId = UUID.randomUUID().toString()
        val selected = candidates.firstOrNull { it.model.id == selectedId }
        val runnerUp = candidates.firstOrNull { it.model.id != selectedId }

        val selectedModel = selected?.model
        val selectedName = selectedModel?.name ?: selectedId
        val selectedScore = selected?.score ?: 0.0
        val runnerUpName = runnerUp?.model?.name
        val runnerUpScore = runnerUp?.score

        val reason = buildReasonText(
            selectedName = selectedName,
            selectedScore = selectedScore,
            selected = selected,
            runnerUpName = runnerUpName,
            runnerUpScore = runnerUpScore
        )

        // 将完整决策快照序列化为 JSON，便于后续回溯分析
        val decisionJson = gson.toJson(
            mapOf(
                "reason" to reason,
                "candidates" to candidates.map {
                    mapOf(
                        "modelId" to it.model.id,
                        "modelName" to it.model.name,
                        "score" to it.score,
                        "priceScore" to it.priceScore,
                        "speedScore" to it.speedScore,
                        "failurePenalty" to it.failurePenalty,
                        "contextBonus" to it.contextBonus,
                        "capabilityBonus" to it.capabilityBonus,
                        "priorityBonus" to it.priorityBonus,
                        "noHistoryPenalty" to it.noHistoryPenalty,
                        "reasons" to it.reasons
                    )
                },
                "request" to mapOf(
                    "promptChars" to request.promptChars,
                    "estimatedContextTokens" to request.estimatedContextTokens,
                    "sessionMode" to request.sessionMode,
                    "hasAttachments" to request.hasAttachments,
                    "dailyBudgetUsd" to request.dailyBudgetUsd,
                    "dailySpentUsd" to request.dailySpentUsd,
                    "isAgent" to request.isAgent,
                    "isComplex" to request.isComplex,
                    "isSimple" to request.isSimple
                )
            )
        )

        val isAbTest = abTestConfig != null && abTestConfig.enabled
        val abTestGroup = if (isAbTest) {
            AbTestSplitter({ abTestConfig }).resolveGroup(sessionId)
        } else null

        val entity = RoutingDecisionLogEntity(
            id = logId,
            sessionId = sessionId,
            createdAt = nowIso(),
            decisionJson = decisionJson,
            selectedModelId = selectedId,
            selectedModelName = selectedName,
            estimatedCostUsd = estimatedCostUsd,
            isAbTest = isAbTest,
            abTestGroup = abTestGroup
        )
        db.routingDecisionLogDao().insert(entity)
        return logId
    }

    /**
     * 回填实际执行结果。
     *
     * @param logId 日志 ID（由 [log] 返回）
     * @param actualCostUsd 实际花费（USD）
     * @param actualDurationMs 实际总耗时（毫秒）
     * @param actualTtftMs 实际首 token 耗时（毫秒）
     * @param success 是否成功
     * @param failureReason 失败原因（成功时为 null）
     */
    suspend fun updateActualResult(
        logId: String,
        actualCostUsd: Double,
        actualDurationMs: Long,
        actualTtftMs: Long,
        success: Boolean,
        failureReason: String? = null
    ) {
        db.routingDecisionLogDao().updateResult(
            id = logId,
            actualCostUsd = actualCostUsd,
            actualDurationMs = actualDurationMs,
            actualTtftMs = actualTtftMs,
            success = success,
            failureReason = failureReason
        )
    }

    /**
     * 记录用户对回复质量的主观评分。
     *
     * @param logId 日志 ID
     * @param score 质量评分（-1=差, 0=未评, 1=好）
     */
    suspend fun recordQualityScore(logId: String, score: Int) {
        db.routingDecisionLogDao().updateQualityScore(logId, score)
    }

    /**
     * 查询历史决策日志。
     *
     * @param sessionId 会话 ID（为 null 时查询全部）
     * @param limit 返回条数上限
     */
    suspend fun queryHistory(
        sessionId: String? = null,
        limit: Int = 50
    ): List<RoutingDecisionLogEntity> {
        val dao = db.routingDecisionLogDao()
        return if (sessionId != null) {
            dao.listBySession(sessionId, limit)
        } else {
            dao.listRecent(limit)
        }
    }

    /**
     * 按 ID 获取单条日志。
     */
    suspend fun getById(id: String): RoutingDecisionLogEntity? {
        return db.routingDecisionLogDao().getById(id)
    }

    /**
     * 清空全部路由决策日志。
     */
    suspend fun clearAll() {
        db.routingDecisionLogDao().clear()
    }

    // ---- 内部辅助 ----

    /**
     * 构造人类可读的选择原因文本。
     * 格式：
     * 选中模型: {name}（总分 {score:.2f}）
     * {价格优势/速度优势/能力匹配等}
     * ；次选: {runnerUp.name}（{score:.2f}）
     */
    private fun buildReasonText(
        selectedName: String,
        selectedScore: Double,
        selected: SmartModelScoreBreakdown?,
        runnerUpName: String?,
        runnerUpScore: Double?
    ): String {
        val sb = StringBuilder()
        sb.append("选中模型: ").append(selectedName)
        sb.append("（总分 ").append("%.2f".format(selectedScore)).append("）")

        // 汇总选中模型的优势项
        val advantages = mutableListOf<String>()
        selected?.let { b ->
            if (b.priceScore < 0.3) advantages.add("价格优势")
            if (b.speedScore < 0.3) advantages.add("速度优势")
            if (b.capabilityBonus > 10) advantages.add("能力匹配")
        }
        if (advantages.isNotEmpty()) {
            sb.append("\n").append(advantages.joinToString("、"))
        }

        if (runnerUpName != null && runnerUpScore != null) {
            sb.append("；次选: ").append(runnerUpName)
            sb.append("（").append("%.2f".format(runnerUpScore)).append("）")
        }
        return sb.toString()
    }

    /** 生成可排序的 ISO 风格时间戳（yyyy-MM-dd HH:mm:ss） */
    private fun nowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }
}
