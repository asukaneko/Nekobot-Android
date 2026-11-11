package com.nekobot.app.ui.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.CompanionTransferJobEntity
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.*

internal data class CompanionCreateState(
    val characters: List<CharacterPreset> = emptyList(), val selectedId: String? = null,
    val eligibility: CompanionEligibility? = null, val busy: Boolean = false,
    val preview: CompanionTransferPreview? = null, val progress: CompanionTransferProgress? = null,
    val error: String? = null, val details: String? = null, val completed: String? = null,
    val errorReport: String? = null,
    val records: List<CompanionTransferJobEntity> = emptyList(),
    val recordInfo: Map<String, CompanionTransferRecordInfo> = emptyMap(),
    val models: List<CompanionConversionModel> = emptyList(), val conversionModelId: String? = null
)

internal class CompanionCreateViewModel : ViewModel() {
    var state by mutableStateOf(CompanionCreateState()); private set
    private val repo = ServiceContainer.localRepository
    private var operation: Job? = null
    init { reload() }
    private fun active() { check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { ServiceContainer.getString(R.string.companion_profile_changed) } }
    private fun launch(clearError: Boolean = true, block: suspend () -> Unit) {
        if (state.busy) return
        state = if (clearError) state.copy(busy = true, error = null, details = null, errorReport = null)
            else state.copy(busy = true)
        operation = viewModelScope.launch {
            try { active(); block(); active() }
            catch (_: CancellationException) { state = state.copy(progress = null,
                error = if (state.preview != null) ServiceContainer.getString(R.string.companion_conversion_stopped) else null) }
            catch (failure: Exception) {
                val readable = when (failure) {
                    is CompanionCreationException -> ServiceContainer.getString(failure.messageRes)
                    is IllegalArgumentException, is IllegalStateException -> failure.message ?: ServiceContainer.getString(R.string.companion_operation_failed)
                    else -> ServiceContainer.getString(R.string.companion_operation_failed)
                }
                runCatching { reloadRecords() }
                state = state.copy(error = readable, details = (failure as? CompanionConversionCallException)?.technicalDetail ?: failure.toString(),
                    errorReport = (failure as? CompanionConversionCallException)?.diagnosticReport, progress = null)
            } finally { state = state.copy(busy = false) }
        }
    }
    fun reload() = launch(clearError = false) {
        val choices = loadCompanionCharacterChoices(repo.listCharacters(), repo::companionCreationEligibility)
        val selected = choices.firstOrNull { it.character.id == state.selectedId }
        reloadRecords()
        state = state.copy(characters = choices.map { it.character },
            selectedId = selected?.character?.id, eligibility = selected?.eligibility,
            models = repo.companionConversionModels(), conversionModelId = repo.selectedCompanionConversionModel())
    }
    private suspend fun reloadRecords() {
        val records = repo.companionTransferRecords()
        state = state.copy(records = records, recordInfo = repo.companionTransferRecordInfo(records))
    }
    fun select(id: String) = launch {
        // 列表打开后资格也可能变化；重新检查，避免使用过期选项。
        val choice = loadCompanionCharacterChoices(state.characters.filter { it.id == id },
            repo::companionCreationEligibility).singleOrNull()
        state = state.copy(
            characters = if (choice == null) state.characters.filterNot { it.id == id } else state.characters,
            selectedId = choice?.character?.id, eligibility = choice?.eligibility, preview = null, completed = null)
    }
    fun preview(sourceId: String) = launch {
        val preview = repo.previewCompanionTransfer(sourceId)
        state = state.copy(preview = preview, errorReport = repo.companionConversionErrorReports(preview.jobId))
    }
    fun restore(jobId: String) = launch {
        val preview = repo.companionTransferPreview(jobId)
        state = state.copy(preview = preview, completed = preview.completedSessionId,
            errorReport = repo.companionConversionErrorReports(jobId))
    }
    fun create(name: String, greeting: String, open: (String) -> Unit) = launch {
        val result = ServiceContainer.unified.createSession(CreateSessionRequest(
            name = name.takeIf(String::isNotBlank), characterId = state.selectedId,
            sessionMode = "agent", companionMode = true, firstMessage = greeting.takeIf(String::isNotBlank),
            relationshipStateSource = "initial"
        ))
        when (result) {
            is Resource.Success -> open(requireNotNull(result.data.id))
            is Resource.Error -> { state = state.copy(error = result.message); state = state.copy(eligibility = repo.companionCreationEligibility(state.selectedId)) }
            else -> Unit
        }
    }
    fun transfer(name: String) = launch {
        val preview = requireNotNull(state.preview)
        val target = repo.convertCompanionTransfer(preview.jobId, name, requireNotNull(state.conversionModelId)) { progress ->
            // IO 回调切回 ViewModel 主协程，避免跨线程写入 Compose 状态。
            viewModelScope.launch { state = state.copy(progress = progress) }
        }
        state = state.copy(completed = target, progress = null)
        reloadRecords()
    }
    fun selectModel(id: String) {
        if (state.busy || state.models.none { it.id == id }) return
        repo.selectCompanionConversionModel(id)
        state = state.copy(conversionModelId = id)
    }
    fun cancel() { operation?.cancel() }
    fun backToSelection() { state = state.copy(preview = null, completed = null, progress = null, error = null, details = null, errorReport = null) }
    fun deleteBackup(jobId: String) = launch { repo.deleteCompanionTransferBackup(jobId); reloadRecords() }
    fun deleteRecord(jobId: String) = launch {
        repo.deleteCompanionTransferRecord(jobId)
        if (state.preview?.jobId == jobId) backToSelection()
        reloadRecords()
    }
    suspend fun receipt(jobId: String): String { active(); return repo.companionTransferReceipt(jobId) }
}

