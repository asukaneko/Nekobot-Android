package com.nekobot.app.data.local.security

import android.content.Context
import android.content.SharedPreferences

/** 安全值读取后的迁移动作；与 Android 存储解耦，供单元测试覆盖。 */
internal data class SecureValueResolution(
    val value: String?,
    val encryptedValueToPersist: String? = null,
    val removeLegacyValue: Boolean = false,
    val clearUnreadableValue: Boolean = false
)

internal object SecureValueMigration {
    fun resolve(
        encryptedValue: String?,
        legacyValue: String?,
        cipher: SecretCipher
    ): SecureValueResolution {
        if (encryptedValue != null) {
            if (!cipher.isEncrypted(encryptedValue)) {
                return SecureValueResolution(
                    value = null,
                    clearUnreadableValue = true
                )
            }
            return runCatching { cipher.decrypt(encryptedValue) }
                .fold(
                    onSuccess = { SecureValueResolution(value = it) },
                    onFailure = {
                        SecureValueResolution(
                            value = null,
                            clearUnreadableValue = true
                        )
                    }
                )
        }
        if (legacyValue == null) return SecureValueResolution(value = null)
        return SecureValueResolution(
            value = legacyValue,
            encryptedValueToPersist = cipher.encrypt(legacyValue),
            removeLegacyValue = true
        )
    }
}

/**
 * 不参与系统备份的 Keystore 加密 SharedPreferences。
 *
 * [getString] 会把旧偏好文件里的明文值原子迁移到安全文件，并删除旧值。
 */
internal class SecurePreferenceStore(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreSecretCipher(KEY_ALIAS)
) {
    private val securePrefs = context.applicationContext.getSharedPreferences(
        PREF_NAME,
        Context.MODE_PRIVATE
    )

    fun getString(
        key: String,
        legacyPrefs: SharedPreferences? = null,
        legacyKey: String = key
    ): String? {
        val resolution = SecureValueMigration.resolve(
            encryptedValue = securePrefs.getString(key, null),
            legacyValue = legacyPrefs?.getString(legacyKey, null),
            cipher = cipher
        )
        if (
            resolution.encryptedValueToPersist != null ||
            resolution.clearUnreadableValue
        ) {
            securePrefs.edit().apply {
                resolution.encryptedValueToPersist?.let { putString(key, it) }
                if (resolution.clearUnreadableValue) remove(key)
            }.commit()
        }
        if (resolution.removeLegacyValue) {
            legacyPrefs?.edit()?.remove(legacyKey)?.commit()
        }
        return resolution.value
    }

    fun putString(key: String, value: String?) {
        securePrefs.edit().apply {
            if (value == null) remove(key) else putString(key, cipher.encrypt(value))
        }.commit()
    }

    fun remove(key: String, legacyPrefs: SharedPreferences? = null, legacyKey: String = key) {
        securePrefs.edit().remove(key).commit()
        legacyPrefs?.edit()?.remove(legacyKey)?.commit()
    }

    companion object {
        const val PREF_NAME = "nekobot_secure_prefs"
        private const val KEY_ALIAS = "nekobot_app_secrets_v1"
    }
}
