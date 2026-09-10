package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * 同一批次内允许并行执行的只读工具上限。
 *
 * 模型经常一次请求多个互不依赖的读取（多个文件、多个数据库列表）；串行执行会把
 * 一次模型调用的耗时放大成数倍。上限避免一次打满执行器与网络。
 */
private const val MAX_PARALLEL_TOOL_CALLS = 4

/**
 * 可并行的“无副作用”工具白名单（严格只读）。
 *
 * 保守起见只列确定不产生副作用的读取类工具：任何写文件、改状态、发消息、执行命令、
 * 操作界面或调用外部系统的工具都不在内（包括 MCP 工具，其副作用未知）。
 */
internal val parallelSafeToolIds = setOf(
    "file_read",
    "workspace_read_file",
    "workspace_file_info",
    "workspace_list_files",
    "workspace_parse_file",
    "grep",
    "glob",
    "http_get",
    "web_fetch",
    "search_web",
    "get_weather",
    "get_date_time",
    "read_image",
    "skill_list",
    "skill_view",
    "skill_read",
    "skill_get_info",
    "get_session_thinking_history",
    "agent_memory_read",
    "android_device_info",
    "android_battery_status",
    "android_clipboard_read",
    "android_accessibility_status",
    "android_list_apps"
)

/** 判断某个工具是否可以在同一批次内与其他只读工具并行执行。 */
internal fun isParallelSafeTool(toolName: String): Boolean {
    if (toolName.isBlank()) return false
    if (toolName in parallelSafeToolIds) return true
    // 数据库只读前缀（db_list_* / db_get_* / db_token_*）由命名约定保证只读。
    return toolName.startsWith("db_list_") ||
        toolName.startsWith("db_get_") ||
        toolName.startsWith("db_token_")
}

/**
 * 从 [from] 开始取出一个可并行执行的批次：
 * 连续多个只读工具（最多 [MAX_PARALLEL_TOOL_CALLS] 个）构成一批，其余情况只返回单个调用。
 */
internal fun nextParallelToolBatch(
    toolCalls: List<Map<String, Any>>,
    from: Int
): List<Map<String, Any>> {
    if (from !in toolCalls.indices) return emptyList()
    if (!isParallelSafeTool((toolCalls[from]["name"] as? String).orEmpty())) {
        return listOf(toolCalls[from])
    }
    val batch = mutableListOf<Map<String, Any>>()
    var index = from
    while (index < toolCalls.size && batch.size < MAX_PARALLEL_TOOL_CALLS) {
        val call = toolCalls[index]
        if (!isParallelSafeTool((call["name"] as? String).orEmpty())) break
        batch.add(call)
        index++
    }
    return batch
}

/** 并行批次内单次工具执行的结果；[exitContent] 非空表示工具要求提前结束本轮（ToolLoopExit）。 */
private data class ToolCallOutcome(
    val toolCall: Map<String, Any>,
    val result: Map<String, Any>,
    val exitContent: String?
)

/**
 * Agent 服务：工具循环 + 上下文准备，对应原仓库 nbot/core/agent_service.py。
 *
 * 提供工具调用循环（model ↔ tool 多轮交互）和消息上下文预处理。
 */

private val agentGson = Gson()
private val toolCallHistoryType =
    object : TypeToken<List<Map<String, Any>>>() {}.type

private val requiredToolArguments = mapOf(
    "browser_use" to setOf("action"),
    "plugin_use" to setOf("action"),
    "android_step" to setOf("action"),
    "grep" to setOf("pattern"),
    "glob" to setOf("pattern"),
    "web_fetch" to setOf("url"),
    "shell_job" to setOf("action"),
    "subagent_get" to setOf("task_id"),
    "subagent_kill" to setOf("task_id"),
    "exec_command" to setOf("command"),
    "file_read" to setOf("path"),
    "file_write" to setOf("path", "content"),
    "file_edit" to setOf("path", "old_string", "new_string"),
    "read_image" to setOf("path"),
    "workspace_create_file" to setOf("path", "content"),
    "workspace_read_file" to setOf("path"),
    "workspace_edit_file" to setOf("path", "old_string", "new_string"),
    "workspace_delete_file" to setOf("path"),
    "workspace_send_file" to setOf("path")
)

