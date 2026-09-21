package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件资源路径安全与 HTML 注入位置。 */
class PluginAssetPathTest {

    @Test
    fun traversalAndAbsolutePathsAreRejected() {
        listOf(
            "../secret.html",
            "pages/../../secret.html",
            "/etc/passwd",
            "C:/windows/system32",
            "pages/./notes.html",
            "pages//notes.html",
            "",
            " "
        ).forEach { path ->
            assertFalse("应拒绝：$path", PluginManifestValidator.isSafeRelativePath(path))
        }
    }

    @Test
    fun normalRelativePathsAreAccepted() {
        listOf("notes.html", "pages/notes.html", "assets/img/icon.png", "a-b_c.1.js").forEach { path ->
            assertTrue("应接受：$path", PluginManifestValidator.isSafeRelativePath(path))
        }
    }

    @Test
    fun mimeTypesFollowExtension() {
        assertEquals("text/html", PluginAssetServer.mimeTypeFor("pages/notes.html"))
        assertEquals("text/css", PluginAssetServer.mimeTypeFor("notes.css"))
        assertEquals("application/javascript", PluginAssetServer.mimeTypeFor("notes.js"))
        assertEquals("image/png", PluginAssetServer.mimeTypeFor("icon.png"))
        assertEquals("image/svg+xml", PluginAssetServer.mimeTypeFor("icon.svg"))
        assertEquals("application/octet-stream", PluginAssetServer.mimeTypeFor("data.bin"))
    }

    @Test
    fun injectionGoesRightAfterHeadOpenTag() {
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
        val injected = PluginAssetServer.injectIntoHtml(html, "<style>INJECT</style>")
        val headIndex = injected.indexOf("<head>")
        val injectIndex = injected.indexOf("INJECT")
        val metaIndex = injected.indexOf("<meta")
        assertTrue(injectIndex > headIndex)
        assertTrue("注入必须先于页面脚本/资源", injectIndex < metaIndex)
    }

    @Test
    fun injectionFallsBackToHtmlTagThenPrepend() {
        val withoutHead = "<html><body>hi</body></html>"
        val injected = PluginAssetServer.injectIntoHtml(withoutHead, "X")
        assertEquals("<html>X<body>hi</body></html>", injected)

        val bare = "hello"
        assertEquals("Xhello", PluginAssetServer.injectIntoHtml(bare, "X"))
    }

    @Test
    fun themePatchScriptReplacesInjectedStyle() {
        val server = PluginAssetServer(pluginDirectoryProvider = { null })
        server.theme = PluginThemeTokens(
            mode = "dark",
            colors = mapOf("bg" to "#101010"),
            locale = "zh-CN"
        )
        val script = server.buildThemePatchScript()
        assertTrue(script.contains("neko-theme"))
        assertTrue(script.contains("--neko-bg:#101010"))
        assertTrue(script.contains("window.__NEKO_THEME__="))
        assertTrue(script.contains("\"mode\":\"dark\""))

        val injection = server.buildInjection()
        assertTrue(injection.contains("--neko-bg:#101010"))
        assertTrue(injection.contains("window.__NEKO_THEME__ = "))
    }

    @Test
    fun themeTokensProduceCssVariables() {
        val tokens = PluginThemeTokens(
            mode = "dark",
            colors = mapOf("bg" to "#101010", "primary" to "#66CCFF")
        )
        val css = tokens.cssVariables()
        assertTrue(css.contains("--neko-bg:#101010"))
        assertTrue(css.contains("--neko-primary:#66CCFF"))
        assertTrue(css.contains("--neko-radius:16px"))
        assertTrue(css.contains("color-scheme:dark"))
    }
}
