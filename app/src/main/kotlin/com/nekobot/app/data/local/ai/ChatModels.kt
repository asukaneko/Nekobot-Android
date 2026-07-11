package com.nekobot.app.data.local.ai

import java.util.UUID

/**
 * 聊天请求，对应原仓库 nbot/core/chat_models.py:ChatRequest。
 *
 * 所有频道（web/qq/local）的输入统一转换为此结构，AI 管线只依赖此结构。
 */
data class ChatRequest(
    /** 频道标识：web / qq / local */
    val channel: String,
    /** 会话 ID（用于历史/状态隔离） */
    val conversationId: String,
    /** 用户 ID */
    val userId: String? = null,
    /** 消息文本 */
    val content: String = "",
    /** 发送者显示名 */
    val sender: String = "",
    /** 附件列表，每项为 Map（含 url/type 等） */
    val attachments: List<Map<String, Any>> = emptyList(),
    /** 父消息 ID（引用回复） */
    val parentMessageId: String? = null,
    /** 扩展元数据：group_id / session_mode / plot_mode / character_id / custom_prompts 等 */
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        /** Web 频道工厂方法 */
        fun forWeb(
            sessionId: String,
            content: String,
            sender: String,
            attachments: List<Map<String, Any>>? = null,
            parentMessageId: String? = null,
            metadata: Map<String, Any>? = null
        ): ChatRequest = ChatRequest(
            channel = "web",
            conversationId = sessionId,
            content = content,
            sender = sender,
            attachments = attachments?.toList() ?: emptyList(),
            parentMessageId = parentMessageId,
            metadata = metadata?.toMap() ?: emptyMap()
        )

        /** 本地模式工厂方法 */
        fun forLocal(
            sessionId: String,
            content: String,
            sender: String = "user",
            userId: String? = null,
            attachments: List<Map<String, Any>>? = null,
            metadata: Map<String, Any>? = null
        ): ChatRequest = ChatRequest(
            channel = "local",
            conversationId = sessionId,
            userId = userId,
            content = content,
            sender = sender,
            attachments = attachments?.toList() ?: emptyList(),
            metadata = metadata?.toMap() ?: emptyMap()
        )
    }
}

/**
 * 聊天响应，对应原仓库 nbot/core/chat_models.py:ChatResponse。
 *
 * 流式响应通过 RealtimeEvent 回调实时推送；流式完成后此对象持有最终合并结果。
 */
data class ChatResponse(
    /** 最终回复文本 */
    val finalContent: String = "",
    /** 完整助手消息对象（含 id/role/content/timestamp/sender） */
    val assistantMessage: Map<String, Any>? = null,
    /** 工具调用历史 */
    val toolTrace: List<Map<String, Any>> = emptyList(),
    /** 是否可"继续"执行（工具循环中断恢复） */
    val canContinue: Boolean = false,
    /** token 用量 */
    val usage: Map<String, Any> = emptyMap(),
    /** 错误信息 */
    val error: String? = null,
    /** 扩展元数据 */
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * 将 finalContent 封装为标准 assistant 消息字典。
     * 若 assistantMessage 已存在则直接返回。
     */
    fun toAssistantMessage(sender: String = "AI"): Map<String, Any> {
        assistantMessage?.let { return it }

        val content = error ?: finalContent
        val msg = mutableMapOf<String, Any>(
            "id" to UUID.randomUUID().toString(),
            "role" to "assistant",
            "content" to content,
            "timestamp" to java.time.Instant.now().toString(),
            "sender" to sender
        )
        if (error != null) msg["error"] = true
        if (canContinue) msg["can_continue"] = true
        if (toolTrace.isNotEmpty()) msg["tool_call_history"] = toolTrace
        if (metadata.isNotEmpty()) msg.putAll(metadata)
        return msg
    }
}
