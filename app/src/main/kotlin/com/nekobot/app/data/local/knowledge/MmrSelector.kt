package com.nekobot.app.data.local.knowledge

import com.nekobot.app.data.model.KnowledgeSearchResult

/**
 * 最大边际相关性（Maximal Marginal Relevance）选择器。
 *
 * 在保证相关性的同时引入多样性，避免检索结果内容高度重复。
 * 公式：mmr_score = lambda * relevance - (1 - lambda) * max_similarity_to_selected
 */
object MmrSelector {

    /**
     * 从候选列表中按 MMR 策略选出 [k] 个结果。
     *
     * @param candidates 已按相关性降序排列的候选列表
     * @param lambda 相关性权重（0~1），值越大越偏向相关性，值越小越偏向多样性
     * @param k 最终选择数量
     * @return 按 MMR 选中顺序排列的结果列表
     */
    fun select(
        candidates: List<KnowledgeSearchResult>,
        lambda: Float,
        k: Int
    ): List<KnowledgeSearchResult> {
        if (candidates.isEmpty()) return emptyList()
        val selectCount = k.coerceIn(1, candidates.size)
        // 已选中的候选索引集合
        val selected = mutableListOf<KnowledgeSearchResult>()
        val selectedIndices = mutableSetOf<Int>()
        // 剩余候选的索引列表
        val remaining = candidates.indices.toMutableSet()

        // 第一个直接选相关性最高的（candidates 已按相关性降序排列）
        val firstIdx = 0
        selected.add(candidates[firstIdx])
        selectedIndices.add(firstIdx)
        remaining.remove(firstIdx)

        // 缓存切片间相似度，避免重复计算
        val similarityCache = HashMap<Pair<Int, Int>, Float>()

        while (selected.size < selectCount && remaining.isNotEmpty()) {
            var bestIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY

            for (candidateIdx in remaining) {
                val candidate = candidates[candidateIdx]
                // 该候选与已选结果的最大相似度
                var maxSim = 0f
                for (selectedIdx in selectedIndices) {
                    val sim = similarityCache.getOrPut(minOf(candidateIdx, selectedIdx) to maxOf(candidateIdx, selectedIdx)) {
                        val a = candidates[candidateIdx]
                        val b = candidates[selectedIdx]
                        // 使用 lexicalScore 计算切片间文本相似度
                        LocalKnowledgeSearch.lexicalScore(
                            a.content.orEmpty(),
                            b.content.orEmpty()
                        )
                    }
                    if (sim > maxSim) maxSim = sim
                }
                // MMR 得分：相关性 * lambda - 最大相似度 * (1-lambda)
                val relevance = candidate.score ?: 0f
                val mmrScore = lambda * relevance - (1f - lambda) * maxSim
                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestIdx = candidateIdx
                }
            }

            if (bestIdx >= 0) {
                selected.add(candidates[bestIdx])
                selectedIndices.add(bestIdx)
                remaining.remove(bestIdx)
            } else {
                break
            }
        }

        return selected
    }
}
