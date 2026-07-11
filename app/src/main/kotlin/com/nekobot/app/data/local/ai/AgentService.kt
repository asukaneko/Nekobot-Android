package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Agent 服务：工具循环 + 上下文准备，对应原仓库 nbot/core/agent_service.py。
 *
 * 提供工具调用循环（model ↔ tool 多轮交互）和消息上下文预处理。
 */

private val agentGson = Gson()

// ============================================================================
// 异常类型
// ============================================================================

/** 工具循环主动退出（如需要用户确认） */
class ToolLoopExit(val finalContent: String) : Exception(finalContent)

/** 工具循环中模型调用错误，携带迭代索引 */
class ToolLoopModelError(val original: Throwable, val iteration: Int) : Exception(original.message ?: "")

// ============================================================================
// 数据类
// ============================================================================

/** 工具循环钩子 */
data class ToolLoopHooks(
    val onIterationStart: ((Int, List<Map<String, Any>>) -> Unit)? = null,
    val onToolStart: ((Map<String, Any>, String, Int, List<Map<String, Any>>) -> Unit)? = null,
    val onToolResult: ((Map<String, Any>, Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>?)? = null
)

/** 工具循环结果 */
data class ToolLoopResult(
    val finalContent: String = "",
    val toolMessages: List<Map<String, Any>> = emptyList(),
    val stopped: Boolean = false,
    val iterations: Int = 0,
    val consecutiveErrors: Int = 0,
    val usage: Map<String, Any> = emptyMap(),
    val modelId: String = "",
    val modelName: String = "",
    val failoverEvents: List<Map<String, Any>> = emptyList()
)

/** 工具执行结果（含循环结果和准备好的消息） */
data class ToolExecutionResult(
    val loopResult: ToolLoopResult,
    val preparedMessages: List<Map<String, Any>> = emptyList()
)

/** 工具循环会话配置 */
data class ToolLoopSession(
    val initialMessages: List<Map<String, Any>>,
    val modelCall: ModelCall,
    val toolExecutor: (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>,
    val toolCallHistory: List<Map<String, Any>>? = null,
    val maxIterations: Int = 50,
    val maxConsecutiveErrors: Int = 3,
    val hooks: ToolLoopHooks? = null
)

/** 准备好的聊天上下文 */
data class PreparedChatContext(
    val messages: List<Map<String, Any>> = emptyList(),
    val toolCallHistory: List<Map<String, Any>>? = null
)

// ============================================================================
// 上下文准备
// ============================================================================

/** "继续"触发词 */
private val CONTINUE_TOKENS = listOf("继续", "继续执行", "continue")

/** 判断用户输入是否为"继续"请求 */
fun isContinueRequest(userContent: String, continueTokens: List<String> = CONTINUE_TOKENS): Boolean {
    return userContent.trim().lowercase() in continueTokens.map { it.lowercase() }.toSet()
}

/**
 * 恢复"继续"场景下的消息和工具调用历史。
 *
 * 若用户输入为"继续"且最后一条助手消息含 tool_call_history，则移除该标记消息并恢复工具历史。
 *
 * @return Pair(working_messages, tool_call_history_or_null)
 */
fun restoreContinueMessages(
    messages: List<Map<String, Any>>,
    userContent: String,
    continueTokens: List<String> = CONTINUE_TOKENS
): Pair<List<Map<String, Any>>, List<Map<String, Any>>?> {
    val working = messages.map { it.toMutableMap() }.toMutableList()
    if (working.isEmpty() || !isContinueRequest(userContent, continueTokens)) {
        return working to null
    }

    var markerMessage: Map<String, Any>? = null

    // 场景1：最后是 user "继续"，倒数第二是 can_continue 的 assistant
    if (working.size >= 2) {
        val last = working.last()
        val secondLast = working[working.size - 2]
        if (last["role"] == "user" &&
            (last["content"] as? String ?: "").trim() == userContent.trim() &&
            secondLast["can_continue"] == true &&
            secondLast["tool_call_history"] != null
        ) {
            markerMessage = secondLast
            working.removeAt(working.size - 1)
            working.removeAt(working.size - 1)
        }
    }

    // 场景2：最后一条直接是 can_continue 的 assistant
    if (markerMessage == null) {
        val last = working.last()
        if (last["can_continue"] == true && last["tool_call_history"] != null) {
            markerMessage = last
            working.removeAt(working.size - 1)
        }
    }

    if (markerMessage == null) {
        return messages.map { it.toMutableMap() } to null
    }

    @Suppress("UNCHECKED_CAST")
    val history = markerMessage!!["tool_call_history"] as? List<Map<String, Any>>
    return working to history?.map { it.toMap() }
}

/**
 * 裁剪消息列表以控制总字符数。
 * 保留 system 消息和最近的消息，从最早的非 system 消息开始移除。
 */
fun trimMessages(messages: List<Map<String, Any>>, maxTotalChars: Int = 30000): List<Map<String, Any>> {
    val totalChars = messages.sumOf { (it["content"] as? String ?: "").length }
    if (totalChars <= maxTotalChars) return messages

    val systemMessage = if (messages.isNotEmpty() && messages[0]["role"] == "system") messages[0] else null
    val nonSystem = if (systemMessage != null) messages.drop(1).toMutableList() else messages.toMutableList()

    var currentTotal = totalChars
    while (nonSystem.isNotEmpty() && currentTotal > maxTotalChars) {
        val removed = nonSystem.removeAt(0)
        currentTotal -= (removed["content"] as? String ?: "").length
    }

    return if (systemMessage != null) listOf(systemMessage) + nonSystem else nonSystem
}

/** 将知识库文本注入到 system 消息中 */
fun injectKnowledgeContext(messages: List<Map<String, Any>>, knowledgeText: String): List<Map<String, Any>> {
    if (knowledgeText.isBlank()) return messages
    val updated = messages.map { it.toMutableMap() }.toMutableList()
    if (updated.isNotEmpty() && updated[0]["role"] == "system") {
        updated[0]["content"] = (updated[0]["content"] as? String ?: "") + "\n\n$knowledgeText"
    } else {
        updated.add(0, mutableMapOf("role" to "system", "content" to knowledgeText))
    }
    return updated
}

/**
 * 展开隐藏在消息中的 tool_call_history。
 * 消息中的 tool_call_history 字段会被展开为独立的 assistant/tool 消息。
 */
fun expandHiddenToolHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    val expanded = mutableListOf<Map<String, Any>>()
    for (message in messages.map { it.toMutableMap() }) {
        val hidden = message.remove("tool_call_history")
        expanded.add(message.toMap())
        @Suppress("UNCHECKED_CAST")
        if (hidden is List<*>) {
            for (hiddenMsg in hidden) {
                if (hiddenMsg !is Map<*, *>) continue
                val role = hiddenMsg["role"] as? String ?: ""
                if (role !in listOf("assistant", "tool")) continue
                @Suppress("UNCHECKED_CAST")
                expanded.add((hiddenMsg as Map<String, Any>).toMap())
            }
        }
    }
    return expanded
}

/**
 * 准备聊天上下文：恢复"继续"消息、展开工具历史、裁剪、注入知识库。
 */
fun prepareChatContext(
    messages: List<Map<String, Any>>,
    userContent: String,
    knowledgeText: String = "",
    maxTotalChars: Int = 30000,
    continueTokens: List<String> = CONTINUE_TOKENS
): PreparedChatContext {
    val (restored, toolCallHistory) = restoreContinueMessages(messages, userContent, continueTokens)
    val expanded = expandHiddenToolHistory(restored)
    val prepared = injectKnowledgeContext(trimMessages(expanded, maxTotalChars), knowledgeText)
    return PreparedChatContext(messages = prepared, toolCallHistory = toolCallHistory)
}

/** 将工具调用历史追加到消息列表末尾 */
fun applyToolCallHistory(messages: List<Map<String, Any>>, toolCallHistory: List<Map<String, Any>>?): List<Map<String, Any>> {
    if (toolCallHistory.isNullOrEmpty()) return messages
    return messages + toolCallHistory.map { it.toMap() }
}

/** 提取工具调用历史（role 为 assistant 或 tool 的消息） */
fun extractToolCallHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    return messages.filter { it["role"] in listOf("assistant", "tool") }.map { it.toMap() }
}

