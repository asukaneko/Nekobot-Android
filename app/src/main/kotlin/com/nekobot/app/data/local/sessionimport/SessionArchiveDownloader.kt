package com.nekobot.app.data.local.sessionimport

import com.nekobot.app.data.local.plugin.PublicHttpsAddressPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从下载链接获取会话导出归档（例如 DeepSeek 官方数据导出的 OBS 签名链接）。
 *
 * 链接由用户粘贴，属于不可信输入：
 * - 只接受 HTTPS + 公网主机（解析域名时校验，拦截 `https://127.0.0.1` 这类内网探测）；
 * - 重定向逐跳校验，避免公网地址跳转到内网；
 * - 下载体积有上限，超限即中止。
 *
 * 下载到缓存目录后返回文件，解析与清理由调用方负责。
 */
class SessionArchiveDownloader(
    private val client: OkHttpClient = defaultClient(),
    /** 测试用：替换地址校验逻辑；生产环境固定使用公网 HTTPS 校验。 */
    private val validateUrl: (HttpUrl) -> Unit = ::requirePublicUrl
) {

    suspend fun download(
        url: String,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val target = File.createTempFile("session-import-", ".zip", targetDir)
        try {
            fetch(target, url, onProgress)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun fetch(
        target: File,
        url: String,
        onProgress: (Long, Long?) -> Unit
    ): File {
        var current = parseUrl(url).also(validateUrl)
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(current).get().build()
            client.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (++redirects > MAX_REDIRECTS) {
                        throw SessionImportException.DownloadFailed("too many redirects")
                    }
                    val location = response.header("Location")
                        ?: throw SessionImportException.DownloadFailed("redirect without location")
                    current = (current.resolve(location) ?: throw SessionImportException.InvalidUrl("bad redirect"))
                        .also(validateUrl)
                    return@use
                }
                if (!response.isSuccessful) {
                    throw SessionImportException.DownloadFailed("HTTP ${response.code}")
                }
                val body = response.body ?: throw SessionImportException.DownloadFailed("empty response")
                val total = body.contentLength().takeIf { it > 0L }
                if (total != null && total > SessionArchiveStaging.MAX_ARCHIVE_BYTES) {
                    throw SessionImportException.TooLarge()
                }
                body.byteStream().use { source ->
                    target.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > SessionArchiveStaging.MAX_ARCHIVE_BYTES) {
                                throw SessionImportException.TooLarge()
                            }
                            sink.write(buffer, 0, read)
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        if (downloaded == 0L) {
                            throw SessionImportException.DownloadFailed("empty response")
                        }
                        onProgress(downloaded, total)
                    }
                }
                return target
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_STEP_BYTES = 512L * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** 解析用户粘贴的链接（只校验是否是可解析的 HTTP(S) 地址）。 */
        internal fun parseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw SessionImportException.InvalidUrl("empty url")
            return trimmed.toHttpUrlOrNull() ?: throw SessionImportException.InvalidUrl("malformed url")
        }

        /** 公网 HTTPS 校验：拦掉明文与内网/本机地址，避免把导入链接当成内网探测入口。 */
        internal fun requirePublicUrl(url: HttpUrl) {
            if (!url.isHttps) throw SessionImportException.InvalidUrl("https required")
            val host = url.host
            if (host.equals("localhost", ignoreCase = true) ||
                host.endsWith(".localhost", ignoreCase = true) ||
                runCatching { PublicHttpsAddressPolicy.resolve(host) }.isFailure
            ) {
                throw SessionImportException.InvalidUrl("non-public host: $host")
            }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
