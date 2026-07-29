package com.nekobot.app.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * wenku8.net 轻小说榜单类型。
 *
 * 与原仓库 `commands.py` 中 `/hotnovel` 的 `day`/`month` 参数对齐。
 */
internal enum class LocalNovelRankingPeriod(
    val displayName: String,
    val sort: String
) {
    DAY("今日热门", "dayvisit"),
    MONTH("本月热门", "monthvisit")
}

/**
 * 单本轻小说的完整信息。
 *
 * 字段对齐原仓库 `books` 字典中的结构，`/info` 与 `/random_novel` 共用。
 */
internal data class LocalNovelBook(
    val id: String,
    val title: String,
    val author: String,
    val category: String = "未知",
    val wordCount: String = "未知",
    val isSerialize: String = "未知",
    val lastDate: String = "未知",
    val introduction: String = "暂无简介",
    val tags: String = "无",
    val coverUrl: String = "",
    val downloadUrl: String = "",
    val pageUrl: String = "",
    val hot: String = "搜索结果书籍",
    /** 番茄小说 API 的 book_id；非空表示来自 API 而不是 wenku8。 */
    val apiBookId: String? = null
)

/**
 * 番茄小说 API 搜索结果（仅 book_id + 名称）。
 */
internal data class LocalNovelApiBook(
    val bookId: String,
    val title: String
)

/**
 * 一次搜索后的会话级状态：wenku8 网页结果 + 番茄 API 结果。
 *
 * 用于 `/select`、`/info` 通过编号引用上次搜索命中的书籍。
 * 与原仓库 `temp_selections[user_id]` + `api_book[user_id]` 等价。
 */
internal data class LocalNovelSearchState(
    val webMatches: List<LocalNovelBook> = emptyList(),
    val apiMatches: List<LocalNovelApiBook> = emptyList()
) {
    val totalSize: Int get() = webMatches.size + apiMatches.size
}

/**
 * wenku8.net + 番茄小说 API 的轻量 Android 客户端。
 *
 * 移植原仓库 `commands.py` 中 `/findbook`、`/hotnovel`、`/info`、
 * `/random_novel`、`/novel_res`、`/fa`、`/select` 所依赖的网络逻辑。
 *
 * 与原仓库的差异：
 * - 不再读写本地 `novel_details2.json` 缓存（APK 没有该资源）。
 * - Cookie 由 `PrefsManager.wenku8Cookie` 提供，不再读 `wenku8_cookie.txt`。
 * - 番茄 API 的 base URL 每次按候选列表探测，避免硬编码单点失效。
 */
