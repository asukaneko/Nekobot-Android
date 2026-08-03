package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAgentRunEntity

internal object AgentRunStatus {
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val FAILED = "failed"
}

internal object AgentRunStage {
    const val PREPARING = "preparing"
    const val THINKING = "thinking"
    const val TOOL = "tool"
    const val PAUSED = "paused"
    const val FAILED = "failed"
}

/** 聊天页只消费精简后的恢复状态，不直接持有可能很大的 checkpoint JSON。 */
data class AgentRecoveryState(
    val sessionId: String,
    val status: String,
    val stage: String,
    val completedToolCalls: Int,
    val lastToolName: String? = null,
    val lastError: String? = null,
    val updatedAt: String,
    val hasCheckpoint: Boolean,
    /** 中断发生在工具执行中，工具可能已产生副作用但结果尚未进入安全检查点。 */
    val mayHaveUncommittedToolEffect: Boolean
) {
    val canContinueFromCheckpoint: Boolean
        get() = hasCheckpoint && completedToolCalls > 0
}

internal fun LocalAgentRunEntity.toRecoveryState(
    hasLiveGeneration: Boolean
): AgentRecoveryState? {
    if (status == AgentRunStatus.RUNNING && hasLiveGeneration) return null
    return AgentRecoveryState(
        sessionId = sessionId,
        status = if (status == AgentRunStatus.RUNNING) "interrupted" else status,
        stage = stage,
        completedToolCalls = completedToolCalls,
        lastToolName = lastToolName,
        lastError = lastError,
        updatedAt = updatedAt,
        hasCheckpoint = !checkpointHistory.isNullOrBlank(),
        mayHaveUncommittedToolEffect = stage == AgentRunStage.TOOL
    )
}

internal fun completedAgentToolCallCount(history: List<Map<String, Any>>): Int =
    history.count { it["role"] == "tool" }

internal fun lastCompletedAgentToolName(history: List<Map<String, Any>>): String? =
    history.asReversed().firstNotNullOfOrNull { message ->
        (message["name"] as? String)?.takeIf { message["role"] == "tool" && it.isNotBlank() }
    }
