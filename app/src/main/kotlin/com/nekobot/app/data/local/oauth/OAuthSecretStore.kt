package com.nekobot.app.data.local.oauth

import com.nekobot.app.data.local.security.AndroidKeystoreSecretCipher

/**
 * OAuth token 的 Android Keystore 封装。
 *
 * 数据库只保存 `v1.<iv>.<ciphertext>`，AES 密钥不可导出且仅存在于当前设备。
 */
class OAuthSecretStore {
    // 保留原 alias 与 v1 密文格式，升级后无需重新登录 OAuth 账号。
    private val cipher = AndroidKeystoreSecretCipher("nekobot_local_oauth_v1")

    fun encrypt(plainText: String): String = cipher.encrypt(plainText)

    fun decrypt(value: String): String = cipher.decrypt(value)
}
