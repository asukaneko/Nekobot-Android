package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.local.CompanionEligibility
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.CharacterPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionCharacterChoicesTest {
    private fun character(id: String? = "c", name: String = "角色") = CharacterPreset(id = id, name = name)
    private fun session(id: String = "old", characterId: String = "c", mode: String = "character") =
        LocalSessionEntity(id, id, characterId = characterId, sessionMode = mode, createdAt = "now", updatedAt = "now")

    @Test fun mixedLibraryOnlyOffersActionsThatCanProceed() = runBlocking {
        val characters = listOf("new", "main", "convert", "group", "missing").map { character(it) }
        val states = mapOf(
            "new" to CompanionEligibility.Available,
            "main" to CompanionEligibility.Existing("main-window"),
            "convert" to CompanionEligibility.Used(listOf(session(characterId = "convert"))),
            "group" to CompanionEligibility.Used(emptyList()),
            "missing" to CompanionEligibility.MissingCharacter
        )

        val choices = loadCompanionCharacterChoices(characters) { states.getValue(it) }

        assertEquals(listOf("new", "main", "convert"), choices.map { it.character.id })
        assertEquals(listOf(states.getValue("new"), states.getValue("main"), states.getValue("convert")), choices.map { it.eligibility })
    }

    @Test fun emptyLibraryNeedsNoEligibilityLookups() = runBlocking {
        assertTrue(loadCompanionCharacterChoices(emptyList()) { error("No character to check") }.isEmpty())
    }

    @Test fun entirelyUnavailableLibraryProducesEmptyPicker() = runBlocking {
        val choices = loadCompanionCharacterChoices(listOf(character("group"), character("gone"))) {
            if (it == "group") CompanionEligibility.Used(emptyList()) else CompanionEligibility.MissingCharacter
        }
        assertTrue(choices.isEmpty())
    }

    @Test fun missingAndBlankIdsNeverReachRepositoryOrPickerKeys() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val choices = loadCompanionCharacterChoices(listOf(character(null), character(""), character("  "), character("valid"))) {
            lookedUp += it
            CompanionEligibility.Available
        }
        assertEquals(listOf("valid"), lookedUp)
        assertEquals(listOf("valid"), choices.map { it.character.id })
    }

    @Test fun sameNameDoesNotConflateRolesAndDuplicateIdsAreCheckedOnce() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val first = character("a")
        val second = character("b")
        val choices = loadCompanionCharacterChoices(listOf(first, character("a", "重复记录"), second)) {
            lookedUp += it
            if (it == "a") CompanionEligibility.MissingCharacter else CompanionEligibility.Available
        }
        assertEquals(listOf("a", "b"), lookedUp)
        assertEquals(listOf(second), choices.map { it.character })
    }

    @Test fun transferOnlyRetainsMatchingSupportedSourceSessions() = runBlocking {
        val ordinary = session()
        val inheritedAgent = session("agent", mode = "agent").copy(inheritCharacter = true)
        val candidates = listOf(ordinary, inheritedAgent,
            session("other-role", characterId = "other"), session("group", mode = "group"),
            session("tool-only", mode = "agent"), session("owned", mode = "agent").copy(companionMode = true))
        val choices = loadCompanionCharacterChoices(listOf(character())) { CompanionEligibility.Used(candidates) }
        assertEquals(listOf(ordinary, inheritedAgent), (choices.single().eligibility as CompanionEligibility.Used).candidates)
    }

    @Test fun unsupportedTransfersAndBlankMainWindowAreNotSelectable() = runBlocking {
        val choices = loadCompanionCharacterChoices(listOf(character("unsupported"), character("broken"))) {
            if (it == "broken") CompanionEligibility.Existing(" ")
            else CompanionEligibility.Used(listOf(session(characterId = it, mode = "group")))
        }
        assertTrue(choices.isEmpty())
    }

    @Test fun recheckRemovesRoleThatNoLongerExists() = runBlocking {
        val available = loadCompanionCharacterChoices(listOf(character())) { CompanionEligibility.Available }
        assertTrue(loadCompanionCharacterChoices(available.map { it.character }) { CompanionEligibility.MissingCharacter }.isEmpty())
    }

    @Test fun eligibilityFailureIsNotMisreportedAsAnEmptyLibrary() = runBlocking {
        val failure = IllegalStateException("database unavailable")
        try {
            loadCompanionCharacterChoices(listOf(character())) { throw failure }
            throw AssertionError("Expected the load failure")
        } catch (actual: IllegalStateException) {
            assertTrue(actual === failure)
        }
    }
}
