package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import org.junit.Assert.*
import org.junit.Test

class CompanionReviewReplyTest {
    private val before = "2026-10-23T10:00:00Z"
    private val confirmed = "2026-10-23T10:01:00Z"
    private val after = "2026-10-23T10:02:00Z"
    private fun message(at: String = after) = LocalMessageEntity("reply", "session", "assistant", "虚构回复", timestamp = at, createdAt = at)
    private fun review(at: String = confirmed) = CompanionLockedReviewEntity("review", "scope", "session", "locked", "confirmed", at)
    private fun variant(at: String = after) = LocalMessageVariantEntity("variant", "reply", "session", 1, "新的虚构回复", createdAt = at)

    @Test fun userPlaceholderDeletedAndBlankRowsDoNotCountAsSavedReply() {
        for (row in listOf(null, message().copy(role = "user"), message().copy(deleted = true), message().copy(content = ""))) {
            assertNull(CompanionReviewReply.from(row, emptyList()))
        }
    }
    @Test fun earlierSavedReplyCannotReleaseThisTurnsReview() {
        assertFalse(CompanionReviewReply.from(message(before), emptyList())!!.includes(review()))
        assertTrue(CompanionReviewReply.from(message(), emptyList())!!.includes(review()))
    }
    @Test fun regeneratedReplyUsesPersistedVariantTimeAndChangesDismissBoundary() {
        val original = CompanionReviewReply.from(message(before), emptyList())!!
        val regenerated = CompanionReviewReply.from(message(before), listOf(variant()))!!
        assertNotEquals(original.boundary, regenerated.boundary)
        assertTrue(regenerated.includes(review()))
    }
    @Test fun unrelatedVariantsCannotClaimThatTheReplyWasSaved() {
        val reply = CompanionReviewReply.from(message(before), listOf(variant().copy(messageId = "other"), variant().copy(sessionId = "other")))!!
        assertFalse(reply.includes(review()))
    }
    @Test fun timezoneOffsetsAreComparedAsInstants() {
        val reply = CompanionReviewReply.from(message("2026-10-23T18:02:00+08:00"), emptyList())!!
        assertTrue(reply.includes(review()))
        assertFalse(reply.includes(review("2026-10-23T10:03:00Z")))
    }
    @Test fun unknownTimesDoNotInventACompletedReply() {
        assertNull(CompanionReviewReply.from(message("unknown"), emptyList()))
        assertFalse(CompanionReviewReply.from(message(), emptyList())!!.includes(review("unknown")))
    }
    @Test fun regeneratingAnEarlierReplyStillAdvancesTheBoundary() {
        val laterDialogue = message(confirmed).copy(id = "later")
        val reply = CompanionReviewReply.latest(laterDialogue, message(before), variant())!!
        assertEquals(CompanionReviewReply.from(message(before), listOf(variant())), reply)
        assertTrue(reply.includes(review()))
    }
    @Test fun pendingUserMessageCannotReuseAnEarlierVariantAsItsReply() {
        val user = message("2026-10-23T10:03:00Z").copy(role = "user")
        assertNull(CompanionReviewReply.latest(user, message(before), variant()))
        assertNull(CompanionReviewReply.latest(user, message(before).copy(deleted = true), variant("2026-10-23T10:04:00Z")))
    }
    @Test fun dismissedReviewReturnsOnlyAfterANewPersistedReplyBoundary() {
        val queue = CompanionLockedReviewQueue()
        val first = CompanionReviewReply.from(message(), emptyList())!!
        val next = CompanionReviewReply.from(message(), listOf(variant("2026-10-23T10:03:00Z")))!!
        val state = CompanionLockedReviewQueue.State("session", true, false, false, true, true, first.boundary)
        assertEquals(review(), queue.next(state, listOf(review())))
        queue.dismiss("review", first.boundary)
        assertNull(queue.next(state, listOf(review())))
        assertEquals(review(), queue.next(state.copy(boundary = next.boundary), listOf(review())))
    }
}