internal class LocalNovelClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    /** Cookie 未设置时由命令层抛出，用于返回友好提示。 */
    class CookieMissingException(message: String) : IllegalStateException(message)

    /** wenku8 返回 403 或登录页时抛出，提示用户更新 Cookie。 */
    class CookieExpiredException(message: String) : IllegalStateException(message)

    @Volatile
    private var cachedApiBaseUrl: String? = null

    // ==================== wenku8 网页接口 ====================

    /**
     * 在 wenku8.net 搜索小说（按书名或作者）。
     *
     * @param searchTerm 关键词
     * @param searchType `articlename`（按书名）或 `author`（按作者）
     * @param cookie wenku8 登录 Cookie
     * @param userAgent 自定义 User-Agent；为空时使用 [NOVEL_USER_AGENT_VALUE]。
     *                  CloudFlare 的 cf_clearance 绑定获取时的 IP + UA，需保持一致。
     * @param maxPages 最多翻页数
     */
    fun searchBooks(
        searchTerm: String,
        searchType: String,
        cookie: String,
        userAgent: String = "",
        maxPages: Int = 3
    ): List<LocalNovelBook> {
        if (cookie.isBlank()) {
            throw CookieMissingException(WENKU8_COOKIE_MISSING_HINT)
        }
        val results = mutableListOf<LocalNovelBook>()
        val seenIds = mutableSetOf<String>()

        for (page in 1..maxPages) {
            val url = buildWenku8SearchUrl(searchTerm, searchType, page)
            val response = executeTextRequest(url, cookie, userAgent)
            if (response.siteClosed) {
                throw IllegalStateException("wenku8.net 网站已关闭，请稍后再试喵~")
            }
            if (response.is403) {
                throw CookieExpiredException(WENKU8_403_HINT)
            }
            val pageMatches = NOVEL_CARD_BLOCK_REGEX.findAll(response.body).map { it.groupValues[1] }.toList()
            if (pageMatches.isEmpty()) {
                // 匹配为空时才检查是否需要登录（搜索页即使已登录也可能含"登录"字样）
                if (page == 1 && response.requiresLogin) {
                    throw CookieExpiredException(WENKU8_LOGIN_HINT)
                }
                break
            }

            for (match in pageMatches) {
                val book = parseNovelCardBlock(match) ?: continue
                if (book.id in seenIds) continue
                seenIds.add(book.id)
                results.add(book)
            }
            if (pageMatches.size < 20) break
        }
        return results
    }

    /**
     * 获取今日/本月热门榜单。
     */
    fun fetchHotNovels(
        period: LocalNovelRankingPeriod,
        cookie: String,
        userAgent: String = "",
        limit: Int = 10
    ): List<LocalNovelBook> {
        if (cookie.isBlank()) {
            throw CookieMissingException(WENKU8_COOKIE_MISSING_HINT)
        }
        val requestedCount = limit.coerceIn(1, 100)
        val allMatches = mutableListOf<String>()
        var page = 1
        while (allMatches.size < requestedCount && page <= 5) {
            val url = "https://www.wenku8.net/modules/article/toplist.php"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("sort", period.sort)
                .addQueryParameter("page", page.toString())
                .build()
            val response = executeTextRequest(url, cookie, userAgent)
            if (response.is403) {
                throw CookieExpiredException(WENKU8_403_HINT)
            }
            if (response.siteClosed) {
                throw IllegalStateException("wenku8.net 网站已关闭，请稍后再试喵~")
            }
            val pageMatches = NOVEL_CARD_BLOCK_REGEX.findAll(response.body).map { it.groupValues[1] }.toList()
            if (pageMatches.isEmpty()) {
                if (page == 1 && response.requiresLogin) {
                    throw CookieExpiredException(WENKU8_LOGIN_HINT)
                }
                break
            }
            allMatches.addAll(pageMatches)
            if (pageMatches.size < 20) break
            page++
        }
        return allMatches
            .take(requestedCount)
            .mapNotNull { parseNovelCardBlock(it) }
    }

    /**
     * 从今日热门榜单中随机取一本。
     */
    fun fetchRandomFromHot(cookie: String, userAgent: String = ""): LocalNovelBook? {
        val entries = fetchHotNovels(LocalNovelRankingPeriod.DAY, cookie, userAgent, limit = 20)
        if (entries.isEmpty()) return null
        return entries.random()
    }

    /**
     * 通过书籍详情页 URL 获取详细信息。
     */
    fun fetchBookDetail(bookId: String, cookie: String, userAgent: String = ""): LocalNovelBook? {
        if (bookId.isBlank() || !bookId.matches(Regex("\\d+"))) return null
        if (cookie.isBlank()) {
            throw CookieMissingException(WENKU8_COOKIE_MISSING_HINT)
        }
        val url = "https://www.wenku8.net/book/$bookId.htm"
        val response = executeTextRequest(url.toHttpUrl(), cookie, userAgent)
        if (!response.isSuccessful || response.siteClosed) return null
        return parseNovelDetailPage(response.body, bookId)
    }

    /**
     * 下载 wenku8 的 TXT 文件内容。
     *
     * 与原仓库 `/novel_res` 一致：`https://dl.wenku8.com/down.php?type=txt&node={node}&id={id}`。
     * 返回原始字节，由调用方写入工作区。
     */
    fun downloadWenku8Txt(bookId: String, cookie: String, userAgent: String = ""): ByteArray {
        val id = bookId.toLongOrNull() ?: error("书 ID 无效：$bookId")
        val node = (id / 1000).toString()
        val url = "https://dl.wenku8.com/down.php"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("type", "txt")
            .addQueryParameter("node", node)
            .addQueryParameter("id", bookId)
            .build()
        val request = Request.Builder()
            .url(url)
            .headers(buildWenku8Headers(cookie, userAgent))
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            response.body?.bytes() ?: error("下载内容为空")
        }
    }

    // ==================== 番茄小说 API ====================

    /**
     * 在番茄小说 API 中搜索书籍，返回 book_id -> 名称 的列表。
     */
    fun findFromApi(searchTerm: String): List<LocalNovelApiBook> {
        val baseUrl = resolveApiBaseUrl() ?: return emptyList()
        val url = "$baseUrl/api/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", searchTerm)
            .addQueryParameter("tab_type", "3")
            .build()
        return try {
            val body = executeJsonRequest(url)
            val data = body.getAsJsonObject("data") ?: return emptyList()
            val tabs = data.getAsJsonArray("search_tabs") ?: return emptyList()
            tabs.flatMap { tab ->
                val tabObj = tab.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
                if (tabObj.get("tab_type")?.takeIf { it.isJsonPrimitive }?.asInt != 3) {
                    return@flatMap emptyList()
                }
                val items = tabObj.getAsJsonArray("data") ?: return@flatMap emptyList()
                items.flatMap { item ->
                    val itemObj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
                    val bookData = itemObj.getAsJsonArray("book_data") ?: return@flatMap emptyList()
                    bookData.mapNotNull { book ->
                        val bookObj = book.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val id = bookObj.stringValue("book_id") ?: return@mapNotNull null
                        val name = bookObj.stringValue("book_name") ?: return@mapNotNull null
                        if (id.isBlank() || name.isBlank()) null else LocalNovelApiBook(id, name)
                    }
                }
            }
        } catch (error: Exception) {
            emptyList()
        }
    }

    /**
     * 通过番茄 API 下载书籍正文，返回 TXT 内容。
     */
    fun downloadApiBook(bookId: String): String? {
        val baseUrl = resolveApiBaseUrl() ?: return null
        val url = "$baseUrl/api/content"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("tab", "下载")
            .addQueryParameter("book_id", bookId)
            .build()
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (error: Exception) {
            null
        }
    }

    /**
     * 通过番茄 API 获取书籍详情。
     */
    fun fetchApiBookInfo(bookId: String): LocalNovelBook? {
        val baseUrl = resolveApiBaseUrl() ?: return null
        val url = "$baseUrl/api/detail"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("book_id", bookId)
            .build()
        val body = try {
            executeJsonRequest(url)
        } catch (error: Exception) {
            return null
        }
        val book = body.getAsJsonObject("data")
            ?.takeIf { it.has("data") }
            ?.get("data")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val wordCount = book.stringValue("word_number")?.toLongOrNull()
        val creationStatus = book.stringValue("creation_status")?.toIntOrNull()
        val lastPublishTimestamp = book.stringValue("last_publish_time")?.toLongOrNull()
        val cover = book.stringValue("thumb_url").orEmpty()
        return LocalNovelBook(
            id = book.stringValue("book_id").orEmpty(),
            title = book.stringValue("book_name")?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
            author = book.stringValue("author").orEmpty(),
            category = book.stringValue("category").orEmpty(),
            wordCount = wordCount?.let { String.format(Locale.ROOT, "%,d", it) } ?: "未知",
            isSerialize = when (creationStatus) {
                1 -> "连载中"
                2 -> "已完结"
                else -> "未知"
            },
            lastDate = lastPublishTimestamp?.let { ts ->
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ts * 1000L))
            } ?: "未知",
            introduction = book.stringValue("abstract")
                ?.replace("\n", "")
                ?.trim()
                .orEmpty().ifBlank { "暂无简介" },
            coverUrl = cover,
            downloadUrl = "https://tomato-novel-downloader.vercel.app/?book_id=$bookId",
            pageUrl = "https://fanqienovel.com/page/$bookId",
            hot = book.stringValue("read_cnt_text").orEmpty().ifBlank { "API 来源" },
            apiBookId = bookId
        )
    }

    // ==================== 内部工具 ====================

    private fun resolveApiBaseUrl(): String? {
        cachedApiBaseUrl?.let { return it }
        for (candidate in NOVEL_API_BASE_URLS) {
            val base = candidate.trimEnd('/')
            val url = "$base/api/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", "test")
                .addQueryParameter("tab_type", "3")
                .build()
            val body = try {
                executeJsonRequest(url)
            } catch (error: Exception) {
                continue
            }
            if (body.has("data")) {
                cachedApiBaseUrl = base
                return base
            }
        }
        return null
    }

    private data class Wenku8Response(
        val body: String,
        val statusCode: Int
    ) {
        val is403: Boolean get() = statusCode == 403
        val isSuccessful: Boolean get() = statusCode in 200..299
        val siteClosed: Boolean
            get() = body.contains("有缘再相聚") || body.contains("网站已关闭")
        val requiresLogin: Boolean
            get() = body.contains("出现错误") ||
                (body.contains("登录") && !body.contains("退出登录")) ||
                body.contains("请先登录") ||
                body.contains("您还没有登录")
    }

    private fun executeTextRequest(url: okhttp3.HttpUrl, cookie: String, userAgent: String = ""): Wenku8Response {
        val request = Request.Builder()
            .url(url)
            .headers(buildWenku8Headers(cookie, userAgent))
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val rawBytes = response.body?.bytes() ?: ByteArray(0)
            // wenku8.net 统一使用 GBK 编码
            val body = try {
                String(rawBytes, charset("GBK"))
            } catch (error: Exception) {
                String(rawBytes, Charsets.UTF_8)
            }
            Wenku8Response(body, response.code)
        }
    }

    private fun executeJsonRequest(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder().url(url).get().build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) error("响应为空")
            JsonParser.parseString(text).asJsonObject
        }
    }

    companion object {
        private val NOVEL_API_BASE_URLS = listOf(
            "http://43.248.77.205:22222",
            "https://fq.shusan.cn"
        )

        // 榜单/搜索结果中单本书的整块 HTML
        private val NOVEL_CARD_BLOCK_REGEX =
            Regex("""<div style="width:373px;height:136px;float:left;margin:5px 0px 5px 5px;">(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 生成 wenku8 搜索 URL。
 *
 * wenku8 仍要求搜索词使用 GBK 百分号编码。这里必须使用
 * [okhttp3.HttpUrl.Builder.addEncodedQueryParameter]，否则 `%C4` 会被再次转义为 `%25C4`。
 */
internal fun buildWenku8SearchUrl(
    searchTerm: String,
    searchType: String,
    page: Int
): okhttp3.HttpUrl {
    // wenku8 是 GBK 编码网站，搜索关键词必须先用 GBK 编码再做 URL 编码。
    // 注意：OkHttp 的 addQueryParameter 默认用 UTF-8，addEncodedQueryParameter
    // 不会再次编码但需要传入已编码字符串。这里手动 GBK 编码后直接拼到 URL 里。
    val encodedKey = try {
        URLEncoder.encode(searchTerm, "GBK")
    } catch (error: Exception) {
        error("搜索关键词编码失败：${error.message}")
    }
    val url = "https://www.wenku8.net/modules/article/search.php" +
        "?searchtype=$searchType&searchkey=$encodedKey&page=$page"
    return url.toHttpUrl()
}

/**
 * 生成 wenku8 请求头。
 *
 * 注意：不手动设置 `Accept-Encoding`。OkHttp 会自动添加 `gzip` 并透明解压。
 * 若手动设置，OkHttp 会认为用户自行处理解压，导致拿到压缩字节按 GBK 解析成乱码。
 *
 * @param cookie wenku8 登录 Cookie
 * @param userAgent 自定义 User-Agent；为空时使用 [NOVEL_USER_AGENT_VALUE]。
 *                  CloudFlare 的 cf_clearance 绑定获取时的 IP + UA，需保持一致。
 */
internal fun buildWenku8Headers(cookie: String, userAgent: String = ""): okhttp3.Headers {
    val ua = userAgent.ifBlank { NOVEL_USER_AGENT_VALUE }
    return okhttp3.Headers.Builder()
        .add("User-Agent", ua)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Referer", "https://www.wenku8.net/")
        .add("Cookie", cookie)
        .build()
}

private const val NOVEL_USER_AGENT_VALUE =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/**
 * wenku8 返回 403 时的统一提示。
 *
 * 403 最常见的原因是 CloudFlare 的 cf_clearance 绑定了获取时的 IP + User-Agent，
 * 在手机上使用时若 IP 或 UA 不匹配就会被拦截。提示用户同时设置 Cookie 和 UA，
 * 并在手机浏览器上获取 Cookie 以保证 IP 一致。
 */
internal const val WENKU8_403_HINT: String =
    "❌ wenku8 返回 403，被 CloudFlare 拦截喵！\n" +
        "常见原因：cf_clearance 绑定了获取时的 IP + User-Agent，手机上不匹配。\n" +
        "推荐解决方法：\n" +
        "设置 → 轻小说 → wenku8 登录\n" +
        "（内置浏览器登录，自动保存 Cookie + UA，保证 IP 一致）\n\n" +
        "手动方式（高级）：\n" +
        "/set_wenku_cookie <Cookie> || <UA>"

internal const val WENKU8_LOGIN_HINT: String =
    "❌ wenku8 需要登录，Cookie 可能已失效喵！\n" +
        "推荐：设置 → 轻小说 → wenku8 登录（自动保存 Cookie + UA）\n" +
        "或使用 `/set_wenku_cookie <Cookie>` 命令更新 Cookie 喵~"

internal const val WENKU8_COOKIE_MISSING_HINT: String =
    "❌ Cookie 未设置喵！\n" +
        "推荐：设置 → 轻小说 → wenku8 登录（自动保存 Cookie + UA）\n" +
        "或使用 `/set_wenku_cookie <Cookie>` 命令手动设置"

/**
 * 解析 `/hotnovel` 的参数。
 *
 * 与原仓库一致：空串默认 day；month/mouth 走月榜。
 */
internal fun parseLocalNovelRankingPeriod(raw: String): LocalNovelRankingPeriod =
    when (raw.trim().lowercase()) {
        "", "day", "今日", "日" -> LocalNovelRankingPeriod.DAY
        "month", "mouth", "月", "本月" -> LocalNovelRankingPeriod.MONTH
        else -> throw IllegalArgumentException("格式：`/hotnovel <day|month> [数量]`")
    }

/**
 * 解析 `/hotnovel` 的数量参数，默认 10，上限 100。
 */
internal fun parseLocalNovelHotLimit(rawArgs: String): Int {
    val parts = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.size < 2) return 10
    return parts[1].toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(100) ?: 10
}

/**
 * 解析 `/select`、`/info` 的编号参数（1-based）。
 */
internal fun parseLocalNovelSelection(rawArgs: String): Int? =
    rawArgs.trim().toIntOrNull()?.takeIf { it >= 1 }

/**
 * 解析 `/novel_res` 的 res 编号。
 */
internal fun parseLocalNovelRes(rawArgs: String): String? {
    val value = rawArgs.trim()
    if (value.isEmpty()) return null
    // 允许纯数字或 wenku8 的 id=xxx 形式
    if (value.matches(Regex("\\d+"))) return value
    val match = Regex("""id=(\d+)""").find(value)
    return match?.groupValues?.getOrNull(1)
}

// ==================== HTML 解析 ====================

/**
 * 解析 wenku8 搜索/榜单卡片块为 [LocalNovelBook]。
 *
 * 对齐原仓库 `search_wenku8_books` 和 `handle_hotnovel` 中的正则提取逻辑。
 */
internal fun parseNovelCardBlock(html: String): LocalNovelBook? {
    val titleUrlMatch = Regex(
        """<b><a style="font-size:13px;" href="([^"]+)" title="([^"]+)" target="_blank">"""
    ).find(html)
    val bookUrl = titleUrlMatch?.groupValues?.getOrNull(1).orEmpty()
    val title = titleUrlMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
    if (title.isBlank()) return null

    val bookIdMatch = Regex("""/book/(\d+)\.htm""").find(bookUrl)
    val bookId = bookIdMatch?.groupValues?.getOrNull(1) ?: "0"
    val node = bookId.toLongOrNull()?.let { it / 1000 }?.toString() ?: "0"

    val authorCatMatch = Regex("""<p>作者:([^/]+)/分类:([^<]+)</p>""").find(html)
    val author = authorCatMatch?.groupValues?.getOrNull(1)?.trim() ?: "未知"
    val category = authorCatMatch?.groupValues?.getOrNull(2)?.trim() ?: "未知"

    val statsMatch = Regex("""<p>更新:([^/]+)/字数:([^/]+)/([^/<]+)(?:/|<)""").find(html)
    val lastDate = statsMatch?.groupValues?.getOrNull(1)?.trim() ?: "未知"
    val wordCount = statsMatch?.groupValues?.getOrNull(2)?.trim() ?: "未知"
    val isSerialize = statsMatch?.groupValues?.getOrNull(3)?.trim() ?: "未知"

    val tagsMatch = Regex("""Tags:<span[^>]*>([^<]+)</span>""").find(html)
    val tags = tagsMatch?.groupValues?.getOrNull(1)?.trim() ?: "无"

    val introMatch = Regex("""简介:([^<]+)""").find(html)
    val introduction = introMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "暂无简介"

    val imgMatch = Regex("""<img src="([^"]+)"""").find(html)
    val coverUrl = imgMatch?.groupValues?.getOrNull(1)
        ?: "https://img.wenku8.com/image/$node/$bookId/${bookId}s.jpg"

    return LocalNovelBook(
        id = bookId,
        title = title,
        author = author,
        category = category,
        wordCount = wordCount,
        isSerialize = isSerialize,
        lastDate = lastDate,
        introduction = introduction,
        tags = tags,
        coverUrl = coverUrl,
        downloadUrl = "https://dl.wenku8.com/down.php?type=txt&node=$node&id=$bookId",
        pageUrl = "https://www.wenku8.net/book/$bookId.htm",
        hot = "搜索结果书籍"
    )
}

/**
 * 解析 wenku8 单本详情页 HTML。
 *
 * 对齐原仓库 `get_book_detail_by_url` 中的字段提取。
 */
internal fun parseNovelDetailPage(html: String, bookId: String): LocalNovelBook? {
    val titleMatch = Regex("""<span property="v:itemreviewed">([^<]+)</span>""").find(html)
    val title = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: return null

    val authorMatch = Regex("""作者：\s*<a[^>]*>([^<]+)</a>""").find(html)
    val author = authorMatch?.groupValues?.getOrNull(1)?.trim() ?: "未知"

    val categoryMatch = Regex("""类别：\s*<a[^>]*>([^<]+)</a>""").find(html)
    val category = categoryMatch?.groupValues?.getOrNull(1)?.trim() ?: "未知"

    val statusMatch = Regex("""状态：\s*<font[^>]*>([^<]+)</font>""").find(html)
    val isSerialize = statusMatch?.groupValues?.getOrNull(1)?.trim() ?: "未知"

    val wordCountMatch = Regex("""字数：\s*([\d,]+)""").find(html)
    val wordCount = wordCountMatch?.groupValues?.getOrNull(1)?.replace(",", "") ?: "未知"

    val updateMatch = Regex("""更新时间：\s*([\d-]+)""").find(html)
    val lastDate = updateMatch?.groupValues?.getOrNull(1) ?: "未知"

    val introMatch = Regex(
        """<span class="hottext">内容简介：</span>\s*<br\s*/?>\s*([^<]+)""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html)
    val introduction = introMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "暂无简介"

    val node = bookId.toLongOrNull()?.let { it / 1000 }?.toString() ?: "0"
    val coverMatch = Regex("""<img src="([^"]+)"[^>]*alt="[^"]*封面"""").find(html)
    val coverUrl = coverMatch?.groupValues?.getOrNull(1)
        ?: "https://img.wenku8.com/image/$node/$bookId/${bookId}s.jpg"

    return LocalNovelBook(
        id = bookId,
        title = title,
        author = author,
        category = category,
        wordCount = wordCount,
        isSerialize = isSerialize,
        lastDate = lastDate,
        introduction = introduction,
        coverUrl = coverUrl,
        downloadUrl = "https://dl.wenku8.com/down.php?type=txt&node=$node&id=$bookId",
        pageUrl = "https://www.wenku8.net/book/$bookId.htm",
        hot = "URL获取"
    )
}

