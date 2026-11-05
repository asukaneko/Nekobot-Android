package com.nekobot.app.data.local.knowledge

import com.nekobot.app.data.local.ContentSizeLimitException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalKnowledgeDocumentImporterTest {
    @Test fun importsOrdinaryDocxAndSortsEpubChapters() {
        val docx = archive { entry("word/document.xml", "<document><p>demo text</p></document>") }
        assertEquals("demo text", LocalKnowledgeDocumentImporter.fromBytes("demo.docx", null, docx).content)
        val epub = archive {
            entry("02.xhtml", "<p>second</p>")
            entry("01.xhtml", "<p>first</p>")
            entry("cover.bin", "demo image")
        }
        assertEquals("first\n\nsecond", LocalKnowledgeDocumentImporter.fromBytes("demo.epub", null, epub).content)
    }

    @Test fun compressedDocxCannotExpandPastTheEntryLimit() {
        val docx = archive { repeatedEntry("word/document.xml", LocalKnowledgeDocumentImporter.MAX_ARCHIVE_ENTRY_BYTES + 1) }
        assertTrue(docx.size < 25 * 1024 * 1024)
        assertThrows(ContentSizeLimitException::class.java) {
            LocalKnowledgeDocumentImporter.fromBytes("demo.docx", null, docx)
        }
    }

    @Test fun longEpubKeepsTheFirstChaptersEvenWhenZipEntriesAreReversed() {
        val epub = archive {
            entry("02.xhtml", "<p>${"b".repeat(2_000_000)}</p>")
            entry("01.xhtml", "<p>first</p>")
        }
        val content = LocalKnowledgeDocumentImporter.fromBytes("demo.epub", null, epub).content
        assertTrue(content.startsWith("first\n\nb"))
        assertEquals(2_000_000, content.length)
    }

    @Test fun skippedEntriesAlsoConsumeTheDecompressionBudget() {
        val epub = archive {
            repeat(9) { repeatedEntry("asset-$it.bin", LocalKnowledgeDocumentImporter.MAX_ARCHIVE_ENTRY_BYTES) }
            entry("demo.xhtml", "<p>demo</p>")
        }
        assertThrows(ContentSizeLimitException::class.java) {
            LocalKnowledgeDocumentImporter.fromBytes("demo.epub", null, epub)
        }
    }

    @Test fun tooManyEntriesAreRejected() {
        val epub = archive {
            repeat(LocalKnowledgeDocumentImporter.MAX_ARCHIVE_ENTRIES) { entry("folder-$it/", "") }
            entry("demo.xhtml", "<p>demo</p>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalKnowledgeDocumentImporter.fromBytes("demo.epub", null, epub)
        }
    }

    @Test fun filePickerInputIsBoundedBeforeItIsLoaded() {
        var consumed = 0L
        val input = object : InputStream() {
            override fun read(): Int = error("unused")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                return length
            }
        }
        assertThrows(ContentSizeLimitException::class.java) { LocalKnowledgeDocumentImporter.readFileBytes(input) }
        assertEquals(25L * 1024 * 1024 + 1, consumed)
    }

    private fun archive(write: ZipOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { stream -> write(stream) }
        return bytes.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.repeatedEntry(name: String, size: Int) {
        putNextEntry(ZipEntry(name))
        val buffer = ByteArray(8192) { 'x'.code.toByte() }
        var remaining = size
        while (remaining > 0) {
            val count = minOf(buffer.size, remaining)
            write(buffer, 0, count)
            remaining -= count
        }
        closeEntry()
    }
}
