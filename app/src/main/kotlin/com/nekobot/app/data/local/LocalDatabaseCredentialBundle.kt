package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.oauth.OAuthSecretStore
import com.nekobot.app.data.local.security.LocalDatabaseSecrets

/**
 * 可移植的本地数据库凭据清单。
 *
 * Room 文件中的敏感字段由 Android Keystore 加密，不能直接跨重装或设备复制。
 * 此清单只应放进已经由用户密码加密的外层容器，恢复时再用当前设备 Keystore 封装。
 */
internal object LocalDatabaseCredentialBundle {
    private const val VERSION = 1
    private val gson = Gson()
    private val oauthSecrets = OAuthSecretStore()

    suspend fun capture(db: NekobotDatabase): ByteArray {
        val models = db.aiModelDao().listAllStored().map(LocalDatabaseSecrets::revealStrict)
        val apiKeys = db.apiKeyDao().listAllStored().map(LocalDatabaseSecrets::revealStrict)
        val mcpServers = db.mcpServerDao().listAllStored().map(LocalDatabaseSecrets::revealStrict)

        return JsonObject().apply {
            addProperty("version", VERSION)
            add(
                "ai_models",
                gson.toJsonTree(
                    models.map { model ->
                        mapOf(
                            "id" to model.id,
                            "api_key" to model.apiKey,
                            "proxy_url" to model.proxyUrl,
                            "tts_headers" to model.ttsHeaders,
                            "tts_body_template" to model.ttsBodyTemplate,
                            "stt_headers" to model.sttHeaders
                        )
                    }
                )
            )
            add(
                "api_keys",
                gson.toJsonTree(apiKeys.map { key -> mapOf("id" to key.id, "key" to key.key) })
            )
            add(
                "mcp_servers",
                gson.toJsonTree(
                    mcpServers.map { server ->
                        mapOf(
                            "id" to server.id,
                            "url" to server.url,
                            "headers_json" to server.headersJson,
                            "args_json" to server.argsJson,
                            "env_json" to server.envJson
                        )
                    }
                )
            )
            add(
                "oauth_accounts",
                gson.toJsonTree(
                    db.oauthAccountDao().listAll().mapNotNull { account ->
                        account.encryptedCredentials.takeIf { it.isNotEmpty() }?.let { encrypted ->
                            mapOf(
                                "id" to account.id,
                                "credentials" to oauthSecrets.decrypt(encrypted)
                            )
                        }
                    }
                )
            )
        }.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun restore(db: NekobotDatabase, raw: ByteArray) {
        val root = runCatching {
            JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject
        }.getOrElse { throw IllegalArgumentException("凭据清单格式无效", it) }
        require((root.get("version")?.asInt ?: VERSION) == VERSION) {
            "不支持的凭据清单版本"
        }

        root.getAsJsonArray("ai_models")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.requiredId()
            val existing = db.aiModelDao().getById(id) ?: return@forEach
            db.aiModelDao().upsert(
                existing.copy(
                    apiKey = obj.stringOrEmpty("api_key"),
                    proxyUrl = obj.stringOrEmpty("proxy_url"),
                    ttsHeaders = obj.stringOrEmpty("tts_headers"),
                    ttsBodyTemplate = obj.stringOrEmpty("tts_body_template"),
                    sttHeaders = obj.stringOrEmpty("stt_headers")
                )
            )
        }
        root.getAsJsonArray("api_keys")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.requiredId()
            val existing = db.apiKeyDao().getById(id) ?: return@forEach
            db.apiKeyDao().upsert(existing.copy(key = obj.stringOrEmpty("key")))
        }
        root.getAsJsonArray("mcp_servers")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.requiredId()
            val existing = db.mcpServerDao().getById(id) ?: return@forEach
            db.mcpServerDao().upsert(
                existing.copy(
                    url = obj.stringOrNull("url"),
                    headersJson = obj.stringOrNull("headers_json"),
                    argsJson = obj.stringOrNull("args_json"),
                    envJson = obj.stringOrNull("env_json")
                )
            )
        }
        root.getAsJsonArray("oauth_accounts")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.requiredId()
            val credentials = obj.stringOrEmpty("credentials")
            val existing = db.oauthAccountDao().getById(id) ?: return@forEach
            if (credentials.isNotEmpty()) {
                db.oauthAccountDao().upsert(
                    existing.copy(encryptedCredentials = oauthSecrets.encrypt(credentials))
                )
            }
        }
    }

    private fun JsonObject.requiredId(): String =
        stringOrEmpty("id").also { require(it.isNotBlank()) { "凭据清单包含空 ID" } }

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString
}
