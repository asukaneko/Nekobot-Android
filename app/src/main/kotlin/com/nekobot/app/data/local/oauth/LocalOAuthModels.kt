package com.nekobot.app.data.local.oauth

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalOAuthAccountEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class OAuthLoginMode {
    DEVICE_CODE,
    /** 浏览器完成授权后由应用轮询取 token（无设备码，无需输入任何内容）。 */
    BROWSER_POLL,
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
    val defaultMaxTokens: Int,
    /** 鉴权接口所在的站点根地址；为空时与 [baseUrl] 相同。 */
    val authBaseUrl: String = ""
) {
    /** 鉴权站点根地址（无尾部斜杠）。 */
    val authBase: String get() = authBaseUrl.ifBlank { baseUrl }.trimEnd('/')
}

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
    val resourceUrl: String = "",
    /** WorkBuddy / CodeBuddy 账号维度：请求头 X-User-Id。 */
    val uid: String? = null,
    /** WorkBuddy / CodeBuddy 企业维度：请求头 X-Enterprise-Id。 */
    val enterpriseId: String? = null,
    /** WorkBuddy / CodeBuddy 域信息：请求头 X-Domain。 */
    val domain: String? = null
)

object LocalOAuthProviders {
    const val CODEX = "openai-codex"
    const val QWEN = "qwen-oauth"
    const val MINIMAX = "minimax-oauth"
    const val XAI = "xai-oauth"
    const val OPENCODE_ZEN = "opencode-zen"
    const val OPENCODE_GO = "opencode-go"
    const val ANTHROPIC = "anthropic-oauth"
    const val KIMI = "kimi-oauth"
    const val WORKBUDDY_CN = "workbuddy-oauth"
    const val WORKBUDDY_INTL = "workbuddy-intl-oauth"

    /**
     * WorkBuddy / 腾讯 CodeBuddy 内置模型兜底目录。
     * 实际可用列表以 `/v3/config` 在线目录为准，这里只用于离线或拉取失败时展示。
     */
    private val WORKBUDDY_MODELS = listOf(
        "glm-5.2",
        "glm-5.1",
        "glm-5v-turbo",
        "kimi-k2.7",
        "minimax-m3-pay",
        "hy3",
        "hy3-preview",
        "hy3-preview-agent",
        "deepseek-v4-pro",
        "deepseek-v4-flash"
    )

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
                "grok-4.7",
                "grok-4.6",
                "gpt-6-luna",
                "gpt-5.6-luna",
                "glm-5.3",
                "glm-5.2",
                "glm-5.3-flash",
                "kimi-k3",
                "kimi-k2.7-code",
                "deepseek-v4-pro",
                "deepseek-v4.1-flash",
                "deepseek-flash",
                "deepseek-v4-flash",
                "mimo-v2.6-pro",
                "mimo-v2.6-flash",
                "mimo-v2.5-pro",
                "mimo-v2.5",
                "minimax-m3",
                "minimax-m2.7",
                "qwen3.8-max",
                "qwen3.8-flash",
                "qwen3.7-plus",
                "longcat-2.5-preview-free",
                "space-bunny-free",
                "hy3",
                "hy4-preview"
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
        ),
        OAuthProviderSpec(
            id = KIMI,
            title = "Kimi Code",
            subtitle = "使用 Kimi 会员订阅登录，浏览器设备码授权",
            loginMode = OAuthLoginMode.DEVICE_CODE,
            protocol = "openai_chat",
            baseUrl = "https://api.kimi.com/coding/v1",
            models = listOf(
                "k3.1-pro",
                "k3.1-pro-highspeed",
                "k3",
                "k3-256k",
                "kimi-for-coding",
                "kimi-for-coding-highspeed"
            ),
            // 1M 上下文需要更高会员等级，统一按 256K 预算避免超限请求
            defaultContextLength = 262_144,
            defaultMaxTokens = 32_768
        ),
        OAuthProviderSpec(
            id = WORKBUDDY_CN,
            title = "WorkBuddy（国内版）",
            subtitle = "腾讯 CodeBuddy 账号，浏览器扫码授权，无需 API Key",
            loginMode = OAuthLoginMode.BROWSER_POLL,
            protocol = "openai_chat",
            baseUrl = "https://copilot.tencent.com/v2",
            models = WORKBUDDY_MODELS,
            defaultContextLength = 300_000,
            defaultMaxTokens = 32_768,
            authBaseUrl = "https://copilot.tencent.com"
        ),
        OAuthProviderSpec(
            id = WORKBUDDY_INTL,
            title = "WorkBuddy（国际版）",
            subtitle = "WorkBuddy / CodeBuddy 国际账号，浏览器授权，额度与国内版独立",
            loginMode = OAuthLoginMode.BROWSER_POLL,
            protocol = "openai_chat",
            baseUrl = "https://www.workbuddy.ai/v2",
            models = WORKBUDDY_MODELS,
            defaultContextLength = 300_000,
            defaultMaxTokens = 32_768,
            authBaseUrl = "https://www.workbuddy.ai"
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
            return when {
                normalized.startsWith("minimax-") || normalized.startsWith("qwen") ->
                    LocalOAuthModelTarget(
                        protocol = "anthropic_messages",
                        baseUrl = "https://opencode.ai/zen/go",
                        maxContextLength = 200_000,
                        maxTokens = 128_000
                    )
                normalized.startsWith("gpt-") || normalized.startsWith("grok-") ->
                    // Grok / GPT Luna 在 Go 网关只提供 Responses API，
                    // chat/completions 会被拒（ModelProtocolUnsupported）
                    LocalOAuthModelTarget(
                        protocol = "openai_responses",
                        baseUrl = "https://opencode.ai/zen/go/v1",
                        maxContextLength = 272_000,
                        maxTokens = 128_000
                    )
                else ->
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
