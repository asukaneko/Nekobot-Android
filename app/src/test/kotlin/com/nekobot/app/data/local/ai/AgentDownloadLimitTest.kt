package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDownloadLimitTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unknownLengthBodyCannotExceedLimitOrOverwriteExistingFile() = runBlocking {
        val root = temporary.newFolder()
        val target = root.resolve("demo.bin").apply { writeText("original") }
        val result = executor(root, 50L * 1024 * 1024 + 1).execute("download_file",
            mapOf("url" to "https://example.com/demo.bin", "save_path" to "demo.bin"))
        assertEquals(false, result["success"])
        assertEquals("original", target.readText())
        assertEquals(listOf("demo.bin"), root.listFiles()!!.map { it.name })
    }

    @Test fun ordinaryUnknownLengthDownloadStillSucceeds() = runBlocking {
        val root = temporary.newFolder()
        val result = executor(root, 12).execute("download_file", mapOf("url" to "https://example.com/demo.bin"))
        assertEquals(true, result["success"])
        assertEquals(12L, root.resolve("demo.bin").length())
    }

    private fun executor(root: java.io.File, responseBytes: Long): LocalAgentToolExecutor {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = -1L
                private val content = object : Source {
                    private var remaining = responseBytes
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (remaining == 0L) return -1
                        val count = minOf(remaining, byteCount, 8192).toInt()
                        sink.write(ByteArray(count))
                        remaining -= count
                        return count.toLong()
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
                override fun source(): BufferedSource = content
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        return LocalAgentToolExecutor("demo-session", root, LocalExecAuthorizationManager(100), {}, { emptyList() },
            globalAgentMemoryStore = null, httpClient = client)
    }
}