// ==================== HTML 生成 ====================

/**
 * 生成轻小说卡片网格 HTML（搜索结果 / 榜单）。
 *
 * 与原仓库 `build_novel_grid_html` + `append_novel_card` + `close_novel_grid_html` 等价。
 * 封面直接引用 wenku8 图片域名，浏览器联网即可加载。
 */
internal fun buildLocalNovelGridHtml(
    title: String,
    books: List<LocalNovelBook>
): String {
    val cards = books.mapIndexed { index, book ->
        val safeTitle = escapeNovelHtml(book.title)
        val safeAuthor = escapeNovelHtml(book.author)
        val safeId = escapeNovelHtml(book.id)
        val safeCover = escapeNovelHtml(book.coverUrl)
        val safePageUrl = escapeNovelHtml(book.pageUrl)
        """
        <article class="card">
          <a href="$safePageUrl" target="_blank">
            <img class="cover" src="$safeCover" alt="$safeTitle" loading="lazy">
            <div class="meta">
              <div class="rank">#${index + 1}</div>
              <div class="author">$safeAuthor</div>
              <div class="title">$safeTitle</div>
              <div class="info">ID: $safeId</div>
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
          <title>${escapeNovelHtml(title)}</title>
          <style>
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 24px;
              color: #f3f4f6;
              background: #111827;
              font-family: 'Segoe UI', 'PingFang SC', sans-serif;
            }
            h1 { font-size: 24px; margin: 0 0 8px; }
            .note { color: #9ca3af; font-size: 13px; margin: 0 0 20px; }
            .grid {
              display: grid;
              grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
              gap: 18px;
            }
            .card {
              overflow: hidden;
              border: 1px solid rgba(255,255,255,.08);
              border-radius: 16px;
              background: #1f2937;
              box-shadow: 0 10px 24px rgba(0,0,0,.22);
              transition: transform .2s, box-shadow .2s;
            }
            .card:hover { transform: translateY(-4px); box-shadow: 0 14px 28px rgba(0,0,0,.3); }
            .card a { display: block; color: inherit; text-decoration: none; }
            .cover {
              width: 100%;
              aspect-ratio: 3 / 4;
              object-fit: cover;
              display: block;
              background: #0b1220;
            }
            .meta { padding: 12px 14px 16px; }
            .rank { color: #60a5fa; font-weight: 700; font-size: 13px; margin-bottom: 6px; }
            .author { color: #60a5fa; font-weight: 700; font-size: 13px; margin-bottom: 6px; }
            .title { font-size: 14px; line-height: 1.45; word-break: break-word; }
            .info { color: #9ca3af; font-size: 12px; margin-top: 8px; }
          </style>
        </head>
        <body>
          <h1>${escapeNovelHtml(title)}</h1>
          <p class="note">共 ${books.size} 本 · 点击卡片查看小说详情或下载喵~</p>
          <main class="grid">
            $cards
          </main>
        </body>
        </html>
    """.trimIndent()
}

/**
 * 生成单本轻小说详情 HTML。
 *
 * 与原仓库 `build_novel_detail_html` 等价。
 */
internal fun buildLocalNovelDetailHtml(book: LocalNovelBook): String {
    val safeTitle = escapeNovelHtml(book.title)
    val safeAuthor = escapeNovelHtml(book.author)
    val safeCategory = escapeNovelHtml(book.category)
    val safeWordCount = escapeNovelHtml(book.wordCount)
    val safeStatus = escapeNovelHtml(book.isSerialize)
    val safeDate = escapeNovelHtml(book.lastDate)
    val safeHot = escapeNovelHtml(book.hot)
    val safeIntro = escapeNovelHtml(book.introduction)
    val safeCover = escapeNovelHtml(book.coverUrl)
    val safePageUrl = escapeNovelHtml(book.pageUrl)
    val safeDownloadUrl = escapeNovelHtml(book.downloadUrl)
    val safeTags = escapeNovelHtml(book.tags)

    return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>$safeTitle - 轻小说详情</title>
          <style>
            body {
              font-family: 'Segoe UI', 'PingFang SC', sans-serif;
              background: #111827;
              color: #f3f4f6;
              margin: 0;
              padding: 24px;
            }
            .card {
              background: #1f2937;
              border: 1px solid rgba(255,255,255,.08);
              border-radius: 16px;
              overflow: hidden;
              box-shadow: 0 10px 24px rgba(0,0,0,.22);
              max-width: 500px;
              margin: 0 auto;
            }
            .cover {
              width: 100%;
              aspect-ratio: 3 / 4;
              object-fit: cover;
              display: block;
              background: #0b1220;
            }
            .meta { padding: 20px; }
            .title { font-size: 20px; font-weight: 700; margin-bottom: 12px; line-height: 1.4; }
            .author { color: #60a5fa; font-size: 14px; margin-bottom: 16px; }
            .info-row {
              display: flex;
              justify-content: space-between;
              padding: 8px 0;
              border-bottom: 1px solid rgba(255,255,255,.05);
              font-size: 13px;
            }
            .info-label { color: #9ca3af; }
            .info-value { color: #f3f4f6; }
            .intro {
              margin-top: 16px;
              padding: 12px;
              background: rgba(0,0,0,.2);
              border-radius: 8px;
              font-size: 13px;
              line-height: 1.6;
            }
            .intro-title { color: #60a5fa; font-weight: 600; margin-bottom: 8px; }
            .actions { display: flex; gap: 12px; margin-top: 20px; }
            .btn {
              flex: 1;
              padding: 12px;
              border-radius: 8px;
              text-align: center;
              text-decoration: none;
              font-size: 14px;
              font-weight: 600;
              transition: opacity .2s;
            }
            .btn:hover { opacity: .9; }
            .btn-primary { background: #4a9eff; color: white; }
            .btn-secondary { background: #374151; color: white; }
          </style>
        </head>
        <body>
          <div class="card">
            <img class="cover" src="$safeCover" alt="$safeTitle">
            <div class="meta">
              <div class="title">$safeTitle</div>
              <div class="author">作者：$safeAuthor</div>
              <div class="info-row">
                <span class="info-label">分类</span>
                <span class="info-value">$safeCategory</span>
              </div>
              <div class="info-row">
                <span class="info-label">字数</span>
                <span class="info-value">$safeWordCount</span>
              </div>
              <div class="info-row">
                <span class="info-label">状态</span>
                <span class="info-value">$safeStatus</span>
              </div>
              <div class="info-row">
                <span class="info-label">更新日期</span>
                <span class="info-value">$safeDate</span>
              </div>
              <div class="info-row">
                <span class="info-label">热度</span>
                <span class="info-value">$safeHot</span>
              </div>
              <div class="info-row">
                <span class="info-label">Tags</span>
                <span class="info-value">$safeTags</span>
              </div>
              <div class="intro">
                <div class="intro-title">内容简介</div>
                $safeIntro
              </div>
              <div class="actions">
                <a class="btn btn-primary" href="$safeDownloadUrl">下载 TXT</a>
                <a class="btn btn-secondary" href="$safePageUrl" target="_blank">查看网页</a>
              </div>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()
}

private fun escapeNovelHtml(value: String): String =
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