/**
 * 兼容部分模型把 arguments 返回成 JSON 字符串，或漏掉末尾右花括号的情况。
 * 无法修复时保留空参数，由执行前校验生成可读错误，而不是让整个工具循环崩溃。
 */
internal fun normalizeAgentToolCall(toolCall: Map<String, Any>): Map<String, Any> {
    val normalized = toolCall.toMutableMap()
    normalized["name"] = toolCall["name"]?.toString()?.trim().orEmpty()
    val rawArguments = toolCall["arguments"]
    val arguments: Map<String, Any> = when (rawArguments) {
        is Map<*, *> -> rawArguments.entries
            .filter { it.key != null }
            .associate { it.key.toString() to (it.value ?: "") }
        is String -> parseToolArguments(rawArguments)
        else -> emptyMap()
    }
    normalized["arguments"] = arguments
    return normalized
}

@Suppress("UNCHECKED_CAST")
private fun parseToolArguments(raw: String): Map<String, Any> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return emptyMap()
    val candidates = buildList {
        add(trimmed)
        if (trimmed.startsWith("{")) {
            val missingClosers = (trimmed.count { it == '{' } - trimmed.count { it == '}' })
                .coerceIn(0, 8)
            if (missingClosers > 0) add(trimmed + "}".repeat(missingClosers))
        }
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        runCatching {
            agentGson.fromJson(candidate, Map::class.java) as? Map<String, Any>
        }.getOrNull()
    }.orEmpty()
}

internal fun validateAgentToolCall(toolCall: Map<String, Any>): String? {
    val name = toolCall["name"]?.toString()?.trim().orEmpty()
    if (name.isBlank()) return "工具调用缺少 name"
    @Suppress("UNCHECKED_CAST")
    val arguments = toolCall["arguments"] as? Map<String, Any> ?: emptyMap()
    val missing = requiredToolArguments[name].orEmpty().filter { key ->
        val value = if (key == "path") {
            arguments["path"] ?: arguments["filename"] ?: arguments["file_path"]
        } else {
            arguments[key]
        }
        value == null || (value is String && value.isBlank())
    }
    return if (missing.isEmpty()) null else "$name 缺少必填参数: ${missing.joinToString()}"
}

private class AgentToolLoopGuard(
    private val maxIdenticalCalls: Int = 5
) {
    private var previousSignature = ""
    private var identicalCount = 0

    fun inspect(toolCall: Map<String, Any>): String? {
        val signature = agentGson.toJson(
            mapOf(
                "name" to toolCall["name"],
                "arguments" to toolCall["arguments"]
            )
        )
        identicalCount = if (signature == previousSignature) identicalCount + 1 else 1
        previousSignature = signature
        return if (identicalCount >= maxIdenticalCalls) {
            "检测到连续 $identicalCount 次完全相同的工具调用，已停止以避免无进展循环。"
        } else null
    }
}

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
    val onToolResult: ((Map<String, Any>, Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>?)? = null,
    /** 一整批 tool_calls 都写入对应 tool 结果后的安全检查点。 */
    val onCheckpoint: ((Int, List<Map<String, Any>>) -> Unit)? = null
)

