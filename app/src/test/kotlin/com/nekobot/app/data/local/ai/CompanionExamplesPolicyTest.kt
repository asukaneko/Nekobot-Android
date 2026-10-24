package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.CompanionExamplesEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionExamplesPolicyTest {
    private fun row(id: String, role: String, text: String) = LocalMessageEntity(id, "s", role, text, timestamp = "2026-10-17", createdAt = "2026-10-17")
    private fun turn(i: Int, user: String = "今天晚饭吃什么好呢？", answer: String = "不如吃一碗热乎乎的面吧。") =
        listOf(row("u$i", "user", user), row("a$i", "assistant", answer))
    @Test fun twentyFullExchangesCountAsTwentyRounds() { assertEquals(20, CompanionExamplesPolicy.pairs((1..20).flatMap { turn(it) }).size) }
    @Test fun greetingAndIncompleteTurnDoNotCount() {
        assertEquals(1, CompanionExamplesPolicy.pairs(listOf(row("g", "assistant", "你好呀")) + turn(1) + row("last", "user", "回来啦")).size)
    }
    @Test fun deletedAndSystemBreaksAreNotPaired() {
        val t = turn(1)
        assertTrue(CompanionExamplesPolicy.pairs(listOf(t[0], row("sys", "system", "错误"), t[1])).isEmpty())
        assertTrue(CompanionExamplesPolicy.pairs(listOf(t[0], t[1].copy(deleted = true))).isEmpty())
    }
    @Test fun interruptedAndBackgroundRepliesAreNotCompleteExchanges() {
        val t = turn(1)
        for (source in listOf("companion_incomplete", "task_center", "proactive_chat")) {
            val rows = listOf(t[0], t[1].copy(source = source))
            assertTrue(CompanionExamplesPolicy.pairs(rows).isEmpty())
            assertTrue(CompanionExamplesPolicy.candidates(rows).isEmpty())
        }
    }
    @Test fun immediateCorrectionExcludesThePreviousExchange() {
        val candidates = CompanionExamplesPolicy.candidates(turn(1) + turn(2, "不是，我说的是早餐吃什么？"))
        assertEquals(listOf("a2"), candidates.map { it.id })
    }
    @Test fun shortLongAndFailureCandidatesAreRemoved() {
        val rows = turn(1, "嗯") + turn(2, answer = "好") + turn(3, answer = "长".repeat(801)) +
            turn(4, answer = "HTTP 500 请求失败了") + turn(5)
        assertEquals(listOf("a5"), CompanionExamplesPolicy.candidates(rows).map { it.id })
    }
    @Test fun candidatesBalanceScenesBeforeTakingMoreOfTheSame() {
        val rows = (1..20).flatMap { turn(it) } + turn(21, "考试失败了，我好难过") + turn(22, "明天一起去公园好吗？")
        assertEquals(setOf("a1", "a21", "a22"), CompanionExamplesPolicy.candidates(rows, 3).map { it.id }.toSet())
    }
    @Test fun selectedExamplesAreExactOriginalExchanges() {
        val candidates = CompanionExamplesPolicy.candidates(turn(1) + turn(2))
        assertEquals(candidates.map { it.pair }, CompanionExamplesPolicy.selected("{\"ids\":[\"a1\",\"a2\"]}", candidates))
    }
    @Test fun fabricatedAndDuplicateAndWrongCountSelectionsAreRejected() {
        val candidates = CompanionExamplesPolicy.candidates(turn(1) + turn(2))
        for (json in listOf("{\"ids\":[\"a1\"]}", "{\"ids\":[\"a1\",\"a1\"]}", "{\"ids\":[\"a1\",\"unknown\"]}")) {
            assertTrue(runCatching { CompanionExamplesPolicy.selected(json, candidates) }.isFailure)
        }
    }
    @Test fun manualEditsRoundTripAndEmptyReferencesStayEmpty() {
        val examples = listOf(CompanionExample("你好？", "回来啦，今天过得好吗？"))
        assertEquals(examples, CompanionExamplesPolicy.read(CompanionExamplesPolicy.encode(examples)))
        assertEquals("", CompanionExamplesPolicy.reference(emptyList(), "小满"))
        assertTrue(CompanionExamplesPolicy.reference(examples, "小满").contains("小满：回来啦"))
    }
    @Test fun malformedStorageDoesNotProduceAnEmptyCard() {
        assertTrue(CompanionExamplesPolicy.read("bad-json").isEmpty())
        assertTrue(CompanionExamplesPolicy.read("[{}]").isEmpty())
    }
    @Test fun invitationAppearsAtTwentyRoundsOnlyForWaitingNewRoles() {
        val new = CompanionExamplesEntity("s", "waiting")
        assertEquals("waiting", CompanionExamplesPolicy.offer(new, 19).state)
        assertEquals("offered", CompanionExamplesPolicy.offer(new, 20).state)
        for (state in listOf("declined", "selecting", "active", "failed", "disabled"))
            assertEquals(state, CompanionExamplesPolicy.offer(new.copy(state = state), 50).state)
    }
    @Test fun editorIsHiddenUntilOptInAndRemainsVisibleAfterStoppingOrDeletingAllPairs() {
        assertFalse(CompanionExamplesPolicy.editorVisible(null))
        for (state in listOf("waiting", "offered", "declined", "disabled"))
            assertFalse(CompanionExamplesPolicy.editorVisible(CompanionExamplesEntity("s", state)))
        for (state in listOf("selecting", "active", "failed", "disabled"))
            assertTrue(CompanionExamplesPolicy.editorVisible(CompanionExamplesEntity("s", state, optedIn = true)))
    }
    @Test fun declineAndClosePermanentlySuppressFutureOffers() {
        val declined = CompanionExamplesPolicy.decline(CompanionExamplesEntity("s", "offered"))
        assertEquals("declined", declined.state)
        assertEquals(declined, CompanionExamplesPolicy.offer(declined, 200))
        assertEquals(declined, CompanionExamplesPolicy.decline(declined))
    }
    @Test fun autoStopKeepsEditableExamplesAndCreatesOnlyOneNoticeAnchor() {
        val json = CompanionExamplesPolicy.encode(listOf(CompanionExample("你好呀", "今天过得怎么样呢？")))
        val stopped = CompanionExamplesPolicy.stop(CompanionExamplesEntity("s", "active", json), "a20")
        assertEquals("disabled", stopped.state)
        assertEquals(json, stopped.pairsJson)
        assertEquals("a20", stopped.noticeAfterId)
        assertEquals(stopped, CompanionExamplesPolicy.stop(stopped, "a21"))
    }
    @Test fun oldFullDeclinedOrEmptyRolesDoNotProduceSpuriousNotices() {
        for (state in listOf("waiting", "offered", "selecting", "declined", "failed", "disabled", "active"))
            assertNull(CompanionExamplesPolicy.stop(CompanionExamplesEntity("s", state), "a20").noticeAfterId)
    }
    @Test fun rawWindowFillsByActualSelectionOrTokenBoundary() {
        val history = turn(1) + turn(2)
        val cost = FusedCompanionPolicy.tokens(history)
        assertFalse(FusedCompanionPolicy.rawWindowFull(emptyList(), emptyList(), 1))
        assertFalse(FusedCompanionPolicy.rawWindowFull(history, history, cost + 1))
        assertTrue(FusedCompanionPolicy.rawWindowFull(history, history, cost))
        assertTrue(FusedCompanionPolicy.rawWindowFull(history, history.drop(2), cost + 1))
    }
    @Test fun onlyOptedInActiveExamplesEnterTheRequestCopy() {
        val row = CompanionExamplesEntity("s", "active", CompanionExamplesPolicy.encode(listOf(CompanionExample("原问题", "原回答"))), optedIn = true)
        assertTrue(CompanionExamplesPolicy.reference(row, "小满").contains("小满：原回答"))
        for (state in listOf("waiting", "offered", "declined", "selecting", "failed", "disabled"))
            assertEquals("", CompanionExamplesPolicy.reference(row.copy(state = state), "小满"))
        assertEquals("", CompanionExamplesPolicy.reference(row.copy(optedIn = false), "小满"))
        assertEquals("", CompanionExamplesPolicy.reference(null, "小满"))
        assertTrue(row.pairsJson.contains("原回答"))
    }
}
