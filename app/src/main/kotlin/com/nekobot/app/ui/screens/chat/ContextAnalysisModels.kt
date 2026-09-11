package com.nekobot.app.ui.screens.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R
import com.nekobot.app.data.local.agentContextSummaryBoundaryId
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsageMessageRow
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.local.ai.buildContextUsageBreakdown
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session

/**
 * 服务端模式的回退口径：只有消息与提示词可估算，没有本地工具定义与工具轨迹。
 *
 * 本地模式一律使用 `agentLiveContextUsage` 返回的完整口径（含工具定义、
 * 逐条落库的工具轨迹），两者共用 [buildContextUsageBreakdown]，因此分类与分母一致。
 */
internal fun fallbackContextUsageBreakdown(
    session: Session?,
    messages: List<Message>
): ContextUsageBreakdown {
    val isAgentSession = session?.sessionMode.equals("agent", ignoreCase = true)
    // Agent 压缩后的旧消息仍留在聊天记录里，但不会再注入模型，必须先按摘要边界裁剪。
    val contextMessages = if (isAgentSession) {
        messages.agentContextWindow()
            .filter { !it.role.equals("system", ignoreCase = true) || it.isAgentContextSummary() }
    } else {
        messages.filterNot { it.role.equals("system", ignoreCase = true) }
    }
    val systemPrompt = session?.composedSystemPrompt?.takeIf { it.isNotBlank() }
        ?: session?.systemPrompt?.takeIf { it.isNotBlank() }
    val systemPromptTokens = systemPrompt?.let(::estimateLocalTextTokens)
        ?: messages
            .filter { it.role.equals("system", ignoreCase = true) && !it.isAgentContextSummary() }
            .sumOf { estimateLocalTextTokens(it.displayContent) }
    val rows = contextMessages.map { message ->
        ContextUsageMessageRow(
            // 与旧口径一致：优先按 isUser 判定用户消息，再退回 role
            role = if (message.isUser) "user" else message.role.orEmpty(),
            content = message.displayContent,
            toolHistoryTokens = message.toolCallHistory
                ?.takeIf { it.isNotEmpty() }
                ?.let { estimateLocalTextTokens(it.toString()) }
                ?: 0,
            toolHistoryCount = message.toolCallHistory?.size ?: 0,
            isSummary = message.isAgentContextSummary()
        )
    }
    return buildContextUsageBreakdown(
        isAgentSession = isAgentSession,
        systemPromptTokens = systemPromptTokens,
        messages = rows
    )
}

private fun List<Message>.agentContextWindow(): List<Message> {
    val summary = asReversed().firstOrNull(Message::isAgentContextSummary) ?: return this
    val boundaryId = summary.agentContextSummaryBoundaryId() ?: return this
    val boundaryIndex = indexOfFirst { it.id == boundaryId }
    if (boundaryIndex < 0) return this
    return buildList(size - boundaryIndex) {
        add(summary)
        addAll(this@agentContextWindow.drop(boundaryIndex + 1).filterNot(Message::isAgentContextSummary))
    }
}

/** 上下文类型在占比分析中的展示颜色（全屏页与 + 菜单共用）。 */
internal fun ContextUsagePart.displayColor(scheme: ColorScheme): Color = when (this) {
    ContextUsagePart.SYSTEM_PROMPT -> scheme.primary
    ContextUsagePart.TOOL_DEFINITIONS -> scheme.error
    ContextUsagePart.USER_MESSAGES -> scheme.secondary
    ContextUsagePart.ASSISTANT_MESSAGES -> scheme.tertiary
    ContextUsagePart.SUMMARY -> scheme.outline
    ContextUsagePart.TOOL_TRAJECTORY -> scheme.error.copy(alpha = 0.6f)
    ContextUsagePart.OTHER_MESSAGES -> scheme.outline.copy(alpha = 0.6f)
}

@Composable
internal fun contextPartLabel(part: ContextUsagePart): String = when (part) {
    ContextUsagePart.SYSTEM_PROMPT -> stringResource(R.string.chat_context_analysis_system_prompt)
    ContextUsagePart.TOOL_DEFINITIONS -> stringResource(R.string.chat_context_analysis_tool_definitions)
    ContextUsagePart.USER_MESSAGES -> stringResource(R.string.chat_context_analysis_user_messages)
    ContextUsagePart.ASSISTANT_MESSAGES -> stringResource(R.string.chat_context_analysis_assistant_messages)
    ContextUsagePart.SUMMARY -> stringResource(R.string.chat_context_analysis_summary)
    ContextUsagePart.TOOL_TRAJECTORY -> stringResource(R.string.chat_context_analysis_tool_content)
    ContextUsagePart.OTHER_MESSAGES -> stringResource(R.string.chat_context_analysis_other_messages)
}
