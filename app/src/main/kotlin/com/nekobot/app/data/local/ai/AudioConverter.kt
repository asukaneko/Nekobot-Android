package com.nekobot.app.data.local.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频格式转码工具：基于 Android 原生 [MediaExtractor] + [MediaCodec]，
 * 把任意 Android 支持的音频格式（m4a/aac/webm/ogg/flac/mp3 等）解码为 PCM，
 * 重采样到指定采样率/声道，最后包装为 WAV 字节流。
 *
 * 仅用于 STT 调用前的格式适配（如小米 MiMo 仅支持 wav/mp3）。
 * 不引入 ffmpeg 等原生库，APK 体积零增加。
 */
object AudioConverter {

    private const val TAG = "AudioConverter"

    /**
     * 将任意 Android 支持的音频字节转换为 WAV（16-bit PCM）。
     *
     * @param input 输入音频字节（m4a/aac/webm/ogg/flac/mp3 等）
     * @param targetSampleRate 目标采样率（Hz），如 16000
     * @param targetChannels 目标声道数，如 1（单声道）
     * @return WAV 文件字节流（44 字节头 + PCM 数据）
     */
    fun toWav(input: ByteArray, targetSampleRate: Int = 16000, targetChannels: Int = 1): ByteArray {
        require(targetChannels == 1 || targetChannels == 2) { "targetChannels 必须是 1 或 2" }

        // MediaExtractor 不支持直接从 ByteBuffer 读取，需要先写入临时文件
        val tmpFile = java.io.File.createTempFile("audio_in_", ".bin")
        try {
            tmpFile.writeBytes(input)
            val pcm = decodeToPcm(tmpFile.absolutePath)
            if (pcm.pcmBytes.isEmpty()) {
                throw IllegalStateException("解码后 PCM 为空，可能输入不是有效的音频文件")
            }
            // 重采样 + 声道下混
            val resampled = resampleAndDownmix(
                pcm = pcm.pcmBytes,
                srcSampleRate = pcm.sampleRate,
                srcChannels = pcm.channels,
                dstSampleRate = targetSampleRate,
                dstChannels = targetChannels
            )
            return writeWav(resampled, targetSampleRate, targetChannels, 16)
        } finally {
            tmpFile.delete()
        }
    }

    /** 解码后的 PCM 数据元信息 */
    private data class PcmData(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    /** 用 MediaExtractor + MediaCodec 解码到 PCM 16-bit */
    private fun decodeToPcm(filePath: String): PcmData {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(filePath)
            // 选取第一个音频轨道
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                throw IllegalStateException("未找到音频轨道")
            }
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val info = MediaCodec.BufferInfo()
            val outputBuffer = ByteArrayOutputStream()
            val timeoutUs = 10_000L
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk, 0, info.size)
                        outputBuffer.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }

            return PcmData(
                pcmBytes = outputBuffer.toByteArray(),
                sampleRate = srcSampleRate,
                channels = srcChannels
            )
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * 简单线性插值重采样 + 立体声→单声道下混。
     * 输入/输出均为 16-bit signed PCM（little-endian）。
     */
    private fun resampleAndDownmix(
        pcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        dstSampleRate: Int,
        dstChannels: Int
    ): ByteArray {
        if (srcChannels != 1 && srcChannels != 2) {
            throw IllegalArgumentException("不支持的源声道数: $srcChannels")
        }
        // 先把 16-bit 字节流转成 short 数组
        val srcShorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcShorts)

        // 输入帧数（每帧 = srcChannels 个 sample）
        val srcFrames = srcShorts.size / srcChannels
        val dstFrames = if (srcSampleRate == dstSampleRate) srcFrames
            else ((srcFrames.toLong() * dstSampleRate) / srcSampleRate).toInt()

        val dstShorts = ShortArray(dstFrames * dstChannels)
        val ratio = srcSampleRate.toDouble() / dstSampleRate

        for (di in 0 until dstFrames) {
            val srcPos = di * ratio
            val si0 = srcPos.toInt()
            val si1 = (si0 + 1).coerceAtMost(srcFrames - 1)
            val frac = srcPos - si0

            // 取第 si0/si1 帧的 sample（若立体声则下混为单值）
            val s0 = downmixFrame(srcShorts, si0, srcChannels)
            val s1 = downmixFrame(srcShorts, si1, srcChannels)
            // 线性插值
            val v = s0 + ((s1 - s0) * frac).toInt()

            if (dstChannels == 1) {
                dstShorts[di] = v.toShort()
            } else {
                dstShorts[di * 2] = v.toShort()
                dstShorts[di * 2 + 1] = v.toShort()
            }
        }

        val out = ByteArray(dstShorts.size * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(dstShorts)
        return out
    }

    /** 把一帧（可能立体声）下混为单个 sample */
    private fun downmixFrame(buf: ShortArray, frameIdx: Int, channels: Int): Int {
        return if (channels == 1) {
            buf[frameIdx].toInt()
        } else {
            // 立体声：取左右声道平均
            val l = buf[frameIdx * 2].toInt()
            val r = buf[frameIdx * 2 + 1].toInt()
            (l + r) / 2
        }
    }

    /** 写 WAV 文件头 + PCM 数据 */
    private fun writeWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        val totalLen = 36 + dataLen

        val out = ByteArrayOutputStream(44 + dataLen)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        header.put("RIFF".toByteArray())
        header.putInt(totalLen)
        header.put("WAVE".toByteArray())
        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)                          // PCM fmt chunk 大小
        header.putShort(1)                         // AudioFormat = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        // data chunk
        header.put("data".toByteArray())
        header.putInt(dataLen)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
