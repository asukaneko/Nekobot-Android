package com.nekobot.app.data.local.knowledge

import kotlin.math.sqrt

/** 文档切片与纯本地检索算法；无向量模型时仍可工作。 */
object LocalKnowledgeSearch {
    private const val DEFAULT_CHUNK_SIZE = 900
    private const val DEFAULT_OVERLAP = 140

    fun chunk(
        content: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        overlap: Int = DEFAULT_OVERLAP
    ): List<String> {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return emptyList()
        val safeSize = chunkSize.coerceAtLeast(200)
        val safeOverlap = overlap.coerceIn(0, safeSize / 2)
        val result = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = (start + safeSize).coerceAtMost(normalized.length)
            if (end < normalized.length) {
                val searchFloor = (end - safeSize / 3).coerceAtLeast(start)
                val paragraphBreak = normalized.lastIndexOf("\n\n", end - 1)
                    .takeIf { it >= searchFloor }
                val sentenceBreak = normalized.lastIndexOfAny(
                    charArrayOf('。', '！', '？', '.', '!', '?', '\n'),
                    end - 1
                ).takeIf { it >= searchFloor }
                end = ((paragraphBreak?.plus(2)) ?: (sentenceBreak?.plus(1)) ?: end)
                    .coerceAtLeast(start + 1)
            }
            normalized.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(result::add)
            if (end >= normalized.length) break
            start = (end - safeOverlap).coerceAtLeast(start + 1)
        }
        return result
    }

    fun lexicalScore(query: String, text: String): Float {
        val queryTerms = terms(query)
        val textTerms = terms(text)
        if (queryTerms.isEmpty() || textTerms.isEmpty()) return 0f
        val queryCounts = queryTerms.groupingBy { it }.eachCount()
        val textCounts = textTerms.groupingBy { it }.eachCount()
        val dot = queryCounts.entries.sumOf { (term, count) ->
            count.toDouble() * (textCounts[term] ?: 0)
        }
        if (dot <= 0.0) return 0f
        val queryNorm = sqrt(queryCounts.values.sumOf { it.toDouble() * it })
        val textNorm = sqrt(textCounts.values.sumOf { it.toDouble() * it })
        return (dot / (queryNorm * textNorm).coerceAtLeast(1e-9)).toFloat()
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denominator <= 1e-12) 0f else (dot / denominator).toFloat()
    }

    internal fun terms(value: String): List<String> {
        val lower = value.lowercase()
        val terms = mutableListOf<String>()
        Regex("[a-z0-9_]+").findAll(lower).forEach { terms += it.value }
        val cjk = lower.filter { it.code in 0x3400..0x9FFF }
        cjk.forEach { terms += it.toString() }
        if (cjk.length > 1) {
            for (index in 0 until cjk.lastIndex) {
                terms += cjk.substring(index, index + 2)
            }
        }
        return terms
    }
}
