package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeepSeekVoiceToolsTest {

    @Test
    fun endpointAcceptsDomainOrV1OrFullPath() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/tts",
            DeepSeekVoiceTools.endpoint("https://api.deepseek.com", "", "tts")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            DeepSeekVoiceTools.endpoint("https://api.deepseek.com/v1/", "", "asr")
        )
        assertEquals(
            "https://gateway.example.com/v1/tools/asr",
            DeepSeekVoiceTools.endpoint("", "https://gateway.example.com/v1/tools/asr", "asr")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            DeepSeekVoiceTools.endpoint("", "", "asr")
        )
    }

    @Test
    fun billingLengthCountsCjkAsTwo() {
        assertEquals(4, DeepSeekVoiceTools.billingLength("你好"))
        assertEquals(6, DeepSeekVoiceTools.billingLength("hi你好"))
    }

    @Test
    fun voiceFallsBackToOfficialDefault() {
        assertEquals("stella", DeepSeekVoiceTools.ttsVoice("stella"))
        assertEquals("stella", DeepSeekVoiceTools.ttsVoice("default"))
        assertEquals("stella", DeepSeekVoiceTools.ttsVoice(""))
        assertEquals("tide", DeepSeekVoiceTools.ttsVoice("tide"))
    }

    @Test
    fun longTextIsSplitIntoBillableChunks() {
        val text = "这是一句用来测试切分的长文本。".repeat(400)
        val chunks = DeepSeekVoiceTools.ttsChunks(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { DeepSeekVoiceTools.billingLength(it) <= 4_000 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun shortTextStaysInOneChunk() {
        assertEquals(listOf("你好，我是 DeepSeek。"), DeepSeekVoiceTools.ttsChunks("  你好，我是 DeepSeek。\n"))
        assertTrue(DeepSeekVoiceTools.ttsChunks("   ").isEmpty())
    }

    @Test
    fun longWavIsSplitBelowServiceLimit() {
        val seconds = 130
        val pcm = ByteArray(seconds * 16_000 * 2)
        val chunks = DeepSeekVoiceTools.asrWavChunks(wav(pcm, 16_000, 1))

        assertEquals(2, chunks.size)
        chunks.forEach { chunk ->
            assertEquals("RIFF", String(chunk, 0, 4, Charsets.US_ASCII))
            val frames = (chunk.size - 44) / 2
            assertTrue(frames <= 110 * 16_000)
        }
    }

    @Test
    fun stereoIsDownmixedToMono() {
        val frames = 16_000
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putShort(1_000)
                putShort(2_000)
            }
        }.array()

        val chunk = DeepSeekVoiceTools.asrWavChunks(wav(pcm, 16_000, 2)).single()

        assertEquals(16_000, readIntLe(chunk, 24))
        assertEquals(1, readShortLe(chunk, 22))
        assertEquals(1_500, readShortLe(chunk, 44))
    }

    @Test
    fun non16kInputIsResampled() {
        val frames = 32_000
        val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort(1_000) }
        }.array()

        val chunk = DeepSeekVoiceTools.asrWavChunks(wav(pcm, 32_000, 1)).single()

        assertEquals(16_000, readIntLe(chunk, 24))
        assertEquals(16_000, (chunk.size - 44) / 2)
        assertEquals(1_000, readShortLe(chunk, 44))
    }

    private fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
