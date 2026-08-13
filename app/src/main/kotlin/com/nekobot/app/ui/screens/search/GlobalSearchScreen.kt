package com.nekobot.app.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonArray
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.LegacyMemory
import com.nekobot.app.data.model.MemoryFile
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.navigation.Routes
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

    private var searchJob: Job? = null

    fun updateQuery(value: String) {
        _query.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            search(value)
        }
    }

    private suspend fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            _results.value = defaultCommands()
            _searching.value = false
            return
        }
        _searching.value = true
        try {
            val result = coroutineScope {
                val sessionsCall = async { unified.listSessions().successData().orEmpty() }
                val charactersCall = async { unified.listCharacters().successData().orEmpty() }
                val booksCall = async { unified.listWorldBooks().successData().orEmpty() }
                val messagesCall = async {
                    if (isLocalMode) ServiceContainer.localRepository.searchMessages(query, 60) else emptyList()
                }
                val memoriesCall = async { loadMemories() }

                val sessions = sessionsCall.await()
                val commands = defaultCommands().mapNotNull { it.matching(query) }
                val sessionResults = sessions.mapNotNull { session ->
                    val score = matchScore(
                        query,
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
                        subtitle = listOfNotNull(session.characterName, session.lastMessage?.oneLine()).joinToString(" · ").take(180),
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
                        title = content.oneLine().take(90).ifBlank { string(R.string.global_search_message_fallback) },
                        subtitle = "${message.sender ?: message.role.orEmpty()} · ${message.timestamp.orEmpty()}",
                        route = Routes.chat(sessionId),
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
                    .take(MAX_RESULTS)
            }
            _results.value = result
        } catch (error: Exception) {
            showError(error.message ?: string(R.string.global_search_failed))
        } finally {
            _searching.value = false
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
        return sessionIds.take(MAX_WORKSPACES).flatMap { sessionId ->
            val entries = ServiceContainer.localRepository.listWorkspaceFiles(sessionId, null)
            if (!entries.isJsonArray) return@flatMap emptyList()
            entries.asJsonArray.mapNotNull { element ->
                val item = element.asJsonObject
                val name = item.get("name")?.asString.orEmpty()
                val path = item.get("path")?.asString.orEmpty()
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
        command(R.string.global_search_command_characters, R.string.global_search_command_characters_desc, Routes.CHARACTERS),
        command(R.string.global_search_command_world_books, R.string.global_search_command_world_books_desc, Routes.WORLD_BOOKS),
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
        const val MAX_WORKSPACES = 80
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
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                },
                placeholder = { Text(stringResource(R.string.global_search_placeholder)) },
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(12.dp))
            if (!error.isNullOrBlank()) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (!searching && results.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.global_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = results.groupBy(GlobalSearchResult::kind)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlobalSearchKind.entries.forEach { kind ->
                        val items = grouped[kind].orEmpty()
                        if (items.isNotEmpty()) {
                            item(key = "header:${kind.name}") {
                                Text(
                                    stringResource(kind.labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            itemsIndexed(items, key = { index, item -> "${item.key}:$index" }) { _, item ->
                                GlobalSearchRow(item) { onNavigate(item.route) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchRow(result: GlobalSearchResult, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = result.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.subtitle.isNotBlank()) {
                    Text(
                        result.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun GlobalSearchKind.icon(): ImageVector = when (this) {
    GlobalSearchKind.COMMAND -> Icons.Filled.Bolt
    GlobalSearchKind.SESSION, GlobalSearchKind.MESSAGE -> Icons.Filled.Chat
    GlobalSearchKind.CHARACTER -> Icons.Filled.Person
    GlobalSearchKind.WORLD_BOOK -> Icons.Filled.MenuBook
    GlobalSearchKind.MEMORY -> Icons.Filled.Memory
    GlobalSearchKind.WORKSPACE -> Icons.Filled.Folder
}
