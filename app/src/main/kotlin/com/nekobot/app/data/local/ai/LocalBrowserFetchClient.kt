package com.nekobot.app.data.local.ai

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 每次跳转都重新获取目标站点的 Cookie，并重新检查地址策略。 */
internal class LocalBrowserFetchClient(
    client: OkHttpClient,
    private val cookiesForUrl: (HttpUrl) -> String,
    private val isAllowedUrl: (HttpUrl) -> Boolean
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun execute(initialRequest: Request): Response {
        var request = initialRequest
        repeat(21) { redirectCount ->
            if (!isAllowedUrl(request.url)) throw IOException("下载地址不受支持")
            request = request.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Cookie2")
                .apply { cookiesForUrl(request.url).takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                .build()
            val response = client.newCall(request).execute()
            if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return response
            val location = response.header("Location") ?: return response
            val target = request.url.resolve(location)
            response.close()
            if (target == null || !isAllowedUrl(target) || (request.url.isHttps && !target.isHttps)) {
                throw IOException("重定向地址不受支持")
            }
            if (redirectCount == 20) throw IOException("重定向次数过多")
            val sameOrigin = request.url.scheme == target.scheme &&
                request.url.host == target.host && request.url.port == target.port
            request = request.newBuilder().url(target)
                .removeHeader("Host")
                .apply {
                    if (!sameOrigin) {
                        // 自定义鉴权头也不能随下载地址跳到另一站点。
                        val safeHeaders = setOf("user-agent", "accept", "accept-language", "accept-encoding", "range", "cache-control")
                        request.headers.names().filter { it.lowercase() !in safeHeaders }.forEach(::removeHeader)
                    }
                }
                .build()
        }
        throw IOException("重定向次数过多")
    }
}
