package com.nekobot.app.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoPageHorizontalPadding
import com.nekobot.app.ui.components.NekoIconAction
import com.nekobot.app.ui.components.NekoGroup
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoDetailTopBar
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.LegacyMemory
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.MemoryFile
import com.nekobot.app.data.model.userFacingTitle
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GlobalSearchKind(@StringRes val labelRes: Int) {
    COMMAND(R.string.global_search_kind_command),
    SESSION(R.string.global_search_kind_session),
    MESSAGE(R.string.global_search_kind_message),
    CHARACTER(R.string.global_search_kind_character),
    WORLD_BOOK(R.string.global_search_kind_world_book),
    MEMORY(R.string.global_search_kind_memory),
    WORKSPACE(R.string.global_search_kind_workspace)
}

data class GlobalSearchResult(
    val key: String,
    val kind: GlobalSearchKind,
    val title: String,
    val subtitle: String,
    val route: String,
    val score: Int
)

class GlobalSearchViewModel : BaseViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(defaultCommands())
    val results: StateFlow<List<GlobalSearchResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _recentSessions = MutableStateFlow<List<Session>>(emptyList())

    /** 空查询时展示在「快捷入口」下方的最近会话（按更新时间倒序，最多 4 条）。 */
    val recentSessions: StateFlow<List<Session>> = _recentSessions.asStateFlow()

    private var searchJob: Job? = null
    private var recentJob: Job? = null
    private var searchRequest = 0
    private data class Cached(val revision: Long, val expiresAt: Long, val value: Any)
    private val catalogCache = mutableMapOf<String, Cached>()
    private val _selectedKind = MutableStateFlow<GlobalSearchKind?>(null)
    val selectedKind = _selectedKind.asStateFlow()
    fun selectKind(kind: GlobalSearchKind?) { _selectedKind.value = kind }
    fun refresh() {
        catalogCache.clear()
        updateQuery(_query.value)
        // 会话可能在别处被改名 / 归档 / 删除，刷新时一并更新「最近会话」
        loadRecentSessions()
    }

    init {
        viewModelScope.launch {
            ServiceContainer.sessionChanged.collect { refresh() }
        }
        viewModelScope.launch {
            ServiceContainer.dataSourceRevision.collect {
                catalogCache.clear()
                _results.value = defaultCommands()
                updateQuery(_query.value)
                loadRecentSessions()
            }
        }
        loadRecentSessions()
    }

    /** 载入「最近会话」：不过滤、按更新时间倒序取前几条。 */
    private fun loadRecentSessions() {
        recentJob?.cancel()
        val revision = ServiceContainer.dataSourceRevision.value
        recentJob = viewModelScope.launch {
            val rows = runCatching {
                (ServiceContainer.unified.listSessions() as? Resource.Success<List<Session>>)?.data
            }.getOrNull()
                .orEmpty()
                .filter { it.isArchive != true && it.archived != true }
                .sortedByDescending { it.updatedAt ?: it.createdAt.orEmpty() }
                .take(RECENT_SESSION_LIMIT)
            // 数据源在加载期间切换过就丢弃这次结果，避免旧数据源的列表覆盖新的
            if (revision == ServiceContainer.dataSourceRevision.value) {
                _recentSessions.value = rows
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T {
        val revision = ServiceContainer.dataSourceRevision.value
        catalogCache[key]?.takeIf { it.revision == revision && it.expiresAt > System.currentTimeMillis() }
            ?.let { return it.value as T }
        return load().also { value ->
            if (revision == ServiceContainer.dataSourceRevision.value)
                catalogCache[key] = Cached(revision, System.currentTimeMillis() + 30_000, value)
        }
    }

    fun updateQuery(value: String) {
        _query.value = value
        val request = ++searchRequest
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            search(value, request)
        }
    }

    private suspend fun search(rawQuery: String, request: Int) {
        val revision = ServiceContainer.dataSourceRevision.value
        val query = rawQuery.trim()
        if (query.isBlank()) {
            _results.value = defaultCommands()
            _searching.value = false
            return
        }
        _searching.value = true
        try {
            val result = coroutineScope {
                val sessionsCall = async { cached("sessions") { unified.listSessions().successData().orEmpty() } }
                val charactersCall = async { cached("characters") { unified.listCharacters().successData().orEmpty() } }
                val booksCall = async { cached("books") { unified.listWorldBooks().successData().orEmpty() } }
                val messagesCall = async {
                    unified.searchMessages(query, 120).successData().orEmpty()
                }
                val memoriesCall = async { cached("memories") { loadMemories() } }

                val sessions = sessionsCall.await()
                val commands = defaultCommands().mapNotNull { it.matching(query) }
                val sessionResults = sessions.mapNotNull { session ->
                    val score = matchScore(
                        query,
                        session.displayName,
                        session.name,
                        session.characterName,
                        session.lastMessage,
                        session.tags?.joinToString(" ")
                    ) ?: return@mapNotNull null
                    val id = session.id ?: return@mapNotNull null
                    GlobalSearchResult(
                        key = "session:$id",
                        kind = GlobalSearchKind.SESSION,
                        title = session.displayName,
                        subtitle = listOfNotNull(if (session.companionMode == true) session.userFacingTitle() else session.characterName,
                            session.lastMessage?.oneLine()).joinToString(" · ").take(180),
                        route = Routes.chat(id),
                        score = score
                    )
                }
                val messageResults = messagesCall.await().mapNotNull { message ->
                    val sessionId = message.sessionId ?: return@mapNotNull null
                    val content = message.content.orEmpty()
                    val score = matchScore(query, content, message.reasoningContent) ?: return@mapNotNull null
                    GlobalSearchResult(
                        key = "message:${message.id ?: "$sessionId:${message.timestamp}:${content.hashCode()}"}",
                        kind = GlobalSearchKind.MESSAGE,
                        title = searchSnippet(if (content.contains(query, ignoreCase = true)) content else message.reasoningContent.orEmpty(), query)
                            .ifBlank { string(R.string.global_search_message_fallback) },
                        subtitle = "${message.sender ?: message.role.orEmpty()} · ${message.timestamp.orEmpty()}",
                        route = message.id?.let { Routes.chatLocation(sessionId, it) } ?: Routes.chat(sessionId),
                        score = score
                    )
                }
                val characterResults = charactersCall.await().mapNotNull { character ->
                    val score = matchScore(
                        query,
                        character.name,
                        character.description,
                        character.personality,
                        character.tags?.joinToString(" ")
                    ) ?: return@mapNotNull null
                    val id = character.id ?: return@mapNotNull null
                    GlobalSearchResult(
                        key = "character:$id",
                        kind = GlobalSearchKind.CHARACTER,
                        title = character.displayName,
                        subtitle = character.description.orEmpty().oneLine().take(180),
                        route = Routes.characterView(id),
                        score = score
                    )
                }
                val bookResults = booksCall.await().mapNotNull { book ->
                    val entryText = book.entries.orEmpty().joinToString(" ") { entry ->
                        listOf(entry.keysText, entry.comment, entry.content).joinToString(" ")
                    }
                    val score = matchScore(query, book.name, book.description, entryText) ?: return@mapNotNull null
                    val id = book.id ?: return@mapNotNull null
                    GlobalSearchResult(
                        key = "worldbook:$id",
                        kind = GlobalSearchKind.WORLD_BOOK,
                        title = book.displayName,
                        subtitle = book.description.orEmpty().oneLine().take(180),
                        route = Routes.worldBookDetail(id),
                        score = score
                    )
                }
                val memoryResults = memoriesCall.await().mapNotNull { memory -> memory.toResult(query) }
                val workspaceResults = if (isLocalMode) searchWorkspaces(query, sessions.mapNotNull { it.id }) else emptyList()

                (commands + sessionResults + messageResults + characterResults + bookResults + memoryResults + workspaceResults)
                    .distinctBy(GlobalSearchResult::key)
                    .sortedWith(compareBy<GlobalSearchResult> { it.score }.thenBy { it.kind.ordinal }.thenBy { it.title })
                    .groupBy(GlobalSearchResult::kind).values.flatMap { it.take(MAX_RESULTS) }
            }
            if (request == searchRequest && revision == ServiceContainer.dataSourceRevision.value && rawQuery == _query.value) _results.value = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (request == searchRequest && revision == ServiceContainer.dataSourceRevision.value)
                showError(error.message ?: string(R.string.global_search_failed))
        } finally {
            if (request == searchRequest) _searching.value = false
        }
    }

    private suspend fun loadMemories(): List<SearchableMemory> {
        return if (isLocalMode) {
            ServiceContainer.localRepository.listMemories(null).map(SearchableMemory::fromLegacy)
        } else {
            val fsFiles = repo.listMemoryFs().successData()?.files.orEmpty().map(SearchableMemory::fromFile)
            val legacyResponse = repo.listLegacyMemory().successData()
            val legacy = legacyResponse?.memories.orEmpty() +
                legacyResponse?.longTerm.orEmpty() + legacyResponse?.shortTerm.orEmpty()
            fsFiles + legacy.map(SearchableMemory::fromLegacy)
        }
    }

    private suspend fun searchWorkspaces(query: String, sessionIds: List<String>): List<GlobalSearchResult> {
        return cached("workspaces") { unified.listWorkspaceSearchEntries(sessionIds) }.mapNotNull { item ->
                val sessionId = item.sessionId
                val name = item.name
                val path = item.path
                val score = matchScore(query, name, path) ?: return@mapNotNull null
                GlobalSearchResult(
                    key = "workspace:$sessionId:$path",
                    kind = GlobalSearchKind.WORKSPACE,
                    title = name,
                    subtitle = path,
                    route = Routes.workspace(sessionId),
                    score = score
                )
        }
    }

    private fun SearchableMemory.toResult(query: String): GlobalSearchResult? {
        val score = matchScore(query, title, content, summary, characterName) ?: return null
        return GlobalSearchResult(
            key = "memory:$id",
            kind = GlobalSearchKind.MEMORY,
            title = title.ifBlank { string(R.string.global_search_memory_fallback) },
            subtitle = listOf(characterName, summary.ifBlank { content.oneLine() }).filter(String::isNotBlank).joinToString(" · ").take(180),
            route = Routes.MEMORY,
            score = score
        )
    }

    private fun GlobalSearchResult.matching(query: String): GlobalSearchResult? =
        matchScore(query, title, subtitle)?.let { copy(score = it) }

    private fun defaultCommands(): List<GlobalSearchResult> = listOf(
        command(R.string.global_search_command_sessions, R.string.global_search_command_sessions_desc, Routes.SESSIONS),
        command(R.string.global_search_command_characters, R.string.global_search_command_characters_desc, Routes.library(0)),
        command(R.string.global_search_command_world_books, R.string.global_search_command_world_books_desc, Routes.library(1)),
        command(R.string.global_search_command_memories, R.string.global_search_command_memories_desc, Routes.MEMORY),
        command(R.string.global_search_command_ai_config, R.string.global_search_command_ai_config_desc, Routes.AI_CONFIG_CENTER),
        command(R.string.global_search_command_system_ops, R.string.global_search_command_system_ops_desc, Routes.SYSTEM_OPERATIONS),
        command(R.string.global_search_command_data_portability, R.string.global_search_command_data_portability_desc, Routes.DATA_PORTABILITY),
        command(R.string.global_search_command_webdav, R.string.global_search_command_webdav_desc, Routes.WEBDAV_BACKUP),
        command(R.string.global_search_command_skills, R.string.global_search_command_skills_desc, Routes.SKILLS),
        command(R.string.global_search_command_settings, R.string.global_search_command_settings_desc, Routes.SETTINGS)
    )

    private fun command(@StringRes title: Int, @StringRes subtitle: Int, route: String) = GlobalSearchResult(
        key = "command:$route",
        kind = GlobalSearchKind.COMMAND,
        title = string(title),
        subtitle = string(subtitle),
        route = route,
        score = 50
    )

    private fun matchScore(query: String, vararg values: String?): Int? {
        val needle = query.lowercase()
        var best: Int? = null
        values.filterNotNull().forEach { raw ->
            val value = raw.lowercase()
            val score = when {
                value == needle -> 0
                value.startsWith(needle) -> 5
                value.contains(needle) -> 10 + value.indexOf(needle).coerceAtMost(40)
                else -> null
            }
            if (score != null && (best == null || score < best!!)) best = score
        }
        return best
    }

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

    private fun <T> Resource<T>.successData(): T? = (this as? Resource.Success<T>)?.data

    private data class SearchableMemory(
        val id: String,
        val title: String,
        val content: String,
        val summary: String,
        val characterName: String
    ) {
        companion object {
            fun fromLegacy(memory: LegacyMemory) = SearchableMemory(
                id = memory.id ?: "legacy:${memory.characterName}:${memory.title}:${memory.content.hashCode()}",
                title = memory.title,
                content = memory.content,
                summary = memory.summary.orEmpty(),
                characterName = memory.characterName
            )

            fun fromFile(file: MemoryFile) = SearchableMemory(
                id = file.path,
                title = file.title.orEmpty(),
                content = file.content.orEmpty(),
                summary = file.summary.orEmpty(),
                characterName = file.characterId.orEmpty()
            )
        }
    }

    private companion object {
        const val MAX_RESULTS = 120
        const val RECENT_SESSION_LIMIT = 4
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: GlobalSearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selectedKind by viewModel.selectedKind.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val filtered = results.filter { selectedKind == null || it.kind == selectedKind }
    val grouped = filtered.groupBy(GlobalSearchResult::kind)
    // 空查询且未选类型时是"首页"：快捷前往 + 最近会话，两块各自一张分组卡
    val homeMode = query.isBlank() && selectedKind == null

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Scaffold(
        topBar = {
            NekoDetailTopBar(
                title = stringResource(R.string.global_search_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                actions = {
                    NekoIconAction(
                        icon = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.search_refresh),
                        onClick = viewModel::refresh
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            GlobalSearchInput(
                query = query,
                onQueryChange = viewModel::updateQuery,
                searching = searching,
                focusRequester = focusRequester,
                onSearch = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NekoPageHorizontalPadding, vertical = 8.dp)
            )
            GlobalSearchCategories(selectedKind, viewModel::selectKind)
            if (!error.isNullOrBlank()) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = NekoPageHorizontalPadding, vertical = 8.dp)
                )
            }
            if (!searching && filtered.isEmpty() && !homeMode) {
                GlobalSearchEmpty(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = NekoPageHorizontalPadding,
                        end = NekoPageHorizontalPadding,
                        bottom = 24.dp
                    )
                ) {
                    if (homeMode) {
                        val commands = grouped[GlobalSearchKind.COMMAND].orEmpty()
                        if (commands.isNotEmpty()) {
                            item(key = "home:commands-header") {
                                NekoSectionHeader(title = stringResource(R.string.shell_search_quick_access))
                            }
                            item(key = "home:commands") {
                                NekoGroup {
                                    commands.forEachIndexed { index, item ->
                                        GlobalSearchRow(
                                            result = item,
                                            query = query,
                                            onClick = { onNavigate(item.route) }
                                        )
                                        if (index != commands.lastIndex) NekoDivider(startInset = 61.dp)
                                    }
                                }
                            }
                        }
                        if (recentSessions.isNotEmpty()) {
                            item(key = "home:recent-header") {
                                NekoSectionHeader(title = stringResource(R.string.shell_search_recent))
                            }
                            item(key = "home:recent") {
                                NekoGroup {
                                    recentSessions.forEachIndexed { index, session ->
                                        GlobalSearchSessionRow(
                                            session = session,
                                            onClick = {
                                                session.id?.let { onNavigate(Routes.chat(it)) }
                                            }
                                        )
                                        if (index != recentSessions.lastIndex) NekoDivider(startInset = 61.dp)
                                    }
                                }
                            }
                        }
                        item(key = "home:foot") {
                            Text(
                                text = stringResource(R.string.shell_search_foot),
                                style = nekoCaptionStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        GlobalSearchKind.entries.forEach { kind ->
                            val items = grouped[kind].orEmpty()
                            if (items.isNotEmpty()) {
                                item(key = "header:${kind.name}") {
                                    NekoSectionHeader(
                                        title = stringResource(kind.labelRes),
                                        meta = items.size.toString()
                                    )
                                }
                                item(key = "group:${kind.name}") {
                                    NekoGroup {
                                        items.forEachIndexed { index, item ->
                                            GlobalSearchRow(
                                                result = item,
                                                query = query,
                                                onClick = { onNavigate(item.route) }
                                            )
                                            if (index != items.lastIndex) NekoDivider(startInset = 61.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
