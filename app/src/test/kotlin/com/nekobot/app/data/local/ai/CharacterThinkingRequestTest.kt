package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingRequestTest {
    private val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "林小满", "小星")
    private val section get() = "## $CHARACTER_THINKING_STYLE_KEY\n$prompt"

    @Test
    fun finalizerCannotDropOrBuryEnabledStyleAndUserHahaIsUnchanged() = runBlocking {
        val ctx = PipelineContext(ChatRequest.forLocal("thinking", "哈哈")).apply {
            metadata["session_mode"] = "character"
            metadata["reasoning_effort"] = "high"
            metadata["user_name"] = "小星"
            characterTurn = CharacterTurnContext(profile = CharacterProfile(id = "role", name = "林小满"), promptText = "角色卡")
        }
        val callbacks = object : PipelineCallbacks() {
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>,
                tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> =
                messages.map { if (it["role"] == "system") it + ("content" to "重新构建的角色设定") else it }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        // 思考提示词不再放顶部 system：重建后的系统提示词保持原样。
        assertEquals("重新构建的角色设定", ctx.messages.first()["content"])
        assertEquals(listOf(mapOf("role" to "user", "content" to "哈哈")), ctx.messages.filter { it["role"] == "user" })
        assertEquals(ctx.messages.first()["content"], ctx.metadata["composed_system_prompt"])
    }

    @Test
    fun repeatedToolRequestsHaveOneFullMultilinePromptAndRetainToolAndSignatureObjects() {
        val custom = "我是林小满。\n\n我想起小星。\n参数 {\"query\":\"原样\"}。"
        val user = mapOf<String, Any>("role" to "user", "content" to "哈哈")
        val assistant = mapOf<String, Any>("role" to "assistant", "content" to "", "thinking_signature" to "mock-signature",
            "reasoning_content" to "我想想。", "tool_calls" to listOf(mapOf("id" to "call-1", "name" to "agent_history_search")))
        val tool = mapOf<String, Any>("role" to "tool", "content" to "书店", "tool_call_id" to "call-1")
        val original = listOf(mapOf<String, Any>("role" to "system", "content" to "角色卡"), user, assistant, tool)
        var request = original
        repeat(3) { request = withCharacterThinkingRequest(request, custom) }
        assertEquals("## $CHARACTER_THINKING_STYLE_KEY\n$custom\n\n角色卡", request.first()["content"])
        assertSame(user, request[1]); assertSame(assistant, request[2]); assertSame(tool, request[3])
        assertEquals("角色卡", original.first()["content"])
    }

    @Test
    fun staleSystemCopiesAreRemovedAndMissingSystemIsCreatedOnlyWhenEnabled() {
        val user = mapOf<String, Any>("role" to "user", "content" to "哈哈")
        val input = listOf(mapOf<String, Any>("role" to "system", "content" to "角色卡\n\n[$CHARACTER_THINKING_STYLE_KEY]\n旧规则"),
            mapOf<String, Any>("role" to "system", "content" to section), user)
        val output = withCharacterThinkingRequest(input, prompt)
        assertEquals("$section\n\n角色卡", output.first()["content"])
        assertFalse(output.any { it["content"].toString().contains("旧规则") })
        for (disabled in listOf(null, " ")) {
            val cleared = withCharacterThinkingRequest(input, disabled)
            assertFalse(cleared.any { it["role"] == "system" && it["content"].toString().contains(CHARACTER_THINKING_STYLE_KEY) })
            assertSame(user, cleared.last())
        }
        val noSystem = withCharacterThinkingRequest(listOf(user), prompt)
        assertEquals(section, noSystem.first()["content"])
        assertSame(user, noSystem.last())
        val plain = listOf(mapOf<String, Any>("role" to "system", "content" to "  保留原文\n\n  "), user)
        assertSame(plain, withCharacterThinkingRequest(plain, null))
    }

    @Test
    fun actualChatPayloadKeepsDefaultInSystemAndDoesNotAddAModelCall() = runBlocking {
        val bodies = mutableListOf<com.google.gson.JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"怎么啦？","reasoning_content":"我听见他笑了。"},"finish_reason":"stop"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }).build())
        val model = LocalAiModelEntity(id = "mock", name = "Mock", protocol = OpenAIChatProtocol.name,
            apiKey = "test-key", baseUrl = "https://api.example.com/v1", model = "custom-chat", createdAt = "2026-10-14")
        val result = client.chatOnce(model, withCharacterThinkingRequest(listOf(mapOf("role" to "user", "content" to "哈哈")), prompt),
            extra = mapOf("reasoning_effort" to "high"))
        assertEquals("怎么啦？", result.content)
        assertEquals(1, bodies.size)
        val messages = bodies.single()["messages"].asJsonArray
        assertEquals("system", messages[0].asJsonObject["role"].asString)
        assertEquals(section, messages[0].asJsonObject["content"].asString)
        assertEquals("user", messages[1].asJsonObject["role"].asString)
        assertEquals("哈哈", messages[1].asJsonObject["content"].asString)
    }
}