// ============================================================================
// 响应内容处理
// ============================================================================

/** 清理响应内容：去除 markdown 代码块包裹 */
fun cleanResponseContent(content: String): String {
    var cleaned = content.trim()
    if (cleaned.startsWith("```json")) {
        cleaned = cleaned.removePrefix("```json")
        if (cleaned.endsWith("```")) cleaned = cleaned.removeSuffix("```")
    } else if (cleaned.startsWith("```")) {
        cleaned = cleaned.removePrefix("```")
        if (cleaned.endsWith("```")) cleaned = cleaned.removeSuffix("```")
    }
    return cleaned.trim()
}

/** 从响应内容中提取显示文本（解析 {msg: "..."} 格式） */
fun extractDisplayText(content: String): String {
    val cleaned = cleanResponseContent(content)
    if (cleaned.startsWith("{")) {
        return try {
            val fixed = cleaned
                .replace("\u201C", "\"")
                .replace("\u201D", "\"")
                .replace("\uFF1A", ":")
            @Suppress("UNCHECKED_CAST")
            val parsed = agentGson.fromJson(fixed, Map::class.java) as? Map<String, Any>
            if (parsed?.containsKey("msg") == true) parsed["msg"].toString() else cleaned
        } catch (e: Exception) {
            cleaned
        }
    }
    return cleaned
}

