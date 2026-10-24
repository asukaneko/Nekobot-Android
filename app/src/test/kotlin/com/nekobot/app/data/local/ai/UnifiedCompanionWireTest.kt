package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UnifiedCompanionWireTest {
    @Test fun actualOpenAiRequestKeepsFiveBlocksAndHasNoPrivateTagsOrUserSuffix() = runBlocking {
        var calls = 0
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val json = JsonParser.parseString(body).asJsonObject
            val sent = json["messages"].asJsonArray.map { it.asJsonObject }
            assertEquals(listOf("system", "user", "assistant", "system", "user", "system"), sent.map { it["role"].asString })
            assertEquals("刚才那句", sent[1]["content"].asString)
            assertEquals("刚才的回答", sent[2]["content"].asString)
            assertEquals("统一参考", sent[3]["content"].asString)
            assertEquals("新问题", sent[4]["content"].asString)
            assertEquals("现在是上午十点\n\n唯一思考规则", sent.last()["content"].asString)
            assertEquals(1, body.split("唯一思考规则").size - 1)
            assertFalse(body.contains("_companion_"))
            assertFalse(body.contains("_character_"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"合成回答"},"finish_reason":"stop"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        fun row(id: String, role: String, content: String) = LocalMessageEntity(id, "s", role, content, createdAt = "now", timestamp = "now")
        val history = listOf(row("u1", "user", "刚才那句"), row("a1", "assistant", "刚才的回答"))
        val current = row("u2", "user", "新问题")
        val messages = FusedCompanionContext.assembleMessages("固定规则", history, emptyList(), "统一参考", "现在是上午十点",
            mapOf("role" to "user", "content" to current.content), current, listOf("15"))
        val request = withCharacterThinkingRule(messages, "唯一思考规则", true).map(CompanionPromptLanguage::message)
        val model = LocalAiModelEntity(id = "mock", name = "Mock", protocol = OpenAIChatProtocol.name,
            apiKey = "test-key", baseUrl = "https://api.example.com/v1", model = "test-chat", createdAt = "now")
        assertEquals("合成回答", client.chatOnce(model, request).content)
        assertEquals(1, calls)
    }
}
