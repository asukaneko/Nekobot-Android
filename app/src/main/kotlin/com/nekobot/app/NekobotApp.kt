package com.nekobot.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.data.local.LocalPlotStoryStore
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.plugin.PluginGrants
import com.nekobot.app.data.local.plugin.PluginManager
import com.nekobot.app.data.local.plugin.PluginMetaStore
import com.nekobot.app.data.remote.NetworkClient
import com.nekobot.app.data.remote.SocketManager
import com.nekobot.app.data.repository.NekobotRepository
import com.nekobot.app.data.repository.UnifiedRepository
import com.nekobot.app.data.local.security.RealtimeCredentialStore
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
import kotlinx.coroutines.runBlocking
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
    /** 本地 ZIP 插件管理器；插件命令会在本地斜杠命令解析阶段动态注册。 */
    lateinit var pluginManager: PluginManager
        private set
    /** 插件权限授权集合（清单声明 ∧ 用户授权）。 */
    lateinit var pluginGrants: PluginGrants
        private set
    /** 插件兼容级别记录（移植来源与差异说明）。 */
    lateinit var pluginMetaStore: PluginMetaStore
        private set
    internal lateinit var realtimeCredentialStore: RealtimeCredentialStore
        private set
    /** 跨会话共享、按当前本地数据库 Profile 隔离的 Agent 长期记忆。 */
    var globalAgentMemory: GlobalAgentMemoryStore = GlobalAgentMemoryStore.emptyPlaceholder()
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
    private val _appModeFlow = MutableStateFlow(AppMode.LOCAL)
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

    /** 插件命令触发的待打开插件页面（NavGraph 观察并消费）。 */
    private val _pendingPluginPage = MutableStateFlow<PendingPluginPage?>(null)
    val pendingPluginPage: StateFlow<PendingPluginPage?> = _pendingPluginPage.asStateFlow()

    /** 插件命令请求打开页面；由聊天命令执行链路调用，UI 层负责导航。 */
    fun requestPluginPage(
        pluginId: String,
        pageId: String,
        sessionId: String?,
        args: String,
        progressParentMessageId: String? = null
    ) {
        if (pluginId.isBlank() || pageId.isBlank()) return
        _pendingPluginPage.value = PendingPluginPage(
            pluginId = pluginId,
            pageId = pageId,
            sessionId = sessionId,
            args = args,
            progressParentMessageId = progressParentMessageId
        )
    }

    fun consumePendingPluginPage() {
        _pendingPluginPage.value = null
    }

    /** 插件命令触发的页面打开请求。 */
    data class PendingPluginPage(
        val pluginId: String,
        val pageId: String,
        val sessionId: String?,
        val args: String,
        /** 触发命令的用户消息 id；页面可用它更新该消息上的进度卡片。 */
        val progressParentMessageId: String? = null
    )

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

    /** 待用户决定的「旧版全局记忆迁移」询问；null 表示没有需要询问的事项。 */
    private val _pendingMemoryMigration = MutableStateFlow<PendingMemoryMigration?>(null)
    val pendingMemoryMigration: StateFlow<PendingMemoryMigration?> = _pendingMemoryMigration.asStateFlow()

    /** Agent 记忆内容变化事件流：迁移写入后通知已打开的页面重新读取。 */
    private val _memoryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val memoryChanged: SharedFlow<Unit> = _memoryChanged

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
        realtimeCredentialStore = RealtimeCredentialStore(app)
        bindGlobalAgentMemory(app, prefs.activeDbName)
        prefs.migrateSensitivePreferences()
        freezeLegacyPlotStoryProfiles(app)
        network = NetworkClient(prefs)
        repository = NekobotRepository(network, prefs)
        val db = NekobotDatabase.get(app, prefs.activeDbName)
        migrateLegacyPlotStoryProfile(app, prefs.activeDbName, db)
        localRepository = LocalRepository(db, LocalAiClient(), app)
        unified = UnifiedRepository(prefs, repository, localRepository, app)
        pluginGrants = PluginGrants(app)
        pluginMetaStore = PluginMetaStore(app)
        pluginManager = PluginManager(app, pluginGrants, pluginMetaStore)
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
        // 插件页面网格小组件跟随插件启停/安装/卸载刷新（StateFlow 立即发射当前值）。
        applicationScope.launch {
            pluginManager.installed.collect {
                runCatching { com.nekobot.app.widget.PluginPagesWidgetProvider.refreshAll(app) }
            }
        }
    }

    /** 在任何新 profile 可能创建前冻结 legacy 迁移资格，不在冷启动时打开所有数据库。 */
    private fun freezeLegacyPlotStoryProfiles(app: Application) {
        val existingProfiles = (prefs.listDbProfiles().map { it.name } + prefs.activeDbName)
            .distinct()
            .filter { profileName ->
                val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
                app.getDatabasePath(dbName).isFile
            }
        LocalPlotStoryStore.freezeLegacyProfiles(app, existingProfiles)
    }

    /** 仅在某个旧 profile 实际打开时查询 id 并迁移，避免全库冷启动与重试覆盖。 */
    private fun migrateLegacyPlotStoryProfile(
        context: Context,
        profileName: String,
        db: NekobotDatabase
    ) {
        if (!LocalPlotStoryStore.isLegacyProfilePending(context, profileName)) return
        try {
            val sessionIds = runBlocking(Dispatchers.IO) {
                db.openHelper.readableDatabase.query("SELECT id FROM local_sessions").use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow("id")
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(idColumn))
                    }
                }
            }
            LocalPlotStoryStore.migrateLegacyProfile(context, profileName, sessionIds)
        } catch (error: Exception) {
            // 数据库暂时不可读时继续使用 legacy 存储，避免启动循环或提前丢数据。
            com.nekobot.app.data.local.LocalLogger.e(
                "PlotStory",
                "数据库 $profileName 的旧故事地图迁移失败: ${error.message}",
                error
            )
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
            migrateLegacyPlotStoryProfile(ctx, profileName, db)
            bindGlobalAgentMemory(ctx, profileName)
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

    /**
     * 把 Agent 长期记忆绑定到指定数据库 Profile。
     *
     * 记忆不再跨数据库共享：每个 Profile 读写自己的 `agent/<profile>/global-memory.md`。
     * 若该 Profile 尚无记忆文件、而旧版全局记忆有内容，则挂起一个待确认的迁移询问
     * （见 [pendingMemoryMigration]），由 UI 弹窗让用户选择「迁移」或「留空」。
     * 用户做出选择后不再询问，避免把用户刻意清空的 Profile 反复塞回旧内容。
     */
    private fun bindGlobalAgentMemory(context: android.content.Context, profileName: String) {
        globalAgentMemory = GlobalAgentMemoryStore(context, profileName)
        _pendingMemoryMigration.value = resolvePendingMemoryMigration(context, profileName)
    }

    /**
     * 判断是否需要就旧版全局记忆的迁移询问用户。
     *
     * 只有同时满足以下条件才询问：
     * 1. 还没问过（[PrefsManager.agentMemoryMigrationAsked] 为 false）；
     * 2. 当前 Profile 没有记忆文件（从未有过，或用户已删库重建）；
     * 3. 旧版全局记忆确实有内容可迁移。
     *
     * 注意判据是「文件不存在」而不是「内容为空」：用户主动清空后文件仍在，
     * 不应该再被当成「需要迁移」。
     */
    private fun resolvePendingMemoryMigration(
        context: android.content.Context,
        profileName: String
    ): PendingMemoryMigration? = runCatching {
        if (prefs.agentMemoryMigrationAsked) return@runCatching null
        val store = GlobalAgentMemoryStore(context, profileName)
        if (store.exists()) return@runCatching null
        val legacyContent = GlobalAgentMemoryStore.legacyStore(context).read().content
        if (legacyContent.isBlank()) return@runCatching null
        PendingMemoryMigration(
            profileName = profileName,
            legacyCharCount = legacyContent.length
        )
    }.onFailure {
        com.nekobot.app.data.local.LocalLogger.e(
            "GlobalAgentMemory",
            "解析记忆迁移询问失败: ${it.message}",
            it
        )
    }.getOrNull()

    /**
     * 应用用户对记忆迁移的选择，并记录「已询问」，此后不再询问。
     *
     * @param migrate true = 把旧版全局记忆内容拷贝到当前 Profile；false = 保持为空。
     */
    fun resolveMemoryMigration(migrate: Boolean) {
        val pending = _pendingMemoryMigration.value ?: return
        appContext?.let { ctx ->
            runCatching {
                if (migrate) {
                    // 迁移期间用户可能切了 Profile，这里始终写回询问时那个 Profile 的文件，
                    // 而不是当前绑定，避免把旧记忆写进用户刚切过去的库。
                    val target = GlobalAgentMemoryStore(ctx, pending.profileName)
                    if (!target.exists()) {
                        val legacyContent = GlobalAgentMemoryStore.legacyStore(ctx).read().content
                        if (legacyContent.isNotBlank()) target.replace(legacyContent)
                    }
                }
            }.onFailure {
                com.nekobot.app.data.local.LocalLogger.e(
                    "GlobalAgentMemory",
                    "旧全局记忆迁移失败: ${it.message}",
                    it
                )
            }
        }
        prefs.agentMemoryMigrationAsked = true
        _pendingMemoryMigration.value = null
        // 迁移写入了文件，通知已打开的页面对齐到最新内容。
        _dataSourceRevision.value += 1L
        _memoryChanged.tryEmit(Unit)
    }

    /** 旧版全局记忆等待用户决定是否迁移到当前 Profile。 */
    data class PendingMemoryMigration(
        val profileName: String,
        val legacyCharCount: Int
    )

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
        // 事件钩子：app.lifecycle（app.start），异步执行，不阻塞启动
        ServiceContainer.applicationScope.launch {
            runCatching { ServiceContainer.pluginManager.runLifecycleHook("app.start") }
        }
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
