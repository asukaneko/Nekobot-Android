package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalLogger
import java.io.BufferedReader

private const val TAG = "LocalSse"

/** 日志里最多保留的负载长度，避免把超大响应整体写进日志。 */
private const val MAX_LOGGED_PAYLOAD_CHARS = 500

private const val DONE_SENTINEL = "[DONE]"

/**
 * SSE 事件读取器。
 *
 * 按 SSE 规范把同一个事件的连续 `data:` 行聚合为一个负载（规范要求用 `\n` 连接多行 data），
 * 空行才是事件边界。同时兼容不符合规范、但现实中常见的两类服务端行为：
 *
 * 1. 每个事件只有一行 `data:`，事件之间**没有**空行（多数 OpenAI 兼容网关）；
 * 2. 一个事件的 JSON 被拆到多行 `data:`（规范实现，如部分 Anthropic/代理端点）。
 *
 * 判定方式：新的 `data:` 行到达时，若缓冲区内容已是完整 JSON，先派发上一个事件；
 * 若缓冲区既不是完整 JSON、也不像 JSON 的开头（例如网关塞进来的 HTML 错误页），
 * 先丢弃这段垃圾，避免它污染紧随其后的合法事件；否则继续拼接。
 *
 * 无法解析或中途截断的负载都会写日志，不再静默吞掉——静默丢弃只会表现为“流莫名中断”。
 *
 * @param onEvent 处理一个事件负载；返回 false 表示调用方要求停止读取（例如收到 `[DONE]`）。
 */
internal suspend fun readSseEvents(
    reader: BufferedReader,
    onEvent: suspend (String) -> Boolean
) {
    val buffer = StringBuilder()

    suspend fun dispatch(): Boolean {
        if (buffer.isEmpty()) return true
        val payload = buffer.toString()
        buffer.setLength(0)
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed == DONE_SENTINEL) return onEvent(trimmed)
        if (!looksLikeCompleteJson(trimmed) || !isValidJson(trimmed)) {
            // 区分“网关返回了非协议内容”和“事件被中途截断”，两者排查方向不同。
            val reason = if (looksLikeJson(trimmed)) "SSE 事件不完整（流可能被中断）" else "无法解析的 SSE 负载"
            LocalLogger.w(TAG, "丢弃$reason: ${trimmed.take(MAX_LOGGED_PAYLOAD_CHARS)}")
            return true
        }
        return onEvent(trimmed)
    }

    while (true) {
        val line = reader.readLine() ?: break
        when {
            // 空行是事件边界
            line.isEmpty() -> if (!dispatch()) return
            // SSE 注释 / 心跳（部分网关用 ": ping" 保活）
            line.startsWith(":") -> Unit
            // 事件名由负载内的 type 字段承载，协议解析器不需要它
            line.startsWith("event:") -> Unit
            // id: / retry: 等其他字段与模型协议无关
            !line.startsWith("data:") -> Unit
            else -> {
                val data = line.removePrefix("data:")
                    .let { if (it.startsWith(" ")) it.substring(1) else it }
                if (buffer.isNotEmpty() && looksLikeCompleteJson(buffer.toString().trim())) {
                    if (!dispatch()) return
                } else if (buffer.isNotEmpty() && !looksLikeJson(buffer.toString().trim())) {
                    // 缓冲区已经不可能拼成 JSON：丢弃，保证后续合法事件能被单独识别。
                    dispatch()
                }
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(data)
            }
        }
    }

    dispatch()
}

private fun looksLikeJson(payload: String): Boolean {
    val first = payload.firstOrNull() ?: return false
    return first == '{' || first == '['
}

/** 用括号配平粗判是否为完整 JSON，字符串与转义按 JSON 规则处理。 */
private fun looksLikeCompleteJson(payload: String): Boolean {
    if (!looksLikeJson(payload)) return false
    var depth = 0
    var inString = false
    var escaped = false
    for (ch in payload) {
        if (escaped) {
            escaped = false
            continue
        }
        when {
            ch == '\\' && inString -> escaped = true
            ch == '"' -> inString = !inString
            inString -> Unit
            ch == '{' || ch == '[' -> depth++
            ch == '}' || ch == ']' -> depth--
        }
    }
    return !inString && depth <= 0
}

private fun isValidJson(payload: String): Boolean = runCatching {
    JsonParser.parseString(payload)
    true
}.getOrDefault(false)
