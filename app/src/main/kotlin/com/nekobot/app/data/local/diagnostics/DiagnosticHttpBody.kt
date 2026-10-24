package com.nekobot.app.data.local.diagnostics

import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Observe only bytes the client consumes. Never drain the network after a user stops. */
internal fun DiagnosticHttpTrace.wrap(body: ResponseBody, status: Int): ResponseBody {
    val capture = Buffer()
    var eof = false
    var failure: String? = null
    val source = object : ForwardingSource(body.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            return try {
                val offset = sink.size
                val count = super.read(sink, byteCount)
                if (count < 0) eof = true
                else if (count > 0) sink.copyTo(capture, offset, count)
                count
            } catch (error: Exception) {
                failure = error.javaClass.simpleName + ": " + error.message.orEmpty()
                throw error
            }
        }
        override fun close() {
            try { super.close() } finally {
                val complete = eof || (body.contentLength() >= 0 && capture.size == body.contentLength())
                finish(status, capture.clone().readUtf8(), complete, failure)
            }
        }
    }.buffer()
    return object : ResponseBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): BufferedSource = source
    }
}
