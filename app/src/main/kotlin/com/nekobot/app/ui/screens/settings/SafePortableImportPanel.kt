package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private data class ImportGroup(val category: PortableDataCategory, val title: String, val details: List<PortableCategoryDetail>)

@Composable
internal fun SafePortableImportPanel(onNavigate: (String) -> Unit = {}, externalBusy: Boolean = false,
    onBusyChanged: (Boolean) -> Unit = {}, onCompleted: () -> Unit = {}, onFinish: () -> Unit = {},
    vm: SafePortableImportViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.busy) { onBusyChanged(state.busy) }
    LaunchedEffect(state.result) { if (state.result != null) onCompleted() }
    var group by remember { mutableStateOf<ImportGroup?>(null) }
    var showDifferences by remember { mutableStateOf(false) }
    var reviewRowKeys by remember { mutableStateOf<Set<String>?>(null) }
    var showIssues by remember { mutableStateOf(false) }
    var storyConfirm by remember { mutableStateOf(false) }
    var formatDetails by remember { mutableStateOf(false) }
    var archiveInfo by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(state.step, showDifferences) { if (!showDifferences) scroll.scrollToItem(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        vm.pickerClosed()
        if (selected != null) vm.inspect(context, selected, "")
    }
    val chooseFile = { if (!externalBusy && vm.beginPicking()) picker.launch(arrayOf("*/*")) }
    val reviewSources = {
        val keys = state.plan?.rows?.sourceReviewKeys.orEmpty()
        if (vm.beginSourceDifferenceReview(context)) {
            reviewRowKeys = keys; showIssues = false; showDifferences = true
        }
    }
    val sourceDecisionOnly = state.plan?.let { plan ->
        plan.rows.sourceConflictKeys.isNotEmpty() && plan.issues.isNotEmpty() &&
            plan.issues.all { it.contains("记忆正文、主体或归属") }
    } == true
    val groups = buildList {
        state.prepared?.preview?.categories?.forEach { summary ->
            if (summary.category == PortableDataCategory.CONVERSATIONS) {
                val independent = summary.details.filter { it.key.removePrefix("table:") in INDEPENDENT_MEMORY_TABLES }
                val other = summary.details - independent.toSet()
                if (other.isNotEmpty()) add(ImportGroup(summary.category, categoryLabel(summary.category), other))
                if (independent.isNotEmpty()) add(ImportGroup(summary.category, stringResource(R.string.safe_import_independent_memory), independent))
            } else add(ImportGroup(summary.category, categoryLabel(summary.category), summary.details))
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("safe-import-scroll"), state = scroll, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ImportSteps(state.step) }
            if (state.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { stringResource(R.string.safe_import_checking) }, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            when (state.step) {
                SafeImportStep.FILE -> {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.portability_ui_choose_file), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.safe_import_intro), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    if (state.sourceUri != null) item {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(state.fileName.ifBlank { stringResource(R.string.portability_ui_selected_file) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = chooseFile, enabled = !state.busy && !state.picking && !externalBusy) { Text(stringResource(R.string.portability_ui_change_file)) }
                            }
                        }
                    }
                    if (state.passwordNeeded && state.prepared == null) item {
                        OutlinedTextField(value = state.passwordInput, onValueChange = vm::password,
                            label = { Text(stringResource(R.string.portability_import_password)) }, enabled = !state.busy,
                            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                    state.route?.let { route -> item {
                        val label = when (route) {
                            Routes.CHARACTERS -> categoryLabel(PortableDataCategory.CHARACTERS)
                            Routes.SESSIONS -> categoryLabel(PortableDataCategory.CONVERSATIONS)
                            Routes.KNOWLEDGE -> categoryLabel(PortableDataCategory.KNOWLEDGE)
                            Routes.SKILLS -> stringResource(R.string.skills_title)
                            Routes.PLUGINS -> stringResource(R.string.plugins_title)
                            Routes.DB_PROFILE -> stringResource(R.string.safe_import_database)
                            else -> stringResource(R.string.settings_config_transfer)
                        }
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    } }
                    item {
                        TextButton(onClick = { formatDetails = !formatDetails }) { Text(stringResource(R.string.portability_ui_formats)) }
                        if (formatDetails) Text(stringResource(R.string.portability_ui_formats_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SafeImportStep.CONTENTS -> {
                    item {
                        Text(stringResource(R.string.portability_ui_choose_contents), style = MaterialTheme.typography.titleLarge)
                        Text(state.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { archiveInfo = true }, enabled = !state.busy) { Text(stringResource(R.string.portability_archive_summary)) }
                    }
                    if (groups.isEmpty()) item { Text(stringResource(R.string.safe_import_empty)) }
                    groups.forEach { entry -> item(key = entry.title) {
                        val keys = entry.details.mapTo(linkedSetOf()) { it.key }
                        val selected = state.selected[entry.category].orEmpty()
                        val groupTag = "safe-import-${entry.category.id}-${if (keys.any { it.removePrefix("table:") in INDEPENDENT_MEMORY_TABLES }) "memory" else "data"}"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state = when {
                                keys.all(selected::contains) -> ToggleableState.On
                                keys.none(selected::contains) -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            }, enabled = !state.busy, onClick = { vm.select(entry.category, keys, keys.none(selected::contains)) }, modifier = Modifier.testTag(groupTag))
                            Column(Modifier.weight(1f).clickable(enabled = !state.busy) { group = entry }.padding(vertical = 8.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.safe_import_selected_count, keys.count(selected::contains), keys.size), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { group = entry }, enabled = !state.busy) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.safe_import_contents))
                            }
                        }
                    } }
                    if ("credentials_bundle" in state.selected[PortableDataCategory.CREDENTIALS].orEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state.choices.restoreCredentials, enabled = !state.busy, onCheckedChange = { vm.choices(state.choices.copy(restoreCredentials = it)) })
                            Text(stringResource(R.string.safe_import_credentials_consent), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item { Text(stringResource(R.string.safe_import_defaults), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SafeImportStep.CONFIRM -> state.plan?.let { plan ->
                    item {
                        Text(stringResource(R.string.import_review_preview), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.import_review_preview_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.planValid) item {
                        PortableImportChangeSummary(plan)
                    }
                    if (state.planValid && plan.rows.sourceConflictMemoryCount > 0) item {
                        PortableImportSourceNotice(plan, state.busy, reviewSources)
                    }
                    if (!state.planValid) item { Text(stringResource(if (state.busy) R.string.safe_import_checking else R.string.portability_ui_recheck), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("safe-import-summary-pending")) }
                    if (!sourceDecisionOnly && (plan.rows.differences.isNotEmpty() || plan.fileDifferences.isNotEmpty() || plan.settingDifferences.isNotEmpty() || plan.storyDifferent)) item {
                        TextButton(onClick = { if (vm.beginDifferenceReview()) { reviewRowKeys = null; showDifferences = true } }, enabled = !state.busy && state.planValid) { Text(stringResource(R.string.safe_import_changes)) }
                    }
                    if (state.planValid && plan.issues.isNotEmpty() && !sourceDecisionOnly) item {
                        TextButton(onClick = { showIssues = true }, enabled = !state.busy) { Text(stringResource(R.string.safe_import_dependencies, plan.issues.size), color = MaterialTheme.colorScheme.error) }
                    }
                    if (state.planValid && (plan.rows.sameNameCopies > 0 || plan.rows.newKeys.any { it.startsWith("local_characters:") })) item {
                        Text(stringResource(R.string.portability_ui_character_preview, plan.rows.newKeys.count { it.startsWith("local_characters:") }, plan.rows.sameNameCopies), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.choices.restoreCredentials && "credentials_bundle" in state.selected[PortableDataCategory.CREDENTIALS].orEmpty()) item { Text(stringResource(R.string.safe_import_credentials_consent), style = MaterialTheme.typography.bodyMedium) }
                    if (state.planValid && plan.useStory) item { Text(stringResource(R.string.safe_import_story_confirm_message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (state.planValid && plan.rows.sensitiveFieldsOmitted > 0) item { Text(stringResource(R.string.safe_import_secrets_skipped, plan.rows.sensitiveFieldsOmitted), style = MaterialTheme.typography.bodySmall) }
                }
                SafeImportStep.RESULT -> state.result?.let { result ->
                    item {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.safe_import_done), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.safe_import_result, result.newRows, result.updatedRows, result.importedFiles, result.sameItems, result.keptItems, result.coreMemories), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.safe_import_character_result, result.newCharacters, result.sameNameCopies), style = MaterialTheme.typography.bodyMedium)
                        if (result.credentialsRestored > 0) Text(stringResource(R.string.safe_import_credentials_result, result.credentialsRestored))
                        if (result.settingsUpdated > 0) Text(stringResource(R.string.safe_import_settings_result, result.settingsUpdated))
                        if (result.storyUpdated) Text(stringResource(R.string.safe_import_story_result))
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            val ready = !externalBusy && !state.picking && when (state.step) {
                SafeImportStep.FILE -> !state.passwordNeeded || state.passwordInput.isNotBlank()
                SafeImportStep.CONTENTS -> state.selected.values.any { it.isNotEmpty() }
                SafeImportStep.CONFIRM -> state.plan != null
                SafeImportStep.RESULT -> true
            }
            Button(onClick = {
                when {
                    state.busy -> vm.cancel()
                    state.step == SafeImportStep.RESULT -> onFinish()
                    state.step == SafeImportStep.FILE && state.route != null -> onNavigate(requireNotNull(state.route))
                    state.step == SafeImportStep.FILE && state.passwordNeeded -> state.sourceUri?.let { vm.inspect(context, it, state.passwordInput) }
                    state.step == SafeImportStep.FILE && state.prepared != null -> vm.continueContents()
                    state.step == SafeImportStep.FILE -> chooseFile()
                    state.step == SafeImportStep.CONTENTS -> vm.preview(context)
                    !state.planValid -> vm.preview(context)
                    sourceDecisionOnly -> reviewSources()
                    state.plan?.issues?.isNotEmpty() == true -> showIssues = true
                    else -> vm.commit(context)
                }
            }, enabled = state.busy || ready,
                modifier = Modifier.widthIn(min = 160.dp, max = 200.dp).heightIn(min = 48.dp)
                    .testTag(if (state.step == SafeImportStep.FILE) "safe-import-choose" else "safe-import-execute")) {
                Text(stringResource(when {
                    state.busy -> R.string.common_cancel
                    state.step == SafeImportStep.RESULT -> R.string.portability_ui_done
                    state.step == SafeImportStep.FILE && state.route != null -> R.string.safe_import_open_dedicated
                    state.step == SafeImportStep.FILE && state.passwordNeeded -> R.string.portability_analyze_archive
                    state.step == SafeImportStep.FILE && state.prepared != null -> R.string.portability_ui_next
                    state.step == SafeImportStep.FILE -> R.string.portability_choose_archive
                    state.step == SafeImportStep.CONTENTS -> R.string.portability_ui_next
                    !state.planValid -> R.string.safe_import_refresh
                    sourceDecisionOnly -> R.string.import_review_compare
                    state.plan?.issues?.isNotEmpty() == true -> R.string.portability_ui_resolve
                    else -> R.string.import_review_start
                }), textAlign = TextAlign.Center)
            }
        }
    }
    if (archiveInfo) state.prepared?.let { prepared ->
        NekoDialog(onDismiss = { archiveInfo = false }, title = stringResource(R.string.portability_archive_summary),
            confirmText = stringResource(R.string.common_close), onConfirm = { archiveInfo = false }, cancelText = null) {
            Text(stringResource(R.string.portability_archive_meta, prepared.preview.sourceVersion.ifBlank { "—" }, prepared.preview.exportedAt.ifBlank { "—" },
                stringResource(if (prepared.preview.encrypted) R.string.portability_encrypted else R.string.portability_not_encrypted)))
        }
    }
    group?.let { item ->
        NekoDialog(onDismiss = { group = null }, title = item.title, confirmText = stringResource(R.string.common_confirm), onConfirm = { group = null }, cancelText = null, contentScrollable = true) {
            item.details.forEach { detail ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = detail.key in state.selected[item.category].orEmpty(), onCheckedChange = { vm.select(item.category, setOf(detail.key), it) }, enabled = !state.busy)
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(importDetailLabel(detail), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.portability_item_count, detail.itemCount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showDifferences) state.plan?.let { plan ->
        DisposableEffect(vm) { onDispose { vm.discardDifferenceReview() } }
        PortableImportDifferences(plan, state.choices, state.busy, onDismiss = { vm.discardDifferenceReview(); showDifferences = false },
            onSave = { choices -> if (vm.saveDifferenceReview(context, choices)) showDifferences = false },
            onMore = { choices -> vm.choices(choices); vm.preview(context, more = true) },
            onBulkRecords = { choices, useIncoming, individualRows -> vm.chooseAllRecordFields(context, choices, useIncoming,
                reviewRowKeys, individualRows, completeReview = true, onComplete = { showDifferences = false }) },
            reviewRowKeys = reviewRowKeys, error = state.error)
    }
    if (showIssues) state.plan?.let { plan ->
        PortableImportIssueDialog(plan, state.choices, state.busy, onDismiss = { showIssues = false },
            onBack = { showIssues = false; vm.backToContents() }, onReview = reviewSources,
            onChoices = { vm.choices(it) }, onSelect = { option, selected -> vm.select(option.category, setOf(option.detail.key), selected) })
    }
    if (storyConfirm) NekoDialog(onDismiss = { storyConfirm = false }, title = stringResource(R.string.safe_import_story_confirm_title),
        message = stringResource(R.string.safe_import_story_confirm_message), confirmText = stringResource(R.string.common_confirm),
        onConfirm = { storyConfirm = false; vm.choices(state.choices.copy(files = state.choices.files + "plot_story")) },
        onCancel = { storyConfirm = false })
}

@Composable
private fun ImportSteps(step: SafeImportStep) {
    val labels = listOf(R.string.portability_ui_file_step, R.string.portability_ui_contents_step, R.string.portability_ui_confirm_step)
    val current = step.ordinal.coerceAtMost(2)
    Row(Modifier.fillMaxWidth().testTag("safe-import-step-${step.name.lowercase()}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = if (index == current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ImportStat(label: String, count: Int, modifier: Modifier) {
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(count.toString(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
}

private val INDEPENDENT_MEMORY_TABLES = setOf("companion_scopes", "companion_memories", "companion_sources", "companion_receipts",
    "companion_forgotten_sources", "companion_pending", "companion_vectors", "companion_clarifications", "companion_owners")

/** Recognition only: dedicated formats retain their own existing validation/permission screens. */
internal fun recognizeDedicatedImport(context: Context, uri: Uri): String? = runCatching {
    val prefix = context.contentResolver.openInputStream(uri)?.use { input -> ByteArray(4096).let { data -> val count = input.read(data); data.copyOf(count.coerceAtLeast(0)) } } ?: return null
    if (prefix.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte())) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 64 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: return null
        require(bytes.size <= 64 * 1024 * 1024)
        return if (com.nekobot.app.ServiceContainer.localRepository.extractCharacterJsonFromPng(bytes) != null) Routes.CHARACTERS else null
    }
    val text = String(prefix, Charsets.UTF_8).trimStart()
    if (text.startsWith("SQLite format 3")) return Routes.DB_PROFILE
    if (text.startsWith("[") || text.startsWith("{")) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 4 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: return null
        if (raw.size > 4 * 1024 * 1024) return null
        val json = JsonParser.parseString(String(raw, Charsets.UTF_8))
        if (json.isJsonArray) return Routes.SESSIONS
        if (json.isJsonObject) {
            val obj = json.asJsonObject
            if (obj.has("source_format")) return null
            if (listOf("session", "sessions", "messages").any(obj::has)) return Routes.SESSIONS
            if (obj.has("spec") || obj.has("name") && listOf("description", "personality", "first_mes").any(obj::has)) return Routes.CHARACTERS
        }
    }
    if (prefix.size >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
        val temp = File.createTempFile("import-identify-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 512L * 1024 * 1024); output.write(buffer, 0, count) }
            } }
            ZipFile(temp).use { zip ->
                val names = zip.entries().asSequence().take(50001).map { it.name }.toSet()
                require(names.size <= 50000)
                when {
                    "plugin.json" in names -> Routes.PLUGINS
                    names.any { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) } -> Routes.SKILLS
                    names.any { it.substringAfterLast('/') == "character.json" } -> Routes.CHARACTERS
                    names.any { it.endsWith(".db") } -> Routes.DB_PROFILE
                    "config.nbotcfg" in names -> Routes.CONFIG_TRANSFER
                    "word/document.xml" in names || "META-INF/container.xml" in names -> Routes.KNOWLEDGE
                    else -> null
                }
            }
        } finally { temp.delete() }
    } else if (text.startsWith("%PDF") || context.contentResolver.getType(uri)?.startsWith("text/") == true) Routes.KNOWLEDGE else null
}.getOrNull()

@Composable internal fun importDetailLabel(detail: PortableCategoryDetail): String {
    val key = detail.key.removePrefix("table:")
    return when (key) {
        "companion_memories" -> stringResource(R.string.safe_import_memory_bodies)
        "companion_sources" -> stringResource(R.string.safe_import_memory_sources)
        "companion_scopes", "companion_owners" -> stringResource(R.string.safe_import_memory_ownership)
        "companion_receipts", "companion_pending" -> stringResource(R.string.safe_import_memory_progress)
        "companion_forgotten_sources" -> stringResource(R.string.safe_import_memory_forgotten)
        "companion_vectors" -> stringResource(R.string.safe_import_memory_vectors)
        "companion_clarifications" -> stringResource(R.string.safe_import_memory_clarifications)
        else -> portableDetailLabel(detail)
    }
}

@Composable internal fun importFieldLabel(column: String): String = when (column) {
    "content" -> stringResource(R.string.safe_import_field_content)
    "name", "title" -> stringResource(R.string.safe_import_field_name)
    "category" -> stringResource(R.string.safe_import_field_category)
    "alwaysProvide" -> stringResource(R.string.safe_import_field_core)
    "description" -> stringResource(R.string.safe_import_field_description)
    "updated_at", "updatedAt" -> stringResource(R.string.safe_import_field_updated)
    "expiresAt", "expires_at" -> stringResource(R.string.safe_import_field_expiry)
    "status" -> stringResource(R.string.safe_import_field_status)
    "current" -> stringResource(R.string.safe_import_field_current)
    else -> column
}
