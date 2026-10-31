package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionMemoryOverview
import com.nekobot.app.data.local.CompanionEmbeddingSettings
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.ai.CompanionMemorySourceView
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun FusedCompanionMemoryScreen(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    CompanionMemoryTheme { CompanionMemoryDetail(sessionId, onBack, onLocate) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CompanionMemoryDetail(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    val textResources = LocalResources.current
    val repo = remember { ServiceContainer.localRepository }
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<CompanionMemoryEntity>()) }
    var linkedMemoryIds by remember { mutableStateOf(emptySet<String>()) }
    var overview by remember { mutableStateOf<CompanionMemoryOverview?>(null) }
    var ownerName by remember { mutableStateOf("") }
    var ownerAvatar by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable(sessionId) { mutableStateOf("") }
    var category by rememberSaveable(sessionId) { mutableStateOf("") }
    var coreOnly by rememberSaveable(sessionId) { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var versions by remember { mutableStateOf<List<CompanionMemoryEntity>?>(null) }
    var versionKey by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<CompanionMemorySourceView>?>(null) }
    var sourceMemory by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var deletion by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var ready by remember { mutableStateOf(false) }
    var recallSettingsOpen by rememberSaveable(sessionId, dataSourceRevision, appMode) { mutableStateOf(false) }
    var recallSettings by remember { mutableStateOf(repo.companionRecallSettings(sessionId)) }
    var embeddingSettings by remember(sessionId, dataSourceRevision, appMode) { mutableStateOf<CompanionEmbeddingSettings?>(null) }
    var addingEmbeddingModel by rememberSaveable { mutableStateOf(false) }
    var addedEmbeddingId by remember { mutableStateOf<String?>(null) }
    val categories = memoryCategoryNames
    LaunchedEffect(sessionId, dataSourceRevision, appMode) {
        records = emptyList()
        linkedMemoryIds = emptySet()
        overview = null
        ownerName = ""
        ownerAvatar = null
        versions = null
        sources = null
        sourceMemory = null
        editor = false
        deletion = null
        selected = null
        actionError = null
        embeddingSettings = null
        addingEmbeddingModel = false
        addedEmbeddingId = null
    }
    LaunchedEffect(sessionId, refresh, dataSourceRevision, appMode) {
        ready = false
        error = null
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { textResources.getString(R.string.single_role_the_data_profile_changed_reopen_the_memory_page) }
            val session = repo.getSession(sessionId)
            check(session?.companionMode == true) { textResources.getString(R.string.single_role_this_page_only_manages_independent_memories_for_the_current_singl) }
            val loadedRecords = repo.listCompanionMemories(sessionId)
            val loadedSourceIds = repo.companionMemoriesWithSources(sessionId)
            val loadedOverview = repo.companionMemoryOverview(sessionId)
            val loadedEmbedding = repo.companionEmbeddingSettings(sessionId)
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { textResources.getString(R.string.single_role_the_data_profile_changed_reopen_the_memory_page) }
            records = loadedRecords
            linkedMemoryIds = loadedSourceIds
            overview = loadedOverview
            embeddingSettings = loadedEmbedding
            ownerName = com.nekobot.app.data.model.sessionTitle(session.listTitleOverride, session.characterName?.takeIf { it.isNotBlank() } ?: session.name.orEmpty())
            ownerAvatar = session.portrait?.takeIf(String::isNotBlank) ?: session.characterAvatar
            error = null
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            records = emptyList(); linkedMemoryIds = emptySet(); overview = null; ownerName = ""
            error = failure.message ?: textResources.getString(R.string.single_role_memories_cannot_be_loaded_right_now_please_try_again)
        }
    }
    LaunchedEffect(sessionId) {
        repo.experienceSourceReader().changes().collect { refresh++ }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { textResources.getString(R.string.single_role_the_data_profile_changed_reopen_this_page) }
            block()
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { textResources.getString(R.string.single_role_the_data_profile_changed_reopen_this_page) }
            refresh++
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (repo !== ServiceContainer.localRepository || !ServiceContainer.prefs.isLocalMode) {
                records = emptyList(); overview = null; ownerName = ""
                versions = null; sources = null; editor = false; deletion = null; ready = false
            }
            actionError = failure.message ?: textResources.getString(R.string.single_role_this_action_did_not_complete_please_try_again)
        }
        finally { busy = false }
    } }
    val visibleRecords = records.filter { it.current || it.status in setOf("conflict", "locked_conflict") }
    val coreRecords = CompanionCoreMemory.rows(records)
    val coreTokens = CompanionCoreMemory.tokens(coreRecords)
    val filteredRecords = if (coreOnly) coreRecords else visibleRecords
    val shown = filteredRecords.filter { (category.isEmpty() || it.category == category) &&
        (query.isBlank() || "${it.content} ${it.tagsJson} ${it.factKey}".contains(query, true)) }
    val editable = ready && !busy && error == null && repo === ServiceContainer.localRepository
    val loadedEmbedding = embeddingSettings
    if (recallSettingsOpen && loadedEmbedding != null) {
        CompanionRecallSettingsScreen(sessionId, ownerName, ownerAvatar, recallSettings, busy, actionError,
            onBack = { recallSettingsOpen = false; actionError = null; addedEmbeddingId = null }, onSave = { rounds, tokens, perCall, enabled, modelId, consolidation -> action {
                repo.saveCompanionMemorySettings(sessionId, rounds, tokens, perCall, enabled, modelId, consolidation)
                recallSettings = repo.companionRecallSettings(sessionId)
                embeddingSettings = repo.companionEmbeddingSettings(sessionId)
                recallSettingsOpen = false
                addedEmbeddingId = null
            } }, embedding = loadedEmbedding, onAddModel = { addingEmbeddingModel = true }, addedModelId = addedEmbeddingId,
            initialConsolidation = repo.companionConsolidationSettings(sessionId))
        if (addingEmbeddingModel) CompanionEmbeddingModelEditor(repo, onDismiss = { addingEmbeddingModel = false }, onAdded = { id -> action {
            embeddingSettings = repo.companionEmbeddingSettings(sessionId)
            addedEmbeddingId = id
            addingEmbeddingModel = false
        } })
        return
    }
    BackHandler(enabled = coreOnly && !editor && versions == null && sources == null && deletion == null) { coreOnly = false }
    Scaffold(topBar = { TopAppBar(title = { Text(if (coreOnly) textResources.getString(R.string.single_role_core_memories) else stringResource(R.string.agent_character_memory_title),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
        IconButton(onClick = { if (coreOnly) coreOnly = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back)) }
    }, actions = {
        IconButton(enabled = editable, onClick = { action { versionKey = null; versions = repo.fusedMemoryHistory(sessionId) } }) {
            Icon(Icons.Filled.History, textResources.getString(R.string.single_role_revision_history))
        }
        IconButton(enabled = editable && embeddingSettings != null, onClick = { recallSettings = repo.companionRecallSettings(sessionId); actionError = null; recallSettingsOpen = true }) {
            Icon(Icons.Filled.Tune, textResources.getString(R.string.single_role_memory_settings))
        }
        IconButton(enabled = editable,
        onClick = { selected = null; actionError = null; editor = true }) { Icon(Icons.Filled.Add, textResources.getString(R.string.memory_add_title)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("fused-memory-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!coreOnly) item("overview") {
                CompanionMemoryOverviewCard(ownerName, overview, visibleRecords.size,
                    coreTokens,
                    loading = !ready && error == null, onRefresh = { refresh++ })
            }
            if (ready) item("core-entry") {
                Surface(shape = RoundedCornerShape(18.dp), color = memoryCategoryColor("relationship").copy(alpha = 0.09f)) {
                    Column(Modifier.fillMaxWidth().then(if (!coreOnly) Modifier.clickable {
                        coreOnly = true; category = ""; query = ""
                    } else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CoreMemoryIcon(Modifier.size(36.dp))
                            Text(if (coreOnly) textResources.getString(R.string.single_role_1_s_core_memories, coreRecords.size) else textResources.getString(R.string.single_role_core_memories), Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (!coreOnly) { Text(textResources.getString(R.string.single_role_1_s_items, coreRecords.size)); Icon(Icons.Filled.ChevronRight, textResources.getString(R.string.single_role_view_core_memories)) }
                        }
                        Text(if (coreOnly) textResources.getString(R.string.single_role_these_memories_are_included_in_every_chat_use_the_top_right_menu)
                            else textResources.getString(R.string.single_role_review_and_adjust_important_names_relationships_and_habits_at_any), style = MaterialTheme.typography.bodySmall)
                        if (coreOnly) Text(textResources.getString(R.string.single_role_about_1_s_tokens_does_not_use_recall_quota, coreTokens), style = MaterialTheme.typography.labelMedium)
                        if (coreTokens > CompanionCoreMemory.maintenanceTokens) Text(
                            textResources.getString(R.string.single_role_core_memories_exceed_the_target_size_and_will_still_be_included_i),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { message -> item("error") {
                CompanionMemoryNotice(textResources.getString(R.string.single_role_memories_temporarily_unavailable), message) { refresh++ }
            } }
            if (!editor && deletion == null) actionError?.let { message -> item("action-error") {
                CompanionMemoryNotice(textResources.getString(R.string.single_role_this_action_did_not_complete), message) { actionError = null; refresh++ }
            } }
            if (busy && !editor && deletion == null) item("action-loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item("filters") {
                CompanionMemoryFilters(query, { query = it }, category, { category = it },
                    filteredRecords.groupingBy { it.category }.eachCount(), filteredRecords.size, shown.size)
            }
            if (shown.isEmpty() && ready && error == null) item("empty") {
                if (coreOnly && coreRecords.isEmpty()) Text(textResources.getString(R.string.single_role_no_core_memories_yet_return_to_all_memories_and_choose_set_as_cor),
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else CompanionMemoryEmpty(query.isNotBlank() || category.isNotBlank())
            }
            items(shown, key = { it.id }) { memory ->
                CompanionMemoryCard(memory, enabled = editable, hasSources = memory.id in linkedMemoryIds,
                    onSource = { action { sources = repo.fusedMemorySourceViews(sessionId, memory.id); sourceMemory = memory } },
                    onEdit = { selected = memory; actionError = null; editor = true },
                    onVersions = { action { versionKey = memory.factKey; versions = repo.fusedMemoryVersions(sessionId, memory.factKey) } },
                    onDelete = { actionError = null; deletion = memory },
                    onCoreChange = { action { repo.setFusedMemoryCore(sessionId, memory.factKey, !CompanionCoreMemory.contains(memory)) } })
            }
        }
    }
    if (editor) {
        CompanionMemoryEditor(selected, busy, actionError, onDismiss = { editor = false; actionError = null }) {
                content, type, tags, pinned, onlyMe -> action {
            repo.saveFusedMemory(sessionId, selected?.factKey, content, type, tags, pinned, onlyMe)
            editor = false
        } }
    }
    versions?.takeIf { sources == null }?.let { rows -> CompanionMemoryChanges(rows, busy, actionError,
        onDismiss = { versions = null; actionError = null },
        onRestore = { memory -> action {
            repo.restoreFusedMemory(sessionId, memory.id)
            versions = versionKey?.let { repo.fusedMemoryVersions(sessionId, it) } ?: repo.fusedMemoryHistory(sessionId)
        } }, onSource = { memory -> action { sources = repo.fusedMemorySourceViews(sessionId, memory.id); sourceMemory = memory } }) }
    sources?.let { rows -> CompanionMemorySources(rows, sourceMemory, ownerName,
        onDismiss = { sources = null }, onLocate = { messageId -> sources = null; onLocate(messageId) }) }
    deletion?.let { memory -> CompanionMemoryDelete(memory, busy, actionError,
        onDismiss = { deletion = null; actionError = null },
        onDelete = { action { repo.deleteFusedMemory(sessionId, memory.factKey); deletion = null } }) }
}
