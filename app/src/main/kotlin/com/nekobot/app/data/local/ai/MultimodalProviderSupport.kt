package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity

/** TTS、STT、图片生成共用的 provider 解析与端点工具。 */
internal object MultimodalProviderSupport {
    fun ttsProvider(model: LocalAiModelEntity): String =
        firstNonBlank(model.ttsProvider, model.provider, model.protocol)

    fun sttProvider(model: LocalAiModelEntity): String =
        firstNonBlank(model.sttProvider, model.provider, model.protocol)

    fun imageProvider(model: LocalAiModelEntity): String =
        firstNonBlank(model.provider, model.protocol)

    fun endpoint(
        baseUrl: String,
        suffix: String,
        defaultUrl: String,
        appendBaseUrlPath: Boolean = true
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return defaultUrl
        if (base.endsWith(suffix)) return base
        if (!appendBaseUrlPath) return base
        return "$base/${suffix.trimStart('/')}"
    }

    fun imageAspectRatio(size: String): String {
        val parts = size.lowercase().split('x', '*')
        val width = parts.getOrNull(0)?.toFloatOrNull() ?: return "1:1"
        val height = parts.getOrNull(1)?.toFloatOrNull() ?: return "1:1"
        if (width <= 0f || height <= 0f) return "1:1"
        val ratio = width / height
        val candidates = listOf(
            1f to "1:1", 4f / 3f to "4:3", 3f / 4f to "3:4",
            16f / 9f to "16:9", 9f / 16f to "9:16", 3f / 2f to "3:2", 2f / 3f to "2:3"
        )
        return candidates.minBy { kotlin.math.abs(it.first - ratio) }.second
    }

    fun dashscopeEndpoint(
        baseUrl: String,
        suffix: String,
        defaultUrl: String,
        appendBaseUrlPath: Boolean = true
    ): String {
        val normalized = baseUrl.trim().trimEnd('/').replace("/compatible-mode/v1", "/api/v1")
        return endpoint(normalized, suffix, defaultUrl, appendBaseUrlPath)
    }

    /** Gemini / Qwen 部分接口返回裸 PCM，统一封装为可播放的 WAV。 */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 24_000, channels: Int = 1): ByteArray {
        if (pcm.size >= 12 && pcm.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) return pcm
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun writeAscii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun writeLe(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }
        fun writeLeShort(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
        writeAscii("RIFF")
        writeLe(36 + pcm.size)
        writeAscii("WAVEfmt ")
        writeLe(16)
        writeLeShort(1)
        writeLeShort(channels)
        writeLe(sampleRate)
        writeLe(byteRate)
        writeLeShort(blockAlign)
        writeLeShort(16)
        writeAscii("data")
        writeLe(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()?.lowercase().orEmpty()
}
