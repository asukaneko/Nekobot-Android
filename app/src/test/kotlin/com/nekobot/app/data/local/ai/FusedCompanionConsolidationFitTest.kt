package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedCompanionConsolidationFitTest {
    @Test fun capacityCannotSplitTheLastWholeTurnEvenIfOneMessageWouldFit() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(listOf(2), listOf(2), 2) { target, reference, existing ->
            target + reference + existing <= 1
        }
        assertTrue(fit.needsLargerModel)
        assertEquals(0, fit.targetMessages)
    }

    @Test fun batchThatFitsStaysWhole() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(2, 2),
            referenceTurnSizes = listOf(2),
            existingCount = 3,
            fits = { _, _, _ -> true })
        assertEquals(4, fit.targetMessages)
        assertEquals(2, fit.referenceMessages)
        assertEquals(3, fit.existingCount)
        assertFalse(fit.needsLargerModel)
    }

    @Test fun overflowShrinksReferenceBeforeTheBatch() {
        val seen = mutableListOf<Triple<Int, Int, Int>>()
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1),
            referenceTurnSizes = listOf(1),
            existingCount = 0,
            fits = { target, reference, existing ->
                seen += Triple(target, reference, existing)
                target + reference <= 2
            })
        assertEquals(2, fit.targetMessages)
        assertEquals(0, fit.referenceMessages)
        assertEquals(Triple(2, 1, 0), seen.first())
        assertTrue(seen.any { it.first == 2 && it.second == 0 })
    }

    @Test fun singleMessageThatCannotFitRequiresLargerModel() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1),
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { _, _, _ -> false })
        assertEquals(0, fit.targetMessages)
        assertTrue(fit.needsLargerModel)
    }

    @Test fun severalMessagesThatCannotFitAreNotMarkedProcessed() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(2),
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { _, _, _ -> false })
        assertEquals(0, fit.targetMessages)
        assertTrue(fit.needsLargerModel)
    }

    @Test fun messageThatFitsAloneIsKeptAfterOtherPartsShrink() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1),
            referenceTurnSizes = listOf(4),
            existingCount = 6,
            fits = { target, reference, existing -> target == 1 && reference == 0 && existing == 0 })
        assertEquals(1, fit.targetMessages)
        assertEquals(0, fit.referenceMessages)
        assertEquals(0, fit.existingCount)
    }

    @Test fun tokenSizedPayloadShrinksUntilTheLimit() {
        val prompt = 2_500
        val limit = 14_000
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1, 1, 1),
            referenceTurnSizes = listOf(1, 1),
            existingCount = 3,
            fits = { target, reference, existing ->
                prompt + target * 3_000 + reference * 2_000 + existing * 500 <= limit
            })
        assertTrue(prompt + fit.targetMessages * 3_000 + fit.referenceMessages * 2_000 + fit.existingCount * 500 <= limit)
        assertTrue(fit.targetMessages >= 1)
    }

    @Test fun deferredMessageStaysPendingAfterTheRestOfItsTurnSucceeds() {
        val huge = row(1, "user", "长")
        val reply = row(2, "assistant", "答")
        val plan = FusedCompanionPolicy.planPendingConsolidation(listOf(huge, reply)) { it.id != huge.id }
        assertEquals(setOf(huge.id), plan.deferredIds)
        val pending = FusedCompanionPolicy.pendingConsolidationTurns(listOf(listOf(huge, reply)), plan.processableIds)
        assertEquals(listOf(listOf(reply)), pending)
        val second = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = pending.map { it.size },
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { target, _, _ -> target == 1 })
        assertEquals(1, second.targetMessages)
        assertFalse(second.needsLargerModel)
        // 仅成功的目标会写回执；原话队列中仍有超容量消息，下轮重新计算且保留提示。
        val unprocessed = listOf(huge, reply).filterNot { it.id in pending.flatten().map { row -> row.id } }
        val next = FusedCompanionPolicy.planPendingConsolidation(unprocessed) { it.id != huge.id }
        assertEquals(listOf(huge), unprocessed)
        assertEquals(plan.capacityNotice, next.capacityNotice)
    }

    @Test fun deferredMessageBecomesProcessableWithLargerCapacity() {
        val huge = row(1, "user", "长原话".repeat(100))
        val small = FusedCompanionPolicy.planPendingConsolidation(listOf(huge)) { it.content.length <= 100 }
        assertTrue(small.processableIds.isEmpty())
        assertTrue(small.capacityNotice!!.contains("尚未整理"))
        val larger = FusedCompanionPolicy.planPendingConsolidation(listOf(huge)) { it.content.length <= 1000 }
        assertEquals(setOf(huge.id), larger.processableIds)
        assertTrue(larger.deferredIds.isEmpty())
        assertEquals(null, larger.capacityNotice)
    }

    @Test fun allOversizedMessagesStayDeferredWithoutAnyModelTarget() {
        val rows = listOf(row(1, "user", "长"), row(2, "assistant", "长回复"))
        val plan = FusedCompanionPolicy.planPendingConsolidation(rows) { false }
        assertTrue(plan.processableIds.isEmpty())
        assertEquals(rows.map { it.id }.toSet(), plan.deferredIds)
        assertTrue(plan.capacityNotice!!.contains("有2条原话"))
    }

    @Test fun successfulNormalTargetsDoNotRemoveAnotherDeferredMessagesNotice() {
        val huge = row(1, "user", "长")
        val ordinary = listOf(row(2, "assistant", "回复"), row(3, "user", "新句"), row(4, "assistant", "新回复"))
        var pending = listOf(huge) + ordinary
        val notice = FusedCompanionPolicy.planPendingConsolidation(pending) { it != huge }.capacityNotice
        for (done in ordinary) {
            pending = pending.filterNot { it.id == done.id }
            val plan = FusedCompanionPolicy.planPendingConsolidation(pending) { it != huge }
            assertEquals(notice, plan.capacityNotice)
            assertTrue(huge.id !in plan.processableIds)
        }
    }

    @Test fun existingReferenceShrinksBeforeSplittingTargetBatch() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(listOf(2, 2), emptyList(), 3) { target, _, existing ->
            target + existing <= 4
        }
        assertEquals(4, fit.targetMessages)
        assertEquals(0, fit.existingCount)
    }

    @Test fun lockedMemoryIsKeptWhenASmallerOrdinaryOneWouldFitInstead() {
        val rows = listOf(slot("locked", 8, protected = true), slot("small", 3, protected = false), slot("later", 3, protected = false))
        val packed = FusedCompanionPolicy.packExistingMemories(rows, { it.protected }) { kept -> kept.sumOf { it.size } <= 6 }
        assertEquals(listOf("locked"), packed.kept.map { it.id })
        assertEquals(1, packed.protectedKept)
        assertEquals(1, packed.protectedOverBudget)
        assertEquals(2, packed.skippedOrdinary)
    }

    @Test fun ordinaryMemoriesStopAtTheFirstOneThatDoesNotFit() {
        val rows = listOf(slot("a", 4, false), slot("b", 4, false), slot("c", 2, false))
        val packed = FusedCompanionPolicy.packExistingMemories(rows, { it.protected }) { kept -> kept.sumOf { it.size } <= 6 }
        assertEquals(listOf("a"), packed.kept.map { it.id })
        assertEquals(0, packed.protectedKept)
        assertEquals(2, packed.skippedOrdinary)
    }

    @Test fun protectedMemoriesComeFirstEvenIfListedAfterOrdinaryOnes() {
        val rows = listOf(slot("ordinary", 3, false), slot("core", 3, true), slot("tail", 3, false))
        val packed = FusedCompanionPolicy.packExistingMemories(rows, { it.protected }) { kept -> kept.sumOf { it.size } <= 5 }
        assertEquals(listOf("core"), packed.kept.map { it.id })
        assertEquals(1, packed.protectedKept)
        assertEquals(2, packed.skippedOrdinary)
    }

    @Test fun memoriesThatFitStayInProtectedThenOrdinaryOrder() {
        val rows = listOf(slot("note", 2, false), slot("locked", 2, true))
        val packed = FusedCompanionPolicy.packExistingMemories(rows, { it.protected }) { kept -> kept.sumOf { it.size } <= 6 }
        assertEquals(listOf("locked", "note"), packed.kept.map { it.id })
        assertEquals(0, packed.protectedOverBudget)
        assertEquals(0, packed.skippedOrdinary)
    }

    @Test fun lockedCoreAndOpenQuestionsAreProtectedFromBeingDropped() {
        assertTrue(consolidationReferenceProtected(memory("locked", locked = true), emptySet()))
        assertTrue(consolidationReferenceProtected(memory("fixed", alwaysProvide = true), emptySet()))
        assertTrue(consolidationReferenceProtected(memory("core", kind = "core"), emptySet()))
        assertTrue(consolidationReferenceProtected(memory("asked"), setOf("asked")))
        assertFalse(consolidationReferenceProtected(memory("plain"), emptySet()))
        assertFalse(consolidationReferenceProtected(memory("removed", kind = "core", coreExcluded = true), emptySet()))
    }

    private fun slot(id: String, size: Int, protected: Boolean) = MemorySlot(id, size, protected)
    private data class MemorySlot(val id: String, val size: Int, val protected: Boolean)
    private fun memory(key: String, locked: Boolean = false, kind: String = "fact", alwaysProvide: Boolean = false,
        coreExcluded: Boolean = false) = CompanionMemoryEntity(
        id = key, scopeId = "s", factKey = key, version = 1, kind = kind, subject = "用户", content = "正文",
        status = "active", locked = locked, alwaysProvide = alwaysProvide, coreExcluded = coreExcluded,
        createdAt = "now", updatedAt = "now")

    private fun row(id: Int, role: String, text: String) = LocalMessageEntity(
        id = "$id", sessionId = "s", role = role, content = text,
        timestamp = "2026-10-13T12:00:00Z", createdAt = "2026-10-13T12:00:00Z")
}
