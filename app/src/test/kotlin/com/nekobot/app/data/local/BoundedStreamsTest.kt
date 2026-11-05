package com.nekobot.app.data.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader

class BoundedStreamsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactLimitCanReplaceAnExistingDownload() {
        val directory = temporary.newFolder()
        val target = directory.resolve("demo.txt").apply { writeText("old") }
        assertEquals(4L, ByteArrayInputStream("demo".toByteArray()).saveLimitedDownload(target, 4))
        assertEquals("demo", target.readText())
        assertEquals(listOf("demo.txt"), directory.listFiles()!!.map { it.name })
    }

    @Test fun overflowPreservesExistingFileAndRemovesTemporaryDownload() {
        val directory = temporary.newFolder()
        val target = directory.resolve("demo.txt").apply { writeText("old") }
        assertThrows(ContentSizeLimitException::class.java) {
            ByteArrayInputStream("oversized".toByteArray()).saveLimitedDownload(target, 4)
        }
        assertEquals("old", target.readText())
        assertEquals(listOf("demo.txt"), directory.listFiles()!!.map { it.name })
    }

    @Test fun interruptedDownloadDoesNotPublishAPartialFile() {
        val directory = temporary.newFolder()
        val target = directory.resolve("demo.txt")
        val broken = object : InputStream() {
            private var calls = 0
            override fun read(): Int = error("unused")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (calls++ > 0) throw IOException("demo interruption")
                buffer[offset] = 1
                return 1
            }
        }
        assertThrows(IOException::class.java) { broken.saveLimitedDownload(target, 10) }
        assertFalse(target.exists())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun previewReadsOnlyItsBudgetAndOneExtraCharacter() {
        var consumed = 0
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                repeat(length) { buffer[offset + it] = '字' }
                consumed += length
                return length
            }
            override fun close() = Unit
        }
        val result = reader.readTextPreview(10)
        assertEquals("字".repeat(10), result.content)
        assertTrue(result.truncated)
        assertEquals(11, consumed)
        assertFalse("demo".reader().readTextPreview(4).truncated)
    }

    @Test fun boundedReadRejectsAnOversizedInput() {
        assertEquals("demo", "demo".byteInputStream().readBytesLimited(4).toString(Charsets.UTF_8))
        assertThrows(ContentSizeLimitException::class.java) { "demo!".byteInputStream().readBytesLimited(4) }
    }
}
