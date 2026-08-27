package com.nekobot.app.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal enum class LocalJmRankingPeriod(
    val displayName: String,
    val order: String
) {
    WEEK("周排行", "mv_w"),
    MONTH("月排行", "mv_m")
}

internal data class LocalJmRankingEntry(
    val id: String,
    val title: String
)

internal data class LocalJmSearchEntry(
    val id: String,
    val title: String,
    val author: String = ""
)

internal data class LocalJmChapter(
    val id: String,
    val title: String,
    val order: Int
)

internal data class LocalJmAlbum(
    val id: String,
    val title: String,
    val chapters: List<LocalJmChapter>,
    internal val apiSession: LocalJmApiSession
)

internal data class LocalJmPhoto(
    val id: String,
    val title: String,
    val imageFiles: List<String>
)

internal data class LocalJmCoverCandidate(
    val path: String,
    val filename: String,
    val isScrambled: Boolean
)

internal data class LocalJmPdfImage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
)

internal data class LocalJmDownloadRequest(
    val albumId: String,
    val force: Boolean
)

internal data class LocalJmApiSession(
    val domain: String,
    val appVersion: String,
    val cookieHeader: String
)

/**
 * JM 移动端协议的轻量 Android 客户端。
 *
 * 移植 jmcomic 的移动端域名发现、请求签名、响应解密、图片下载与切片修复流程。
 * 上游协议基线：jmcomic 2.7.2。
 */
