package com.nekobot.app.data.local.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `token_usage_<db>.xml` 的体积守卫。
 *
 * 历史版本允许把约 6MB 的 JSON 写进这份 SharedPreferences，系统解析 XML 时
 * 会为单个值再申请一份等长缓冲，真机上直接死在 `loadFromDisk` 里。
 * 守卫必须在该文件的第一次 `getSharedPreferences` 之前把超大文件删掉。
 */
class TokenUsagePrefsGuardTest {

    private fun tempDir(): File = Files.createTempDirectory("token-usage-guard").toFile()

    private fun prefsXml(dir: File, name: String, bytes: Int): File =
        File(dir, "$name.xml").apply { writeBytes(ByteArray(bytes) { 'a'.code.toByte() }) }

    @Test
    fun `oversized prefs file is discarded`() {
        val dir = tempDir()
        val xml = prefsXml(dir, "token_usage_demo.db", 64)
        assertTrue(
            discardOversizedTokenUsagePrefs(dir, "token_usage_demo.db", maxBytes = 32)
        )
        assertFalse(xml.exists())
    }

    @Test
    fun `normal sized prefs file is kept`() {
        val dir = tempDir()
        val xml = prefsXml(dir, "token_usage_demo.db", 16)
        assertFalse(
            discardOversizedTokenUsagePrefs(dir, "token_usage_demo.db", maxBytes = 32)
        )
        assertTrue(xml.exists())
    }

    @Test
    fun `backup file is discarded too`() {
        val dir = tempDir()
        val backup = File(dir, "token_usage_demo.db.xml.bak").apply {
            writeBytes(ByteArray(64))
        }
        assertTrue(
            discardOversizedTokenUsagePrefs(dir, "token_usage_demo.db", maxBytes = 32)
        )
        assertFalse(backup.exists())
    }

    @Test
    fun `missing directory is not an error`() {
        val dir = File(tempDir(), "not-created")
        assertFalse(
            discardOversizedTokenUsagePrefs(dir, "token_usage_demo.db", maxBytes = 32)
        )
    }
}
