package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionMemoryReplacementTest {
    private fun row(id: String, key: String = "job", version: Int = 1, current: Boolean = true) =
        CompanionMemoryEntity(id, "scope", key, version, "fact", "用户", "我记得他换了工作。", "active",
            current = current, createdAt = "2026-10-17", updatedAt = "2026-10-17")
    @Test fun replacementTargetsOnlyTheSameScopeAndExactKey() {
        val rows = listOf(row("old"), row("new", version = 2), row("similar", "job_detail"), row("other-scope").copy(scopeId = "other"))
        assertEquals(listOf("old"), CompanionMemoryReplacement.replacedIds(rows, "scope", "job", "new"))
    }
    @Test fun manualLocksCannotBeDeleted() {
        assertEquals(emptyList<String>(), CompanionMemoryReplacement.replacedIds(listOf(row("protected").copy(locked = true)), "scope", "job"))
    }
    @Test fun liveReplacementPreservesEqualNewerAndUnresolvedVersions() {
        val rows = listOf(row("old"), row("new", version = 2), row("same", version = 2),
            row("later", version = 3), row("conflict").copy(status = "conflict"),
            row("locked-conflict").copy(status = "locked_conflict"))
        assertEquals(listOf("old"), CompanionMemoryReplacement.replacedIds(rows, "scope", "job", "new"))
    }
    @Test fun missingInvalidOrForeignReplacementCannotDeleteAnything() {
        val old = row("old")
        for (replacement in listOf(row("new", version = 2).copy(valid = false),
            row("new", version = 2).copy(current = false), row("new", version = 2).copy(scopeId = "other"),
            row("new", version = 2).copy(status = "conflict"))) {
            assertTrue(CompanionMemoryReplacement.replacedIds(listOf(old, replacement), "scope", "job", "new").isEmpty())
        }
        assertTrue(CompanionMemoryReplacement.replacedIds(listOf(old), "scope", "job", "missing").isEmpty())
    }
    @Test fun importedConfirmedReplacementDropsOnlyOlderVersions() {
        val rows = listOf(row("old", current = false), row("new", version = 2), row("unrelated", "hobby"))
        assertEquals(setOf("new", "unrelated"), CompanionMemoryReplacement.imported(rows).map { it.id }.toSet())
    }
    @Test fun unrelatedCategoryOrIdenticalTextDoesNotCauseDeletion() {
        val rows = listOf(row("a", "job"), row("b", "hobby"), row("c", "other").copy(category = "episode"))
        assertEquals(rows.toSet(), CompanionMemoryReplacement.imported(rows).toSet())
    }
    @Test fun unresolvedConflictDoesNotReplaceCurrentMemory() {
        val rows = listOf(row("old"), row("question", version = 2, current = false).copy(status = "conflict"))
        assertEquals(rows.toSet(), CompanionMemoryReplacement.imported(rows).toSet())
    }
    @Test fun importedRetractionRemovesOnlyItsOwnOldFact() {
        val rows = listOf(row("old", current = false), row("retracted", version = 2, current = false).copy(status = "retracted"), row("other", "hobby"))
        assertEquals(listOf("other"), CompanionMemoryReplacement.imported(rows).map { it.id })
    }
    @Test fun invalidNewVersionCannotReplaceOldFact() {
        val rows = listOf(row("old"), row("invalid", version = 2).copy(valid = false))
        assertEquals(rows.toSet(), CompanionMemoryReplacement.imported(rows).toSet())
    }
    @Test fun importedProtectedHistoryRemainsProtected() {
        val rows = listOf(row("old", current = false).copy(locked = true), row("new", version = 2))
        assertEquals(rows.toSet(), CompanionMemoryReplacement.imported(rows).toSet())
    }
    @Test fun equalVersionNumbersAreNotEvidenceThatOneEntryReplacesTheOther() {
        val rows = listOf(row("first"), row("second"))
        assertEquals(rows.toSet(), CompanionMemoryReplacement.imported(rows).toSet())
    }
}
