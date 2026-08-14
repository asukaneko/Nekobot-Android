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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

class DataPortabilityViewModel : BaseViewModel() {
    private val _current = MutableStateFlow<List<PortableCategorySummary>>(emptyList())
    val current: StateFlow<List<PortableCategorySummary>> = _current.asStateFlow()

    private val _preview = MutableStateFlow<PortableArchivePreview?>(null)
    val preview: StateFlow<PortableArchivePreview?> = _preview.asStateFlow()

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

    fun inspect(context: Context, uri: Uri, password: String) {
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                _preview.value = context.contentResolver.openInputStream(uri)?.use { input ->
                    PortableDataArchiveManager(context).inspect(input, password)
                } ?: error(string(R.string.portability_open_input_failed))
            } catch (error: Exception) {
                _preview.value = null
                showError(error.message ?: string(R.string.portability_inspect_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    fun import(
        context: Context,
        uri: Uri,
        password: String,
        categories: Set<PortableDataCategory>
    ) {
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val result = context.contentResolver.openInputStream(uri)?.use { input ->
                    PortableDataArchiveManager(context).import(input, password, categories)
                } ?: error(string(R.string.portability_open_input_failed))
                showToast(
                    string(
                        R.string.portability_import_success,
                        result.categories,
                        result.importedRows,
                        result.importedFiles
                    )
                )
                _preview.value = null
                loadCurrent(context)
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.portability_import_failed))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPortabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DataPortabilityViewModel = viewModel()
    val current by vm.current.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    var exportSelection by rememberSaveable {
        mutableStateOf(
            setOf(
                PortableDataCategory.CONVERSATIONS.id,
                PortableDataCategory.CHARACTERS.id,
                PortableDataCategory.WORLD_BOOKS.id,
                PortableDataCategory.MEMORIES.id,
                PortableDataCategory.AI_CONFIG.id,
                PortableDataCategory.APP_SETTINGS.id,
                PortableDataCategory.GLOBAL_MEMORY.id
            )
        )
    }
    var exportPassword by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<Set<PortableDataCategory>>(emptySet()) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var exportDetails by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var detailCategory by remember { mutableStateOf<PortableDataCategory?>(null) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    var importFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importSelection by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var confirmImport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadCurrent(context) }
    LaunchedEffect(current) {
        if (current.isNotEmpty()) {
            exportDetails = exportDetails.toMutableMap().apply {
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
    LaunchedEffect(preview) {
        preview?.let { value -> importSelection = value.categories.mapTo(linkedSetOf()) { it.category.id } }
    }

    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExport.isNotEmpty()) {
            vm.export(
                context = context,
                uri = uri,
                categories = pendingExport,
                password = pendingExportPassword,
                selectedDetails = exportDetails.mapNotNull { (id, details) ->
                    PortableDataCategory.fromId(id)?.let { it to details }
                }.toMap()
            )
        }
        pendingExport = emptySet()
        pendingExportPassword = ""
    }
    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri.toString()
            importFileName = displayName(context, uri)
            vm.inspect(context, uri, importPassword)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.portability_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.portability_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error?.let { message ->
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = vm::clearError) { Text(stringResource(R.string.common_close)) }
                    }
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        title = stringResource(R.string.portability_export_title),
                        subtitle = stringResource(R.string.portability_export_subtitle)
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionActions(
                        onAll = { exportSelection = PortableDataCategory.entries.mapTo(linkedSetOf()) { it.id } },
                        onClear = { exportSelection = emptySet() }
                    )
                    Spacer(Modifier.height(4.dp))
                    current.forEach { summary ->
                            CategorySelectionRow(
                                summary = summary,
                                selected = summary.category.id in exportSelection,
                                onToggle = { selected ->
                                    exportSelection = exportSelection.toggle(summary.category.id, selected)
                                },
                                onClick = { detailCategory = summary.category }
                            )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.portability_password_optional)) },
                        supportingText = {
                            Text(
                                if (PortableDataCategory.CREDENTIALS.id in exportSelection) {
                                    stringResource(R.string.portability_password_required)
                                } else {
                                    stringResource(R.string.portability_password_hint)
                                }
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val categories = exportSelection.mapNotNullTo(linkedSetOf(), PortableDataCategory::fromId)
                                .filterTo(linkedSetOf()) { category ->
                                    exportDetails[category.id].orEmpty().isNotEmpty()
                                }
                            if (categories.isEmpty()) {
                                vm.showError(context.getString(R.string.portability_select_one))
                            } else if (
                                PortableDataCategory.CREDENTIALS in categories && exportPassword.trim().length < 8
                            ) {
                                vm.showError(context.getString(R.string.portability_password_required))
                            } else {
                                pendingExport = categories
                                pendingExportPassword = exportPassword
                                createArchive.launch(suggestedArchiveName())
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.portability_export_action, exportSelection.size))
                    }
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        title = stringResource(R.string.portability_import_title),
                        subtitle = stringResource(R.string.portability_import_subtitle)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            openArchive.launch(
                                arrayOf("application/octet-stream", "application/zip", "application/json", "*/*")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.SwapVert, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.portability_choose_archive))
                    }
                    importFileName?.let { name ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.portability_import_password)) },
                        supportingText = { Text(stringResource(R.string.portability_import_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (importUri != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { vm.inspect(context, Uri.parse(importUri), importPassword) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.portability_analyze_archive))
                        }
                    }

                    preview?.let { archive ->
                        Spacer(Modifier.height(8.dp))
                        ArchiveSummary(archive)
                        Spacer(Modifier.height(6.dp))
                        SelectionActions(
                            onAll = { importSelection = archive.categories.mapTo(linkedSetOf()) { it.category.id } },
                            onClear = { importSelection = emptySet() }
                        )
                        archive.categories.forEach { summary ->
                            CategorySelectionRow(
                                summary = summary,
                                selected = summary.category.id in importSelection,
                                onToggle = { selected ->
                                    importSelection = importSelection.toggle(summary.category.id, selected)
                                },
                                onClick = {}
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.portability_merge_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (importSelection.isEmpty()) vm.showError(context.getString(R.string.portability_select_one))
                                else confirmImport = true
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.portability_import_action, importSelection.size))
                        }
                    }
                }
            }
        }
        LoadingOverlay(visible = loading)
    }

    if (confirmImport) {
        NekoDialog(
            onDismiss = { confirmImport = false },
            title = stringResource(R.string.portability_confirm_title),
            message = stringResource(R.string.portability_confirm_message, importSelection.size),
            confirmText = stringResource(R.string.portability_confirm_action),
            onConfirm = {
                confirmImport = false
                val uri = importUri?.let(Uri::parse) ?: return@NekoDialog
                vm.import(
                    context,
                    uri,
                    importPassword,
                    importSelection.mapNotNullTo(linkedSetOf(), PortableDataCategory::fromId)
                )
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { confirmImport = false }
        )
    }

    detailCategory?.let { category ->
        val summary = current.firstOrNull { it.category == category }
        if (summary != null) {
            val selectedDetails = exportDetails[category.id].orEmpty()
            NekoDialog(
                onDismiss = { detailCategory = null },
                title = "${categoryLabel(category)}${stringResource(R.string.portability_detail_title)}",
                message = stringResource(R.string.portability_detail_hint),
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
                                exportDetails = exportDetails + (category.id to summary.details.mapTo(linkedSetOf()) { it.key })
                            },
                            onClear = { exportDetails = exportDetails + (category.id to emptySet()) }
                        )
                        summary.details.forEach { detail ->
                            val selected = detail.key in selectedDetails
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val next = if (selected) selectedDetails - detail.key else selectedDetails + detail.key
                                        exportDetails = exportDetails + (category.id to next)
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
                                        exportDetails = exportDetails + (category.id to next)
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
private fun SelectionActions(onAll: () -> Unit, onClear: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onAll) { Text(stringResource(R.string.portability_select_all)) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.portability_clear_selection)) }
    }
}

