package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalMcpServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LocalMcpRuntimeTest {

    @Test
    fun toolNameMatchesUpstreamMcpBridgeFormat() {
        val fullName = makeMcpToolName(
            serverId = "12345678-abcd-ef00-1122-334455667788",
            toolName = "search_files"
        )

        assertEquals("mcp__12345678__search_files", fullName)
        assertEquals("12345678" to "search_files", parseMcpToolName(fullName))
        assertNull(parseMcpToolName("search_files"))
    }

    @Test
    fun parsesJsonStreamableHttpResponse() {
        val messages = parseMcpHttpMessages(
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
        )

        assertEquals(1, messages.size)
        assertEquals(1, messages.single().get("id").asInt)
        assertNotNull(messages.single().getAsJsonObject("result"))
    }

    @Test
    fun parsesSseAndKeepsNotificationsAndTargetResponse() {
        val payload = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}

            id: event-2
            event: message
            data: {"jsonrpc":"2.0","id":7,"result":{"tools":[{"name":"echo"}]}}
        """.trimIndent()

        val messages = parseMcpHttpMessages(payload)

        assertEquals(2, messages.size)
        assertEquals("notifications/progress", messages[0].get("method").asString)
        assertEquals(7, messages[1].get("id").asInt)
        assertEquals(
            "echo",
            messages[1].getAsJsonObject("result")
                .getAsJsonArray("tools")
                .single()
                .asJsonObject
                .get("name")
                .asString
        )
    }

    @Test
    fun parsesJsonRpcBatchResponse() {
        val messages = parseMcpHttpMessages(
            """
            [
              {"jsonrpc":"2.0","method":"notifications/message","params":{}},
              {"jsonrpc":"2.0","id":"9","result":{}}
            ]
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertEquals("9", messages[1].get("id").asString)
    }

    @Test
    fun streamableHttpCompletesHandshakeListsAndCallsTools() {
        FakeMcpHttpServer().use { server ->
            val runtime = LocalMcpRuntime()
            val config = LocalMcpServerEntity(
                id = "12345678-abcd-ef00-1122-334455667788",
                name = "fake",
                transport = "streamable-http",
                url = server.url,
                createdAt = "2026-07-18T00:00:00"
            )

            val tools = runtime.connect(config)
            assertEquals(listOf("echo"), tools.map { it.name })

            val definitions = runtime.getOpenAiToolDefinitions()
            @Suppress("UNCHECKED_CAST")
            val function = definitions.single()["function"] as Map<String, Any>
            assertEquals("mcp__12345678__echo", function["name"])

            val result = runtime.executeByFullName(
                "mcp__12345678__echo",
                mapOf("text" to "hello")
            )
            assertEquals("hello", result["echo"])

            runtime.disconnect(config.id)
            server.awaitDone()

            assertEquals(
                listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
                server.methods
            )
            val operationalHeaders = server.headers.drop(1)
            assertTrue(operationalHeaders.all { it["mcp-session-id"] == "fake-session" })
            assertTrue(operationalHeaders.all { it["mcp-protocol-version"] == "2025-11-25" })
            assertFalse(runtime.isConnected(config.id))
        }
    }

    private class FakeMcpHttpServer : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val methods = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val url = "http://127.0.0.1:${socket.localPort}/mcp"

        private val worker = thread(name = "fake-mcp-http", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    var shouldStop = false
                    try {
                        val input = BufferedInputStream(client.getInputStream())
                        val requestLine = readLine(input)
                        if (requestLine == null) {
                            continue
                        }
                        val requestHeaders = linkedMapOf<String, String>()
                        while (true) {
                            val line = readLine(input) ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) {
                                requestHeaders[line.substring(0, separator).trim().lowercase()] =
                                    line.substring(separator + 1).trim()
                            }
                        }

                        val length = requestHeaders["content-length"]?.toIntOrNull() ?: 0
                        val body = if (length > 0) {
                            String(input.readNBytes(length), StandardCharsets.UTF_8)
                        } else {
                            ""
                        }

                        if (requestLine.startsWith("DELETE ")) {
                            writeResponse(client.getOutputStream(), 200, "")
                            shouldStop = true
                        } else {
                            val request = JsonParser.parseString(body).asJsonObject
                            val method = request.get("method").asString
                            methods += method
                            headers += requestHeaders
                            when (method) {
                                "notifications/initialized" ->
                                    writeResponse(client.getOutputStream(), 202, "")
                                "initialize" -> {
                                    val result = JsonObject().apply {
                                        addProperty("protocolVersion", "2025-11-25")
                                        add("capabilities", JsonObject().apply {
                                            add("tools", JsonObject())
                                        })
                                        add("serverInfo", JsonObject().apply {
                                            addProperty("name", "fake")
                                            addProperty("version", "1.0")
                                        })
                                    }
                                    writeJsonRpcResult(
                                        client.getOutputStream(),
                                        request,
                                        result,
                                        sessionId = "fake-session"
                                    )
                                }
                                "tools/list" -> {
                                    val result = JsonObject().apply {
                                        add("tools", com.google.gson.JsonArray().apply {
                                            add(JsonObject().apply {
                                                addProperty("name", "echo")
                                                addProperty("description", "Echo text")
                                                add("inputSchema", JsonObject().apply {
                                                    addProperty("type", "object")
                                                    add("properties", JsonObject().apply {
                                                        add("text", JsonObject().apply {
                                                            addProperty("type", "string")
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    }
                                    writeJsonRpcResult(client.getOutputStream(), request, result)
                                }
                                "tools/call" -> {
                                    val text = request.getAsJsonObject("params")
                                        .getAsJsonObject("arguments")
                                        .get("text")
                                        .asString
                                    val result = JsonObject().apply {
                                        add("content", com.google.gson.JsonArray().apply {
                                            add(JsonObject().apply {
                                                addProperty("type", "text")
                                                addProperty("text", """{"echo":"$text"}""")
                                            })
                                        })
                                        addProperty("isError", false)
                                    }
                                    writeJsonRpcResult(client.getOutputStream(), request, result)
                                }
                                else -> error("Unexpected method: $method")
                            }
                        }
                    } finally {
                        client.close()
                    }
                    if (shouldStop) break
                }
            } catch (error: Throwable) {
                if (!(socket.isClosed && error is SocketException)) failure.set(error)
            }
        }

        fun awaitDone() {
            worker.join(2_000)
            assertFalse("Fake MCP server did not stop", worker.isAlive)
            failure.get()?.let { throw AssertionError("Fake MCP server failed", it) }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Fake MCP server failed", it) }
        }

        private fun writeJsonRpcResult(
            output: java.io.OutputStream,
            request: JsonObject,
            result: JsonObject,
            sessionId: String? = null
        ) {
            val response = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", request.get("id"))
                add("result", result)
            }
            writeResponse(output, 200, response.toString(), sessionId)
        }

        private fun writeResponse(
            output: java.io.OutputStream,
            status: Int,
            body: String,
            sessionId: String? = null
        ) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = if (status == 202) "Accepted" else "OK"
            val responseHeaders = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                sessionId?.let { append("Mcp-Session-Id: $it\r\n") }
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            output.write(responseHeaders)
            output.write(bytes)
            output.flush()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString("UTF-8")
        }
    }
}
