package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipBackfillTest {
    private val state = CompanionRelationshipEntity("s", "c", awaitingHistory = true, cursorId = "last")
    private val full = RelationshipNarrative(CompanionRelationshipScores.FORMAT,
        CompanionRelationshipPolicy.dimensions.mapValues { RelationshipDescription("合成关系自述", "", emptyList(), score = 75) },
        null, 1)

    @Test fun completedBackfillCanClearOnlyTheObsoleteWaitFlag() {
        assertTrue(CompanionRelationshipPolicy.backfillComplete(state, full, 0))
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state.copy(awaitingHistory = false), full, 0))
    }

    @Test fun newTurnsUnknownCursorAndExplicitHistoryReviewKeepTheirPendingDecision() {
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state, full, 1))
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state, full, null))
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state.copy(cursorId = null), full, 0))
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state.copy(historyReviewRequired = true), full, 0))
    }

    @Test fun partialOrTextOnlyAssessmentCannotClaimCompletedScoring() {
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state, null, 0))
        val partial = full.copy(relationships = full.relationships + ("trust" to full.relationships.getValue("trust")?.copy(score = null)))
        assertFalse(CompanionRelationshipPolicy.backfillComplete(state, partial, 0))
    }
}
