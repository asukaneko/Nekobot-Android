package com.nekobot.app.data.local.ai

import com.google.gson.Gson

/**
 * 本地会话上下文占比的统一口径。
 *
 * 聊天页圆环、+ 面板的类型占比、全屏「上下文分析」页都使用这里的同一份估算，
 * 避免出现「圆环 12%、类型行合计 100%」这种分母不一致的情况。
 *
 * 统计范围对齐"下一次请求实际发送的内容"：
 * - 系统提示词（Agent 为每轮重新组装的 composed_system_prompt）
 * - 工具定义（function-calling JSON，Agent 会话随每次请求发送，体量很大）
 * - 压缩摘要与压缩边界之后的历史消息
 * - 已完成轮次消息上折叠保存的 tool_call_history
 * - 进行中一轮逐条落库的工具轨迹（local_agent_tool_messages）
 */
enum class ContextUsagePart {
    SYSTEM_PROMPT,
    TOOL_DEFINITIONS,
    SUMMARY,
    USER_MESSAGES,
    ASSISTANT_MESSAGES,
    TOOL_TRAJECTORY,
    OTHER_MESSAGES
}

data class ContextUsagePartTokens(
    val part: ContextUsagePart,
    val tokens: Int,
    val itemCount: Int
)

data class ContextUsageBreakdown(
    val parts: List<ContextUsagePartTokens>
) {
    val totalTokens: Int = parts.sumOf(ContextUsagePartTokens::tokens)

    val isEmpty: Boolean get() = parts.isEmpty()
}

/**
 * 参与上下文统计的一条消息（按发送顺序）。
 *
 * [toolHistoryTokens] / [toolHistoryCount] 是该消息自身折叠保存的工具历史
 * （已完成轮次），由调用方解析后传入，保持本函数纯粹可测。
 */
data class ContextUsageMessageRow(
    val role: String,
    val content: String,
    val toolHistoryTokens: Int = 0,
    val toolHistoryCount: Int = 0,
    val isSummary: Boolean = false
)

internal const val CONTEXT_USAGE_AGENT_OVERHEAD_TOKENS = 8
internal const val CONTEXT_USAGE_STANDARD_OVERHEAD_TOKENS = 4

private val contextUsageGson = Gson()

/**
 * 工具定义（OpenAI function-calling JSON）的 token 估算。
 *
 * 内置工具 + Skill + 数据库 + 子代理共 100+ 个定义，JSON 可达 5 万字符以上，
 * 是 Agent 请求里最大的一块固定开销，必须计入占比，否则圆环会明显偏低。
 */
internal fun estimateToolDefinitionsTokens(definitions: List<Map<String, Any>>): Int =
    if (definitions.isEmpty()) 0 else estimateLocalTextTokens(contextUsageGson.toJson(definitions))

/**
 * 汇总上下文构成。
 *
 * @param systemPromptTokens 实际系统提示词的估算（Agent 为 composed_system_prompt）
 * @param messages 压缩窗口内、仍会发送的历史消息（不含系统提示词，含压缩摘要行）
 * @param toolDefinitionTokens 本次请求工具定义的估算；非 Agent 会话为 0
 * @param toolTrajectoryTokens 进行中一轮逐条落库的工具轨迹；无落库行时为 0
 */
internal fun buildContextUsageBreakdown(
    isAgentSession: Boolean,
    systemPromptTokens: Int,
    messages: List<ContextUsageMessageRow>,
    toolDefinitionTokens: Int = 0,
    toolDefinitionCount: Int = 0,
    toolTrajectoryTokens: Int = 0,
    toolTrajectoryCount: Int = 0
): ContextUsageBreakdown {
    val tokensByPart = ContextUsagePart.entries.associateWith { 0 }.toMutableMap()
    val countByPart = ContextUsagePart.entries.associateWith { 0 }.toMutableMap()

    fun add(part: ContextUsagePart, tokens: Int, count: Int = 1) {
        tokensByPart[part] = tokensByPart.getValue(part) + tokens.coerceAtLeast(0)
        countByPart[part] = countByPart.getValue(part) + count
    }

    if (systemPromptTokens > 0) add(ContextUsagePart.SYSTEM_PROMPT, systemPromptTokens)
    if (toolDefinitionTokens > 0 || toolDefinitionCount > 0) {
        add(ContextUsagePart.TOOL_DEFINITIONS, toolDefinitionTokens, toolDefinitionCount)
    }

    // 逐条落库的工具轨迹 = 下一轮请求真正注入的内容。它已覆盖最后一条助手消息上
    // 折叠保存的那份受限历史，两者必须只计一次，否则工具占比会翻倍。
    val durableActive = toolTrajectoryTokens > 0 || toolTrajectoryCount > 0
    val supersededAnchorIndex = if (durableActive) {
        messages.indexOfLast { it.role.equals("assistant", ignoreCase = true) }
    } else {
        -1
    }

    val overhead = if (isAgentSession) {
        CONTEXT_USAGE_AGENT_OVERHEAD_TOKENS
    } else {
        CONTEXT_USAGE_STANDARD_OVERHEAD_TOKENS
    }
    messages.forEachIndexed { index, message ->
        val part = when {
            message.isSummary -> ContextUsagePart.SUMMARY
            message.role.equals("tool", ignoreCase = true) ||
                message.role.equals("function", ignoreCase = true) -> ContextUsagePart.TOOL_TRAJECTORY
            message.role.equals("user", ignoreCase = true) -> ContextUsagePart.USER_MESSAGES
            message.role.equals("assistant", ignoreCase = true) ||
                message.role.equals("model", ignoreCase = true) -> ContextUsagePart.ASSISTANT_MESSAGES
            else -> ContextUsagePart.OTHER_MESSAGES
        }
        add(part, estimateLocalTextTokens(message.content) + overhead)
        if (message.toolHistoryTokens > 0 || message.toolHistoryCount > 0) {
            if (index != supersededAnchorIndex) {
                add(ContextUsagePart.TOOL_TRAJECTORY, message.toolHistoryTokens, message.toolHistoryCount)
            }
        }
    }

    if (durableActive) add(ContextUsagePart.TOOL_TRAJECTORY, toolTrajectoryTokens, toolTrajectoryCount)

    return ContextUsageBreakdown(
        parts = ContextUsagePart.entries.mapNotNull { part ->
            val tokens = tokensByPart.getValue(part)
            val count = countByPart.getValue(part)
            if (tokens == 0 && count == 0) null else ContextUsagePartTokens(part, tokens, count)
        }
    )
}