// ============================================================================
// 工具循环控制
// ============================================================================

/** 判断工具循环是否应停止 */
fun shouldStopToolLoop(
    finalContent: String,
    finishReason: String,
    iteration: Int,
    maxIterations: Int,
    consecutiveErrors: Int,
    maxConsecutiveErrors: Int = 3
): Boolean {
    if (finishReason == "content_filter") return true
    return finishReason == "stop" ||
        (finishReason.isEmpty() && finalContent.isNotEmpty()) ||
        finalContent.trimEnd().endsWith("break") ||
        iteration >= maxIterations - 1 ||
        consecutiveErrors >= maxConsecutiveErrors
}

/** 合并 usage 字典 */
private fun mergeUsage(target: MutableMap<String, Any>, usage: Any?) {
    if (usage !is Map<*, *>) return
    for ((key, value) in usage) {
        try {
            val amount = (value as? Number)?.toInt() ?: continue
            if (amount > 0) {
                target[key as String] = (target[key as String] as? Int ?: 0) + amount
            }
        } catch (e: Exception) {
            continue
        }
    }
}

// ============================================================================
// 工具循环主逻辑
// ============================================================================

/**
 * 运行工具调用循环。
 *
 * 每轮：调用模型 → 若有 tool_calls 则执行工具并继续 → 否则返回最终内容。
 *
 * @param initialMessages 初始消息列表
 * @param modelCall 模型调用函数
 * @param toolExecutor 工具执行器 (tool_call, thinking, iteration, messages) -> result
 * @param maxIterations 最大迭代次数
 * @param maxConsecutiveErrors 最大连续错误数
 * @param hooks 钩子回调
 * @return ToolLoopResult
 */
