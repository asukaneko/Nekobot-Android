package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.components.NekoDialog

@Composable
internal fun PortableImportChangeSummary(plan: PortableImportPreview) {
    val groups = linkedMapOf<Int, PortableImportCounts>()
    plan.rows.tableCounts.forEach { (table, count) ->
        val label = when (table) {
            "companion_sources" -> R.string.import_review_sources
            "companion_memories" -> R.string.import_review_memories
            "local_characters" -> R.string.import_review_characters
            "local_messages" -> R.string.import_review_messages
            else -> R.string.import_review_other
        }
        val old = groups[label] ?: PortableImportCounts()
        groups[label] = PortableImportCounts(old.added + count.added, old.same + count.same,
            old.different + count.different, old.updated + count.updated)
    }
    if (groups.isEmpty()) groups[R.string.import_review_other] = plan.rows.counts
    if (plan.fileCounts != PortableImportCounts()) groups[R.string.import_review_files] = plan.fileCounts
    if (plan.settingDifferences.isNotEmpty()) groups[R.string.import_review_settings] = PortableImportCounts(
        different = plan.settingDifferences.size, updated = plan.settingDifferences.count { it.column in plan.choices.settings })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("import-review-summary")) {
        groups.forEach { (label, counts) ->
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                val parts = listOfNotNull(
                    counts.added.takeIf { it > 0 }?.let { stringResource(R.string.import_review_added, it) },
                    counts.updated.takeIf { it > 0 }?.let { stringResource(R.string.import_review_updated, it) },
                    counts.kept.takeIf { it > 0 }?.let { stringResource(R.string.import_review_kept, it) },
                    counts.same.takeIf { it > 0 }?.let { stringResource(R.string.import_review_same, it) })
                Text(parts.joinToString("；"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if ((plan.rows.tableCounts["companion_sources"]?.added ?: 0) > 0)
            Text(stringResource(R.string.import_review_sources_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (plan.rows.skippedSourceRows > 0)
            Text(stringResource(R.string.import_review_skipped_sources, plan.rows.skippedSourceRows), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PortableImportSourceNotice(plan: PortableImportPreview, busy: Boolean, onReview: () -> Unit) {
    if (plan.rows.sourceConflictMemoryCount == 0) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("import-source-conflict")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.import_review_source_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.import_review_source_message, plan.rows.sourceConflictMemoryCount), style = MaterialTheme.typography.bodyMedium)
            if (plan.rows.sourceConflictKeys.isNotEmpty()) TextButton(onClick = onReview, enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth().testTag("import-review-related")) {
                Text(stringResource(R.string.import_review_compare_count, plan.rows.sourceReviewKeys.size))
            }
        }
    }
}

@Composable
internal fun PortableImportIssueDialog(plan: PortableImportPreview, choices: PortableImportChoices, busy: Boolean,
    onDismiss: () -> Unit, onBack: () -> Unit, onReview: () -> Unit,
    onChoices: (PortableImportChoices) -> Unit, onSelect: (PortableImportDependencyOption, Boolean) -> Unit) {
    val canReview = plan.rows.sourceConflictKeys.isNotEmpty()
    NekoDialog(onDismiss = onDismiss, title = stringResource(R.string.import_review_attention),
        confirmText = stringResource(if (canReview) R.string.import_review_compare else R.string.import_review_adjust),
        confirmEnabled = !busy, onConfirm = if (canReview) onReview else onBack,
        cancelText = if (canReview) stringResource(R.string.import_review_adjust) else null,
        onCancel = onBack, contentScrollable = true) {
        plan.issues.forEach { issue ->
            val message = when {
                issue.contains("记忆正文、主体或归属") -> stringResource(R.string.import_review_source_message, plan.rows.sourceConflictMemoryCount)
                issue.contains("记忆原话来源与当前聊天") -> stringResource(R.string.import_review_chat_changed)
                issue.contains("已核验的迁入记忆缺少原话") -> stringResource(R.string.import_review_missing_source)
                issue.contains("同名但ID不同的角色") -> stringResource(R.string.import_review_duplicate_message, plan.rows.sameNameCopies)
                issue.contains("正在生成") -> stringResource(R.string.import_review_generating)
                else -> stringResource(R.string.import_review_other_issue)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
            var details by remember(issue) { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.import_review_hide_details else R.string.import_review_details)) }
            if (details) Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.import_review_no_write), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.dependencyOptions.forEach { option ->
            Row {
                Checkbox(option.detail.key in plan.selected[option.category].orEmpty(), enabled = !busy,
                    onCheckedChange = { onSelect(option, it) })
                Text(importDetailLabel(option.detail), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (plan.rows.sameNameCopies > 0) Row(Modifier.testTag("import-review-same-name")) {
            Checkbox(choices.allowSameName, enabled = !busy, onCheckedChange = { onChoices(choices.copy(allowSameName = it)) })
            Text(stringResource(R.string.import_review_duplicate_consent, plan.rows.sameNameCopies), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
