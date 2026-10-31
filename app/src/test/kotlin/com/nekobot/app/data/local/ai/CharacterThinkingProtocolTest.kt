package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** 实际协议请求/响应：原生思考、正文兼容、调用次数与工具续接。 */
class CharacterThinkingProtocolTest {
    private val user = mapOf<String, Any>("role" to "user", "content" to "哈哈")
    private val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "林小满", "小星")
    private val messages get() = withCharacterThinkingRequest(listOf(user), prompt)
    private val tools = listOf(mapOf("type" to "function", "function" to mapOf(
        "name" to "lookup", "parameters" to mapOf("type" to "object"))))
    private fun extra(effort: ReasoningEffort = ReasoningEffort.HIGH,
        custom: CustomReasoningParam = CustomReasoningParam("thinking.type", "enabled")) =
        mutableMapOf<String, Any?>("temperature" to 0.7, "tools" to tools).apply {
            putReasoningParams(this, effort, true) { custom }
        }
    private fun model(id: String = "mock", protocol: String = OpenAIChatProtocol.name,
        provider: String? = null, modelName: String = "custom-chat") = LocalAiModelEntity(
        id = id, name = id, protocol = protocol, provider = provider, model = modelName,
        apiKey = "test-key", baseUrl = "https://api.example.com/v1", createdAt = "2026-10-15")
    private class Http(val response: (Int) -> Triple<Int, String, String>) {
        val bodies = mutableListOf<JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            val (code, body, type) = response(bodies.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Mock")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build())
    }
    private fun json(body: String) = Triple(200, body, "application/json")
    private fun answer(text: String = "怎么啦？", thinking: String = "我听见小星笑了。") =
        Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text,
            "reasoning_content" to thinking), "finish_reason" to "stop"))))
    private fun sse(payloads: List<String>) = Triple(200, buildString {
        payloads.forEach { append("data: $it\n\n") }; append("data: [DONE]\n\n")
    }, "text/event-stream")
    private fun delta(key: String, value: String) = Gson().toJson(mapOf(
        "choices" to listOf(mapOf("delta" to mapOf(key to value)))))
    private fun assertSystemOnly(body: JsonObject) {
        val sent = body["messages"].asJsonArray
        assertEquals(2, sent.size())
        assertEquals("system", sent[0].asJsonObject["role"].asString)
        assertEquals("## $CHARACTER_THINKING_STYLE_KEY\n$prompt", sent[0].asJsonObject["content"].asString)
        assertEquals("user", sent[1].asJsonObject["role"].asString)
        assertEquals("哈哈", sent[1].asJsonObject["content"].asString)
        assertFalse(body.has(CHARACTER_THINKING_EXTRA))
        assertFalse(body.has(CustomReasoningParam.EXTRA_KEY))
    }

    @Test
    fun nativeThinkingSurvivesWithoutTagsWithOnlySystemInstructionsAndOneCall() = runBlocking {
        val http = Http { json(answer()) }
        val result = characterThinkingResult(http.client.chatOnce(model(), messages, extra()), true)
        assertEquals("怎么啦？", result.content)
        assertEquals("我听见小星笑了。", result.thinkingContent)
        assertEquals(1, http.bodies.size)
        assertEquals("high", http.bodies.single()["reasoning_effort"].asString)
        assertSystemOnly(http.bodies.single())
        assertSame(user, messages.last())
        for (required in listOf("简体中文", "第一人称")) assertTrue(prompt.contains(required))
        // 2026-10-22 起默认不再教独白，末尾思考提示也不写这种格式。
        assertFalse(prompt.contains("（内心：…）"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun imageUserAndExistingAssistantHistoryAreNotModifiedOrAppendedWithPrompts() = runBlocking {
        val images = listOf(mapOf("type" to "text", "text" to "原话"),
            mapOf("type" to "image_url", "image_url" to mapOf("url" to "mock-image")))
        val input = listOf(mapOf<String, Any>("role" to "assistant", "content" to "上次回答"),
            mapOf("role" to "user", "content" to images))
        val http = Http { json(answer()) }
        http.client.chatOnce(model(), withCharacterThinkingRequest(input, prompt), extra())
        val sent = http.bodies.single()["messages"].asJsonArray
        assertEquals(3, sent.size())
        assertEquals("上次回答", sent[1].asJsonObject["content"].asString)
        assertEquals(Gson().toJsonTree(images), sent[2].asJsonObject["content"])
        assertSame(images, input.last()["content"])
    }

    @Test
    fun plainContentIsNotReclassifiedAndTaggedFallbackDoesNotNeedPrefill() = runBlocking {
        for ((text, native, expected) in listOf(Triple("普通正文", "", ""),
            Triple("", "我还在想。", "我还在想。"),
            Triple("<THINKING>\n我想起小星了。</THINKING>\n你好", "按thinking_style放在思考和回复里。", "我想起小星了。"))) {
            val http = Http { json(answer(text, native)) }
            val result = characterThinkingResult(http.client.chatOnce(model(), messages, extra()), true)
            assertEquals(if (text.startsWith("<")) "你好" else text, result.content)
            assertEquals(expected, result.thinkingContent)
            assertEquals(1, http.bodies.size); assertSystemOnly(http.bodies.single())
        }
    }

    @Test
    fun customReasoningParametersSurviveActualRequest() = runBlocking {
        for (custom in listOf(CustomReasoningParam("thinking.type", "enabled"), CustomReasoningParam("enable_thinking", "true"))) {
            val http = Http { json(answer()) }
            http.client.chatOnce(model(), messages, extra(ReasoningEffort.CUSTOM, custom))
            val body = http.bodies.single()
            assertFalse(body.has("reasoning_effort"))
            if (custom.path == "thinking.type") assertEquals("enabled", body["thinking"].asJsonObject["type"].asString)
            else assertTrue(body["enable_thinking"].asBoolean)
            assertSystemOnly(body)
        }
    }

    @Test
    fun providerReasoningMappingsStayEnabledRatherThanBeingForcedToNone() = runBlocking {
        for (target in listOf(model(provider = "deepseek"), model(protocol = AnthropicMessagesProtocol.name),
            model(protocol = OpenAIResponsesProtocol.name), model(modelName = "gpt-5"))) {
            val response = when (target.protocol) {
                AnthropicMessagesProtocol.name -> """{"content":[{"type":"thinking","thinking":"我想好了。","signature":"sig"},{"type":"text","text":"回答"}],"stop_reason":"end_turn"}"""
                OpenAIResponsesProtocol.name -> """{"status":"completed","output_text":"回答"}"""
                else -> answer("回答")
            }
            val http = Http { json(response) }
            val result = http.client.chatOnce(target, messages, extra())
            assertEquals("回答", result.content); assertEquals(1, http.bodies.size)
            val body = http.bodies.single()
            assertFalse(body.has(CHARACTER_THINKING_EXTRA))
            when {
                target.provider == "deepseek" -> assertEquals("enabled", body["thinking"].asJsonObject["type"].asString)
                target.protocol == AnthropicMessagesProtocol.name -> {
                    assertEquals("adaptive", body["thinking"].asJsonObject["type"].asString)
                    assertEquals("high", body["output_config"].asJsonObject["effort"].asString)
                    assertEquals("我想好了。", result.thinkingContent); assertEquals("sig", result.thinkingSignature)
                }
                target.protocol == OpenAIResponsesProtocol.name -> {
                    assertEquals("high", body["reasoning"].asJsonObject["effort"].asString)
                    assertEquals("auto", body["reasoning"].asJsonObject["summary"].asString)
                }
                else -> assertEquals("high", body["reasoning_effort"].asString)
            }
        }
    }

    @Test
    fun ordinary400CompatibilityRetryRetainsReasoningAndTools() = runBlocking {
        for (provider in listOf(null, "deepseek")) {
            val http = Http { if (it == 1) Triple(400, "unsupported temperature", "text/plain") else json(answer()) }
            val result = characterThinkingResult(http.client.chatOnce(model(provider = provider), messages, extra()), true)
            assertEquals("我听见小星笑了。", result.thinkingContent); assertEquals(2, http.bodies.size)
            assertTrue(http.bodies.first().has("temperature")); assertFalse(http.bodies.last().has("temperature"))
            http.bodies.forEach {
                assertTrue(it.has("tools")); assertSystemOnly(it)
                assertEquals("high", it["reasoning_effort"].asString)
                if (provider == "deepseek") assertEquals("enabled", it["thinking"].asJsonObject["type"].asString)
            }
        }
    }

    @Test
    fun non400FailureDoesNotTriggerACharacterThinkingRetry() = runBlocking {
        for (status in listOf(422, 500)) {
            val http = Http { Triple(status, "mock failure", "text/plain") }
            try { http.client.chatOnce(model(), messages, extra()); fail("应报告原请求错误") }
            catch (error: FailoverHttpException) { assertEquals(status, error.statusCode) }
            assertEquals(1, http.bodies.size)
        }
    }

    @Test
    fun failoverKeepsSystemAndReasoningWithoutExtraPrefillRetry() = runBlocking {
        val http = Http { if (it == 1) Triple(500, "mock failure", "text/plain") else json(answer()) }
        val result = characterThinkingResult(http.client.chatOnceWithFailover(
            listOf(model("primary"), model("backup", provider = "deepseek")), messages, extra()), true)
        assertEquals(2, http.bodies.size); assertEquals("backup", result.usedModelId)
        assertEquals("我听见小星笑了。", result.thinkingContent)
        http.bodies.forEach(::assertSystemOnly)
        assertEquals("enabled", http.bodies.last()["thinking"].asJsonObject["type"].asString)
    }

    @Test
    fun streamAndAgentCallbacksMatchFinalResponseForNativeAndTaggedJsonAndSse() = runBlocking {
        for (tagged in listOf(false, true)) for (buffered in listOf(false, true))
            for (mode in listOf("agent", "queue", "plain")) for (thinkingOnly in listOf(false, true)) {
            val expectedContent = if (thinkingOnly) "" else "怎么啦？"
            val body = (if (tagged) "<think>我听见小星笑了。</think>" else "") + expectedContent
            val http = Http { if (buffered) json(answer(body)) else sse(
                listOf(delta("reasoning_content", "我听见小星笑了。")) + body.map { delta("content", it.toString()) }) }
            val content = StringBuilder(); val thinking = StringBuilder()
            val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
                onStart = { content.setLength(0); thinking.setLength(0) },
                onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
            var final: LocalAiResult? = null
            if (mode == "agent") final = characterThinkingResult(http.client.chatOnce(model(), messages, extra(), streamCallbacks = relay.callbacks), true)
            else (if (mode == "queue") http.client.chatStreamWithFailover(listOf(model()), messages, extra())
                else http.client.chatStream(model(), messages, extra())).collect { event ->
                when (event) {
                    is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                    is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                    is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                    is RealtimeEvent.Error -> fail(event.message)
                    else -> Unit
                }
                assertFalse(content.toString().contains('<')); assertFalse(content.toString().contains("我听见"))
            }
            relay.finish()
            assertEquals(expectedContent, content.toString()); assertEquals("我听见小星笑了。", thinking.toString())
            final?.let { assertEquals(content.toString(), it.content); assertEquals(thinking.toString(), it.thinkingContent) }
            assertEquals(1, http.bodies.size); assertSystemOnly(http.bodies.single())
        }
    }

    @Test
    fun emptyReasoningAliasDoesNotMaskThinkingContentInJsonOrSse() = runBlocking {
        for (empty in listOf("null", "\"\"", "\" \"")) for (stream in listOf(false, true)) {
            val fields = "\"reasoning_content\":$empty,\"thinking_content\":\"我想起小星了。\",\"content\":\"回答\""
            val http = Http { if (stream) sse(listOf("""{"choices":[{"delta":{$fields}}]}"""))
                else json("""{"choices":[{"message":{$fields},"finish_reason":"stop"}]}""") }
            val result = characterThinkingResult(http.client.chatOnce(model(), messages, extra(),
                streamCallbacks = if (stream) LocalAiStreamCallbacks() else null), true)
            assertEquals("我想起小星了。", result.thinkingContent); assertEquals("回答", result.content)
        }
    }

    @Test
    fun nativeThinkingWhitespaceChunksSurviveAgentAndDirectAndFailoverStreams() = runBlocking {
        val chunks = listOf("我想起", " ", "小星", "\n", "今天的回答。", "\n\n", "我还想再确认一下。")
        val expected = chunks.joinToString("")
        for (mode in listOf("agent", "plain", "queue")) {
            val http = Http { sse(chunks.map { delta("reasoning_content", it) } + delta("content", "回答")) }
            val content = StringBuilder(); val thinking = StringBuilder()
            val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
                onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
            if (mode == "agent") {
                val final = characterThinkingResult(http.client.chatOnce(model(), messages, extra(),
                    streamCallbacks = relay.callbacks), true)
                assertEquals(expected, final.thinkingContent)
                assertEquals("回答", final.content)
            } else (if (mode == "plain") http.client.chatStream(model(), messages, extra())
                else http.client.chatStreamWithFailover(listOf(model()), messages, extra())).collect { event ->
                when (event) {
                    is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                    is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                    is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                    is RealtimeEvent.Error -> fail(event.message)
                    else -> Unit
                }
            }
            relay.finish()
            assertEquals(expected, thinking.toString())
            assertEquals("回答", content.toString())
            assertEquals(1, http.bodies.size)
            assertSystemOnly(http.bodies.single())
        }
    }

    @Test
    fun toolLoopReturnsOnlyNativeThinkingWithItsSignatureAndNeverSignsTaggedDisplay() = runBlocking {
        for (native in listOf("", "我先核对记录。")) {
            var count = 0
            val result = runToolCallLoop(messages, modelCall = { current, _ ->
                count++
                if (count == 1) mapOf("content" to "", "thinking_content" to "我有点担心小星。",
                    NATIVE_CHARACTER_THINKING_CONTENT to native, NATIVE_CHARACTER_THINKING_SIGNATURE to "native-sig",
                    "tool_calls" to listOf(mapOf("id" to "call-1", "name" to "lookup", "arguments" to emptyMap<String, Any>())))
                else {
                    val sent = characterThinkingMessages(current, true)
                    val assistant = sent.last { it["role"] == "assistant" }
                    assertFalse(assistant.values.contains("我有点担心小星。"))
                    if (native.isBlank()) assertFalse(assistant.containsKey("reasoning_content")) else {
                        assertEquals(native, assistant["reasoning_content"])
                        assertEquals("native-sig", assistant["reasoning_signature"])
                        val raw = Gson().toJson(AnthropicMessagesProtocol.buildPayload("claude", sent, false, extra()))
                        assertTrue(raw.contains("native-sig")); assertFalse(raw.contains("我有点担心小星。"))
                    }
                    mapOf("content" to "回答", "finish_reason" to "stop")
                }
            }, toolExecutor = { _, _, _, _ -> mapOf("content" to "书店") }, maxIterations = 2)
            assertEquals("回答", result.finalContent); assertEquals(2, count)
        }
    }

    @Test
    fun actualJsonAndSseToolOnlyResponsesKeepNativeReasoningForNextRequest() = runBlocking {
        for (bodyOnly in listOf(false, true)) for (stream in listOf(false, true)) for (native in listOf("", "Let me check the record.", "「按thinking_style放在思考和回复里。」")) {
            val expectedThinking = if (bodyOnly) "我有点担心小星。"
                else native.takeIf { it.isNotBlank() && !it.contains("thinking_style") } ?: "我有点担心小星。"
            val toolCall = mapOf("id" to "call-1", "type" to "function", "function" to
                mapOf("name" to "lookup", "arguments" to "{\"query\":\"书店\"}"))
            val toolMessage = mapOf("content" to "<think>我有点担心小星。</think>",
                "reasoning_content" to native, "tool_calls" to listOf(toolCall))
            val http = Http { attempt ->
                if (attempt != 1) json(answer("回答", "我找到了。"))
                else if (stream) sse(listOf(Gson().toJson(mapOf("choices" to listOf(mapOf(
                    "delta" to toolMessage + ("tool_calls" to listOf(toolCall + ("index" to 0))),
                    "finish_reason" to "tool_calls"))))))
                else json(Gson().toJson(mapOf("choices" to listOf(mapOf(
                    "message" to toolMessage, "finish_reason" to "tool_calls")))))
            }
            val content = StringBuilder(); val thinking = StringBuilder()
            var executions = 0
            val result = runToolCallLoop(messages, modelCall = { current, _ -> runBlocking {
                val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
                    onStart = { content.setLength(0); thinking.setLength(0) },
                    onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }), bodyOnly = bodyOnly)
                val raw = http.client.chatOnce(model(provider = "deepseek"),
                    characterThinkingMessages(withCharacterThinkingRequest(current, prompt), true),
                    extra(), streamCallbacks = if (stream) relay.callbacks else null)
                relay.finish()
                val parsed = if (stream) relay.resolve(raw) else characterThinkingResult(raw, true, bodyOnly = bodyOnly)
                if (http.bodies.size == 1) {
                    assertEquals("", parsed.content)
                    assertEquals(expectedThinking, parsed.thinkingContent)
                    assertEquals("tool_calls", parsed.finishReason)
                    if (stream) {
                        assertEquals(parsed.content, content.toString())
                        assertEquals(parsed.thinkingContent, thinking.toString())
                    }
                }
                mapOf("content" to parsed.content, "thinking_content" to parsed.thinkingContent,
                    NATIVE_CHARACTER_THINKING_CONTENT to raw.thinkingContent,
                    NATIVE_CHARACTER_THINKING_SIGNATURE to raw.thinkingSignature,
                    "tool_calls" to parsed.toolCalls, "finish_reason" to parsed.finishReason)
            } }, toolExecutor = { call, displayThinking, _, _ ->
                executions++
                assertEquals("lookup", call["name"])
                assertEquals(expectedThinking, displayThinking)
                assertEquals("书店", (call["arguments"] as Map<*, *>)["query"])
                mapOf("content" to "书店记录")
            }, maxIterations = 2)
            assertEquals("回答", result.finalContent)
            assertEquals(1, executions); assertEquals(2, http.bodies.size)
            val sent = http.bodies.last()["messages"].asJsonArray
            assertEquals(listOf("system", "user", "assistant", "tool"),
                sent.map { it.asJsonObject["role"].asString })
            assertEquals(http.bodies.first()["messages"].asJsonArray[0], sent[0])
            assertEquals("哈哈", sent[1].asJsonObject["content"].asString)
            val assistant = sent[2].asJsonObject
            if (native.isBlank()) assertFalse(assistant.has("reasoning_content"))
            else assertEquals(native, assistant["reasoning_content"].asString)
            assertFalse(sent.toString().contains("我有点担心小星。"))
            assertFalse(sent.toString().contains(NATIVE_CHARACTER_THINKING_CONTENT))
            assertEquals("call-1", sent[3].asJsonObject["tool_call_id"].asString)
            assertEquals("书店记录", JsonParser.parseString(sent[3].asJsonObject["content"].asString)
                .asJsonObject["content"].asString)
        }
    }

    @Test
    fun failedSseAttemptCannotLeaveNativeOrTaggedThinkingInBackup() = runBlocking {
        val http = Http { if (it == 1) sse(listOf(
            """{"type":"response.reasoning_summary_text.delta","delta":"失败原生思考"}""",
            Gson().toJson(mapOf("type" to "response.output_text.delta", "delta" to "<think>" + "失败念头".repeat(100))),
            """{"type":"error","message":"mock stream failure"}""")) else json(answer("备用正文", "我重新想好了。")) }
        val content = StringBuilder(); val thinking = StringBuilder(); var starts = 0
        val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onStart = { starts++; content.setLength(0); thinking.setLength(0) },
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
        http.client.chatStreamWithFailover(listOf(model("primary", OpenAIResponsesProtocol.name), model("backup")), messages, extra()).collect { event ->
            when (event) {
                is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                is RealtimeEvent.Error -> fail(event.message)
                else -> Unit
            }
        }
        relay.finish()
        assertEquals(2, starts); assertEquals("备用正文", content.toString())
        assertEquals("我重新想好了。", thinking.toString()); assertEquals(2, http.bodies.size)
    }
}
