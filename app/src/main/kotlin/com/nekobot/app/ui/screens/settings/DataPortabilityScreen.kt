package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.local.PortableArchivePreview
import com.nekobot.app.data.local.PortableCategorySummary
import com.nekobot.app.data.local.PortableCategoryDetail
import com.nekobot.app.data.local.PortableDataArchiveManager
import com.nekobot.app.data.local.PortableDataCategory
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class PortableExportUi {
    var selection by mutableStateOf(setOf(PortableDataCategory.CONVERSATIONS.id, PortableDataCategory.CHARACTERS.id,
        PortableDataCategory.WORLD_BOOKS.id, PortableDataCategory.MEMORIES.id, PortableDataCategory.AI_CONFIG.id,
        PortableDataCategory.APP_SETTINGS.id, PortableDataCategory.STICKERS.id, PortableDataCategory.GLOBAL_MEMORY.id))
    var password by mutableStateOf("")
    var details by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var pending by mutableStateOf<Set<PortableDataCategory>>(emptySet())
    var pendingPassword by mutableStateOf("")
    var pendingDetails by mutableStateOf<Map<PortableDataCategory, Set<String>>>(emptyMap())
    var advanced by mutableStateOf(false)
}

class DataPortabilityViewModel : BaseViewModel() {
    internal val exportUi = PortableExportUi()
    private val _current = MutableStateFlow<List<PortableCategorySummary>>(emptyList())
    val current: StateFlow<List<PortableCategorySummary>> = _current.asStateFlow()

    fun loadCurrent(context: Context) {
        viewModelScope.launch {
            runCatching { PortableDataArchiveManager(context).scanCurrent() }
                .onSuccess { _current.value = it }
                .onFailure { showError(it.message) }
        }
    }

