package com.nekobot.app.data.local

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalWebDavArchiveCodecTest {

    @Test
    fun encryptedArchiveRoundTripsAndUsesCompatibleEnvelope() {
        val archive = ByteArray(1024) { index -> (index % 251).toByte() }

        val encrypted = LocalWebDavArchiveCodec.encrypt(
            archive = archive,
            password = "可爱但足够长的密码",
            profileName = "nekobot_local",
            timestampSeconds = 1_700_000_000L
        )
        val envelope = JsonParser.parseString(String(encrypted)).asJsonObject

        assertEquals("nbot_config_bundle", envelope.get("type").asString)
        assertEquals("fernet", envelope.get("algorithm").asString)
        assertEquals("pbkdf2_hmac_sha256", envelope.get("kdf").asString)
        assertEquals(390_000, envelope.get("iterations").asInt)
        assertEquals(
            "nekobot_android_room_v1",
            envelope.get("source_format").asString
        )
        assertArrayEquals(
            archive,
            LocalWebDavArchiveCodec.decrypt(encrypted, "可爱但足够长的密码")
        )
    }

    @Test
    fun wrongPasswordIsRejected() {
        val encrypted = LocalWebDavArchiveCodec.encrypt(
            archive = "database".toByteArray(),
            password = "right-password",
            profileName = "nekobot_local"
        )

        assertThrows(IllegalArgumentException::class.java) {
            LocalWebDavArchiveCodec.decrypt(encrypted, "wrong-password")
        }
    }
}
