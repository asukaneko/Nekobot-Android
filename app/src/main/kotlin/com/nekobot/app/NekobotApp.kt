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
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.remote.NetworkClient
import com.nekobot.app.data.remote.SocketManager
import com.nekobot.app.data.repository.NekobotRepository
import com.nekobot.app.data.repository.UnifiedRepository
import com.nekobot.app.integration.IncomingShare
import com.nekobot.app.integration.NekobotShortcutManager
import com.nekobot.app.widget.NekobotWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

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

    /**
     * 应用级协程作用域：生命周期独立于 Activity/Fragment/ViewModel。
     *
     * 用途：后台异步任务（如记忆抽取、状态快照写入等不应因 UI 退出而中断的操作）。
     * 使用 SupervisorJob：单个子协程失败不会影响其他子协程。
     * 使用 IO dispatcher：这些任务主要是 I/O 密集型（DB + 网络）。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全局运行模式流：模式切换时所有观察页面自动刷新 */
    private val _appModeFlow = MutableStateFlow(AppMode.SERVER)
    val appModeFlow: StateFlow<AppMode> = _appModeFlow.asStateFlow()

    /** 数据源代次：本地数据库 Profile 切换时递增，驱动现有页面重新加载。 */
    private val _dataSourceRevision = MutableStateFlow(0L)
    val dataSourceRevision: StateFlow<Long> = _dataSourceRevision.asStateFlow()

    /** 全局登录态流：登录/登出/token 失效时自动刷新路由 */
    private val _loginStateFlow = MutableStateFlow(false)
    val loginStateFlow: StateFlow<Boolean> = _loginStateFlow.asStateFlow()

    /** 通知点击待跳转的会话 ID（NavGraph 观察并消费） */
    private val _pendingSessionId = MutableStateFlow<String?>(null)
    val pendingSessionId: StateFlow<String?> = _pendingSessionId.asStateFlow()

    /** Android 系统分享进入应用后等待选择目标会话的内容。 */
    private val _pendingShare = MutableStateFlow<IncomingShare?>(null)
    val pendingShare: StateFlow<IncomingShare?> = _pendingShare.asStateFlow()

    /**
     * 角色卡数据变化事件流：角色卡立绘/头像等关键字段更新后广播其 ID，
     * 会话列表/聊天页等持有角色快照的 ViewModel 订阅后重新加载，保持立绘同步。
     * 使用 SharedFlow + replay=0，只关心最新变化，订阅前的旧事件不重放。
     */
    private val _characterChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val characterChanged: SharedFlow<String> = _characterChanged

    fun setPendingSessionId(id: String?) { _pendingSessionId.value = id }

    fun setPendingShare(share: IncomingShare?) { _pendingShare.value = share }

    fun consumePendingShare(id: String) {
        if (_pendingShare.value?.id == id) _pendingShare.value = null
    }

    /** 广播角色卡数据变化（id 为变化的角色卡 ID，null/blank 时广播通配符 ""）。 */
    fun notifyCharacterChanged(characterId: String?) {
        _characterChanged.tryEmit(characterId?.takeIf { it.isNotBlank() } ?: "")
    }

    fun init(app: Application) {
        appContext = app.applicationContext
        prefs = PrefsManager(app)
        prefs.migrateSensitivePreferences()
        network = NetworkClient(prefs)
        repository = NekobotRepository(network, prefs)
        val db = NekobotDatabase.get(app, prefs.activeDbName)
        localRepository = LocalRepository(db, LocalAiClient(), app)
        unified = UnifiedRepository(prefs, repository, localRepository, app)
        unified.migrateLocalSecurePreferences()
        socket = SocketManager(prefs)
        ModelPricingCatalog.loadCached(app)
        // 初始化本地日志记录器
        com.nekobot.app.data.local.LocalLogger.init(app)
        // 初始化成就系统；本地模式按数据库 Profile 隔离解锁记录。
        com.nekobot.app.data.local.AchievementManager.init(app, achievementScopeId())
        // 初始化带语言配置的上下文（ViewModel 通过此上下文获取本地化字符串）
        localizedContext = LocaleHelper.wrap(appContext!!)
        // 初始化全局状态
        _appModeFlow.value = prefs.appMode
        _loginStateFlow.value = prefs.isLoggedIn
        applicationScope.launch {
            runCatching { localRepository.migrateStoredSecrets() }
                .onFailure {
                    com.nekobot.app.data.local.LocalLogger.e(
                        "LocalSecrets",
                        "本地敏感凭据迁移失败: ${it.message}",
                        it
                    )
                }
        }
        if (prefs.isLocalMode) {
            applicationScope.launch { localRepository.syncAutomationSchedules() }
        }
        applicationScope.launch {
            NekobotShortcutManager.refresh(app)
            NekobotWidgetProvider.refreshAll(app)
        }
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
            prefs.activeDbName = profileName
            localRepository.close()
            NekobotDatabase.switchProfile(ctx, profileName)
            val db = NekobotDatabase.get(ctx, profileName)
            localRepository = LocalRepository(db, LocalAiClient(), ctx)
            unified = UnifiedRepository(prefs, repository, localRepository, ctx)
            com.nekobot.app.data.local.AchievementManager.switchScope(achievementScopeId())
            _dataSourceRevision.value += 1L
            applicationScope.launch {
                runCatching { localRepository.migrateStoredSecrets() }
                    .onFailure {
                        com.nekobot.app.data.local.LocalLogger.e(
                            "LocalSecrets",
                            "本地敏感凭据迁移失败: ${it.message}",
                            it
                        )
                    }
            }
            applicationScope.launch { localRepository.syncAutomationSchedules() }
        }
    }

    /** 切换运行模式并广播 */
    fun switchAppMode(mode: AppMode) {
        prefs.appMode = mode
        com.nekobot.app.data.local.AchievementManager.switchScope(achievementScopeId())
        _appModeFlow.value = mode
        _dataSourceRevision.value += 1L
        _loginStateFlow.value = prefs.isLoggedIn
        if (mode == AppMode.LOCAL) {
            applicationScope.launch { localRepository.syncAutomationSchedules() }
        }
    }

    private fun achievementScopeId(): String =
        if (prefs.isLocalMode) {
            "local:${prefs.activeDbName}"
        } else {
            "server"
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
        PDFBoxResourceLoader.init(this)
        ServiceContainer.init(this)
    }

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .crossfade(true)
            // 工作区图片接口需要与 Retrofit 相同的 Bearer Token 鉴权。
            .okHttpClient(ServiceContainer.network.client)
            .components {
                // SVG 解码器：用于 AI 提供商 Logo 等矢量图标（assets/providers/）
                add(coil.decode.SvgDecoder.Factory())
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
