package com.nekobot.app.data.local

import com.nekobot.app.data.model.WebDavTransferProgress
import com.nekobot.app.data.model.WebDavTransferStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WebDavTransferReporterTest {
    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Test
    fun uploadReportsActualBytesAndResetsForRetry() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val delegate = payload.toRequestBody("application/octet-stream".toMediaType())
        val body = WebDavProgressRequestBody(delegate, "config.nbotcfg", updates::add)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertArrayEquals(payload, buffer.readByteArray())
        assertEquals(0, updates.first().percent)
        assertEquals(payload.size.toLong(), updates.last().transferredBytes)
        assertEquals(100, updates.last().percent)
        assertEquals(WebDavTransferStage.Uploading, updates.last().stage)
        val previousSize = updates.size
        body.writeTo(Buffer())
        assertEquals(0L, updates[previousSize].transferredBytes)
        assertEquals(delegate.contentType(), body.contentType())
        assertEquals(delegate.contentLength(), body.contentLength())
    }

    @Test
    fun failedUploadNeverReportsComplete() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val delegate = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(payload, 0, 8192)
                sink.flush()
                throw IOException("网络中断")
            }
        }
        try {
            WebDavProgressRequestBody(delegate, "file", updates::add).writeTo(Buffer())
            fail("应报告网络中断")
        } catch (_: IOException) { }
        assertFalse(updates.any { it.completed || it.percent == 100 })
    }

    @Test
    fun downloadReportsRealBytesAndPreservesContent() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val body = WebDavProgressResponseBody(responseBody(payload.size.toLong()), "file", updates::add)
        assertArrayEquals(payload, body.bytes())
        assertEquals(0, updates.first().percent)
        assertEquals(payload.size.toLong(), updates.last().transferredBytes)
        assertEquals(100, updates.last().percent)
        assertEquals(WebDavTransferStage.Downloading, updates.last().stage)
    }

    @Test
    fun unknownDownloadLengthRemainsIndeterminateUntilEof() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val body = WebDavProgressResponseBody(responseBody(-1L), "file", updates::add)
        assertArrayEquals(payload, body.bytes())
        assertNull(updates.first().percent)
        assertEquals(100, updates.last().percent)
        assertEquals(payload.size.toLong(), updates.last().totalBytes)
    }

    @Test
    fun closingPartialDownloadDoesNotReportComplete() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val body = WebDavProgressResponseBody(responseBody(payload.size.toLong()), "file", updates::add)
        body.source().readByte()
        body.close()
        assertFalse(updates.any { it.completed || it.percent == 100 })
    }

    @Test
    fun truncatedDownloadDoesNotReportComplete() {
        val updates = mutableListOf<WebDavTransferProgress>()
        val body = WebDavProgressResponseBody(responseBody(payload.size.toLong() + 1), "file", updates::add)
        body.source().readByteArray()
        assertFalse(updates.last().completed)
        assertTrue(updates.last().percent!! < 100)
    }

    @Test
    fun callbackSurvivesDispatcherSwitchAndDoesNotLeak() = runBlocking {
        val updates = mutableListOf<WebDavTransferProgress>()
        val request = Request.Builder().url("https://dav.example/config.nbotcfg").get().build()
        val call: (Request) -> Response = {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody(payload.size.toLong())).build()
        }
        WebDavTransferReporter.track(updates::add) {
            withContext(Dispatchers.IO) {
                WebDavTransferReporter.execute(request, call).use { it.body!!.bytes() }
            }
        }
        assertEquals(100, updates.last().percent)
        val size = updates.size
        withContext(Dispatchers.IO) {
            WebDavTransferReporter.execute(request, call).use { it.body!!.bytes() }
        }
        assertEquals(size, updates.size)
    }

    private fun responseBody(length: Long) = object : ResponseBody() {
        private val source = Buffer().write(payload)
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = source
    }
}
