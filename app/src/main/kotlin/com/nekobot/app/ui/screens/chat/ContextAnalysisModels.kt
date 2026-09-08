package com.nekobot.app.ui.screens.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R
import com.nekobot.app.data.local.agentContextSummaryBoundaryId
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session

internal enum class ContextPartType {
    SYSTEM_PROMPT,
    USER_MESSAGES,
    ASSISTANT_MESSAGES,
    COMPRESSED_SUMMARY,
    TOOL_CALLS,
    OTHER_MESSAGES
}

internal data class ContextPart(
    val type: ContextPartType,
    val estimatedTokens: Int,
    val itemCount: Int
)

internal data class ContextBreakdown(
    val parts: List<ContextPart>
) {
    val estimatedTokens: Int = parts.sumOf(ContextPart::estimatedTokens)
}

private const val STANDARD_MESSAGE_OVERHEAD_TOKENS = 4
private const val AGENT_MESSAGE_OVERHEAD_TOKENS = 8

/**
 * 按当前仍会传给模型的消息窗口估算上下文构成。
 *
 * Agent 压缩后的旧消息会继续显示在聊天记录中，但不会再次注入模型，
 * 因此这里需要按摘要边界裁剪，避免把它们错误地算入占比。
 */
internal fun buildContextBreakdown(
    session: Session?,
    messages: List<Message>
): ContextBreakdown {
    val isAgentSession = session?.sessionMode.equals("agent", ignoreCase = true)
    val contextMessages = if (isAgentSession) {
        messages.agentContextWindow()
            .filter { !it.role.equals("system", ignoreCase = true) || it.isAgentContextSummary() }
    } else {
        messages.filterNot { it.role.equals("system", ignoreCase = true) }
    }
    val overhead = if (isAgentSession) AGENT_MESSAGE_OVERHEAD_TOKENS else STANDARD_MESSAGE_OVERHEAD_TOKENS
    val tokensByType = ContextPartType.entries.associateWith { 0 }.toMutableMap()
    val countsByType = ContextPartType.entries.associateWith { 0 }.toMutableMap()

    fun add(type: ContextPartType, tokens: Int, count: Int = 1) {
        tokensByType[type] = tokensByType.getValue(type) + tokens.coerceAtLeast(0)
        countsByType[type] = countsByType.getValue(type) + count
    }

    val systemPrompt = session?.composedSystemPrompt
        ?.takeIf { it.isNotBlank() }
        ?: session?.systemPrompt?.takeIf { it.isNotBlank() }
    if (systemPrompt != null) {
        add(ContextPartType.SYSTEM_PROMPT, estimateLocalTextTokens(systemPrompt))
    } else {
        messages
            .filter { it.role.equals("system", ignoreCase = true) && !it.isAgentContextSummary() }
            .forEach { message ->
                add(ContextPartType.SYSTEM_PROMPT, estimateLocalTextTokens(message.displayContent) + overhead)
            }
    }

    contextMessages.forEach { message ->
        val type = when {
            message.isAgentContextSummary() -> ContextPartType.COMPRESSED_SUMMARY
            message.role.equals("tool", ignoreCase = true) ||
                message.role.equals("function", ignoreCase = true) -> ContextPartType.TOOL_CALLS
            message.isUser -> ContextPartType.USER_MESSAGES
            message.role.equals("assistant", ignoreCase = true) ||
                message.role.equals("model", ignoreCase = true) -> ContextPartType.ASSISTANT_MESSAGES
            else -> ContextPartType.OTHER_MESSAGES
        }
        add(type, estimateLocalTextTokens(message.displayContent) + overhead)
        message.toolCallHistory
            ?.takeIf { it.isNotEmpty() }
            ?.let { history ->
                add(ContextPartType.TOOL_CALLS, estimateLocalTextTokens(history.toString()), history.size)
            }
    }

    return ContextBreakdown(
        parts = ContextPartType.entries.mapNotNull { type ->
            val tokens = tokensByType.getValue(type)
            val count = countsByType.getValue(type)
            if (tokens == 0 && count == 0) null else ContextPart(type, tokens, count)
        }
    )
}

/**
 * 合并 Agent 运行中（尚未落库、但会随 tool_call_history 注入下一轮请求）的工具调用历史，
 * 归入 [ContextPartType.TOOL_CALLS]，使占比与分析随工具调用步骤动态增长。
 */
internal fun ContextBreakdown.withLiveToolTokens(liveTokens: Long, liveCount: Int): ContextBreakdown {
    if (liveTokens <= 0 && liveCount <= 0) return this
    val mapped = parts.map { part ->
        if (part.type == ContextPartType.TOOL_CALLS) {
            part.copy(
                estimatedTokens = part.estimatedTokens + liveTokens.toInt(),
                itemCount = part.itemCount + liveCount
            )
        } else {
            part
        }
    }
    return if (mapped.none { it.type == ContextPartType.TOOL_CALLS }) {
        ContextBreakdown(parts = mapped + ContextPart(ContextPartType.TOOL_CALLS, liveTokens.toInt(), liveCount))
    } else {
        ContextBreakdown(parts = mapped)
    }
}

/** 上下文类型在占比分析中的展示颜色（全屏页与 + 菜单共用）。 */
internal fun ContextPartType.displayColor(scheme: ColorScheme): Color = when (this) {
    ContextPartType.SYSTEM_PROMPT -> scheme.primary
    ContextPartType.USER_MESSAGES -> scheme.secondary
    ContextPartType.ASSISTANT_MESSAGES -> scheme.tertiary
    ContextPartType.COMPRESSED_SUMMARY -> scheme.outline
    ContextPartType.TOOL_CALLS -> scheme.error
    ContextPartType.OTHER_MESSAGES -> scheme.outline
}

@Composable
internal fun contextPartLabel(type: ContextPartType): String = when (type) {
    ContextPartType.SYSTEM_PROMPT -> stringResource(R.string.chat_context_analysis_system_prompt)
    ContextPartType.USER_MESSAGES -> stringResource(R.string.chat_context_analysis_user_messages)
    ContextPartType.ASSISTANT_MESSAGES -> stringResource(R.string.chat_context_analysis_assistant_messages)
    ContextPartType.COMPRESSED_SUMMARY -> stringResource(R.string.chat_context_analysis_summary)
    ContextPartType.TOOL_CALLS -> stringResource(R.string.chat_context_analysis_tool_content)
    ContextPartType.OTHER_MESSAGES -> stringResource(R.string.chat_context_analysis_other_messages)
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