/** 工具循环结果 */
data class ToolLoopResult(
    val finalContent: String = "",
    val finalReasoning: String = "",
    val toolMessages: List<Map<String, Any>> = emptyList(),
    val stopped: Boolean = false,
    val iterations: Int = 0,
    val consecutiveErrors: Int = 0,
    val usage: Map<String, Any> = emptyMap(),
    val modelId: String = "",
    val modelName: String = "",
    /** 实际模型标识（如 gpt-4o），用于排行榜按模型聚合 */
    val modelActualName: String = "",
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
    val toolExecutor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>,
    val toolCallHistory: List<Map<String, Any>>? = null,
    val maxIterations: Int = 150,
    val maxConsecutiveErrors: Int = 3,
    val hooks: ToolLoopHooks? = null,
    val shouldStop: () -> Boolean = { false },
    /**
     * 每轮模型调用前取出的待注入用户消息（Agent 排队“立即发送”场景）。
     * 返回的内容会按顺序以 user 消息插入下一轮模型上下文。
     */
    val pendingUserMessages: () -> List<String> = { emptyList() }
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
 * 单条消息占用体积：content（字符串或多模态分块）+ 推理内容 + tool_calls 参数。
 *
 * 只统计 content 字符串会显著低估体积——工具调用参数与多模态分块都不在 content 里，
 * 会让裁剪阈值失真。
 */
internal fun messageSizeChars(message: Map<String, Any>): Int {
    var size = when (val content = message["content"]) {
        is String -> content.length
        is List<*> -> content.sumOf { part ->
            when (part) {
                is String -> part.length
                is Map<*, *> -> (part["text"] as? String)?.length ?: 0
                else -> 0
            }
        }
        else -> 0
    }
    (message["reasoning_content"] as? String)?.let { size += it.length }
    (message["tool_calls"] as? List<*>)?.let { size += agentGson.toJson(it).length }
    return size
}

/**
 * 把历史消息切成可整体丢弃的块。
 *
 * 带 tool_calls 的 assistant 消息与其后续的 tool 结果必须同生共死：只保留 tool 结果会让
 * OpenAI/Anthropic 直接拒绝请求（缺少对应的 tool_call），只保留 tool_calls 则缺少工具响应。
 * 因此裁剪必须以块为单位，而不能按单条消息删除。
 */
internal fun groupMessageBlocks(messages: List<Map<String, Any>>): List<List<Map<String, Any>>> {
    val blocks = mutableListOf<List<Map<String, Any>>>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        val role = message["role"] as? String ?: ""
        val hasToolCalls = (message["tool_calls"] as? List<*>)?.isNotEmpty() == true
        when {
            role == "assistant" && hasToolCalls -> {
                val block = mutableListOf(message)
                var cursor = index + 1
                while (cursor < messages.size && (messages[cursor]["role"] as? String) == "tool") {
                    block.add(messages[cursor])
                    cursor++
                }
                blocks.add(block)
                index = cursor
            }
            // 没有前置 assistant 的孤儿 tool 消息：并入上一块一起丢弃，避免裁剪后以 tool 开头。
            role == "tool" -> {
                if (blocks.isEmpty()) {
                    blocks.add(listOf(message))
                } else {
                    blocks[blocks.lastIndex] = blocks.last() + message
                }
                index++
            }
            else -> {
                blocks.add(listOf(message))
                index++
            }
        }
    }
    return blocks
}

/**
 * 裁剪消息列表以控制总体积（字符数近似）。
 *
 * 规则：
 * - system 消息始终保留；
 * - 从最早的块开始整体丢弃，块内保持工具调用/结果配对完整；
 * - 至少保留最后一块（当前用户消息），避免把用户问题整段裁掉；
 * - 裁剪后不会以 tool 消息开头，保证发出去的消息序列对协议合法。
 */
fun trimMessages(messages: List<Map<String, Any>>, maxTotalChars: Int = 30000): List<Map<String, Any>> {
    if (messages.isEmpty()) return messages
    val systemMessage = messages.first().takeIf { (it["role"] as? String) == "system" }
    val body = if (systemMessage != null) messages.drop(1) else messages
    if (body.isEmpty()) return messages

    var currentTotal = messages.sumOf(::messageSizeChars)
    if (currentTotal <= maxTotalChars) return messages

    val blocks = groupMessageBlocks(body)
    var firstKept = 0
    while (firstKept < blocks.size - 1 && currentTotal > maxTotalChars) {
        currentTotal -= blocks[firstKept].sumOf(::messageSizeChars)
        firstKept++
    }

    val kept = blocks.drop(firstKept)
        .flatten()
        .dropWhile { (it["role"] as? String) == "tool" }
    return if (systemMessage != null) listOf(systemMessage) + kept else kept
}

/** 循环内裁剪后工具结果正文的最小保留长度（字符）；短于此长度的结果不值得裁剪。 */
private const val IN_LOOP_TOOL_RESULT_MIN_KEEP = 400

