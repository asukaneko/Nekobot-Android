package com.nekobot.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.nekobot.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * GitHub Releases 更新检查器。
 *
 * 优先使用 GitHub 代理拉取发布信息和 APK，直连 GitHub 仅作为最终回退。
 * 这样可以改善国内网络下载速度，并降低共享 VPN 出口触发 GitHub API 限流的概率。
 */
object UpdateChecker {

    private const val OWNER = "asukaneko"
    private const val REPO = "Nekobot-Android"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val HTML_RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"
    private const val UPDATE_PREFS = "nekobot_update_cache"
    private const val KEY_LATEST_RELEASE_JSON = "latest_release_json"
    private const val KEY_GITHUB_ETAG = "github_etag"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val KEY_STARTUP_UPDATE_PREVIEW_PENDING = "startup_update_preview_pending"
    private const val KEY_IGNORED_PREVIEW_VERSION = "ignored_preview_version"
    private const val STARTUP_UPDATE_PREVIEW_TAG = "v999.0.0-preview"

    enum class DownloadSource {
        AUTO,
        GHPROXY,
        GITHUB_DIRECT
    }

    private data class ReleaseSource(
        val downloadSource: DownloadSource,
        val name: String,
        val proxyPrefix: String = ""
    ) {
        val isDirect: Boolean get() = proxyPrefix.isEmpty()

        fun wrap(url: String): String = proxyPrefix + url
    }

    /** 可访问 GitHub Releases API 与发布资产的 HTTPS 源，按优先级回退。 */
    private val releaseSources = listOf(
        ReleaseSource(DownloadSource.GHPROXY, "ghproxy.com", "https://ghproxy.com/"),
        ReleaseSource(DownloadSource.GITHUB_DIRECT, "GitHub")
    )

    private val metadataClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 发布资产（apk）。 */
    data class ReleaseAsset(
        val name: String,
        val browserDownloadUrl: String,
        val size: Long,
        val contentType: String,
        val downloadUrls: List<String> = buildDownloadUrls(browserDownloadUrl)
    )

    /** 发布信息。 */
    data class ReleaseInfo(
        val tagName: String,           // e.g. "v0.3.4"
        val name: String,              // 发布标题
        val body: String,              // markdown 发布说明
        val publishedAt: String,       // ISO 时间
        val htmlUrl: String,           // release 网页地址
        val assets: List<ReleaseAsset>
    ) {
        /** 找出 apk 资产（按扩展名 + content-type 判断）。 */
        val apkAsset: ReleaseAsset?
            get() = assets.firstOrNull { asset ->
                val ct = asset.contentType.lowercase()
                val nm = asset.name.lowercase()
                nm.endsWith(".apk") || ct.contains("android.package") || ct.contains("vnd.android.package")
            }
    }

