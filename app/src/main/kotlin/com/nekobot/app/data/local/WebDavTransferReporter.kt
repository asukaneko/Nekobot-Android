package com.nekobot.app.data.local

import com.nekobot.app.data.model.WebDavTransferProgress
import com.nekobot.app.data.model.WebDavTransferStage
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer

/** 回调只作用于当前协程，后台自动同步与并行的配置请求不会串入界面进度。 */
internal object WebDavTransferReporter {
    private val listener = ThreadLocal<((WebDavTransferProgress) -> Unit)?>()

    suspend fun <T> track(onProgress: ((WebDavTransferProgress) -> Unit)?, block: suspend () -> T): T =
        withContext(listener.asContextElement(onProgress)) { block() }

    fun execute(request: Request, call: (Request) -> Response): Response {
        val callback = listener.get() ?: return call(request)
        // 空的协议探测与目录请求不属于文件传输。
        if (request.url.encodedPath.contains(".conditional-probe-")) return call(request)
        val name = request.url.pathSegments.lastOrNull().orEmpty().substringBefore(".upload-")
        val body = request.body
        val trackedRequest = if (request.method == "PUT" && body != null && body.contentLength() != 0L) {
            request.newBuilder().method(request.method, WebDavProgressRequestBody(body, name, callback)).build()
        } else request
        val response = call(trackedRequest)
        val responseBody = response.body
        return if (request.method == "GET" && response.isSuccessful && responseBody != null) {
            response.newBuilder().body(WebDavProgressResponseBody(responseBody, name, callback)).build()
        } else response
    }
}

internal class WebDavProgressRequestBody(
    private val delegate: RequestBody,
    private val fileName: String,
    private val onProgress: (WebDavTransferProgress) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = delegate.isOneShot()
    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        val counter = WebDavByteProgress(WebDavTransferStage.Uploading, fileName, contentLength(), onProgress)
        val trackingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                counter.advance(byteCount)
            }
        }.buffer()
        delegate.writeTo(trackingSink)
        trackingSink.flush()
        counter.finish()
    }
}

internal class WebDavProgressResponseBody(
    private val delegate: ResponseBody,
    fileName: String,
    onProgress: (WebDavTransferProgress) -> Unit
) : ResponseBody() {
    private val trackedSource: BufferedSource by lazy {
        val counter = WebDavByteProgress(WebDavTransferStage.Downloading, fileName, contentLength(), onProgress)
        object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count == -1L) counter.finish() else counter.advance(count)
                return count
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = trackedSource
}

private class WebDavByteProgress(
    private val stage: WebDavTransferStage,
    private val fileName: String,
    length: Long,
    private val onProgress: (WebDavTransferProgress) -> Unit
) {
    private val total = length.takeIf { it >= 0L }
    private var transferred = 0L
    private var lastUpdateNanos = System.nanoTime()
    private var finished = false

    init { emit(false) }

    fun advance(bytes: Long) {
        transferred += bytes
        val now = System.nanoTime()
        // 限制 UI 通知频率，传输完成时始终补发最终值。
        if (now - lastUpdateNanos >= 100_000_000L) {
            lastUpdateNanos = now
            emit(false)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        // 已知长度的截断响应不应显示 100%。
        emit(total == null || transferred == total)
    }

    private fun emit(complete: Boolean) {
        onProgress(WebDavTransferProgress(stage, fileName, transferred, total ?: transferred.takeIf { complete }, complete))
    }
}
