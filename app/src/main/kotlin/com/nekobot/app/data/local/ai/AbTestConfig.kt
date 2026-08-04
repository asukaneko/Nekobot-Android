package com.nekobot.app.data.local.ai

/** A/B 测试配置，用于模型对比实验。 */
data class AbTestConfig(
    val enabled: Boolean = false,
    /** 分流比例：0.0~1.0，如 0.3 表示 30% 流量到实验组 */
    val splitRatio: Float = 0.5f,
    /** 对照组模型 ID */
    val controlModelId: String? = null,
    /** 实验组模型 ID */
    val experimentModelId: String? = null,
    /** 测试名称 */
    val testName: String = "default"
)
