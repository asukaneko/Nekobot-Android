package com.nekobot.app.data.local

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.HashingSink
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import java.util.UUID

/** 首次创建清单依次尝试条件 PUT、禁止覆盖的 MOVE、检查后 PUT 并回读校验。 */
internal class WebDavConditionalWriter(private val execute: (Request) -> Response) {
    enum class CreateMode { IF_NONE_MATCH, MOVE_NO_OVERWRITE, CHECK_THEN_PUT }

    fun probe(rootUrl: String): CreateMode {
        val probeUrl = "${rootUrl}.conditional-probe-${UUID.randomUUID()}.tmp"
        try {
            val firstCode = put(probeUrl, "If-None-Match", "*")
            val mode = if (firstCode in SUCCESS_CODES) {
                val secondCode = put(probeUrl, "If-None-Match", "*")
                when {
                    secondCode == 412 -> CreateMode.IF_NONE_MATCH
                    secondCode in SUCCESS_CODES || secondCode in UNSUPPORTED_CODES -> {
                        probeFallback(probeUrl)
                    }
                    else -> error("探测 WebDAV 条件写入失败 (HTTP $secondCode)")
                }
            } else if (firstCode in UNSUPPORTED_CODES || firstCode == 412) {
                probeFallback(probeUrl)
            } else {
                error("探测 WebDAV 条件写入失败 (HTTP $firstCode)")
            }

            // 无论首次创建使用哪种方式，后续更新仍须验证 ETag 与 If-Match。
            val etag = execute(Request.Builder().url(probeUrl).get().build()).use {
                check(it.code == 200) { "读取 WebDAV 条件写入探测资源失败 (HTTP ${it.code})" }
                it.header("ETag")
            }
            require(!etag.isNullOrBlank()) { "WebDAV 服务器没有提供 ETag，无法安全进行多设备增量同步" }
            check(put(probeUrl, "If-Match", "\"nekobot-invalid-etag\"") == 412) {
                "WebDAV 服务器未执行 If-Match 条件写入，无法安全进行多设备增量同步"
            }
            val validCode = put(probeUrl, "If-Match", etag)
            check(validCode in SUCCESS_CODES) { "WebDAV 服务器拒绝有效的 If-Match 条件写入 (HTTP $validCode)" }
            return mode
        } finally {
            deleteQuietly(probeUrl)
        }
    }

    fun create(
        url: String,
        body: RequestBody,
        mode: CreateMode,
        onFallback: (CreateMode) -> Unit = {}
    ): Int {
        var currentMode = mode
        if (mode == CreateMode.IF_NONE_MATCH) {
            val code = execute(
                Request.Builder().url(url).put(body).header("If-None-Match", "*").build()
            ).use { it.code }
            // 412 表示目标已经存在，不能去掉条件头重传并覆盖它。
            if (code !in UNSUPPORTED_CODES) return code
            val probeUrl = "$url.conditional-probe-${UUID.randomUUID()}.tmp"
            try {
                currentMode = probeFallback(probeUrl)
            } finally {
                deleteQuietly(probeUrl)
            }
            onFallback(currentMode)
        }
        if (currentMode == CreateMode.CHECK_THEN_PUT) return checkedPut(url, body)
        val temporaryUrl = "$url.upload-${UUID.randomUUID()}.tmp"
        try {
            val uploadCode = execute(Request.Builder().url(temporaryUrl).put(body).build()).use { it.code }
            if (uploadCode !in SUCCESS_CODES) return uploadCode
            val moveCode = move(temporaryUrl, url)
            if (moveCode !in UNSUPPORTED_MOVE_CODES) return moveCode
            onFallback(CreateMode.CHECK_THEN_PUT)
            return checkedPut(url, body)
        } finally {
            deleteQuietly(temporaryUrl)
        }
    }

    private fun probeFallback(probeUrl: String): CreateMode =
        if (probeMove(probeUrl)) CreateMode.MOVE_NO_OVERWRITE else CreateMode.CHECK_THEN_PUT

