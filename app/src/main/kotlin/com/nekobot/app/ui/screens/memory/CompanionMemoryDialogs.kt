package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.ai.CompanionMemorySourceView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 记忆入口内的宽面板：正文独立滚动，底部动作始终可见，避开系统栏与键盘。 */
@Composable
private fun MemoryPanel(
    title: String, subtitle: String, busy: Boolean = false, onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val textResources = LocalResources.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding().padding(20.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Filled.Close, textResources.getString(R.string.common_close)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    content(Modifier.weight(1f, fill = false))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionMemoryEditor(
    memory: CompanionMemoryEntity?, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (String, String, List<String>, Boolean, Boolean) -> Unit
) {
    val textResources = LocalResources.current
    var content by rememberSaveable(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var tags by rememberSaveable(memory?.id) { mutableStateOf(memory?.let { MemoryTags.fromJson(it.tagsJson).joinToString("，") }.orEmpty()) }
    var category by rememberSaveable(memory?.id) { mutableStateOf(memory?.category ?: "user_profile") }
    var pinned by rememberSaveable(memory?.id) { mutableStateOf(memory?.let { CompanionCoreMemory.contains(it) } ?: false) }
    var locked by rememberSaveable(memory?.id) { mutableStateOf(memory?.locked ?: false) }
    MemoryPanel(if (memory == null) textResources.getString(R.string.single_role_add_a_memory) else textResources.getString(R.string.single_role_edit_this_memory), textResources.getString(R.string.single_role_write_something_you_want_the_character_to_remember), busy, onDismiss,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(textResources.getString(R.string.common_cancel)) }
            Button(onClick = { onSave(content, category, tags.split(',', '，').map(String::trim).filter(String::isNotEmpty), pinned, locked) },
                enabled = !busy && content.isNotBlank() && content.length <= 1500) {
                Text(if (busy) textResources.getString(R.string.single_role_saving) else textResources.getString(R.string.single_role_save_memory))
            }
        }) { body ->
        Column(body.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            error?.let { CompanionMemoryNotice(textResources.getString(R.string.single_role_this_memory_has_not_been_saved_yet), it) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(textResources.getString(R.string.single_role_memory_content)) },
                    placeholder = { Text(textResources.getString(R.string.single_role_for_example_i_now_prefer_unsweetened_jasmine_tea)) }, minLines = 4, maxLines = 9,
                    shape = RoundedCornerShape(16.dp), isError = content.length > 1500,
                    supportingText = { Text(textResources.getString(R.string.single_role_1_s_1500_characters, content.length)) })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(textResources.getString(R.string.single_role_category), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                MemoryCategorySelector(category, { category = it }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text(when (category) {
                    "user_profile" -> textResources.getString(R.string.single_role_your_preferences_habits_and_personal_information)
                    "character_profile" -> textResources.getString(R.string.single_role_traits_the_character_has_shown_while_spending_time_together)
                    "relationship" -> textResources.getString(R.string.single_role_your_relationship_and_how_it_has_changed)
                    "episode" -> textResources.getString(R.string.single_role_events_that_actually_happened_alone_or_together)
                    "promise" -> textResources.getString(R.string.single_role_promises_including_whether_they_were_fulfilled_or_canceled)
                    else -> textResources.getString(R.string.single_role_unresolved_matters_that_need_follow_up)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(textResources.getString(R.string.single_role_tags_optional)) }, placeholder = { Text(textResources.getString(R.string.single_role_drinks_preferences)) },
                supportingText = { Text(textResources.getString(R.string.single_role_separate_with_commas_to_make_searching_easier)) }, shape = RoundedCornerShape(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
                Column {
                    MemoryOption(textResources.getString(R.string.single_role_set_as_core_memory), textResources.getString(R.string.single_role_included_in_every_chat_once_you_move_it_into_or_out_of_core_memor),
                        pinned, !busy) { pinned = it }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    MemoryOption(textResources.getString(R.string.single_role_only_i_can_edit), textResources.getString(R.string.single_role_ai_will_not_rewrite_this_automatically_you_can_still_edit_or_dele), locked, !busy) { locked = it }
                }
            }
        }
    }
}

@Composable
private fun MemoryOption(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun CompanionMemoryChanges(rows: List<CompanionMemoryEntity>, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onRestore: (CompanionMemoryEntity) -> Unit, onSource: (CompanionMemoryEntity) -> Unit) {
    val textResources = LocalResources.current
    MemoryPanel(textResources.getString(R.string.single_role_revision_history), textResources.getString(R.string.single_role_view_earlier_versions_of_this_memory_opening_this_page_does_not_c), busy = busy, onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss, enabled = !busy) { Text(textResources.getString(R.string.character_got_it)) } }) { body ->
        LazyColumn(body.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) item("busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item("error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (rows.isEmpty()) item { Text(textResources.getString(R.string.single_role_no_revision_history_available_yet)) }
            items(rows, key = { it.id }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = if (row.current)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when {
                            row.voided -> textResources.getString(R.string.memory_v2_voided)
                            row.current -> textResources.getString(R.string.single_role_current_content)
                            row.status == "conflict" -> textResources.getString(R.string.single_role_content_awaiting_verification)
                            row.status == "locked_conflict" -> textResources.getString(R.string.single_role_content_awaiting_your_edits)
                            else -> textResources.getString(R.string.single_role_earlier_content)
                        }, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge, color = memoryAccent())
                        SelectionContainer { Text(row.content.ifBlank { textResources.getString(R.string.single_role_this_content_was_deleted) }, style = MaterialTheme.typography.bodyLarge) }
                        Text("${memorySavedTime(row.updatedAt, textResources.getString(R.string.single_role_save_time_not_recorded))} · ${if (row.manual) textResources.getString(R.string.single_role_saved_manually) else textResources.getString(R.string.single_role_organized_automatically)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.voided) {
                            Text("${textResources.getString(R.string.memory_v2_void_reason)} · ${textResources.getString(memoryVoidReason(row.voidedReason))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${textResources.getString(R.string.memory_v2_void_time)} · ${memorySavedTime(row.voidedAt.orEmpty(), textResources.getString(R.string.single_role_save_time_not_recorded))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = { onRestore(row) }, enabled = !busy && row.content.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(textResources.getString(R.string.memory_v2_restore))
                            }
                        }
                        TextButton(onClick = { onSource(row) }, enabled = !busy) {
                            Text(textResources.getString(R.string.memory_v2_source_title))
                        }
                    }
                }
            }
        }
    }
}

