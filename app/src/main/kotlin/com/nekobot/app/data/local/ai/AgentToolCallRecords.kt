package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一条工具调用记录：assistant 的 `tool_calls` 与 tool 结果按 call id 配对后的只读视图。
 *
 * [status] 取值见 [STATUS_DONE] / [STATUS_ERROR] / [STATUS_PENDING]；
 * 尚无配对结果的调用（中断或仍在执行）保持 [STATUS_PENDING]，不会丢弃。
 */
data class LocalToolCallRecord(
    val callId: String?,
    val name: String,
    val arguments: String,
    val result: String?,
    val status: String,
    val messageId: String?,
    val createdAt: String?,
    val source: String
) {
    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_ERROR = "error"
        const val STATUS_PENDING = "pending"

        /** 来自已完成轮次、折叠保存在助手消息上的 tool_call_history。 */
        const val SOURCE_HISTORY = "history"

        /** 来自进行中/中断轮次逐条落库的工具轨迹。 */
        const val SOURCE_TRAJECTORY = "trajectory"

        const val UNKNOWN_TOOL_NAME = "unknown"
    }
}

/**
 * 从 assistant/tool 消息序列抽取工具调用记录。
 *
 * 兼容两种历史形态：assistant 携带 `tool_calls`（OpenAI 风格）与其后的 `tool` 结果；
 * 找不到配对调用的 tool 结果也会作为独立记录返回（避免轨迹缺块时整段丢失）。
 */
internal fun toolCallRecordsFromHistory(
    history: List<Map<String, Any>>,
    messageId: String? = null,
    createdAt: String? = null,
    source: String = LocalToolCallRecord.SOURCE_HISTORY
): List<LocalToolCallRecord> {
    val records = mutableListOf<LocalToolCallRecord>()
    val recordIndexByCallId = mutableMapOf<String, Int>()

    history.forEach { message ->
        when (message["role"]) {
            "assistant" -> {
                val calls = message["tool_calls"] as? List<*> ?: return@forEach
                calls.forEach { rawCall ->
                    val call = rawCall as? Map<*, *> ?: return@forEach
                    val function = call["function"] as? Map<*, *>
                    val callId = call["id"]?.toString()?.takeIf { it.isNotBlank() }
                    val name = function?.get("name")?.toString()?.takeIf { it.isNotBlank() }
                        ?: call["name"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: LocalToolCallRecord.UNKNOWN_TOOL_NAME
                    val arguments = function?.get("arguments")?.toString().orEmpty()
                    if (callId != null) recordIndexByCallId[callId] = records.size
                    records += LocalToolCallRecord(
                        callId = callId,
                        name = name,
                        arguments = arguments,
                        result = null,
                        status = LocalToolCallRecord.STATUS_PENDING,
                        messageId = messageId,
                        createdAt = createdAt,
                        source = source
                    )
                }
            }
            "tool" -> {
                val callId = message["tool_call_id"]?.toString()?.takeIf { it.isNotBlank() }
                val content = message["content"]?.toString()
                val status = if (toolResultIsError(content)) {
                    LocalToolCallRecord.STATUS_ERROR
                } else {
                    LocalToolCallRecord.STATUS_DONE
                }
                val index = callId?.let(recordIndexByCallId::get)
                if (index != null) {
                    records[index] = records[index].copy(result = content, status = status)
                } else {
                    records += LocalToolCallRecord(
                        callId = callId,
                        name = message["name"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: LocalToolCallRecord.UNKNOWN_TOOL_NAME,
                        arguments = "",
                        result = content,
                        status = status,
                        messageId = messageId,
                        createdAt = createdAt,
                        source = source
                    )
                }
            }
        }
    }
    return records
}

/** 工具结果 JSON 的 `success == false` 视为失败；无法解析或未带该字段时按成功处理。 */
internal fun toolResultIsError(content: String?): Boolean {
    val text = content?.trim().orEmpty()
    if (!text.startsWith("{")) return false
    val obj = runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull() ?: return false
    val success = obj.get("success")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
    if (!success.isBoolean) return false
    return !success.asBoolean
}
