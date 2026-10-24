package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionMemoryOverview
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.db.CompanionMemoryEntity

// 分类鍵与记忆协议一致；显示名称跟随界面语言。
internal val memoryCategoryNames: Map<String, String>
    @Composable get() {
        val resources = LocalResources.current
        return linkedMapOf(
            "user_profile" to resources.getString(R.string.single_role_category_user),
            "character_profile" to resources.getString(R.string.single_role_category_character),
            "relationship" to resources.getString(R.string.single_role_category_relationship),
            "episode" to resources.getString(R.string.single_role_category_episode),
            "promise" to resources.getString(R.string.single_role_category_promise),
            "open_issue" to resources.getString(R.string.single_role_category_open_issue)
        )
    }

@Composable
internal fun memoryAccent(): Color = MaterialTheme.colorScheme.primary

internal fun companionMemoryColorScheme(colors: ColorScheme): ColorScheme = colors.copy(
    onPrimary = if (colors.surface.luminance() < 0.5f) colors.background else colors.onPrimary,
    primaryContainer = lerp(colors.surface, colors.primary, 0.12f),
    onPrimaryContainer = colors.primary,
    surfaceContainerLow = lerp(colors.surface, colors.onSurface, 0.035f))

/** 只在独立记忆入口内应用，不覆盖其他页面的用户主题。 */
@Composable
fun CompanionMemoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = companionMemoryColorScheme(MaterialTheme.colorScheme), content = content)
}

@Composable
internal fun memoryCategoryColor(category: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (category) {
        "episode", "open_issue" -> if (dark) Color(0xFFE4BA7B) else Color(0xFF9D6B2C)
        "relationship", "promise" -> if (dark) Color(0xFFDEAABD) else Color(0xFFA2637C)
        "character_profile" -> if (dark) Color(0xFFC9B7DA) else Color(0xFF8A6B9A)
        else -> memoryAccent()
    }
}

