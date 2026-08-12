package com.nekobot.app.data.local.security

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalApiKeyEntity
import com.nekobot.app.data.local.db.LocalMcpServerEntity

/** Room 敏感字段的字段级加密，保持现有数据库结构和导入格式不变。 */
internal class LocalDatabaseSecretProtector(
    private val cipher: SecretCipher
) {
    fun protect(model: LocalAiModelEntity): LocalAiModelEntity = model.copy(
        apiKey = protectUnlessReference(model.apiKey),
        proxyUrl = protect(model.proxyUrl),
        ttsHeaders = protect(model.ttsHeaders),
        ttsBodyTemplate = protect(model.ttsBodyTemplate),
        sttHeaders = protect(model.sttHeaders)
    )

    fun reveal(model: LocalAiModelEntity): LocalAiModelEntity = model.copy(
        apiKey = revealUnlessReference(model.apiKey),
        proxyUrl = reveal(model.proxyUrl),
        ttsHeaders = reveal(model.ttsHeaders),
        ttsBodyTemplate = reveal(model.ttsBodyTemplate),
        sttHeaders = reveal(model.sttHeaders)
    )

    /** 导出时不允许把无法解密的设备密文静默变成空字符串。 */
    fun revealStrict(model: LocalAiModelEntity): LocalAiModelEntity = model.copy(
        apiKey = revealUnlessReferenceStrict(model.apiKey),
        proxyUrl = revealStrict(model.proxyUrl),
        ttsHeaders = revealStrict(model.ttsHeaders),
        ttsBodyTemplate = revealStrict(model.ttsBodyTemplate),
        sttHeaders = revealStrict(model.sttHeaders)
    )

    fun protect(server: LocalMcpServerEntity): LocalMcpServerEntity = server.copy(
        url = server.url?.let(::protectNullable),
        headersJson = server.headersJson?.let(::protectNullable),
        argsJson = server.argsJson?.let(::protectNullable),
        envJson = server.envJson?.let(::protectNullable)
    )

    fun reveal(server: LocalMcpServerEntity): LocalMcpServerEntity = server.copy(
        url = server.url?.let(::revealNullable),
        headersJson = server.headersJson?.let(::revealNullable),
        argsJson = server.argsJson?.let(::revealNullable),
        envJson = server.envJson?.let(::revealNullable)
    )

    fun revealStrict(server: LocalMcpServerEntity): LocalMcpServerEntity = server.copy(
        url = server.url?.let(::revealStrict),
        headersJson = server.headersJson?.let(::revealStrict),
        argsJson = server.argsJson?.let(::revealStrict),
        envJson = server.envJson?.let(::revealStrict)
    )

    fun protect(apiKey: LocalApiKeyEntity): LocalApiKeyEntity =
        apiKey.copy(key = protect(apiKey.key))

    fun reveal(apiKey: LocalApiKeyEntity): LocalApiKeyEntity =
        apiKey.copy(key = reveal(apiKey.key))

    fun revealStrict(apiKey: LocalApiKeyEntity): LocalApiKeyEntity =
        apiKey.copy(key = revealStrict(apiKey.key))

    fun isProtected(value: String?): Boolean = value.isNullOrEmpty() || cipher.isEncrypted(value)

    private fun protectUnlessReference(value: String): String =
        if (value.startsWith(OAUTH_REFERENCE_PREFIX)) value else protect(value)

    private fun revealUnlessReference(value: String): String =
        if (value.startsWith(OAUTH_REFERENCE_PREFIX)) value else reveal(value)

    private fun revealUnlessReferenceStrict(value: String): String =
        if (value.startsWith(OAUTH_REFERENCE_PREFIX)) value else revealStrict(value)

    private fun protectNullable(value: String): String = protect(value)

    private fun revealNullable(value: String): String = reveal(value)

    private fun protect(value: String): String = when {
        value.isEmpty() || cipher.isEncrypted(value) -> value
        else -> cipher.encrypt(value)
    }

    private fun reveal(value: String): String = when {
        value.isEmpty() || !cipher.isEncrypted(value) -> value
        else -> runCatching { cipher.decrypt(value) }.getOrDefault("")
    }

    private fun revealStrict(value: String): String = when {
        value.isEmpty() || !cipher.isEncrypted(value) -> value
        else -> cipher.decrypt(value)
    }

    private companion object {
        const val OAUTH_REFERENCE_PREFIX = "oauth:"
    }
}

/** 进程级字段保护器；密钥由 Android Keystore 持有。 */
internal object LocalDatabaseSecrets {
    private val protector by lazy {
        LocalDatabaseSecretProtector(
            AndroidKeystoreSecretCipher(
                keyAlias = "nekobot_local_database_secrets_v1",
                formatVersion = "ks1"
            )
        )
    }

    fun protect(model: LocalAiModelEntity): LocalAiModelEntity = protector.protect(model)
    fun reveal(model: LocalAiModelEntity): LocalAiModelEntity = protector.reveal(model)
    fun revealStrict(model: LocalAiModelEntity): LocalAiModelEntity = protector.revealStrict(model)
    fun protect(server: LocalMcpServerEntity): LocalMcpServerEntity = protector.protect(server)
    fun reveal(server: LocalMcpServerEntity): LocalMcpServerEntity = protector.reveal(server)
    fun revealStrict(server: LocalMcpServerEntity): LocalMcpServerEntity = protector.revealStrict(server)
    fun protect(apiKey: LocalApiKeyEntity): LocalApiKeyEntity = protector.protect(apiKey)
    fun reveal(apiKey: LocalApiKeyEntity): LocalApiKeyEntity = protector.reveal(apiKey)
    fun revealStrict(apiKey: LocalApiKeyEntity): LocalApiKeyEntity = protector.revealStrict(apiKey)
}
