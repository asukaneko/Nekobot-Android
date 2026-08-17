package com.nekobot.app.data.remote

import com.nekobot.app.data.local.PrefsManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 注入鉴权 header：Authorization: Bearer <token>
 */
class AuthInterceptor(private val prefs: PrefsManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = prefs.token
        val request = if (!token.isNullOrEmpty()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
