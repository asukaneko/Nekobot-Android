package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class FusedMemoryRetryPolicyTest {
    private val rows = (1..6).map { i -> LocalMessageEntity("m$i", "fictional-session", if (i % 2 == 1) "user" else "assistant",
        "虚构原话$i", timestamp = "2026-10-23T00:00:00Z", createdAt = "2026-10-23T00:00:00Z") }
    @Test fun exactlyTwoIncompleteAttemptsSplitNextAttemptIntoWholeTurns() {
        val policy = FusedMemoryRetryPolicy(); val key = policy.key("profile", "scope", rows)
        assertEquals(listOf(rows), policy.batches(key, rows))
        policy.incomplete(key); assertEquals(listOf(rows), policy.batches(key, rows))
        policy.incomplete(key)
        val split = policy.batches(key, rows)
        assertEquals(listOf(rows.take(4), rows.drop(4)), split)
        assertEquals(rows, split.flatten())
        policy.complete(key); assertEquals(listOf(rows), policy.batches(key, rows))
    }
    @Test fun changedSourceAndDifferentScopeHaveIndependentRetryCounters() {
        val policy = FusedMemoryRetryPolicy(); val key = policy.key("profile", "scope", rows)
        repeat(2) { policy.incomplete(key) }
        for (other in listOf(policy.key("other", "scope", rows), policy.key("profile", "other", rows),
            policy.key("profile", "scope", rows.map { it.copy(content = "虚构新版本") }))) {
            assertNotEquals(key, other); assertEquals(listOf(rows), policy.batches(other, rows))
        }
    }
    @Test fun oneLongTurnIsNeverSplitInTheMiddle() {
        val policy = FusedMemoryRetryPolicy(); val rows = rows.take(2); val key = policy.key("profile", "scope", rows)
        repeat(3) { policy.incomplete(key) }
        assertEquals(listOf(rows), policy.batches(key, rows))
    }
}
