package com.nekobot.app.data.local.knowledge

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 把常见文档格式转换为知识库纯文本。 */
object LocalKnowledgeDocumentImporter {
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_EXTRACTED_CHARS = 2_000_000

    data class ImportedDocument(
        val title: String,
        val content: String,
        val source: String
    )

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
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    return stripMarkup(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        return ""
    }

    private fun extractZipMarkup(bytes: ByteArray, extensions: Set<String>): String {
        val sections = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val lower = entry.name.lowercase()
                if (!entry.isDirectory && extensions.any(lower::endsWith)) {
                    val content = stripMarkup(zip.readBytes().toString(Charsets.UTF_8))
                    if (content.isNotBlank()) sections += entry.name to content
                }
            }
        }
        return sections.sortedBy { it.first }.joinToString("\n\n") { it.second }
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
