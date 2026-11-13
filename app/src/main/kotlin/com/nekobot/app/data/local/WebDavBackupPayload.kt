package com.nekobot.app.data.local

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * WebDAV 备份载荷的物理格式。
 *
 * 备份文件既可能是加密后的 JSON（config.nbotcfg），也可能是旧版本或第三方工具
 * 直接写出的 ZIP/GZIP 归档。读取前必须先按文件头判断格式，不能把所有内容都按
 * UTF-8 文本打开。
 */
internal enum class WebDavPayloadFormat {
    ZIP,
    GZIP,
    JSON,
    UNKNOWN
}

/** 只读取文件头，不把二进制正文当作文本解码。 */
internal object WebDavPayloadInspector {

    private const val HEADER_BYTES = 16
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun detect(file: File): WebDavPayloadFormat =
        file.inputStream().buffered().use { input ->
            val header = ByteArray(HEADER_BYTES)
            var size = 0
            while (size < header.size) {
                val read = input.read(header, size, header.size - size)
                if (read < 0) break
                if (read == 0) continue
                size += read
            }
            detect(header, size)
        }

    fun detect(bytes: ByteArray): WebDavPayloadFormat = detect(bytes, bytes.size)

    private fun detect(bytes: ByteArray, size: Int): WebDavPayloadFormat {
        if (size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val third = bytes[2].toInt() and 0xFF
            val fourth = bytes[3].toInt() and 0xFF
            if ((third == 0x03 && fourth == 0x04) ||
                (third == 0x05 && fourth == 0x06) ||
                (third == 0x07 && fourth == 0x08)
            ) {
                return WebDavPayloadFormat.ZIP
            }
        }
        if (size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return WebDavPayloadFormat.GZIP
        }
        var index = 0
        if (startsWith(bytes, size, UTF8_BOM)) {
            index = UTF8_BOM.size
        } else if (startsWith(bytes, size, UTF16_LE_BOM) || startsWith(bytes, size, UTF16_BE_BOM)) {
            index = 2
        }
        while (index < size) {
            val value = bytes[index].toInt() and 0xFF
            // 跳过空白；同时跳过 UTF-16 文本中 ASCII 字符前的 0x00。
            if (value == 0x20 || value == 0x09 || value == 0x0A || value == 0x0D || value == 0x00) {
                index++
            } else {
                break
            }
        }
        if (index >= size) return WebDavPayloadFormat.UNKNOWN
        val first = bytes[index]
        return if (first == '{'.code.toByte() || first == '['.code.toByte()) {
            WebDavPayloadFormat.JSON
        } else {
            WebDavPayloadFormat.UNKNOWN
        }
    }

    private fun startsWith(bytes: ByteArray, size: Int, prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}

/**
 * 备份文本的编码识别与容错解码。
 *
 * 服务端可能在 Windows 上以本地编码（GBK/GB18030）写出 JSON，直接按 UTF-8 解码
 * 会得到替换字符甚至失败。这里优先严格 UTF-8，其次 GB18030，最后退回带替换的
 * UTF-8，保证读取阶段不会因编码问题抛异常。
 */
internal object WebDavTextDecoder {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val gb18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    fun detectCharset(prefix: ByteArray): Charset {
        if (startsWith(prefix, UTF16_LE_BOM)) return StandardCharsets.UTF_16LE
        if (startsWith(prefix, UTF16_BE_BOM)) return StandardCharsets.UTF_16BE
        val sample = when {
            startsWith(prefix, UTF8_BOM) -> prefix.copyOfRange(UTF8_BOM.size, prefix.size)
            else -> prefix
        }
        if (sample.isEmpty()) return StandardCharsets.UTF_8
        if (isDecodable(sample, StandardCharsets.UTF_8)) return StandardCharsets.UTF_8
        if (gb18030 != null && isDecodable(sample, gb18030)) return gb18030
        return StandardCharsets.UTF_8
    }

    fun decode(bytes: ByteArray): String = decode(bytes, detectCharset(bytes))

    fun decode(bytes: ByteArray, charset: Charset): String {
        val offset = when {
            charset == StandardCharsets.UTF_8 && startsWith(bytes, UTF8_BOM) -> UTF8_BOM.size
            (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) &&
                (startsWith(bytes, UTF16_LE_BOM) || startsWith(bytes, UTF16_BE_BOM)) -> 2
            else -> 0
        }
        val text = String(bytes, offset, bytes.size - offset, charset)
        return if (offset == 0) text.removePrefix("\uFEFF") else text
    }

    /** 采样末尾可能截断多字节字符，因此最多回退 3 个字节再判定。 */
    private fun isDecodable(sample: ByteArray, charset: Charset): Boolean {
        val maxTrim = minOf(3, sample.size)
        for (trim in 0..maxTrim) {
            if (decodeStrict(sample, sample.size - trim, charset)) return true
        }
        return false
    }

    private fun decodeStrict(sample: ByteArray, length: Int, charset: Charset): Boolean {
        if (length <= 0) return true
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample, 0, length))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}
