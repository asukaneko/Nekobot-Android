package com.nekobot.app.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.screens.memory.memoryCategoryNames

/** Every choice remains local until Next opens the import preview; only the final action writes data. */
@Composable
internal fun PortableImportDifferences(plan: PortableImportPreview, choices: PortableImportChoices, busy: Boolean,
    onDismiss: () -> Unit, onSave: (PortableImportChoices) -> Unit, onMore: (PortableImportChoices) -> Unit,
    onBulkRecords: (PortableImportChoices, Boolean, Set<String>) -> Unit, reviewRowKeys: Set<String>? = null,
    error: String? = null) {
    var draft by remember(plan) { mutableStateOf(choices.normalized()) }
    var confirmStory by remember { mutableStateOf(false) }
    var bulkChoice by remember(plan) { mutableStateOf<Boolean?>(null) }
    var individualRows by remember(plan) { mutableStateOf(emptySet<String>()) }
    val errorView = remember { BringIntoViewRequester() }
    LaunchedEffect(error) { if (error != null) errorView.bringIntoView() }
    val focused = reviewRowKeys != null
    var visibleCount by remember(plan) { mutableIntStateOf(50) }
    val allReviewRows = plan.rows.differences.filter { reviewRowKeys == null || it.key in reviewRowKeys }
    val reviewRows = if (focused) allReviewRows.take(visibleCount) else allReviewRows
    val count = reviewRowKeys?.size ?: plan.rows.counts.different
    val hasChoices = bulkChoice != null || !focused || allReviewRows.size == count && allReviewRows.all { row ->
        row.key in individualRows || row.fields.all { it.column in draft.fields[row.key].orEmpty() } || row.memoryId in draft.keepMemorySources
    }
    NekoDialog(onDismiss = onDismiss, title = stringResource(if (focused) R.string.import_review_compare else R.string.safe_import_changes),
        message = null,
        confirmText = stringResource(R.string.portability_ui_next), confirmEnabled = !busy && hasChoices,
        onConfirm = { bulkChoice?.let { onBulkRecords(draft.normalized(), it, individualRows) } ?: onSave(draft.normalized()) },
        onCancel = onDismiss, contentScrollable = true) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.bringIntoViewRequester(errorView).testTag("import-review-error")) }
        Text(stringResource(R.string.import_review_memory_choice_hint), style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.safe_import_checking), style = MaterialTheme.typography.bodyMedium)
        }
        if (count > 0) {
            Text(stringResource(if (focused) R.string.import_review_related_scope else R.string.import_bulk_record_scope, count),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                listOf(true, false).forEach { incoming ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = bulkChoice == incoming,
                        enabled = !busy, role = Role.RadioButton, onClick = {
                            bulkChoice = incoming; individualRows = emptySet()
                            allReviewRows.forEach { row -> draft = draft.withRecordChoice(row, incoming,
                                plan.rows.sourceFieldChoices[row.memoryId].orEmpty()) }
                        }).testTag(if (incoming) "safe-difference-bulk-record-incoming" else "safe-difference-bulk-record-keep")
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = bulkChoice == incoming, onClick = null, enabled = !busy)
                        Text(stringResource(if (incoming) R.string.import_review_use_file else R.string.import_review_keep_current),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        reviewRows.forEachIndexed { index, row ->
            DifferenceHeading(importDetailLabel(PortableCategoryDetail("table:${row.table}", 1)), row.label, row.owner)
            if (focused || row.table in setOf("companion_memories", "local_characters")) {
                val visible = row.fields.filter { it.column in setOf("content", "reasoning_content", "subject", "name", "description", "basic_info", "rules", "personality", "category", "alwaysProvide") }
                visible.forEach { field ->
                    Text(when (field.column) {
                        "subject" -> stringResource(R.string.import_review_memory_person)
                        "basic_info" -> stringResource(R.string.import_review_basic_info)
                        "rules" -> stringResource(R.string.import_review_rules)
                        "personality" -> stringResource(R.string.import_review_personality)
                        else -> importFieldLabel(field.column)
                    },
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                }
                val other = row.fields - visible.toSet()
                if (other.isNotEmpty()) {
                    Text(stringResource(if (row.memoryId != null) R.string.import_review_memory_metadata else R.string.import_review_record_metadata),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var expanded by remember(row.key) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.import_review_hide_details else R.string.import_review_details)) }
                    if (expanded) other.forEach { field ->
                        Text(importFieldLabel(field.column), style = MaterialTheme.typography.titleSmall)
                        DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                    }
                }
                val decision = when {
                    row.fields.all { it.column in draft.fields[row.key].orEmpty() } -> true
                    row.memoryId in draft.keepMemorySources -> false
                    else -> if (focused) null else false
                }
                DifferenceChoice(decision, !busy,
                    if (focused) "safe-difference-memory-$index" else "safe-difference-row-$index-${row.fields.first().column}",
                    if (row.memoryId != null) R.string.import_review_keep_memory else R.string.import_review_keep_record,
                    if (row.memoryId != null) R.string.import_review_update_memory else R.string.import_review_update_record) { replace ->
                    draft = draft.withRecordChoice(row, replace, plan.rows.sourceFieldChoices[row.memoryId].orEmpty())
                    individualRows = individualRows + row.key + plan.rows.sourceFieldChoices[row.memoryId].orEmpty().keys
                }
            } else row.fields.forEach { field ->
                Text(importFieldLabel(field.column), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                DifferenceChoice(field.column in draft.fields[row.key].orEmpty(), !busy, "safe-difference-row-$index-${field.column}") { replace ->
                    val fields = draft.fields[row.key].orEmpty()
                    draft = draft.copy(fields = draft.fields + (row.key to if (replace) fields + field.column else fields - field.column)).normalized()
                    individualRows = individualRows + row.key
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        if (focused && reviewRows.size < allReviewRows.size) TextButton(onClick = { visibleCount += 50 }, enabled = !busy) {
            Text(stringResource(R.string.safe_import_more))
        }
        plan.fileDifferences.filter { !focused }.forEachIndexed { index, file ->
            DifferenceHeading(stringResource(R.string.import_difference_file), file.fileName, file.owner)
            Text(portableDetailLabel(PortableCategoryDetail("root:${file.root}", 1, true)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val extension = file.fileName.substringAfterLast('.', "").uppercase().ifBlank { stringResource(R.string.import_difference_unknown_type) }
            Text(stringResource(R.string.import_difference_type, extension), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DifferenceValues(fileSize(file.currentBytes) + checksum(file.currentHash), fileSize(file.incomingBytes) + checksum(file.incomingHash))
            DifferenceChoice(file.key in draft.files, !busy, "safe-difference-file-$index") { replace ->
                draft = draft.copy(files = if (replace) draft.files + file.key else draft.files - file.key)
            }
            var pathVisible by remember(file.key) { mutableStateOf(false) }
            TextButton(onClick = { pathVisible = !pathVisible }) { Text(stringResource(if (pathVisible) R.string.import_difference_hide_path else R.string.import_difference_show_path)) }
            if (pathVisible) SelectionContainer { Text(file.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        plan.settingDifferences.filter { !focused }.forEach { field ->
            DifferenceHeading(stringResource(R.string.portability_category_app_settings), importFieldLabel(field.column), PortableImportOwner())
            DifferenceValues(field.current, field.incoming)
            DifferenceChoice(field.column in draft.settings, !busy, "safe-difference-setting-${field.column}") { replace ->
                draft = draft.copy(settings = if (replace) draft.settings + field.column else draft.settings - field.column)
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        if (plan.storyDifferent && !focused) {
            DifferenceHeading(stringResource(R.string.safe_import_story_confirm_title), stringResource(R.string.safe_import_story), PortableImportOwner())
            Text(stringResource(R.string.safe_import_story_confirm_message), style = MaterialTheme.typography.bodyMedium)
            DifferenceChoice("plot_story" in draft.files, !busy, "safe-difference-story") { replace ->
                if (replace) confirmStory = true else draft = draft.copy(files = draft.files - "plot_story")
            }
        }
        val more = plan.rows.differences.size < plan.rows.counts.different || plan.fileDifferences.size < plan.fileCounts.different
        if (more && !focused && bulkChoice == null) TextButton(onClick = { onMore(draft.normalized()) }, enabled = !busy) { Text(stringResource(R.string.safe_import_more)) }
    }
    if (confirmStory) NekoDialog(onDismiss = { confirmStory = false }, title = stringResource(R.string.safe_import_story_confirm_title),
        message = stringResource(R.string.safe_import_story_confirm_message),
        onConfirm = { confirmStory = false; draft = draft.copy(files = draft.files + "plot_story") }, onCancel = { confirmStory = false })
}

@Composable private fun DifferenceHeading(kind: String, title: String, owner: PortableImportOwner) {
    Text(kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    val parts = buildList {
        if (owner.character.isNotBlank() && owner.character.trim() != title.trim())
            add(stringResource(R.string.import_difference_character, owner.character))
        if (owner.session.isNotBlank() && owner.session.trim() != title.trim())
            add(stringResource(R.string.import_difference_session, owner.session))
        if (owner.shared) add(stringResource(R.string.import_difference_shared))
    }
    if (parts.isNotEmpty()) Text(parts.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun DifferenceValues(current: String, incoming: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DifferenceValue(stringResource(R.string.import_difference_current), current)
        DifferenceValue(stringResource(R.string.import_difference_incoming), incoming)
    }
}

@Composable private fun DifferenceValue(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable private fun DifferenceChoice(replace: Boolean?, enabled: Boolean, tag: String,
    keepLabel: Int = R.string.import_difference_keep, updateLabel: Int = R.string.import_difference_replace, onSelect: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)) {
        listOf(false, true).forEach { useIncoming ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = replace == useIncoming, enabled = enabled,
                role = Role.RadioButton, onClick = { onSelect(useIncoming) }).testTag(tag + if (useIncoming) "-replace" else "-keep").padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = replace == useIncoming, enabled = enabled, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (useIncoming) updateLabel else keepLabel),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun fileSize(bytes: Long): String = if (bytes < 0) stringResource(R.string.import_difference_unknown_size)
    else Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable private fun checksum(value: String): String = if (value.isBlank()) "" else "\n" + stringResource(R.string.import_difference_checksum, value.take(12))

@Composable private fun importDifferenceValue(column: String, value: String): String = when {
    column == "category" -> memoryCategoryNames[value] ?: value
    column in setOf("alwaysProvide", "current", "valid", "active", "enabled") && value in setOf("0", "1") -> stringResource(if (value == "1") R.string.common_yes else R.string.common_no)
    else -> value
}
