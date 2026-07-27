package com.nekobot.app.data.local.ai

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder

internal data class ModelProxyConfig(
    val proxy: Proxy,
    val username: String? = null,
    val password: String = ""
)

/**
 * 解析单个模型的代理链接。
 *
 * 支持 HTTP 和 SOCKS5；省略协议时按 HTTP 处理。HTTP 代理可在链接中携带用户名密码。
 */
internal fun parseModelProxyUrl(rawUrl: String): ModelProxyConfig? {
    val raw = rawUrl.trim()
    if (raw.isEmpty()) return null

    val normalized = if ("://" in raw) raw else "http://$raw"
    val uri = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("代理链接格式无效", it) }
    val scheme = uri.scheme?.lowercase()
        ?: throw IllegalArgumentException("代理链接缺少协议")
    val type = when (scheme) {
        "http" -> Proxy.Type.HTTP
        "socks", "socks5", "socks5h" -> Proxy.Type.SOCKS
        else -> throw IllegalArgumentException("代理仅支持 http:// 或 socks5://")
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("代理链接缺少主机")
    val port = if (uri.port >= 0) uri.port else when (type) {
        Proxy.Type.HTTP -> 80
        Proxy.Type.SOCKS -> 1080
        else -> error("不支持的代理类型")
    }
    require(port in 1..65535) { "代理端口必须在 1 到 65535 之间" }

    val userInfo = uri.rawUserInfo
    if (type == Proxy.Type.SOCKS && !userInfo.isNullOrBlank()) {
        throw IllegalArgumentException("SOCKS5 代理暂不支持账号密码")
    }
    val credentials = userInfo?.split(':', limit = 2).orEmpty()
    val username = credentials.firstOrNull()
        ?.let(::decodeProxyCredential)
        ?.takeIf { it.isNotEmpty() }
    val password = credentials.getOrNull(1)?.let(::decodeProxyCredential).orEmpty()

    return ModelProxyConfig(
        proxy = Proxy(type, InetSocketAddress(host, port)),
        username = username,
        password = password
    )
}

internal fun OkHttpClient.withModelProxy(rawUrl: String): OkHttpClient {
    val config = parseModelProxyUrl(rawUrl) ?: return this
    return newBuilder()
        .proxy(config.proxy)
        .apply {
            config.username?.let { username ->
                proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(username, config.password, Charsets.UTF_8)
                            )
                            .build()
                    }
                }
            }
        }
        .build()
}

private fun decodeProxyCredential(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8.name())
