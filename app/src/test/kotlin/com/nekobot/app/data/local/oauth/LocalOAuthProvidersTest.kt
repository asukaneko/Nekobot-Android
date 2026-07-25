package com.nekobot.app.data.local.oauth

import com.nekobot.app.data.local.db.LocalOAuthAccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOAuthProvidersTest {
    @Test
    fun `provider catalog contains all requested providers`() {
        assertEquals(
            setOf(
                LocalOAuthProviders.CODEX,
                LocalOAuthProviders.QWEN,
                LocalOAuthProviders.MINIMAX,
                LocalOAuthProviders.XAI,
                LocalOAuthProviders.OPENCODE_ZEN,
                LocalOAuthProviders.OPENCODE_GO,
                LocalOAuthProviders.ANTHROPIC
            ),
            LocalOAuthProviders.all.map { it.id }.toSet()
        )
        assertEquals(
            OAuthLoginMode.API_KEY,
            requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.OPENCODE_ZEN)).loginMode
        )
        assertEquals(
            OAuthLoginMode.API_KEY,
            requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.OPENCODE_GO)).loginMode
        )
    }

    @Test
    fun `generated model keeps a stable encrypted-account reference`() {
        val account = LocalOAuthAccountEntity(
            id = "account-1",
            provider = LocalOAuthProviders.CODEX,
            label = "Codex",
            encryptedCredentials = "encrypted",
            createdAt = "2026-07-25T00:00:00Z",
            updatedAt = "2026-07-25T00:00:00Z"
        )

        val first = LocalOAuthProviders.createLocalModel(account, "gpt-test", priority = 0)
        val second = LocalOAuthProviders.createLocalModel(account, "gpt-test", priority = 3)

        assertEquals(first.id, second.id)
        assertEquals(account.id, first.oauthAccountId)
        assertEquals("oauth:${account.id}", first.apiKey)
        assertEquals("openai_responses", first.protocol)
        assertTrue(first.supportsStream)
    }

    @Test
    fun `opencode models use the matching compatible endpoint`() {
        val gpt = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_ZEN,
            "gpt-5.4"
        )
        val claude = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_ZEN,
            "claude-sonnet-4-6"
        )
        val qwen = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_ZEN,
            "qwen3.6-plus"
        )
        val deepSeek = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_ZEN,
            "deepseek-v4-flash"
        )

        assertEquals("openai_responses", gpt.protocol)
        assertEquals("https://opencode.ai/zen/v1", gpt.baseUrl)
        assertEquals("anthropic_messages", claude.protocol)
        assertEquals("https://opencode.ai/zen", claude.baseUrl)
        assertEquals("anthropic_messages", qwen.protocol)
        assertEquals("openai_chat", deepSeek.protocol)
        assertEquals("https://opencode.ai/zen/v1", deepSeek.baseUrl)
    }

    @Test
    fun `opencode go models use go endpoints`() {
        val qwen = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_GO,
            "qwen3.7-plus"
        )
        val miniMax = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_GO,
            "minimax-m3"
        )
        val kimi = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_GO,
            "kimi-k3"
        )

        assertEquals("anthropic_messages", qwen.protocol)
        assertEquals("https://opencode.ai/zen/go", qwen.baseUrl)
        assertEquals("anthropic_messages", miniMax.protocol)
        assertEquals("openai_chat", kimi.protocol)
        assertEquals("https://opencode.ai/zen/go/v1", kimi.baseUrl)
    }

    @Test
    fun `opencode hides models that require an unsupported native protocol`() {
        assertTrue(LocalOAuthProviders.supportsOpenCodeModel("gpt-5.4"))
        assertTrue(!LocalOAuthProviders.supportsOpenCodeModel("gemini-3-pro"))
    }
}
