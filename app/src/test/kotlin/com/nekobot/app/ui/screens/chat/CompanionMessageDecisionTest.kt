package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionMessageDecisionTest {
    private fun memory(id: String) = CompanionMemoryEntity(id, "scope", id, 1, "fact", "虚构角色", "记忆$id", "active",
        createdAt = "now", updatedAt = "now")
    private val edit = CompanionMessageChange("session", "user", "新正文", true)
    private fun state(memories: List<CompanionMemoryEntity>) = CompanionMessageDecisionState(edit, listOf("user", "reply", "later"), memories)

    @Test fun noAttachedMemoryCommitsImmediatelyWithTransactionRecheck() = runBlocking {
        val calls = mutableListOf<Pair<CompanionMessageDecisionState, Boolean?>>()
        val controller = CompanionMessageDecision({ state(emptyList()) }, { s, keep -> calls += s to keep }, { error("不应取消") })
        assertEquals(state(emptyList()), controller.start(edit))
        assertNull(controller.pending)
        assertEquals(listOf(state(emptyList()) to null), calls)
    }
    @Test fun cancelLeavesAllMessagesAndMemoriesUntouched() = runBlocking {
        var writes = 0; var cancelled = 0
        val controller = CompanionMessageDecision({ state(listOf(memory("m"))) }, { _, _ -> writes++ }, { cancelled++ })
        assertNull(controller.start(edit))
        assertEquals(0, writes)
        assertNull(controller.choose(null))
        assertNull(controller.pending)
        assertEquals(0, writes); assertEquals(1, cancelled)
    }
    @Test fun regenerationMakesOneChoiceAndOneCommitForTheWholeTail() = runBlocking {
        for (keep in listOf(false, true)) {
            val writes = mutableListOf<Pair<List<String>, Boolean?>>()
            val controller = CompanionMessageDecision({ state(listOf(memory("a"), memory("b"))) },
                { s, choice -> writes += s.messageIds to choice }, {})
            controller.start(edit)
            assertTrue(writes.isEmpty())
            assertNotNull(controller.choose(keep))
            assertNull(controller.choose(keep))
            assertEquals(listOf(listOf("user", "reply", "later") to keep), writes)
        }
    }
    @Test fun attachmentsChangedDuringConfirmationRequireAnotherChoice() = runBlocking {
        var fresh = state(listOf(memory("a"))); var writes = 0
        val controller = CompanionMessageDecision({ fresh }, { _, _ -> writes++ }, {})
        controller.start(edit)
        fresh = state(listOf(memory("a"), memory("new")))
        assertNull(controller.choose(false)); assertEquals(0, writes)
        assertEquals(fresh, controller.pending)
        assertNotNull(controller.choose(false)); assertEquals(1, writes)
    }
    @Test fun changedTailAndChangedMemoryTextAreAlsoReconfirmed() = runBlocking {
        var fresh = state(listOf(memory("a"))); var writes = 0
        val controller = CompanionMessageDecision({ fresh }, { _, _ -> writes++ }, {})
        controller.start(edit)
        fresh = fresh.copy(messageIds = fresh.messageIds + "newer")
        assertNull(controller.choose(true))
        fresh = fresh.copy(memories = listOf(memory("a").copy(content = "新修订")))
        assertNull(controller.choose(true)); assertEquals(0, writes)
    }
    @Test fun listInitiallyShowsFiveAndExpandsWithoutTruncatingBodies() {
        val memories = (1..7).map { memory("$it").copy(content = "完整原文".repeat(200)) }
        val state = state(memories)
        assertEquals(memories.take(5), state.visibleMemories(false))
        assertEquals(memories, state.visibleMemories(true))
    }
    @Test fun selectedMessagesShareOneDecisionAndCancelNeverPartiallyDeletes() = runBlocking {
        val selected = CompanionMessageChange("session", "a", selectedIds = listOf("a", "c"))
        var writes = 0
        val controller = CompanionMessageDecision({ CompanionMessageDecisionState(it, it.selectedIds, listOf(memory("m"))) },
            { _, _ -> writes++ }, {})
        controller.start(selected)
        assertEquals(listOf("a", "c"), controller.pending!!.messageIds)
        controller.choose(null)
        assertEquals(0, writes)
    }
    @Test fun failedCommitKeepsTheDecisionForRetry() = runBlocking {
        var fail = true
        val controller = CompanionMessageDecision({ state(listOf(memory("a"))) }, { _, _ -> if (fail) error("失败") }, {})
        controller.start(edit)
        assertTrue(runCatching { controller.choose(true) }.isFailure)
        assertNotNull(controller.pending)
        fail = false
        assertNotNull(controller.choose(true)); assertNull(controller.pending)
    }
}