@Composable
private fun CategorySelectionRow(
    summary: PortableCategorySummary,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            categoryIcon(summary.category),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = categoryLabel(summary.category),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = categoryDescription(summary.category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.portability_item_count, summary.itemCount),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        Checkbox(checked = selected, onCheckedChange = onToggle)
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

private fun displayName(context: Context, uri: Uri): String =
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
    PortableDataCategory.GLOBAL_MEMORY -> Icons.Filled.Psychology
}

@Composable
private fun categoryLabel(category: PortableDataCategory): String = stringResource(
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
        PortableDataCategory.GLOBAL_MEMORY -> R.string.portability_category_global_memory_desc
    }
)

private fun portableDetailLabel(detail: PortableCategoryDetail): String {
    val key = detail.key.substringAfter(':')
    return when (key) {
        "local_sessions" -> "会话"
        "local_messages" -> "消息"
        "local_agent_runs" -> "Agent 运行记录"
        "local_message_favorites" -> "消息收藏"
        "local_characters" -> "角色卡"
        "local_world_books" -> "世界书"
        "local_world_book_entries" -> "世界书条目"
        "local_character_states" -> "角色状态"
        "local_relationship_states" -> "关系状态"
        "local_character_memories" -> "角色记忆"
        "local_state_snapshots" -> "状态快照"
        "local_ai_models" -> "AI 模型"
        "local_failover_health" -> "故障转移状态"
        "local_hooks" -> "Hooks"
        "local_hook_logs" -> "Hook 日志"
        "local_tasks" -> "任务"
        "local_workflows" -> "工作流"
        "local_skills" -> "Skills"
        "local_tools" -> "工具"
        "local_mcp_servers" -> "MCP 服务器"
        "local_knowledge_documents" -> "知识文档"
        "local_knowledge_chunks" -> "知识分块"
        "routing_decision_logs" -> "路由决策记录"
        "portraits" -> "角色立绘"
        "cached_portraits" -> "立绘缓存"
        "chat_backgrounds" -> "聊天背景"
        "fonts" -> "自定义字体"
        "workspace" -> "工作区文件"
        "global_memory" -> "全局 Agent 记忆"
        "app_settings" -> "应用设置"
        "credentials_bundle" -> "加密账号与凭据"
        else -> key
    }
}