@Composable
internal fun CompanionMemoryIntro(title: String, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_agent_character_memory), null, Modifier.size(44.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CompanionMemoryNotice(title: String, message: String, onRetry: (() -> Unit)? = null) {
    val textResources = LocalResources.current
    var details by remember(message) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { details = !details }) { Text(if (details) textResources.getString(R.string.single_role_hide_reason) else textResources.getString(R.string.single_role_view_reason)) }
                Spacer(Modifier.weight(1f))
                onRetry?.let { TextButton(onClick = it) { Text(textResources.getString(R.string.single_role_reload)) } }
            }
            if (details) SelectionContainer { Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
internal fun CompanionMemoryOverviewCard(
    ownerName: String,
    overview: CompanionMemoryOverview?,
    memoryCount: Int,
    pinnedTokens: Int,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val textResources = LocalResources.current
    var details by rememberSaveable(ownerName) { mutableStateOf(false) }
    val accent = memoryAccent()
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_agent_character_memory), null, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ownerName.ifBlank { textResources.getString(R.string.single_role_character_s_independent_memories) }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(textResources.getString(R.string.single_role_1_s_memories_saved, memoryCount), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, textResources.getString(R.string.sandbox_settings_refresh), tint = accent)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
            overview?.error?.let {
                CompanionMemoryNotice(textResources.getString(R.string.single_role_some_memories_have_not_been_organized_yet), it)
            }
            Row(Modifier.fillMaxWidth().clickable { details = !details }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(if (overview == null) { if (loading) textResources.getString(R.string.single_role_loading_organization_status) else textResources.getString(R.string.single_role_organization_status_unavailable) } else
                    textResources.getString(R.string.single_role_organized_1_s_pending_2_s, overview.processedMessages, overview.pendingMessages),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (details) textResources.getString(R.string.single_role_hide_details) else textResources.getString(R.string.single_role_organization_details), style = MaterialTheme.typography.labelMedium, color = accent)
                Icon(if (details) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (details) textResources.getString(R.string.single_role_collapse_organization_details) else textResources.getString(R.string.single_role_expand_organization_details), Modifier.size(20.dp), tint = accent)
            }
            if (details) {
                Text(textResources.getString(R.string.single_role_original_messages_are_always_kept_pending_items_include_recent_me, overview?.lastRetrieval ?: textResources.getString(R.string.single_role_no_chats_yet)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(textResources.getString(R.string.single_role_core_memories_use_about_1_s_tokens_and_are_included_in_full_every, pinnedTokens),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CompanionMemoryFilters(
    query: String, onQueryChange: (String) -> Unit,
    category: String, onCategoryChange: (String) -> Unit,
    counts: Map<String, Int>, total: Int, visibleCount: Int = total
) {
    val textResources = LocalResources.current
    val accent = memoryAccent()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = query, onValueChange = onQueryChange, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            placeholder = { Text(textResources.getString(R.string.single_role_search_memories_topics_or_tags)) },
            leadingIcon = { Icon(Icons.Filled.Search, textResources.getString(R.string.single_role_search_memories)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, textResources.getString(R.string.single_role_clear_search))
            } },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MemoryCategorySelector(category, onCategoryChange, counts = counts, total = total,
                includeAll = true, modifier = Modifier.weight(1f))
            Text(textResources.getString(R.string.single_role_1_s_items, visibleCount), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionMemoryCard(
    memory: CompanionMemoryEntity, enabled: Boolean,
    onSource: () -> Unit, onEdit: () -> Unit, onVersions: () -> Unit, onDelete: () -> Unit,
    onCoreChange: (() -> Unit)? = null, hasSources: Boolean = false
) {
    val textResources = LocalResources.current
    val accent = memoryCategoryColor(memory.category)
    var menu by remember(memory.id) { mutableStateOf(false) }
    val imported = !memory.manual && memory.provenance in setOf(CompanionImportPolicy.IMPORTED_UNVERIFIED, CompanionImportPolicy.IMPORTED_VERIFIED)
    val tags = remember(memory.tagsJson, imported) { MemoryTags.fromJson(memory.tagsJson).filterNot {
        imported && it in setOf("爱语原有记忆", "原话来源未验证", "原话来源已核验", "原话来源已验证")
    } }
    Surface(Modifier.fillMaxWidth().testTag("companion-memory-${memory.id}"), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, accent.copy(alpha = 0.10f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(memoryCategoryIcon(memory.category), null, Modifier.size(19.dp), tint = accent)
                Spacer(Modifier.width(7.dp))
                Text(memoryCategoryNames[memory.category] ?: memory.category, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent)
                if (imported && memory.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED && memory.status == "active") {
                    Text(textResources.getString(if (hasSources) R.string.memory_review_partial else R.string.memory_review_unlinked),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else MemoryStatusLabel(memory.status)
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Filled.MoreHoriz, textResources.getString(R.string.single_role_more_memory_actions)) }
                    DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                        if (onCoreChange != null && memory.current && memory.status in setOf("active", "completed")) {
                            DropdownMenuItem(text = { Text(if (CompanionCoreMemory.contains(memory)) textResources.getString(R.string.single_role_remove_from_core_memories) else textResources.getString(R.string.single_role_set_as_core_memory)) },
                                leadingIcon = { CoreMemoryIcon() },
                                onClick = { menu = false; onCoreChange() })
                        }
                        DropdownMenuItem(text = { Text(textResources.getString(R.string.memory_edit_title)) }, leadingIcon = { EditMemoryIcon() },
                            onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(textResources.getString(R.string.single_role_revision_history)) }, leadingIcon = { MemoryHistoryIcon() },
                            onClick = { menu = false; onVersions() })
                        DropdownMenuItem(text = { Text(textResources.getString(R.string.memory_delete_memory_title), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            SelectionContainer {
                Text(memory.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (!memory.eventDate.isNullOrBlank()) {
                Text(memoryDisplayDate(memory.eventDate.orEmpty()), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (memory.category == "episode") {
                Text(textResources.getString(R.string.single_role_event_date_unknown), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { Text("#$it", style = MaterialTheme.typography.labelMedium, color = accent) }
            }
            if (CompanionCoreMemory.contains(memory) || memory.locked || memory.manual) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (CompanionCoreMemory.contains(memory)) MemoryBadge(textResources.getString(R.string.single_role_core_memories)) { CoreMemoryIcon(Modifier.size(16.dp)) }
                if (memory.locked) MemoryBadge(textResources.getString(R.string.single_role_only_i_can_edit)) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = memoryAccent())
                }
                if (memory.manual) MemoryBadge(textResources.getString(R.string.single_role_manual_memory)) { EditMemoryIcon(Modifier.size(16.dp)) }
            }
            if (imported) {
                Text(textResources.getString(R.string.memory_review_imported_origin), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            memory.expiresAt?.let { Text(textResources.getString(R.string.single_role_valid_until_1_s, it), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSource, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                    Icon(Icons.Filled.FormatQuote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(textResources.getString(R.string.single_role_view_original_messages))
                }
            }
        }
    }
}

@Composable
private fun MemoryBadge(label: String, icon: (@Composable () -> Unit)? = null) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { it(); Spacer(Modifier.width(4.dp)) }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
}

@Composable
private fun MemoryStatusLabel(status: String) {
    val textResources = LocalResources.current
    val color = if (status in setOf("conflict", "locked_conflict")) MaterialTheme.colorScheme.error else memoryAccent()
    val label = when (status) {
        "active" -> textResources.getString(R.string.single_role_valid)
        "completed" -> textResources.getString(R.string.companion_completed)
        "cancelled", "canceled" -> textResources.getString(R.string.single_role_canceled)
        "conflict" -> textResources.getString(R.string.single_role_needs_verification)
        "locked_conflict" -> textResources.getString(R.string.single_role_needs_your_edits)
        "retracted" -> textResources.getString(R.string.single_role_withdrawn)
        "deleted" -> textResources.getString(R.string.localai_deleted)
        "expired" -> textResources.getString(R.string.single_role_expired)
        "superseded" -> textResources.getString(R.string.single_role_past_version)
        else -> status
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

internal fun memoryCategoryIcon(category: String): ImageVector = when (category) {
    "user_profile" -> Icons.Filled.PersonOutline
    "character_profile" -> Icons.Filled.Face
    "relationship" -> Icons.Filled.FavoriteBorder
    "episode" -> Icons.AutoMirrored.Filled.MenuBook
    "promise" -> Icons.Filled.EventAvailable
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

@Composable
internal fun CompanionMemoryEmpty(filtered: Boolean) {
    val textResources = LocalResources.current
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.ic_agent_character_memory), null, Modifier.size(64.dp))
        Text(if (filtered) textResources.getString(R.string.single_role_memory_not_found) else textResources.getString(R.string.single_role_memories_grow_over_time), style = MaterialTheme.typography.titleMedium)
        Text(if (filtered) textResources.getString(R.string.single_role_try_another_keyword_or_category) else textResources.getString(R.string.single_role_older_chats_are_saved_automatically_once_the_threshold_is_met_you),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
