package com.nekobot.app.data.local.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DeepSeek 官方语音工具（`/v1/tools/asr`、`/v1/tools/tts`）的协议适配。
 *
 * 这两个接口与 chat/completions 完全独立：ASR 只接受 16bit PCM WAV 或 Ogg Opus，
 * 且没有模型、语种参数；TTS 只回 wav/pcm，且不支持语速、音调、音量。
 * 这里集中处理路径解析、音频规整和长文本切分，[LocalAiClient] 只负责发请求。
 */
internal object DeepSeekVoiceTools {
    /** 官方开放平台地址；该接口消耗开放平台余额，与网关代理的套餐额度无关。 */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val TTS_DEFAULT_VOICE = "stella"
    val TTS_VOICES = listOf("stella", "tide")

    /** 官方接口没有模型字段；这两个名字只作配置占位，避免编辑器强制用户填写模型名。 */
    const val TTS_MODEL_PLACEHOLDER = "deepseek-tts"
    const val ASR_MODEL_PLACEHOLDER = "deepseek-asr"

    /** ASR 单段上限 120 秒，留出余量后按 110 秒切片。 */
    private const val ASR_CHUNK_SECONDS = 110
    private const val ASR_SAMPLE_RATE = 16_000

    /** 单请求上限 10000 计费字符（汉字算 2 个），分片时保守取 4000。 */
    private const val TTS_CHUNK_BILLING_CHARS = 4_000

    private const val SENTENCE_END = "。！？!?；;\n"

    /** 语音工具端点：允许只填域名、填 `/v1` 或直接填完整路径。 */
    fun endpoint(baseUrl: String, customUrl: String, tool: String): String {
        val custom = customUrl.trim()
        if (custom.isNotBlank()) return custom
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.isBlank() -> "$DEFAULT_BASE_URL/tools/$tool"
            base.endsWith("/tools/$tool") -> base
            base.endsWith("/v1") -> "$base/tools/$tool"
            else -> "$base/v1/tools/$tool"
        }
    }

    fun ttsVoice(configured: String?): String =
        configured?.trim()?.takeIf { it.isNotBlank() && !it.equals("default", true) }
            ?: TTS_DEFAULT_VOICE

    /** TTS 计费字符：中日韩字符按 2 算，其余按 1 算。 */
    fun billingLength(text: String): Int = text.sumOf { if (isWideChar(it)) 2 else 1 }

    /**
     * 按单请求计费上限切分待合成文本：优先在句末标点处断开，避免一句话被硬切后语气断裂。
     */
    fun ttsChunks(text: String, maxBillingChars: Int = TTS_CHUNK_BILLING_CHARS): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        if (billingLength(normalized) <= maxBillingChars) return listOf(normalized)

        val sentences = mutableListOf<String>()
        val sentence = StringBuilder()
        for (ch in normalized) {
            sentence.append(ch)
            if (ch in SENTENCE_END) {
                sentences += sentence.toString()
                sentence.clear()
            }
        }
        if (sentence.isNotEmpty()) sentences += sentence.toString()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentCost = 0
        for (part in sentences.flatMap { hardSplit(it, maxBillingChars) }) {
            val cost = billingLength(part)
            if (current.isNotEmpty() && currentCost + cost > maxBillingChars) {
                chunks += current.toString().trim()
                current.clear()
                currentCost = 0
            }
            current.append(part)
            currentCost += cost
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    /**
     * 规整为 16kHz 单声道 16bit WAV，并按 [ASR_CHUNK_SECONDS] 秒切片。
     *
     * 16bit PCM WAV 直接在内存里下混/重采样（不依赖解码器）；
     * mp3/m4a/amr 等压缩格式交给 [AudioConverter] 解码后再走同一条路。
     */
    fun asrWavChunks(input: ByteArray): List<ByteArray> {
        require(input.isNotEmpty()) { "音频内容为空" }
        val source = parsePcm16Wav(input)
            ?: parsePcm16Wav(AudioConverter.toWav(input, ASR_SAMPLE_RATE, 1))
            ?: throw IllegalStateException("DeepSeek ASR 无法解析音频，请改用 WAV 或 Ogg Opus")
        val mono = downmixToMono(source.pcm, source.channels)
        val pcm = if (source.sampleRate == ASR_SAMPLE_RATE) mono
        else resample(mono, source.sampleRate, ASR_SAMPLE_RATE)
        require(pcm.isNotEmpty()) { "音频内容为空" }
        return splitPcm(pcm, ASR_SAMPLE_RATE * 2 * ASR_CHUNK_SECONDS)
            .map { MultimodalProviderSupport.pcmToWav(it, ASR_SAMPLE_RATE) }
    }

    private fun hardSplit(text: String, limit: Int): List<String> {
        if (billingLength(text) <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var cost = 0
        for (ch in text) {
            val weight = if (isWideChar(ch)) 2 else 1
            if (cost + weight > limit) {
                parts += current.toString()
                current.clear()
                cost = 0
            }
            current.append(ch)
            cost += weight
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun splitPcm(pcm: ByteArray, chunkBytes: Int): List<ByteArray> {
        if (pcm.size <= chunkBytes) return listOf(pcm)
        return buildList {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunkBytes, pcm.size)
                add(pcm.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    /** 双声道取左右平均下混；与 ffmpeg `-ac 1` 的行为一致。 */
    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return pcm
        val frames = pcm.size / (channels * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val left = buffer.short.toInt()
            val right = buffer.short.toInt()
            output.putShort(((left + right) / 2).toShort())
        }
        return output.array()
    }

    /** 线性插值重采样（16bit 单声道）；只用于把非 16k 的 WAV 对齐到 ASR 采样率。 */
    private fun resample(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate <= 0 || sourceRate == targetRate) return pcm
        val source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sourceFrames = source.limit()
        if (sourceFrames == 0) return ByteArray(0)
        val targetFrames = (sourceFrames.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(targetFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = sourceRate.toDouble() / targetRate
        for (index in 0 until targetFrames) {
            val position = index * ratio
            val left = position.toInt().coerceAtMost(sourceFrames - 1)
            val right = (left + 1).coerceAtMost(sourceFrames - 1)
            val fraction = position - left
            val value = source.get(left) + ((source.get(right) - source.get(left)) * fraction).toInt()
            output.putShort(value.toShort())
        }
        return output.array()
    }

    private data class PcmWav(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    /** 只接受 16bit PCM WAV；其他格式返回 null，交给 [AudioConverter]。 */
    private fun parsePcm16Wav(bytes: ByteArray): PcmWav? {
        if (bytes.size < 44) return null
        if (bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WAVE") return null
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = bytes.ascii(offset, 4)
            val size = bytes.leInt(offset + 4)
            if (size < 0 || offset + 8L + size > bytes.size) return null
            val start = offset + 8
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    format = bytes.leShort(start)
                    channels = bytes.leShort(start + 2)
                    sampleRate = bytes.leInt(start + 4)
                    bits = bytes.leShort(start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }
        if (format != 1 || bits != 16 || channels !in 1..2 || sampleRate <= 0) return null
        return PcmWav(data ?: return null, sampleRate, channels)
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.leShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    /** 中日韩字符（含全角标点）按 2 个计费字符计。 */
    private fun isWideChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x11FF || code in 0x2E80..0x303F || code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF || code in 0xFF00..0xFF60 || code in 0xFFE0..0xFFE6
    }
}
