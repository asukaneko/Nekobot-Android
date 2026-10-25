package com.nekobot.app.ui.screens.aiconfig

import org.junit.Assert.*
import org.junit.Test

class AiModelEditorValidationTest {
    private val valid = AiModelEditorState(name = "Test", baseUrl = "https://example.com/v1", apiKey = "test-key", model = "test-model")

    @Test fun missingFieldsLeadToTheirOwnStep() {
        val issues = valid.copy(name = " ", baseUrl = "", apiKey = "", model = "").validationIssues(true, false)
        assertEquals(listOf("name", "baseUrl", "apiKey", "model"), issues.map { it.field })
        assertEquals(listOf(0, 1, 1, 1), issues.map { it.step })
    }

    @Test fun invalidEndpointAndProxyCannotBeSaved() {
        val issues = valid.copy(baseUrl = "example.com", proxyUrl = "ftp://proxy.invalid:8080").validationIssues(true, false)
        assertEquals(setOf("baseUrl", "proxyUrl"), issues.map { it.field }.toSet())
        assertTrue(valid.copy(baseUrl = "wss://example.com/realtime").validationIssues(true, false).isEmpty())
        assertTrue(valid.copy(proxyUrl = "proxy.invalid:8080").validationIssues(true, false).isEmpty())
    }

    @Test fun optionalNumbersCanBeBlankButNotMalformed() {
        assertTrue(valid.copy(temperature = "", topP = "", maxTokens = "", maxContextLength = "", priority = "").validationIssues(true, false).isEmpty())
        val issues = valid.copy(temperature = "NaN", topP = "1.1", maxTokens = "0", maxContextLength = "3.5", inputPrice = "-1", priority = "first").validationIssues(true, false)
        assertEquals(setOf("temperature", "topP", "maxTokens", "maxContextLength", "inputPrice", "priority"), issues.map { it.field }.toSet())
        assertTrue(issues.all { it.step == 2 })
    }

    @Test fun embeddingDimensionsAreValidatedWithoutChatParameters() {
        val embedding = valid.copy(purpose = "embedding", model = "text-embedding-3-small", temperature = "unused", maxTokens = "unused")
        assertTrue(embedding.validationIssues(true, false).isEmpty())
        assertEquals(listOf("dimensions"), embedding.copy(dimensions = "0").validationIssues(true, true).map { it.field })
        assertTrue(embedding.copy(dimensions = "1536").validationIssues(true, true).isEmpty())
    }

    @Test fun deviceSpeechRecognitionDoesNotRequireCloudCredentials() {
        assertTrue(valid.copy(purpose = "stt", sttProvider = "local", baseUrl = "", apiKey = "", model = "").validationIssues(true, false).isEmpty())
    }

    @Test fun serverEditorIgnoresLocalProxy() {
        assertTrue(valid.copy(proxyUrl = "invalid").validationIssues(false, false).isEmpty())
    }
}
