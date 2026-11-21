package com.nekobot.app.data.local.sessionimport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 链接导入的回归测试。
 *
 * 单元测试用本地 MockWebServer，因此注入空的地址校验器；生产环境固定使用
 * [SessionArchiveDownloader.requirePublicUrl]（公网 HTTPS）校验。
 */
class SessionArchiveDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder(File("build/session-import-tests").apply { mkdirs() })

    @Test
    fun `follows a redirect and reports the downloaded size`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/archive.zip").toString()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("synthetic archive bytes"))
        try {
            var lastDownloaded = 0L
            var lastTotal: Long? = null
            val file = downloader().download(
                url = server.url("/start").toString(),
                targetDir = temp.root,
                onProgress = { downloaded, total ->
                    lastDownloaded = downloaded
                    lastTotal = total
                }
            )
            assertEquals("synthetic archive bytes", file.readText())
            assertEquals(file.length(), lastDownloaded)
            assertEquals(file.length(), lastTotal ?: -1L)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http error becomes a download failure and leaves no temp file`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.start()
        try {
            downloader().download(server.url("/archive.zip").toString(), temp.root)
            fail("expected DownloadFailed")
        } catch (error: SessionImportException.DownloadFailed) {
            assertTrue(error.reason.contains("404"))
            assertTrue(temp.root.listFiles().orEmpty().none { it.name.endsWith(".zip") })
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = SessionImportException.InvalidUrl::class)
    fun `plain http is rejected by the default validator`() {
        SessionArchiveDownloader.requirePublicUrl(SessionArchiveDownloader.parseUrl("http://example.com/export.zip"))
    }

    @Test(expected = SessionImportException.InvalidUrl::class)
    fun `local host is rejected`() {
        SessionArchiveDownloader.requirePublicUrl(SessionArchiveDownloader.parseUrl("https://localhost/export.zip"))
    }

    @Test(expected = SessionImportException.InvalidUrl::class)
    fun `malformed link is rejected`() {
        SessionArchiveDownloader.parseUrl("not a url")
    }

    @Test
    fun `query string does not affect URL parsing`() {
        val url = SessionArchiveDownloader.parseUrl(
            "https://example.com/export.zip?response-content-disposition=attachment%3B%20filename%3Ddemo.zip&Expires=1"
        )
        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/export.zip", url.encodedPath)
    }

    private fun downloader() = SessionArchiveDownloader(
        client = OkHttpClient(),
        validateUrl = { }
    )
}
