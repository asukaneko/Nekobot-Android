package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationHistoryStoreTest {

    private fun entry(id: String, createdAt: Long) = ImageHistoryEntry(
        id = id,
        prompt = "demo prompt",
        size = "1024x1024",
        modelName = "demo-model",
        mimeType = "image/png",
        fileName = "$id.png",
        createdAt = createdAt
    )

    @Test
    fun encodeDecodeRoundTripKeepsFields() {
        val original = listOf(entry("a", 1L), entry("b", 2L))
        val decoded = ImageGenerationHistoryStore.decode(ImageGenerationHistoryStore.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun decodeToleratesBlankOrBrokenJson() {
        assertTrue(ImageGenerationHistoryStore.decode(null).isEmpty())
        assertTrue(ImageGenerationHistoryStore.decode("   ").isEmpty())
        assertTrue(ImageGenerationHistoryStore.decode("{not a list}").isEmpty())
    }

    @Test
    fun decodeDropsEntriesWithoutFile() {
        val raw = """
            [
              {"id":"ok","prompt":"p","size":"1024x1024","modelName":"m","mimeType":"image/png","fileName":"ok.png","createdAt":1},
              {"id":"","prompt":"p","size":"1024x1024","modelName":"m","mimeType":"image/png","fileName":"x.png","createdAt":2},
              {"id":"nofile","prompt":"p","size":"1024x1024","modelName":"m","mimeType":"image/png","fileName":"","createdAt":3}
            ]
        """.trimIndent()

        assertEquals(listOf("ok"), ImageGenerationHistoryStore.decode(raw).map { it.id })
    }

    @Test
    fun trimKeepsNewestEntriesAndReportsRemoved() {
        val entries = listOf(entry("old", 10L), entry("new", 30L), entry("mid", 20L))

        val (kept, removed) = ImageGenerationHistoryStore.trimHistory(entries, 2)

        assertEquals(listOf("new", "mid"), kept.map { it.id })
        assertEquals(listOf("old"), removed.map { it.id })
    }

    @Test
    fun trimWithNonPositiveLimitRemovesEverything() {
        val (kept, removed) = ImageGenerationHistoryStore.trimHistory(listOf(entry("a", 1L)), 0)

        assertTrue(kept.isEmpty())
        assertEquals(listOf("a"), removed.map { it.id })
    }

    @Test
    fun extensionFollowsMimeType() {
        assertEquals("jpg", ImageGenerationHistoryStore.extensionFor("image/jpeg"))
        assertEquals("webp", ImageGenerationHistoryStore.extensionFor("IMAGE/WEBP"))
        assertEquals("gif", ImageGenerationHistoryStore.extensionFor("image/gif"))
        assertEquals("png", ImageGenerationHistoryStore.extensionFor("image/png"))
        assertEquals("png", ImageGenerationHistoryStore.extensionFor("application/octet-stream"))
    }
}
