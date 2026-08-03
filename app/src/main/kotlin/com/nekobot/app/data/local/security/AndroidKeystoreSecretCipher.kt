package com.nekobot.app.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 可替换的敏感值加解密接口，便于迁移逻辑进行纯 JVM 测试。 */
internal interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(value: String): String
    fun isEncrypted(value: String): Boolean
}

/**
 * Android Keystore AES-GCM 封装。
 *
 * 密钥不可导出；备份恢复到其他设备后，旧密文会被视为不可读并清除，避免把密文
 * 误当成真实 token 或密码发送到网络。
 */
internal class AndroidKeystoreSecretCipher(
    private val keyAlias: String,
    private val formatVersion: String = DEFAULT_FORMAT_VERSION
) : SecretCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, BASE64_FLAGS)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
            BASE64_FLAGS
        )
        return "$formatVersion.$iv.$encrypted"
    }

    override fun decrypt(value: String): String {
        val parts = value.split('.', limit = 3)
        require(parts.size == 3 && parts[0] == formatVersion) { "不支持的安全凭据格式" }
        val iv = Base64.decode(parts[1], BASE64_FLAGS)
        val encrypted = Base64.decode(parts[2], BASE64_FLAGS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    override fun isEncrypted(value: String): Boolean =
        value.startsWith("$formatVersion.") && value.count { it == '.' } == 2

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_FORMAT_VERSION = "v1"
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE
    }
}