internal fun memoryVoidReason(reason: String?): Int = when (reason) {
    "update" -> R.string.memory_v2_reason_update
    "cancel" -> R.string.memory_v2_reason_cancel
    "complete" -> R.string.memory_v2_reason_complete
    "retract" -> R.string.memory_v2_reason_retract
    "source_deleted" -> R.string.memory_v2_reason_source_deleted
    "source_edited" -> R.string.memory_v2_reason_source_edited
    "uncertain_confirmed" -> R.string.memory_v2_reason_uncertain_confirmed
    else -> R.string.memory_v2_reason_user
}

private fun memorySavedTime(value: String, unrecorded: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value.ifBlank { unrecorded })

@Composable
internal fun CompanionMemorySources(
    rows: List<CompanionMemorySourceView>, memory: CompanionMemoryEntity?, ownerName: String,
    onDismiss: () -> Unit, onLocate: (String) -> Unit
) {
    val textResources = LocalResources.current
    MemoryPanel(textResources.getString(R.string.memory_v2_source_title), if (rows.isEmpty()) textResources.getString(R.string.single_role_no_original_messages_to_locate)
        else textResources.getString(R.string.single_role_this_memory_refers_to_1_s_original_messages_open_each_to_view_the, rows.size), onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text(textResources.getString(R.string.common_close)) } }) { body ->
        LazyColumn(body.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            memory?.let { item("memory") {
                Text(textResources.getString(R.string.global_memory_editor_title), style = MaterialTheme.typography.labelLarge, color = memoryAccent())
                Text(it.content, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (rows.isNotEmpty() && memory?.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED) item("partial-sources") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(textResources.getString(R.string.imported_history_partial_sources_title),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(textResources.getString(R.string.imported_history_partial_sources_message),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rows.isEmpty()) item("empty") {
                CompanionMemoryIntro(textResources.getString(R.string.single_role_no_original_messages_linked_yet), when {
                    memory?.manual == true -> textResources.getString(R.string.single_role_you_entered_this_memory_manually_it_has_no_linked_chat_sources)
                    memory?.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED -> textResources.getString(R.string.single_role_this_memory_came_from_older_data_its_original_sources_have_not_be)
                    else -> textResources.getString(R.string.single_role_no_verifiable_chat_source_found_check_that_the_memory_is_accurate)
                })
            }
            items(rows, key = { it.source.messageId }) { view ->
                val source = view.source
                val isUser = source.role in setOf("user", "human")
                Surface(shape = RoundedCornerShape(18.dp), color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (isUser) textResources.getString(R.string.live_you) else ownerName.ifBlank { textResources.getString(R.string.nav_characters) },
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = memoryAccent())
                        if (view.originalDeleted) Text(textResources.getString(R.string.memory_v2_source_deleted),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (view.snapshot) Text(textResources.getString(R.string.memory_v2_source_snapshot),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(view.content, style = MaterialTheme.typography.bodyLarge) }
                        source.spokenDate?.takeIf(String::isNotBlank)?.let { Text(textResources.getString(R.string.single_role_message_date_1_s, memoryDisplayDate(it)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!view.originalDeleted) TextButton(onClick = { onLocate(source.messageId) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(textResources.getString(R.string.memory_v2_source_locate))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompanionMemoryDelete(memory: CompanionMemoryEntity, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val textResources = LocalResources.current
    MemoryPanel(textResources.getString(R.string.single_role_delete_this_memory), textResources.getString(R.string.single_role_original_chat_messages_will_be_kept), busy, onDismiss,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(textResources.getString(R.string.single_role_keep_memory)) }
            Button(onClick = onDelete, enabled = !busy, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) textResources.getString(R.string.single_role_deleting) else textResources.getString(R.string.memory_delete_memory_title))
            }
        }) { body ->
        Column(body.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { CompanionMemoryNotice(textResources.getString(R.string.single_role_not_deleted_yet), it) }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Text(memory.content, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(textResources.getString(R.string.single_role_this_memory_and_its_revisions_will_be_removed_organization_progre),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