internal class LocalJmRankingClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    @Volatile
    private var lastApiDomain: String? = null

    fun fetchRanking(
        period: LocalJmRankingPeriod,
        limit: Int = MAX_RANKING_SIZE
    ): List<LocalJmRankingEntry> {
        val domains = (discoverApiDomains() + FALLBACK_API_DOMAINS)
            .map(::normalizeDomain)
            .filter { it.isNotBlank() }
            .distinct()

        for (domain in domains) {
            val result = runCatching {
                fetchRankingFromDomain(domain, period, limit)
            }.getOrNull()
            if (!result.isNullOrEmpty()) {
                lastApiDomain = domain
                return result
            }
        }

        throw IllegalStateException(
            "JM 排行接口暂时不可用，请稍后重试；如果持续失败，可能是接口签名规则已更新。"
        )
    }

    /**
     * 搜索 JM 站内漫画。协议与 jmcomic 的 search_site 保持一致：
     * `main_tag=0`、按最新排序，并最多合并前五页结果。
     */
    fun searchSite(
        query: String,
        maxPages: Int = MAX_SEARCH_PAGES,
        limit: Int = MAX_SEARCH_SIZE
    ): List<LocalJmSearchEntry> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotBlank()) { "搜索内容不能为空" }
        val pages = maxPages.coerceIn(1, MAX_SEARCH_PAGES)
        val resultLimit = limit.coerceIn(1, MAX_SEARCH_SIZE)
        var lastError: Exception? = null

        for (domain in availableApiDomains()) {
            try {
                val entries = searchSiteFromDomain(domain, normalizedQuery, pages, resultLimit)
                lastApiDomain = domain
                return entries
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw IllegalStateException(
            "JM 搜索接口暂时不可用，请稍后重试；如果持续失败，可能是接口签名规则已更新。",
            lastError
        )
    }

    fun fetchAlbum(albumId: String): LocalJmAlbum {
        require(albumId.matches(Regex("\\d+"))) { "JM 漫画 ID 无效" }
        val domains = availableApiDomains()
        var lastError: Exception? = null
        for (domain in domains) {
            try {
                val session = createApiSession(domain)
                val payload = requestEncryptedApi(
                    session = session,
                    path = "album",
                    query = mapOf("id" to albumId)
                )
                val album = parseJmAlbumPayload(payload, session)
                lastApiDomain = domain
                return album
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "无法获取 JM$albumId：${lastError?.message ?: "JM 接口暂时不可用"}",
            lastError
        )
    }

    fun fetchPhoto(album: LocalJmAlbum, chapter: LocalJmChapter): LocalJmPhoto {
        var lastError: Exception? = null
        repeat(2) {
            try {
                val payload = requestEncryptedApi(
                    session = album.apiSession,
                    path = "chapter",
                    query = mapOf("id" to chapter.id)
                )
                return parseJmPhotoPayload(payload, chapter)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "无法获取章节“${chapter.title}”：${lastError?.message ?: "请求失败"}",
            lastError
        )
    }

    fun fetchScrambleId(album: LocalJmAlbum, photoId: String): Long {
        val timestamp = currentTimestamp()
        val url = "https://${album.apiSession.domain}/chapter_view_template"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("id", photoId)
            .addQueryParameter("mode", "vertical")
            .addQueryParameter("page", "0")
            .addQueryParameter("app_img_shunt", "1")
            .addQueryParameter("express", "off")
            .addQueryParameter("v", album.apiSession.appVersion)
            .build()
        val headers = signedHeaders(
            timestamp = timestamp,
            appVersion = album.apiSession.appVersion,
            secret = APP_CONTENT_SECRET
        ).newBuilder().apply {
            if (album.apiSession.cookieHeader.isNotBlank()) {
                add("Cookie", album.apiSession.cookieHeader)
            }
        }.build()
        val body = executeText(
            Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build()
        )
        return SCRAMBLE_ID_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: DEFAULT_SCRAMBLE_ID
    }

    fun fetchPageJpeg(
        album: LocalJmAlbum,
        photoId: String,
        imageFile: String,
        scrambleId: Long
    ): LocalJmPdfImage {
        require(photoId.matches(Regex("\\d+"))) { "章节 ID 无效" }
        val filename = imageFile.substringAfterLast('/').substringBefore('?')
        require(filename.isNotBlank()) { "图片文件名无效" }
        val referer = "https://${album.apiSession.domain}"
        var lastError: Exception? = null
        for (imageDomain in IMAGE_DOMAINS) {
            try {
                val bytes = fetchImageBytes(
                    imageDomain,
                    "media/photos/$photoId/$filename",
                    referer
                )
                return decodeAndCompressJmImage(
                    albumId = photoId.toLong(),
                    filename = filename.substringBeforeLast('.'),
                    bytes = bytes,
                    scrambleId = scrambleId,
                    maxWidth = MAX_PDF_IMAGE_WIDTH,
                    maxHeight = MAX_PDF_IMAGE_HEIGHT,
                    quality = PDF_JPEG_QUALITY
                )
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "图片 $photoId/$filename 下载失败：${lastError?.message ?: "所有图片节点均不可用"}",
            lastError
        )
    }

    /**
     * 下载、修复 JM 封面的纵向切片顺序并压缩为适合嵌入 HTML 的 JPEG Data URL。
     */
    fun fetchCoverDataUrl(albumId: String): String? {
        if (albumId.toLongOrNull() == null) return null
        val referer = "https://${lastApiDomain ?: FALLBACK_API_DOMAINS.first()}"
        for (candidate in buildJmCoverCandidates(albumId)) {
            for (imageDomain in IMAGE_DOMAINS) {
                val bytes = runCatching {
                    fetchImageBytes(imageDomain, candidate.path, referer)
                }.getOrNull() ?: continue

                val dataUrl = runCatching {
                    decodeAndCompressCover(
                        albumId = albumId,
                        filename = candidate.filename,
                        bytes = bytes,
                        scrambleId = if (candidate.isScrambled) 0L else null
                    )
                }.getOrNull()
                if (dataUrl != null) return dataUrl
            }
        }
        return null
    }

    private fun availableApiDomains(): List<String> =
        (discoverApiDomains() + listOfNotNull(lastApiDomain) + FALLBACK_API_DOMAINS)
            .map(::normalizeDomain)
            .filter { it.isNotBlank() }
            .distinct()

    private fun discoverApiDomains(): List<String> {
        for (url in DOMAIN_DISCOVERY_URLS) {
            val result = runCatching {
                executeText(Request.Builder().url(url).get().build()).let { encrypted ->
                    val decoded = decodeJmPayload(
                        encoded = encrypted.trimStart('\uFEFF'),
                        timestamp = "",
                        secret = DOMAIN_SERVER_SECRET
                    )
                    JsonParser.parseString(decoded)
                        .asJsonObject
                        .getAsJsonArray("Server")
                        ?.mapNotNull { element ->
                            element.takeIf { it.isJsonPrimitive }?.asString
                        }
                        .orEmpty()
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun fetchRankingFromDomain(
        domain: String,
        period: LocalJmRankingPeriod,
        limit: Int
    ): List<LocalJmRankingEntry> {
        val session = createApiSession(domain)
        val rankingPayload = requestEncryptedApi(
            session = session,
            path = "categories/filter",
            query = mapOf(
                "page" to "1",
                "order" to "",
                "c" to "0",
                "o" to period.order
            )
        )
        return parseJmRankingPayload(rankingPayload, limit)
    }

    private fun searchSiteFromDomain(
        domain: String,
        query: String,
        maxPages: Int,
        limit: Int
    ): List<LocalJmSearchEntry> {
        val session = createApiSession(domain)
        val entries = mutableListOf<LocalJmSearchEntry>()
        val seenIds = mutableSetOf<String>()
        for (page in 1..maxPages) {
            val payload = requestEncryptedApi(
                session = session,
                path = "search",
                query = mapOf(
                    "main_tag" to "0",
                    "search_query" to query,
                    "page" to page.toString(),
                    "o" to "mr",
                    "t" to "a"
                )
            )
            val pageEntries = parseJmSearchPayload(payload, limit - entries.size)
            if (pageEntries.isEmpty()) break
            pageEntries.forEach { entry ->
                if (seenIds.add(entry.id)) entries += entry
            }
            if (entries.size >= limit) break
        }
        return entries.take(limit)
    }

    private fun createApiSession(domain: String): LocalJmApiSession {
        val timestamp = currentTimestamp()
        val initialHeaders = signedHeaders(timestamp, INITIAL_APP_VERSION, APP_SECRET)
        val settingResponse = execute(
            Request.Builder()
                .url("https://$domain/setting")
                .headers(initialHeaders)
                .get()
                .build()
        )

        val settingPayload = decodeApiResponse(settingResponse.body, timestamp)
        val appVersion = settingPayload
            .get("jm3_version")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: INITIAL_APP_VERSION
        val cookieHeader = settingResponse.cookies.joinToString("; ")
        return LocalJmApiSession(domain, appVersion, cookieHeader)
    }

    private fun requestEncryptedApi(
        session: LocalJmApiSession,
        path: String,
        query: Map<String, String>
    ): JsonObject {
        val timestamp = currentTimestamp()
        val requestUrl = "https://${session.domain}/$path"
            .toHttpUrl()
            .newBuilder().apply {
                query.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
        val headers = signedHeaders(timestamp, session.appVersion, APP_SECRET).newBuilder().apply {
            if (session.cookieHeader.isNotBlank()) add("Cookie", session.cookieHeader)
        }.build()
        val response = execute(
            Request.Builder()
                .url(requestUrl)
                .headers(headers)
                .get()
                .build()
        )
        return decodeApiResponse(response.body, timestamp)
    }

    private fun signedHeaders(
        timestamp: String,
        appVersion: String,
        secret: String
    ): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("User-Agent", APP_USER_AGENT)
            .add("token", md5Hex(timestamp + secret))
            .add("tokenparam", "$timestamp,$appVersion")
            .build()

    private fun fetchImageBytes(
        imageDomain: String,
        path: String,
        referer: String
    ): ByteArray {
        val imageUrl = "https://$imageDomain"
            .toHttpUrl()
            .newBuilder()
            .apply {
                path.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) }
            }
            .build()
        return httpClient.newCall(
            Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", APP_USER_AGENT)
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .addHeader("X-Requested-With", "com.JMComic3.app")
                .addHeader("Referer", referer)
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: error("图片内容为空")
        }
    }

    private fun execute(request: Request): JmHttpResponse =
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val cookies = response.headers("Set-Cookie")
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
            JmHttpResponse(body, cookies)
        }

    private fun executeText(request: Request): String = execute(request).body

    private fun decodeApiResponse(body: String, timestamp: String): JsonObject {
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
        if (code != 200) throw IllegalStateException("JM API code=$code")
        val encoded = root.get("data")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?: throw IllegalStateException("JM API 缺少 data")
        val decoded = decodeJmPayload(encoded, timestamp, APP_SECRET)
        return JsonParser.parseString(decoded).asJsonObject
    }

    private data class JmHttpResponse(
        val body: String,
        val cookies: List<String>
    )

    companion object {
        private const val MAX_RANKING_SIZE = 50
        private const val MAX_SEARCH_PAGES = 5
        private const val MAX_SEARCH_SIZE = 50
        private const val INITIAL_APP_VERSION = "2.0.28"
        private const val APP_SECRET = "185Hcomic3PAPP7R"
        private const val APP_CONTENT_SECRET = "18comicAPPContent"
        private const val DOMAIN_SERVER_SECRET = "diosfjckwpqpdfjkvnqQjsik"
        private const val DEFAULT_SCRAMBLE_ID = 220_980L
        private const val MAX_PDF_IMAGE_WIDTH = 1_600
        private const val MAX_PDF_IMAGE_HEIGHT = 2_800
        private const val PDF_JPEG_QUALITY = 85
        private const val APP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/91.0.4472.114 Safari/537.36"

        private val DOMAIN_DISCOVERY_URLS = listOf(
            "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt"
        )
        private val FALLBACK_API_DOMAINS = listOf(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club"
        )
        private val IMAGE_DOMAINS = listOf(
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net"
        )
        private val SCRAMBLE_ID_REGEX =
            Regex("""var\s+scramble_id\s*=\s*(\d+)\s*;""")

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        private fun currentTimestamp(): String =
            (System.currentTimeMillis() / 1_000L).toString()
    }
}

internal fun parseLocalJmRankingPeriod(raw: String): LocalJmRankingPeriod =
    when (raw.trim().lowercase()) {
        "", "周", "周排行", "week", "w" -> LocalJmRankingPeriod.WEEK
        "月", "月排行", "month", "m" -> LocalJmRankingPeriod.MONTH
        else -> throw IllegalArgumentException("格式：`/jmrank [周排行|月排行]`")
    }

internal fun parseLocalJmDownloadRequest(raw: String): LocalJmDownloadRequest {
    val parts = raw.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    val force = parts.any { it.equals("--force", ignoreCase = true) }
    val values = parts.filterNot { it.equals("--force", ignoreCase = true) }
    if (values.size != 1) {
        throw IllegalArgumentException("格式：`/jm <漫画ID> [--force]`")
    }
    val albumId = values.single()
        .removePrefix("JM")
        .removePrefix("jm")
    if (!albumId.matches(Regex("\\d+"))) {
        throw IllegalArgumentException("漫画 ID 应为纯数字，例如：`/jm 123456`")
    }
    return LocalJmDownloadRequest(albumId, force)
}

internal fun parseJmRankingPayload(
    payload: JsonObject,
    limit: Int = 50
): List<LocalJmRankingEntry> =
    payload.getAsJsonArray("content")
        ?.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.get("id")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()
            val title = item.get("name")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            if (id.isBlank() || title.isBlank()) null else LocalJmRankingEntry(id, title)
        }
        ?.take(limit.coerceIn(1, 50))
        .orEmpty()

internal fun parseJmSearchPayload(
    payload: JsonObject,
    limit: Int = 50
): List<LocalJmSearchEntry> =
    payload.getAsJsonArray("content")
        ?.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.stringValue("id")?.takeIf { it.matches(Regex("\\d+")) }.orEmpty()
            val title = item.stringValue("name")?.normalizedJmText().orEmpty()
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            LocalJmSearchEntry(
                id = id,
                title = title,
                author = item.jmAuthorValue()
            )
        }
        ?.take(limit.coerceIn(1, 50))
        .orEmpty()

internal fun buildJmCoverCandidates(albumId: String): List<LocalJmCoverCandidate> {
    if (albumId.toLongOrNull() == null) return emptyList()
    return listOf(
        LocalJmCoverCandidate(
            path = "media/albums/$albumId.jpg",
            filename = albumId,
            isScrambled = false
        ),
        LocalJmCoverCandidate(
            path = "media/albums/$albumId.webp",
            filename = albumId,
            isScrambled = false
        ),
        LocalJmCoverCandidate(
            path = "media/photos/$albumId/00001.webp",
            filename = "00001",
            isScrambled = true
        ),
        LocalJmCoverCandidate(
            path = "media/photos/$albumId/00001.jpg",
            filename = "00001",
            isScrambled = true
        )
    )
}

internal fun parseJmAlbumPayload(
    payload: JsonObject,
    apiSession: LocalJmApiSession
): LocalJmAlbum {
    val id = payload.stringValue("id")
        ?: throw IllegalArgumentException("JM 专辑数据缺少 ID")
    val title = payload.stringValue("name")
        ?.normalizedJmText()
        ?.takeIf { it.isNotBlank() }
        ?: "JM$id"
    val chapters = payload.getAsJsonArray("series")
        ?.mapIndexedNotNull { index, element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapIndexedNotNull null
            val chapterId = item.stringValue("id")
                ?.takeIf { it.matches(Regex("\\d+")) }
                ?: return@mapIndexedNotNull null
            val chapterTitle = item.stringValue("name")
                ?.normalizedJmText()
                ?.takeIf { it.isNotBlank() }
                ?: "第 ${index + 1} 章"
            val order = item.stringValue("sort")?.toIntOrNull() ?: index + 1
            LocalJmChapter(chapterId, chapterTitle, order)
        }
        ?.sortedWith(compareBy<LocalJmChapter> { it.order }.thenBy { it.id.toLongOrNull() })
        .orEmpty()
        .ifEmpty { listOf(LocalJmChapter(id, title, 1)) }
    return LocalJmAlbum(id, title, chapters, apiSession)
}

internal fun parseJmPhotoPayload(
    payload: JsonObject,
    chapter: LocalJmChapter
): LocalJmPhoto {
    val id = payload.stringValue("id") ?: chapter.id
    val title = payload.stringValue("name")
        ?.normalizedJmText()
        ?.takeIf { it.isNotBlank() }
        ?: chapter.title
    val images = payload.getAsJsonArray("images")
        ?.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        .orEmpty()
    if (images.isEmpty()) {
        throw IllegalArgumentException("章节“$title”没有可下载的图片")
    }
    return LocalJmPhoto(id, title, images)
}

internal fun decodeJmPayload(
    encoded: String,
    timestamp: String,
    secret: String
): String {
    val encrypted = Base64.getDecoder().decode(encoded.trim())
    val key = md5Hex(timestamp + secret).toByteArray(Charsets.UTF_8)
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
}

private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun normalizeDomain(value: String): String =
    value.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

internal fun calculateJmScrambleSegments(
    albumId: Long,
    filename: String = "00001",
    scrambleId: Long = 0L
): Int {
    if (albumId < scrambleId) return 0
    if (albumId < 268_850L) return 10
    val modulus = if (albumId < 421_926L) 10 else 8
    val hash = md5Hex("$albumId$filename")
    return (hash.last().code % modulus) * 2 + 2
}

private fun decodeAndCompressCover(
    albumId: String,
    filename: String,
    bytes: ByteArray,
    scrambleId: Long?
): String {
    val image = decodeAndCompressJmImage(
        albumId = albumId.toLong(),
        filename = filename,
        bytes = bytes,
        scrambleId = scrambleId,
        maxWidth = 600,
        maxHeight = 840,
        quality = 84
    )
    return "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image.jpegBytes)}"
}

private fun decodeAndCompressJmImage(
    albumId: Long,
    filename: String,
    bytes: ByteArray,
    scrambleId: Long?,
    maxWidth: Int,
    maxHeight: Int,
    quality: Int
): LocalJmPdfImage {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("无法解析图片")
    val segmentCount = scrambleId?.let {
        calculateJmScrambleSegments(
            albumId = albumId,
            filename = filename,
            scrambleId = it
        )
    } ?: 0
    val decoded = unscrambleJmBitmap(source, segmentCount)
    if (decoded !== source) source.recycle()

    val scale = minOf(
        maxWidth.toFloat() / decoded.width.toFloat(),
        maxHeight.toFloat() / decoded.height.toFloat(),
        1f
    )
    val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
    val thumbnail = if (targetWidth != decoded.width || targetHeight != decoded.height) {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    } else {
        decoded
    }
    if (thumbnail !== decoded) decoded.recycle()

    val output = ByteArrayOutputStream()
    try {
        if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            throw IllegalStateException("图片压缩失败")
        }
        return LocalJmPdfImage(
            jpegBytes = output.toByteArray(),
            width = targetWidth,
            height = targetHeight
        )
    } finally {
        thumbnail.recycle()
        output.close()
    }
}

private fun unscrambleJmBitmap(source: Bitmap, segmentCount: Int): Bitmap {
    if (segmentCount <= 0) return source
    val width = source.width
    val height = source.height
    val decoded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(decoded)
    val baseHeight = height / segmentCount
    val remainder = height % segmentCount

    for (index in 0 until segmentCount) {
        var sliceHeight = baseHeight
        val sourceY = height - (baseHeight * (index + 1)) - remainder
        var targetY = baseHeight * index
        if (index == 0) {
            sliceHeight += remainder
        } else {
            targetY += remainder
        }
        if (sliceHeight <= 0) continue
        canvas.drawBitmap(
            source,
            Rect(0, sourceY, width, sourceY + sliceHeight),
            Rect(0, targetY, width, targetY + sliceHeight),
            null
        )
    }
    return decoded
}

internal fun buildLocalJmRankingHtml(
    period: LocalJmRankingPeriod,
    entries: List<LocalJmRankingEntry>,
    covers: Map<String, String?>
): String {
    val coverCount = entries.count { !covers[it.id].isNullOrBlank() }
    val cards = entries.mapIndexed { index, entry ->
        val safeId = escapeHtml(entry.id)
        val safeTitle = escapeHtml(entry.title)
        val cover = covers[entry.id]
        val coverHtml = if (cover.isNullOrBlank()) {
            """<div class="cover placeholder"><span>暂无封面</span></div>"""
        } else {
            """<img class="cover" src="$cover" alt="$safeTitle" loading="lazy">"""
        }
        """
        <article class="card">
          <a href="https://18comic.vip/album/$safeId">
            $coverHtml
            <div class="meta">
              <div class="rank">#${index + 1}</div>
              <div class="title">$safeTitle</div>
              <div class="id">JM$safeId</div>
            </div>
          </a>
        </article>
        """.trimIndent()
    }.joinToString("\n")

    return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>JM ${escapeHtml(period.displayName)}</title>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 22px;
              color: #f3f4f6;
              background: #111827;
              font-family: system-ui, -apple-system, "Segoe UI", "PingFang SC", sans-serif;
            }
            h1 { margin: 0 0 8px; font-size: 24px; }
            .note { margin: 0 0 20px; color: #9ca3af; font-size: 13px; }
            .grid {
              display: grid;
              grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
              gap: 16px;
            }
            .card {
              overflow: hidden;
              border: 1px solid rgba(255,255,255,.08);
              border-radius: 15px;
              background: #1f2937;
              box-shadow: 0 9px 22px rgba(0,0,0,.24);
            }
            .card a { display: block; color: inherit; text-decoration: none; }
            .cover {
              display: block;
              width: 100%;
              aspect-ratio: 13 / 18;
              object-fit: cover;
              background: #0b1220;
            }
            .placeholder {
              display: grid;
              place-items: center;
              color: #6b7280;
              font-size: 13px;
            }
            .meta { padding: 11px 13px 14px; }
            .rank { margin-bottom: 5px; color: #60a5fa; font-weight: 750; font-size: 13px; }
            .title { min-height: 40px; font-size: 14px; line-height: 1.45; word-break: break-word; }
            .id { margin-top: 7px; color: #9ca3af; font-size: 12px; }
          </style>
        </head>
        <body>
          <h1>JM ${escapeHtml(period.displayName)}</h1>
          <p class="note">共 ${entries.size} 条 · 已获取 $coverCount 张封面 · 点击卡片打开漫画详情</p>
          <main class="grid">
            $cards
          </main>
        </body>
        </html>
    """.trimIndent()
}

internal fun buildLocalJmSearchHtml(
    query: String,
    entries: List<LocalJmSearchEntry>,
    covers: Map<String, String?>
): String {
    val coverCount = entries.count { !covers[it.id].isNullOrBlank() }
    val cards = entries.mapIndexed { index, entry ->
        val safeId = escapeHtml(entry.id)
        val safeTitle = escapeHtml(entry.title)
        val safeAuthor = escapeHtml(entry.author)
        val cover = covers[entry.id]
        val coverHtml = if (cover.isNullOrBlank()) {
            """<div class="cover placeholder"><span>暂无封面</span></div>"""
        } else {
            """<img class="cover" src="$cover" alt="$safeTitle" loading="lazy">"""
        }
        val authorHtml = if (safeAuthor.isBlank()) "" else """<div class="author">$safeAuthor</div>"""
        """
        <article class="card">
          <a href="https://18comic.vip/album/$safeId">
            $coverHtml
            <div class="meta">
              <div class="rank">#${index + 1}</div>
              <div class="title">$safeTitle</div>
              $authorHtml
              <div class="id">JM$safeId</div>
            </div>
          </a>
        </article>
        """.trimIndent()
    }.joinToString("\n")

    return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>${escapeHtml(query)} - JM 搜索</title>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 22px;
              color: #f3f4f6;
              background: #111827;
              font-family: system-ui, -apple-system, "Segoe UI", "PingFang SC", sans-serif;
            }
            h1 { margin: 0 0 8px; font-size: 24px; }
            .note { margin: 0 0 20px; color: #9ca3af; font-size: 13px; }
            .grid {
              display: grid;
              grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
              gap: 16px;
            }
            .card {
              overflow: hidden;
              border: 1px solid rgba(255,255,255,.08);
              border-radius: 15px;
              background: #1f2937;
              box-shadow: 0 9px 22px rgba(0,0,0,.24);
            }
            .card a { display: block; color: inherit; text-decoration: none; }
            .cover {
              display: block;
              width: 100%;
              aspect-ratio: 13 / 18;
              object-fit: cover;
              background: #0b1220;
            }
            .placeholder {
              display: grid;
              place-items: center;
              color: #6b7280;
              font-size: 13px;
            }
            .meta { padding: 11px 13px 14px; }
            .rank { margin-bottom: 5px; color: #60a5fa; font-weight: 750; font-size: 13px; }
            .title { min-height: 40px; font-size: 14px; line-height: 1.45; word-break: break-word; }
            .author { margin-top: 6px; color: #93c5fd; font-size: 12px; word-break: break-word; }
            .id { margin-top: 7px; color: #9ca3af; font-size: 12px; }
          </style>
        </head>
        <body>
          <h1>JM 搜索</h1>
          <p class="note">${escapeHtml(query)} · 共 ${entries.size} 条 · 已获取 $coverCount 张封面 · 点击卡片打开漫画详情</p>
          <main class="grid">
            $cards
          </main>
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String =
    value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun JsonObject.stringValue(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()

private fun JsonObject.jmAuthorValue(): String =
    get("author")?.let { value ->
        when {
            value.isJsonPrimitive -> value.asString
            value.isJsonArray -> value.asJsonArray
                .mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString?.trim() }
                .filter(String::isNotBlank)
                .joinToString(" / ")
            else -> ""
        }
    }?.normalizedJmText().orEmpty()

private fun String.normalizedJmText(): String =
    replace(Regex("\\s+"), " ").trim()
