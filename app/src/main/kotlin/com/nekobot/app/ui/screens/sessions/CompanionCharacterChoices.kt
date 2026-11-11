package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.local.CompanionEligibility
import com.nekobot.app.data.local.canTransferToCompanion
import com.nekobot.app.data.model.CharacterPreset

internal data class CompanionCharacterChoice(
    val character: CharacterPreset,
    val eligibility: CompanionEligibility
)

/** 只展示能新建、打开主窗口或迁入旧会话的角色；不按名称猜测角色类型。 */
internal suspend fun loadCompanionCharacterChoices(
    characters: List<CharacterPreset>,
    eligibility: suspend (String) -> CompanionEligibility
): List<CompanionCharacterChoice> = buildList {
    val seen = mutableSetOf<String>()
    for (character in characters) {
        val id = character.id?.takeIf(String::isNotBlank) ?: continue
        if (!seen.add(id)) continue
        val state = when (val current = eligibility(id)) {
            is CompanionEligibility.Used -> current.copy(candidates = current.candidates.filter {
                it.characterId == id && it.canTransferToCompanion()
            })
            else -> current
        }
        val selectable = when (state) {
            CompanionEligibility.Available -> true
            is CompanionEligibility.Existing -> state.sessionId.isNotBlank()
            is CompanionEligibility.Used -> state.candidates.isNotEmpty()
            CompanionEligibility.MissingCharacter -> false
        }
        if (selectable) add(CompanionCharacterChoice(character, state))
    }
}
