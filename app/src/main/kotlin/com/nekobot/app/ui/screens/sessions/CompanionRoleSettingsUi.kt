package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import com.nekobot.app.data.model.userFacingTitle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import coil.compose.AsyncImage
import com.nekobot.app.data.model.Session
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.screens.chat.*

/** Presentation only. Values and persistence stay in SessionDetailViewModel. */
@Composable
internal fun CompanionRoleSettingsContent(
    session: Session, name: String, tags: String, pinned: Boolean, favorite: Boolean,
    onNameChange: (String) -> Unit, onTagsChange: (String) -> Unit,
    onPinnedChange: (Boolean) -> Unit, onFavoriteChange: (Boolean) -> Unit,
    onMemories: () -> Unit, onRelationship: () -> Unit, onModels: (() -> Unit)?, onAdvanced: () -> Unit,
    delayEnabled: Boolean, onDelayChange: (Boolean) -> Unit, delayEligible: Boolean,
    ttsEnabled: Boolean, onTtsChange: (Boolean) -> Unit,
    proactiveEnabled: Boolean, onProactiveChange: (Boolean) -> Unit,
    notificationEnabled: Boolean, onNotificationChange: (Boolean) -> Unit,
    delayConfiguration: @Composable () -> Unit,
    ttsConfiguration: @Composable () -> Unit,
    proactiveConfiguration: @Composable () -> Unit,
    notificationDescription: String,
    onAvatarClick: (() -> Unit)? = null
) {
    val textResources = LocalResources.current
    CompanionPreferenceGroup {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val character = session.userFacingTitle()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(modifier = Modifier.testTag("companion-avatar-edit").then(
                if (onAvatarClick != null) Modifier.clickable(role = Role.Button,
                    onClickLabel = textResources.getString(R.string.companion_avatar_change), onClick = onAvatarClick) else Modifier),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    com.nekobot.app.ui.components.CharacterAvatarPhoto(session.characterAvatar, session.portrait, character, Modifier.fillMaxSize())
                }
            }
            if (onAvatarClick != null) Text(textResources.getString(R.string.companion_avatar_change),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(character, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Text(textResources.getString(R.string.sessions_filter_agent_characters), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(textResources.getString(R.string.single_role_uses_one_fixed_conversation_memories_apply_only_to_it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.archived == true || session.readOnly == true) Text(
                    listOfNotNull(textResources.getString(R.string.sessions_filter_archived).takeIf { session.archived == true }, textResources.getString(R.string.sessions_detail_readonly_badge).takeIf { session.readOnly == true }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    CompanionPreferenceGroup(textResources.getString(R.string.single_role_character_profile)) {
        CompanionPreferenceRow(textResources.getString(R.string.agent_character_memory_title), Icons.AutoMirrored.Outlined.MenuBook, onClick = onMemories)
        CompanionPreferenceDivider()
        CompanionPreferenceRow(textResources.getString(R.string.portability_detail_local_relationship_states), Icons.Outlined.FavoriteBorder, onClick = onRelationship)
        if (onModels != null) {
            CompanionPreferenceDivider()
            CompanionPreferenceRow(textResources.getString(R.string.single_role_chat_models), Icons.Outlined.Forum, onClick = onModels)
        }
        CompanionPreferenceDivider()
        CompanionPreferenceRow(textResources.getString(R.string.sessions_detail_advanced), Icons.Outlined.Tune, textResources.getString(R.string.single_role_view_debug_information), onClick = onAdvanced)
    }
    CompanionPreferenceGroup(textResources.getString(R.string.single_role_conversation_information)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, onNameChange, label = { Text(textResources.getString(R.string.sessions_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(tags, onTagsChange, label = { Text(textResources.getString(R.string.single_role_tags)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(pinned, { onPinnedChange(!pinned) }, label = { Text(textResources.getString(R.string.sessions_filter_pinned)) },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                FilterChip(favorite, { onFavoriteChange(!favorite) }, label = { Text(textResources.getString(R.string.sessions_filter_favorite)) },
                    leadingIcon = { Icon(Icons.Outlined.StarBorder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
            val metadata = listOfNotNull(session.messageCount?.let { textResources.getString(R.string.single_role_1_s_messages, it) }, session.updatedAt?.let { textResources.getString(R.string.single_role_updated_1_s, it.replace('T', ' ').take(16)) })
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CompanionPreferenceGroup(textResources.getString(R.string.single_role_replies_and_reminders)) {
        if (delayEligible) {
            CompanionPreferenceRow(textResources.getString(R.string.sessions_detail_delay_reply), Icons.Outlined.Timer, textResources.getString(R.string.single_role_pause_the_timer_while_typing), checked = delayEnabled, onCheckedChange = onDelayChange)
            if (delayEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { delayConfiguration() }
            CompanionPreferenceDivider()
        }
        CompanionPreferenceRow(textResources.getString(R.string.single_role_read_aloud), Icons.AutoMirrored.Outlined.VolumeUp, checked = ttsEnabled, onCheckedChange = onTtsChange)
        if (ttsEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { ttsConfiguration() }
        CompanionPreferenceDivider()
        CompanionPreferenceRow(textResources.getString(R.string.feature_desc_active_chat), Icons.Outlined.ChatBubbleOutline, checked = proactiveEnabled, onCheckedChange = onProactiveChange)
        if (proactiveEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { proactiveConfiguration() }
        CompanionPreferenceDivider()
        CompanionPreferenceRow(textResources.getString(R.string.sessions_detail_notification), Icons.Outlined.NotificationsNone, checked = notificationEnabled, onCheckedChange = onNotificationChange)
        if (notificationEnabled) Text(notificationDescription, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SessionSharingContainer(
    companion: Boolean, title: String, expanded: Boolean, onExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val textResources = LocalResources.current
    if (!companion) GlassCard(Modifier.fillMaxWidth(), content = content)
    else CompanionPreferenceGroup(title) {
        CompanionPreferenceRow(title, Icons.Outlined.Share, if (expanded) textResources.getString(R.string.single_role_collapse_sharing_settings) else textResources.getString(R.string.single_role_expand_sharing_settings),
            trailing = { Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null) }, onClick = onExpand)
        if (expanded) Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun CompanionRoleMenu(onExport: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit, expanded: Boolean) {
    val textResources = LocalResources.current
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text(textResources.getString(R.string.sessions_detail_export)) }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { onDismiss(); onExport() })
        DropdownMenuItem(text = { Text(textResources.getString(R.string.sessions_delete_title), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onDismiss(); onDelete() })
    }
}
