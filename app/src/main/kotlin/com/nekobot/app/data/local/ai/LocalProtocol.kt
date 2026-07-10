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
 * 5. parseNonStreamResponse：解析非流式响应，返回完整文本 + usage
 */
interface LocalProtocol {
    val name: String

    fun resolveUrl(baseUrl: String, model: String, appendBaseUrlPath: Boolean): String

    fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String>

    /**
     * @param messages 统一格式：[{role: "system"|"user"|"assistant", content: "..."}]
     * @param extra 可选参数：temperature / max_tokens / top_p
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

    /**
     * 解析流式 SSE chunk 中的 usage 字段（OpenAI 在最后 chunk 携带，Anthropic 在 message_delta 携带）。
     * @return Triple(prompt, completion, total)，无 usage 返回 null
     */
    fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>?

    /**
     * 解析非流式响应 JSON。
     * @return Pair(content, usage) usage = {prompt, completion, total}
     */
    fun parseNonStreamResponse(data: Map<String, Any>): Pair<String, Map<String, Int>>
}

/** 协议注册表。 */
object LocalProtocols {
    private val registry: Map<String, LocalProtocol> = mapOf(
        OpenAIChatProtocol.name to OpenAIChatProtocol,
        AnthropicMessagesProtocol.name to AnthropicMessagesProtocol
    )

    fun get(name: String): LocalProtocol =
        registry[name] ?: OpenAIChatProtocol

    fun names(): List<String> = registry.keys.toList()
}
