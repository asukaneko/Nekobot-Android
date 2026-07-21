package com.nekobot.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 更新检查器。
 *
 * 从 https://api.github.com/repos/asukaneko/Nekobot-Android/releases/latest 获取最新发布信息，
 * 与当前应用版本比较，并提供 APK 下载与安装意图。
 */
object UpdateChecker {

    private const val OWNER = "asukaneko"
    private const val REPO = "Nekobot-Android"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val HTML_RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 发布资产（apk）。 */
    data class ReleaseAsset(
        val name: String,
        val browserDownloadUrl: String,
        val size: Long,
        val contentType: String
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
        data class Progress(val percent: Int) : DownloadResult()
        data class Done(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * 获取最新 release。
     * @param currentVersion 当前 versionName（如 "0.3.4"）
     */
    suspend fun checkForUpdate(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nekobot-Android-Updater")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext CheckResult.Error("HTTP ${resp.code}")
                }
                val bodyStr = resp.body?.string()
                    ?: return@withContext CheckResult.Error("响应为空")
                val info = parseRelease(bodyStr)
                    ?: return@withContext CheckResult.Error("解析发布信息失败")
                if (isNewer(info.tagName, currentVersion)) {
                    CheckResult.Available(info)
                } else {
                    CheckResult.UpToDate
                }
            }
        }.getOrElse { e ->
            CheckResult.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 下载指定 asset 到 cacheDir/downloads，并通过 onProgress 回报进度。
     */
    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (DownloadResult) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val target = File(dir, asset.name)
            if (target.exists()) target.delete()

            val req = Request.Builder().url(asset.browserDownloadUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onProgress(DownloadResult.Error("HTTP ${resp.code}"))
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val total = resp.body?.contentLength() ?: -1L
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var read: Int
                        var sum = 0L
                        var lastReport = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            sum += read
                            if (total > 0) {
                                val pct = (sum * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastReport) {
                                    lastReport = pct
                                    onProgress(DownloadResult.Progress(pct))
                                }
                            }
                        }
                        output.flush()
                    }
                } ?: run {
                    onProgress(DownloadResult.Error("下载内容为空"))
                    return@withContext DownloadResult.Error("下载内容为空")
                }
            }
            onProgress(DownloadResult.Done(target))
            DownloadResult.Done(target)
        }.getOrElse { e ->
            val msg = e.message ?: "下载失败"
            onProgress(DownloadResult.Error(msg))
            DownloadResult.Error(msg)
        }
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
