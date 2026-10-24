package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CompanionMemoryInteractionsTest {
    private val scope = CompanionScopeEntity("scope", "test", "s", "fictional", 0, updatedAt = "now")
    private val raw = LocalMessageEntity("u", "s", "user", "完整的虚构原消息", timestamp = "2026-10-23", createdAt = "2026-10-23")
    private fun memory(id: String) = CompanionMemoryEntity(id, "scope", id, 1, "fact", "虚构角色", "记忆$id", "active", createdAt = "now", updatedAt = "now")
    private val review = CompanionLockedReviewEntity("r", "scope", "s", "locked", "confirmed", "2026-10-23")
    private class Fixture(row: LocalMessageEntity, initial: List<CompanionMemoryEntity>) {
        var raw = row
        var resolution: String? = null
        val memories = initial.associateBy { it.id }.toMutableMap()
        val sources = initial.associate { it.id to CompanionSourceEntity(it.id, row.id, CompanionMemoryPolicy.fingerprint(row), "片段", "2026-10-23", "user") }.toMutableMap()
        var receipt: CompanionReceiptEntity? = CompanionReceiptEntity("scope", row.id, CompanionMemoryPolicy.fingerprint(row))
        @Suppress("UNCHECKED_CAST")
        val dao = Proxy.newProxyInstance(CompanionMemoryDao::class.java.classLoader, arrayOf(CompanionMemoryDao::class.java)) { _, method, args ->
            val a = args!!
            when (method.name) {
                "memory" -> memories[a[0]]
                "versions" -> memories.values.filter { it.scopeId == a[0] && it.factKey == a[1] }
                "saveMemory" -> { val m = a[0] as CompanionMemoryEntity; memories[m.id] = m; Unit }
                "resolveLockedReview" -> { resolution = a[1] as String; Unit }
                "memoriesBySourceMessages" -> memories.values.filter { it.current && !it.voided && sources[it.id]?.messageId in a[0] as List<String> }
                "sources" -> listOfNotNull(sources[a[0]])
                "saveSources" -> { (a[0] as List<CompanionSourceEntity>).forEach { sources[it.memoryId] = it }; Unit }
                "voidMemory" -> { val m = memories.getValue(a[0] as String); memories[m.id] = m.copy(current = false, voided = true, voidedReason = a[1] as String); Unit }
                "saveReceipts" -> { receipt = (a[0] as List<CompanionReceiptEntity>).single(); Unit }
                "removeReceipts" -> { receipt = null; Unit }
                "bumpRevision" -> Unit
                else -> error(method.name)
            }
        } as CompanionMemoryDao
        val service = CompanionSourceMutation(dao, { block ->
            val r = raw; val m = memories.toMap(); val s = sources.toMap(); val receiptBefore = receipt
            try { block() } catch (e: Exception) {
                raw = r; memories.clear(); memories.putAll(m); sources.clear(); sources.putAll(s); receipt = receiptBefore; throw e
            }
        }, { raw.takeIf { row -> row.id == it } })
    }
    @Test fun noAttachmentsChangesImmediatelyButAttachedRequiresExplicitChoice() = runBlocking {
        val plain = Fixture(raw, emptyList()); plain.service.mutate(scope, listOf(raw), null) { plain.raw = raw.copy(deleted = true) }
        assertTrue(plain.raw.deleted)
        val linked = Fixture(raw, listOf(memory("a"), memory("b")))
        try { linked.service.mutate(scope, listOf(raw), null) { linked.raw = raw.copy(deleted = true) }; fail() }
        catch (e: MemoryAttachmentDecisionRequired) { assertEquals(2, e.attached.size) }
        assertEquals(raw, linked.raw); assertTrue(linked.memories.values.none { it.voided })
    }
    @Test fun deletingTogetherVoidsBothMemoriesAndPreservesFullSnapshots() = runBlocking {
        val f = Fixture(raw, listOf(memory("a"), memory("b")))
        f.service.mutate(scope, listOf(raw), false) { f.raw = raw.copy(deleted = true) }
        assertTrue(f.memories.values.all { it.voided && it.voidedReason == "source_deleted" })
        assertTrue(f.sources.values.all { it.messageSnapshot == raw.content })
    }
    @Test fun keepingAfterDeleteLeavesMemoriesRecallableAndShowsDeletedSourceSnapshot() = runBlocking {
        val f = Fixture(raw, listOf(memory("a")))
        f.service.mutate(scope, listOf(raw), true) { f.raw = raw.copy(deleted = true) }
        assertEquals(1, CompanionReferences.current(f.memories.values.toList()).size)
        val source = CompanionMemoryInteractionPolicy.source(f.sources.getValue("a"), f.raw)
        assertTrue(source.originalDeleted); assertEquals(raw.content, source.content)
    }
    @Test fun editKeepRefreshesReceiptWhileDeleteChoiceRemovesReceipt() = runBlocking {
        for (keep in listOf(true, false)) {
            val f = Fixture(raw, listOf(memory("a")))
            f.service.mutate(scope, listOf(raw), keep, raw.id) { f.raw = raw.copy(content = "修改后的原话") }
            if (keep) {
                assertEquals(CompanionMemoryPolicy.fingerprint(f.raw), f.receipt?.fingerprint)
                assertFalse(f.memories.getValue("a").voided)
            } else { assertNull(f.receipt); assertEquals("source_edited", f.memories.getValue("a").voidedReason) }
            assertEquals(raw.content, CompanionMemoryInteractionPolicy.source(f.sources.getValue("a"), f.raw).content)
        }
    }
    @Test fun sourceChangesDuringDecisionAreRejectedAndMutationFailureRollsBackAllEffects() = runBlocking {
        val f = Fixture(raw, listOf(memory("a")))
        f.raw = raw.copy(content = "别处修改")
        try { f.service.mutate(scope, listOf(raw), false) { fail() }; fail() } catch (_: IllegalArgumentException) { }
        f.raw = raw
        try { f.service.mutate(scope, listOf(raw), false) { f.raw = raw.copy(deleted = true); error("模拟失败") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(raw, f.raw); assertFalse(f.memories.getValue("a").voided); assertNull(f.sources.getValue("a").messageSnapshot)
    }
    @Test fun editRegenerateCollectsEntireTailInOneDecision() {
        val history = (1..5).map { raw.copy(id = "$it") }
        assertEquals(listOf("2", "3", "4", "5"), CompanionMemoryInteractionPolicy.affected(history, "2", true).map { it.id })
        assertEquals(listOf(history[1]), CompanionMemoryInteractionPolicy.affected(history, "2", false))
    }
    @Test fun newAttachmentAfterUiRecheckStillRequiresAChoiceInsideTheTransaction() = runBlocking {
        val f = Fixture(raw, listOf(memory("shown"), memory("new")))
        for (keep in listOf(false, true)) {
            try {
                f.service.mutate(scope, listOf(raw), keep, expectation = CompanionSourceExpectation(listOf(raw.id), setOf("shown"))) { fail("不应提交") }
                fail("必须重新选择")
            } catch (e: MemoryAttachmentDecisionRequired) { assertEquals(2, e.attached.size) }
            assertEquals(raw, f.raw); assertTrue(f.memories.values.none { it.voided })
            assertTrue(f.sources.values.all { it.messageSnapshot == null })
        }
    }
    @Test fun tailChangedAfterUiRecheckCannotCommitAnUnseenScope() = runBlocking {
        val f = Fixture(raw, listOf(memory("shown")))
        try {
            f.service.mutate(scope, listOf(raw), false,
                expectation = CompanionSourceExpectation(listOf(raw.id, "removed-tail"), setOf("shown"))) { fail("不应提交") }
            fail("必须重新选择")
        } catch (_: MemoryAttachmentDecisionRequired) { }
        assertEquals(raw, f.raw); assertFalse(f.memories.getValue("shown").voided)
    }
    @Test fun sourceViewUsesFullLiveTextAndFallsBackToSnapshotAfterPhysicalDeletion() {
        val source = CompanionSourceEntity("m", "u", CompanionMemoryPolicy.fingerprint(raw), "片段", "today", "user", raw.content)
        assertEquals(raw.content, CompanionMemoryInteractionPolicy.source(source, raw).content)
        assertEquals(raw.content, CompanionMemoryInteractionPolicy.source(source, null).content)
    }
    @Test fun editedDeletedUnlockedOrVoidedConfirmedMemoryMakesReviewObsolete() {
        val locked = memory("locked").copy(locked = true); val confirmed = memory("confirmed")
        assertFalse(CompanionMemoryInteractionPolicy.obsolete(review, locked, confirmed))
        for (row in listOf(null, locked.copy(current = false), locked.copy(voided = true), locked.copy(locked = false), locked.copy(valid = false))) {
            assertTrue(CompanionMemoryInteractionPolicy.obsolete(review, row, confirmed))
        }
        assertTrue(CompanionMemoryInteractionPolicy.obsolete(review, locked, confirmed.copy(voided = true)))
    }
    @Test fun reviewsWaitForForegroundIdleSavedAndFinishedThenShowOldestOne() {
        val q = CompanionLockedReviewQueue()
        val ready = CompanionLockedReviewQueue.State("s", true, false, false, true, true, "turn1")
        val list = listOf(review.copy(id = "new", createdAt = "2026-10-24"), review)
        for (state in listOf(ready.copy(sessionId = "other"), ready.copy(foreground = false), ready.copy(generating = true),
            ready.copy(summarizing = true), ready.copy(replySaved = false), ready.copy(generationFinished = false))) assertNull(q.next(state, list))
        assertEquals("r", q.next(ready, list)?.id)
        q.dismiss("r", ready.boundary)
        assertNull(q.next(ready, list))
        assertEquals("r", q.next(ready.copy(boundary = "turn2"), list)?.id)
        for (resolution in listOf("edit", "delete", "keep", "obsolete")) {
            assertNull(q.next(ready, listOf(review.copy(resolvedAt = "now", resolution = resolution))))
        }
    }
    @Test fun reviewEditDeleteAndKeepResolveOnceWithoutChangingConfirmedMemory() = runBlocking {
        for (choice in listOf("edit", "delete", "keep")) {
            val locked = memory("locked").copy(locked = true, manual = true)
            val confirmed = memory("confirmed")
            val f = Fixture(raw, listOf(locked, confirmed)); var edits = 0
            CompanionMemoryRevisionActions.resolve(f.dao, CompanionLockedReviewItem(review, locked, confirmed), choice, "新说法") { original, content ->
                edits++; assertTrue(original.locked); assertEquals("新说法", content)
            }
            assertEquals(choice, f.resolution)
            assertEquals(confirmed, f.memories[confirmed.id])
            assertEquals(if (choice == "edit") 1 else 0, edits)
            assertEquals(choice == "delete", f.memories.getValue("locked").voided)
            if (choice == "delete") assertEquals("review_deleted", f.memories.getValue("locked").voidedReason)
        }
    }
    @Test fun restoringVoidedVersionRetiresCurrentVersionAndKeepsBothRows() = runBlocking {
        val old = memory("old").copy(factKey = "job", current = false, voided = true, voidedReason = "update")
        val current = memory("new").copy(factKey = "job", version = 2)
        val f = Fixture(raw, listOf(old, current))
        CompanionMemoryRevisionActions.restore(f.dao, "scope", old.id)
        assertEquals(2, f.memories.size)
        assertEquals(listOf(old.id), CompanionReferences.current(f.memories.values.toList()).map { it.id })
        assertEquals("user", f.memories.getValue(current.id).voidedReason)
        assertNull(f.memories.getValue(old.id).voidedReason)
        try { CompanionMemoryRevisionActions.restore(f.dao, "other-scope", current.id); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun manualDeleteKeepsFullBodySourcesAndAllVersionsForRestore() = runBlocking {
        val old = memory("old").copy(factKey = "job", current = false, voided = true, voidedReason = "update")
        val current = memory("new").copy(factKey = "job", version = 2, manual = true, locked = true)
        val f = Fixture(raw, listOf(old, current)); val sources = f.sources.toMap()
        CompanionMemoryRevisionActions.delete(f.dao, "scope", "job")
        assertEquals(2, f.memories.size); assertEquals(old, f.memories[old.id])
        assertEquals(current.content, f.memories[current.id]!!.content)
        assertEquals("user", f.memories[current.id]!!.voidedReason)
        assertEquals(sources, f.sources)
        assertTrue(CompanionReferences.current(f.memories.values.toList()).isEmpty())
        CompanionMemoryRevisionActions.restore(f.dao, "scope", current.id)
        assertEquals(listOf(current.id), CompanionReferences.current(f.memories.values.toList()).map { it.id })
    }
}
