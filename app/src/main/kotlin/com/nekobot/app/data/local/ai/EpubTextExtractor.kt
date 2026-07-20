package com.nekobot.app.data.local.ai

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

internal data class EpubExtractionResult(
    val chapterCount: Int,
    val characterCount: Long
)

/**
 * 按 EPUB 的 OPF spine 阅读顺序提取正文，并写成 UTF-8 TXT。
 *
 * EPUB 本质上是 ZIP，但不能简单按文件名拼接章节；spine 才是小说的实际阅读顺序。
 */
internal object EpubTextExtractor {
    private const val MAX_XML_BYTES = 5 * 1024 * 1024
    private const val MAX_CHAPTER_BYTES = 16 * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 100L * 1024 * 1024

    private data class ManifestItem(
        val href: String,
        val mediaType: String
    )

    private data class PackageDocument(
        val manifest: LinkedHashMap<String, ManifestItem>,
        val spine: List<String>
    )

    fun extract(epubFile: File, outputFile: File): EpubExtractionResult {
        require(epubFile.isFile) { "EPUB 文件不存在" }
        require(epubFile.extension.equals("epub", ignoreCase = true)) { "输入文件必须是 EPUB 格式" }
        require(outputFile.extension.equals("txt", ignoreCase = true)) { "输出文件必须使用 .txt 扩展名" }
        require(epubFile.canonicalFile != outputFile.canonicalFile) { "输出文件不能覆盖 EPUB 源文件" }

        return ZipFile(epubFile).use { zip ->
            val containerEntry = findEntry(zip, "", "META-INF/container.xml")
                ?: error("无效 EPUB：缺少 META-INF/container.xml")
            val containerXml = readEntryText(zip, containerEntry, MAX_XML_BYTES)
            val packagePath = parsePackagePath(containerXml)
            val packageEntry = findEntry(zip, "", packagePath)
                ?: error("无效 EPUB：找不到包文档 $packagePath")
            val packageDocument = parsePackageDocument(
                readEntryText(zip, packageEntry, MAX_XML_BYTES)
            )
            val packageBase = packageEntry.name.substringBeforeLast('/', "")
            val orderedItems = packageDocument.spine
                .mapNotNull(packageDocument.manifest::get)
                .ifEmpty {
                    packageDocument.manifest.values.filter { it.isHtml() }
                }
                .filter { it.isHtml() }

            if (orderedItems.isEmpty()) error("EPUB 中没有可提取的正文")

            val outputParent = outputFile.parentFile ?: error("输出目录不可用")
            outputParent.mkdirs()
            if (!outputParent.isDirectory) error("无法创建输出目录")
            if (outputFile.isDirectory) error("输出路径是目录")

            val temporaryFile = File.createTempFile(".${outputFile.name}.", ".tmp", outputParent)
            try {
                var chapterCount = 0
                var characterCount = 0L
                temporaryFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    orderedItems.forEach { item ->
                        val chapterEntry = findEntry(zip, packageBase, item.href) ?: return@forEach
                        val chapterText = htmlToText(
                            readEntryText(zip, chapterEntry, MAX_CHAPTER_BYTES)
                        )
                        if (chapterText.isBlank()) return@forEach

                        val nextCharacterCount = characterCount + chapterText.length
                        if (nextCharacterCount > MAX_OUTPUT_CHARS) {
                            error("EPUB 正文超过 100MB 提取限制")
                        }
                        if (chapterCount > 0) writer.append("\n\n")
                        writer.append(chapterText)
                        characterCount = nextCharacterCount
                        chapterCount++
                    }
                    if (chapterCount == 0) error("EPUB 正文章节为空或无法读取")
                    writer.newLine()
                }

                temporaryFile.copyTo(outputFile, overwrite = true)
                EpubExtractionResult(
                    chapterCount = chapterCount,
                    characterCount = characterCount
                )
            } finally {
                temporaryFile.delete()
            }
        }
    }

    private fun ManifestItem.isHtml(): Boolean {
        val type = mediaType.lowercase()
        val extension = href.substringBefore('#').substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
        return type == "application/xhtml+xml" ||
            type == "text/html" ||
            extension in setOf("xhtml", "html", "htm")
    }

    private fun parsePackagePath(xml: String): String {
        val document = parseXml(xml)
        return elementsByLocalName(document, "rootfile")
            .firstNotNullOfOrNull { it.getAttribute("full-path").takeIf(String::isNotBlank) }
            ?: error("无效 EPUB：container.xml 未声明包文档")
    }

    private fun parsePackageDocument(xml: String): PackageDocument {
        val document = parseXml(xml)
        val manifest = linkedMapOf<String, ManifestItem>()
        elementsByLocalName(document, "item").forEach { item ->
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = ManifestItem(
                    href = href,
                    mediaType = item.getAttribute("media-type")
                )
            }
        }
        val spine = elementsByLocalName(document, "itemref")
            .mapNotNull { it.getAttribute("idref").takeIf(String::isNotBlank) }
        return PackageDocument(manifest, spine)
    }

    private fun parseXml(xml: String): Document {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            error("EPUB XML 不允许包含 DOCTYPE")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun elementsByLocalName(document: Document, name: String): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val localName = element.localName ?: element.tagName.substringAfter(':')
                if (localName == name) add(element)
            }
        }
    }

    private fun findEntry(zip: ZipFile, base: String, href: String): ZipEntry? {
        val rawPath = resolveArchivePath(base, href) ?: return null
        val decodedPath = resolveArchivePath(base, decodeUriPath(href))
        return sequenceOf(rawPath, decodedPath)
            .filterNotNull()
            .distinct()
            .mapNotNull(zip::getEntry)
            .firstOrNull()
    }

    private fun resolveArchivePath(base: String, href: String): String? {
        val cleanHref = href.substringBefore('#').substringBefore('?')
            .replace('\\', '/')
            .trimStart('/')
        if (cleanHref.isBlank()) return null
        val parts = buildList {
            addAll(base.replace('\\', '/').split('/').filter(String::isNotBlank))
            cleanHref.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (isEmpty()) return null else removeAt(lastIndex)
                    else -> add(part)
                }
            }
        }
        return parts.joinToString("/").takeIf(String::isNotBlank)
    }

    private fun decodeUriPath(path: String): String = path
        .split('/')
        .joinToString("/") { segment ->
            runCatching {
                URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(segment)
        }

    private fun readEntryText(zip: ZipFile, entry: ZipEntry, maxBytes: Int): String {
        if (entry.isDirectory) error("EPUB 条目不是文件: ${entry.name}")
        if (entry.size > maxBytes) error("EPUB 条目过大: ${entry.name}")
        val output = ByteArrayOutputStream(
            entry.size.takeIf { it in 1..maxBytes.toLong() }?.toInt() ?: 8192
        )
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) error("EPUB 条目过大: ${entry.name}")
                output.write(buffer, 0, count)
            }
        }
        return decodeText(output.toByteArray())
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val charset = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                StandardCharsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                StandardCharsets.UTF_16LE
            else -> detectDeclaredCharset(bytes) ?: StandardCharsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun detectDeclaredCharset(bytes: ByteArray): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, 1024), StandardCharsets.ISO_8859_1)
        val name = Regex(
            """(?i)(?:encoding\s*=\s*["']|charset\s*=\s*["']?)([A-Za-z0-9._-]+)"""
        ).find(prefix)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    internal fun htmlToText(html: String): String {
        val withoutHiddenContent = html
            .replace(Regex("""(?is)<(script|style|head|svg|math)\b[^>]*>.*?</\1\s*>"""), "")
            .replace(Regex("""(?is)<!--.*?-->"""), "")
        val withLineBreaks = withoutHiddenContent
            .replace(Regex("""(?i)<br\s*/?\s*>"""), "\n")
            .replace(
                Regex(
                    """(?i)</?(?:p|div|section|article|header|footer|aside|nav|h[1-6]|li|ul|ol|blockquote|pre|hr|table|tr|dt|dd)\b[^>]*>"""
                ),
                "\n"
            )
        val plainText = decodeHtmlEntities(
            withLineBreaks.replace(Regex("""(?s)<[^>]+>"""), "")
        )
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return plainText.lineSequence()
            .map { line -> line.replace(Regex("""[ \t\u00A0]+"""), " ").trim() }
            .toList()
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(text: String): String {
        val named = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "ldquo" to "“",
            "rdquo" to "”",
            "lsquo" to "‘",
            "rsquo" to "’"
        )
        return Regex("""&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);""").replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).toIntOrNull(16)?.toUnicodeString() ?: match.value
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.toUnicodeString() ?: match.value
                else -> named[entity.lowercase()] ?: match.value
            }
        }
    }

    private fun Int.toUnicodeString(): String =
        if (this in 0..0x10FFFF && this !in 0xD800..0xDFFF) {
            String(Character.toChars(this))
        } else {
            "\uFFFD"
        }
}
