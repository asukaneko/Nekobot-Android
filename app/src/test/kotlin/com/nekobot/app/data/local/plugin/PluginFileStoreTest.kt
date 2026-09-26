package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** 插件私有文件目录：命名安全、唯一化、配额与越界防护。 */
class PluginFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = PluginFileStore(temp.newFolder())

    @Test
    fun saveStreamSanitizesNameAndWritesContent() {
        val store = store()
        val entry = store.saveStream(
            "demo.notes",
            "../../evil?.txt",
            ByteArrayInputStream("hi".toByteArray())
        )
        assertEquals("evil_.txt", entry.name)
        assertEquals(2L, entry.size)
        assertEquals("hi", store.resolve("demo.notes", entry.name)?.readText())
    }

    @Test
    fun duplicateNamesGetSuffix() {
        val store = store()
        val first = store.saveStream("demo.notes", "note.txt", ByteArrayInputStream("a".toByteArray()))
        val second = store.saveStream("demo.notes", "note.txt", ByteArrayInputStream("b".toByteArray()))
        assertEquals("note.txt", first.name)
        assertEquals("note-1.txt", second.name)
    }

    @Test
    fun resolveRejectsUnsafeNames() {
        val store = store()
        store.saveStream("demo.notes", "note.txt", ByteArrayInputStream("a".toByteArray()))
        listOf("../note.txt", "a/b.txt", "a\\b.txt", "..", ".", "", "a\u0000b").forEach { name ->
            assertNull("必须拒绝：$name", store.resolve("demo.notes", name))
        }
        assertNotNull(store.resolve("demo.notes", "note.txt"))
    }

    @Test
    fun listIgnoresHiddenFilesAndSortsByName() {
        val store = store()
        val dir = store.directory("demo.notes").apply { mkdirs() }
        java.io.File(dir, ".secret").writeText("hidden")
        store.saveStream("demo.notes", "b.txt", ByteArrayInputStream("b".toByteArray()))
        store.saveStream("demo.notes", "a.txt", ByteArrayInputStream("a".toByteArray()))
        assertEquals(listOf("a.txt", "b.txt"), store.list("demo.notes").map { it.name })
    }

    @Test
    fun deleteAndClearRemoveFiles() {
        val store = store()
        store.saveStream("demo.notes", "note.txt", ByteArrayInputStream("a".toByteArray()))
        assertTrue(store.delete("demo.notes", "note.txt"))
        assertFalse(store.delete("demo.notes", "note.txt"))
        store.saveStream("demo.notes", "note.txt", ByteArrayInputStream("a".toByteArray()))
        store.clear("demo.notes")
        assertTrue(store.list("demo.notes").isEmpty())
        assertFalse(store.directory("demo.notes").exists())
    }

    @Test
    fun saveStreamEnforcesFileCountLimit() {
        val store = store()
        repeat(PluginFileStore.MAX_FILES) { index ->
            store.saveStream("demo.notes", "f$index.txt", ByteArrayInputStream(ByteArray(0)))
        }
        val error = runCatching {
            store.saveStream("demo.notes", "extra.txt", ByteArrayInputStream(ByteArray(0)))
        }.exceptionOrNull()
        assertTrue(error is PluginApiException)
        assertEquals("too_many_files", (error as PluginApiException).code)
    }

    @Test
    fun saveStreamEnforcesSingleFileSizeLimit() {
        val store = store()
        val oversized = ByteArray((PluginFileStore.MAX_FILE_BYTES + 1).toInt())
        val error = runCatching {
            store.saveStream("demo.notes", "big.bin", ByteArrayInputStream(oversized))
        }.exceptionOrNull()
        assertTrue(error is PluginApiException)
        assertEquals("too_large", (error as PluginApiException).code)
        assertTrue("失败时不得留下半成品", store.list("demo.notes").isEmpty())
    }

    @Test
    fun fileNameSafetyRules() {
        assertTrue(PluginFileStore.isSafeFileName("图标 页.png"))
        assertFalse(PluginFileStore.isSafeFileName("a/b"))
        assertFalse(PluginFileStore.isSafeFileName("a\\b"))
        assertFalse(PluginFileStore.isSafeFileName(".."))
        assertFalse(PluginFileStore.isSafeFileName(""))
        assertFalse(PluginFileStore.isSafeFileName("x".repeat(PluginFileStore.MAX_NAME_CHARS + 1)))
        assertEquals("upload", PluginFileStore.sanitizeFileName("..."))
        assertEquals("a_b", PluginFileStore.sanitizeFileName("a<b"))
    }
}
