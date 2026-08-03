package com.nekobot.app.data.local.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureValueMigrationTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plainText: String): String = "v1.$plainText"

        override fun decrypt(value: String): String {
            require(isEncrypted(value))
            if (value == "v1.corrupt") error("密文损坏")
            return value.removePrefix("v1.")
        }

        override fun isEncrypted(value: String): Boolean = value.startsWith("v1.")
    }

    @Test
    fun legacyPlaintextIsReturnedAndScheduledForMigration() {
        val result = SecureValueMigration.resolve(
            encryptedValue = null,
            legacyValue = "secret-token",
            cipher = cipher
        )

        assertEquals("secret-token", result.value)
        assertEquals("v1.secret-token", result.encryptedValueToPersist)
        assertTrue(result.removeLegacyValue)
        assertFalse(result.clearUnreadableValue)
    }

    @Test
    fun validEncryptedValueIsReturnedWithoutRewrite() {
        val result = SecureValueMigration.resolve(
            encryptedValue = "v1.secret-token",
            legacyValue = "stale-legacy-token",
            cipher = cipher
        )

        assertEquals("secret-token", result.value)
        assertNull(result.encryptedValueToPersist)
        assertFalse(result.removeLegacyValue)
        assertFalse(result.clearUnreadableValue)
    }

    @Test
    fun unreadableEncryptedValueIsClearedInsteadOfBeingUsedAsCredential() {
        val result = SecureValueMigration.resolve(
            encryptedValue = "v1.corrupt",
            legacyValue = null,
            cipher = cipher
        )

        assertNull(result.value)
        assertNull(result.encryptedValueToPersist)
        assertFalse(result.removeLegacyValue)
        assertTrue(result.clearUnreadableValue)
    }

    @Test
    fun plaintextInSecureFileIsRejected() {
        val result = SecureValueMigration.resolve(
            encryptedValue = "unexpected-plaintext",
            legacyValue = null,
            cipher = cipher
        )

        assertNull(result.value)
        assertTrue(result.clearUnreadableValue)
    }
}
