package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class WorkspaceAndPdfPreviewTest {
    @Test
    fun identifiesPdfFilesForExternalViewer() {
        assertTrue(isPdfWorkspaceFile("downloads/JM123.PDF"))
        assertTrue(isPdfWorkspaceFile("no-extension", "application/pdf"))
        assertTrue(!isPdfWorkspaceFile("notes.txt", "text/plain"))
    }

    @Test
    fun identifiesTxtFilesForSystemChooser() {
        assertTrue(isPlainTextWorkspaceFile("novel/book.TXT"))
        assertTrue(isPlainTextWorkspaceFile("no-extension", "text/plain"))
        assertTrue(!isPlainTextWorkspaceFile("page.html", "text/html"))
    }

    @Test
    fun markdownFilesUseBuiltInRenderingInsteadOfPlainText() {
        assertTrue(isMarkdownWorkspaceFile("docs/README.md"))
        assertTrue(isMarkdownWorkspaceFile("notes.MARKDOWN"))
        assertTrue(isMarkdownWorkspaceFile("no-extension", "text/markdown"))
        assertTrue(!isMarkdownWorkspaceFile("novel/book.txt"))
        // 工作区列表可能把 .md 报成 text/plain，也不能当成纯文本交给外部应用
        assertTrue(!isPlainTextWorkspaceFile("docs/README.md", "text/plain"))
        assertTrue(!isPlainTextWorkspaceFile("docs/README.md", "text/markdown"))
    }

    @Test
    fun nonMediaUrlsBecomeClickableLinks() {
        val segments = parseContentSegments("看这份笔记 https://example.com/note.md")
        // 网址不再直接抓取展示，而是渲染为可点击链接（点击后才预览）
        assertTrue(segments.any { it.type == SegmentType.LINK && it.url == "https://example.com/note.md" })

        val page = parseContentSegments("参考 https://example.com/docs/index.html")
        assertTrue(page.any { it.type == SegmentType.LINK })

        val plain = parseContentSegments("主页 https://example.com/")
        assertTrue(plain.any { it.type == SegmentType.LINK })
    }

    @Test
    fun mediaUrlsStayInline() {
        val segments = parseContentSegments("看图 https://example.com/a.png")
        assertTrue(segments.any { it.type == SegmentType.IMAGE })
        assertTrue(!segments.any { it.type == SegmentType.LINK })
    }

    @Test
    fun inlineHtmlTagsInRepliesStayAsText() {
        // AI 回复里夹带零散 HTML 标签是常态，不能整条丢进 WebView 渲染成网页
        val reply = "可以这样写：<div class=\"box\">内容</div>，注意闭合标签。"
        val segments = parseContentSegments(reply)
        assertTrue(segments.none { it.type == SegmentType.HTML })
        assertEquals(reply, segments.filter { it.type == SegmentType.TEXT }.joinToString("") { it.text })

        // 讲解 HTML 的长回复同样按文本渲染
        val tutorial = """
            示例：
            <table>
              <tr><td><b>键</b></td></tr>
            </table>
        """.trimIndent()
        assertTrue(parseContentSegments(tutorial).none { it.type == SegmentType.HTML })
    }

    @Test
    fun onlyFullHtmlDocumentsRenderInWebView() {
        assertTrue(isFullHtmlDocument("<!DOCTYPE html>\n<html><body>hi</body></html>"))
        assertTrue(isFullHtmlDocument("  <html lang=\"zh\"><body>hi</body></html>"))
        assertTrue(isFullHtmlDocument("<HTML><body>hi</body></HTML>"))

        val document = "<!DOCTYPE html><html><body><p>页面</p></body></html>"
        val segments = parseContentSegments(document)
        assertEquals(SegmentType.HTML, segments.single().type)

        // 仅仅出现标签名不算文档
        assertTrue(!isFullHtmlDocument("先说 <html> 标签的用法"))
        assertTrue(!isFullHtmlDocument("<div>片段</div>"))
        assertTrue(!isFullHtmlDocument("普通回复"))
    }

    @Test
    fun fullHtmlDocumentStillYieldsToInlineMediaUrls() {
        val content = "<html><body><img src=\"https://example.com/a.png\"></body></html>"
        val segments = parseContentSegments(content)
        assertTrue(segments.none { it.type == SegmentType.HTML })
        assertTrue(segments.any { it.type == SegmentType.IMAGE })
    }

    @Test
    fun readTextPreviewTruncatesLongMarkdown() {
        val file = File.createTempFile("neko-md-preview", ".md")
        try {
            file.writeText("a".repeat(50))
            assertEquals("a".repeat(50) to false, readTextPreview(file, maxChars = 100))

            file.writeText("b".repeat(120))
            val (content, truncated) = readTextPreview(file, maxChars = 100)
            assertEquals("b".repeat(100), content)
            assertTrue(truncated)
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalizesWorkspacePathsAndFindsParent() {
        assertEquals(
            "downloads/books",
            normalizeWorkspaceBrowserPath("/downloads/./temp/../books/")
        )
        assertEquals(
            "downloads",
            parentWorkspaceBrowserPath("downloads/books")
        )
        assertEquals("", parentWorkspaceBrowserPath("downloads"))
    }

    @Test
    fun pdfRenderSizeFitsScreenAndKeepsAspectRatio() {
        val size = calculatePdfRenderSize(
            pageWidth = 595,
            pageHeight = 842,
            targetWidth = 800,
            maxPixels = 2_000_000
        )

        assertEquals(800, size.width)
        assertTrue(size.width * size.height <= 2_000_000)
        assertTrue(abs(size.width.toDouble() / size.height - 595.0 / 842.0) < 0.002)
    }

    @Test
    fun pdfRenderSizeCapsVeryTallPagesByPixelBudget() {
        val size = calculatePdfRenderSize(
            pageWidth = 1_000,
            pageHeight = 100_000,
            targetWidth = 800,
            maxPixels = 2_000_000
        )

        assertTrue(size.width < 800)
        assertTrue(size.width * size.height <= 2_000_000)
        assertTrue(abs(size.width.toDouble() / size.height - 0.01) < 0.001)
    }
}