    private fun probeMove(probeUrl: String): Boolean {
        val sourceUrl = "$probeUrl.source"
        try {
            val targetCode = put(probeUrl)
            check(targetCode in SUCCESS_CODES) { "WebDAV 回退上传失败 (HTTP $targetCode)" }
            val sourceCode = put(sourceUrl)
            check(sourceCode in SUCCESS_CODES) { "WebDAV 回退上传失败 (HTTP $sourceCode)" }
            val occupiedCode = move(sourceUrl, probeUrl)
            if (occupiedCode in UNSUPPORTED_MOVE_CODES || occupiedCode in SUCCESS_CODES) return false
            check(occupiedCode == 412) {
                "探测 WebDAV MOVE 回退失败 (HTTP $occupiedCode)"
            }
            val deleteCode = execute(Request.Builder().url(probeUrl).delete().build()).use { it.code }
            check(deleteCode in setOf(200, 202, 204)) { "清理 WebDAV 探测资源失败 (HTTP $deleteCode)" }
            val createCode = move(sourceUrl, probeUrl)
            if (createCode in UNSUPPORTED_MOVE_CODES) {
                val restoreCode = put(probeUrl)
                check(restoreCode in SUCCESS_CODES) { "WebDAV 回退上传失败 (HTTP $restoreCode)" }
                return false
            }
            check(createCode in SUCCESS_CODES) { "WebDAV MOVE 回退失败 (HTTP $createCode)" }
            return true
        } finally {
            deleteQuietly(sourceUrl)
        }
    }

    private fun checkedPut(url: String, body: RequestBody): Int {
        require(!body.isOneShot() && !body.isDuplex()) { "WebDAV 回退需要可重传的请求正文" }
        val expectedLength = body.contentLength()
        require(expectedLength >= 0L) { "WebDAV 回退需要已知文件长度" }
        val hashSink = HashingSink.sha256(blackholeSink())
        hashSink.buffer().use { body.writeTo(it) }
        val expectedHash = hashSink.hash

        // 此回退不具备原子创建能力。尽量紧邻 PUT 检查，已有对象按并发冲突处理。
        val headCode = execute(Request.Builder().url(url).head().build()).use { it.code }
        val existenceCode = if (headCode in UNSUPPORTED_HEAD_CODES) {
            execute(Request.Builder().url(url).get().build()).use { it.code }
        } else headCode
        if (existenceCode in 200..299) return 412
        if (existenceCode != 404) return existenceCode

        val putCode = execute(Request.Builder().url(url).put(body).build()).use { it.code }
        if (putCode !in SUCCESS_CODES) return putCode
        return execute(Request.Builder().url(url).get().build()).use { response ->
            if (response.code == 404) return@use 412
            if (response.code != 200) return@use if (response.isSuccessful) 412 else response.code
            val responseBody = response.body ?: return@use 412
            val source = HashingSource.sha256(responseBody.source())
            val scratch = Buffer()
            var received = 0L
            while (true) {
                val read = source.read(scratch, 8192L)
                if (read == -1L) break
                received += read
                scratch.clear()
                if (received > expectedLength) return@use 412
            }
            if (received == expectedLength && source.hash == expectedHash) putCode else 412
        }
    }

    private fun put(url: String, header: String? = null, value: String? = null): Int {
        val builder = Request.Builder().url(url).put(ByteArray(0).toRequestBody(BINARY_MEDIA_TYPE))
        if (header != null && value != null) builder.header(header, value)
        return execute(builder.build()).use { it.code }
    }

    private fun move(source: String, destination: String): Int = execute(
        Request.Builder().url(source).method("MOVE", null)
            .header("Destination", destination).header("Overwrite", "F").build()
    ).use { it.code }

    private fun deleteQuietly(url: String) {
        runCatching { execute(Request.Builder().url(url).delete().build()).use { } }
    }

    companion object {
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val SUCCESS_CODES = setOf(200, 201, 204)
        // 403 可能来自对条件头的限制；回退探测也必须能通过普通 PUT 的权限检查。
        private val UNSUPPORTED_CODES = setOf(400, 403, 405, 422, 428, 501)
        private val UNSUPPORTED_MOVE_CODES = UNSUPPORTED_CODES + setOf(404, 409)
        private val UNSUPPORTED_HEAD_CODES = setOf(400, 403, 405, 501)
    }
}
