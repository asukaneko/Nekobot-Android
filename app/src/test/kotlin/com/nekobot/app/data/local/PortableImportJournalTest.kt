package com.nekobot.app.data.local

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortableImportJournalTest {
    @get:Rule val temp = TemporaryFolder(File("build/portable-import-tests").apply { mkdirs() })
    private fun fixture(): Triple<PortableImportJournal, File, File> {
        val targets = temp.newFolder()
        val root = temp.newFolder()
        val source = temp.newFile().apply { writeText("imported synthetic bytes") }
        return Triple(PortableImportJournal(root, listOf(targets)), targets, source)
    }
    @Test fun rollbackRestoresOldFileAndRemovesOnlyTheNewFile() {
        val (journal, targets, source) = fixture()
        val old = File(targets, "old.bin").apply { writeText("original synthetic bytes") }
        val fresh = File(targets, "new.bin")
        val unrelated = File(targets, "unrelated.bin").apply { writeText("unrelated") }
        val record = journal.prepare("fixture.db", "fixture", listOf(
            PortableFileWrite("old", old, source, PortableImportJournal.hash(old)), PortableFileWrite("new", fresh, source, null)), JsonObject())
        journal.install(record)
        assertEquals(source.readText(), old.readText())
        journal.rollback(record) {}
        assertEquals("original synthetic bytes", old.readText())
        assertFalse(fresh.exists())
        assertEquals("unrelated", unrelated.readText())
    }
    @Test fun processInterruptionWithoutDatabaseReceiptRestoresFilesAndState() {
        val (journal, targets, source) = fixture()
        val target = File(targets, "a.bin").apply { writeText("before") }
        val record = journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, PortableImportJournal.hash(target))), JsonObject().apply { addProperty("fixture", "before") })
        journal.install(record)
        var restored = false
        journal.recover({ false }) { restored = it.state["fixture"].asString == "before" }
        assertTrue(restored)
        assertEquals("before", target.readText())
    }
    @Test fun processInterruptionAfterDatabaseCommitKeepsImportedFiles() {
        val (journal, targets, source) = fixture()
        val target = File(targets, "a.bin")
        val record = journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, null)), JsonObject())
        journal.install(record)
        journal.recover({ it.id == record.id }) { fail("Committed transaction must not be undone") }
        assertEquals(source.readText(), target.readText())
    }
    @Test fun previewRaceIsRejectedBeforePreparingAnyWrite() {
        val (journal, targets, source) = fixture()
        val target = File(targets, "a.bin").apply { writeText("before") }
        val expected = PortableImportJournal.hash(target)
        target.writeText("later local change")
        try { journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, expected)), JsonObject()); fail() }
        catch (_: IllegalArgumentException) { assertEquals("later local change", target.readText()) }
    }
    @Test fun cancellationBetweenFilesCanBeRolledBack() {
        val (journal, targets, source) = fixture()
        val a = File(targets, "a.bin"); val b = File(targets, "b.bin")
        val record = journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", a, source, null), PortableFileWrite("b", b, source, null)), JsonObject())
        var count = 0
        try { journal.install(record) { if (++count == 2) throw java.util.concurrent.CancellationException() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { journal.rollback(record) {} }
        assertFalse(a.exists()); assertFalse(b.exists())
    }
    @Test fun traversalCannotEscapeAllowedRoot() {
        val (journal, targets, source) = fixture()
        val escaped = File(targets, "../outside.bin")
        try { journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("x", escaped, source, null)), JsonObject()); fail() }
        catch (_: IllegalStateException) { assertFalse(escaped.exists()) }
        catch (_: IllegalArgumentException) { assertFalse(escaped.exists()) }
    }
    @Test fun twoDescriptorsCannotOverwriteTheSameTarget() {
        val (journal, targets, source) = fixture()
        val target = File(targets, "a.bin")
        try { journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, null), PortableFileWrite("b", target, source, null)), JsonObject()); fail() }
        catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
    }
    @Test fun corruptedBackupIsDetectedAndRecoveryRecordIsKept() {
        val targets = temp.newFolder(); val root = temp.newFolder(); val source = temp.newFile().apply { writeText("after") }
        val journal = PortableImportJournal(root, listOf(targets))
        val target = File(targets, "a").apply { writeText("before") }
        val record = journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, PortableImportJournal.hash(target))), JsonObject())
        journal.install(record)
        File(root, "${record.id}/0.old").writeText("corrupted")
        try { journal.rollback(record) {}; fail() } catch (_: IllegalArgumentException) {
            assertTrue(File(root, "${record.id}/journal.json").exists())
        }
    }
    @Test fun incomingFileChangedAfterPreviewIsRejectedWithoutOverwritingLocalFile() {
        val (journal, targets, source) = fixture()
        val target = File(targets, "a").apply { writeText("local") }
        val before = PortableImportJournal.hash(target)
        val incoming = PortableImportJournal.hash(source)
        source.writeText("changed staging bytes")
        try { journal.prepare("fixture.db", "fixture", listOf(PortableFileWrite("a", target, source, before, incoming)), JsonObject()); fail() }
        catch (_: IllegalArgumentException) { assertEquals("local", target.readText()) }
    }
}
