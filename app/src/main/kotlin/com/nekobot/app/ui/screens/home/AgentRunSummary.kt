package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nekobot.app.data.SessionActivity
import com.nekobot.app.data.SessionActivityPhase
import com.nekobot.app.ui.screens.sessions.SessionListRow
import com.nekobot.app.ui.screens.sessions.SessionModeLabel

/**
 * 首页 Agent 卡上的「正在运行」一行。
 *
 * 只带纯数据（会话标题 + 阶段 + 工具 id）：文案全部交给 Compose 侧按当前语言渲染，
 * 因此这里可以在 JVM 单元测试里直接构造验证。
 */
@Immutable
data class AgentRunUi(
    val sessionId: String,
    val title: String,
    /** null = 还没收到阶段上报（如服务器模式），界面显示通用「运行中」。 */
    val phase: SessionActivityPhase? = null,
    /** [SessionActivityPhase.TOOL] 阶段正在执行的工具 id。 */
    val toolName: String? = null
)

/** 首页一张卡最多列出多少个运行中的会话：再多也只是「还有 N 个」的提示。 */
internal const val MAX_HOME_AGENT_RUNS = 6

/**
 * 从会话列表快照 + 全局运行状态算出 Agent 卡要展示的运行会话。
 *
 * - 只统计 Agent 模式的会话：正在跑的角色会话属于角色聊天卡，不该出现在这里；
 * - 有阶段上报的会话按最近活跃排在前面，没有上报的按列表原有顺序跟在后面；
 * - 顺序通过稳定排序保持，同一时间戳的会话不会在两次重组之间跳动。
 *
 * @param runningSessionIds 全局「生成中」集合（见 `ServiceContainer.generatingSessions`）
 * @param activities sessionId → 当前运行阶段
 */
fun buildAgentRunUis(
    rows: List<SessionListRow>,
    runningSessionIds: Set<String>,
    activities: Map<String, SessionActivity>,
    limit: Int = MAX_HOME_AGENT_RUNS
): List<AgentRunUi> {
    if (runningSessionIds.isEmpty() || limit <= 0) return emptyList()
    val agentRows = rows.asSequence()
        .filter { it.modeLabel == SessionModeLabel.AGENT }
        .mapNotNull { row -> row.id?.takeIf { it.isNotBlank() }?.let { id -> id to row } }
        .toMap(LinkedHashMap())
    if (agentRows.isEmpty()) return emptyList()
    return runningSessionIds.asSequence()
        .mapNotNull { id -> agentRows[id]?.let { row -> id to row } }
        .sortedByDescending { (id, _) -> activities[id]?.updatedAt ?: Long.MIN_VALUE }
        .take(limit)
        .map { (id, row) ->
            val activity = activities[id]
            AgentRunUi(
                sessionId = id,
                title = row.listTitle,
                phase = activity?.phase,
                toolName = activity?.toolName
            )
        }
        .toList()
}
