package com.nekobot.app.data.local.ai

/** 回查结束后模型仍要求调用工具时：去掉工具再请模型以角色身份回答一次，不用写死的台词冒充角色。 */
internal object CompanionRecallFinal {
    const val FINAL_NOTE = "先用已经想起来的事，按你平时的口吻接着聊；想不起来或拿不准的地方，就坦然说记不清了，别编。"
    fun finalMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> =
        if (messages.lastOrNull() == mapOf("role" to "system", "content" to FINAL_NOTE)) messages
        else messages + mapOf("role" to "system", "content" to FINAL_NOTE)

    suspend fun resolve(first: LocalAiResult, mustAnswer: Boolean, retry: suspend () -> LocalAiResult): LocalAiResult {
        if (!mustAnswer) return first
        CompanionReplyGuard.validate(first.content)
        check(first.error == null) { first.error.orEmpty() }
        if (first.toolCalls.isEmpty()) {
            check(first.content.isNotBlank()) { CompanionReplyGuard.FORMAT_ERROR }
            return first
        }
        // 保留原有的一次结构化调用收尾重试；正文中的调用标记直接失败，不额外请求。
        val second = retry()
        check(second.error == null) { second.error.orEmpty() }
        check(second.toolCalls.isEmpty() && second.content.isNotBlank()) { CompanionReplyGuard.FORMAT_ERROR }
        CompanionReplyGuard.validate(second.content)
        return second.copy(finishReason = "stop")
    }
}