/** 循环内裁剪保留的最近消息条数（当前工作进度通常在这几条里）。 */
private const val IN_LOOP_RECENT_MESSAGES_KEPT = 6

/**
 * 循环内上下文管理：按 token 预算裁剪历史工具结果。
 *
 * 与轮次之间的自动压缩互补——一次长任务往往在"一轮"内就堆满上下文：
 * 每个工具结果都可能上万字符，几十次调用后必然超出模型窗口，而轮次之间的压缩根本来不及触发。
 *
 * 只裁剪 `role == "tool"` 的消息正文（保留 role / tool_call_id / name 等结构）：
 * 直接删除 tool 消息会让部分模型报 "tool message without preceding tool_calls"。
 * 裁剪从最旧开始，最近 [IN_LOOP_RECENT_MESSAGES_KEPT] 条消息始终保留。
 *
 * @return 本次裁剪的说明文本；未发生裁剪时返回 null。
 */
internal fun manageInLoopContextBudget(
    toolMessages: MutableList<MutableMap<String, Any>>,
    budgetTokens: Int,
    outputReserveRatio: Double = 0.2
): String? {
    if (budgetTokens <= 0) return null
    val usable = (budgetTokens * (1.0 - outputReserveRatio.coerceIn(0.0, 0.6))).toInt()
    if (usable <= 0) return null
    val before = estimateLocalMessagesTokens(toolMessages)
    if (before <= usable) return null

    var elided = 0
    val elisionLimit = (toolMessages.size - IN_LOOP_RECENT_MESSAGES_KEPT).coerceAtLeast(0)
    for (index in 0 until elisionLimit) {
        if (estimateLocalMessagesTokens(toolMessages) <= usable) break
        val message = toolMessages[index]
        if (!(message["role"] as? String).equals("tool", ignoreCase = true)) continue
        val content = message["content"] as? String ?: continue
        if (content.length <= IN_LOOP_TOOL_RESULT_MIN_KEEP) continue
        message["content"] = "[历史工具结果已因上下文预算裁剪，原始长度 ${content.length} 字符。" +
            "如果仍然需要这些信息，请重新调用对应工具获取。]"
        elided++
    }

    if (elided == 0) {
        // 没有可裁剪的历史工具结果（例如几个巨大的结果都落在最近消息里）：
        // 用整体硬裁剪兜底，保证本轮请求不会直接超出模型窗口。
        val allowedChars = (usable * 2).coerceAtLeast(4_000)
        val hardTrimmed = trimMessages(toolMessages, maxTotalChars = allowedChars)
        val removed = toolMessages.size - hardTrimmed.size
        if (removed <= 0) return null
        toolMessages.clear()
        toolMessages.addAll(hardTrimmed.map { it.toMutableMap() })
        return "硬裁剪移除 $removed 条最旧消息（预算 $usable token）"
    }

    val after = estimateLocalMessagesTokens(toolMessages)
    return "裁剪 $elided 条工具结果（$before → $after token，预算 $usable）"
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
        val restored = mutableListOf<Map<String, Any>>()
        @Suppress("UNCHECKED_CAST")
        if (hidden is List<*>) {
            for (hiddenMsg in hidden) {
                if (hiddenMsg !is Map<*, *>) continue
                val role = hiddenMsg["role"] as? String ?: ""
                if (role !in listOf("assistant", "tool")) continue
                @Suppress("UNCHECKED_CAST")
                restored.add((hiddenMsg as Map<String, Any>).toMap())
            }
        }
        // tool_call_history 记录的是生成这条公开助手回复之前的模型/工具交互，
        // 必须按真实时间顺序放在公开回复前，避免出现“最终回复之后又调用工具”的错序上下文。
        expanded.addAll(restored)
        expanded.add(message.toMap())
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

/** 将持久化的工具调用历史 JSON 恢复为模型可直接使用的 assistant/tool 消息。 */
fun decodeToolCallHistory(json: String?): List<Map<String, Any>> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        agentGson.fromJson<List<Map<String, Any>>>(json, toolCallHistoryType)
            .orEmpty()
            .filter { it["role"] in listOf("assistant", "tool") }
            .map { it.toMap() }
    }.getOrDefault(emptyList())
}

