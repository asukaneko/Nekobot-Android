package com.nekobot.app.data.local

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class LocalJmRankingClientTest {
    private val apiSession = LocalJmApiSession(
        domain = "example.test",
        appVersion = "2.0.28",
        cookieHeader = "session=test"
    )

    @Test
    fun parsesRankingPeriodWithOriginalChineseArguments() {
        assertEquals(LocalJmRankingPeriod.WEEK, parseLocalJmRankingPeriod(""))
        assertEquals(LocalJmRankingPeriod.WEEK, parseLocalJmRankingPeriod("周排行"))
        assertEquals(LocalJmRankingPeriod.MONTH, parseLocalJmRankingPeriod("月排行"))
        assertEquals(LocalJmRankingPeriod.MONTH, parseLocalJmRankingPeriod("month"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedRankingPeriod() {
        parseLocalJmRankingPeriod("日排行")
    }

    @Test
    fun parsesDownloadRequestAndOptionalForceFlag() {
        assertEquals(
            LocalJmDownloadRequest("123456", false),
            parseLocalJmDownloadRequest("JM123456")
        )
        assertEquals(
            LocalJmDownloadRequest("654321", true),
            parseLocalJmDownloadRequest("--force 654321")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDownloadRequest() {
        parseLocalJmDownloadRequest("abc")
    }

    @Test
    fun parsesAndLimitsRankingPayload() {
        val payload = JsonParser.parseString(
            """
            {
              "content": [
                {"id": "100", "name": " 第一部  漫画 "},
                {"id": "200", "name": "第二部\n漫画"},
                {"id": "", "name": "无效数据"}
              ]
            }
            """.trimIndent()
        ).asJsonObject

        val entries = parseJmRankingPayload(payload, limit = 2)

        assertEquals(
            listOf(
                LocalJmRankingEntry("100", "第一部 漫画"),
                LocalJmRankingEntry("200", "第二部 漫画")
            ),
            entries
        )
    }

    @Test
    fun decodesUpstreamAesPayloadFormat() {
        val timestamp = "1720000000"
        val secret = "test-secret"
        val plaintext = """{"content":[{"id":"123","name":"测试"}]}"""
        val encrypted = encodePayload(plaintext, timestamp, secret)

        assertEquals(plaintext, decodeJmPayload(encrypted, timestamp, secret))
    }

    @Test
    fun parsesAlbumAndSortsChapters() {
        val payload = JsonParser.parseString(
            """
            {
              "id": "286368",
              "name": " 测试  漫画 ",
              "series": [
                {"id": "286370", "name": "第三话", "sort": "3"},
                {"id": "286368", "name": "第一话", "sort": "1"}
              ]
            }
            """.trimIndent()
        ).asJsonObject

        val album = parseJmAlbumPayload(payload, apiSession)

        assertEquals("286368", album.id)
        assertEquals("测试 漫画", album.title)
        assertEquals(listOf("286368", "286370"), album.chapters.map { it.id })
        assertEquals(apiSession, album.apiSession)
    }

    @Test
    fun fallsBackToAlbumAsSingleChapter() {
        val payload = JsonParser.parseString(
            """{"id":"100","name":"单话漫画","series":[]}"""
        ).asJsonObject

        val album = parseJmAlbumPayload(payload, apiSession)

        assertEquals(listOf(LocalJmChapter("100", "单话漫画", 1)), album.chapters)
    }

    @Test
    fun parsesChapterImages() {
        val payload = JsonParser.parseString(
            """
            {
              "id": "286368",
              "name": " 第一话 ",
              "images": ["00001.webp", "00002.webp"]
            }
            """.trimIndent()
        ).asJsonObject
        val chapter = LocalJmChapter("286368", "备用标题", 1)

        assertEquals(
            LocalJmPhoto(
                id = "286368",
                title = "第一话",
                imageFiles = listOf("00001.webp", "00002.webp")
            ),
            parseJmPhotoPayload(payload, chapter)
        )
    }

    @Test
    fun matchesUpstreamCoverScrambleSegments() {
        assertEquals(0, calculateJmScrambleSegments(202_784, scrambleId = 220_980))
        assertEquals(10, calculateJmScrambleSegments(202_784))
        assertEquals(18, calculateJmScrambleSegments(286_368))
        assertEquals(12, calculateJmScrambleSegments(287_234))
        assertEquals(14, calculateJmScrambleSegments(421_926))
        assertEquals(4, calculateJmScrambleSegments(1_143_285))
    }

    @Test
    fun buildsSelfContainedClickableHtmlAndEscapesTitles() {
        val entries = listOf(LocalJmRankingEntry("123", "A&B <测试>"))
        val html = buildLocalJmRankingHtml(
            period = LocalJmRankingPeriod.MONTH,
            entries = entries,
            covers = mapOf("123" to "data:image/jpeg;base64,abc")
        )

        assertTrue(html.contains("data:image/jpeg;base64,abc"))
        assertTrue(html.contains("href=\"https://18comic.vip/album/123\""))
        assertTrue(html.contains("A&amp;B &lt;测试&gt;"))
        assertTrue(html.contains("点击卡片打开漫画详情"))
        assertTrue(!html.contains("target=\"_blank\""))
    }

    private fun encodePayload(plaintext: String, timestamp: String, secret: String): String {
        val key = MessageDigest.getInstance("MD5")
            .digest((timestamp + secret).toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray()))
    }
}
