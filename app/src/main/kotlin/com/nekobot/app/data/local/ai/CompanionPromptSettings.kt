package com.nekobot.app.data.local.ai

internal const val COMPANION_SPEAKING_STYLE_KEY = "output.speaking_style"

internal data class CompanionPromptSettings(
    val speakingPrompt: String = "",
    val thinkingPrompt: String = "",
    val thinkingEnabled: Boolean = true
) {
    val thinking get() = CharacterThinkingSettings(thinkingEnabled, thinkingPrompt)
    val speakingForEditing get() = speakingPrompt.takeIf(String::isNotBlank) ?: CompanionPromptLanguage.speakingRule

    fun speakingForRequest(characterName: String, userName: String?): String =
        speakingForEditing.replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
            .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")
}

internal fun companionSpeakingPromptToSave(draft: String): String =
    if (draft.isBlank() || draft == CompanionPromptLanguage.speakingRule) "" else draft
