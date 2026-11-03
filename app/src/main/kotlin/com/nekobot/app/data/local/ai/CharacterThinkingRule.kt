package com.nekobot.app.data.local.ai

internal const val CHARACTER_TURN_CONTEXT = "_character_turn_context"
internal const val CHARACTER_THINKING_RULE = "_character_thinking_rule"
internal fun withCharacterThinkingRule(
    messages: List<Map<String, Any>>,
    prompt: String?,
    enabled: Boolean
): List<Map<String, Any>> {
    if (messages.isEmpty()) return messages
    val rule = prompt?.trim().orEmpty().takeIf { enabled }.orEmpty()
    val clock = messages.lastOrNull { it.containsKey(CHARACTER_TURN_CONTEXT) }
        ?.get(CHARACTER_TURN_CONTEXT) as? String
    val unchanged = messages.filterNot { it.containsKey(CHARACTER_TURN_CONTEXT) || it[CHARACTER_THINKING_RULE] == true }
    val content = listOfNotNull(clock?.takeIf(String::isNotBlank), rule.takeIf(String::isNotBlank)).joinToString("\n\n")
    if (content.isEmpty()) return unchanged
    val tail = buildMap<String, Any> {
        put("role", "system")
        put("content", content)
        if (clock != null) put(CHARACTER_TURN_CONTEXT, clock)
        put(CHARACTER_THINKING_RULE, true)
    }
    return unchanged + tail
}
