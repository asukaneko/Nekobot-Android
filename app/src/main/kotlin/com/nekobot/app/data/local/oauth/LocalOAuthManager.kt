package com.nekobot.app.data.local.oauth

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()
    private val secrets = OAuthSecretStore()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun observeAccounts(): Flow<List<LocalOAuthAccountEntity>> = accountDao.observeAll()

    suspend fun listAccounts(): List<LocalOAuthAccountEntity> = accountDao.listAll()

    suspend fun startLogin(provider: String): OAuthLoginSession = withContext(Dispatchers.IO) {
        when (provider) {
            LocalOAuthProviders.CODEX -> startCodexLogin()
            LocalOAuthProviders.MINIMAX -> startMiniMaxLogin()
            LocalOAuthProviders.XAI -> startXaiLogin()
            LocalOAuthProviders.ANTHROPIC -> startAnthropicLogin()
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
            val data = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrDefault(JsonObject())
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
        state: OAuthTokenState
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
            label = email?.let { "${spec.title} · $it" } ?: spec.title,
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
            else -> error("该 OAuth 提供商不支持刷新")
        }
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
        runCatching { JsonParser.parseString(raw).asJsonObject }.getOrDefault(JsonObject())

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

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
