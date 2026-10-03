package com.nekobot.app.ui.screens.memory

import com.nekobot.app.data.local.db.LocalMessageEntity

/** Display groups only. Original IDs, bodies, roles and order are never rewritten. */
internal fun sourceChatTurns(
    messages: List<LocalMessageEntity>, highlightedIds: Set<String> = emptySet()
): List<List<LocalMessageEntity>> {
    val turns = mutableListOf<MutableList<LocalMessageEntity>>()
    var answered = false
    for (message in messages) {
        val previous = turns.lastOrNull()?.lastOrNull()
        val beginsTurn = message.role == "user" && answered
        val changesHighlight = previous != null && (previous.id in highlightedIds) != (message.id in highlightedIds)
        if (previous == null || beginsTurn || changesHighlight) {
            turns += mutableListOf<LocalMessageEntity>()
            answered = false
        }
        turns.last() += message
        if (message.role == "assistant") answered = true
    }
    return turns
}

internal fun sourceAssistantName(message: LocalMessageEntity, sessionAssistantName: String?): String? =
    message.sender?.trim()?.takeIf(String::isNotEmpty)
        ?: sessionAssistantName?.trim()?.takeIf(String::isNotEmpty)

/** Keep different speakers separate, including explicit names in imported group history. */
internal fun sourceSpeakerGroups(
    messages: List<LocalMessageEntity>, sessionAssistantName: String?
): List<List<LocalMessageEntity>> {
    val groups = mutableListOf<MutableList<LocalMessageEntity>>()
    for (message in messages) {
        val previous = groups.lastOrNull()?.lastOrNull()
        if (previous == null || previous.role != message.role ||
            (message.role != "user" && sourceAssistantName(previous, sessionAssistantName) != sourceAssistantName(message, sessionAssistantName))) {
            groups += mutableListOf<LocalMessageEntity>()
        }
        groups.last() += message
    }
    return groups
}