/** 将本轮工具调用历史序列化，供后续轮次自动恢复。 */
fun encodeToolCallHistory(history: List<Map<String, Any>>?): String? {
    val normalized = history.orEmpty()
        .filter { it["role"] in listOf("assistant", "tool") }
        .map { it.toMap() }
    return normalized.takeIf { it.isNotEmpty() }?.let(agentGson::toJson)
}

/** 提取工具调用历史（role 为 assistant 或 tool 的消息） */
fun extractToolCallHistory(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    return messages.filter { it["role"] in listOf("assistant", "tool") }.map { it.toMap() }
}

/**
 * 只提取本轮新产生的工具调用历史。
 *
 * [initialMessageCount] 之前的消息属于既有上下文，不能再次保存到当前回复中，
 * 否则每轮都会重复嵌套全部历史并导致上下文指数膨胀。
 */
fun extractCurrentTurnToolCallHistory(
    messages: List<Map<String, Any>>,
    initialMessageCount: Int
): List<Map<String, Any>> {
    return messages
        .drop(initialMessageCount.coerceIn(0, messages.size))
        .filter { it["role"] in listOf("assistant", "tool") }
        .map { it.toMap() }
}

// ============================================================================
// 响应内容处理
// ============================================================================

/**
 * 构建工具消息 content。
 *
 * 工具结果默认序列化为 JSON 文本。若结果含 _image_urls（data URI 列表，
 * 由 android_screenshot / android_step 在支持视觉的模型下注入），则返回
 * 多模态 content 数组：[文本 JSON, image_url parts]，让视觉模型直接看到截图，
 * 无需再调用 understand_image。协议层（Gemini/Anthropic/OpenAI Chat）已支持
 * 解析该数组；OpenAI Responses 协议只取文本部分（图片降级为文本提示）。
 */