@Composable
internal fun CompanionCreateScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNewCharacter: () -> Unit, onModelSettings: () -> Unit = {}) {
    val vm: CompanionCreateViewModel = viewModel()
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val initialRevision = remember { revision }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.reload() }
    val scope = rememberCoroutineScope()
    var receipt by remember { mutableStateOf<String?>(null) }
    var backupToDelete by remember { mutableStateOf<String?>(null) }
    var recordToDelete by remember { mutableStateOf<CompanionTransferJobEntity?>(null) }
    var configuration by rememberSaveable { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(revision, appMode) { if (revision != initialRevision || !ServiceContainer.prefs.isLocalMode) { vm.cancel(); onBack() } }
    com.nekobot.app.ui.screens.memory.CompanionMemoryTheme {
    CompanionCreateContent(vm.state, onBack = { vm.cancel(); onBack() }, onSelect = vm::select,
        onPreview = vm::preview, onCreate = { name, greeting -> vm.create(name, greeting, onOpen) },
        onTransfer = vm::transfer, onOpen = onOpen, onCancel = vm::cancel, onNewCharacter = onNewCharacter,
        onBackToSelection = vm::backToSelection, onRestore = vm::restore,
        onReceipt = { id -> scope.launch { receipt = runCatching { vm.receipt(id) }.getOrElse { it.message.orEmpty() } } },
        onDeleteBackup = { backupToDelete = it }, onConfigure = { configuration = true }, onRules = { rules = true },
        onDeleteRecord = { id -> recordToDelete = vm.state.records.firstOrNull { it.id == id } })
    if (configuration) CompanionConversionModelPicker(vm.state.models, vm.state.conversionModelId,
        onSelect = { vm.selectModel(it); configuration = false }, onDismiss = { configuration = false },
        onManage = { configuration = false; onModelSettings() })
    if (rules) CompanionConversionRulesSheet { rules = false }
    }
    receipt?.let { body -> AlertDialog(onDismissRequest = { receipt = null },
        title = { Text(stringResource(R.string.companion_transfer_record)) },
        text = { LazyColumn { item { androidx.compose.foundation.text.selection.SelectionContainer { Text(body) } } } },
        confirmButton = { TextButton(onClick = { receipt = null }) { Text(stringResource(R.string.common_close)) } }) }
    backupToDelete?.let { id -> AlertDialog(onDismissRequest = { backupToDelete = null },
        title = { Text(stringResource(R.string.companion_delete_backup)) }, text = { Text(stringResource(R.string.companion_delete_backup_hint)) },
        confirmButton = { TextButton(onClick = { backupToDelete = null; vm.deleteBackup(id) }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { backupToDelete = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    recordToDelete?.let { job -> CompanionTransferDeleteDialog(job, vm.state.recordInfo[job.id],
        onDismiss = { recordToDelete = null }, onConfirm = { recordToDelete = null; vm.deleteRecord(job.id) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionCreateContent(
    state: CompanionCreateState, onBack: () -> Unit, onSelect: (String) -> Unit,
    onPreview: (String) -> Unit, onCreate: (String, String) -> Unit, onTransfer: (String) -> Unit,
    onOpen: (String) -> Unit, onCancel: () -> Unit, onNewCharacter: () -> Unit,
    onBackToSelection: () -> Unit, onRestore: (String) -> Unit, onReceipt: (String) -> Unit,
    onDeleteBackup: (String) -> Unit, onConfigure: () -> Unit = {}, onRules: () -> Unit = {},
    onDeleteRecord: (String) -> Unit = {}
) {
    var picker by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(state.selectedId, state.preview?.jobId) { mutableStateOf(state.preview?.name.orEmpty()) }
    var greeting by rememberSaveable(state.selectedId) { mutableStateOf("") }
    val selected = state.characters.firstOrNull { it.id == state.selectedId }
    val existing = (state.eligibility as? CompanionEligibility.Existing)?.sessionId
    val completed = state.completed ?: state.preview?.completedSessionId
    val candidates = (state.eligibility as? CompanionEligibility.Used)?.candidates.orEmpty()
    val conversionModel = state.models.firstOrNull { it.id == state.conversionModelId }
    val back = { if (state.busy) onCancel() else if (state.preview != null) onBackToSelection() else onBack() }
    BackHandler(onBack = back)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(stringResource(if (state.preview != null) R.string.companion_transfer_title else R.string.companion_create_title), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } }) },
        bottomBar = { Surface(tonalElevation = 2.dp) { Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (state.preview != null && completed == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(conversionModel?.name ?: stringResource(R.string.companion_conversion_no_model),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    TextButton(onClick = onConfigure, enabled = !state.busy, modifier = Modifier.testTag("companion_configure")) {
                        Text(stringResource(R.string.companion_conversion_configure))
                    }
                }
                conversionModel?.let { model -> Text(stringResource(R.string.companion_conversion_wait_limit, model.timeoutSeconds),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)) }
            }
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("companion_primary"),
                enabled = !state.busy && (completed != null || existing != null || state.preview?.let { conversionModel != null && it.missingAttachments.isEmpty() && name.isNotBlank() } == true ||
                    state.preview == null && (state.eligibility == CompanionEligibility.Available || candidates.isNotEmpty())),
                onClick = { when {
                    completed != null -> onOpen(completed)
                    state.preview != null -> onTransfer(name)
                    existing != null -> onOpen(existing)
                    state.eligibility == CompanionEligibility.Available -> onCreate(name, greeting)
                    else -> sources = true
                } }) {
                Text(stringResource(when {
                    completed != null -> R.string.companion_open_new
                    state.busy -> R.string.companion_processing
                    state.preview != null -> R.string.companion_conversion_start
                    existing != null -> R.string.companion_open_existing
                    candidates.isNotEmpty() -> R.string.companion_select_session
                    else -> R.string.companion_create_title
                }))
            }
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("companion_content"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.preview == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.single_session_headline), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.companion_create_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { about = true }) { Text(stringResource(R.string.single_session_about)) }
                    }
                }
                item {
                    Card(onClick = { if (!state.busy) picker = true }, modifier = Modifier.fillMaxWidth().testTag("companion_character"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (selected?.avatarUrl != null) AsyncImage(resolveAvatarUrl(selected.avatarUrl), contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                            else Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                    if (selected != null) Text(selected.displayName.take(1), style = MaterialTheme.typography.titleLarge)
                                    else Icon(Icons.Filled.Person, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(selected?.displayName ?: stringResource(R.string.companion_choose_character), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(when(state.eligibility) {
                                    CompanionEligibility.Available -> R.string.companion_available
                                    is CompanionEligibility.Existing -> R.string.companion_has_main
                                    is CompanionEligibility.Used -> R.string.companion_has_sessions
                                    else -> R.string.companion_select_hint
                                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(if (selected == null) R.string.common_select else R.string.companion_change), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.eligibility is CompanionEligibility.Used) {
                    item { Text(stringResource(if (candidates.isEmpty()) R.string.companion_group_unsupported else R.string.companion_copy_hint), style = MaterialTheme.typography.bodyMedium) }
                    if (sources) items(candidates, key = { it.id }) { session ->
                        OutlinedCard(onClick = { if (!state.busy) onPreview(session.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) { Text(session.name, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.companion_session_messages, session.messageCount), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (state.eligibility == CompanionEligibility.Available) {
                    item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.companion_optional_name)) }, singleLine = true) }
                    item { TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (advanced) R.string.companion_less_settings else R.string.companion_more_settings)) } }
                    if (advanced) {
                        item { OutlinedTextField(greeting, { greeting = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.companion_optional_greeting)) }, minLines = 3) }
                        item { Text(stringResource(R.string.companion_initial_state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item { TextButton(onClick = onNewCharacter, enabled = !state.busy) { Text(stringResource(R.string.companion_new_character)) } }
            } else {
                val preview = state.preview
                item { Text(if (completed != null) stringResource(R.string.companion_complete_hint) else stringResource(R.string.companion_conversion_intro), style = MaterialTheme.typography.bodyLarge) }
                if (completed == null) item {
                    Text(stringResource(R.string.companion_conversion_service_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRules, modifier = Modifier.testTag("companion_rules")) { Text(stringResource(R.string.companion_conversion_rules)) }
                }
                item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("companion_copy_name"), enabled = !state.busy && completed == null, label = { Text(stringResource(R.string.companion_copy_name)) }) }
                item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(preview.sourceName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.companion_preview_counts, preview.messages, preview.variants, preview.memories, preview.attachments), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.companion_preview_space, (preview.requiredBytes / 1048576.0).let { String.format(java.util.Locale.getDefault(), "%.1f", it) }), style = MaterialTheme.typography.bodyMedium)
                    if(preview.skippedMemories > 0) Text(stringResource(R.string.companion_skipped_count, preview.skippedMemories))
                    if(preview.remoteAttachments > 0) Text(stringResource(R.string.companion_remote_count, preview.remoteAttachments))
                } } }
                items(preview.reviewNotes) { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(preview.missingAttachments) { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if(completed != null) item { TextButton(onClick = { onReceipt(preview.jobId) }) { Text(stringResource(R.string.companion_transfer_record)) } }
            }
            if (state.busy) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress?.let { it.phase + if(it.total > 0) " ${it.processed} / ${it.total}" else "" } ?: stringResource(R.string.companion_processing))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            } }
            state.error?.let { message -> item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (state.errorReport != null) CompanionConversionErrorReport(state.errorReport)
                        else if (state.details != null) { TextButton(onClick = { details = !details }) { Text(stringResource(R.string.companion_technical_details)) }; if (details) Text(state.details, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } }
            if (state.error == null && state.errorReport != null) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.companion_conversion_previous_errors), style = MaterialTheme.typography.titleSmall)
                        CompanionConversionErrorReport(state.errorReport)
                    }
                }
            }
            if (state.preview == null && state.records.isNotEmpty()) {
                item { HorizontalDivider(); Text(stringResource(R.string.companion_transfer_records), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                items(state.records, key = { it.id }) { job ->
                    CompanionTransferRecordCard(job, state.recordInfo[job.id], enabled = !state.busy,
                        onRestore = { onRestore(job.id) }, onReceipt = { onReceipt(job.id) },
                        onDeleteBackup = { onDeleteBackup(job.id) }, onDelete = { onDeleteRecord(job.id) })
                }
            }
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false },
        title = { Text(stringResource(R.string.single_session_about)) },
        text = { LazyColumn { item { Text(stringResource(R.string.single_session_about_body)) } } },
        confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.common_close)) } })
    if(picker) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.companion_choose_character), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true, label = { Text(stringResource(R.string.companion_search_character)) })
            val matches = state.characters.filter { it.displayName.contains(search.trim(), true) }
            if (state.busy && state.characters.isEmpty()) {
                CircularProgressIndicator(Modifier.padding(16.dp))
            } else if (matches.isEmpty()) {
                Text(stringResource(if (state.characters.isEmpty()) R.string.companion_no_available_characters
                    else R.string.companion_no_matching_characters), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.characters.isEmpty()) TextButton(onClick = { picker = false; onNewCharacter() }) {
                    Text(stringResource(R.string.companion_new_character))
                }
            }
            LazyColumn { items(matches, key = { it.id.orEmpty() }) { character ->
                ListItem(
                    leadingContent = {
                        // 选择抽屉与角色卡一致：优先立绘，回退头像；都没有时用首字兜底。
                        val image = resolveAvatarUrl(character.avatarUrl)
                        if (image != null) AsyncImage(image, contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        else Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Text(character.displayName.take(1), style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    },
                    headlineContent = { Text(character.displayName) },
                    supportingContent = { character.description?.take(90)?.let { Text(it, maxLines = 2) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !state.busy) { character.id?.let(onSelect); picker = false })
            } }
        }
    }
}
