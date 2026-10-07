package com.nekobot.app.ui.screens.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinBrowserTargetsTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory(name).toFile().canonicalFile

    private fun writeFile(dir: File, relativePath: String, content: String = "<html></html>"): File {
        val file = File(dir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.canonicalFile
    }

    @Test
    fun `无协议域名补 https`() {
        assertEquals(
            BrowserTarget.Remote("https://example.com/a?b=1"),
            BuiltinBrowserTargets.resolve("example.com/a?b=1", null)
        )
    }

    @Test
    fun `https 地址原样放行`() {
        assertEquals(
            BrowserTarget.Remote("https://example.com"),
            BuiltinBrowserTargets.resolve("https://example.com", null)
        )
    }

    @Test
    fun `回环地址允许明文 http`() {
        assertEquals(
            BrowserTarget.Remote("http://localhost:8080/index"),
            BuiltinBrowserTargets.resolve("localhost:8080/index", null)
        )
        assertEquals(
            BrowserTarget.Remote("http://127.0.0.1:5000/"),
            BuiltinBrowserTargets.resolve("http://127.0.0.1:5000/", null)
        )
    }

    @Test
    fun `非回环的明文 http 被拒绝`() {
        assertNull(BuiltinBrowserTargets.resolve("http://example.com", null))
        assertNull(BuiltinBrowserTargets.resolve("ftp://example.com/x.html", null))
        assertNull(BuiltinBrowserTargets.resolve("javascript:alert(1)", null))
    }

    @Test
    fun `工作区相对路径解析为本地 HTML`() {
        val root = tempDir("nekobot-browser-ws")
        val page = writeFile(root, "docs/index.html")

        assertEquals(
            BrowserTarget.LocalHtml(page),
            BuiltinBrowserTargets.resolve("docs/index.html", root)
        )
        assertEquals(
            BrowserTarget.LocalHtml(page),
            BuiltinBrowserTargets.resolve("docs/../docs/index.html", root)
        )
    }

    @Test
    fun `越界路径与不存在的文件不解析`() {
        val root = tempDir("nekobot-browser-escape")
        writeFile(root.parentFile!!, "outside.html")

        assertNull(BuiltinBrowserTargets.resolve("../outside.html", root))
        assertNull(BuiltinBrowserTargets.resolve("docs/missing.html", root))
        assertNull(BuiltinBrowserTargets.resolve("docs/readme.md", root))
    }

    @Test
    fun `file 协议仅接受存在的 HTML`() {
        val root = tempDir("nekobot-browser-file-url")
        val page = writeFile(root, "app.html")
        val text = writeFile(root, "note.txt")

        assertEquals(
            BrowserTarget.LocalHtml(page),
            BuiltinBrowserTargets.resolve("file://${page.absolutePath}", null)
        )
        assertNull(BuiltinBrowserTargets.resolve("file://${text.absolutePath}", null))
        assertNull(BuiltinBrowserTargets.resolve("file://${root.absolutePath}/gone.html", null))
    }

    @Test
    fun `绝对路径 HTML 可直接打开`() {
        val root = tempDir("nekobot-browser-absolute")
        val page = writeFile(root, "report.htm")

        assertEquals(
            BrowserTarget.LocalHtml(page),
            BuiltinBrowserTargets.resolve(page.absolutePath, null)
        )
    }

    @Test
    fun `空输入不解析`() {
        assertNull(BuiltinBrowserTargets.resolve("   ", null))
    }

    @Test
    fun `导航白名单与地址栏一致`() {
        assertTrue(BuiltinBrowserTargets.isAllowedNavigationUrl("https://example.com/x"))
        assertTrue(BuiltinBrowserTargets.isAllowedNavigationUrl("http://localhost:8080/x"))
        assertTrue(BuiltinBrowserTargets.isAllowedNavigationUrl("http://127.0.0.1/x"))
        assertTrue(BuiltinBrowserTargets.isAllowedNavigationUrl("file:///sdcard/a/b.html"))
        assertFalse(BuiltinBrowserTargets.isAllowedNavigationUrl("http://example.com"))
        assertFalse(BuiltinBrowserTargets.isAllowedNavigationUrl("file:///sdcard/a/b.txt"))
        assertFalse(BuiltinBrowserTargets.isAllowedNavigationUrl("intent://scan/#Intent;end"))
    }

    @Test
    fun `工作区 HTML 列表只保留 HTML 并受上限约束`() {
        val root = tempDir("nekobot-browser-scan")
        writeFile(root, "index.html")
        writeFile(root, "sub/page.htm")
        writeFile(root, "sub/note.md")
        writeFile(root, "deep/a/b/c/d/e.html")

        val pages = BuiltinBrowserTargets.workspaceHtmlFiles(root)
        val relative = pages.map { it.relativeTo(root).path.replace('\\', '/') }

        assertEquals(listOf("index.html", "sub/page.htm"), relative)
    }

    @Test
    fun `工作区 HTML 列表遵守数量上限`() {
        val root = tempDir("nekobot-browser-limit")
        repeat(5) { writeFile(root, "page-$it.html") }

        assertEquals(2, BuiltinBrowserTargets.workspaceHtmlFiles(root, limit = 2).size)
    }

    @Test
    fun `工作区不存在时列表为空`() {
        assertEquals(emptyList<File>(), BuiltinBrowserTargets.workspaceHtmlFiles(null))
    }
}