    /** 检查结果。 */
    sealed class CheckResult {
        data object UpToDate : CheckResult()
        data class Available(val info: ReleaseInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /** 下载结果。 */
    sealed class DownloadResult {
        data class Progress(val percent: Int, val source: DownloadSource) : DownloadResult()
        data class Done(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * 获取最新 release。
     * @param currentVersion 当前 versionName（如 "0.3.4"）
     */
    suspend fun checkForUpdate(context: Context, currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(KEY_LATEST_RELEASE_JSON, null)
        val cachedEtag = prefs.getString(KEY_GITHUB_ETAG, null)
        val failures = mutableListOf<String>()

        for (source in releaseSources) {
            val requestBuilder = Request.Builder()
                .url(source.wrap(LATEST_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nekobot-Android-Updater")
            if (source.isDirect && !cachedEtag.isNullOrBlank()) {
                requestBuilder.header("If-None-Match", cachedEtag)
            }

            val responseResult = runCatching {
                metadataClient.newCall(requestBuilder.build()).execute()
            }
            if (responseResult.isFailure) {
                val error = responseResult.exceptionOrNull()
                failures += "${source.name}: ${error?.message ?: "连接失败"}"
                continue
            }
            val response = responseResult.getOrThrow()

            var responseEtag: String? = null
            val releaseJson = response.use { resp ->
                responseEtag = resp.header("ETag")
                when {
                    resp.code == 304 && !cachedJson.isNullOrBlank() -> cachedJson
                    resp.isSuccessful -> resp.body?.string().also {
                        if (it.isNullOrBlank()) failures += "${source.name}: 响应为空"
                    }
                    else -> {
                        failures += "${source.name}: HTTP ${resp.code}"
                        null
                    }
                }
            } ?: continue

            val info = parseRelease(releaseJson)
            if (info == null) {
                failures += "${source.name}: 发布信息无效"
                continue
            }

            prefs.edit().putString(KEY_LATEST_RELEASE_JSON, releaseJson).apply {
                if (source.isDirect && !responseEtag.isNullOrBlank()) {
                    putString(KEY_GITHUB_ETAG, responseEtag)
                }
            }.apply()
            return@withContext if (isNewer(info.tagName, currentVersion)) {
                CheckResult.Available(info)
            } else {
                CheckResult.UpToDate
            }
        }

        CheckResult.Error(failures.joinToString("；").ifBlank { "所有更新源均不可用" })
    }

    /**
     * 下载指定 asset 到 cacheDir/downloads，并通过 onProgress 回报进度。
     */
    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        source: DownloadSource = DownloadSource.AUTO,
        onProgress: (DownloadResult) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val target = File(dir, asset.name)
        val failures = mutableListOf<String>()
        val downloadUrls = buildDownloadUrls(asset.browserDownloadUrl, source)

        for (url in downloadUrls) {
            val activeSource = sourceForUrl(url)
            onProgress(DownloadResult.Progress(0, activeSource))
            val failure = downloadFromSource(target, url, asset.size, activeSource, onProgress)
            if (failure == null) {
                onProgress(DownloadResult.Done(target))
                return@withContext DownloadResult.Done(target)
            }
            failures += "${sourceLabel(url)}: $failure"
        }

        val message = failures.joinToString("；").ifBlank { "所有下载源均不可用" }
        onProgress(DownloadResult.Error(message))
        DownloadResult.Error(message)
    }

    /** 构造安装 APK 的 Intent（通过 FileProvider）。 */
    fun buildInstallIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android N+ 必须通过 FileProvider，不能用 file:// URI
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        }
    }

    /** Releases 页面 Intent。 */
    fun buildReleasesPageIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(HTML_RELEASES_URL))

    /** 读取用户选择不再提醒的 release tag；新版本的 tag 不会受影响。 */
    fun isVersionIgnored(context: Context, tagName: String): Boolean =
        tagName.isNotBlank() && context.applicationContext
            .getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IGNORED_VERSION, null) == tagName

    /** 保存用户选择不再提醒的 release tag。 */
    fun ignoreVersion(context: Context, tagName: String) {
        context.applicationContext
            .getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IGNORED_VERSION, tagName)
            .apply()
    }

