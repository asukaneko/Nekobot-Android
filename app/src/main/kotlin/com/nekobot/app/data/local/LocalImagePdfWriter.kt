package com.nekobot.app.data.local

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 面向图片漫画的流式 PDF 写入器。
 *
 * 每加入一页便立即写入 JPEG、内容流和页面对象，内存中只保留对象偏移与页面引用，
 * 避免 Android PdfDocument 在长篇漫画中积压全部页面。
 */
internal class LocalImagePdfWriter(outputFile: File) : Closeable {
    private val output = CountingPdfOutput(outputFile)
    private val objectOffsets = linkedMapOf<Int, Long>()
    private val pageObjectIds = mutableListOf<Int>()
    private var nextObjectId = 3
    private var finished = false

    init {
        output.writeAscii("%PDF-1.4\n")
        output.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        writePlainObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
    }

    val pageCount: Int
        get() = pageObjectIds.size

    fun addJpegPage(jpegBytes: ByteArray, pixelWidth: Int, pixelHeight: Int) {
        check(!finished) { "PDF 已完成写入" }
        require(jpegBytes.size >= 4) { "JPEG 数据为空" }
        require(pixelWidth > 0 && pixelHeight > 0) { "图片尺寸无效" }

        val imageObjectId = nextObjectId++
        val contentObjectId = nextObjectId++
        val pageObjectId = nextObjectId++
        val landscape = pixelWidth > pixelHeight
        val pageWidth = if (landscape) A4_LONG_EDGE else A4_SHORT_EDGE
        val pageHeight = if (landscape) A4_SHORT_EDGE else A4_LONG_EDGE
        val scale = minOf(
            pageWidth / pixelWidth.toDouble(),
            pageHeight / pixelHeight.toDouble()
        )
        val drawWidth = pixelWidth * scale
        val drawHeight = pixelHeight * scale
        val offsetX = (pageWidth - drawWidth) / 2.0
        val offsetY = (pageHeight - drawHeight) / 2.0

        writeStreamObject(
            objectId = imageObjectId,
            dictionary = buildString {
                append("<< /Type /XObject /Subtype /Image")
                append(" /Width $pixelWidth /Height $pixelHeight")
                append(" /ColorSpace /DeviceRGB /BitsPerComponent 8")
                append(" /Filter /DCTDecode /Length ${jpegBytes.size} >>")
            },
            stream = jpegBytes
        )

        val content = buildString {
            appendLine("q")
            appendLine(
                String.format(
                    Locale.US,
                    "%.3f 0 0 %.3f %.3f %.3f cm",
                    drawWidth,
                    drawHeight,
                    offsetX,
                    offsetY
                )
            )
            appendLine("/Im0 Do")
            append("Q")
        }.toByteArray(StandardCharsets.US_ASCII)
        writeStreamObject(
            objectId = contentObjectId,
            dictionary = "<< /Length ${content.size} >>",
            stream = content
        )
        writePlainObject(
            pageObjectId,
            buildString {
                append("<< /Type /Page /Parent 2 0 R")
                append(
                    String.format(
                        Locale.US,
                        " /MediaBox [0 0 %.3f %.3f]",
                        pageWidth,
                        pageHeight
                    )
                )
                append(" /Resources << /XObject << /Im0 $imageObjectId 0 R >> >>")
                append(" /Contents $contentObjectId 0 R >>")
            }
        )
        pageObjectIds += pageObjectId
    }

    fun finish() {
        check(!finished) { "PDF 已完成写入" }
        require(pageObjectIds.isNotEmpty()) { "PDF 没有可写入的页面" }

        val kids = pageObjectIds.joinToString(" ") { "$it 0 R" }
        writePlainObject(
            2,
            "<< /Type /Pages /Kids [$kids] /Count ${pageObjectIds.size} >>"
        )

        val maxObjectId = nextObjectId - 1
        val xrefOffset = output.bytesWritten
        output.writeAscii("xref\n")
        output.writeAscii("0 ${maxObjectId + 1}\n")
        output.writeAscii("0000000000 65535 f \n")
        for (objectId in 1..maxObjectId) {
            val offset = objectOffsets[objectId]
                ?: error("PDF 对象 $objectId 缺少偏移")
            output.writeAscii(String.format(Locale.US, "%010d 00000 n \n", offset))
        }
        output.writeAscii("trailer\n")
        output.writeAscii("<< /Size ${maxObjectId + 1} /Root 1 0 R >>\n")
        output.writeAscii("startxref\n$xrefOffset\n%%EOF\n")
        output.flush()
        finished = true
    }

    override fun close() {
        output.close()
    }

    private fun writePlainObject(objectId: Int, body: String) {
        objectOffsets[objectId] = output.bytesWritten
        output.writeAscii("$objectId 0 obj\n$body\nendobj\n")
    }

    private fun writeStreamObject(objectId: Int, dictionary: String, stream: ByteArray) {
        objectOffsets[objectId] = output.bytesWritten
        output.writeAscii("$objectId 0 obj\n$dictionary\nstream\n")
        output.write(stream)
        output.writeAscii("\nendstream\nendobj\n")
    }

    private class CountingPdfOutput(file: File) : Closeable {
        private val delegate = BufferedOutputStream(FileOutputStream(file))
        var bytesWritten: Long = 0L
            private set

        fun write(bytes: ByteArray) {
            delegate.write(bytes)
            bytesWritten += bytes.size
        }

        fun writeAscii(value: String) {
            write(value.toByteArray(StandardCharsets.US_ASCII))
        }

        fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    companion object {
        private const val A4_SHORT_EDGE = 595.276
        private const val A4_LONG_EDGE = 841.890
    }
}
