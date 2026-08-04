package com.nekobot.app.data.local.ai

/**
 * A/B 测试分流器。
 *
 * 基于 sessionId 的确定性哈希分流，确保同一会话始终落入同一分组，
 * 从而保证对比实验的可重复性。
 */
class AbTestSplitter(private val configProvider: () -> AbTestConfig) {

    /**
     * 检查是否启用 A/B 测试且配置了两个模型。
     *
     * @return true 表示当前配置可进行分流
     */
    fun shouldSplit(sessionId: String): Boolean {
        val config = configProvider()
        return config.enabled &&
            !config.controlModelId.isNullOrEmpty() &&
            !config.experimentModelId.isNullOrEmpty()
    }

    /**
     * 用 sessionId.hashCode 做确定性分流。
     *
     * 将 hashCode 视为无符号 32 位整数，映射到 [0, 1) 区间，
     * 小于 [AbTestConfig.splitRatio] 的进入实验组，其余进入对照组。
     *
     * @return "experiment" 或 "control"
     */
    fun resolveGroup(sessionId: String): String {
        val config = configProvider()
        val hash = sessionId.hashCode().toLong() and 0xFFFFFFFFL
        val ratio = (hash % 1000) / 1000.0f
        return if (ratio < config.splitRatio) "experiment" else "control"
    }

    /**
     * 返回当前会话应使用的模型 ID。
     *
     * @return 模型 ID；若未启用 A/B 测试或配置不完整则返回 null
     */
    fun resolveModelId(sessionId: String): String? {
        if (!shouldSplit(sessionId)) return null
        val config = configProvider()
        return when (resolveGroup(sessionId)) {
            "experiment" -> config.experimentModelId
            else -> config.controlModelId
        }
    }
}
