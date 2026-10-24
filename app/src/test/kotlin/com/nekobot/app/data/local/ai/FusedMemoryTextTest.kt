package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class FusedMemoryTextTest {
    @Test fun everyNewWorkflowErrorHasAllFiveLanguagesAndTraditionalAliases() {
        for (message in FusedMemoryText.entries) {
            val translations = listOf("zh-CN", "zh-TW", "en-US", "ja-JP", "ko-KR").map(message::value)
            assertTrue(message.name, translations.all { it.isNotBlank() })
            assertEquals(message.name, 5, translations.distinct().size)
            assertEquals(message.value("zh-TW"), message.value("zh-Hant-HK"))
            assertEquals(message.value("zh-CN"), message.value("und"))
        }
    }
}
