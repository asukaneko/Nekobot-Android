package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 调用生产执行器和窗口计划，以内存回执替代数据库、以回调替代模型网络请求。 */
class FusedCompanionSummaryTest {
    private fun row(id: String, role: String, tokens: Int) = LocalMessageEntity(id, "fictional", role, tokens.toString(),
        timestamp = "2026-10-23T12:00:00Z", createdAt = "2026-10-23T12:00:00Z")
    private fun turn(id: String, tokens: Int) = listOf(row("${id}U", "user", tokens / 2), row("${id}A", "assistant", tokens - tokens / 2))
    private fun shortAndLong() = turn("short", 2000) + turn("long", 9000) + row("current", "user", 1500)

    private inner class Send(var rows: List<LocalMessageEntity>) {
        val receipts = mutableSetOf<String>()
        val calls = mutableListOf<List<String>>()
        val events = mutableListOf<String>()
        val references = mutableListOf<List<LocalMessageEntity>>()

        suspend fun run(afterReceipt: suspend () -> Unit = {},
            beforeReceipt: suspend (FusedCompanionPolicy.WindowPlan) -> Unit = {}): Int {
            // 生产入口在整个 processFused 调用外发一次开始/结束事件，完成后才进入回复。
            events += "等待开始"
            val changed = try {
                FusedCompanionSummary.run(nextPlan = {
                    FusedCompanionPolicy.windowPlan(rows, receipts, count = { batch -> batch.sumOf { it.content.toInt() } })
                }, summarize = { plan ->
                    calls += plan.batch.map { it.id }
                    references += plan.reference
                    events += "总结${calls.size}"
                    beforeReceipt(plan)
                    receipts += plan.batch.map { it.id }
                    events += "回执${calls.size}"
                    afterReceipt()
                    1
                })
            } finally { events += "等待结束" }
            events += "回复"
            return changed
        }
    }

    @Test fun shortThenLongMakesTwoOrderedCallsInsideOneWaitBeforeReply() = runBlocking {
        val send = Send(shortAndLong())
        assertEquals(2, send.run())
        assertEquals(listOf(listOf("shortU", "shortA"), listOf("longU", "longA")), send.calls)
        assertEquals(listOf("等待开始", "总结1", "回执1", "总结2", "回执2", "等待结束", "回复"), send.events)
        assertEquals(setOf("shortU", "shortA", "longU", "longA"), send.receipts)
        assertEquals(listOf("current"), send.references[1].map { it.id })
    }

    @Test fun onlyOversizedTurnMakesOneCallWithoutSplittingIt() = runBlocking {
        val send = Send(turn("long", 9000) + row("current", "user", 100))
        assertEquals(1, send.run())
        assertEquals(listOf(listOf("longU", "longA")), send.calls)
        assertEquals(listOf("等待开始", "总结1", "回执1", "等待结束", "回复"), send.events)
    }

    @Test fun firstBatchFailureStopsBeforeLongAndNextSendRetriesShortFirst() = runBlocking {
        val send = Send(shortAndLong())
        val failure = IOException("模拟断网")
        try { send.run { throw failure }; fail("应传播总结失败") }
        catch (e: IOException) { assertSame(failure, e) }
        assertEquals(listOf(listOf("shortU", "shortA")), send.calls)
        assertTrue(send.receipts.isEmpty())
        assertEquals(listOf("等待开始", "总结1", "等待结束"), send.events)
        send.run()
        assertEquals(listOf(listOf("shortU", "shortA"), listOf("shortU", "shortA"), listOf("longU", "longA")), send.calls)
    }

