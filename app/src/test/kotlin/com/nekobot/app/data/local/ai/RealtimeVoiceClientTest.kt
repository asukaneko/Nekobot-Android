package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceClientTest {

    @Test
    fun websocketUrlAppendsRealtimePathAndModel() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime",
            buildRealtimeWebSocketUrl(
                baseUrl = "https://api.openai.com/v1/",
                appendRealtimePath = true,
                model = "gpt-realtime"
            )
        )
    }

    @Test
    fun websocketUrlPreservesCustomQueryWithoutDuplicatingModel() {
        assertEquals(
            "wss://proxy.example/v1/realtime?region=cn&model=gpt-realtime-2025-08-28",
            buildRealtimeWebSocketUrl(
                baseUrl = "wss://proxy.example/v1/realtime?region=cn&model=old",
                appendRealtimePath = true,
                model = "gpt-realtime-2025-08-28"
            )
        )
    }

    @Test
    fun sessionUpdateRequestsAudioWithTranscriptAndPcm24k() {
        val event = buildRealtimeSessionUpdate(
            config = RealtimeModelConfig(
                id = "live",
                name = "Live",
                apiKey = "test",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-realtime",
                voice = "marin",
                transcriptionModel = "gpt-4o-mini-transcribe",
                language = "zh"
            ),
            instructions = "保持角色设定"
        )
        val session = event.getAsJsonObject("session")
        val audio = session.getAsJsonObject("audio")
        val input = audio.getAsJsonObject("input")
        val output = audio.getAsJsonObject("output")

        assertEquals("session.update", event.get("type").asString)
        assertEquals("realtime", session.get("type").asString)
        assertEquals("audio", session.getAsJsonArray("output_modalities")[0].asString)
        assertEquals("audio/pcm", input.getAsJsonObject("format").get("type").asString)
        assertEquals(24_000, input.getAsJsonObject("format").get("rate").asInt)
        assertTrue(input.get("turn_detection").isJsonNull)
        assertEquals("gpt-4o-mini-transcribe", input.getAsJsonObject("transcription").get("model").asString)
        assertEquals("marin", output.get("voice").asString)
    }

    @Test
    fun contextEventsUseInputTextForUserAndOutputTextForAssistant() {
        val events = buildRealtimeContextEvents(
            listOf(
                RealtimeContextMessage("user", "你好"),
                RealtimeContextMessage("assistant", "晚上好")
            )
        )

        val userItem = events[0].getAsJsonObject("item")
        val assistantItem = events[1].getAsJsonObject("item")
        assertEquals("input_text", userItem.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
        assertEquals("output_text", assistantItem.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun contextTrimmingKeepsNewestTurns() {
        val trimmed = trimRealtimeContext(
            listOf(
                RealtimeContextMessage("user", "old"),
                RealtimeContextMessage("assistant", "newest")
            ),
            maxChars = 6
        )

        assertEquals(1, trimmed.size)
        assertEquals("newest", trimmed.single().content)
        assertFalse(trimmed.any { it.content == "old" })
    }
}
