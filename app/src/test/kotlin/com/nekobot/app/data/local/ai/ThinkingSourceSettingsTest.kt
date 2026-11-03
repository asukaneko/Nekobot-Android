package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingSourceSettingsTest {

    @Test
    fun `default config uses built-in thinking fields`() {
        assertEquals(
            listOf("reasoning_content", "thinking_content", "reasoning", "thinking"),
            ThinkingSourceConfig.DEFAULT_FIELDS
        )
        assertEquals(ThinkingSourceConfig.DEFAULT_FIELDS, ThinkingSourceConfig.DEFAULT.fields)
        assertEquals("", ThinkingSourceConfig.DEFAULT.markerStart)
        assertEquals("", ThinkingSourceConfig.DEFAULT.markerEnd)
        assertFalse(ThinkingSourceConfig.DEFAULT.markerEnabled)
    }

    @Test
    fun `current falls back to default without app initialization`() {
        ThinkingSourceSettings.install(null)
        assertEquals(ThinkingSourceConfig.DEFAULT, ThinkingSourceSettings.current())
    }

    @Test
    fun `installed resolver is used`() {
        val custom = ThinkingSourceConfig(
            fields = listOf("custom_reasoning"),
            markerStart = "<thinking>",
            markerEnd = "</thinking>"
        )
        try {
            ThinkingSourceSettings.install { custom }
            assertEquals(custom, ThinkingSourceSettings.current())
        } finally {
            ThinkingSourceSettings.install(null)
        }
    }

    @Test
    fun `fields are split trimmed deduplicated and empty entries removed`() {
        assertEquals(
            listOf("reasoning_content", "thinking"),
            parseThinkingSourceFields(" reasoning_content, , thinking ,reasoning_content,thinking ")
        )
    }

    @Test
    fun `blank field input falls back to defaults`() {
        assertEquals(ThinkingSourceConfig.DEFAULT_FIELDS, parseThinkingSourceFields(""))
        assertEquals(ThinkingSourceConfig.DEFAULT_FIELDS, parseThinkingSourceFields("  , ,  "))
    }

    @Test
    fun `marker enabled only when both sides are non blank`() {
        assertFalse(ThinkingSourceConfig(markerStart = "<t>").markerEnabled)
        assertFalse(ThinkingSourceConfig(markerEnd = "</t>").markerEnabled)
        assertTrue(ThinkingSourceConfig(markerStart = "<t>", markerEnd = "</t>").markerEnabled)
    }

    @Test
    fun `field thinking wins over markers`() {
        val config = ThinkingSourceConfig(markerStart = "<think>", markerEnd = "</think>")
        val (content, thinking) = extractThinkingFromMarkedContent(
            config,
            "<think>from marker</think>reply",
            "from field"
        )
        assertEquals("<think>from marker</think>reply", content)
        assertEquals("from field", thinking)
    }

    @Test
    fun `markers extract thinking when fields returned nothing`() {
        val config = ThinkingSourceConfig(markerStart = "<think>", markerEnd = "</think>")
        val (content, thinking) = extractThinkingFromMarkedContent(
            config,
            "<think>from marker</think>reply",
            ""
        )
        assertEquals("reply", content)
        assertEquals("from marker", thinking)
    }

    @Test
    fun `unmatched marker keeps reply untouched`() {
        val config = ThinkingSourceConfig(markerStart = "<think>", markerEnd = "</think>")
        val (content, thinking) = extractThinkingFromMarkedContent(
            config,
            "plain reply",
            ""
        )
        assertEquals("plain reply", content)
        assertEquals("", thinking)
    }

    @Test
    fun `strip helper removes marker from already extracted reply`() {
        val config = ThinkingSourceConfig(markerStart = "<think>", markerEnd = "</think>")
        assertEquals("reply", stripThinkingMarkers(config, "<think>t</think>reply"))
        assertEquals("plain", stripThinkingMarkers(config, "plain"))
    }
}
