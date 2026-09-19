package com.nekobot.app.data.remote

import com.nekobot.app.data.local.PrefsManager
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * 注入鉴权 header：Authorization: Bearer <token>
 *
 * 只在请求目标就是当前配置的服务器主机时才附加令牌。
 *
 * 这个客户端是共享的：Coil 的全局 ImageLoader（见 NekobotApp.newImageLoader）与聊天页面
 * 都用它抓取**消息正文里的任意 URL**。如果无条件附加令牌，攻击者只要让助手回复里出现一个
 * 自己域名的图片地址，用户打开会话时令牌就会被送到该主机。
 *
 * serverUrl 为空或无法解析时按「不附加」处理（fail closed），避免退化成无条件放行。
 */
class AuthInterceptor(private val prefs: PrefsManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = prefs.token
        val request = if (!token.isNullOrEmpty() && isServerHost(original.url.host)) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    /** 请求主机是否就是用户配置的服务器主机。 */
    private fun isServerHost(host: String): Boolean {
        if (host.isBlank()) return false
        val serverHost = runCatching { URI(prefs.serverUrl).host }.getOrNull() ?: return false
        return serverHost.isNotBlank() && host.equals(serverHost, ignoreCase = true)
    }
}
