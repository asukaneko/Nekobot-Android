package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ChatHistoryWindow
import com.nekobot.app.data.local.ExperienceSourcePage
import com.nekobot.app.data.local.ExperienceSourceReader
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred

/** A retained page belongs to the database generation in which it was opened. */
private class OriginalChatReadSession(
    scope: CoroutineScope,
    private val revisions: StateFlow<Long>,
    private val readerFactory: (() -> ExperienceSourceReader)?,
    private val invalidate: () -> Unit,
    private val refresh: () -> Unit,
    private val unavailable: () -> Unit
) {
    private val ownerRevision = revisions.value
    private var bound = false
    private val ownedDatabase: ExperienceSourceReader? = readerFactory?.invoke()
    private var observer: Job? = null
    private var observerReady: CompletableDeferred<Unit>? = null
    private val scope = scope
    var reader: ExperienceSourceReader? = null
        private set
    var sourceVersion = 0L
        private set

    init {
        scope.launch {
            revisions.collect {
                if (!isCurrent()) {
                    observer?.cancel()
                    reader = null
                    invalidate()
                }
            }
        }
    }

    /** Capture one repository instance; matching IDs never rebind an old page to another database. */
    fun bind(): Boolean {
        if (!isCurrent()) { invalidate(); return false }
        if (!bound) {
            reader = ownedDatabase
            bound = true
        }
        if (!isCurrent()) { invalidate(); return false }
        val boundReader = reader
        if (boundReader != null && observer?.isActive != true) {
            val ready = CompletableDeferred<Unit>()
            observerReady = ready
            observer = scope.launch {
                try {
                    boundReader.changes().collect {
                        if (isCurrent()) { sourceVersion++; ready.complete(Unit); refresh() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    ready.completeExceptionally(error)
                    if (isCurrent()) unavailable()
                }
            }
        }
        return true
    }

    suspend fun awaitObserver() { observerReady?.await() }
    fun isCurrent(revision: Long = revisions.value): Boolean = ownerRevision == revision &&
        (ownedDatabase == null || runCatching { ownedDatabase!!.sharesDatabase(readerFactory!!.invoke()) }.getOrDefault(false))
}

class ExperienceSourceViewModel(
    val dataSourceRevision: StateFlow<Long> = ServiceContainer.dataSourceRevision,
    private val readerFactory: (() -> ExperienceSourceReader)? = null,
    private val readPage: (suspend (String, String, String?) -> ExperienceSourcePage?)? = null
) : BaseViewModel() {
    private val _page = MutableStateFlow<ExperienceSourcePage?>(null)
    val page = _page.asStateFlow()
    private var request = 0
    private var job: Job? = null
    private var readActive = false
    private var refreshPending = false
    private var requestedTarget: Pair<String, String>? = null
    private val access = OriginalChatReadSession(viewModelScope, dataSourceRevision,
        if (readPage == null || readerFactory != null) readerFactory ?: { ServiceContainer.localRepository.experienceSourceReader() } else null,
        { invalidate() }, ::refreshLoaded,
        { invalidate(R.string.experience_source_load_failed) })

    fun ownsCurrentDatabase(revision: Long = dataSourceRevision.value): Boolean = access.isCurrent(revision)
    fun ownsTarget(sessionId: String, archiveId: String, revision: Long): Boolean =
        access.isCurrent(revision) && requestedTarget == (sessionId to archiveId)

    /** Fail closed on a database switch or failed change subscription. */
    private fun invalidate(reason: Int = R.string.experience_source_database_changed) {
        request++
        job?.cancel()
        job = null
        readActive = false
        refreshPending = false
        _page.value = null
        setLoading(false)
        showError(string(reason))
    }

    /** Keep loaded pages when the same back-stack entry resumes or re-enters composition. */
    fun loadIfNeeded(sessionId: String, archiveId: String) {
        if (!access.isCurrent()) invalidate()
        else if (requestedTarget != (sessionId to archiveId)) load(sessionId, archiveId)
    }

    /** Explicit refresh and retry always start a new read, including after a failed attempt. */
    fun load(sessionId: String, archiveId: String) {
        if (!access.bind()) return
        requestedTarget = sessionId to archiveId
        _page.value = null
        read {
            readPage?.invoke(sessionId, archiveId, null) ?: if (readPage == null)
                access.reader!!.sourcePage(sessionId, archiveId) else null
        }
    }

    fun loadMore() {
        if (!access.isCurrent()) { invalidate(); return }
        val current = _page.value ?: return
        if (loading.value || !current.hasMore) return
        val cursor = current.messages.lastOrNull()?.id ?: return
        read {
            val next = if (readPage != null) readPage.invoke(current.archive.sessionId, current.archive.id, cursor)
                else access.reader!!.sourcePage(current.archive.sessionId, current.archive.id, cursor)
            checkNotNull(next)
            val combined = next.copy(messages = (current.messages + next.messages).distinctBy { it.id })
            if (access.reader == null) combined else access.reader!!.refreshSource(combined)
        }
    }

    private fun refreshLoaded() {
        if (readActive) { refreshPending = true; return }
        val current = _page.value ?: return
        val reader = access.reader ?: return
        read(showBusy = false, clearOnFailure = true) { reader.refreshSource(current) }
    }

    /** Defer background invalidation instead of cancelling pagination or repeatedly fetching it. */
    private fun read(showBusy: Boolean = true, clearOnFailure: Boolean = false, block: suspend () -> ExperienceSourcePage?) {
        val token = ++request
        job?.cancel()
        readActive = true
        refreshPending = false
        if (showBusy) clearError()
        setLoading(showBusy)
        job = viewModelScope.launch {
            try {
                access.awaitObserver()
                if (token != request || !access.isCurrent()) return@launch
                val version = access.sourceVersion
                refreshPending = false
                var result = block()
                if (token != request || !access.isCurrent()) return@launch
                if (version != access.sourceVersion && result != null && access.reader != null) {
                    // Validate the expanded result once, without discarding or rereading the next page.
                    val validatedVersion = access.sourceVersion
                    result = access.reader!!.refreshSource(result)
                    if (validatedVersion == access.sourceVersion) refreshPending = false
                }
                if (token != request || !access.isCurrent()) return@launch
                _page.value = result
                if (result == null) showError(string(R.string.experience_source_missing))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == request && access.isCurrent()) {
                    if (clearOnFailure) _page.value = null
                    showError(string(R.string.experience_source_load_failed))
                }
            } finally {
                if (token == request) {
                    job = null
                    readActive = false
                    setLoading(false)
                    if (refreshPending && access.isCurrent()) {
                        refreshPending = false
                        refreshLoaded()
                    }
                }
            }
        }
    }
}

class ChatLocationViewModel(
    val dataSourceRevision: StateFlow<Long> = ServiceContainer.dataSourceRevision,
    private val readerFactory: (() -> ExperienceSourceReader)? = null,
    private val readWindow: (suspend (String, String, String?) -> ChatHistoryWindow?)? = null,
    private val readPage: (suspend (String, String, Boolean, String?) -> com.nekobot.app.data.local.ChatHistoryPage?)? = null
) : BaseViewModel() {
    private val _window = MutableStateFlow<ChatHistoryWindow?>(null)
    val window = _window.asStateFlow()
    private val _locationRevision = MutableStateFlow(0)
    val locationRevision = _locationRevision.asStateFlow()
    private var request = 0
    private var job: Job? = null
    private var readActive = false
    private var refreshPending = false
    private var requestedTarget: Triple<String, String, String?>? = null
    private val access = OriginalChatReadSession(viewModelScope, dataSourceRevision,
        if (readWindow == null || readerFactory != null) readerFactory ?: { ServiceContainer.localRepository.experienceSourceReader() } else null,
        { invalidate() }, ::refreshLoaded,
        { invalidate(R.string.experience_source_load_failed) })

    fun ownsCurrentDatabase(revision: Long = dataSourceRevision.value): Boolean = access.isCurrent(revision)
    fun ownsTarget(sessionId: String, messageId: String, archiveId: String?, revision: Long): Boolean =
        access.isCurrent(revision) && requestedTarget == Triple(sessionId, messageId, archiveId)

    private fun invalidate(reason: Int = R.string.experience_source_database_changed) {
        request++
        job?.cancel()
        job = null
        readActive = false
        refreshPending = false
        _window.value = null
        setLoading(false)
        showError(string(reason))
    }

    /** Initialize each requested location once; pagination survives backgrounding and return. */
    fun loadIfNeeded(sessionId: String, messageId: String, archiveId: String? = null) {
        if (!access.isCurrent()) invalidate()
        else if (requestedTarget != Triple(sessionId, messageId, archiveId)) load(sessionId, messageId, archiveId)
    }

    /** Retry and returning to an evicted anchor deliberately reload the original window. */
    fun load(sessionId: String, messageId: String, archiveId: String? = null) {
        if (!access.bind()) return
        requestedTarget = Triple(sessionId, messageId, archiveId)
        _window.value = null
        read(locate = true) {
            if (readWindow != null) readWindow.invoke(sessionId, messageId, archiveId)
            else access.reader!!.historyWindow(sessionId, messageId, archiveId)
        }
    }

    fun loadMore(older: Boolean) {
        if (!access.isCurrent()) { invalidate(); return }
        val current = _window.value ?: return
        if (loading.value || (if (older) !current.hasOlder else !current.hasNewer)) return
        val cursor = (if (older) current.messages.firstOrNull() else current.messages.lastOrNull())?.id ?: return
        read {
            val next = if (readPage != null) readPage.invoke(current.sessionId, cursor, older, current.archiveId)
                else access.reader?.historyPage(current.sessionId, cursor, older, current.archiveId)
            checkNotNull(next)
            val combined = (if (older) next.messages + current.messages else current.messages + next.messages).distinctBy { it.id }
            val trimmed = combined.size > MAX_MESSAGES
            val visible = if (older) combined.take(MAX_MESSAGES) else combined.takeLast(MAX_MESSAGES)
            val visibleIds = visible.mapTo(hashSetOf()) { it.id }
            val merged = current.copy(
                messages = visible,
                hasOlder = if (older) next.hasMore else current.hasOlder || trimmed,
                hasNewer = if (older) current.hasNewer || trimmed else next.hasMore,
                sourceMessageIds = (current.sourceMessageIds + next.sourceMessageIds).filterTo(hashSetOf()) { it in visibleIds }
            )
            if (access.reader == null) merged else access.reader!!.refreshHistory(merged)
        }
    }

    private fun refreshLoaded() {
        if (readActive) { refreshPending = true; return }
        val current = _window.value ?: return
        val reader = access.reader ?: return
        read(showBusy = false, clearOnFailure = true) { reader.refreshHistory(current) }
    }

    /** Revalidation updates text in place; only an explicit location read recenters the screen. */
    private fun read(locate: Boolean = false, showBusy: Boolean = true, clearOnFailure: Boolean = false, block: suspend () -> ChatHistoryWindow?) {
        val token = ++request
        job?.cancel()
        readActive = true
        refreshPending = false
        if (showBusy) clearError()
        setLoading(showBusy)
        job = viewModelScope.launch {
            try {
                access.awaitObserver()
                if (token != request || !access.isCurrent()) return@launch
                val version = access.sourceVersion
                refreshPending = false
                var result = block()
                if (token != request || !access.isCurrent()) return@launch
                if (version != access.sourceVersion && result != null && access.reader != null) {
                    val validatedVersion = access.sourceVersion
                    result = access.reader!!.refreshHistory(result)
                    if (validatedVersion == access.sourceVersion) refreshPending = false
                }
                if (token != request || !access.isCurrent()) return@launch
                _window.value = result
                if (result != null && locate) _locationRevision.value = token
                if (result == null) showError(string(R.string.chat_location_missing))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == request && access.isCurrent()) {
                    if (clearOnFailure) _window.value = null
                    showError(string(R.string.experience_source_load_failed))
                }
            } finally {
                if (token == request) {
                    job = null
                    readActive = false
                    setLoading(false)
                    if (refreshPending && access.isCurrent()) {
                        refreshPending = false
                        refreshLoaded()
                    }
                }
            }
        }
    }

    private companion object { const val MAX_MESSAGES = 300 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperienceSourceScreen(
    sessionId: String, archiveId: String, onBack: () -> Unit,
    onLocate: (String) -> Unit, viewModel: ExperienceSourceViewModel = viewModel()
) {
    val cachedPage by viewModel.page.collectAsStateWithLifecycle()
    val databaseRevision by viewModel.dataSourceRevision.collectAsStateWithLifecycle()
    val ownerCurrent = viewModel.ownsCurrentDatabase(databaseRevision)
    val page = cachedPage?.takeIf {
        viewModel.ownsTarget(sessionId, archiveId, databaseRevision) &&
            it.archive.sessionId == sessionId && it.archive.id == archiveId
    }
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val turns = remember(page?.messages) { sourceChatTurns(page?.messages.orEmpty()) }
    val listState = key(sessionId, archiveId) { rememberLazyListState() }
    LaunchedEffect(sessionId, archiveId) { viewModel.loadIfNeeded(sessionId, archiveId) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.experience_source_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
            actions = { IconButton(onClick = { viewModel.load(sessionId, archiveId) }, enabled = ownerCurrent) { Icon(Icons.Filled.Refresh, stringResource(R.string.experience_source_refresh)) } }
        )
    }) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!ownerCurrent) item("database-changed") {
                    Text(stringResource(R.string.experience_source_database_changed), color = MaterialTheme.colorScheme.error)
                } else error?.let { message -> item("error") { ErrorBanner(message = message, onRetry = { viewModel.load(sessionId, archiveId) }) } }
                page?.let { current ->
                    item("source") {
                        var summaryExpanded by rememberSaveable(archiveId) { mutableStateOf(false) }
                        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16, contentPadding = PaddingValues(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.experience_source_session, current.sessionName),
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                current.messages.firstOrNull()?.let { first ->
                                    OutlinedButton(onClick = {
                                        if (viewModel.ownsTarget(sessionId, archiveId, viewModel.dataSourceRevision.value)) onLocate(first.id)
                                    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(stringResource(R.string.experience_source_jump))
                                    }
                                }
                            }
                            Text(displaySourceTimeRange(current.archive.sourceStartedAt, current.archive.sourceEndedAt),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.experience_source_count, current.sourceCount, current.availableCount),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { summaryExpanded = !summaryExpanded },
                                modifier = Modifier.testTag("source-summary-toggle"), contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(if (summaryExpanded) R.string.experience_source_summary_collapse else R.string.experience_source_summary_expand))
                            }
                            if (summaryExpanded) SelectionContainer {
                                Text(current.archive.summary, modifier = Modifier.testTag("source-summary"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (current.archive.status == "stale") item("changed") {
                        Text(stringResource(R.string.experience_source_changed), color = MaterialTheme.colorScheme.error)
                    }
                    if (current.sourceCount > current.availableCount) item("missing") {
                        Text(stringResource(R.string.experience_source_missing_messages, current.sourceCount - current.availableCount), color = MaterialTheme.colorScheme.error)
                    }
                    item("originals") {
                        Text(stringResource(R.string.experience_source_originals), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.experience_source_expand_hint), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current.messages.isEmpty()) item("empty") {
                        EmptyState(title = stringResource(R.string.experience_source_empty), hint = if (current.sourceCount == 0) stringResource(R.string.experience_source_no_mapping) else null)
                    }
                    items(turns, key = { "source:${it.first().id}" }) { turn ->
                        SourceTurnCard(turn, current.assistantName)
                    }
                    if (current.hasMore) item("more") {
                        TextButton(onClick = viewModel::loadMore, enabled = !loading) { Text(stringResource(R.string.experience_source_load_more)) }
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLocationScreen(
    sessionId: String, messageId: String, onBack: () -> Unit, onLatest: () -> Unit,
    viewModel: ChatLocationViewModel = viewModel(), archiveId: String? = null
) {
    val cachedWindow by viewModel.window.collectAsStateWithLifecycle()
    val databaseRevision by viewModel.dataSourceRevision.collectAsStateWithLifecycle()
    val ownerCurrent = viewModel.ownsCurrentDatabase(databaseRevision)
    val window = cachedWindow?.takeIf {
        viewModel.ownsTarget(sessionId, messageId, archiveId, databaseRevision) &&
            it.sessionId == sessionId && it.anchorMessageId == messageId && it.archiveId == archiveId
    }
    val locationRevision by viewModel.locationRevision.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val listState = key(sessionId, messageId, archiveId) { rememberLazyListState() }
    var positionedRevision by rememberSaveable(sessionId, messageId, archiveId) { mutableStateOf<Int?>(null) }
    val highlightedIds = window?.let { if (it.archiveId == null) setOf(it.anchorMessageId) else it.sourceMessageIds }.orEmpty()
    val turns = remember(window?.messages, highlightedIds) { sourceChatTurns(window?.messages.orEmpty(), highlightedIds) }
    LaunchedEffect(sessionId, messageId, archiveId) { viewModel.loadIfNeeded(sessionId, messageId, archiveId) }
    LaunchedEffect(window?.anchorMessageId, locationRevision) {
        val current = window
        if (current == null) {
            positionedRevision = null
            return@LaunchedEffect
        }
        if (current.sessionId != sessionId || current.anchorMessageId != messageId || current.archiveId != archiveId ||
            positionedRevision == locationRevision) return@LaunchedEffect
        val index = turns.indexOfFirst { turn -> turn.any { it.id == current.anchorMessageId } }
        if (index >= 0) {
            listState.scrollToItem(index + 1)
            positionedRevision = locationRevision
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.chat_location_title))
                    window?.let { Text(it.sessionName, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
            actions = { TextButton(onClick = {
                if (viewModel.ownsCurrentDatabase()) onLatest()
            }, enabled = ownerCurrent) { Text(stringResource(R.string.chat_location_latest)) } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!ownerCurrent) Text(stringResource(R.string.experience_source_database_changed),
                modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else error?.let { ErrorBanner(message = it, onRetry = { viewModel.load(sessionId, messageId, archiveId) }) }
            window?.let { current ->
                if (current.messages.none { it.id == current.anchorMessageId }) {
                    TextButton(onClick = { viewModel.load(sessionId, messageId, archiveId) }) {
                        Text(stringResource(if (archiveId == null) R.string.chat_location_return_target else R.string.chat_location_return_segment))
                    }
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    window?.let { current ->
                        item("older") {
                            if (current.hasOlder) TextButton(onClick = { viewModel.loadMore(true) }, enabled = !loading) { Text(stringResource(R.string.chat_location_older)) }
                            else Text(stringResource(R.string.chat_location_start), style = MaterialTheme.typography.labelMedium)
                        }
                        items(turns, key = { "history:${it.first().id}" }) { turn ->
                            SourceTurnCard(
                                messages = turn, assistantName = current.assistantName,
                                highlighted = turn.first().id in highlightedIds,
                                targetLabel = if (turn.any { it.id == current.anchorMessageId })
                                    stringResource(if (current.archiveId == null) R.string.chat_location_target else R.string.chat_location_segment_target)
                                    else null
                            )
                        }
                        item("newer") {
                            if (current.hasNewer) TextButton(onClick = { viewModel.loadMore(false) }, enabled = !loading) { Text(stringResource(R.string.chat_location_newer)) }
                            else Text(stringResource(R.string.chat_location_end), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                LoadingOverlay(visible = loading)
            }
        }
    }
}

@Composable
private fun SourceTurnCard(
    messages: List<LocalMessageEntity>, assistantName: String?,
    highlighted: Boolean = false, targetLabel: String? = null
) {
    val shape = RoundedCornerShape(12.dp)
    var modifier = Modifier.fillMaxWidth().testTag("source-turn:${messages.first().id}")
    if (highlighted) modifier = modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
    val speakers = remember(messages, assistantName) { sourceSpeakerGroups(messages, assistantName) }
    Column(modifier.padding(vertical = 6.dp).then(if (highlighted) Modifier.padding(8.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        targetLabel?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(displaySourceTimeRange(messages), modifier = Modifier.weight(4f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        speakers.forEach { group ->
            val isUser = group.first().role == "user"
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                Text(if (isUser) stringResource(R.string.experience_source_you)
                    else sourceAssistantName(group.first(), assistantName) ?: stringResource(R.string.experience_source_ai),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                group.forEach { message ->
                    key(message.id) {
                        SourceMessageBubble(message, isUser)
                    }
                }
            }
        }
    }
}

/** Presentation-only clipping: the Text always receives the complete original body. */
@Composable
private fun SourceMessageBubble(message: LocalMessageEntity, isUser: Boolean) {
    var expanded by rememberSaveable(message.id, message.content) { mutableStateOf(false) }
    var overflows by remember(message.id, message.content) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.TopEnd else Alignment.TopStart) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth * 0.9f).testTag("source-bubble:${message.id}"),
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 5.dp, bottomEnd = if (isUser) 5.dp else 14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(message.content.ifBlank { stringResource(R.string.experience_source_no_text) },
                        modifier = Modifier.testTag("source-message:${message.id}"),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow })
                }
                if (overflows) {
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.testTag("source-expand:${message.id}"), contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (expanded) R.string.experience_source_original_collapse else R.string.experience_source_original_expand))
                    }
                }
            }
        }
    }
}

private fun displaySourceTimeRange(messages: List<LocalMessageEntity>): String {
    return displaySourceTimeRange(messages.first().createdAt, messages.last().createdAt)
}

private fun displaySourceTimeRange(startedAt: String, endedAt: String): String {
    val start = displaySourceTime(startedAt)
    val end = displaySourceTime(endedAt)
    return if (start == end) start else "$start — ${if (start.take(10) == end.take(10)) end.takeLast(8) else end}"
}

private fun displaySourceTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrElse { value.replace('T', ' ').take(19) }
