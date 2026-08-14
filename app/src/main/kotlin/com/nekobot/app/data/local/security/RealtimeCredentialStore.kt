package com.nekobot.app.data.local.security

import android.content.Context
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.AiModelRequest
import java.security.MessageDigest

/**
 * 服务端会脱敏 AI 模型密钥；Realtime WebSocket 又必须由手机直接鉴权。
 * 这里只缓存用户在 Android 端保存过的 Live 模型密钥，值由 Android Keystore 加密。
 */
internal class RealtimeCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreSecretCipher(
        keyAlias = "nekobot_realtime_credentials_v1",
        formatVersion = "rt1"
    )

    fun save(serverUrl: String, request: AiModelRequest) {
        val apiKey = request.apiKey.orEmpty().trim()
        if (request.purpose != "live" || apiKey.isBlank() || apiKey == MASKED_KEY) return
        put(keyFor(serverUrl, request.name, request.baseUrl, request.model), apiKey)
    }

    fun migrate(serverUrl: String, previous: AiModel?, request: AiModelRequest) {
        if (previous?.purpose == "live") {
            val previousKey = keyFor(serverUrl, previous.name, previous.baseUrl, previous.model)
            val nextKey = keyFor(serverUrl, request.name, request.baseUrl, request.model)
            val submitted = request.apiKey.orEmpty().trim()
            val secret = when {
                submitted.isNotBlank() && submitted != MASKED_KEY -> submitted
                else -> read(previousKey)
            }
            if (previousKey != nextKey) preferences.edit().remove(previousKey).apply()
            if (request.purpose == "live" && !secret.isNullOrBlank()) put(nextKey, secret)
        } else {
            save(serverUrl, request)
        }
    }

    fun get(serverUrl: String, model: AiModel): String? =
        read(keyFor(serverUrl, model.name, model.baseUrl, model.model))

    fun remove(serverUrl: String, model: AiModel?) {
        model ?: return
        preferences.edit()
            .remove(keyFor(serverUrl, model.name, model.baseUrl, model.model))
            .apply()
    }

    private fun put(key: String, value: String) {
        val encrypted = runCatching { cipher.encrypt(value) }.getOrNull() ?: return
        preferences.edit().putString(key, encrypted).apply()
    }

    private fun read(key: String): String? {
        val encrypted = preferences.getString(key, null) ?: return null
        return runCatching { cipher.decrypt(encrypted) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private companion object {
        const val PREFS_NAME = "realtime_credentials"
        const val MASKED_KEY = "********"

        fun keyFor(
            serverUrl: String,
            name: String?,
            baseUrl: String?,
            model: String?
        ): String {
            val identity = listOf(serverUrl, name, baseUrl, model)
                .joinToString("\u001f") { it.orEmpty().trim().trimEnd('/').lowercase() }
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
