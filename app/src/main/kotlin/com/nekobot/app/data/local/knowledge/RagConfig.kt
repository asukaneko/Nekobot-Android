package com.nekobot.app.data.local.knowledge

/** RAG 检索可调参数，从 PrefsManager 读取。 */
data class RagConfig(
    /** 语义检索权重（0.0~1.0），词法权重 = 1 - 此值 */
    val semanticWeight: Float = 0.88f,
    /** 检索候选数（重排前，从全量切片中取前 N 个） */
    val candidateK: Int = 20,
    /** 最终返回结果数（重排后） */
    val topK: Int = 5,
    /** 最低得分阈值，低于此值的结果被过滤 */
    val scoreThreshold: Float = 0.01f,
    /** MMR 多样性系数（0=最大多样性，1=最大相关性） */
    val mmrLambda: Float = 0.7f,
    /** 是否启用重排 */
    val rerankEnabled: Boolean = false,
    /** 重排候选数（从 candidateK 中取前 N 个送入重排） */
    val rerankCandidateCount: Int = 10,
    /** searchPrompt 最大字符数 */
    val maxPromptChars: Int = 12_000,
    /** 是否在回答中生成引用标注 */
    val citationEnabled: Boolean = true
)
