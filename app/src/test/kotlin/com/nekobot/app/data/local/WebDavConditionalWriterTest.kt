package com.nekobot.app.data.local

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class WebDavConditionalWriterTest {
    private val root = "https://dav.example/backup/"
    private val target = "${root}manifest.nksync"
    private val content = "encrypted-manifest".toByteArray()
    private val body get() = content.toRequestBody("application/octet-stream".toMediaType())

    @Test
    fun supportedServerKeepsConditionalPut() {
        val dav = FakeDav()
        val writer = WebDavConditionalWriter(dav::execute)
        assertEquals(WebDavConditionalWriter.CreateMode.IF_NONE_MATCH, writer.probe(root))
        assertTrue(dav.files.isEmpty())
        assertEquals(201, writer.create(target, body, writer.probe(root)))
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals("*", dav.requests.last { it.method == "PUT" && it.url.toString() == target }.header("If-None-Match"))
        assertFalse(dav.requests.any { it.method == "MOVE" })
    }

    @Test
    fun rejectedConditionalHeaderFallsBackToMove() {
        val dav = FakeDav(rejectedIfNoneMatch = 400)
        val writer = WebDavConditionalWriter(dav::execute)
        val mode = writer.probe(root)
        assertEquals(WebDavConditionalWriter.CreateMode.MOVE_NO_OVERWRITE, mode)
        assertTrue(dav.files.isEmpty())
        assertEquals(201, writer.create(target, body, mode))
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals(setOf(target), dav.files.keys)
        assertTrue(dav.requests.filter { it.method == "MOVE" }.all { it.header("Overwrite") == "F" })
    }

    @Test
    fun silentlyIgnoredConditionalHeaderFallsBackToMove() {
        val dav = FakeDav(ignoreIfNoneMatch = true)
        assertEquals(WebDavConditionalWriter.CreateMode.MOVE_NO_OVERWRITE, WebDavConditionalWriter(dav::execute).probe(root))
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun realPreconditionConflictNeverRetriesWithoutCondition() {
        val dav = FakeDav()
        dav.files[target] = Stored("other-device".toByteArray(), "\"v1\"")
        val fallbackModes = mutableListOf<WebDavConditionalWriter.CreateMode>()
        val code = WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.IF_NONE_MATCH, fallbackModes::add)
        assertEquals(412, code)
        assertEquals("other-device", dav.files[target]!!.bytes.toString(Charsets.UTF_8))
        assertTrue(fallbackModes.isEmpty())
        assertEquals(1, dav.requests.size)
    }

    @Test
    fun cachedCapabilityCanFallbackWithoutOverwritingExistingManifest() {
        val dav = FakeDav(rejectedIfNoneMatch = 501)
        dav.files[target] = Stored("other-device".toByteArray(), "\"v1\"")
        var fallback: WebDavConditionalWriter.CreateMode? = null
        val code = WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.IF_NONE_MATCH) { fallback = it }
        assertEquals(412, code)
        assertEquals(WebDavConditionalWriter.CreateMode.MOVE_NO_OVERWRITE, fallback)
        assertEquals("other-device", dav.files[target]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(setOf(target), dav.files.keys)
    }

    @Test
    fun fallbackCreatesManifestAfterCachedSupportChanges() {
        val dav = FakeDav(rejectedIfNoneMatch = 405)
        assertEquals(201, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.IF_NONE_MATCH))
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals(setOf(target), dav.files.keys)
    }

    @Test
    fun ignoredOverwriteProtectionFallsBackToCheckedPut() {
        val dav = FakeDav(rejectedIfNoneMatch = 400, ignoreOverwrite = true)
        assertEquals(WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT, WebDavConditionalWriter(dav::execute).probe(root))
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun ignoredIfMatchProtectionStillFailsProbe() {
        val dav = FakeDav(rejectedIfNoneMatch = 400, ignoreIfMatch = true)
        assertFails { WebDavConditionalWriter(dav::execute).probe(root) }
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun authenticationFailureDoesNotTriggerFallback() {
        val dav = FakeDav(rejectedIfNoneMatch = 401)
        assertFails { WebDavConditionalWriter(dav::execute).probe(root) }
        assertFalse(dav.requests.any { it.method == "MOVE" })
        assertFalse(dav.requests.any { it.method == "PUT" && it.header("If-None-Match") == null })
    }

    @Test
    fun missingMoveSupportFallsBackToDirectPutAndReadback() {
        val dav = FakeDav(rejectedIfNoneMatch = 400, rejectedMove = 405)
        val writer = WebDavConditionalWriter(dav::execute)
        val mode = writer.probe(root)
        assertEquals(WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT, mode)
        assertTrue(dav.files.isEmpty())
        dav.requests.clear()
        assertEquals(201, writer.create(target, body, mode))
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals(listOf("HEAD", "PUT", "GET"), dav.requests.map { it.method })
        assertNull(dav.requests[1].header("If-None-Match"))
        assertEquals(setOf(target), dav.files.keys)
    }

    @Test
    fun missingHeadSupportUsesGetForExistenceCheck() {
        for (status in listOf(400, 403, 405, 501)) {
            val dav = FakeDav(rejectedHead = status)
            assertEquals(201, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
            assertEquals(listOf("HEAD", "GET", "PUT", "GET"), dav.requests.map { it.method })
            assertArrayEquals(content, dav.files[target]!!.bytes)
        }
    }

    @Test
    fun checkedPutDoesNotOverwriteExistingManifest() {
        val dav = FakeDav()
        dav.files[target] = Stored("other-device".toByteArray(), "\"v1\"")
        assertEquals(412, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
        assertEquals("other-device", dav.files[target]!!.bytes.toString(Charsets.UTF_8))
        assertEquals(listOf("HEAD"), dav.requests.map { it.method })
    }

    @Test
    fun cachedMoveCapabilityFallsBackAndRemovesTemporaryUpload() {
        val dav = FakeDav(rejectedMove = 501)
        var fallback: WebDavConditionalWriter.CreateMode? = null
        assertEquals(201, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.MOVE_NO_OVERWRITE) { fallback = it })
        assertEquals(WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT, fallback)
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals(setOf(target), dav.files.keys)
    }

    @Test
    fun cachedConditionalCapabilityCanFallBackThroughUnsupportedMove() {
        val dav = FakeDav(rejectedIfNoneMatch = 501, rejectedMove = 405)
        var fallback: WebDavConditionalWriter.CreateMode? = null
        assertEquals(201, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.IF_NONE_MATCH) { fallback = it })
        assertEquals(WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT, fallback)
        assertArrayEquals(content, dav.files[target]!!.bytes)
        assertEquals(setOf(target), dav.files.keys)
    }

    @Test
    fun changedReadbackIsReportedAsConflict() {
        val dav = FakeDav()
        dav.afterPut = { url ->
            if (url == target) dav.files[url] = Stored("other-device".toByteArray(), "\"new\"")
        }
        assertEquals(412, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
        assertEquals("other-device", dav.files[target]!!.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun failingExistenceCheckNeverUploads() {
        val dav = FakeDav(rejectedHead = 500)
        assertEquals(500, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
        assertEquals(listOf("HEAD"), dav.requests.map { it.method })
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun unauthorizedExistenceCheckNeverRetriesWithGetOrPut() {
        val dav = FakeDav(rejectedHead = 401)
        assertEquals(401, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
        assertEquals(listOf("HEAD"), dav.requests.map { it.method })
    }

    @Test
    fun moveServerErrorIsNotMisclassifiedAsUnsupported() {
        val dav = FakeDav(rejectedIfNoneMatch = 400, rejectedMove = 500)
        assertFails { WebDavConditionalWriter(dav::execute).probe(root) }
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun unexpectedSuccessfulReadbackStatusDoesNotClaimVerifiedUpload() {
        val dav = FakeDav()
        dav.afterPut = { dav.rejectedGet = 204 }
        assertEquals(412, WebDavConditionalWriter(dav::execute).create(target, body, WebDavConditionalWriter.CreateMode.CHECK_THEN_PUT))
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("应拒绝不安全的条件写入") } catch (_: IllegalStateException) { }
    }

    private data class Stored(val bytes: ByteArray, val etag: String)

    private class FakeDav(
        val rejectedIfNoneMatch: Int? = null,
        val ignoreIfNoneMatch: Boolean = false,
        val ignoreIfMatch: Boolean = false,
        val ignoreOverwrite: Boolean = false,
        val rejectedMove: Int? = null,
        val rejectedHead: Int? = null
    ) {
        val files = linkedMapOf<String, Stored>()
        val requests = mutableListOf<Request>()
        var afterPut: ((String) -> Unit)? = null
        var rejectedGet: Int? = null
        private var revision = 0

        fun execute(request: Request): Response {
            requests += request
            val url = request.url.toString()
            val existing = files[url]
            var code = 200
            var responseBytes = byteArrayOf()
            when (request.method) {
                "PUT" -> {
                    val none = request.header("If-None-Match")
                    val match = request.header("If-Match")
                    code = when {
                        none != null && rejectedIfNoneMatch != null -> rejectedIfNoneMatch
                        none != null && !ignoreIfNoneMatch && existing != null -> 412
                        match != null && !ignoreIfMatch && match != existing?.etag -> 412
                        else -> {
                            val buffer = Buffer()
                            request.body!!.writeTo(buffer)
                            files[url] = Stored(buffer.readByteArray(), "\"v${++revision}\"")
                            afterPut?.invoke(url)
                            if (existing == null) 201 else 204
                        }
                    }
                }
                "HEAD" -> code = rejectedHead ?: if (existing == null) 404 else 200
                "GET" -> if (rejectedGet != null) code = rejectedGet!! else if (existing == null) code = 404 else responseBytes = existing.bytes
                "DELETE" -> { files.remove(url); code = 204 }
                "MOVE" -> {
                    val destination = request.header("Destination")!!
                    code = when {
                        rejectedMove != null -> rejectedMove
                        existing == null -> 404
                        files.containsKey(destination) && request.header("Overwrite") == "F" && !ignoreOverwrite -> 412
                        else -> {
                            val occupied = files.containsKey(destination)
                            files[destination] = existing
                            files.remove(url)
                            if (occupied) 204 else 201
                        }
                    }
                }
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test")
                .apply { files[url]?.etag?.let { header("ETag", it) } }
                .body(responseBytes.toResponseBody()).build()
        }
    }
}
