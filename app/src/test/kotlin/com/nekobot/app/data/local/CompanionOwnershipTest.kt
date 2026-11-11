package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 归属只对仍然有效的窗口成立；其余情况必须按未绑定处理，避免角色页打不开。 */
class CompanionOwnershipTest {
    private fun session(id: String = "main", characterId: String? = "role", companion: Boolean = true) =
        LocalSessionEntity(id, id, characterId = characterId, sessionMode = "agent", companionMode = companion,
            inheritCharacter = companion, longConversationEnabled = companion, createdAt = "now", updatedAt = "now")

    @Test fun noOwnerRowMeansOrdinaryCharacter() {
        assertEquals(CompanionOwnership.Unbound, CompanionOwnership.resolve(null, session(), "role"))
        assertEquals(CompanionOwnership.Unbound, CompanionOwnership.resolve(" ", session(), "role"))
    }

    @Test fun existingWindowKeepsOwnership() {
        assertEquals(CompanionOwnership.Bound, CompanionOwnership.resolve("main", session(), "role"))
    }

    @Test fun deletedWindowLeavesDanglingOwnership() {
        assertEquals(CompanionOwnership.Dangling, CompanionOwnership.resolve("main", null, "role"))
    }

    @Test fun windowOfAnotherCharacterOrOrdinaryModeIsDangling() {
        assertEquals(CompanionOwnership.Dangling, CompanionOwnership.resolve("main", session(characterId = "other"), "role"))
        assertEquals(CompanionOwnership.Dangling, CompanionOwnership.resolve("main", session(companion = false), "role"))
    }
}
