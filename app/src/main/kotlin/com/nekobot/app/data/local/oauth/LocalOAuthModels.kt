package com.nekobot.app.data.local.oauth

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalOAuthAccountEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class OAuthLoginMode {
    DEVICE_CODE,
    PKCE_CODE,
    QWEN_CREDENTIAL_IMPORT,
    API_KEY
}

data class OAuthProviderSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val loginMode: OAuthLoginMode,
    val protocol: String,
    val baseUrl: String,
    val models: List<String>,
    val defaultContextLength: Int,
    val defaultMaxTokens: Int
)

data class LocalOAuthModelTarget(
    val protocol: String,
    val baseUrl: String,
    val maxContextLength: Int,
    val maxTokens: Int
)

data class OAuthLoginSession(
    val id: String = UUID.randomUUID().toString(),
    val provider: String,
    val verificationUrl: String,
    val userCode: String = "",
    val expiresAt: Long,
    val pollIntervalSeconds: Int = 3,
    internal val secret: Map<String, String> = emptyMap()
)

sealed interface OAuthPollResult {
    data object Pending : OAuthPollResult
    data class Connected(
        val account: LocalOAuthAccountEntity,
        val models: List<String>
    ) : OAuthPollResult

    data class Failed(val message: String) : OAuthPollResult
}

data class OAuthRuntimeCredential(
    val accessToken: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val removeHeaders: Set<String> = emptySet()
)

data class OAuthTokenState(
    val accessToken: String,
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAt: Long? = null,
    val tokenEndpoint: String = "",
    val clientId: String = "",
    val portalBaseUrl: String = "",
    val resourceUrl: String = ""
)

object LocalOAuthProviders {
    const val CODEX = "openai-codex"
    const val QWEN = "qwen-oauth"
    const val MINIMAX = "minimax-oauth"
    const val XAI = "xai-oauth"
    const val OPENCODE_ZEN = "opencode-zen"
    const val OPENCODE_GO = "opencode-go"
    const val ANTHROPIC = "anthropic-oauth"

    val all: List<OAuthProviderSpec> = listOf(
        OAuthProviderSpec(
            id = CODEX,
            title = "Codex",
            subtitle = "使用 ChatGPT 订阅登录 OpenAI Codex",
            loginMode = OAuthLoginMode.DEVICE_CODE,
            protocol = "openai_responses",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            models = listOf(
                "gpt-5.6-sol",
                "gpt-5.6-sol-pro",
                "gpt-5.6-terra",
                "gpt-5.6-terra-pro",
                "gpt-5.6-luna",
                "gpt-5.6-luna-pro",
                "gpt-5.5",
                "gpt-5.4-mini",
                "gpt-5.4",
                "gpt-5.3-codex",
                "gpt-5.3-codex-spark"
            ),
            defaultContextLength = 272_000,
            defaultMaxTokens = 128_000
        ),
        OAuthProviderSpec(
            id = QWEN,
            title = "Qwen (via Qwen CLI)",
            subtitle = "导入 Qwen CLI 的 oauth_creds.json",
            loginMode = OAuthLoginMode.QWEN_CREDENTIAL_IMPORT,
            protocol = "openai_chat",
            baseUrl = "https://portal.qwen.ai/v1",
            models = listOf("qwen3-coder-plus", "qwen3-coder"),
            defaultContextLength = 1_000_000,
            defaultMaxTokens = 65_536
        ),
        OAuthProviderSpec(
            id = MINIMAX,
            title = "MiniMax (OAuth)",
            subtitle = "浏览器授权，无需 API Key",
            loginMode = OAuthLoginMode.DEVICE_CODE,
            protocol = "anthropic_messages",
            baseUrl = "https://api.minimax.io/anthropic",
            models = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"),
            defaultContextLength = 204_800,
            defaultMaxTokens = 131_072
        ),
        OAuthProviderSpec(
            id = XAI,
            title = "xAI Grok OAuth (SuperGrok / Premium+)",
            subtitle = "使用 SuperGrok 或 X Premium+ 订阅",
            loginMode = OAuthLoginMode.DEVICE_CODE,
            protocol = "openai_responses",
            baseUrl = "https://api.x.ai/v1",
            models = listOf(
                "grok-build-0.1",
                "grok-4.5",
                "grok-4.3",
                "grok-composer-2.5-fast",
                "grok-4.20-0309-reasoning",
                "grok-4.20-0309-non-reasoning",
                "grok-4.20-multi-agent-0309"
            ),
            defaultContextLength = 1_000_000,
            defaultMaxTokens = 131_072
        ),
        OAuthProviderSpec(
            id = OPENCODE_ZEN,
            title = "OpenCode Zen",
            subtitle = "使用 OpenCode API Key 登录",
            loginMode = OAuthLoginMode.API_KEY,
            protocol = "openai_chat",
            baseUrl = "https://opencode.ai/zen/v1",
            models = listOf(
                "gpt-5.4",
                "gpt-5.3-codex",
                "claude-sonnet-4-6",
                "claude-haiku-4-5",
                "qwen3.6-plus",
                "grok-4.5",
                "deepseek-v4-flash",
                "minimax-m2.7",
                "glm-5",
                "kimi-k2.6",
                "big-pickle",
                "deepseek-v4-flash-free"
            ),
            defaultContextLength = 200_000,
            defaultMaxTokens = 65_536
        ),
        OAuthProviderSpec(
            id = OPENCODE_GO,
            title = "OpenCode Go",
            subtitle = "使用 OpenCode Go 订阅的 API Key 登录",
            loginMode = OAuthLoginMode.API_KEY,
            protocol = "openai_chat",
            baseUrl = "https://opencode.ai/zen/go/v1",
            models = listOf(
                "grok-4.5",
                "glm-5.2",
                "glm-5.1",
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.6",
                "deepseek-v4-pro",
                "deepseek-v4-flash",
                "mimo-v2.5-pro",
                "mimo-v2.5",
                "minimax-m3",
                "minimax-m2.7",
                "minimax-m2.5",
                "qwen3.7-max",
                "qwen3.7-plus",
                "qwen3.6-plus"
            ),
            defaultContextLength = 200_000,
            defaultMaxTokens = 65_536
        ),
        OAuthProviderSpec(
            id = ANTHROPIC,
            title = "Anthropic OAuth: Required Extra Usage Credits to Use Subscription",
            subtitle = "需要 Claude Max 与 Extra Usage Credits",
            loginMode = OAuthLoginMode.PKCE_CODE,
            protocol = "anthropic_messages",
            baseUrl = "https://api.anthropic.com",
            models = listOf(
                "claude-fable-5",
                "claude-sonnet-5",
                "claude-opus-4-8",
                "claude-opus-4-7",
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-opus-4-5-20251101",
                "claude-sonnet-4-5-20250929",
                "claude-haiku-4-5-20251001"
            ),
            defaultContextLength = 1_000_000,
            defaultMaxTokens = 128_000
        )
    )

