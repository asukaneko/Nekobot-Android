package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INITIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelationshipStateInitializationTest {

    @Test
    fun `character card values initialize all six dimensions`() {
        val state = relationshipStateFromInitial(
            characterId = "character-1",
            targetId = sessionRelationshipTargetId("session-1"),
            initialState = mapOf(
                "affection" to 61,
                "trust" to 62.9,
                "familiarity" to "63",
                "dependency" to 64,
                "security" to 65,
                "jealousy" to 66
            ),
            updatedAt = "2026-07-22T00:00:00Z"
        )

        assertEquals("session:session-1", state.targetId)
        assertEquals(61, state.affection)
        assertEquals(62, state.trust)
        assertEquals(63, state.familiarity)
        assertEquals(64, state.dependency)
        assertEquals(65, state.security)
        assertEquals(66, state.jealousy)
    }

    @Test
    fun `missing and out of range values use safe defaults`() {
        val state = relationshipStateFromInitial(
            characterId = "character-1",
            targetId = "session:session-1",
            initialState = mapOf(
                "affection" to 101,
                "jealousy" to -3
            ),
            updatedAt = "now"
        )

        assertEquals(100, state.affection)
        assertEquals(50, state.trust)
        assertEquals(30, state.familiarity)
        assertEquals(30, state.dependency)
        assertEquals(50, state.security)
        assertEquals(0, state.jealousy)
    }

    @Test
    fun `local relationship source is not serialized for remote api`() {
        val json = Gson().toJson(
            CreateSessionRequest(
                name = "new session",
                relationshipStateSource = RELATIONSHIP_STATE_SOURCE_INITIAL
            )
        )

        assertFalse(json.contains("relationshipStateSource"))
        assertFalse(json.contains("relationship_state_source"))
    }
}
