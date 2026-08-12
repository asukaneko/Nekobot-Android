package com.nekobot.app.data.local.security

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalApiKeyEntity
import com.nekobot.app.data.local.db.LocalMcpServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDatabaseSecretProtectorTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plainText: String): String = "ks1.${plainText.reversed()}"
        override fun decrypt(value: String): String {
            if (value == "ks1.corrupt") error("损坏")
            return value.removePrefix("ks1.").reversed()
        }
        override fun isEncrypted(value: String): Boolean = value.startsWith("ks1.")
    }
    private val protector = LocalDatabaseSecretProtector(cipher)

    @Test
    fun modelCredentialsAreEncryptedAndRestored() {
        val model = LocalAiModelEntity(
            id = "model-1",
            name = "测试模型",
            protocol = "openai_chat",
            apiKey = "sk-secret",
            proxyUrl = "http://user:pass@127.0.0.1:8080",
            baseUrl = "https://example.com/v1",
            model = "test",
            ttsHeaders = "{\"Authorization\":\"Bearer tts\"}",
            ttsBodyTemplate = "{\"secret\":\"value\"}",
            sttHeaders = "{\"X-Key\":\"stt\"}",
            createdAt = "2026-08-03T00:00:00Z"
        )

        val stored = protector.protect(model)
        assertNotEquals(model.apiKey, stored.apiKey)
        assertNotEquals(model.proxyUrl, stored.proxyUrl)
        assertTrue(stored.ttsHeaders.startsWith("ks1."))
        assertEquals(model, protector.reveal(stored))
        assertEquals(stored, protector.protect(stored))
    }

    @Test
    fun oauthReferenceIsNotEncrypted() {
        val model = LocalAiModelEntity(
            id = "oauth-model",
            name = "OAuth",
            protocol = "openai_responses",
            apiKey = "oauth:account-1",
            baseUrl = "https://example.com",
            model = "codex",
            createdAt = "2026-08-03T00:00:00Z"
        )

        assertEquals("oauth:account-1", protector.protect(model).apiKey)
    }

    @Test
    fun apiKeyAndMcpSecretsRoundTrip() {
        val key = LocalApiKeyEntity(
            id = "key-1",
            name = "服务密钥",
            key = "plain-key",
            createdAt = "now",
            updatedAt = "now"
        )
        val server = LocalMcpServerEntity(
            id = "mcp-1",
            name = "MCP",
            url = "https://example.com/mcp?token=abc",
            headersJson = "{\"Authorization\":\"Bearer abc\"}",
            argsJson = "[\"--token\",\"abc\"]",
            envJson = "{\"TOKEN\":\"abc\"}",
            createdAt = "now"
        )

        assertEquals(key, protector.reveal(protector.protect(key)))
        assertEquals(server, protector.reveal(protector.protect(server)))
    }

    @Test
    fun unreadableCiphertextDoesNotLeakIntoNetworkConfiguration() {
        val key = LocalApiKeyEntity(
            id = "key-1",
            name = "服务密钥",
            key = "ks1.corrupt",
            createdAt = "now",
            updatedAt = "now"
        )

        assertEquals("", protector.reveal(key).key)
        assertThrows(IllegalStateException::class.java) {
            protector.revealStrict(key)
        }
    }
}
