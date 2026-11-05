package com.nekobot.app.data.local.knowledge

import com.nekobot.app.data.local.ContentSizeLimitException
import com.nekobot.app.data.local.readBytesLimited
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 把常见文档格式转换为知识库纯文本。 */
object LocalKnowledgeDocumentImporter {
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_EXTRACTED_CHARS = 2_000_000
    internal const val MAX_ARCHIVE_ENTRY_BYTES = 8 * 1024 * 1024
    internal const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    internal const val MAX_ARCHIVE_ENTRIES = 2048

    data class ImportedDocument(
        val title: String,
        val content: String,
        val source: String
    )

    /** 文件选择器读取阶段也要限额，不能等整个文件已进内存后再检查。 */
    fun readFileBytes(input: InputStream): ByteArray = input.readBytesLimited(MAX_FILE_BYTES.toLong())

    fun fromBytes(fileName: String, mimeType: String?, bytes: ByteArray): ImportedDocument {
        require(bytes.isNotEmpty()) { "文件内容为空" }
        require(bytes.size <= MAX_FILE_BYTES) { "文件超过 25 MB 限制" }
        val lowerName = fileName.lowercase()
        val text = when {
            lowerName.endsWith(".pdf") || mimeType == "application/pdf" -> extractPdf(bytes)
            lowerName.endsWith(".epub") || mimeType == "application/epub+zip" ->
                extractZipMarkup(bytes, setOf(".xhtml", ".html", ".htm"))
            lowerName.endsWith(".docx") ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                extractDocx(bytes)
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                mimeType == "text/html" -> stripMarkup(bytes.toString(Charsets.UTF_8))
            else -> decodeText(bytes)
        }.replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_EXTRACTED_CHARS)
        require(text.isNotBlank()) { "未能从文件中提取文本" }
        return ImportedDocument(
            title = fileName.substringBeforeLast('.').ifBlank { fileName },
            content = text,
            source = fileName
        )
    }

    private fun extractPdf(bytes: ByteArray): String =
        PDDocument.load(bytes).use { document ->
            PDFTextStripper().getText(document)
        }

    private fun extractDocx(bytes: ByteArray): String {
        val budget = ArchiveBudget()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val isDocument = !entry.isDirectory && entry.name == "word/document.xml"
                val content = budget.readEntry(zip, isDocument)
                if (isDocument) return stripMarkup(content.toString(Charsets.UTF_8))
            }
        }
        return ""
    }

    private fun extractZipMarkup(bytes: ByteArray, extensions: Set<String>): String {
        val budget = ArchiveBudget()
        val sections = mutableListOf<Pair<String, String>>()
        var textBudgetFull = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val lower = entry.name.lowercase()
                val isMarkup = !entry.isDirectory && extensions.any(lower::endsWith) &&
                    (!textBudgetFull || entry.name < sections.last().first)
                val data = budget.readEntry(zip, isMarkup)
                if (isMarkup) {
                    val content = stripMarkup(data.toString(Charsets.UTF_8)).trim().take(MAX_EXTRACTED_CHARS)
                    if (content.isNotBlank()) {
                        sections += entry.name to content
                        // 按章节顺序保留文本前缀，不依赖 ZIP 内的存储顺序。
                        sections.sortBy { it.first }
                        var remainingChars = MAX_EXTRACTED_CHARS
                        val iterator = sections.listIterator()
                        while (iterator.hasNext()) {
                            val (name, text) = iterator.next()
                            if (remainingChars == 0) iterator.remove()
                            else {
                                val kept = text.take(remainingChars)
                                iterator.set(name to kept)
                                remainingChars = (remainingChars - kept.length - 2).coerceAtLeast(0)
                            }
                        }
                        textBudgetFull = remainingChars == 0
                    }
                }
            }
        }
        return sections.joinToString("\n\n") { it.second }
    }

    /** 非正文条目也按实际读取量计数，避免跳过图片等条目时无限解压。 */
    private class ArchiveBudget {
        private var entries = 0
        private var totalBytes = 0L

        fun readEntry(input: InputStream, retain: Boolean): ByteArray {
            require(++entries <= MAX_ARCHIVE_ENTRIES) { "压缩包文件数量超过限制" }
            val output = if (retain) ByteArrayOutputStream() else null
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryBytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                entryBytes += count
                totalBytes += count
                if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
                    throw ContentSizeLimitException()
                }
                output?.write(buffer, 0, count)
            }
            return output?.toByteArray() ?: byteArrayOf()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        val replacementRatio = utf8.count { it == '\uFFFD' }.toDouble() / utf8.length.coerceAtLeast(1)
        return if (replacementRatio < 0.01) utf8 else bytes.toString(Charsets.ISO_8859_1)
    }

    internal fun stripMarkup(raw: String): String = raw
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\n *"), "\n")
}
