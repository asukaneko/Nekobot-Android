package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

class LocalWebDavArchiveCodecTest {

    private val gb18030: Charset = Charset.forName("GB18030")

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

    @Test
    fun plainZipPayloadIsCopiedWithoutTextDecoding() {
        // 0xBA 在 UTF-8 中非法；ZIP 载荷必须按字节原样处理。
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0xBA.toByte(), 0x01, 0x02, 0x03)
        val payload = tempFile("payload", zip)
        val output = File(payload.parentFile, "output.zip")
        try {
            LocalWebDavArchiveCodec.decryptFile(payload, "", output)
            assertArrayEquals(zip, output.readBytes())
        } finally {
            payload.delete()
            output.delete()
        }
    }

    @Test
    fun gzipPayloadIsDecompressedAsBinary() {
        val plain = ByteArray(32) { index -> (index * 7).toByte() }
        val compressed = java.io.ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(plain) }
        }.toByteArray()
        val payload = tempFile("payload", compressed)
        val output = File(payload.parentFile, "output.zip")
        try {
            LocalWebDavArchiveCodec.decryptFile(payload, "", output)
            assertArrayEquals(plain, output.readBytes())
        } finally {
            payload.delete()
            output.delete()
        }
    }

    @Test
    fun unknownBinaryPayloadIsRejectedWithReadableError() {
        val payload = tempFile("payload", byteArrayOf(0x00, 0xBA.toByte(), 0x01, 0x02, 0x03))
        val output = File(payload.parentFile, "output.zip")
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                LocalWebDavArchiveCodec.decryptFile(payload, "password", output)
            }
            assertTrue(error.message.orEmpty().contains("格式无法识别"))
        } finally {
            payload.delete()
            output.delete()
        }
    }

    @Test
    fun gb18030EncodedEnvelopeIsDecodedInsteadOfFailingUtf8() {
        val archive = ByteArray(128) { index -> (index * 3).toByte() }
        val envelope = JsonParser.parseString(
            String(
                LocalWebDavArchiveCodec.encrypt(archive, "password", "示例档案"),
                Charsets.UTF_8
            )
        ).asJsonObject
        // “汉字备份”在 GB18030 下包含 0xBA，是旧问题里最常见的非法 UTF-8 字节。
        envelope.addProperty("profile_name", "汉字备份")
        val encoded = Gson().toJson(envelope).toByteArray(gb18030)
        assertTrue(encoded.any { it == 0xBA.toByte() })

        assertArrayEquals(archive, LocalWebDavArchiveCodec.decrypt(encoded, "password"))
    }

    @Test
    fun gb18030EnvelopeFileIsDecryptedAsBinary() {
        val archive = ByteArray(64) { index -> (index * 5).toByte() }
        val envelope = JsonParser.parseString(
            String(
                LocalWebDavArchiveCodec.encrypt(archive, "password", "示例档案"),
                Charsets.UTF_8
            )
        ).asJsonObject
        envelope.addProperty("profile_name", "汉字备份")
        val payload = tempFile("payload", Gson().toJson(envelope).toByteArray(gb18030))
        val output = File(payload.parentFile, "archive.zip")
        try {
            LocalWebDavArchiveCodec.decryptFile(payload, "password", output)
            assertArrayEquals(archive, output.readBytes())
        } finally {
            payload.delete()
            output.delete()
        }
    }

    @Test
    fun utf8BomEnvelopeFileIsAccepted() {
        val archive = ByteArray(64) { index -> (index * 11).toByte() }
        val encrypted = LocalWebDavArchiveCodec.encrypt(
            archive = archive,
            password = "password",
            profileName = "nekobot_local"
        )
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encrypted
        val payload = tempFile("payload", withBom)
        val output = File(payload.parentFile, "archive.zip")
        try {
            LocalWebDavArchiveCodec.decryptFile(payload, "password", output)
            assertArrayEquals(archive, output.readBytes())
        } finally {
            payload.delete()
            output.delete()
        }
    }

    private fun tempFile(prefix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, ".bin").also { it.writeBytes(bytes) }
}

