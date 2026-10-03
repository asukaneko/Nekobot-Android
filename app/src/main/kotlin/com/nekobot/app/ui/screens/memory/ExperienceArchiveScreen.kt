package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ExperienceBackfillInfo
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalExperienceArchiveJobEntity
import com.nekobot.app.data.model.LegacyMemory
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A retained archive list and its actions belong to the database in which it was opened. */
class ExperienceArchiveViewModel(
    val dataSourceRevision: StateFlow<Long> = ServiceContainer.dataSourceRevision
) : BaseViewModel() {
    private val ownerRevision = dataSourceRevision.value
    private val ownerRepository: LocalRepository = ServiceContainer.localRepository
    private val _archives = MutableStateFlow<List<LocalExperienceArchiveEntity>>(emptyList())
    val archives: StateFlow<List<LocalExperienceArchiveEntity>> = _archives.asStateFlow()

    private val _characterMemories = MutableStateFlow<List<LegacyMemory>>(emptyList())
    val characterMemories: StateFlow<List<LegacyMemory>> = _characterMemories.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _backfillInfo = MutableStateFlow<ExperienceBackfillInfo?>(null)
    val backfillInfo: StateFlow<ExperienceBackfillInfo?> = _backfillInfo.asStateFlow()
    private val _backfillJob = MutableStateFlow<LocalExperienceArchiveJobEntity?>(null)
    val backfillJob: StateFlow<LocalExperienceArchiveJobEntity?> = _backfillJob.asStateFlow()
    private var runningBackfill: Job? = null
    private val _historyCopyRunning = MutableStateFlow(false)
    val historyCopyRunning: StateFlow<Boolean> = _historyCopyRunning.asStateFlow()
    private val _historyCopyProgress = MutableStateFlow<Long?>(null)
    val historyCopyProgress: StateFlow<Long?> = _historyCopyProgress.asStateFlow()

    private var currentSessionId: String? = null

    fun ownsCurrentDatabase(revision: Long = dataSourceRevision.value): Boolean = ownerRevision == revision &&
        runCatching { ownerRepository === ServiceContainer.localRepository }.getOrDefault(false)

    init {
        viewModelScope.launch { dataSourceRevision.collect {
            if (!ownsCurrentDatabase()) {
                runningBackfill?.cancel()
                currentSessionId = null
                _archives.value = emptyList()
                _characterMemories.value = emptyList()
                _hasMore.value = false
                _backfillInfo.value = null
                _backfillJob.value = null
                _historyCopyRunning.value = false
                _historyCopyProgress.value = null
                setLoading(false)
            }
        } }
    }

    fun load(sessionId: String) {
        if (!ownsCurrentDatabase() || sessionId.isBlank()) return
        val local = ownerRepository
        currentSessionId = sessionId
        launchResult(
            block = {
                val session = local.getSession(sessionId)
                val page = local.listExperienceArchives(sessionId, PAGE_SIZE, 0)
                val info = local.experienceBackfillInfo(sessionId)
                val memories = if (session?.inheritCharacter == true && !session.characterId.isNullOrBlank()) {
                    local.listMemories(session.characterId).filter { it.targetId == "local-user" }
                } else {
                    emptyList()
                }
                Resource.Success(Triple(page, memories, info))
            },
            onSuccess = { (page, memories, info) ->
                if (!ownsCurrentDatabase() || currentSessionId != sessionId) return@launchResult
                _archives.value = page
                _characterMemories.value = memories
                _hasMore.value = page.size == PAGE_SIZE
                _backfillInfo.value = info
                if (runningBackfill?.isActive != true) _backfillJob.value = info.job
            }
        )
    }

    /** Called only after the explicit cost confirmation dialog has been accepted. */
    fun startBackfill() {
        if (!ownsCurrentDatabase()) return
        val sessionId = currentSessionId ?: return
        val local = ownerRepository
        if (runningBackfill?.isActive == true) return
        runningBackfill = viewModelScope.launch {
            try {
                local.runExperienceBackfill(sessionId) { job ->
                    if (ownsCurrentDatabase()) _backfillJob.value = job
                }
                load(sessionId)
            } catch (_: CancellationException) {
                // Repository persists paused state in NonCancellable before rethrowing.
            } catch (error: Exception) {
                showError(error.message ?: "经历整理失败")
            }
        }
    }

    fun pauseBackfill() { runningBackfill?.cancel() }

    /** Rebuilds a bounded read-only copy on demand; never calls an AI model. */
    fun rebuildHistoryCopy() {
        if (!ownsCurrentDatabase()) return
        val sessionId = currentSessionId ?: return
        val local = ownerRepository
        if (_historyCopyRunning.value) return
        viewModelScope.launch {
            _historyCopyRunning.value = true
            _historyCopyProgress.value = 0L
            try {
                val result = local.rebuildWorkspaceHistoryCopy(sessionId) { count ->
                    if (ownsCurrentDatabase()) _historyCopyProgress.value = count
                }
                if (ownsCurrentDatabase()) showToast(string(R.string.experience_history_copy_done, result.messageCount))
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.experience_history_copy_failed))
            } finally {
                _historyCopyRunning.value = false
            }
        }
    }

    fun loadMore() {
        if (!ownsCurrentDatabase()) return
        val sessionId = currentSessionId ?: return
        val local = ownerRepository
        if (!_hasMore.value || loading.value) return
        val offset = _archives.value.size
        launchResult(
            block = {
                Resource.Success(
                    local.listExperienceArchives(sessionId, PAGE_SIZE, offset)
                )
            },
            onSuccess = { page ->
                if (!ownsCurrentDatabase() || currentSessionId != sessionId) return@launchResult
                _archives.value = (_archives.value + page).distinctBy { it.id }
                _hasMore.value = page.size == PAGE_SIZE
            }
        )
    }

    fun save(archiveId: String, summary: String, tags: List<String>) {
        if (!ownsCurrentDatabase()) return
        val sessionId = currentSessionId ?: return
        val local = ownerRepository
        launchResult(
            block = {
                Resource.Success(
                    local.updateExperienceArchive(archiveId, summary, tags)
                )
            },
            onSuccess = { saved ->
                if (!ownsCurrentDatabase()) return@launchResult
                if (saved) {
                    load(sessionId)
                    showToast(string(R.string.experience_archive_saved))
                } else {
                    showError(string(R.string.experience_archive_missing))
                }
            }
        )
    }

    private companion object { const val PAGE_SIZE = 60 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperienceArchiveScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit = {},
    viewModel: ExperienceArchiveViewModel = viewModel()
) {
    val databaseRevision by viewModel.dataSourceRevision.collectAsStateWithLifecycle()
    if (!viewModel.ownsCurrentDatabase(databaseRevision)) {
        Scaffold(topBar = { TopAppBar(
            title = { Text(stringResource(R.string.experience_archive_title)) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
            } }
        ) }) { padding -> Text(stringResource(R.string.experience_source_database_changed),
            modifier = Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.error) }
        return
    }
    val archives by viewModel.archives.collectAsStateWithLifecycle()
    val memories by viewModel.characterMemories.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val backfillInfo by viewModel.backfillInfo.collectAsStateWithLifecycle()
    val backfillJob by viewModel.backfillJob.collectAsStateWithLifecycle()
    val historyCopyRunning by viewModel.historyCopyRunning.collectAsStateWithLifecycle()
    val historyCopyProgress by viewModel.historyCopyProgress.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocalExperienceArchiveEntity?>(null) }
    var showBackfillConfirm by remember { mutableStateOf(false) }
    val tags = remember(archives, memories) {
        (archives.asSequence().flatMap { MemoryTags.fromJson(it.tagsJson).asSequence() } +
            memories.asSequence().flatMap { it.tags.orEmpty().asSequence() })
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }
    val visibleArchives = remember(archives, selectedTag) {
        archives.filter { archive ->
            selectedTag == null || MemoryTags.fromJson(archive.tagsJson)
                .any { it.equals(selectedTag, ignoreCase = true) }
        }
    }
    val relatedMemories = remember(memories, selectedTag) {
        if (selectedTag == null) emptyList() else memories.filter { memory ->
            memory.tags.orEmpty().any { it.equals(selectedTag, ignoreCase = true) }
        }
    }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    LaunchedEffect(tags, selectedTag) {
        if (selectedTag != null && tags.none { it.equals(selectedTag, ignoreCase = true) }) {
            selectedTag = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.experience_archive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load(sessionId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.memory_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "backfill") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
                        Text(stringResource(R.string.experience_backfill_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.experience_backfill_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        backfillJob?.let { job ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(
                                    R.string.experience_backfill_progress,
                                    job.status,
                                    job.processedCount,
                                    job.totalCount ?: backfillInfo?.messageCount ?: 0,
                                    job.generatedCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (job.status == "running") {
                                LinearProgressIndicator(
                                    progress = { (job.processedCount.toFloat() /
                                        (job.totalCount ?: backfillInfo?.messageCount ?: 1).coerceAtLeast(1))
                                        .coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            job.error?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (backfillJob?.status == "running") {
                            TextButton(onClick = viewModel::pauseBackfill) {
                                Text(stringResource(R.string.experience_backfill_pause))
                            }
                        } else {
                            Button(
                                onClick = { showBackfillConfirm = true },
                                enabled = backfillInfo != null
                            ) {
                                Text(stringResource(if (backfillJob?.status == "paused" || backfillJob?.status == "failed")
                                    R.string.experience_backfill_resume else R.string.experience_backfill_start))
                            }
                        }
                    }
                }
                item(key = "history_copy") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
                        Text(stringResource(R.string.experience_history_copy_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.experience_history_copy_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        historyCopyProgress?.let { count ->
                            Text(stringResource(R.string.experience_history_copy_progress, count))
                        }
                        TextButton(onClick = viewModel::rebuildHistoryCopy, enabled = !historyCopyRunning) {
                            Text(stringResource(R.string.experience_history_copy_rebuild))
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    item(key = "tags") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTag == null,
                                onClick = { selectedTag = null },
                                label = { Text(stringResource(R.string.memory_tag_all)) }
                            )
                            tags.forEach { tag ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = { selectedTag = tag },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
                if (error != null) {
                    item(key = "error") {
                        ErrorBanner(
                            message = error!!,
                            onRetry = { viewModel.clearError(); viewModel.load(sessionId) }
                        )
                    }
                }
                if (!loading && archives.isEmpty() && relatedMemories.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            Modifier.fillMaxWidth().fillParentMaxHeight(0.55f),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                title = stringResource(R.string.experience_archive_empty),
                                hint = stringResource(R.string.experience_archive_empty_hint)
                            )
                        }
                    }
                } else {
                    if (visibleArchives.isNotEmpty()) {
                        item(key = "archives_header") {
                            SectionHeader(
                                title = stringResource(R.string.experience_archive_entries),
                                subtitle = stringResource(R.string.memory_count_format, visibleArchives.size)
                            )
                        }
                        items(visibleArchives, key = { it.id }) { archive ->
                            ExperienceArchiveCard(
                                archive = archive,
                                onEdit = { editing = archive },
                                onOpenSource = { if (viewModel.ownsCurrentDatabase()) onOpenSource(archive.id) },
                                onTagClick = { selectedTag = it }
                            )
                        }
                    }
                    if (relatedMemories.isNotEmpty()) {
                        item(key = "memories_header") {
                            SectionHeader(
                                title = stringResource(R.string.experience_archive_related_memories),
                                subtitle = stringResource(R.string.memory_count_format, relatedMemories.size)
                            )
                        }
                        items(relatedMemories, key = { "memory_${it.id}" }) { memory ->
                            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
                                Text(memory.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    memory.summary?.takeIf(String::isNotBlank) ?: memory.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (visibleArchives.isEmpty() && relatedMemories.isEmpty() && archives.isNotEmpty()) {
                        item(key = "no_match") { Text(stringResource(R.string.memory_no_match)) }
                    }
                    if (hasMore) {
                        item(key = "load_more") {
                            TextButton(onClick = viewModel::loadMore, enabled = !loading) {
                                Text(stringResource(R.string.experience_archive_load_more))
                            }
                        }
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    editing?.let { archive ->
        ExperienceArchiveEditDialog(
            archive = archive,
            onDismiss = { editing = null },
            onSave = { summary, tags ->
                viewModel.save(archive.id, summary, tags)
                editing = null
            }
        )
    }
    if (showBackfillConfirm) {
        val info = backfillInfo
        if (info != null) {
            NekoDialog(
                onDismiss = { showBackfillConfirm = false },
                title = stringResource(R.string.experience_backfill_confirm_title),
                confirmText = stringResource(R.string.experience_backfill_confirm),
                cancelText = stringResource(R.string.common_cancel),
                onConfirm = {
                    showBackfillConfirm = false
                    viewModel.startBackfill()
                },
                onCancel = { showBackfillConfirm = false }
            ) {
                Text(
                    stringResource(
                        R.string.experience_backfill_cost_notice,
                        info.messageCount,
                        info.modelName ?: stringResource(R.string.experience_backfill_unknown),
                        info.inputPricePerMillionUsd?.toString() ?: stringResource(R.string.experience_backfill_unknown),
                        info.outputPricePerMillionUsd?.toString() ?: stringResource(R.string.experience_backfill_unknown)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ExperienceArchiveCard(
    archive: LocalExperienceArchiveEntity,
    onEdit: () -> Unit,
    onOpenSource: () -> Unit,
    onTagClick: (String) -> Unit
) {
    val tags = remember(archive.tagsJson) { MemoryTags.fromJson(archive.tagsJson) }
    val status = when (archive.status) {
        "ready" -> stringResource(R.string.experience_archive_ready)
        "stale" -> stringResource(R.string.experience_archive_stale)
        "processing" -> stringResource(R.string.experience_archive_processing)
        "failed" -> stringResource(R.string.experience_archive_failed)
        else -> archive.status
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
        Text(
            "${archive.sourceStartedAt.take(16).replace('T', ' ')} – ${archive.sourceEndedAt.take(16).replace('T', ' ')}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(archive.summary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.experience_archive_status, status),
            style = MaterialTheme.typography.labelSmall,
            color = if (archive.status == "stale" || archive.status == "failed")
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(selected = false, onClick = { onTagClick(tag) }, label = { Text(tag) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.experience_archive_source_range, archive.startMessageId, archive.endMessageId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSource) {
                Text(stringResource(R.string.experience_source_open))
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

@Composable
private fun ExperienceArchiveEditDialog(
    archive: LocalExperienceArchiveEntity,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var summary by remember(archive.id) { mutableStateOf(archive.summary) }
    var tagDraft by remember(archive.id) {
        mutableStateOf(MemoryTags.fromJson(archive.tagsJson).joinToString("，"))
    }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.experience_archive_edit),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            if (summary.isBlank()) return@NekoDialog
            val tags = tagDraft.split(',', '，', ';', '；', '\n')
            onSave(summary.trim(), MemoryTags.normalize(tags))
        },
        onCancel = onDismiss
    ) {
        Text(stringResource(R.string.experience_archive_summary), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = summary,
            onValueChange = { summary = it },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.experience_archive_tags), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = tagDraft,
            onValueChange = { tagDraft = it },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.memory_tags_separator_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