internal fun buildToolMessageContent(toolResult: Map<String, Any>): Any {
    val images = (toolResult["_image_urls"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val textJson = agentGson.toJson(toolResult.filterKeys { it != "_image_urls" })
    if (images.isEmpty()) return textJson
    return buildList {
        add(mapOf("type" to "text", "text" to textJson))
        images.forEach { url ->
            add(mapOf("type" to "image_url", "image_url" to mapOf("url" to url)))
        }
    }
}

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
suspend fun runToolCallLoop(
    initialMessages: List<Map<String, Any>>,
    modelCall: ModelCall,
    toolExecutor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>,
    maxIterations: Int = 150,
    maxConsecutiveErrors: Int = 3,
    hooks: ToolLoopHooks? = null,
    shouldStop: () -> Boolean = { false },
    pendingUserMessages: () -> List<String> = { emptyList() }
): ToolLoopResult {
    val toolMessages = initialMessages.map { it.toMutableMap() }.toMutableList()
    var finalContent = ""
    var finalReasoning = ""
    var consecutiveErrors = 0
    val usageTotal = mutableMapOf<String, Any>()
    var currentModelId = ""
    var currentModelName = ""
    var currentModelActualName = ""
    val allFailoverEvents = mutableListOf<Map<String, Any>>()
    val loopGuard = AgentToolLoopGuard()

    fun result(
        finalContentArg: String? = null,
        toolMessagesArg: List<Map<String, Any>>? = null,
        stopped: Boolean = false,
        iterations: Int = 0,
        consecutiveErrorsArg: Int? = null
    ): ToolLoopResult = ToolLoopResult(
        finalContent = finalContentArg ?: finalContent,
        finalReasoning = finalReasoning,
        toolMessages = toolMessagesArg ?: toolMessages.map { it.toMap() },
        stopped = stopped,
        iterations = iterations,
        consecutiveErrors = consecutiveErrorsArg ?: consecutiveErrors,
        usage = usageTotal.toMap(),
        modelId = currentModelId,
        modelName = currentModelName,
        modelActualName = currentModelActualName,
        failoverEvents = allFailoverEvents.toList()
    )

    for (iteration in 0 until maxIterations) {
        if (shouldStop()) {
            return result(stopped = true, iterations = iteration)
        }

        // 注入排队用户消息：在下一轮模型调用（即下一次工具决策）前插入上下文，
        // 使用户的加急消息先于后续工具调用被模型看到。
        val injectedUserMessages = pendingUserMessages()
            .filter(String::isNotBlank)
        if (injectedUserMessages.isNotEmpty()) {
            for (content in injectedUserMessages) {
                toolMessages.add(mutableMapOf("role" to "user", "content" to content))
            }
        }

        hooks?.onIterationStart?.invoke(iteration, toolMessages.map { it.toMap() })

        val response = try {
            modelCall(toolMessages.map { it.toMap() }, shouldStop())
        } catch (e: ToolLoopExit) {
            return result(finalContentArg = e.finalContent, iterations = iteration + 1)
        } catch (e: Exception) {
            if (shouldStop()) {
                return result(stopped = true, iterations = iteration + 1)
            }
            throw ToolLoopModelError(e, iteration)
        }
        if (shouldStop()) {
            return result(stopped = true, iterations = iteration + 1)
        }

        // 提取模型追踪信息
        (response["_model_id"] as? String)?.let { currentModelId = it }
        (response["_model_name"] as? String)?.let { currentModelName = it }
        (response["_model_actual_name"] as? String)?.let { currentModelActualName = it }
        @Suppress("UNCHECKED_CAST")
        (response["_failover_events"] as? List<Map<String, Any>>)?.let { allFailoverEvents.addAll(it) }

        mergeUsage(usageTotal, response["usage"])

        @Suppress("UNCHECKED_CAST")
        val toolCalls = (response["tool_calls"] as? List<Map<String, Any>>)
            .orEmpty()
            .map(::normalizeAgentToolCall)
        val responseReasoning = (response["reasoning_content"] as? String)
            ?: (response["thinking_content"] as? String)
            ?: ""
        if (responseReasoning.isNotBlank()) finalReasoning = responseReasoning
        val thinkingContent = responseReasoning.ifBlank { (response["content"] as? String).orEmpty() }

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
            toolMessages.add(buildMap<String, Any> {
                put("role", "assistant")
                put("content", response["content"] ?: "")
                put("tool_calls", toolCallEntries)
                if (responseReasoning.isNotBlank()) {
                    // DeepSeek 等提供商要求后续工具轮次原样带回 reasoning_content。
                    put("reasoning_content", responseReasoning)
                    // Anthropic 扩展思考还要求带回思考块签名（signature），
                    // 缺失会让下一轮带工具结果的请求被服务端判为非法。
                    (response["thinking_signature"] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("reasoning_signature", it) }
                }
            }.toMutableMap())

            // 执行每个工具调用：连续的只读工具并行执行（结果顺序与副作用顺序保持不变）
            var loopAbortMessage: String? = null
            var callIndex = 0
            while (callIndex < toolCalls.size) {
                if (shouldStop()) {
                    return result(stopped = true, iterations = iteration + 1)
                }
                val batch = nextParallelToolBatch(toolCalls, callIndex)
                if (batch.isEmpty()) break

                // 1) 预检串行执行：守卫与参数校验的顺序必须稳定，越界调用直接返回错误结果。
                val prepared = batch.map { toolCall ->
                    hooks?.onToolStart?.invoke(toolCall, thinkingContent, iteration, toolMessages.map { it.toMap() })
                    val loopGuardMessage = loopAbortMessage ?: loopGuard.inspect(toolCall)
                    if (loopAbortMessage == null && loopGuardMessage != null) {
                        loopAbortMessage = loopGuardMessage
                    }
                    val validationMessage = validateAgentToolCall(toolCall)
                    Triple(toolCall, loopGuardMessage, validationMessage)
                }

                // 2) 执行：只读工具并行，其余（含单个调用）串行。
                val snapshot = toolMessages.map { it.toMap() }
                val outcomes: List<ToolCallOutcome> = if (batch.size > 1) {
                    coroutineScope {
                        prepared.map { (toolCall, loopGuardMessage, validationMessage) ->
                            async(Dispatchers.IO) {
                                if (loopGuardMessage != null || validationMessage != null) {
                                    return@async ToolCallOutcome(
                                        toolCall,
                                        mapOf(
                                            "success" to false,
                                            "error" to (loopGuardMessage ?: validationMessage.orEmpty())
                                        ),
                                        null
                                    )
                                }
                                var exitContent: String? = null
                                val value = try {
                                    toolExecutor(toolCall, thinkingContent, iteration, snapshot)
                                } catch (e: ToolLoopExit) {
                                    exitContent = e.finalContent
                                    emptyMap()
                                }
                                ToolCallOutcome(toolCall, value, exitContent)
                            }
                        }.map { it.await() }
                    }
                } else {
                    prepared.map { (toolCall, loopGuardMessage, validationMessage) ->
                        if (loopGuardMessage != null || validationMessage != null) {
                            ToolCallOutcome(
                                toolCall,
                                mapOf(
                                    "success" to false,
                                    "error" to (loopGuardMessage ?: validationMessage.orEmpty())
                                ),
                                null
                            )
                        } else {
                            var exitContent: String? = null
                            val value = try {
                                toolExecutor(toolCall, thinkingContent, iteration, snapshot)
                            } catch (e: ToolLoopExit) {
                                exitContent = e.finalContent
                                emptyMap()
                            }
                            ToolCallOutcome(toolCall, value, exitContent)
                        }
                    }
                }

                // 3) 结果处理串行执行：保证 tool 消息顺序与模型给出的 tool_calls 顺序一致。
                for (outcome in outcomes) {
                    if (outcome.exitContent != null) {
                        return result(finalContentArg = outcome.exitContent, iterations = iteration + 1)
                    }
                    if (shouldStop()) {
                        return result(stopped = true, iterations = iteration + 1)
                    }
                    val toolCall = outcome.toolCall
                    val toolResult = outcome.result

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
                            "content" to buildToolMessageContent(toolResult)
                        )
                    }

                    toolMessages.add(toolHistoryMessage.toMutableMap())
                }

                callIndex += batch.size
            }
            hooks?.onCheckpoint?.invoke(iteration, toolMessages.map { it.toMap() })
            loopAbortMessage?.let { message ->
                return result(finalContentArg = message, iterations = iteration + 1)
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
suspend fun runToolLoopSession(session: ToolLoopSession): ToolExecutionResult {
    val preparedMessages = applyToolCallHistory(session.initialMessages, session.toolCallHistory)
    val loopResult = runToolCallLoop(
        preparedMessages,
        session.modelCall,
        session.toolExecutor,
        maxIterations = session.maxIterations,
        maxConsecutiveErrors = session.maxConsecutiveErrors,
        hooks = session.hooks,
        shouldStop = session.shouldStop,
        pendingUserMessages = session.pendingUserMessages
    )
    return ToolExecutionResult(loopResult = loopResult, preparedMessages = preparedMessages)
}

/**
 * 从循环结果中解析最终内容。
 *
 * 本地 send_message 工具本身不会额外创建聊天气泡；部分模型会把最终答复只放进该工具，
 * 随后的空 assistant 结束包会让 loopResult.finalContent 为空。此时必须把最后一次成功发送
 * 的内容提升为公开回复，否则 UI、Room 与自动命名都会收到空字符串。
 */
fun resolveLoopFinalContent(loopResult: ToolLoopResult, defaultContent: String = ""): String {
    if (loopResult.finalContent.isNotEmpty()) return loopResult.finalContent
    loopResult.toolMessages.asReversed().firstNotNullOfOrNull { message ->
        if (message["role"] != "tool" || message["name"] != "send_message") {
            return@firstNotNullOfOrNull null
        }
        val rawContent = message["content"] as? String ?: return@firstNotNullOfOrNull null
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val result = agentGson.fromJson(rawContent, Map::class.java) as? Map<String, Any>
            (result?.get("_send_message") as? String)?.takeIf(String::isNotBlank)
        }.getOrNull()
    }?.let { return it }
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
