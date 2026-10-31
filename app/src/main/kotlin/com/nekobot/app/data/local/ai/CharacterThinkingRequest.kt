package com.nekobot.app.data.local.ai

/**
 * 在最终请求中保留本轮已经批准注入的思考文案，放在首条 system 的开头。
 * 工具循环复用时不重复；不接触 user、assistant、tool 的正文或签名。
 */
internal fun withCharacterThinkingRequest(
    messages: List<Map<String, Any>>,
    prompt: String?,
    companionMode: Boolean = false
): List<Map<String, Any>> {
    val section = prompt?.takeIf(String::isNotBlank)?.let { if (companionMode) it else "## $CHARACTER_THINKING_STYLE_KEY\n$it" }
    var inserted = section == null
    var changed = false
    val result = messages.map { message ->
        if (message["role"] != "system") return@map message
        val original = message["content"] as? String ?: return@map message
        // 先移除本轮完整全文（自定义文案可能含空行），再清理历史旧副本。
        val withoutCurrent = if (section == null) original else original.replace(section, "")
        val hasStyleHeading = Regex("(?:\\A|\\n{2,})(?:## ${Regex.escape(CHARACTER_THINKING_STYLE_KEY)}|\\[${Regex.escape(CHARACTER_THINKING_STYLE_KEY)}\\])\\n")
            .containsMatchIn(withoutCurrent)
        val cleaned = if (hasStyleHeading) PromptStack.stripSection(withoutCurrent, CHARACTER_THINKING_STYLE_KEY)
            else withoutCurrent
        val content = if (!inserted) {
            inserted = true
            listOf(requireNotNull(section), cleaned.trim()).filter(String::isNotBlank).joinToString("\n\n")
        } else cleaned
        if (content == original) message else {
            changed = true
            message + ("content" to content)
        }
    }
    return if (!inserted) listOf(mapOf("role" to "system", "content" to requireNotNull(section))) + result
        else if (changed) result else messages
}
