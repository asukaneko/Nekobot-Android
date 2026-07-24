package com.nekobot.app.ui.screens.chat

import com.google.gson.Gson
import com.nekobot.app.data.model.UpdateMessageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTtsPresentationTest {

    @Test
    fun prepareTtsTextMatchesOriginalMarkdownCleanup() {
        val raw = """
            # 标题

            你好，**世界**！
            `inline code`
            ```kotlin
            println("不要朗读")
            ```
            > 继续说
        """.trimIndent()

        assertEquals(
            "标题\n你好，世界！\n 继续说",
            prepareTtsText(raw)
        )
    }

    @Test
    fun prepareTtsTextLimitsProviderPayloadToTwoThousandCharacters() {
        assertEquals(2000, prepareTtsText("语".repeat(2500)).length)
    }

    @Test
    fun messageAudioPersistenceUsesBackendAudioUrlField() {
        val json = Gson().toJson(UpdateMessageRequest(audioUrl = "/api/tts/audio/reply.mp3"))

        assertTrue(json.contains("\"audio_url\":\"/api/tts/audio/reply.mp3\""))
        assertFalse(json.contains("\"audioUrl\""))
    }
}
