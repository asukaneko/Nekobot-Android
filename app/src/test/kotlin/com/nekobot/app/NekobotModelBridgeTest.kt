package com.nekobot.app

import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NekobotModelBridgeTest {
    private val row = LocalAiModelEntity(id="one", name="", protocol="openai_chat", apiKey="synthetic",
        baseUrl="https://example.test/v1///", model="sample", createdAt="")

    @Test fun filtersWithoutReadingModelsOutsideLocalMode() = runBlocking {
        assertTrue(localModelSnapshots(false) { error("unexpected DAO read") }.isEmpty())
        val excluded = listOf(row.copy(enabled=false), row.copy(oauthAccountId="synthetic"),
            row.copy(apiKey=""), row.copy(apiKey="a b"), row.copy(apiKey="a\u007fb"),
            row.copy(baseUrl="http://example.test"), row.copy(protocol="openai_responses"),
            row.copy(purpose="tts"), row.copy(appendBaseUrlPath=false),
            row.copy(baseUrl="https://example.test/chat/completions"),
            row.copy(purpose="embedding",baseUrl="https://example.test"))
        assertTrue(localModelSnapshots(true) { excluded }.isEmpty())
        val models = localModelSnapshots(true) { listOf(row, row.copy(id="two",purpose="embedding")) }
        assertEquals(listOf("chat","embedding"), models.map { it.purpose })
        assertEquals("https://example.test/v1",models.first().baseUrl)
        assertEquals("sample", models.first().name)
    }
}
