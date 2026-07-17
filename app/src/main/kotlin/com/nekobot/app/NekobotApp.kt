package com.nekobot.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.remote.NetworkClient
import com.nekobot.app.data.remote.SocketManager
import com.nekobot.app.data.repository.NekobotRepository
import com.nekobot.app.data.repository.UnifiedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局依赖容器：单例持有 Prefs / Network / Repository / Gson。
 */
object ServiceContainer {
    lateinit var prefs: PrefsManager
        private set
    /** 应用上下文（用于发送通知等需要 Context 的操作） */
    var appContext: android.content.Context? = null
        private set
    /** 带选定语言配置的上下文，供 ViewModel 等非 Composable 代码获取本地化字符串。 */
    var localizedContext: Context? = null
        private set
    lateinit var network: NetworkClient
        private set
    lateinit var repository: NekobotRepository
        private set
    lateinit var unified: UnifiedRepository
        private set
    lateinit var localRepository: LocalRepository
        private set
    lateinit var socket: SocketManager
        private set
    val gson: Gson = GsonBuilder().setLenient().disableHtmlEscaping().create()

    /** 全局运行模式流：模式切换时所有观察页面自动刷新 */
    private val _appModeFlow = MutableStateFlow(AppMode.SERVER)
    val appModeFlow: StateFlow<AppMode> = _appModeFlow.asStateFlow()

    /** 全局登录态流：登录/登出/token 失效时自动刷新路由 */
    private val _loginStateFlow = MutableStateFlow(false)
    val loginStateFlow: StateFlow<Boolean> = _loginStateFlow.asStateFlow()

    /** 通知点击待跳转的会话 ID（NavGraph 观察并消费） */
    private val _pendingSessionId = MutableStateFlow<String?>(null)
    val pendingSessionId: StateFlow<String?> = _pendingSessionId.asStateFlow()

    fun setPendingSessionId(id: String?) { _pendingSessionId.value = id }

    fun init(app: Application) {
        appContext = app.applicationContext
        prefs = PrefsManager(app)
        network = NetworkClient(prefs)
        repository = NekobotRepository(network, prefs)
        val db = NekobotDatabase.get(app, prefs.activeDbName)
        localRepository = LocalRepository(db, LocalAiClient(), app)
        unified = UnifiedRepository(prefs, repository, localRepository, app)
        socket = SocketManager(prefs)
        // 初始化本地日志记录器
        com.nekobot.app.data.local.LocalLogger.init(app)
        // 初始化带语言配置的上下文（ViewModel 通过此上下文获取本地化字符串）
        localizedContext = LocaleHelper.wrap(appContext!!)
        // 初始化全局状态
        _appModeFlow.value = prefs.appMode
        _loginStateFlow.value = prefs.isLoggedIn
    }

    /** 刷新本地化上下文（语言切换后调用）。 */
    fun refreshLocale() {
        appContext?.let { localizedContext = LocaleHelper.wrap(it) }
    }

    /** 获取本地化字符串（ViewModel 等非 Composable 场景使用）。 */
    fun getString(resId: Int): String = localizedContext?.getString(resId) ?: ""

    fun rebuildNetwork() {
        network.rebuild()
    }

    /** 切换本地 db profile：重建 LocalRepository/UnifiedRepository，广播全局刷新。 */
    fun switchLocalDb(profileName: String) {
        appContext?.let { ctx ->
            NekobotDatabase.switchProfile(ctx, profileName)
            val db = NekobotDatabase.get(ctx, profileName)
            localRepository = LocalRepository(db, LocalAiClient(), ctx)
            unified = UnifiedRepository(prefs, repository, localRepository, ctx)
            // 触发模式流刷新，所有观察页自动重载
            _appModeFlow.value = prefs.appMode
        }
    }

    /** 切换运行模式并广播 */
    fun switchAppMode(mode: AppMode) {
        prefs.appMode = mode
        _appModeFlow.value = mode
        _loginStateFlow.value = prefs.isLoggedIn
    }

    /** 广播登录状态变化（登录成功 / 登出 / token 失效）。
     *  本地模式恒为已登录，不接受 false（避免本地模式被强制跳转登录页）。 */
    fun notifyLoginState(loggedIn: Boolean) {
        _loginStateFlow.value = if (prefs.isLocalMode) true else loggedIn
    }
}

class NekobotApp : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ServiceContainer.init(this)
    }

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .crossfade(true)
            .components {
                // GIF 动图解码器：API 28+ 用系统 ImageDecoder（性能更优），低版本用纯 Kotlin 解码器
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