    @Test fun stoppingBetweenBatchesPreservesCompletedReceiptAndPreventsReply() = runBlocking {
        val send = Send(shortAndLong())
        val task = launch { send.run(afterReceipt = { currentCoroutineContext().cancel() }) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(listOf(listOf("shortU", "shortA")), send.calls)
        assertEquals(setOf("shortU", "shortA"), send.receipts)
        assertEquals(listOf("等待开始", "总结1", "回执1", "等待结束"), send.events)
    }

    @Test fun stoppingDuringLongBatchKeepsOnlyFirstReceiptAndPreventsReply() = runBlocking {
        val send = Send(shortAndLong())
        val task = launch { send.run { if (send.calls.size == 2) throw CancellationException("模拟停止") } }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(2, send.calls.size)
        assertEquals(setOf("shortU", "shortA"), send.receipts)
        assertEquals(listOf("等待开始", "总结1", "回执1", "总结2", "等待结束"), send.events)
    }

    @Test fun longBatchCapacityFailurePreservesShortBatchWithoutRetryingInSameWait() = runBlocking {
        val send = Send(shortAndLong())
        try {
            send.run { if (send.calls.size == 2) throw IllegalArgumentException("模拟模型输入容量不足") }
            fail("应传播容量失败")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, send.calls.size)
        assertEquals(setOf("shortU", "shortA"), send.receipts)
        assertFalse("回复" in send.events)
    }

    @Test fun shortPrefixStopsNearBatchGoalAndDoesNotForceLaterLongTurn() = runBlocking {
        val prefix = (1..3).flatMap { turn("short$it", 1000) }
        val send = Send(prefix + turn("long", 5000) + row("current", "user", 500))
        assertEquals(1, send.run())
        assertEquals(prefix.take(4).map { it.id }, send.calls[0])
        assertFalse("longU" in send.receipts)
    }

    @Test fun backlogStopsAsSoonAsTwoWholeTurnBatchesRestoreRecentBudget() = runBlocking {
        val prefix = (1..6).flatMap { turn("short$it", 1000) }
        val send = Send(prefix + turn("long", 5000) + row("current", "user", 500))
        assertEquals(2, send.run())
        assertEquals(listOf(prefix.take(4).map { it.id }, prefix.drop(4).take(4).map { it.id }), send.calls)
        assertFalse("longU" in send.receipts)
        assertEquals(1, send.events.count { it == "等待开始" })
        assertEquals(1, send.events.count { it == "回复" })
    }

    @Test fun pendingStillOverThresholdAfterPairContinuesExistingBatchRule() = runBlocking {
        val later = (1..12).flatMap { turn("later$it", 1000) }
        val send = Send(turn("short", 1000) + turn("long", 5000) + later + row("current", "user", 100))
        assertEquals(5, send.run())
        assertEquals(listOf(2, 2, 4, 4, 4), send.calls.map { it.size })
        assertEquals(later.take(4).map { it.id }, send.calls[2])
        assertEquals(later.drop(4).take(4).map { it.id }, send.calls[3])
        assertEquals(later.drop(8).take(4).map { it.id }, send.calls[4])
        assertEquals(1, send.events.count { it == "等待开始" })
        assertEquals("回复", send.events.last())
    }

    @Test fun secondOversizedTurnStaysPendingWhenPairAlreadyRestoredThreshold() = runBlocking {
        val send = Send(turn("short", 1000) + turn("long", 5000) + turn("later", 5000) + row("current", "user", 1000))
        assertEquals(2, send.run())
        assertEquals(listOf(listOf("shortU", "shortA"), listOf("longU", "longA")), send.calls)
        assertFalse("laterU" in send.receipts)
    }

    @Test fun oversizedTurnDoesNotTriggerBelowThreshold() = runBlocking {
        val send = Send(turn("long", 5000) + row("current", "user", 100))
        assertEquals(0, send.run())
        assertTrue(send.calls.isEmpty())
    }

    @Test fun oversizedCurrentTurnIsNeverSummarized() = runBlocking {
        val send = Send(turn("short", 2000) + row("current", "user", 7000))
        assertEquals(1, send.run())
        assertEquals(listOf(listOf("shortU", "shortA")), send.calls)
    }

    @Test fun deletedContinuationIsRecheckedBeforeCallingModel() = runBlocking {
        val send = Send(shortAndLong())
        assertEquals(1, send.run { send.rows = send.rows.filterNot { it.id.startsWith("long") } })
        assertEquals(listOf(listOf("shortU", "shortA")), send.calls)
    }

    @Test fun changedContinuationUsesFreshWholeTurn() = runBlocking {
        val send = Send(shortAndLong())
        assertEquals(2, send.run { plan ->
            if (send.calls.size == 1) send.rows = send.rows.map { if (it.id == "longU") it.copy(content = "2600") else it }
            else assertEquals("2600", plan.batch.first().content)
        })
        assertEquals(listOf("longU", "longA"), send.calls[1])
    }

    @Test fun continuationAlreadySummarizedWhileShortBatchRunsIsNotRepeated() = runBlocking {
        val send = Send(shortAndLong())
        assertEquals(1, send.run { send.receipts += listOf("longU", "longA") })
        assertEquals(listOf(listOf("shortU", "shortA")), send.calls)
    }

    @Test fun continuationCannotPullCurrentTurnIntoSummary() {
        val rows = turn("short", 2000) + row("current", "user", 7000)
        val plan = FusedCompanionPolicy.windowPlan(rows, setOf("shortU", "shortA"),
            count = { it.sumOf { row -> row.content.toInt() } })
        assertFalse(plan.needsSummary)
        assertTrue(plan.batch.isEmpty())
    }

    @Test fun missingReceiptsCannotCauseAnInfiniteLoopOrAdvancePastFailedShortBatch() = runBlocking {
        val rows = shortAndLong()
        var calls = 0
        try {
            FusedCompanionSummary.run(nextPlan = {
                FusedCompanionPolicy.windowPlan(rows, emptySet(), count = { it.sumOf { row -> row.content.toInt() } })
            }, summarize = { calls++; 0 })
            fail("未写回执不能无限重试同一包")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }
}
