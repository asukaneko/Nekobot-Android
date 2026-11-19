package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class TtsVoiceCatalogTest {
    @Test
    fun nativeProvidersDoNotReceiveOpenAiVoices() {
        listOf("google_gemini" to "Kore", "bigmodel" to "tongtong", "dashscope" to "Cherry",
            "minimax" to "male-qn-qingse", "mimo" to "mimo_default",
            "volcengine" to "zh_female_shuangkuaisisi_moon_bigtts").forEach { (provider, expected) ->
            val voices = TtsVoiceCatalog.voices(provider)
            assertEquals(expected, voices.first().id)
            assertFalse(voices.any { it.id == "alloy" })
            assertEquals(voices.size, voices.map { it.id }.distinct().size)
        }
    }

    @Test
    fun deepSeekUsesOfficialVoiceIds() {
        val voices = TtsVoiceCatalog.voices("deepseek")
        assertEquals(listOf("stella", "tide"), voices.map { it.id })
        assertEquals(setOf("deepseek"), voices.map { it.provider }.toSet())
        assertEquals("stella", TtsVoiceCatalog.voices("deepseek", "default").first().id)
    }

    @Test
    fun configuredCustomVoiceKeepsCaseAndIsSelectable() {
        val voices = TtsVoiceCatalog.voices("gemini", "CustomVoiceID")
        assertEquals("CustomVoiceID", voices.first().id)
        assertEquals("gemini", voices.first().provider)
        assertTrue(voices.any { it.id == "Kore" })
        assertEquals(1, TtsVoiceCatalog.voices("gemini", "Kore").count { it.id == "Kore" })
        assertFalse(TtsVoiceCatalog.voices("gemini", "default").any { it.id == "default" })
    }
}
