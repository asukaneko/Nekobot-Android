package com.nekobot.app.data.local.oauth

import com.nekobot.app.data.local.ai.OpenAIChatProtocol
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
                LocalOAuthProviders.ANTHROPIC,
                LocalOAuthProviders.KIMI,
                LocalOAuthProviders.WORKBUDDY_CN,
                LocalOAuthProviders.WORKBUDDY_INTL
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
        assertEquals(
            OAuthLoginMode.DEVICE_CODE,
            requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.KIMI)).loginMode
        )
    }

    @Test
    fun `workbuddy providers use the codebuddy plugin gateway`() {
        val cn = requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.WORKBUDDY_CN))
        assertEquals(OAuthLoginMode.BROWSER_POLL, cn.loginMode)
        assertEquals("openai_chat", cn.protocol)
        assertEquals("https://copilot.tencent.com/v2", cn.baseUrl)
        assertEquals("https://copilot.tencent.com", cn.authBase)

        val intl = requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.WORKBUDDY_INTL))
        assertEquals(OAuthLoginMode.BROWSER_POLL, intl.loginMode)
        assertEquals("https://www.workbuddy.ai", intl.authBase)

        val target = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.WORKBUDDY_CN,
            "glm-5.2"
        )
        assertEquals("openai_chat", target.protocol)
        assertEquals("https://copilot.tencent.com/v2", target.baseUrl)
        assertTrue(target.maxContextLength > 0)
        assertTrue(target.maxTokens > 0)
        assertEquals(
            "https://copilot.tencent.com/v2/chat/completions",
            OpenAIChatProtocol.resolveUrl(
                target.baseUrl,
                "glm-5.2",
                appendBaseUrlPath = true,
                stream = true,
                apiKey = "oauth:account-1"
            )
        )
    }

    @Test
    fun `workbuddy models keep per-model capacity limits`() {
        val cn = requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.WORKBUDDY_CN))
        val intl = requireNotNull(LocalOAuthProviders.get(LocalOAuthProviders.WORKBUDDY_INTL))

        // 两个站点是两套账号，模型 id 只有部分重合，不能共用同一份列表。
        assertTrue(cn.models.isNotEmpty())
        assertTrue(intl.models.isNotEmpty())
        assertTrue(intl.models.contains("default-model"))
        assertTrue(cn.models.contains("hy4-preview"))
        assertTrue(!cn.models.contains("default-model"))

        // 兜底列表里不能出现上游根本不存在的模型 id。
        assertTrue(cn.models.none { it == "hy3-preview" || it == "hy3-preview-agent" })
        assertTrue(cn.models.contains("hy4-preview"))
        assertTrue(cn.models.contains("deepseek-v4.1-flash"))

        // 每个模型都要有自己的容量，不能统一套 300k / 32k：
        // kimi-k2.7 的输出上限是 32000，发 32768 就会被上游拒绝。
        cn.models.forEach { model ->
            val target = LocalOAuthProviders.resolveModelTarget(LocalOAuthProviders.WORKBUDDY_CN, model)
            assertEquals("openai_chat", target.protocol)
            assertEquals("https://copilot.tencent.com/v2", target.baseUrl)
            assertTrue("$model 缺少容量上限", target.maxContextLength in 1..2_000_000)
            assertTrue("$model 缺少输出上限", target.maxTokens in 1..200_000)
        }
        assertEquals(256_000, LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.WORKBUDDY_CN, "kimi-k2.7"
        ).maxContextLength)
        assertEquals(32_000, LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.WORKBUDDY_CN, "kimi-k2.7"
        ).maxTokens)
        assertEquals(200_000, LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.WORKBUDDY_CN, "glm-5.1"
        ).maxContextLength)
        assertEquals(64_000, LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.WORKBUDDY_CN, "glm-5.2"
        ).maxTokens)

        intl.models.forEach { model ->
            val target = LocalOAuthProviders.resolveModelTarget(LocalOAuthProviders.WORKBUDDY_INTL, model)
            assertTrue(target.maxContextLength > 0)
            assertTrue(target.maxTokens > 0)
        }
    }

    @Test
    fun `workbuddy resync refreshes the stored model capacity`() {
        val account = LocalOAuthAccountEntity(
            id = "account-2",
            provider = LocalOAuthProviders.WORKBUDDY_CN,
            label = "WorkBuddy",
            encryptedCredentials = "encrypted",
            createdAt = "2026-11-09T00:00:00Z",
            updatedAt = "2026-11-09T00:00:00Z"
        )
        val stale = LocalOAuthProviders.createLocalModel(account, "kimi-k2.7", priority = 0)
            .copy(maxContextLength = 300_000, maxTokens = 32_768)

        val refreshed = LocalOAuthProviders.createLocalModel(
            account, "kimi-k2.7", priority = 0, existing = stale
        )

        assertEquals(256_000, refreshed.maxContextLength)
        assertEquals(32_000, refreshed.maxTokens)
        assertEquals(stale.id, refreshed.id)
    }

    @Test
    fun `kimi models target the coding plan chat completion endpoint`() {
        val target = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.KIMI,
            "k3.1-pro"
        )

        assertEquals("openai_chat", target.protocol)
        assertEquals("https://api.kimi.com/coding/v1", target.baseUrl)
        assertEquals(262_144, target.maxContextLength)
        assertTrue(target.maxTokens > 0)
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
        val grok = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_GO,
            "grok-4.7"
        )
        val luna = LocalOAuthProviders.resolveModelTarget(
            LocalOAuthProviders.OPENCODE_GO,
            "gpt-6-luna"
        )

        assertEquals("anthropic_messages", qwen.protocol)
        assertEquals("https://opencode.ai/zen/go", qwen.baseUrl)
        assertEquals("anthropic_messages", miniMax.protocol)
        assertEquals("openai_chat", kimi.protocol)
        assertEquals("https://opencode.ai/zen/go/v1", kimi.baseUrl)
        // Grok / GPT Luna 在 Go 网关只提供 Responses API，chat/completions 会被 400 拒绝
        assertEquals("openai_responses", grok.protocol)
        assertEquals("https://opencode.ai/zen/go/v1", grok.baseUrl)
        assertEquals("openai_responses", luna.protocol)
    }

    @Test
    fun `opencode hides models that require an unsupported native protocol`() {
        assertTrue(LocalOAuthProviders.supportsOpenCodeModel("gpt-5.4"))
        assertTrue(!LocalOAuthProviders.supportsOpenCodeModel("gemini-3-pro"))
    }
}