    fun get(id: String): OAuthProviderSpec? = all.firstOrNull { it.id == id }

    fun resolveModelTarget(provider: String, modelId: String): LocalOAuthModelTarget {
        val spec = requireNotNull(get(provider)) { "未知账号提供商: $provider" }
        if (provider != OPENCODE_ZEN && provider != OPENCODE_GO) {
            return LocalOAuthModelTarget(
                protocol = spec.protocol,
                baseUrl = spec.baseUrl,
                maxContextLength = spec.defaultContextLength,
                maxTokens = spec.defaultMaxTokens
            )
        }

        val normalized = modelId.lowercase()
        if (provider == OPENCODE_GO) {
            return if (normalized.startsWith("minimax-") || normalized.startsWith("qwen")) {
                LocalOAuthModelTarget(
                    protocol = "anthropic_messages",
                    baseUrl = "https://opencode.ai/zen/go",
                    maxContextLength = 200_000,
                    maxTokens = 128_000
                )
            } else {
                LocalOAuthModelTarget(
                    protocol = "openai_chat",
                    baseUrl = "https://opencode.ai/zen/go/v1",
                    maxContextLength = 200_000,
                    maxTokens = 65_536
                )
            }
        }

        return when {
            normalized.startsWith("gpt-") -> LocalOAuthModelTarget(
                protocol = "openai_responses",
                baseUrl = "https://opencode.ai/zen/v1",
                maxContextLength = 272_000,
                maxTokens = 128_000
            )
            normalized.startsWith("claude-") || normalized.startsWith("qwen") ->
                LocalOAuthModelTarget(
                    protocol = "anthropic_messages",
                    baseUrl = "https://opencode.ai/zen",
                    maxContextLength = 200_000,
                    maxTokens = 128_000
                )
            else -> LocalOAuthModelTarget(
                protocol = "openai_chat",
                baseUrl = "https://opencode.ai/zen/v1",
                maxContextLength = 131_072,
                maxTokens = 65_536
            )
        }
    }

    fun supportsOpenCodeModel(modelId: String): Boolean =
        !modelId.startsWith("gemini-", ignoreCase = true)

    fun createLocalModel(
        account: LocalOAuthAccountEntity,
        modelId: String,
        priority: Int,
        existing: LocalAiModelEntity? = null
    ): LocalAiModelEntity {
        val spec = requireNotNull(get(account.provider)) { "未知 OAuth 提供商: ${account.provider}" }
        val target = resolveModelTarget(account.provider, modelId)
        val stableId = UUID.nameUUIDFromBytes(
            "${account.id}:$modelId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val now = Instant.now().toString()
        return existing?.copy(
            name = existing.name.ifBlank { "${spec.title} · $modelId" },
            protocol = target.protocol,
            provider = spec.id,
            apiKey = "oauth:${account.id}",
            baseUrl = target.baseUrl,
            model = modelId,
            priority = priority,
            maxTokens = target.maxTokens,
            maxContextLength = target.maxContextLength,
            oauthAccountId = account.id
        ) ?: LocalAiModelEntity(
            id = stableId,
            name = "${spec.title} · $modelId",
            protocol = target.protocol,
            provider = spec.id,
            apiKey = "oauth:${account.id}",
            baseUrl = target.baseUrl,
            model = modelId,
            enabled = true,
            purpose = "chat",
            priority = priority,
            active = false,
            maxTokens = target.maxTokens,
            maxContextLength = target.maxContextLength,
            appendBaseUrlPath = true,
            supportsTools = true,
            supportsReasoning = true,
            supportsStream = true,
            createdAt = now,
            oauthAccountId = account.id
        )
    }
}