    fun export(
        context: Context,
        uri: Uri,
        categories: Set<PortableDataCategory>,
        password: String,
        selectedDetails: Map<PortableDataCategory, Set<String>> = emptyMap()
    ) {
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    PortableDataArchiveManager(context).export(
                        selected = categories,
                        password = password,
                        output = output,
                        appVersion = version,
                        selectedDetails = selectedDetails
                    )
                } ?: error(string(R.string.portability_open_output_failed))
                showToast(string(R.string.portability_export_success))
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.portability_export_failed))
            } finally {
                setLoading(false)
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPortabilityScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: DataPortabilityViewModel = viewModel()
    val current by vm.current.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    val exportUi = vm.exportUi
    var detailCategory by remember { mutableStateOf<PortableDataCategory?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val safeImportVm: SafePortableImportViewModel = viewModel()
    val importState by safeImportVm.state.collectAsStateWithLifecycle()
    val handleBack = { if (selectedTab != 0 || !safeImportVm.back()) onBack() }
    androidx.activity.compose.BackHandler(enabled = selectedTab == 0 &&
        (importState.busy || importState.step in setOf(SafeImportStep.CONTENTS, SafeImportStep.CONFIRM))) { safeImportVm.back() }

    LaunchedEffect(Unit) { vm.loadCurrent(context) }
    LaunchedEffect(current) {
        if (current.isNotEmpty()) {
            exportUi.details = exportUi.details.toMutableMap().apply {
                current.forEach { summary ->
                    if (summary.category.id !in this) {
                        this[summary.category.id] = summary.details.mapTo(linkedSetOf()) { it.key }
                    }
                }
            }
        }
    }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && exportUi.pending.isNotEmpty()) {
            vm.export(
                context = context,
                uri = uri,
                categories = exportUi.pending,
                password = exportUi.pendingPassword,
                selectedDetails = exportUi.pendingDetails
            )
        }
        exportUi.pending = emptySet()
        exportUi.pendingPassword = ""
        exportUi.pendingDetails = emptyMap()
    }

    val uiEnabled = !loading && !importState.busy && !importState.picking && exportUi.pending.isEmpty()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.portability_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = handleBack, modifier = Modifier.testTag("portability-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(if (importState.busy) R.string.common_cancel else R.string.common_back))
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = selectedTab == 0, onClick = { if (uiEnabled) selectedTab = 0 }, enabled = uiEnabled,
                        text = { Text(stringResource(R.string.portability_ui_import_tab)) }, modifier = Modifier.testTag("portability-tab-import"))
                    Tab(selected = selectedTab == 1, onClick = { if (uiEnabled) selectedTab = 1 }, enabled = uiEnabled,
                        text = { Text(stringResource(R.string.portability_ui_export_tab)) }, modifier = Modifier.testTag("portability-tab-export"))
                }
            }
        }, containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (selectedTab == 0) {
                SafePortableImportPanel(vm = safeImportVm, onNavigate = onNavigate, externalBusy = loading,
                    onCompleted = { vm.loadCurrent(context) }, onFinish = onBack)
            } else {
                LazyColumn(Modifier.weight(1f).testTag("portability-export-scroll"), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.portability_ui_export_heading), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.portability_export_subtitle), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionActions(onAll = { exportUi.selection = PortableDataCategory.entries.mapTo(linkedSetOf()) { it.id } },
                            onClear = { exportUi.selection = emptySet() }, enabled = uiEnabled)
                    }
                    error?.let { message -> item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::clearError) { Text(stringResource(R.string.common_close)) }
                    } }
                    current.groupBy { exportCategoryGroup(it.category) }.toSortedMap().forEach { (group, summaries) ->
                        item { Text(stringResource(when (group) { 0 -> R.string.portability_group_content; 1 -> R.string.portability_group_tools; else -> R.string.portability_group_files }),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                        items(summaries, key = { it.category.id }) { summary ->
                            CategorySelectionRow(summary = summary, selected = summary.category.id in exportUi.selection,
                                onToggle = { exportUi.selection = exportUi.selection.toggle(summary.category.id, it) },
                                onClick = { detailCategory = summary.category }, enabled = uiEnabled)
                        }
                    }
                    item {
                        HorizontalDivider()
                        TextButton(onClick = { exportUi.advanced = !exportUi.advanced }, enabled = uiEnabled) {
                            Text(stringResource(R.string.portability_ui_encryption))
                        }
                        val needsPassword = PortableDataCategory.CREDENTIALS.id in exportUi.selection
                        if (needsPassword || exportUi.advanced) {
                            OutlinedTextField(value = exportUi.password, onValueChange = { exportUi.password = it }, enabled = uiEnabled,
                                modifier = Modifier.fillMaxWidth().testTag("portability-export-password"), label = { Text(stringResource(R.string.portability_password_optional)) },
                                supportingText = { Text(stringResource(if (needsPassword) R.string.portability_password_required else R.string.portability_password_hint)) },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        } else if (exportUi.password.isNotEmpty()) {
                            Text(stringResource(R.string.portability_ui_encrypted_export), style = MaterialTheme.typography.bodySmall)
                        }
                        if (exportUi.selection.isEmpty()) Text(stringResource(R.string.portability_select_one), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = {
                        if (!loading && !importState.busy && exportUi.pending.isEmpty()) {
                            val categories = exportUi.selection.mapNotNullTo(linkedSetOf(), PortableDataCategory::fromId)
                                .filterTo(linkedSetOf()) { exportUi.details[it.id].orEmpty().isNotEmpty() }
                            if (categories.isEmpty()) vm.showError(context.getString(R.string.portability_select_one))
                            else if (PortableDataCategory.CREDENTIALS in categories && exportUi.password.trim().length < 8)
                                vm.showError(context.getString(R.string.portability_password_required))
                            else {
                                exportUi.pending = categories
                                exportUi.pendingPassword = exportUi.password
                                exportUi.pendingDetails = exportUi.details.mapNotNull { (id, details) ->
                                    PortableDataCategory.fromId(id)?.let { it to details.toSet() }
                                }.toMap()
                                createArchive.launch(suggestedArchiveName())
                            }
                        }
                    }, enabled = uiEnabled && exportUi.selection.isNotEmpty(), modifier = Modifier.testTag("portability-export-action")
                        .widthIn(min = 160.dp, max = 200.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.portability_export_action, exportUi.selection.count { exportUi.details[it].orEmpty().isNotEmpty() }), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        LoadingOverlay(visible = loading)
    }

    detailCategory?.let { category ->
        val summary = current.firstOrNull { it.category == category }
        if (summary != null) {
            val selectedDetails = exportUi.details[category.id].orEmpty()
            NekoDialog(
                onDismiss = { detailCategory = null },
                title = "${categoryLabel(category)}${stringResource(R.string.portability_detail_title)}",
                message = categoryDescription(category) + "\n" + stringResource(R.string.portability_detail_hint),
                confirmText = stringResource(R.string.portability_detail_done),
                onConfirm = { detailCategory = null },
                cancelText = null,
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionActions(
                            onAll = {
                                exportUi.details = exportUi.details + (category.id to summary.details.mapTo(linkedSetOf()) { it.key })
                            },
                            onClear = { exportUi.details = exportUi.details + (category.id to emptySet()) }
                        )
                        summary.details.forEach { detail ->
                            val selected = detail.key in selectedDetails
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val next = if (selected) selectedDetails - detail.key else selectedDetails + detail.key
                                        exportUi.details = exportUi.details + (category.id to next)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(portableDetailLabel(detail), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = if (detail.isFile) {
                                            stringResource(R.string.portability_detail_file_count, detail.itemCount)
                                        } else {
                                            stringResource(R.string.portability_detail_table_count, detail.itemCount)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) selectedDetails + detail.key else selectedDetails - detail.key
                                        exportUi.details = exportUi.details + (category.id to next)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SelectionActions(onAll: () -> Unit, onClear: () -> Unit, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onAll, enabled = enabled) { Text(stringResource(R.string.portability_select_all)) }
        TextButton(onClick = onClear, enabled = enabled) { Text(stringResource(R.string.portability_clear_selection)) }
    }
}

@Composable
private fun CategorySelectionRow(summary: PortableCategorySummary, selected: Boolean,
    onToggle: (Boolean) -> Unit, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(categoryIcon(summary.category), null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(categoryLabel(summary.category), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.portability_ui_export_details, summary.itemCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(selected, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.testTag("portability-export-${summary.category.id}"))
    }
}

@Composable
private fun ArchiveSummary(preview: PortableArchivePreview) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.portability_archive_summary),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.portability_archive_meta,
                preview.sourceVersion.ifBlank { "—" },
                preview.exportedAt.ifBlank { "—" },
                if (preview.encrypted) stringResource(R.string.portability_encrypted)
                else stringResource(R.string.portability_not_encrypted)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Set<String>.toggle(id: String, selected: Boolean): Set<String> =
    if (selected) this + id else this - id

internal fun portableArchiveDisplayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

private fun suggestedArchiveName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
    return "Nekobot-data-$timestamp.nekodata"
}

private fun categoryIcon(category: PortableDataCategory): ImageVector = when (category) {
    PortableDataCategory.CONVERSATIONS -> Icons.Filled.People
    PortableDataCategory.CHARACTERS -> Icons.Filled.SmartToy
    PortableDataCategory.WORLD_BOOKS -> Icons.AutoMirrored.Filled.MenuBook
    PortableDataCategory.MEMORIES -> Icons.Filled.Psychology
    PortableDataCategory.AI_CONFIG -> Icons.Filled.Memory
    PortableDataCategory.EXTENSIONS -> Icons.Filled.Extension
    PortableDataCategory.KNOWLEDGE -> Icons.Filled.School
    PortableDataCategory.ANALYTICS -> Icons.Filled.Analytics
    PortableDataCategory.APP_SETTINGS -> Icons.Filled.Settings
    PortableDataCategory.CREDENTIALS -> Icons.Filled.Key
    PortableDataCategory.MEDIA -> Icons.Filled.Image
    PortableDataCategory.WORKSPACE -> Icons.Filled.Folder
    PortableDataCategory.STICKERS -> Icons.Filled.EmojiEmotions
    PortableDataCategory.GLOBAL_MEMORY -> Icons.Filled.Psychology
}

@Composable
internal fun categoryLabel(category: PortableDataCategory): String = stringResource(
    when (category) {
        PortableDataCategory.CONVERSATIONS -> R.string.portability_category_conversations
        PortableDataCategory.CHARACTERS -> R.string.portability_category_characters
        PortableDataCategory.WORLD_BOOKS -> R.string.portability_category_world_books
        PortableDataCategory.MEMORIES -> R.string.portability_category_memories
        PortableDataCategory.AI_CONFIG -> R.string.portability_category_ai_config
        PortableDataCategory.EXTENSIONS -> R.string.portability_category_extensions
        PortableDataCategory.KNOWLEDGE -> R.string.portability_category_knowledge
        PortableDataCategory.ANALYTICS -> R.string.portability_category_analytics
        PortableDataCategory.APP_SETTINGS -> R.string.portability_category_app_settings
        PortableDataCategory.CREDENTIALS -> R.string.portability_category_credentials
        PortableDataCategory.MEDIA -> R.string.portability_category_media
        PortableDataCategory.WORKSPACE -> R.string.portability_category_workspace
        PortableDataCategory.STICKERS -> R.string.portability_category_stickers
        PortableDataCategory.GLOBAL_MEMORY -> R.string.portability_category_global_memory
    }
)

@Composable
private fun categoryDescription(category: PortableDataCategory): String = stringResource(
    when (category) {
        PortableDataCategory.CONVERSATIONS -> R.string.portability_category_conversations_desc
        PortableDataCategory.CHARACTERS -> R.string.portability_category_characters_desc
        PortableDataCategory.WORLD_BOOKS -> R.string.portability_category_world_books_desc
        PortableDataCategory.MEMORIES -> R.string.portability_category_memories_desc
        PortableDataCategory.AI_CONFIG -> R.string.portability_category_ai_config_desc
        PortableDataCategory.EXTENSIONS -> R.string.portability_category_extensions_desc
        PortableDataCategory.KNOWLEDGE -> R.string.portability_category_knowledge_desc
        PortableDataCategory.ANALYTICS -> R.string.portability_category_analytics_desc
        PortableDataCategory.APP_SETTINGS -> R.string.portability_category_app_settings_desc
        PortableDataCategory.CREDENTIALS -> R.string.portability_category_credentials_desc
        PortableDataCategory.MEDIA -> R.string.portability_category_media_desc
        PortableDataCategory.WORKSPACE -> R.string.portability_category_workspace_desc
        PortableDataCategory.STICKERS -> R.string.portability_category_stickers_desc
        PortableDataCategory.GLOBAL_MEMORY -> R.string.portability_category_global_memory_desc
    }
)

@Composable
internal fun portableDetailLabel(detail: PortableCategoryDetail): String {
    val key = detail.key.substringAfter(':')
    val resourceId = when (key) {
        "local_sessions" -> R.string.portability_detail_local_sessions
        "local_messages" -> R.string.portability_detail_local_messages
        "local_agent_runs" -> R.string.portability_detail_local_agent_runs
        "local_message_favorites" -> R.string.portability_detail_local_message_favorites
        "local_characters" -> R.string.portability_detail_local_characters
        "local_world_books" -> R.string.portability_detail_local_world_books
        "local_world_book_entries" -> R.string.portability_detail_local_world_book_entries
        "local_character_states" -> R.string.portability_detail_local_character_states
        "local_relationship_states" -> R.string.portability_detail_local_relationship_states
        "local_character_memories" -> R.string.portability_detail_local_character_memories
        "local_state_snapshots" -> R.string.portability_detail_local_state_snapshots
        "local_ai_models" -> R.string.portability_detail_local_ai_models
        "local_failover_health" -> R.string.portability_detail_local_failover_health
        "local_hooks" -> R.string.portability_detail_local_hooks
        "local_hook_logs" -> R.string.portability_detail_local_hook_logs
        "local_tasks" -> R.string.portability_detail_local_tasks
        "local_workflows" -> R.string.portability_detail_local_workflows
        "local_skills" -> R.string.portability_detail_local_skills
        "local_tools" -> R.string.portability_detail_local_tools
        "local_mcp_servers" -> R.string.portability_detail_local_mcp_servers
        "local_stickers" -> R.string.portability_detail_local_stickers
        "local_knowledge_documents" -> R.string.portability_detail_local_knowledge_documents
        "local_knowledge_chunks" -> R.string.portability_detail_local_knowledge_chunks
        "routing_decision_logs" -> R.string.portability_detail_routing_decision_logs
        "portraits" -> R.string.portability_detail_portraits
        "cached_portraits" -> R.string.portability_detail_cached_portraits
        "worldbook_covers" -> R.string.portability_detail_worldbook_covers
        "tts_audio" -> R.string.portability_detail_tts_audio
        "chat_backgrounds" -> R.string.portability_detail_chat_backgrounds
        "fonts" -> R.string.portability_detail_fonts
        "workspace" -> R.string.portability_detail_workspace
        "stickers" -> R.string.portability_detail_local_stickers
        "global_memory" -> R.string.portability_detail_global_memory
        "app_settings" -> R.string.portability_detail_app_settings
        "credentials_bundle" -> R.string.portability_detail_credentials_bundle
        else -> null
    }
    return if (resourceId != null) stringResource(resourceId) else key
}

/** Grouping only changes presentation; every category remains selectable with the same defaults. */
private fun exportCategoryGroup(category: PortableDataCategory): Int = when (category) {
    PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS, PortableDataCategory.WORLD_BOOKS,
    PortableDataCategory.MEMORIES, PortableDataCategory.GLOBAL_MEMORY -> 0
    PortableDataCategory.AI_CONFIG, PortableDataCategory.EXTENSIONS, PortableDataCategory.CREDENTIALS -> 1
    else -> 2
}
