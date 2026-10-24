package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class FusedRollingWindowTest {
    private fun row(id: String, role: String, cost: Int) = LocalMessageEntity(
        id, "fictional", role, cost.toString(), timestamp = "2026-10-23", createdAt = "2026-10-23")
    private fun turn(id: String, cost: Int = 1000) =
        listOf(row("${id}u", "user", cost / 2), row("${id}a", "assistant", cost - cost / 2))
    private fun plan(rows: List<LocalMessageEntity>, receipts: Set<String> = emptySet()) =
        FusedCompanionPolicy.windowPlan(rows, receipts, currentMessageId = "current",
            count = { batch -> batch.sumOf { it.content.toInt() } })

    @Test fun reachingEightThousandSummarizesOldestTwoThousand() {
        val rows = (1..7).flatMap { turn("$it") } + row("current", "user", 1000)
        val next = plan(rows)
        assertTrue(next.needsSummary)
        assertEquals(rows.take(4), next.batch)
    }

    @Test fun successfulReceiptLeavesSixThousandWithoutRefillingFromHistory() {
        val rows = (1..8).flatMap { turn("$it") } + row("current", "user", 50)
        val before = plan(rows)
        assertEquals(rows, before.window) // Nothing leaves the window before persistence.
        val receiptIds = before.batch.map { it.id }.toSet()
        val after = plan(rows, receiptIds)
        assertEquals(6050, after.window.sumOf { it.content.toInt() })
        assertEquals(rows.filterNot { it.id in receiptIds }, after.window)
        assertFalse(after.needsSummary)
        assertEquals(17, rows.size) // Selection does not mutate original chat.
    }

    @Test fun nextGrowthSummarizesNextBatchWithoutReintroducingPreviousBatch() {
        val first = (1..8).flatMap { turn("$it") } + row("current", "user", 50)
        val saved = plan(first).batch.map { it.id }.toSet()
        val grown = first.dropLast(1) + turn("9") + turn("10") + row("current", "user", 50)
        val next = plan(grown, saved)
        assertTrue(next.needsSummary)
        assertEquals(listOf("3u", "3a", "4u", "4a"), next.batch.map { it.id })
        val after = plan(grown, saved + next.batch.map { it.id })
        assertEquals(6050, after.window.sumOf { it.content.toInt() })
        assertTrue(after.window.none { it.id in saved })
    }

    @Test fun backlogUsesApproximatelyTwoThousandPerBatch() {
        val rows = (1..15).flatMap { turn("$it") } + row("current", "user", 1)
        val receipts = mutableSetOf<String>()
        val batches = mutableListOf<Int>()
        repeat(20) {
            val next = plan(rows, receipts)
            if (!next.needsSummary) return@repeat
            assertTrue(next.batch.isNotEmpty())
            batches += next.batch.sumOf { it.content.toInt() }
            receipts += next.batch.map { it.id }
        }
        assertEquals(listOf(2000, 2000, 2000, 2000), batches)
        assertEquals(7001, plan(rows, receipts).window.sumOf { it.content.toInt() })
    }

    @Test fun oversizedCurrentTurnFailsBeforeSummarizationWithoutTrimmingAnySource() {
        val rows = turn("old") + row("current", "user", 8001)
        try { plan(rows); fail() }
        catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.CURRENT_LIMIT, e.reason) }
        assertEquals(3, rows.size)
        assertEquals("8001", rows.last().content)
        val exactLimit = plan(listOf(row("current", "user", 8000)))
        assertFalse(exactLimit.needsSummary) // No older completed turn can be summarized.
        assertEquals(8000, exactLimit.currentTurnTokens)
    }

    @Test fun historicalOversizedTurnRemainsWholeAndOwnsItsBatch() {
        val rows = turn("long", 10000) + row("current", "user", 50)
        val next = plan(rows)
        assertEquals(rows.take(2), next.batch)
        assertEquals(10000, next.batch.sumOf { it.content.toInt() })
        assertEquals(50, plan(rows, next.batch.map { it.id }.toSet()).unsummarizedTokens)
    }

    @Test fun currentMessageRemainsCountedEvenWhenRegeneratingAnOldSummarizedTurn() {
        val rows = turn("old", 2000) + row("current", "user", 1000)
        val receipts = rows.map { it.id }.toSet()
        val next = plan(rows, receipts)
        assertEquals(listOf(rows.last()), next.window)
        assertEquals(1000, next.unsummarizedTokens)
        assertFalse(next.needsSummary)
    }
}
