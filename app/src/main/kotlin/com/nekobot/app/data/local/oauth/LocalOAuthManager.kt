package com.nekobot.app.data.local.oauth

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.AiModelDao
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalOAuthAccountEntity
import com.nekobot.app.data.local.db.OAuthAccountDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalOAuthManager(
    private val accountDao: OAuthAccountDao,
    private val aiModelDao: AiModelDao,
    private val client: OkHttpClient = defaultClient,
    /** 用于持久化 Kimi OAuth 的设备 ID；为空时退化为进程内随机 ID。 */
    private val filesDir: java.io.File? = null
) {
    private val gson = Gson()
    private val secrets = OAuthSecretStore()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val kimiDeviceId: String by lazy { loadOrCreateKimiDeviceId() }

    fun observeAccounts(): Flow<List<LocalOAuthAccountEntity>> = accountDao.observeAll()

    suspend fun listAccounts(): List<LocalOAuthAccountEntity> = accountDao.listAll()

    suspend fun startLogin(provider: String): OAuthLoginSession = withContext(Dispatchers.IO) {
        when (provider) {
            LocalOAuthProviders.CODEX -> startCodexLogin()
            LocalOAuthProviders.MINIMAX -> startMiniMaxLogin()
            LocalOAuthProviders.XAI -> startXaiLogin()
            LocalOAuthProviders.ANTHROPIC -> startAnthropicLogin()
            LocalOAuthProviders.KIMI -> startKimiLogin()
            LocalOAuthProviders.WORKBUDDY_CN,
            LocalOAuthProviders.WORKBUDDY_INTL -> startWorkBuddyLogin(provider)
            LocalOAuthProviders.QWEN ->
                error("Qwen OAuth 需要导入 Qwen CLI 的 oauth_creds.json")
            LocalOAuthProviders.OPENCODE_ZEN,
            LocalOAuthProviders.OPENCODE_GO ->
                error("OpenCode 需要输入 API Key")
            else -> error("未知 OAuth 提供商: $provider")
        }
    }

    suspend fun pollLogin(session: OAuthLoginSession): OAuthPollResult =
        withContext(Dispatchers.IO) {
            if (System.currentTimeMillis() >= session.expiresAt) {
                return@withContext OAuthPollResult.Failed("登录授权已过期，请重新开始")
            }
            runCatching {
                when (session.provider) {
                    LocalOAuthProviders.CODEX -> pollCodex(session)
                    LocalOAuthProviders.MINIMAX -> pollMiniMax(session)
                    LocalOAuthProviders.XAI -> pollXai(session)
                    LocalOAuthProviders.KIMI -> pollKimi(session)
                    LocalOAuthProviders.WORKBUDDY_CN,
                    LocalOAuthProviders.WORKBUDDY_INTL -> pollWorkBuddy(session)
                    else -> OAuthPollResult.Failed("该提供商不使用设备码轮询")
                }
            }.getOrElse { OAuthPollResult.Failed(it.message ?: "OAuth 登录失败") }
        }

    suspend fun submitAnthropicCode(
        session: OAuthLoginSession,
        codeInput: String
    ): OAuthPollResult = withContext(Dispatchers.IO) {
        if (session.provider != LocalOAuthProviders.ANTHROPIC) {
            return@withContext OAuthPollResult.Failed("OAuth 会话类型不匹配")
        }
        runCatching {
            val parts = codeInput.trim().split('#', limit = 2)
            val code = parts.firstOrNull().orEmpty().trim()
            require(code.isNotEmpty()) { "请输入 Anthropic 授权码" }
            val returnedState = parts.getOrNull(1).orEmpty()
            val expectedState = session.secret.getValue("state")
            require(returnedState == expectedState) { "OAuth state 不匹配，请重新登录" }
            val payload = jsonObjectOf(
                "grant_type" to "authorization_code",
                "client_id" to ANTHROPIC_CLIENT_ID,
                "code" to code,
                "state" to (returnedState.ifEmpty { expectedState }),
                "redirect_uri" to ANTHROPIC_REDIRECT_URI,
                "code_verifier" to session.secret.getValue("code_verifier")
            )
            val response = postJsonWithFallback(
                ANTHROPIC_TOKEN_ENDPOINTS,
                payload,
                mapOf("User-Agent" to "axios/1.7.9")
            )
            val state = tokenStateFromStandardResponse(
                response,
                clientId = ANTHROPIC_CLIENT_ID,
                tokenEndpoint = ANTHROPIC_TOKEN_ENDPOINTS.first()
            )
            connectedResult(LocalOAuthProviders.ANTHROPIC, state)
        }.getOrElse { OAuthPollResult.Failed(it.message ?: "Anthropic OAuth 登录失败") }
    }

    suspend fun importQwenCredentials(rawJson: String): OAuthPollResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val accessToken = root.string("access_token")
                val refreshToken = root.string("refresh_token")
                require(accessToken.isNotBlank()) { "oauth_creds.json 缺少 access_token" }
                require(refreshToken.isNotBlank()) { "oauth_creds.json 缺少 refresh_token" }
                val expiryDate = root.longOrNull("expiry_date")
                val state = OAuthTokenState(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiryDate,
                    tokenEndpoint = QWEN_TOKEN_URL,
                    clientId = QWEN_CLIENT_ID,
                    resourceUrl = root.string("resource_url").ifBlank { "portal.qwen.ai" }
                )
                connectedResult(LocalOAuthProviders.QWEN, state)
            }.getOrElse { OAuthPollResult.Failed(it.message ?: "导入 Qwen OAuth 凭证失败") }
        }

    suspend fun importApiKey(provider: String, apiKey: String): OAuthPollResult =
        withContext(Dispatchers.IO) {
            runCatching {
                require(
                    provider == LocalOAuthProviders.OPENCODE_ZEN ||
                        provider == LocalOAuthProviders.OPENCODE_GO
                ) { "该提供商不支持 API Key 登录" }
                val normalized = apiKey.trim()
                require(normalized.isNotBlank()) { "请输入 OpenCode API Key" }
                connectedResult(
                    provider,
                    OAuthTokenState(accessToken = normalized)
                )
            }.getOrElse {
                OAuthPollResult.Failed(it.message ?: "OpenCode API Key 登录失败")
            }
        }

    suspend fun availableModels(accountId: String, refresh: Boolean = false): List<String> =
        withContext(Dispatchers.IO) {
            val account = requireNotNull(accountDao.getById(accountId)) { "OAuth 账号不存在" }
            val spec = requireNotNull(LocalOAuthProviders.get(account.provider))
            val metadata = parseMetadata(account.metadataJson)
            val cached = metadata.getAsJsonArray("models")
                ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                .orEmpty()
            if (!refresh && cached.isNotEmpty()) return@withContext cached
            val state = resolveTokenState(account, forceRefresh = false)
            val fetched = fetchProviderModels(account.provider, state)
            val models = mergeModels(spec.models, fetched)
            saveMetadata(account, metadata.apply { add("models", gson.toJsonTree(models)) })
            models
        }

    suspend fun selectedModels(accountId: String): List<String> =
        aiModelDao.listByOAuthAccount(accountId).map(LocalAiModelEntity::model)

    suspend fun syncSelectedModels(accountId: String, selected: Set<String>) {
        withContext(Dispatchers.IO) {
            val account = requireNotNull(accountDao.getById(accountId)) { "OAuth 账号不存在" }
            val existing = aiModelDao.listByOAuthAccount(accountId).associateBy { it.model }
            existing.values
                .filterNot { it.model in selected }
                .forEach { aiModelDao.deleteById(it.id) }

            selected.forEachIndexed { index, modelId ->
                aiModelDao.upsert(
                    LocalOAuthProviders.createLocalModel(
                        account = account,
                        modelId = modelId,
                        priority = existing[modelId]?.priority ?: (existing.size + index),
                        existing = existing[modelId]
                    )
                )
            }
            val active = aiModelDao.getActive()
            if (active == null || active.oauthAccountId == accountId && active.model !in selected) {
                aiModelDao.listByOAuthAccount(accountId).firstOrNull()?.let {
                    aiModelDao.setActiveForPurpose(it.id, "chat")
                }
            }
        }
    }

    suspend fun deleteAccount(accountId: String) {
        withContext(Dispatchers.IO) {
            val deletedModels = aiModelDao.listByOAuthAccount(accountId)
            val deletedActiveModel = deletedModels.any(LocalAiModelEntity::active)
            aiModelDao.deleteByOAuthAccount(accountId)
            accountDao.deleteById(accountId)
            if (deletedActiveModel) {
                aiModelDao.listByPurpose("chat").firstOrNull()?.let {
                    aiModelDao.setActiveForPurpose(it.id, "chat")
                }
            }
        }
    }

    /**
     * 每次实际请求前解析账号，并在即将过期时刷新。
     * 返回的 header 覆盖协议默认 header，保证 MiniMax/Anthropic OAuth 使用 Bearer。
     */
    suspend fun resolveCredential(accountId: String): OAuthRuntimeCredential =
        withContext(Dispatchers.IO) {
            val account = requireNotNull(accountDao.getById(accountId)) { "OAuth 账号已删除" }
            val state = resolveTokenState(account, forceRefresh = false)
            when (account.provider) {
                LocalOAuthProviders.CODEX -> {
                    val accountIdHeader = extractJwtString(
                        state.accessToken,
                        "https://api.openai.com/auth",
                        "chatgpt_account_id"
                    )
                    OAuthRuntimeCredential(
                        accessToken = state.accessToken,
                        extraHeaders = buildMap {
                            put("User-Agent", "codex_cli_rs/0.0.0 (Nekobot Android)")
                            put("originator", "codex_cli_rs")
                            accountIdHeader?.let { put("ChatGPT-Account-ID", it) }
                        }
                    )
                }
                LocalOAuthProviders.MINIMAX -> OAuthRuntimeCredential(
                    accessToken = state.accessToken,
                    extraHeaders = mapOf("Authorization" to "Bearer ${state.accessToken}"),
                    removeHeaders = setOf("x-api-key")
                )
                LocalOAuthProviders.QWEN -> {
                    val userAgent = "QwenCode/0.10.3 (android; arm64)"
                    OAuthRuntimeCredential(
                        accessToken = state.accessToken,
                        extraHeaders = mapOf(
                            "User-Agent" to userAgent,
                            "X-DashScope-CacheControl" to "enable",
                            "X-DashScope-UserAgent" to userAgent,
                            "X-DashScope-AuthType" to "qwen-oauth"
                        )
                    )
                }
                LocalOAuthProviders.ANTHROPIC -> OAuthRuntimeCredential(
                    accessToken = state.accessToken,
                    extraHeaders = mapOf(
                        "Authorization" to "Bearer ${state.accessToken}",
                        "anthropic-beta" to ANTHROPIC_OAUTH_BETAS,
                        "User-Agent" to "claude-code/2.1.74 (external, cli)",
                        "x-app" to "cli"
                    ),
                    removeHeaders = setOf("x-api-key")
                )
                LocalOAuthProviders.WORKBUDDY_CN,
                LocalOAuthProviders.WORKBUDDY_INTL -> OAuthRuntimeCredential(
                    accessToken = state.accessToken,
                    extraHeaders = workBuddyRuntimeHeaders(account.provider, state)
                )
                else -> OAuthRuntimeCredential(accessToken = state.accessToken)
            }
        }

    private fun startCodexLogin(): OAuthLoginSession {
        val body = gson.toJson(mapOf("client_id" to CODEX_CLIENT_ID))
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = executeJson(
            Request.Builder()
                .url("$CODEX_ISSUER/api/accounts/deviceauth/usercode")
                .post(body)
                .build()
        )
        val userCode = response.string("user_code")
        val deviceAuthId = response.string("device_auth_id")
        require(userCode.isNotBlank() && deviceAuthId.isNotBlank()) {
            "OpenAI 设备码响应缺少必要字段"
        }
        val interval = response.intOrNull("interval")?.coerceAtLeast(3) ?: 5
        return OAuthLoginSession(
            provider = LocalOAuthProviders.CODEX,
            verificationUrl = "$CODEX_ISSUER/codex/device",
            userCode = userCode,
            expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L,
            pollIntervalSeconds = interval,
            secret = mapOf("device_auth_id" to deviceAuthId)
        )
    }

    private suspend fun pollCodex(session: OAuthLoginSession): OAuthPollResult {
        val body = gson.toJson(
            mapOf(
                "device_auth_id" to session.secret.getValue("device_auth_id"),
                "user_code" to session.userCode
            )
        ).toRequestBody(JSON_MEDIA_TYPE)
        val response = client.newCall(
            Request.Builder()
                .url("$CODEX_ISSUER/api/accounts/deviceauth/token")
                .post(body)
                .build()
        ).execute()
        response.use {
            if (it.code in setOf(403, 404)) return OAuthPollResult.Pending
            val raw = it.body?.string().orEmpty()
            require(it.isSuccessful) { "OpenAI 登录轮询失败: HTTP ${it.code} ${raw.take(200)}" }
            val codeResponse = JsonParser.parseString(raw).asJsonObject
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", codeResponse.string("authorization_code"))
                .add("redirect_uri", "$CODEX_ISSUER/deviceauth/callback")
                .add("client_id", CODEX_CLIENT_ID)
                .add("code_verifier", codeResponse.string("code_verifier"))
                .build()
            val tokenResponse = executeJson(
                Request.Builder().url(CODEX_TOKEN_URL).post(form).build()
            )
            return connectedResult(
                LocalOAuthProviders.CODEX,
                tokenStateFromStandardResponse(
                    tokenResponse,
                    clientId = CODEX_CLIENT_ID,
                    tokenEndpoint = CODEX_TOKEN_URL
                )
            )
        }
    }

    private fun startMiniMaxLogin(): OAuthLoginSession {
        val verifier = randomUrlSafe(64)
        val challenge = sha256UrlSafe(verifier)
        val state = randomUrlSafe(16)
        val form = FormBody.Builder()
            .add("response_type", "code")
            .add("client_id", MINIMAX_CLIENT_ID)
            .add("scope", MINIMAX_SCOPE)
            .add("code_challenge", challenge)
            .add("code_challenge_method", "S256")
            .add("state", state)
            .build()
        val response = executeJson(
            Request.Builder()
                .url("$MINIMAX_PORTAL/oauth/code")
                .header("Accept", "application/json")
                .header("x-request-id", UUID.randomUUID().toString())
                .post(form)
                .build()
        )
        require(response.string("state") == state) { "MiniMax OAuth state 不匹配" }
        val rawExpiry = response.longOrNull("expired_in") ?: 900L
        val expiresAt = if (rawExpiry > 1_000_000_000_000L) {
            rawExpiry
        } else {
            System.currentTimeMillis() + rawExpiry * 1000L
        }
        return OAuthLoginSession(
            provider = LocalOAuthProviders.MINIMAX,
            verificationUrl = response.string("verification_uri"),
            userCode = response.string("user_code"),
            expiresAt = expiresAt,
            pollIntervalSeconds = ((response.longOrNull("interval") ?: 2000L) / 1000L)
                .toInt()
                .coerceAtLeast(2),
            secret = mapOf("code_verifier" to verifier)
        )
    }

    private suspend fun pollMiniMax(session: OAuthLoginSession): OAuthPollResult {
        val form = FormBody.Builder()
            .add("grant_type", MINIMAX_USER_CODE_GRANT)
            .add("client_id", MINIMAX_CLIENT_ID)
            .add("user_code", session.userCode)
            .add("code_verifier", session.secret.getValue("code_verifier"))
            .build()
        val response = executeJson(
            Request.Builder()
                .url("$MINIMAX_PORTAL/oauth/token")
                .header("Accept", "application/json")
                .post(form)
                .build()
        )
        return when (response.string("status")) {
            "success" -> {
                val rawExpiry = response.longOrNull("expired_in") ?: 900L
                val expiresAt = if (rawExpiry > 1_000_000_000_000L) {
                    rawExpiry
                } else {
                    System.currentTimeMillis() + rawExpiry * 1000L
                }
                connectedResult(
                    LocalOAuthProviders.MINIMAX,
                    OAuthTokenState(
                        accessToken = response.string("access_token"),
                        refreshToken = response.string("refresh_token"),
                        expiresAt = expiresAt,
                        tokenEndpoint = "$MINIMAX_PORTAL/oauth/token",
                        clientId = MINIMAX_CLIENT_ID,
                        portalBaseUrl = MINIMAX_PORTAL,
                        resourceUrl = response.string("resource_url")
                    )
                )
            }
            "error" -> OAuthPollResult.Failed("MiniMax 拒绝了本次授权")
            else -> OAuthPollResult.Pending
        }
    }

    private fun startXaiLogin(): OAuthLoginSession {
        val discovery = executeJson(
            Request.Builder().url(XAI_DISCOVERY_URL).get().build()
        )
        val tokenEndpoint = discovery.string("token_endpoint")
        val parsedEndpoint = tokenEndpoint.toHttpUrlOrNull()
        val endpointHost = parsedEndpoint?.host.orEmpty().lowercase()
        require(
            parsedEndpoint?.scheme == "https" &&
                (endpointHost == "x.ai" || endpointHost.endsWith(".x.ai"))
        ) {
            "xAI 返回了不安全的 token endpoint"
        }
        val form = FormBody.Builder()
            .add("client_id", XAI_CLIENT_ID)
            .add("scope", XAI_SCOPE)
            .build()
        val response = executeJson(
            Request.Builder()
                .url(XAI_DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(form)
                .build()
        )
        return OAuthLoginSession(
            provider = LocalOAuthProviders.XAI,
            verificationUrl = response.string("verification_uri_complete")
                .ifBlank { response.string("verification_uri") },
            userCode = response.string("user_code"),
            expiresAt = System.currentTimeMillis() +
                (response.longOrNull("expires_in") ?: 900L) * 1000L,
            pollIntervalSeconds = response.intOrNull("interval")?.coerceAtLeast(1) ?: 5,
            secret = mapOf(
                "device_code" to response.string("device_code"),
                "token_endpoint" to tokenEndpoint
            )
        )
    }

    private suspend fun pollXai(session: OAuthLoginSession): OAuthPollResult {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", XAI_CLIENT_ID)
            .add("device_code", session.secret.getValue("device_code"))
            .build()
        val response = client.newCall(
            Request.Builder()
                .url(session.secret.getValue("token_endpoint"))
                .header("Accept", "application/json")
                .post(form)
                .build()
        ).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            val data = runCatching { JsonParser.parseString(raw).asJsonObject }
                .onFailure {
                    LocalLogger.w(TAG, R.string.log_oauth_poll_parse_failed, it.message)
                }.getOrDefault(JsonObject())
            if (!it.isSuccessful) {
                return when (data.string("error")) {
                    "authorization_pending", "slow_down" -> OAuthPollResult.Pending
                    else -> OAuthPollResult.Failed(
                        data.string("error_description").ifBlank {
                            "xAI OAuth 失败: HTTP ${it.code} ${raw.take(200)}"
                        }
                    )
                }
            }
            return connectedResult(
                LocalOAuthProviders.XAI,
                tokenStateFromStandardResponse(
                    data,
                    clientId = XAI_CLIENT_ID,
                    tokenEndpoint = session.secret.getValue("token_endpoint")
                )
            )
        }
    }

    private fun startKimiLogin(): OAuthLoginSession {
        val form = FormBody.Builder()
            .add("client_id", KIMI_CLIENT_ID)
            .build()
        val response = executeJson(
            Request.Builder()
                .url("$KIMI_OAUTH_HOST/api/oauth/device_authorization")
                .header("Accept", "application/json")
                .post(form)
                .apply { kimiDeviceHeaders().forEach { (name, value) -> header(name, value) } }
                .build()
        )
        val userCode = response.string("user_code")
        val deviceCode = response.string("device_code")
        require(userCode.isNotBlank() && deviceCode.isNotBlank()) {
            "Kimi 设备码响应缺少必要字段"
        }
        val verificationUrl = response.string("verification_uri_complete")
            .ifBlank { response.string("verification_uri") }
        require(verificationUrl.isNotBlank()) { "Kimi 未返回授权地址" }
        return OAuthLoginSession(
            provider = LocalOAuthProviders.KIMI,
            verificationUrl = verificationUrl,
            userCode = userCode,
            expiresAt = System.currentTimeMillis() +
                (response.longOrNull("expires_in") ?: 900L) * 1000L,
            pollIntervalSeconds = response.intOrNull("interval")?.coerceAtLeast(2) ?: 5,
            secret = mapOf("device_code" to deviceCode)
        )
    }

    private suspend fun pollKimi(session: OAuthLoginSession): OAuthPollResult {
        val form = FormBody.Builder()
            .add("client_id", KIMI_CLIENT_ID)
            .add("device_code", session.secret.getValue("device_code"))
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val response = client.newCall(
            Request.Builder()
                .url(KIMI_TOKEN_URL)
                .header("Accept", "application/json")
                .post(form)
                .apply { kimiDeviceHeaders().forEach { (name, value) -> header(name, value) } }
                .build()
        ).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            val data = runCatching { JsonParser.parseString(raw).asJsonObject }
                .onFailure { error ->
                    LocalLogger.w(TAG, R.string.log_oauth_poll_parse_failed, error.message)
                }.getOrDefault(JsonObject())
            if (it.isSuccessful && data.string("access_token").isNotBlank()) {
                return connectedResult(
                    LocalOAuthProviders.KIMI,
                    tokenStateFromStandardResponse(
                        data,
                        clientId = KIMI_CLIENT_ID,
                        tokenEndpoint = KIMI_TOKEN_URL
                    )
                )
            }
            return when (data.string("error")) {
                "authorization_pending", "slow_down" -> OAuthPollResult.Pending
                "expired_token" -> OAuthPollResult.Failed("Kimi 设备码已过期，请重新登录")
                else -> OAuthPollResult.Failed(
                    data.string("error_description").ifBlank {
                        "Kimi OAuth 失败: HTTP ${it.code} ${raw.take(200)}"
                    }
                )
            }
        }
    }

    /**
     * WorkBuddy / CodeBuddy 浏览器授权登录：
     * 1. `POST {root}/v2/plugin/auth/state?platform=CLI` 取 state 与授权页地址；
     * 2. 用户在浏览器完成授权（国内版为扫码）；
     * 3. 应用轮询 `GET {root}/v2/plugin/auth/token?state=`，业务码 11217 表示仍在等待。
     */
    private fun startWorkBuddyLogin(provider: String): OAuthLoginSession {
        val spec = requireNotNull(LocalOAuthProviders.get(provider))
        val root = spec.authBase
        val request = Request.Builder()
            .url("$root/v2/plugin/auth/state?platform=CLI")
            .post(EMPTY_JSON_BODY)
            .apply { workBuddyBrowserHeaders(root).forEach { (name, value) -> header(name, value) } }
            .build()
        val data = executeJson(request).dataObject()
        val state = data.string("state")
        require(state.isNotBlank()) { "WorkBuddy 授权响应缺少 state" }
        val authUrl = data.string("authUrl")
            .ifBlank { data.string("auth_url") }
            .ifBlank { data.string("url") }
        return OAuthLoginSession(
            provider = provider,
            verificationUrl = authUrl.ifBlank { "$root/login?state=$state" },
            expiresAt = System.currentTimeMillis() + WORKBUDDY_LOGIN_TTL_MS,
            pollIntervalSeconds = WORKBUDDY_POLL_INTERVAL_SECONDS,
            secret = mapOf("state" to state, "root" to root)
        )
    }

    private suspend fun pollWorkBuddy(session: OAuthLoginSession): OAuthPollResult {
        val root = session.secret["root"].orEmpty()
        val state = session.secret.getValue("state")
        val request = Request.Builder()
            .url("$root/v2/plugin/auth/token?state=$state")
            .apply { workBuddyBrowserHeaders(root).forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val (httpCode, body) = executeJsonOrNull(request)
        // 未完成授权时上游返回非 2xx 或业务码 11217，都视为继续等待。
        if (body == null || httpCode !in 200..299) return OAuthPollResult.Pending
        val businessCode = body.intOrNull("code")
        if (businessCode == WORKBUDDY_LOGIN_PENDING_CODE) return OAuthPollResult.Pending
        if (businessCode != null && businessCode != 0 && businessCode != 200) {
            return OAuthPollResult.Failed(
                body.string("msg").ifBlank { body.string("message") }
                    .ifBlank { "WorkBuddy 登录失败 (code=$businessCode)" }
            )
        }
        val data = body.dataObject()
        val accessToken = data.string("accessToken").ifBlank { data.string("access_token") }
        if (accessToken.isBlank()) return OAuthPollResult.Pending
        val domain = data.string("domain")
        val expiresIn = data.longOrNull("expiresIn") ?: data.longOrNull("expires_in")
        val account = fetchWorkBuddyAccount(root, state, accessToken, domain)
        return connectedResult(
            provider = session.provider,
            state = OAuthTokenState(
                accessToken = accessToken,
                refreshToken = data.string("refreshToken").ifBlank { data.string("refresh_token") },
                expiresAt = expiresIn?.takeIf { it > 0 }
                    ?.let { System.currentTimeMillis() + it * 1000L },
                tokenEndpoint = "$root/v2/plugin/auth/token/refresh",
                portalBaseUrl = root,
                uid = account?.uid,
                enterpriseId = account?.enterpriseId,
                domain = domain.ifBlank { null }
            ),
            label = account?.label
        )
    }

    /** 授权完成后读取账号资料，仅用于账号标签与请求身份头。 */
    private fun fetchWorkBuddyAccount(
        root: String,
        state: String,
        accessToken: String,
        domain: String
    ): WorkBuddyAccountInfo? = runCatching {
        val request = Request.Builder()
            .url("$root/v2/plugin/login/account?state=$state")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", WORKBUDDY_USER_AGENT)
            .apply { if (domain.isNotBlank()) header("X-Domain", domain) }
            .get()
            .build()
        val data = executeJson(request).dataObject()
        val email = data.string("email")
        val nickname = data.string("nickname")
        val uid = data.string("uid")
        WorkBuddyAccountInfo(
            label = email.ifBlank { nickname }.ifBlank { uid }.ifBlank { null },
            uid = uid.ifBlank { null },
            enterpriseId = data.string("enterpriseId").ifBlank { null }
        )
    }.onFailure {
        LocalLogger.w(TAG, "WorkBuddy 账号信息获取失败：${it.message}")
    }.getOrNull()

    /**
     * WorkBuddy / CodeBuddy 刷新令牌：上游会轮换 refresh token，
     * 刷新必须携带 X-Refresh-Token 与来源标识。
     */
    private fun refreshWorkBuddy(state: OAuthTokenState): OAuthTokenState {
        val root = state.portalBaseUrl.trimEnd('/')
        require(root.startsWith("https://")) { "OAuth token endpoint 不安全" }
        require(state.refreshToken.isNotBlank()) { "WorkBuddy 登录已过期，请重新登录" }
        val builder = Request.Builder()
            .url("$root/v2/plugin/auth/token/refresh")
            .post(EMPTY_JSON_BODY)
            .header("Authorization", "Bearer ${state.accessToken}")
            .header("X-Refresh-Token", state.refreshToken)
            .header("X-Auth-Refresh-Source", "workbuddy")
            .header("User-Agent", WORKBUDDY_USER_AGENT)
        state.enterpriseId.orEmpty().takeIf(String::isNotBlank)
            ?.let { builder.header("X-Enterprise-Id", it) }
        val response = executeJson(builder.build())
        val code = response.intOrNull("code")
        require(code == null || code == 0 || code == 200) {
            "WorkBuddy 刷新失败: " + response.string("msg").ifBlank { "code=$code" }
        }
        val data = response.dataObject()
        val accessToken = data.string("accessToken").ifBlank { data.string("access_token") }
        require(accessToken.isNotBlank()) { "WorkBuddy 刷新响应缺少 accessToken" }
        val expiresIn = data.longOrNull("expiresIn") ?: data.longOrNull("expires_in")
        val domain = data.string("domain").ifBlank { state.domain.orEmpty() }
        return state.copy(
            accessToken = accessToken,
            refreshToken = data.string("refreshToken")
                .ifBlank { data.string("refresh_token") }
                .ifBlank { state.refreshToken },
            expiresAt = expiresIn?.takeIf { it > 0 }
                ?.let { System.currentTimeMillis() + it * 1000L },
            domain = domain.ifBlank { null }
        )
    }

    private fun workBuddyBrowserHeaders(root: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to root,
        "Referer" to "$root/",
        "User-Agent" to WORKBUDDY_USER_AGENT
    )

    /**
     * WorkBuddy / CodeBuddy 聊天端点的客户端身份头。
     * 国内版走 CLI 身份，国际版走 WorkBuddy 桌面端身份；缺失的账号/企业/部门
     * 字段按上游约定用 X-No-* 显式声明。
     */
    private fun workBuddyRuntimeHeaders(
        provider: String,
        state: OAuthTokenState
    ): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${state.accessToken}")
        put("X-Product", "SaaS")
        if (provider == LocalOAuthProviders.WORKBUDDY_INTL) {
            put("User-Agent", WORKBUDDY_INTL_UA)
            put("X-IDE-Type", "WorkBuddy")
            put("X-IDE-Name", "WorkBuddy")
            put("X-IDE-Version", WORKBUDDY_INTL_VERSION)
            put("X-Agent-Purpose", "conversation")
            put("X-Agent-Intent", "craft")
            put("X-Requested-With", "XMLHttpRequest")
        } else {
            put("User-Agent", WORKBUDDY_CN_UA)
            put("X-IDE-Type", "CLI")
            put("X-IDE-Name", "CLI")
            put("X-IDE-Version", WORKBUDDY_CN_VERSION)
        }
        val uid = state.uid.orEmpty().trim()
        if (uid.isEmpty()) put("X-No-User-Id", "1") else put("X-User-Id", uid)
        val enterpriseId = state.enterpriseId.orEmpty().trim()
        if (enterpriseId.isEmpty()) {
            put("X-No-Enterprise-Id", "1")
        } else {
            put("X-Enterprise-Id", enterpriseId)
            put("X-Tenant-Id", enterpriseId)
        }
        val domain = state.domain.orEmpty().trim()
        if (domain.isEmpty()) put("X-No-Department-Info", "1") else put("X-Domain", domain)
        state.refreshToken.takeIf(String::isNotBlank)?.let { put("X-Refresh-Token", it) }
    }

    /** WorkBuddy / CodeBuddy 在线模型目录：`GET {root}/v3/config?repos=`。 */
    private fun fetchWorkBuddyModels(provider: String, state: OAuthTokenState): List<String> {
        val spec = requireNotNull(LocalOAuthProviders.get(provider))
        val intl = provider == LocalOAuthProviders.WORKBUDDY_INTL
        val builder = Request.Builder()
            .url("${spec.authBase}/v3/config?repos=")
            .header("Authorization", "Bearer ${state.accessToken}")
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Product", "SaaS")
            .header("X-Agent-Intent", "craft")
        if (intl) {
            builder.header("User-Agent", WORKBUDDY_INTL_UA)
                .header("X-IDE-Type", "WorkBuddy")
                .header("X-IDE-Name", "WorkBuddy")
                .header("X-IDE-Version", WORKBUDDY_INTL_VERSION)
        } else {
            // 国内版目录接口会校验 UA，非 CodeBuddyIDE 直接返回 12403
            builder.header("User-Agent", WORKBUDDY_CN_IDE_UA)
                .header("X-IDE-Type", "VSCode")
                .header("X-IDE-Name", "VSCode")
                .header("X-IDE-Version", WORKBUDDY_CN_IDE_VERSION)
                .header("X-Product-Version", WORKBUDDY_CN_IDE_VERSION)
        }
        state.uid.orEmpty().takeIf(String::isNotBlank)
            ?.let { builder.header("X-User-Id", it) }
        state.enterpriseId.orEmpty().takeIf(String::isNotBlank)
            ?.let {
                builder.header("X-Enterprise-Id", it)
                builder.header("X-Tenant-Id", it)
            }
        state.domain.orEmpty().takeIf(String::isNotBlank)
            ?.let { builder.header("X-Domain", it) }
        return extractWorkBuddyModelIds(executeJson(builder.get().build()))
    }

    /**
     * 目录结构随上游版本变化，这里按"模型数组"的常见键名递归提取 id，
     * 只做宽松收集；拉不到时回退到内置兜底列表。
     */
    private fun extractWorkBuddyModelIds(root: JsonObject): List<String> {
        val ids = LinkedHashSet<String>()
        fun visit(element: com.google.gson.JsonElement, key: String) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (name, value) ->
                    visit(value, name)
                }
                element.isJsonArray -> element.asJsonArray.forEach { item ->
                    if (key.lowercase() in WORKBUDDY_MODEL_LIST_KEYS) {
                        workBuddyModelId(item)?.let(ids::add)
                    } else {
                        visit(item, key)
                    }
                }
                else -> Unit
            }
        }
        visit(root, "")
        return ids.filter { WORKBUDDY_MODEL_ID_PATTERN.matches(it) }
    }

    private fun workBuddyModelId(element: com.google.gson.JsonElement): String? {
        if (element.isJsonPrimitive) {
            return element.asString.trim().takeIf(String::isNotBlank)
        }
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return obj.string("id")
            .ifBlank { obj.string("model") }
            .ifBlank { obj.string("modelId") }
            .ifBlank { obj.string("modelName") }
            .ifBlank { obj.string("slug") }
            .ifBlank { null }
            ?.trim()
    }

    private data class WorkBuddyAccountInfo(
        val label: String?,
        val uid: String?,
        val enterpriseId: String?
    )

    private fun startAnthropicLogin(): OAuthLoginSession {
        val verifier = randomUrlSafe(32)
        val challenge = sha256UrlSafe(verifier)
        val state = randomUrlSafe(32)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("claude.ai")
            .addPathSegments("oauth/authorize")
            .addQueryParameter("code", "true")
            .addQueryParameter("client_id", ANTHROPIC_CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", ANTHROPIC_REDIRECT_URI)
            .addQueryParameter("scope", ANTHROPIC_SCOPE)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
        return OAuthLoginSession(
            provider = LocalOAuthProviders.ANTHROPIC,
            verificationUrl = url.toString(),
            expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L,
            secret = mapOf("code_verifier" to verifier, "state" to state)
        )
    }

    private suspend fun connectedResult(
        provider: String,
        state: OAuthTokenState,
        label: String? = null
    ): OAuthPollResult.Connected {
        require(state.accessToken.isNotBlank()) { "OAuth 响应缺少 access_token" }
        val spec = requireNotNull(LocalOAuthProviders.get(provider))
        val now = Instant.now().toString()
        val email = extractJwtString(state.idToken.ifBlank { state.accessToken }, "email")
            ?: extractJwtString(state.accessToken, "https://api.openai.com/profile", "email")
        val metadata = JsonObject().apply {
            email?.let { addProperty("email", it) }
        }
        val account = LocalOAuthAccountEntity(
            id = UUID.randomUUID().toString(),
            provider = provider,
            label = label?.takeIf(String::isNotBlank)
                ?: email?.let { "${spec.title} · $it" }
                ?: spec.title,
            encryptedCredentials = secrets.encrypt(gson.toJson(state)),
            metadataJson = gson.toJson(metadata),
            status = "connected",
            expiresAt = state.expiresAt,
            createdAt = now,
            updatedAt = now
        )
        accountDao.upsert(account)
        val fetched = runCatching { fetchProviderModels(provider, state) }.getOrDefault(emptyList())
        val models = mergeModels(spec.models, fetched)
        saveMetadata(account, metadata.apply { add("models", gson.toJsonTree(models)) })
        return OAuthPollResult.Connected(accountDao.getById(account.id) ?: account, models)
    }

    private suspend fun resolveTokenState(
        account: LocalOAuthAccountEntity,
        forceRefresh: Boolean
    ): OAuthTokenState {
        val lock = refreshLocks.getOrPut(account.id) { Mutex() }
        return lock.withLock {
            val latest = accountDao.getById(account.id) ?: account
            val state = decodeState(latest)
            val expiring = state.expiresAt?.let {
                it <= System.currentTimeMillis() + REFRESH_SKEW_MS
            } ?: jwtExpiresSoon(state.accessToken)
            if (!forceRefresh && !expiring) return@withLock state
            if (state.refreshToken.isBlank()) {
                accountDao.updateStatus(account.id, "reauth_required", Instant.now().toString())
                error("OAuth 登录已过期，请重新登录 ${latest.label}")
            }
            val refreshed = try {
                refreshToken(latest.provider, state)
            } catch (error: Exception) {
                accountDao.updateStatus(account.id, "reauth_required", Instant.now().toString())
                throw error
            }
            val updated = latest.copy(
                encryptedCredentials = secrets.encrypt(gson.toJson(refreshed)),
                status = "connected",
                expiresAt = refreshed.expiresAt,
                updatedAt = Instant.now().toString()
            )
            accountDao.upsert(updated)
            refreshed
        }
    }

    private fun refreshToken(provider: String, state: OAuthTokenState): OAuthTokenState {
        return when (provider) {
            LocalOAuthProviders.CODEX -> refreshStandardForm(
                state,
                CODEX_TOKEN_URL,
                CODEX_CLIENT_ID
            )
            LocalOAuthProviders.QWEN -> refreshStandardForm(
                state,
                QWEN_TOKEN_URL,
                QWEN_CLIENT_ID
            )
            LocalOAuthProviders.XAI -> refreshStandardForm(
                state,
                state.tokenEndpoint,
                XAI_CLIENT_ID
            )
            LocalOAuthProviders.MINIMAX -> {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", MINIMAX_CLIENT_ID)
                    .add("refresh_token", state.refreshToken)
                    .build()
                val response = executeJson(
                    Request.Builder().url("$MINIMAX_PORTAL/oauth/token").post(form).build()
                )
                require(response.string("status") == "success") { "MiniMax OAuth 刷新失败" }
                val rawExpiry = response.longOrNull("expired_in") ?: 900L
                state.copy(
                    accessToken = response.string("access_token"),
                    refreshToken = response.string("refresh_token").ifBlank { state.refreshToken },
                    expiresAt = if (rawExpiry > 1_000_000_000_000L) {
                        rawExpiry
                    } else {
                        System.currentTimeMillis() + rawExpiry * 1000L
                    }
                )
            }
            LocalOAuthProviders.ANTHROPIC -> {
                val payload = jsonObjectOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to state.refreshToken,
                    "client_id" to ANTHROPIC_CLIENT_ID
                )
                val response = postJsonWithFallback(
                    ANTHROPIC_TOKEN_ENDPOINTS,
                    payload,
                    mapOf("User-Agent" to "axios/1.7.9")
                )
                tokenStateFromStandardResponse(
                    response,
                    clientId = ANTHROPIC_CLIENT_ID,
                    tokenEndpoint = ANTHROPIC_TOKEN_ENDPOINTS.first(),
                    previousRefreshToken = state.refreshToken
                )
            }
            LocalOAuthProviders.KIMI -> refreshKimi(state)
            LocalOAuthProviders.WORKBUDDY_CN,
            LocalOAuthProviders.WORKBUDDY_INTL -> refreshWorkBuddy(state)
            else -> error("该 OAuth 提供商不支持刷新")
        }
    }

    /**
     * Kimi Code OAuth 刷新：token endpoint 要求携带设备信息头（X-Msh-*）。
     */
    private fun refreshKimi(state: OAuthTokenState): OAuthTokenState {
        require(KIMI_TOKEN_URL.startsWith("https://")) { "OAuth token endpoint 不安全" }
        val form = FormBody.Builder()
            .add("client_id", KIMI_CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", state.refreshToken)
            .build()
        val response = executeJson(
            Request.Builder()
                .url(KIMI_TOKEN_URL)
                .header("Accept", "application/json")
                .post(form)
                .apply { kimiDeviceHeaders().forEach { (name, value) -> header(name, value) } }
                .build()
        )
        return tokenStateFromStandardResponse(
            response,
            clientId = KIMI_CLIENT_ID,
            tokenEndpoint = KIMI_TOKEN_URL,
            previousRefreshToken = state.refreshToken
        )
    }

    /** Kimi OAuth 设备信息头；首次启动生成并持久化的稳定设备 ID。 */
    private fun kimiDeviceHeaders(): Map<String, String> {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return mapOf(
            "X-Msh-Platform" to "kimi_cli",
            "X-Msh-Version" to KIMI_CLIENT_VERSION,
            "X-Msh-Device-Name" to asciiHeader(
                android.os.Build.MODEL.ifBlank { "Android" }
            ),
            "X-Msh-Device-Model" to asciiHeader(
                "Android ${android.os.Build.VERSION.RELEASE} $abi"
            ),
            "X-Msh-Os-Version" to asciiHeader(android.os.Build.VERSION.RELEASE),
            "X-Msh-Device-Id" to kimiDeviceId
        )
    }

    private fun asciiHeader(value: String): String {
        val cleaned = value.filter { it.code in 32..126 }.trim()
        return cleaned.ifEmpty { "unknown" }
    }

    private fun loadOrCreateKimiDeviceId(): String {
        val file = filesDir?.let { java.io.File(it, "kimi_oauth_device_id") }
        val existing = runCatching {
            file?.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        }.getOrDefault("")
        if (existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        runCatching { file?.writeText(created) }
        return created
    }

    private fun refreshStandardForm(
        state: OAuthTokenState,
        endpoint: String,
        clientId: String
    ): OAuthTokenState {
        require(endpoint.startsWith("https://")) { "OAuth token endpoint 不安全" }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", state.refreshToken)
            .add("client_id", clientId)
            .build()
        val response = executeJson(Request.Builder().url(endpoint).post(form).build())
        return tokenStateFromStandardResponse(
            response,
            clientId = clientId,
            tokenEndpoint = endpoint,
            previousRefreshToken = state.refreshToken
        )
    }

    private fun fetchProviderModels(
        provider: String,
        state: OAuthTokenState
    ): List<String> {
        val spec = requireNotNull(LocalOAuthProviders.get(provider))
        if (provider == LocalOAuthProviders.WORKBUDDY_CN ||
            provider == LocalOAuthProviders.WORKBUDDY_INTL
        ) {
            return runCatching { fetchWorkBuddyModels(provider, state) }
                .getOrDefault(emptyList())
        }
        if (provider == LocalOAuthProviders.QWEN ||
            provider == LocalOAuthProviders.MINIMAX ||
            provider == LocalOAuthProviders.ANTHROPIC
        ) {
            return spec.models
        }
        val url = when (provider) {
            LocalOAuthProviders.CODEX ->
                "https://chatgpt.com/backend-api/codex/models?client_version=1.0.0"
            else -> "${spec.baseUrl.trimEnd('/')}/models"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${state.accessToken}")
            .apply {
                if (provider == LocalOAuthProviders.CODEX) {
                    header("User-Agent", "codex_cli_rs/0.0.0 (Nekobot Android)")
                    header("originator", "codex_cli_rs")
                    extractJwtString(
                        state.accessToken,
                        "https://api.openai.com/auth",
                        "chatgpt_account_id"
                    )?.let { header("ChatGPT-Account-Id", it) }
                }
                if (provider == LocalOAuthProviders.KIMI) {
                    kimiDeviceHeaders().forEach { (name, value) -> header(name, value) }
                }
            }
            .get()
            .build()
        val response = executeJson(request)
        val array = when {
            response.get("models")?.isJsonArray == true -> response.getAsJsonArray("models")
            response.get("data")?.isJsonArray == true -> response.getAsJsonArray("data")
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString
                item.isJsonObject -> {
                    val obj = item.asJsonObject
                    val hidden = obj.string("visibility").lowercase() in setOf("hide", "hidden")
                    if (hidden) null else obj.string("slug").ifBlank { obj.string("id") }.ifBlank { null }
                }
                else -> null
            }
        }.distinct().filter { modelId ->
            provider != LocalOAuthProviders.OPENCODE_ZEN &&
                provider != LocalOAuthProviders.OPENCODE_GO ||
                LocalOAuthProviders.supportsOpenCodeModel(modelId)
        }
    }

    private fun executeJson(request: Request): JsonObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val obj = JsonParser.parseString(raw).asJsonObject
                    obj.string("error_description")
                        .ifBlank { obj.string("message") }
                        .ifBlank {
                            obj.getAsJsonObject("error")?.string("message").orEmpty()
                        }
                }.getOrDefault("")
                error(
                    detail.ifBlank {
                        "HTTP ${response.code}: ${raw.take(300)}"
                    }
                )
            }
            return JsonParser.parseString(raw.ifBlank { "{}" }).asJsonObject
        }
    }

    /**
     * 不因 HTTP 状态或业务码抛错地读取一次响应，供"登录中"这类正常往返使用。
     * 返回 HTTP 状态码与解析后的 JSON（无法解析时为 null）。
     */
    private fun executeJsonOrNull(request: Request): Pair<Int, JsonObject?> =
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            response.code to runCatching {
                JsonParser.parseString(raw.ifBlank { "{}" }).asJsonObject
            }.getOrNull()
        }

    /** `{code,msg,data}` 包体里取 data；没有 data 时原样返回。 */
    private fun JsonObject.dataObject(): JsonObject =
        get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this

    private fun postJsonWithFallback(
        endpoints: List<String>,
        payload: JsonObject,
        headers: Map<String, String>
    ): JsonObject {
        var lastError: Throwable? = null
        endpoints.forEach { endpoint ->
            runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                executeJson(request)
            }.onSuccess { return it }.onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("OAuth token exchange failed")
    }

    private fun tokenStateFromStandardResponse(
        response: JsonObject,
        clientId: String,
        tokenEndpoint: String,
        previousRefreshToken: String = ""
    ): OAuthTokenState {
        val accessToken = response.string("access_token")
        require(accessToken.isNotBlank()) { "OAuth 响应缺少 access_token" }
        val expiresAt = response.longOrNull("expires_in")?.let {
            System.currentTimeMillis() + it.coerceAtLeast(1L) * 1000L
        } ?: jwtExpiryMillis(accessToken)
        return OAuthTokenState(
            accessToken = accessToken,
            refreshToken = response.string("refresh_token").ifBlank { previousRefreshToken },
            idToken = response.string("id_token"),
            expiresAt = expiresAt,
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            resourceUrl = response.string("resource_url")
        )
    }

    private suspend fun saveMetadata(
        account: LocalOAuthAccountEntity,
        metadata: JsonObject
    ) {
        accountDao.upsert(
            account.copy(
                metadataJson = gson.toJson(metadata),
                updatedAt = Instant.now().toString()
            )
        )
    }

    private fun decodeState(account: LocalOAuthAccountEntity): OAuthTokenState =
        gson.fromJson(secrets.decrypt(account.encryptedCredentials), OAuthTokenState::class.java)

    private fun parseMetadata(raw: String): JsonObject =
        runCatching { JsonParser.parseString(raw).asJsonObject }
            .onFailure {
                LocalLogger.w(TAG, R.string.log_oauth_metadata_parse_failed, it.message)
            }.getOrDefault(JsonObject())

    private fun mergeModels(curated: List<String>, fetched: List<String>): List<String> =
        (fetched + curated).distinct()

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
    }

    private fun sha256UrlSafe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun jwtExpiresSoon(token: String): Boolean =
        jwtExpiryMillis(token)?.let {
            it <= System.currentTimeMillis() + REFRESH_SKEW_MS
        } ?: false

    private fun jwtExpiryMillis(token: String): Long? =
        decodeJwtPayload(token)?.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong?.times(1000L)

    private fun extractJwtString(token: String, vararg path: String): String? {
        var current: com.google.gson.JsonElement = decodeJwtPayload(token) ?: return null
        path.forEach { key ->
            current = current.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get(key)
                ?: return null
        }
        return current.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun decodeJwtPayload(token: String): JsonObject? {
        val part = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.decode(part, Base64.NO_WRAP or Base64.URL_SAFE)
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    private fun jsonObjectOf(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject().apply { pairs.forEach { (key, value) -> addProperty(key, value) } }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

    private companion object {
        private const val TAG = "LocalOAuthManager"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val REFRESH_SKEW_MS = 120_000L

        const val CODEX_ISSUER = "https://auth.openai.com"
        const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token"

        const val QWEN_CLIENT_ID = "f0304373b74a44d2b584a3fb70ca9e56"
        const val QWEN_TOKEN_URL = "https://chat.qwen.ai/api/v1/oauth2/token"

        const val MINIMAX_CLIENT_ID = "78257093-7e40-4613-99e0-527b14b39113"
        const val MINIMAX_PORTAL = "https://api.minimax.io"
        const val MINIMAX_SCOPE = "group_id profile model.completion"
        const val MINIMAX_USER_CODE_GRANT = "urn:ietf:params:oauth:grant-type:user_code"

        const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val XAI_DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val XAI_DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"

        const val ANTHROPIC_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val ANTHROPIC_REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
        const val ANTHROPIC_SCOPE = "org:create_api_key user:profile user:inference"
        val ANTHROPIC_TOKEN_ENDPOINTS = listOf(
            "https://platform.claude.com/v1/oauth/token",
            "https://console.anthropic.com/v1/oauth/token"
        )
        const val ANTHROPIC_OAUTH_BETAS =
            "interleaved-thinking-2025-05-14," +
                "fine-grained-tool-streaming-2025-05-14," +
                "claude-code-20250219,oauth-2025-04-20"

        /** Kimi Code OAuth（Device Authorization Grant），public client，无 client_secret。 */
        const val KIMI_OAUTH_HOST = "https://auth.kimi.com"
        const val KIMI_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        const val KIMI_TOKEN_URL = "$KIMI_OAUTH_HOST/api/oauth/token"
        /** 设备信息头版本号，仅用于标识客户端。 */
        const val KIMI_CLIENT_VERSION = "1.6.0"

        /** WorkBuddy / CodeBuddy 客户端身份：国内版 CLI、国际版桌面端。 */
        const val WORKBUDDY_CN_VERSION = "2.96.0"
        const val WORKBUDDY_CN_UA = "CLI/$WORKBUDDY_CN_VERSION CodeBuddy/$WORKBUDDY_CN_VERSION"
        const val WORKBUDDY_CN_IDE_VERSION = "4.10.33259736"
        const val WORKBUDDY_CN_IDE_UA = "CodeBuddyIDE/$WORKBUDDY_CN_IDE_VERSION"
        const val WORKBUDDY_INTL_VERSION = "5.5.6"
        const val WORKBUDDY_INTL_UA = "WorkBuddy/$WORKBUDDY_INTL_VERSION"
        /** 授权流程（platform=CLI）使用的客户端标识。 */
        const val WORKBUDDY_USER_AGENT = WORKBUDDY_CN_UA
        /** 未完成授权时 upstream 的业务码。 */
        const val WORKBUDDY_LOGIN_PENDING_CODE = 11217
        const val WORKBUDDY_LOGIN_TTL_MS = 10 * 60 * 1000L
        const val WORKBUDDY_POLL_INTERVAL_SECONDS = 2
        /** 目录 JSON 里可能承载模型数组的键名（大小写不敏感）。 */
        val WORKBUDDY_MODEL_LIST_KEYS =
            setOf("models", "modellist", "modelconfigs", "modelinfos", "chatmodels")
        val WORKBUDDY_MODEL_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:+-]{1,63}$")

        val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
