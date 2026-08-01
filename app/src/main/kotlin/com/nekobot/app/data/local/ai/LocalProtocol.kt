package com.nekobot.app.data.local.ai

/**
 * 本地 AI 协议适配层接口。
 *
 * 对应后端 `nbot/core/protocols/base.py:ModelProtocol`，仅保留本地模式必需的方法。
 * 实现需负责：
 * 1. resolveUrl：把 base_url + model 拼成最终请求 URL
 * 2. buildHeaders：构造鉴权 + Content-Type 头
 * 3. buildPayload：把统一的 messages 转成协议特定 payload
 * 4. parseStreamChunk：解析 SSE 单 chunk，返回文本增量
 * 5. parseNonStreamResponse：解析非流式响应，返回文本、工具调用与 usage
 */
data class LocalModelResponse(
    val content: String = "",
    val usage: Map<String, Int> = emptyMap(),
    val toolCalls: List<Map<String, Any>> = emptyList(),
    val finishReason: String = "",
    val thinkingContent: String = ""
)

/** 流式工具调用增量；由协议层解析，客户端负责按 [index] 聚合。 */
data class LocalToolCallDelta(
    val index: Int,
    val idChunk: String = "",
    val nameChunk: String = "",
    val argumentsChunk: String = "",
    val initialArgumentsJson: String = ""
)

interface LocalProtocol {
    val name: String
    val requiresStreaming: Boolean
        get() = false

    fun resolveUrl(baseUrl: String, model: String, appendBaseUrlPath: Boolean): String

    fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String>

    /**
     * @param messages 统一格式：[{role: "system"|"user"|"assistant", content: "..."}]
     * @param extra 可选参数：temperature / max_tokens / top_p / tools
     */
    fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any>

    /**
     * 解析流式 SSE chunk（已去掉 `data: ` 前缀的 JSON 字符串）。
     * @return 文本增量，若无内容返回 null
     */
    fun parseStreamChunk(chunkJson: String): String?

    /** 解析模型推理/思考文本增量；协议不支持时返回 null。 */
    fun parseStreamThinkingChunk(chunkJson: String): String? = null

    /** 解析流式工具调用的 id/name/arguments 增量。 */
    fun parseStreamToolCallDeltas(chunkJson: String): List<LocalToolCallDelta> = emptyList()

    /** 解析流式结束原因，如 stop/tool_calls/content_filter。 */
    fun parseStreamFinishReason(chunkJson: String): String? = null

    /**
     * 解析流式 SSE chunk 中的 usage 字段（OpenAI 在最后 chunk 携带，Anthropic 在 message_delta 携带）。
     * @return Triple(prompt, completion, total)，无 usage 返回 null
     */
    fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>?

    fun parseStreamFinalResponse(chunkJson: String): LocalModelResponse? = null

    fun parseStreamError(chunkJson: String): String? = null

    /**
     * 解析非流式响应 JSON。
     * @return 统一模型响应，包含 content / toolCalls / finishReason / usage
     */
    fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse
}

/** 协议注册表。 */
object LocalProtocols {
    private val registry: Map<String, LocalProtocol> = mapOf(
        OpenAIChatProtocol.name to OpenAIChatProtocol,
        OpenAIResponsesProtocol.name to OpenAIResponsesProtocol,
        AnthropicMessagesProtocol.name to AnthropicMessagesProtocol
    )

    fun get(name: String): LocalProtocol =
        registry[name] ?: OpenAIChatProtocol

    fun names(): List<String> = registry.keys.toList()
}
