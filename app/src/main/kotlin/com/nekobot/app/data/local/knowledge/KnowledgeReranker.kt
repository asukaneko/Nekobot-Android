package com.nekobot.app.data.local.knowledge

import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.KnowledgeSearchResult

/**
 * LLM-based 知识库检索结果重排器。
 *
 * 利用配置了 purpose="rerank" 的 LLM 对候选切片重新打分排序，
 * 提升最终返回结果的相关性质量。无 rerank 模型时降级为按原 score 排序。
 */
class KnowledgeReranker(
    private val aiClient: LocalAiClient,
    private val db: NekobotDatabase
) {
    /**
     * 对候选结果进行重排。
     *
     * 有 rerank 模型时：取前 [RagConfig.rerankCandidateCount] 个送入 LLM 打分，
     * 设置 rerankScore 后按 rerankScore 降序排列，未送入重排的候选保持原顺序追加在后。
     *
     * 无 rerank 模型时：降级为按原 score 降序排序（保底）。
     *
     * @param query 用户查询
     * @param candidates 待重排的候选列表
     * @param config RAG 配置
     * @return 重排后的结果列表
     */
    suspend fun rerank(
        query: String,
        candidates: List<KnowledgeSearchResult>,
        config: RagConfig
    ): List<KnowledgeSearchResult> {
        if (candidates.isEmpty()) return emptyList()

        val rerankModel = rerankModels().firstOrNull()
        if (rerankModel == null) {
            // 无 rerank 模型，降级为按原 score 排序
            return candidates.sortedByDescending { it.score ?: 0f }
        }

        // 取前 rerankCandidateCount 个送入重排
        val rerankCount = config.rerankCandidateCount.coerceIn(1, candidates.size)
        val toRerank = candidates.take(rerankCount)
        val remaining = candidates.drop(rerankCount)

        // 构造 prompt 并调用 LLM
        val prompt = buildRerankPrompt(query, toRerank)
        val messages = listOf(
            mapOf("role" to "user", "content" to prompt as Any)
        )

        val result = runCatching {
            aiClient.chatOnce(rerankModel, messages, emptyMap(), "rerank")
        }.onFailure {
            LocalLogger.w(TAG, "重排模型 ${rerankModel.name} 调用失败: ${it.message}")
        }.getOrNull()

        if (result == null || !result.error.isNullOrBlank() || result.content.isBlank()) {
            LocalLogger.w(TAG, "重排失败，降级为原 score 排序: ${result?.error ?: "空响应"}")
            return candidates.sortedByDescending { it.score ?: 0f }
        }

        // 解析 LLM 返回的分数
        val scores = parseScores(result.content, toRerank.size)

        // 设置 rerankScore 并按降序排列
        val reranked = toRerank.mapIndexed { index, candidate ->
            val score = scores[index]
            candidate.copy(rerankScore = score)
        }.sortedByDescending { it.rerankScore ?: 0f }

        // 未送入重排的候选保持原顺序追加在后面
        return reranked + remaining
    }

    /**
     * 构造重排 prompt。
     * 格式: "请对以下每条资料与用户查询的相关性打分（0到10，10最相关）。\n只返回每行分数，格式：编号:分数\n\n查询：{query}\n\n[1] {content}\n..."
     */
    private fun buildRerankPrompt(query: String, candidates: List<KnowledgeSearchResult>): String {
        val sb = StringBuilder()
        sb.append("请对以下每条资料与用户查询的相关性打分（0到10，10最相关）。\n")
        sb.append("只返回每行分数，格式：编号:分数\n\n")
        sb.append("查询：").append(query).append("\n\n")
        candidates.forEachIndexed { index, candidate ->
            sb.append("[").append(index + 1).append("] ").append(candidate.content.orEmpty()).append("\n")
        }
        return sb.toString()
    }

    /**
     * 解析 LLM 返回的分数。
     * 正则: `Regex("$idx\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)")` 对每个编号逐一匹配。
     *
     * @param content LLM 返回的文本
     * @param count 候选数量
     * @return 以候选索引(0-based)为键、分数(0~10)为值的映射
     */
    private fun parseScores(content: String, count: Int): Map<Int, Float> {
        val scores = mutableMapOf<Int, Float>()
        for (idx in 1..count) {
            val regex = Regex("$idx\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)")
            val match = regex.find(content)
            if (match != null) {
                val score = match.groupValues[1].toFloatOrNull()
                if (score != null) {
                    scores[idx - 1] = score.coerceIn(0f, 10f)
                }
            }
        }
        return scores
    }

    /**
     * 查找 purpose="rerank" 的模型，逻辑与 embeddingModels() 一致。
     */
    private suspend fun rerankModels(): List<LocalAiModelEntity> {
        val active = db.aiModelDao().getActiveByPurpose("rerank")
        return buildList {
            active?.takeIf { it.enabled }?.let(::add)
            db.aiModelDao().listByPurpose("rerank")
                .filter { it.id != active?.id }
                .forEach(::add)
        }
    }

    companion object {
        private const val TAG = "KnowledgeReranker"
    }
}
