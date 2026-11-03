package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

/** 用实际窗口计划模拟发送、回执和回复保存，回复后不执行总结记忆。 */
class CompanionConsolidationCallCountTest {
    private fun row(id: String, role: String, cost: Int) = LocalMessageEntity(id, "s", role, cost.toString(),
        timestamp = "2026-10-23T12:00:00Z", createdAt = "2026-10-23T12:00:00Z")
    private fun plan(rows: List<LocalMessageEntity>, receipts: Set<String> = emptySet(), budget: Int = 8000) =
        FusedCompanionPolicy.windowPlan(rows, receipts, budget, 2000, count = { it.sumOf { row -> row.content.toInt() } })
    private fun history(turns: Int, user: Int = 500, reply: Int = 500) = (1..turns).flatMap {
        listOf(row("u$it", "user", user), row("a$it", "assistant", reply))
    }
    @Test fun eightThousandStartsRollingSummaryWithoutWaitingForOverflow() {
        val rows = history(7) + row("u8", "user", 1000)
        assertTrue(plan(rows).needsSummary)
        assertEquals(8000, plan(rows).unsummarizedTokens)
        val over = plan(rows.dropLast(1) + row("u8", "user", 1001))
        assertTrue(over.needsSummary); assertEquals(4, over.batch.size)
        assertFalse(over.batch.any { it.id == "u8" })
    }
    @Test fun summaryReceiptPrecedesEvictionAndAllPendingTurnsStayVisible() {
        val rows = history(8) + row("current", "user", 50)
        val before = plan(rows)
        assertEquals(rows, before.window)
        val receipts = before.batch.map { it.id }.toSet()
        val after = plan(rows, receipts)
        assertTrue(after.window.sumOf { it.content.toInt() } <= 8000)
        assertTrue(rows.filterNot { it.id in receipts }.all { it in after.window })
        assertTrue(rows.filterNot { it in after.window }.all { it.id in receipts })
    }
    @Test fun continuousChatTriggersAboutEveryTwoThousandAndNeverAfterReply() {
        val rows = mutableListOf<LocalMessageEntity>(); val receipts = mutableSetOf<String>()
        val summaryTurns = mutableListOf<Int>(); var calls = 0
        for (turn in 1..30) {
            rows += row("u$turn", "user", 250)
            val next = plan(rows, receipts)
            if (next.needsSummary) { calls++; summaryTurns += turn; receipts += next.batch.map { it.id } }
            val beforeReply = calls
            rows += row("a$turn", "assistant", 250)
            assertEquals(beforeReply, calls)
        }
        assertEquals(listOf(17, 21, 25, 29), summaryTurns)
    }
    @Test fun backlogUsesWholeTurnsAndMultipleCallsBelowFourThousand() {
        val rows = history(15) + row("current", "user", 1)
        val receipts = mutableSetOf<String>(); var calls = 0
        while (plan(rows, receipts).needsSummary) {
            val batch = plan(rows, receipts).batch
            assertTrue(batch.sumOf { it.content.toInt() } <= 4000)
            assertEquals(0, batch.size % 2); assertFalse(batch.any { it.id == "current" })
            receipts += batch.map { it.id }; calls++
        }
        assertEquals(4, calls)
    }
    @Test fun failedSummaryKeepsEverythingAndNextSendRetriesSameOldestBatch() {
        val rows = history(8) + row("current", "user", 50)
        val failed = plan(rows)
        assertEquals(rows, failed.window)
        assertEquals(failed.batch, plan(rows).batch)
        val retry = plan(rows, failed.batch.map { it.id }.toSet())
        assertFalse(retry.needsSummary)
    }
    @Test fun partiallySummarizedTurnIsProtectedAsAWhole() {
        val rows = history(8) + row("current", "user", 1)
        val next = plan(rows, setOf("u1"))
        assertTrue(next.batch.any { it.id == "u1" }); assertTrue(next.batch.any { it.id == "a1" })
    }
    @Test fun actualCapacityCanRequireSummaryBeforeEightThousand() {
        val rows = history(4) + row("current", "user", 50)
        assertFalse(plan(rows).needsSummary)
        assertTrue(plan(rows, budget = 3000).needsSummary)
    }
    @Test fun oversizedWholeTurnIsNeverSilentlySplitOrSkipped() {
        val rows = history(1, 2500, 2500) + row("current", "user", 4000)
        val next = plan(rows)
        assertTrue(next.needsSummary); assertEquals(rows.take(2), next.batch); assertEquals(rows, next.window)
    }
    @Test fun oversizedTurnWaitsUntilAnotherBatchIsNeededAndThenStaysWhole() {
        val rows = history(1) + listOf(row("hugeU", "user", 3000), row("hugeA", "assistant", 3000)) +
            listOf(row("laterU", "user", 500), row("laterA", "assistant", 500), row("current", "user", 500))
        val first = plan(rows)
        assertEquals(rows.take(2), first.batch)
        val receipts = first.batch.map { it.id }.toSet()
        assertFalse(plan(rows, receipts).needsSummary)
        val grown = rows.dropLast(1) + row("current", "user", 1500)
        val second = plan(grown, receipts)
        assertTrue(second.needsSummary)
        assertEquals(listOf("hugeU", "hugeA"), second.batch.map { it.id })
        assertFalse(plan(grown, (first.batch + second.batch).map { it.id }.toSet()).needsSummary)
    }
    @Test fun referenceContainsOnlyNewerTurnsAndIsBounded() {
        val rows = history(15) + row("current", "user", 1)
        val next = plan(rows)
        assertTrue(next.reference.sumOf { it.content.toInt() } <= 8000)
        assertTrue(next.reference.none { it in next.batch })
        assertTrue(next.reference.all { rows.indexOf(it) > rows.indexOf(next.batch.last()) })
    }
    @Test fun defaultEstimatorCountsCurrentUserInSameUnitsAsWindowAndBatch() {
        val rows = history(3) + row("current", "user", 12)
        val next = FusedCompanionPolicy.windowPlan(rows, emptySet())
        assertEquals(FusedCompanionPolicy.tokens(rows), next.unsummarizedTokens)
        assertEquals(rows, next.window)
    }
}
