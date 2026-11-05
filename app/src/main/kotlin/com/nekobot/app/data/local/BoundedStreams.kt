package com.nekobot.app.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class ContentSizeLimitException : IOException("内容超过大小限制")

/** 按实际读取量限制输入，包括没有 Content-Length 的响应。 */
internal fun InputStream.copyToLimited(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0 && maxBytes < Long.MAX_VALUE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
        if (count < 0) return total
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw ContentSizeLimitException()
        output.write(buffer, 0, count)
    }
}

internal fun InputStream.readBytesLimited(maxBytes: Long): ByteArray =
    ByteArrayOutputStream().also { copyToLimited(it, maxBytes) }.toByteArray()

/** 下载完成后才替换目标；超限、断流时保留原文件并清理临时文件。 */
internal fun InputStream.saveLimitedDownload(target: File, maxBytes: Long): Long {
    val parent = requireNotNull(target.parentFile)
    parent.mkdirs()
    val temporary = File.createTempFile(".download-", ".tmp", parent)
    try {
        val total = temporary.outputStream().use { copyToLimited(it, maxBytes) }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return total
    } finally {
        temporary.delete()
    }
}

internal data class TextPreview(val content: String, val truncated: Boolean)

/** 多读一个字符判断截断，不把整份文件载入内存。 */
internal fun Reader.readTextPreview(maxChars: Int): TextPreview {
    require(maxChars >= 0 && maxChars < Int.MAX_VALUE)
    val result = StringBuilder(minOf(maxChars, DEFAULT_BUFFER_SIZE))
    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
    while (result.length <= maxChars) {
        val count = read(buffer, 0, minOf(buffer.size, maxChars + 1 - result.length))
        if (count < 0) break
        if (count > 0) result.append(buffer, 0, count)
    }
    return TextPreview(result.substring(0, minOf(result.length, maxChars)), result.length > maxChars)
}