    /** 安排下次启动时显示一次模拟更新提示，不影响真实更新检查。 */
    fun scheduleStartupUpdatePreview(context: Context) {
        context.applicationContext
            .getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STARTUP_UPDATE_PREVIEW_PENDING, true)
            .apply()
    }

    /** 消费一次启动更新模拟请求；已忽略模拟版本时不再返回提示。 */
    fun consumeStartupUpdatePreview(context: Context): ReleaseInfo? {
        val prefs = context.applicationContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_STARTUP_UPDATE_PREVIEW_PENDING, false)) return null
        prefs.edit().remove(KEY_STARTUP_UPDATE_PREVIEW_PENDING).apply()
        if (prefs.getString(KEY_IGNORED_PREVIEW_VERSION, null) == STARTUP_UPDATE_PREVIEW_TAG) {
            return null
        }
        return ReleaseInfo(
            tagName = STARTUP_UPDATE_PREVIEW_TAG,
            name = STARTUP_UPDATE_PREVIEW_TAG,
            body = context.getString(R.string.developer_update_preview_release_notes),
            publishedAt = "",
            htmlUrl = HTML_RELEASES_URL,
            assets = listOf(
                ReleaseAsset(
                    name = "Nekobot-preview.apk",
                    browserDownloadUrl = "https://github.com/$OWNER/$REPO/releases/download/" +
                        "$STARTUP_UPDATE_PREVIEW_TAG/Nekobot-preview.apk",
                    size = 8L * 1024L * 1024L,
                    contentType = "application/vnd.android.package-archive"
                )
            )
        )
    }

    fun isStartupUpdatePreview(tagName: String): Boolean = tagName == STARTUP_UPDATE_PREVIEW_TAG

    /** 忽略模拟版本仅影响开发者测试，不会覆盖真实 release 的忽略记录。 */
    fun ignoreStartupUpdatePreview(context: Context) {
        context.applicationContext
            .getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IGNORED_PREVIEW_VERSION, STARTUP_UPDATE_PREVIEW_TAG)
            .apply()
    }

    /** 清除模拟更新提示及其忽略状态，供重复测试使用。 */
    fun resetStartupUpdatePreview(context: Context) {
        context.applicationContext
            .getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STARTUP_UPDATE_PREVIEW_PENDING)
            .remove(KEY_IGNORED_PREVIEW_VERSION)
            .apply()
    }

    fun downloadSourcesFor(asset: ReleaseAsset): List<DownloadSource> =
        if (asset.browserDownloadUrl.startsWith("https://github.com/")) {
            listOf(DownloadSource.AUTO, DownloadSource.GHPROXY, DownloadSource.GITHUB_DIRECT)
        } else {
            listOf(DownloadSource.GITHUB_DIRECT)
        }

    internal fun buildDownloadUrls(
        browserDownloadUrl: String,
        source: DownloadSource = DownloadSource.AUTO
    ): List<String> {
        if (!browserDownloadUrl.startsWith("https://github.com/")) {
            return listOf(browserDownloadUrl)
        }
        return when (source) {
            DownloadSource.AUTO -> releaseSources.map { it.wrap(browserDownloadUrl) }.distinct()
            DownloadSource.GHPROXY -> listOf(
                releaseSources.first { it.downloadSource == DownloadSource.GHPROXY }.wrap(browserDownloadUrl)
            )
            DownloadSource.GITHUB_DIRECT -> listOf(browserDownloadUrl)
        }
    }

    private fun downloadFromSource(
        target: File,
        url: String,
        expectedSize: Long,
        source: DownloadSource,
        onProgress: (DownloadResult) -> Unit
    ): String? {
        target.delete()
        val failure = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Nekobot-Android-Updater")
                .build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use "HTTP ${resp.code}"
                val body = resp.body ?: return@use "下载内容为空"
                val contentLength = body.contentLength()
                if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) {
                    return@use "文件大小不匹配"
                }

                var downloaded = 0L
                var lastReport = -1
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (expectedSize > 0) {
                                val percent = (downloaded * 100 / expectedSize).toInt().coerceIn(0, 100)
                                if (percent != lastReport) {
                                    lastReport = percent
                                    onProgress(DownloadResult.Progress(percent, source))
                                }
                            }
                        }
                        output.flush()
                    }
                }

                when {
                    expectedSize > 0 && downloaded != expectedSize -> "下载文件不完整"
                    !isValidApk(target) -> "下载内容不是有效 APK"
                    else -> null
                }
            }
        }.getOrElse { error -> error.message ?: "下载失败" }
        if (failure != null) target.delete()
        return failure
    }

    private fun isValidApk(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    private fun sourceLabel(url: String): String = Uri.parse(url).host ?: url

    private fun sourceForUrl(url: String): DownloadSource = when {
        url.startsWith("https://ghproxy.com/") -> DownloadSource.GHPROXY
        else -> DownloadSource.GITHUB_DIRECT
    }

    /** 解析 GitHub release JSON 为 ReleaseInfo。 */
    private fun parseRelease(json: String): ReleaseInfo? = runCatching {
        val obj = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val tagName = obj.get("tag_name")?.asString ?: return null
        val name = obj.get("name")?.asString ?: tagName
        val body = obj.get("body")?.asString ?: ""
        val publishedAt = obj.get("published_at")?.asString ?: ""
        val htmlUrl = obj.get("html_url")?.asString ?: HTML_RELEASES_URL
        val assets = obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val a = el.asJsonObject
            ReleaseAsset(
                name = a.get("name")?.asString ?: return@mapNotNull null,
                browserDownloadUrl = a.get("browser_download_url")?.asString ?: return@mapNotNull null,
                size = a.get("size")?.asLong ?: 0L,
                contentType = a.get("content_type")?.asString ?: ""
            )
        } ?: emptyList()
        ReleaseInfo(tagName, name, body, publishedAt, htmlUrl, assets)
    }.getOrNull()

    /**
     * 比较 tag 与当前版本号，判断是否更新。
     * 支持形如 "v0.3.4" / "0.3.4" / "0.3.4-beta" 的版本号，按数字段比较。
     */
    private fun isNewer(tag: String, current: String): Boolean {
        fun normalize(v: String): List<Int> {
            val s = v.trim().removePrefix("v").removePrefix("V")
            // 取首个非数字/非点分隔的部分
            val core = s.split("-", "_", " ", "+").first()
            return core.split(".").mapNotNull { it.toIntOrNull() }
        }
        val a = normalize(tag)
        val b = normalize(current)
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val av = a.getOrNull(i) ?: 0
            val bv = b.getOrNull(i) ?: 0
            if (av != bv) return av > bv
        }
        return false
    }
}
