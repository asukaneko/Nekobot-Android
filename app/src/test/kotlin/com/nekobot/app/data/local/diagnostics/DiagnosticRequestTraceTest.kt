package com.nekobot.app.data.local.diagnostics

import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticRequestTraceTest {
    private val model = LocalAiModelEntity("trace", "合成GLM", protocol="openai_chat", model="fixture",
        baseUrl="https://example.test/v1", apiKey="fixture-private-credential", createdAt="now")
    private fun fixture(block: (DiagnosticTraceStore, DiagnosticTraceSession) -> Unit) {
        val dir = Files.createTempDirectory("request-trace").toFile()
        try { val store = DiagnosticTraceStore(dir); block(store, DiagnosticTraceSession(store,"attempt","p","s","t")) }
        finally { dir.deleteRecursively() }
    }
    private fun events(store: DiagnosticTraceStore, trace: DiagnosticTraceSession, kind: String) =
        store.read(trace.operation,"p","s").filter { it["kind"].asString == kind }.map { it["data"].asJsonObject }

    @Test fun largePromptsKeepEveryCharacterAndTechnicalFields() {
        val text = "中文上下文。".repeat(100_000) + "END-OF-PROMPT"
        val id = "a".repeat(80)
        val value = DiagnosticTraceText.json(mapOf("messages" to listOf(mapOf("role" to "system", "content" to text)),
            "max_tokens" to 6000, "reasoning_effort" to "high", "message_id" to id,
            "tools" to listOf(mapOf("parameters" to mapOf("properties" to mapOf("query" to mapOf("type" to "string"))))))).asJsonObject
        assertEquals(text, value["messages"].asJsonArray[0].asJsonObject["content"].asString)
        assertEquals(6000,value["max_tokens"].asInt); assertEquals(id,value["message_id"].asString)
        assertTrue(value.toString().contains("properties")); assertTrue(value.toString().contains("reasoning_effort"))
    }

    @Test fun credentialsAndNestedToolArgumentsAreScrubbedWithoutRemovingTokenBudgets() {
        val input = JsonParser.parseString("""{"max_tokens":6000,"tokens_left":4000,"api_key":"secret-one",
            "arguments":"{\"password\":\"secret-two\",\"query\":\"tea\"}",
            "content":"Authorization: Bearer secret-three\nthought sk-secret-four",
            "thinkingSignature":"secret-five","image_url":{"url":"data:image/png;base64,secret-six"},
            "endpoint":"https://example.test/v1?key=secret-seven"}""")
        val out = DiagnosticTraceText.json(input).toString()
        listOf("one","two","three","four","five","six","seven").forEach { assertFalse(out.contains("secret-$it")) }
        assertTrue(out.contains("6000")); assertTrue(out.contains("4000")); assertTrue(out.contains("tea"))
    }

    @Test fun jsonInsidePromptsKeepsItsOriginalWhitespaceWhenNoRedactionIsNeeded() {
        val prompt="{\n    \"示例\": \"角色心声\",\n    \"max_tokens\": 100\n}\n"
        val result=DiagnosticTraceText.json(mapOf("content" to prompt)).asJsonObject
        assertEquals(prompt,result["content"].asString)
    }

    @Test fun traceSurvivesReopenAndRejectsOtherProfilesAndSessions() = fixture { store, trace ->
        trace.record("pipeline_prepared", mapOf("prompt" to "角色中文提示"))
        repeat(180) { trace.record("parsed_reply", mapOf("content" to "第${it}段")) }
        val all = store.read(trace.operation,"p.db","s")
        assertEquals(181,all.size); assertTrue(all.last().toString().contains("第179段"))
        assertThrows(IllegalArgumentException::class.java) { store.read(trace.operation,"other","s") }
        assertThrows(IllegalArgumentException::class.java) { store.read(trace.operation,"p","other") }
    }

    @Test fun sseCredentialSplitAcrossDeltasIsRedactedButFullSafeContentSurvives() {
        val raw = listOf("正文开头 ","fixture-private-","credential"," 正文结尾").joinToString("\n\n") {
            "data: "+com.google.gson.Gson().toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to it)))))
        } + "\n\ndata: [DONE]\n\n"
        val safe=DiagnosticTraceText.response(raw,listOf(model.apiKey))
        assertFalse(safe.contains("fixture-private-")); assertFalse(safe.contains("credential"))
        assertTrue(safe.contains("正文开头")); assertTrue(safe.contains("正文结尾"))
        assertTrue(safe.contains("data: [DONE]"))
    }

    @Test fun compatibilityRetryCapturesBothActualPayloadsAndRawResponses() = fixture { store, trace ->
        val sent = mutableListOf<String>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            sent += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val first = sent.size == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if(first)400 else 200).message("fixture").body(
                    (if(first) """{"error":"unsupported temperature"}""" else
                        """{"choices":[{"message":{"content":"（普通括号心声）你好","reasoning_content":"native thought"},"finish_reason":"stop"}]}""")
                        .toResponseBody("application/json".toMediaType())).build()
        }.build())
        runBlocking(trace.context("model")) {
            val result = client.chatOnce(model,listOf(mapOf("role" to "system","content" to "完整提示")),
                mapOf("temperature" to 0.7,"max_tokens" to 6000))
            assertTrue(result.content.contains("普通括号"))
        }
        val requests = events(store,trace,"http_request")
        assertEquals(2,requests.size)
        requests.forEachIndexed { i,event -> assertEquals(JsonParser.parseString(sent[i]),event["payload"]) }
        assertTrue(requests[0]["payload"].asJsonObject.has("temperature"))
        assertFalse(requests[1]["payload"].asJsonObject.has("temperature"))
        val responses = events(store,trace,"http_response")
        assertEquals(listOf(400,200),responses.map { it["status"].asInt })
        assertTrue(responses.last()["body"].asString.contains("native thought"))
        assertFalse(store.read(trace.operation,"p","s").toString().contains(model.apiKey))
    }

    @Test fun partialStreamingBodyIsKeptWhenReaderStopsBeforeEof() = fixture { store, trace ->
        val http = trace.context("model").request(emptyMap(),JsonParser.parseString("{}"),emptyList())
        val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"已收到的部分\"}}]}\n\n".toByteArray()
        val raw = object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            private val buffered = object : okio.Source {
                var delivered = false
                override fun timeout() = okio.Timeout.NONE
                override fun close() {}
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if(delivered) throw java.io.IOException("cancelled")
                    delivered=true; sink.write(bytes); return bytes.size.toLong()
                }
            }.buffer()
            override fun source() = buffered
        }
        val observed = http.wrap(raw,200)
        assertEquals(String(bytes,Charsets.UTF_8),observed.source().readUtf8(bytes.size.toLong()))
        assertThrows(java.io.IOException::class.java) { observed.source().readByte() }
        observed.close()
        val result=events(store,trace,"http_response").single()
        assertFalse(result["complete"].asBoolean)
        assertTrue(result["body"].asString.contains("已收到的部分"))
        assertTrue(result["error"].asString.contains("cancelled"))
    }

    @Test fun recordingFailureNeverBreaksChat() {
        val file = Files.createTempFile("not-a-directory",".tmp").toFile()
        try {
            var losses=0
            val trace=DiagnosticTraceSession(DiagnosticTraceStore(file),"a","p","s","t") { losses++ }
            trace.record("pipeline_prepared",mapOf("content" to "still answers"))
            assertEquals(1,losses)
        } finally { file.delete() }
    }

    @Test fun retentionRemovesWholeCompletedAttemptsAndKeepsCurrentLongAttempt() {
        val dir = Files.createTempDirectory("trace-retention").toFile()
        try {
            val store=DiagnosticTraceStore(dir,maxAttempts=1)
            val first=DiagnosticTraceSession(store,"first","p","s","t")
            first.record("http_request",mapOf("content" to "old"))
            first.record("saved_reply",mapOf("content" to "done"))
            val second=DiagnosticTraceSession(store,"second","p","s","t2")
            second.record("http_request",mapOf("content" to "new"))
            assertTrue(store.read(first.operation,"p","s").isEmpty())
            assertEquals(1,store.read(second.operation,"p","s").size)
        } finally { dir.deleteRecursively() }
    }
}
