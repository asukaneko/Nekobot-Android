package com.nekobot.app.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nekobot.app.data.local.PrefsManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络客户端工厂：根据用户配置的 serverUrl 动态构建 Retrofit 实例。
 * 服务器地址变更后需调用 [rebuild]。
 */
class NetworkClient(private val prefs: PrefsManager) {

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .disableHtmlEscaping()
        .create()

    private val authInterceptor = AuthInterceptor(prefs)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentBaseUrl: String = prefs.serverUrl

    @Volatile
    private var currentRetrofit: Retrofit = buildRetrofit(currentBaseUrl)

    val apiService: ApiService
        get() = currentRetrofit.create(ApiService::class.java)

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val safeUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(safeUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    /** 当服务器地址变更时调用，重建 Retrofit。 */
    fun rebuild() {
        val newUrl = prefs.serverUrl
        if (newUrl != currentBaseUrl) {
            currentBaseUrl = newUrl
            currentRetrofit = buildRetrofit(newUrl)
        }
    }

    fun baseUrl(): String = currentBaseUrl
}