fun runToolCallLoop(
    initialMessages: List<Map<String, Any>>,
    modelCall: ModelCall,
    toolExecutor: (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>,
    maxIterations: Int = 50,
    maxConsecutiveErrors: Int = 3,
    hooks: ToolLoopHooks? = null
): ToolLoopResult {
    val toolMessages = initialMessages.map { it.toMutableMap() }.toMutableList()
    var finalContent = ""
    var consecutiveErrors = 0
    val usageTotal = mutableMapOf<String, Any>()
    var currentModelId = ""
    var currentModelName = ""
    val allFailoverEvents = mutableListOf<Map<String, Any>>()

    fun result(
        finalContentArg: String? = null,
        toolMessagesArg: List<Map<String, Any>>? = null,
        stopped: Boolean = false,
        iterations: Int = 0,
        consecutiveErrorsArg: Int? = null
    ): ToolLoopResult = ToolLoopResult(
        finalContent = finalContentArg ?: finalContent,
        toolMessages = toolMessagesArg ?: toolMessages.map { it.toMap() },
        stopped = stopped,
        iterations = iterations,
        consecutiveErrors = consecutiveErrorsArg ?: consecutiveErrors,
        usage = usageTotal.toMap(),
        modelId = currentModelId,
        modelName = currentModelName,
        failoverEvents = allFailoverEvents.toList()
    )

    for (iteration in 0 until maxIterations) {
        // 检查停止标志（通过 modelCall 的 stopped 参数传递）
        // 实际停止由调用方在 modelCall 中处理

        hooks?.onIterationStart?.invoke(iteration, toolMessages.map { it.toMap() })

        val response = try {
            modelCall(toolMessages.map { it.toMap() }, false)
        } catch (e: ToolLoopExit) {
            return result(finalContentArg = e.finalContent, iterations = iteration + 1)
        } catch (e: Exception) {
            throw ToolLoopModelError(e, iteration)
        }

        // 提取模型追踪信息
        (response["_model_id"] as? String)?.let { currentModelId = it }
        (response["_model_name"] as? String)?.let { currentModelName = it }
        @Suppress("UNCHECKED_CAST")
        (response["_failover_events"] as? List<Map<String, Any>>)?.let { allFailoverEvents.addAll(it) }

        mergeUsage(usageTotal, response["usage"])

        @Suppress("UNCHECKED_CAST")
        val toolCalls = response["tool_calls"] as? List<Map<String, Any>> ?: emptyList()
        val thinkingContent = (response["thinking_content"] as? String) ?: (response["content"] as? String) ?: ""

        if (toolCalls.isNotEmpty()) {
            // 构造 assistant 消息（含 tool_calls）
            val toolCallEntries = toolCalls.map { tc ->
                buildMap {
                    put("id", tc["id"] ?: "")
                    put("type", "function")
                    val funcMap = mutableMapOf<String, Any>(
                        "name" to (tc["name"] ?: ""),
                        "arguments" to agentGson.toJson(tc["arguments"] ?: emptyMap<String, Any>())
                    )
                    tc["_thought_signature"]?.let { funcMap["_thought_signature"] = it }
                    put("function", funcMap)
                }
            }
            toolMessages.add(mutableMapOf(
                "role" to "assistant",
                "content" to (response["content"] ?: ""),
                "tool_calls" to toolCallEntries
            ))

            // 执行每个工具调用
            for (toolCall in toolCalls) {
                hooks?.onToolStart?.invoke(toolCall, thinkingContent, iteration, toolMessages.map { it.toMap() })

                val toolResult = try {
                    toolExecutor(toolCall, thinkingContent, iteration, toolMessages.map { it.toMap() })
                } catch (e: ToolLoopExit) {
                    return result(finalContentArg = e.finalContent, iterations = iteration + 1)
                }

                var toolHistoryMessage: Map<String, Any>? = null
                if (hooks?.onToolResult != null) {
                    toolHistoryMessage = hooks.onToolResult.invoke(
                        toolCall, toolResult, thinkingContent, iteration, toolMessages.map { it.toMap() }
                    )
                }

                if (toolHistoryMessage == null) {
                    toolHistoryMessage = mapOf(
                        "role" to "tool",
                        "tool_call_id" to (toolCall["id"] as? String ?: ""),
                        "name" to (toolCall["name"] as? String ?: ""),
                        "content" to agentGson.toJson(toolResult)
                    )
                }

                toolMessages.add(toolHistoryMessage.toMutableMap())
            }
            continue
        }

        // 无工具调用，提取最终内容
        finalContent = (response["content"] as? String) ?: ""
        val finishReason = (response["finish_reason"] as? String) ?: ""
        consecutiveErrors = if (finalContent.isNotEmpty()) 0 else consecutiveErrors + 1

        // 处理 content_filter
        if (finishReason == "content_filter") {
            if (finalContent.isEmpty()) {
                finalContent = "抱歉，我的回答触发了内容安全过滤，请换个话题试试。"
            }
            return result(iterations = iteration + 1)
        }

        if (shouldStopToolLoop(finalContent, finishReason, iteration, maxIterations, consecutiveErrors, maxConsecutiveErrors)) {
            if (finalContent.trimEnd().endsWith("break")) {
                finalContent = finalContent.trimEnd().removeSuffix("break").trimEnd()
            }
            return result(iterations = iteration + 1)
        }

        // 未停止，继续循环
        toolMessages.add(mutableMapOf("role" to "assistant", "content" to finalContent))
    }

    // 达到最大迭代次数仍未停止
    if (finalContent.isEmpty()) {
        for (message in toolMessages.reversed()) {
            if (message["role"] == "assistant") {
                finalContent = (message["content"] as? String) ?: ""
                break
            }
        }
    }

    return result(iterations = maxIterations)
}

/**
 * 运行工具循环会话（含工具调用历史恢复）。
 */
fun runToolLoopSession(session: ToolLoopSession): ToolExecutionResult {
    val preparedMessages = applyToolCallHistory(session.initialMessages, session.toolCallHistory)
    val loopResult = runToolCallLoop(
        preparedMessages,
        session.modelCall,
        session.toolExecutor,
        maxIterations = session.maxIterations,
        maxConsecutiveErrors = session.maxConsecutiveErrors,
        hooks = session.hooks
    )
    return ToolExecutionResult(loopResult = loopResult, preparedMessages = preparedMessages)
}

/** 从循环结果中解析最终内容 */
fun resolveLoopFinalContent(loopResult: ToolLoopResult, defaultContent: String = ""): String {
    if (loopResult.finalContent.isNotEmpty()) return loopResult.finalContent
    if (loopResult.toolMessages.isNotEmpty()) {
        val lastMsg = loopResult.toolMessages.last()
        if (lastMsg["role"] == "assistant") {
            return (lastMsg["content"] as? String) ?: defaultContent
        }
    }
    return defaultContent
}

/** 构建"继续"响应 */
fun buildContinueChatResponse(
    finalContent: String = "【生成已停止 - 工具调用记录已保存，回复「继续」可继续执行】",
    toolMessages: List<Map<String, Any>>? = null,
    toolTrace: List<Map<String, Any>>? = null
): ChatResponse {
    val trace = toolTrace ?: extractToolCallHistory(toolMessages ?: emptyList())
    return ChatResponse(finalContent = finalContent, canContinue = true, toolTrace = trace)
}

// ============================================================================
// 工具确认处理
// ============================================================================

private val CONFIRM_KEYWORDS = setOf("确认", "同意", "确认执行", "是", "yes", "y", "ok", "执行")
private val REJECT_KEYWORDS = setOf("取消", "拒绝", "否", "不执行", "no", "n", "cancel")
private val CONFIRM_EXACT = setOf("是", "y", "ok", "执行")
private val REJECT_EXACT = setOf("否", "n", "cancel")

/**
 * 检测用户输入是否为确认/拒绝关键词。
 * @return "confirm" / "reject" / null
 */
fun detectConfirmation(content: String): String? {
    val stripped = content.trim().lowercase()
    if (stripped.isEmpty()) return null
    val strippedClean = stripped.trimEnd('。', '.', '!', '！', '?', '？', '~')

    val multiConfirm = CONFIRM_KEYWORDS - CONFIRM_EXACT
    val multiReject = REJECT_KEYWORDS - REJECT_EXACT

    var isConfirm = stripped in CONFIRM_KEYWORDS ||
        strippedClean in CONFIRM_KEYWORDS ||
        multiConfirm.any { strippedClean == it }
    if (!isConfirm) isConfirm = strippedClean in CONFIRM_EXACT

    var isReject = stripped in REJECT_KEYWORDS ||
        strippedClean in REJECT_KEYWORDS ||
        multiReject.any { strippedClean == it }
    if (!isReject) isReject = strippedClean in REJECT_EXACT

    if (isConfirm && isReject) return null  // 歧义
    return when {
        isConfirm -> "confirm"
        isReject -> "reject"
        else -> null
    }
}
