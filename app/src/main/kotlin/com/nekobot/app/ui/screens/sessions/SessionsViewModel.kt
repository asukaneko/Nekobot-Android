package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import com.nekobot.app.ui.screens.chat.ChatSessionManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.RandomCharacterIdea
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import com.nekobot.app.data.model.SessionListQuery
import com.nekobot.app.data.model.WorldBook
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser

private data class DashboardMetricsLoadResults(
    val stats: Resource<TokenStats>,
    val rankings: Resource<TokenRankings>,
    val highAffectionCount: Resource<Int>,
    val worldBooks: Resource<List<WorldBook>>
)

/**
 * 会话列表 ViewModel。
 *
 * 内部维护原始会话列表 + 筛选/搜索状态，
 * 对外暴露 [displayedSessionRows]（已筛选 + 已搜索 + 已排序 + 已解析角色回退）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionsViewModel : BaseViewModel() {

    /**
     * 会话列表滚动状态。
     *
     * 挂在 ViewModel 上而不是 Composable 的 rememberSaveable：rememberSaveable 只保存
     * index/offset，从聊天返回时按重新排序后的同一 index 定位会错位；LazyListState 自身
     * 按 item key 跟踪首个可见项，跨页面存活才能在列表重排后依然停在进入会话前的位置。
     */
    val listScrollState = LazyListState()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessionSummaries: StateFlow<List<Session>> = _sessions.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _dashboardTokenStats = MutableStateFlow<TokenStats?>(null)
    private val _dashboardRankings = MutableStateFlow<TokenRankings?>(null)
    private val _dashboardHighAffectionCharacterCount = MutableStateFlow(0)
    private val _dashboardWorldBookCount = MutableStateFlow(0)
    private val _dashboardAchievementScopeId = MutableStateFlow(
        com.nekobot.app.data.local.AchievementManager.activeScopeId()
    )
    private val _dashboardLoading = MutableStateFlow(false)
    private var dataSourceVersion = 0L
    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    private val _sessionCreationState = MutableStateFlow(SessionCreationUiState())
    private val _webDavStatus = MutableStateFlow<DashboardWebDavStatus?>(null)
    private val _localLogPreview = MutableStateFlow<List<DashboardLogEntry>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()
    internal val sessionCreationState: StateFlow<SessionCreationUiState> = _sessionCreationState.asStateFlow()
    val dashboardLoading: StateFlow<Boolean> = _dashboardLoading.asStateFlow()

    val dashboardData: StateFlow<SessionStatsDashboardData> = combine(
        _sessions,
        _dashboardTokenStats,
        _dashboardRankings,
        _characters,
        _webDavStatus,
        _localLogPreview,
        _dashboardHighAffectionCharacterCount,
        _dashboardWorldBookCount,
        _dashboardAchievementScopeId
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        buildSessionStatsDashboardData(
            sessions = values[0] as List<Session>,
            stats = values[1] as TokenStats?,
            rankings = values[2] as TokenRankings?,
            characters = values[3] as List<CharacterPreset>,
            webDavStatus = values[4] as DashboardWebDavStatus?,
            localLogPreview = values[5] as List<DashboardLogEntry>,
            highAffectionCharacterCount = values[6] as Int,
            worldBookCount = values[7] as Int,
            achievementScopeId = values[8] as String
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SessionStatsDashboardData()
        )

    val overview: StateFlow<SessionOverview> = _sessions
        .map(::buildSessionOverview)
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SessionOverview()
        )

    private val _filter = MutableStateFlow(SessionFilter.ALL)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    /** 当前选中的频道值（null = 全部频道）。默认 null。 */
    private val _channelFilterValue = MutableStateFlow<String?>(null)
    val channelFilterValue: StateFlow<String?> = _channelFilterValue.asStateFlow()

    /** 动态频道列表：从已加载会话的 type 字段派生。 */
    val availableChannels: StateFlow<List<ChannelOption>> = _sessions
        .map { sessions ->
            val types = sessions
                .mapNotNull { it.type?.lowercase()?.trim()?.takeIf { t -> t.isNotBlank() } }
                .distinct()
                .sorted()
            // 标签映射：web->Web, qq->QQ, 其他首字母大写
            types.map { t ->
                val label = when (t) {
                    "web" -> "Web"
                    "qq" -> "QQ"
                    "cli" -> "CLI"
                    else -> t.replaceFirstChar { c -> c.uppercase() }
                }
                ChannelOption(label, t)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _characterFilterId = MutableStateFlow<String?>(null)
    val characterFilterId: StateFlow<String?> = _characterFilterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val visibleSessionLimit = MutableStateFlow(80)
    private val listRevision = MutableStateFlow(0L)
    @Volatile private var loadedSessionLimit = 0
    private val _matchingSessionCount = MutableStateFlow(0)
    val matchingSessionCount: StateFlow<Int> = _matchingSessionCount.asStateFlow()
    @Volatile private var hasMoreSessions = false

    private val listQuery = combine(
        _filter, _channelFilterValue, _characterFilterId, _searchQuery,
        ServiceContainer.prefs.recentSessionsIncludeArchivedFlow
    ) { filter, channel, character, search, includeArchived ->
        SessionListQuery(filter.name, channel, character, search, includeArchived)
    }

    private data class ListRequest(
        val sessions: List<Session>, val query: SessionListQuery,
        val revision: Long, val local: Boolean
    )

    private val displayedSessions: StateFlow<List<Session>> = combine(
        _sessions, listQuery, visibleSessionLimit, listRevision, ServiceContainer.appModeFlow
    ) { sessions, query, limit, revision, mode ->
        ListRequest(sessions, query.copy(limit = limit), revision, mode == AppMode.LOCAL)
    }.mapLatest { request ->
        try {
            val page = if (request.local) unified.listLocalSessionPage(request.query) else null
            val matches = if (request.local) page?.sessions.orEmpty()
                else filterSessionList(request.sessions, request.query)
            _matchingSessionCount.value = page?.totalCount ?: matches.size
            hasMoreSessions = page?.hasMore ?: (matches.size > request.query.limit)
            loadedSessionLimit = request.query.limit
            matches.take(request.query.limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showError(error.message ?: string(R.string.common_unknown_error))
            hasMoreSessions = false
            emptyList()
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadMoreSessions() {
        if (hasMoreSessions && loadedSessionLimit == visibleSessionLimit.value) {
            visibleSessionLimit.value = (visibleSessionLimit.value.toLong() + 80)
                .coerceAtMost(Int.MAX_VALUE - 1L).toInt()
        }
    }

    /** 批量选择仍覆盖当前筛选下的全部记录。 */
    fun loadAllMatchingSessions() {
        visibleSessionLimit.value = Int.MAX_VALUE - 1
    }

    fun selectAllMatchingSessions(onSelected: (Set<String>) -> Unit) {
        val version = dataSourceVersion
        val query = currentListQuery()
        loadAllMatchingSessions()
        viewModelScope.launch {
            try {
                val page = unified.listLocalSessionPage(query.copy(limit = Int.MAX_VALUE - 1))
                val matches = page?.sessions ?: filterSessionList(_sessions.value, query)
                if (version == dataSourceVersion && query == currentListQuery()) {
                    onSelected(matches.mapNotNull { it.id }.toSet())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.common_unknown_error))
            }
        }
    }

    private fun currentListQuery() = SessionListQuery(
        _filter.value.name, _channelFilterValue.value, _characterFilterId.value,
        _searchQuery.value, ServiceContainer.prefs.recentSessionsIncludeArchivedFlow.value
    )

    private fun resetSessionPaging() {
        visibleSessionLimit.value = 80
        loadedSessionLimit = 0
        listScrollState.requestScrollToItem(0)
    }

    private fun filterSessionList(all: List<Session>, querySpec: SessionListQuery): List<Session> {
        val f = SessionFilter.valueOf(querySpec.filter)
        val chVal = querySpec.channel
        val charId = querySpec.characterId
        val query = querySpec.search
        val includeArchived = querySpec.includeArchived
        // 先排除压缩上下文自动创建的归档会话（is_archive == true）；
        // 手动归档的会话（archived == true 但 is_archive != true）仍可展示
        val visible = all
            .filter { it.isArchive != true }
            .let { sessions ->
                when {
                    f in setOf(SessionFilter.CHARACTER_SESSIONS, SessionFilter.AGENT_SESSIONS, SessionFilter.AGENT_CHARACTERS) -> {
                        sessions.filter { it.archived != true }
                    }
                    f == SessionFilter.ALL && !includeArchived -> {
                        sessions.filter { it.archived != true }
                    }
                    else -> sessions
                }
            }
        // 先按频道筛选
        val byChannel = applyChannelFilter(visible, chVal)
        val filtered = applyFilter(byChannel, f, charId)
        val searched = applySearch(filtered, query)
        // 置顶强制置顶；归档会话沉到底部；同组内按时间倒序（updatedAt 回退到 createdAt）
        return searched.sortedWith(
            compareByDescending<Session> { it.archived != true }
                .thenByDescending { it.pinned == true }
                .thenByDescending { it.updatedAt ?: it.createdAt ?: "" }
        )
    }

    /**
     * 列表行在数据变化时于后台线程一次性生成；滚动过程中不再扫描角色列表、
     * 拼接群聊角色名或计算完整 Session 的 hashCode。
     */
    val displayedSessionRows: StateFlow<List<SessionListRow>> = combine(
        displayedSessions,
        _characters
    ) { sessions, characters ->
        buildSessionListRows(
            sessions = sessions,
            characters = characters,
            portraitUrlResolver = { resolveAvatarUrl(it) }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun setFilter(f: SessionFilter) {
        resetSessionPaging()
        _filter.value = f
    }

    /**
     * 首页 banner 用的会话行：不受筛选/搜索影响，按更新时间倒序，且排除归档。
     *
     * 与 [displayedSessionRows] 分开，避免用户在当前筛选下看不到 banner 该显示的那条会话。
     */
    val homeBannerRows: StateFlow<List<SessionListRow>> = combine(
        _sessions,
        _characters
    ) { sessions, characters ->
        buildSessionListRows(
            sessions = sessions
                .filter { it.isArchive != true && it.archived != true }
                .sortedByDescending { it.updatedAt ?: it.createdAt.orEmpty() },
            characters = characters,
            portraitUrlResolver = { resolveAvatarUrl(it) }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * 顶部四个筛选按钮的切换：再次点按已选中的筛选恢复到默认「最近会话」（ALL）。
     * 同时清空仅对 BY_CHARACTER 有意义的角色筛选，避免残留状态。
     */
    fun toggleFilter(f: SessionFilter) {
        resetSessionPaging()
        if (_filter.value == f) {
            _filter.value = SessionFilter.ALL
            _characterFilterId.value = null
        } else {
            _filter.value = f
        }
    }

    fun setChannelFilter(value: String?) {
        resetSessionPaging()
        _channelFilterValue.value = value
    }

    fun setCharacterFilter(id: String?) {
        resetSessionPaging()
        _characterFilterId.value = id
    }

    fun setSearchQuery(q: String) {
        resetSessionPaging()
        _searchQuery.value = q
    }

    /** 加载会话 + 角色列表。 */
    fun loadAll(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        loadSessions(version)
        loadCharacters(version)
        loadSessionCreationCharacters(version)
    }

    /** 下拉刷新独立管理进度，保留列表并等待全部请求完成，避免重复触发。 */
    fun refresh() {
        if (!_refreshing.compareAndSet(expect = false, update = true)) return
        val version = dataSourceVersion
        viewModelScope.launch {
            try {
                val (sessionsResult, charactersResult, creationCharactersResult) = coroutineScope {
                    val sessionsRequest = async { unified.listSessionSummaries() }
                    val charactersRequest = async { unified.listCharacters() }
                    val creationCharactersRequest = async { unified.listCharactersForSessionCreation() }
                    Triple(sessionsRequest.await(), charactersRequest.await(), creationCharactersRequest.await())
                }
                if (version != dataSourceVersion) return@launch
                when (sessionsResult) {
                    is Resource.Success -> {
                        _sessions.value = sessionsResult.data ?: emptyList()
                        listRevision.value += 1
                    }
                    is Resource.Error -> showError(sessionsResult.message)
                    is Resource.Loading -> {}
                }
                when (charactersResult) {
                    is Resource.Success -> _characters.value = charactersResult.data ?: emptyList()
                    is Resource.Error -> showError(charactersResult.message)
                    is Resource.Loading -> {}
                }
                when (creationCharactersResult) {
                    is Resource.Success -> _sessionCreationState.value = _sessionCreationState.value.copy(
                        characters = creationCharactersResult.data ?: emptyList(),
                        error = null
                    )
                    is Resource.Error -> _sessionCreationState.value = _sessionCreationState.value.copy(
                        error = creationCharactersResult.message
                    )
                    is Resource.Loading -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == dataSourceVersion) {
                    showError(e.message ?: string(R.string.common_unknown_error))
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    private var lastDataSourceKey: Pair<AppMode, Long>? = null

    /**
     * 数据源（运行模式 / 数据库档案）真正变化时才清空重建列表；
     * 仅从聊天等页面返回时只做静默刷新，不清空列表，避免列表闪空、滚动位置跳变。
     */
    fun onDataSourceChanged(mode: AppMode, revision: Long) {
        val key = mode to revision
        if (lastDataSourceKey == key) {
            loadAll()
            loadDashboardMetrics()
            return
        }
        lastDataSourceKey = key
        reloadForDataSource()
    }

    /** 数据库 Profile / 运行模式切换时先清空旧快照，再从新数据源完整加载。 */
    fun reloadForDataSource() {
        resetSessionPaging()
        listRevision.value += 1
        val version = ++dataSourceVersion
        // 数据源已切换，旧的滚动锚点不再适用，回到列表顶部
        listScrollState.requestScrollToItem(0)
        _sessions.value = emptyList()
        _characters.value = emptyList()
        _sessionCreationState.value = SessionCreationUiState()
        _dashboardTokenStats.value = null
        _dashboardRankings.value = null
        _dashboardHighAffectionCharacterCount.value = 0
        _dashboardWorldBookCount.value = 0
        _webDavStatus.value = null
        _localLogPreview.value = emptyList()
        _dashboardAchievementScopeId.value =
            com.nekobot.app.data.local.AchievementManager.activeScopeId()
        loadAll(version)
        loadDashboardMetrics(version)
    }

    /** 独立加载负一屏 Token 总览与排行榜；失败时仍展示会话派生统计。 */
    fun loadDashboardMetrics(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        viewModelScope.launch {
            _dashboardLoading.value = true
            try {
                val results = coroutineScope {
                    val statsRequest = async { unified.tokenStats(dateRange = "total") }
                    val rankingsRequest = async { unified.tokenRankings() }
                    val highAffectionCountRequest = async {
                        unified.countHighAffectionCharacters(threshold = 90)
                    }
                    val worldBooksRequest = async { unified.listWorldBooks() }
                    DashboardMetricsLoadResults(
                        stats = statsRequest.await(),
                        rankings = rankingsRequest.await(),
                        highAffectionCount = highAffectionCountRequest.await(),
                        worldBooks = worldBooksRequest.await()
                    )
                }
                if (version != dataSourceVersion) return@launch
                if (results.stats is Resource.Success) {
                    _dashboardTokenStats.value = results.stats.data
                }
                if (results.rankings is Resource.Success) {
                    _dashboardRankings.value = results.rankings.data
                }
                if (results.highAffectionCount is Resource.Success) {
                    _dashboardHighAffectionCharacterCount.value = results.highAffectionCount.data
                }
                if (results.worldBooks is Resource.Success) {
                    _dashboardWorldBookCount.value = results.worldBooks.data.size
                }
                loadWebDavStatus()
                loadLocalLogPreview()
            } catch (_: Exception) {
                // 会话统计可独立工作，Token 接口失败不阻断负一屏。
            } finally {
                if (version == dataSourceVersion) {
                    _dashboardLoading.value = false
                }
            }
        }
    }

    /** 加载 WebDAV 备份状态；本地与服务器模式共用同一张状态卡。 */
    private suspend fun loadWebDavStatus() {
        try {
            val configResult = unified.getWebDavConfig()
            val infoResult = unified.webDavInfo()
            val config = (configResult as? Resource.Success)?.data
            val info = (infoResult as? Resource.Success)?.data?.asJsonObject
            val remoteFileSize = info?.get("file_size")
                ?.takeUnless { it.isJsonNull }
                ?.asLong
                ?: info?.get("size")
                    ?.takeUnless { it.isJsonNull }
                    ?.asLong
                ?: config?.lastFileSize
            _webDavStatus.value = DashboardWebDavStatus(
                enabled = config?.enabled == true,
                configured = !config?.url.isNullOrBlank(),
                url = config?.url,
                lastBackupAt = config?.lastBackupAt?.takeIf { it.isNotBlank() },
                lastSyncAt = config?.lastSyncAt?.takeIf { it.isNotBlank() },
                lastError = config?.lastError?.takeIf { it.isNotBlank() },
                remoteFileSize = remoteFileSize,
                remoteModifiedAt = info?.get("last_modified")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: config?.lastModified?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            _webDavStatus.value = null
        }
    }

    /** 加载本地日志速览（最近 5 条）。 */
    private suspend fun loadLocalLogPreview() {
        try {
            val records = withContext(Dispatchers.IO) {
                com.nekobot.app.data.local.LocalLogger.listLogs()
            }
            _localLogPreview.value = records.take(5).map { record ->
                DashboardLogEntry(
                    time = record.time,
                    level = record.level,
                    tag = record.tag,
                    message = record.message
                )
            }
        } catch (_: Exception) {
            _localLogPreview.value = emptyList()
        }
    }

    /** 加载会话列表。 */
    fun loadSessions(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        launchResult(
            block = { unified.listSessionSummaries() },
            onSuccess = {
                if (version == dataSourceVersion) {
                    _sessions.value = it ?: emptyList()
                    listRevision.value += 1
                }
            }
        )
    }

    /**
     * 导入会话：支持本地模式导出格式、nekobot 远程导出格式，远程模式也兼容本地导出。
     * 在 IO 线程读取 Uri 字节并解析，统一调用 [unified.importSessions] 分发到本地或远程实现。
     */
    fun importSessions(uri: Uri, context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    showToast(string(R.string.sessions_import_empty))
                    return@launch
                }
                val payload = withContext(Dispatchers.Default) {
                    JsonParser.parseString(String(bytes, Charsets.UTF_8))
                }
                if (payload.isJsonNull || (!payload.isJsonObject && !payload.isJsonArray)) {
                    showToast(string(R.string.sessions_import_invalid_format))
                    return@launch
                }
                showToast(string(R.string.sessions_import_progress))
                when (val res = unified.importSessions(payload)) {
                    is Resource.Success -> {
                        val r = res.data
                        when {
                            r.imported > 0 && r.failed == 0 -> {
                                showToast(string(R.string.sessions_import_success, r.imported))
                            }
                            r.imported > 0 && r.failed > 0 -> {
                                showToast(string(R.string.sessions_import_partial, r.imported, r.failed))
                            }
                            r.imported == 0 && r.failed > 0 -> {
                                val detail = r.errors.firstOrNull() ?: ""
                                showToast(
                                    if (detail.isBlank()) string(R.string.sessions_import_failed, "")
                                    else string(R.string.sessions_import_failed, detail)
                                )
                            }
                            else -> showToast(string(R.string.sessions_import_empty))
                        }
                        if (r.imported > 0) loadSessions()
                    }
                    is Resource.Error -> showToast(string(R.string.sessions_import_failed, res.message ?: ""))
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showToast(string(R.string.sessions_import_failed, e.message ?: ""))
            } finally {
                setLoading(false)
            }
        }
    }

    /** 加载完整角色列表，供筛选和统计使用。 */
    fun loadCharacters(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { list ->
                if (version == dataSourceVersion) {
                    _characters.value = list ?: emptyList()
                }
            }
        )
    }

    /** 每次打开新建弹窗时刷新可选角色，避免使用旧归属快照。 */
    fun loadSessionCreationCharacters(expectedDataSourceVersion: Long? = null) {
        if (_sessionCreationState.value.loading) return
        val version = expectedDataSourceVersion ?: dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(characters = emptyList(), loading = true, error = null)
        launchResult(
            block = {
                try {
                    unified.listCharactersForSessionCreation()
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(loading = false)
                    }
                }
            },
            onSuccess = { list ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(characters = list ?: emptyList())
                }
            },
            onError = { message ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(error = message)
                }
            }
        )
    }

    /** 新建会话，提交期间阻止重复创建，失败后保留弹窗里的输入。 */
    fun createSession(req: CreateSessionRequest, onSuccess: (Session) -> Unit = {}) {
        if (_sessionCreationState.value.creating) return
        val version = dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(creating = true, error = null)
        launchResult(
            block = {
                try {
                    unified.createSession(req)
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(creating = false)
                    }
                }
            },
            onSuccess = { session ->
                if (version == dataSourceVersion) {
                    showToast(string(R.string.sessions_created_toast))
                    loadSessions()
                    onSuccess(session)
                }
            },
            onError = { message ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(error = message)
                    showError(message)
                }
            }
        )
    }

    /** 新建临时 Agent 会话：不保存记录、不使用全局记忆，成功后回调。 */
    fun createTemporarySession(name: String, onSuccess: (Session) -> Unit = {}) {
        if (_sessionCreationState.value.creating) return
        val version = dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(creating = true, error = null)
        launchResult(
            block = {
                try {
                    unified.createTemporarySession(name)
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(creating = false)
                    }
                }
            },
            onSuccess = { session ->
                if (version == dataSourceVersion) {
                    loadSessions()
                    onSuccess(session)
                }
            },
            onError = { message ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(error = message)
                    showError(message)
                }
            }
        )
    }

    /** 为指定角色一键创建会话，成功后回调 sessionId。 */
    fun createSessionForCharacter(characterId: String, characterName: String, onSuccess: (String) -> Unit) {
        launchResult(
            block = {
                val req = CreateSessionRequest(
                    name = characterName,
                    sessionMode = "character",
                    characterId = characterId,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                unified.createSession(req)
            },
            onSuccess = { session ->
                session?.id?.let { id ->
                    showToast(string(R.string.sessions_created_toast))
                    loadSessions()
                    onSuccess(id)
                }
            }
        )
    }

    /**
     * AI 根据描述生成角色卡并持久化，然后自动为其创建会话，成功后回调 sessionId。
     */
    fun aiGenerateCharacterAndCreateSession(
        description: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        launchResult(
            block = { unified.aiGenerateCharacter(description) },
            onSuccess = { preset ->
                preset ?: run {
                    showToast(string(R.string.character_ai_generate_failed))
                    onError(string(R.string.character_ai_generate_failed))
                    return@launchResult
                }
                // 保存生成的角色卡
                launchResult(
                    block = {
                        val json = ServiceContainer.gson.toJsonTree(preset)
                        unified.createCharacter(json)
                    },
                    onSuccess = { saved ->
                        saved?.id?.let { characterId ->
                            showToast(string(R.string.character_ai_generated, saved.displayName))
                            // 自动创建会话
                            createSessionForCharacter(
                                characterId = characterId,
                                characterName = saved.displayName,
                                onSuccess = onSuccess
                            )
                        } ?: run {
                            showToast(string(R.string.character_ai_generate_failed))
                            onError(string(R.string.character_ai_generate_failed))
                        }
                    },
                    onError = { msg ->
                        showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
                        onError(msg ?: string(R.string.character_ai_generate_failed))
                    }
                )
            },
            onError = { msg ->
                showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
                onError(msg ?: string(R.string.character_ai_generate_failed))
            }
        )
    }

    /**
     * 使用 AI 随机生成 3 个角色灵感条目。本地模式直接调用 LLM；远程模式回退到内置种子。
     */
    fun generateRandomCharacterIdeas(onResult: (List<RandomCharacterIdea>) -> Unit) {
        launchResult(
            block = { unified.generateRandomCharacterIdeas() },
            onSuccess = { ideas ->
                if (!ideas.isNullOrEmpty()) {
                    onResult(ideas)
                } else {
                    showToast(string(R.string.character_ai_generate_failed, string(R.string.models_none_available)))
                }
            },
            onError = { msg ->
                showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
            }
        )
    }

    /** 删除会话，成功后刷新并回调 [onSuccess]。 */
    fun deleteSession(id: String, onSuccess: () -> Unit = {}) {
        // 先暂停计时再删除：删除失败时批次与气泡保留（重进聊天页恢复计时），成功才提交撤回。
        ChatSessionManager.pauseDelayedReplyTimer(id)
        launchResult(
            block = { unified.deleteSession(id) },
            onSuccess = {
                ChatSessionManager.cancelDelayedReply(id, removeBubbles = true)
                showToast(string(R.string.sessions_deleted_toast))
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 重命名会话，成功后刷新并回调 [onSuccess]。 */
    fun renameSession(id: String, name: String, onSuccess: () -> Unit = {}) {
        if (name.isBlank()) {
            showError(string(R.string.sessions_name_empty_error))
            return
        }
        launchResult(
            block = { unified.updateSession(id, UpdateSessionRequest(name = name.trim(), updateListTitle = true)) },
            onSuccess = {
                showToast(string(R.string.sessions_renamed_toast))
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 切换收藏状态。 */
    fun toggleFavorite(sessionId: String, currentlyFavorite: Boolean) {
        val newFav = !currentlyFavorite
        launchResult(
            block = { unified.updateSession(sessionId, UpdateSessionRequest(favorite = newFav)) },
            onSuccess = {
                loadSessions()
            }
        )
    }

    /** 切换置顶状态。 */
    fun togglePinned(sessionId: String, currentlyPinned: Boolean) {
        val newPinned = !currentlyPinned
        launchResult(
            block = { unified.updateSession(sessionId, UpdateSessionRequest(pinned = newPinned)) },
            onSuccess = {
                showToast(if (newPinned) string(R.string.sessions_pinned_toast) else string(R.string.sessions_unpinned_toast))
                loadSessions()
            }
        )
    }

    /** 切换归档状态。 */
    fun toggleArchived(sessionId: String, currentlyArchived: Boolean) {
        val newArchived = !currentlyArchived
        launchResult(
            block = {
                if (newArchived) unified.archiveSession(sessionId)
                else unified.restoreSession(sessionId)
            },
            onSuccess = {
                showToast(if (newArchived) string(R.string.sessions_archived_toast) else string(R.string.sessions_unarchived_toast))
                loadSessions()
            }
        )
    }

    /**
     * 批量操作：置顶 / 收藏 / 归档 / 删除。
     *
     * [enable] 为目标状态：所选会话已全部置顶时传 false 即批量取消置顶，
     * 收藏与归档同理。逐个会话提交，单个失败不影响其余会话。
     */
    fun batchUpdateSessions(
        action: SessionBatchAction,
        sessionIds: Collection<String>,
        enable: Boolean = true
    ) {
        val targets = sessionIds.filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        val version = dataSourceVersion
        viewModelScope.launch {
            setLoading(true)
            var success = 0
            var failed = 0
            try {
                targets.forEach { id ->
                    val ok = try {
                        when (action) {
                            SessionBatchAction.PIN ->
                                unified.updateSession(id, UpdateSessionRequest(pinned = enable)) is Resource.Success

                            SessionBatchAction.FAVORITE ->
                                unified.updateSession(id, UpdateSessionRequest(favorite = enable)) is Resource.Success

                            SessionBatchAction.ARCHIVE ->
                                if (enable) {
                                    unified.archiveSession(id) is Resource.Success
                                } else {
                                    unified.restoreSession(id) is Resource.Success
                                }

                            SessionBatchAction.DELETE -> {
                                // 与单条删除一致：成功后才撤回待发的延迟回复
                                ChatSessionManager.pauseDelayedReplyTimer(id)
                                val result = unified.deleteSession(id)
                                if (result is Resource.Success) {
                                    ChatSessionManager.cancelDelayedReply(id, removeBubbles = true)
                                }
                                result is Resource.Success
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) success++ else failed++
                }
            } finally {
                setLoading(false)
            }
            if (version != dataSourceVersion) return@launch
            loadSessions()
            when {
                success == 0 -> showError(string(R.string.sessions_batch_failed, failed))
                failed == 0 -> showToast(string(batchSuccessToastRes(action, enable), success))
                else -> showToast(string(R.string.sessions_batch_partial, success, failed))
            }
        }
    }

    private fun batchSuccessToastRes(action: SessionBatchAction, enable: Boolean): Int = when (action) {
        SessionBatchAction.PIN -> if (enable) {
            R.string.sessions_batch_pinned_toast
        } else {
            R.string.sessions_batch_unpinned_toast
        }

        SessionBatchAction.FAVORITE -> if (enable) {
            R.string.sessions_batch_favorited_toast
        } else {
            R.string.sessions_batch_unfavorited_toast
        }

        SessionBatchAction.ARCHIVE -> if (enable) {
            R.string.sessions_batch_archived_toast
        } else {
            R.string.sessions_batch_unarchived_toast
        }

        SessionBatchAction.DELETE -> R.string.sessions_batch_deleted_toast
    }

    /** 根据频道过滤会话（先于其他筛选）。channelValue 为 null 时返回全部。 */    private fun applyChannelFilter(all: List<Session>, channelValue: String?): List<Session> {
        if (channelValue == null) return all
        return all.filter { s ->
            val type = s.type?.lowercase()?.trim() ?: "web"
            type == channelValue
        }
    }

    /** 根据筛选类型过滤会话。 */
    private fun applyFilter(all: List<Session>, f: SessionFilter, charId: String?): List<Session> {
        return when (f) {
            SessionFilter.ALL -> all
            SessionFilter.CHARACTER_SESSIONS -> all.filter(Session::isCharacterSession)
            SessionFilter.AGENT_SESSIONS -> all.filter { it.isAgentSession() && !it.isAgentCharacterSession() }
            SessionFilter.AGENT_CHARACTERS -> all.filter(Session::isAgentCharacterSession)
            SessionFilter.UNARCHIVED -> all.filter { it.archived != true }
            SessionFilter.ARCHIVED -> all.filter { it.archived == true }
            SessionFilter.FAVORITE -> all.filter { it.favorite == true }
            SessionFilter.PINNED -> all.filter { it.pinned == true }
            SessionFilter.PUBLIC -> all.filter { it.isPublic == true }
            SessionFilter.BY_CHARACTER -> {
                if (charId.isNullOrBlank()) all
                else all.filter { s ->
                    s.characterId == charId || s.characterIds?.contains(charId) == true
                }
            }
        }
    }

    /** 根据关键词搜索会话名称 / 角色名 / 最后一条消息。 */
    private fun applySearch(list: List<Session>, query: String): List<Session> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filter { s ->
            s.displayName.contains(q, ignoreCase = true) ||
                (s.characterName?.contains(q, ignoreCase = true) == true) ||
                (s.lastMessage?.contains(q, ignoreCase = true) == true)
        }
    }
}
