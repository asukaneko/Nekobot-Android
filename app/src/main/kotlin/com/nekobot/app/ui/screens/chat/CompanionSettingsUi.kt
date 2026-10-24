package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** Scoped to companion settings: deliberately independent of message and relationship skin colours. */
@Composable
internal fun companionSettingsBackground(): Color =
    lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, 0.035f)

@Composable
internal fun CompanionPreferenceGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = if (highlighted) lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, 0.045f)
                else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
            Column(content = content)
        }
    }
}

@Composable
internal fun CompanionPreferenceDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
internal fun CompanionPreferenceIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun CompanionPreferenceRow(
    title: String, icon: ImageVector, subtitle: String? = null,
    modifier: Modifier = Modifier,
    checked: Boolean? = null, onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interaction = when {
        checked != null && onCheckedChange != null -> Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Row(modifier.fillMaxWidth().then(interaction).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CompanionPreferenceIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            checked != null -> Switch(checked, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = MaterialTheme.colorScheme.surface))
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun CompanionSettingsScreen(
    characterName: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit, onDetails: () -> Unit, onSearch: () -> Unit,
    onFavorites: () -> Unit, onUsage: () -> Unit, onArchive: (() -> Unit)? = null,
    portraitUrl: String? = null,
    reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    customReasoning: CustomReasoningParam = CustomReasoningParam(),
    onReasoningEffortChange: (ReasoningEffort) -> Unit = {},
    onCustomReasoningChange: (CustomReasoningParam) -> Unit = {},
    onSaved: () -> Unit = {},
    sessionId: String? = null,
    characterId: String? = null
) {
    val textResources = LocalResources.current
    var promptsOpen by androidx.compose.runtime.saveable.rememberSaveable(sessionId) { mutableStateOf(false) }
    if (promptsOpen && sessionId != null && characterId != null) {
        CompanionPromptSettingsScreen(sessionId, characterId, characterName, reasoningEffort,
            onBack = { promptsOpen = false }, onSaved = onSaved)
        return
    }
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize().testTag("companion-settings-screen"), color = companionSettingsBackground(),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            CompanionReadingHeader(textResources.getString(R.string.companion_chat_settings), onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CompanionSettingsIdentity(characterName, portraitUrl, onDetails)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    CompanionPreferenceGroup(textResources.getString(R.string.single_role_chat_content)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompanionSettingsAction(textResources.getString(R.string.single_role_find_messages), Icons.Outlined.Search, onSearch, Modifier.weight(1f))
                            CompanionSettingsAction(textResources.getString(R.string.single_role_save_messages), Icons.Outlined.StarBorder, onFavorites, Modifier.weight(1f))
                            if (onArchive != null) CompanionSettingsAction(textResources.getString(R.string.chat_view_archive), Icons.Outlined.FolderOpen, onArchive, Modifier.weight(1f))
                        }
                    }
                    if (sessionId != null && characterId != null) CompanionPreferenceGroup {
                        CompanionPreferenceRow(textResources.getString(R.string.companion_prompts_title), Icons.Outlined.Tune,
                            textResources.getString(R.string.companion_prompts_summary),
                            modifier = Modifier.testTag("companion-prompts-entry"), onClick = { promptsOpen = true })
                    }
                    CompanionPreferenceGroup(textResources.getString(R.string.single_role_thinking)) {
                        CompanionPreferenceRow(textResources.getString(R.string.companion_chat_show_reasoning), Icons.Outlined.Description, textResources.getString(R.string.single_role_control_whether_these_thoughts_are_shown),
                            checked = checked, onCheckedChange = { onCheckedChange(it); onSaved() })
                        CompanionPreferenceDivider()
                        CompanionReasoningEffortSetting(reasoningEffort, onReasoningEffortChange, onSaved)
                        if (reasoningEffort == ReasoningEffort.CUSTOM) {
                            CustomReasoningFields(customReasoning, enabled = true,
                                onChange = { onCustomReasoningChange(it); onSaved() },
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp))
                        }
                        CompanionPreferenceDivider()
                        if (sessionId != null) CompanionThinkingControls(sessionId, reasoningEffort, onSaved)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompanionPreferenceGroup(textResources.getString(R.string.single_role_display_and_usage)) {
                            // 2026-10-22 用户决定：不再提供“显示内心独白”开关，恒定显示。
                            CompanionPreferenceRow(textResources.getString(R.string.single_role_chat_usage), Icons.Outlined.BarChart, textResources.getString(R.string.single_role_view_content_included_in_the_last_request), onClick = onUsage)
                        }
                        Text(textResources.getString(R.string.single_role_enable_both_thinking_display_and_character_perspective_thinking_t), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CompanionFeedbackEntry()
                }
            }
        }
    }
}

@Composable
private fun CompanionReasoningEffortSetting(
    effort: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    onSaved: () -> Unit
) {
    val textResources = LocalResources.current
    var menuOpen by remember { mutableStateOf(false) }
    CompanionPreferenceRow(textResources.getString(R.string.reasoning_effort_title), Icons.Outlined.Psychology, textResources.getString(R.string.single_role_only_affects_this_conversation),
        trailing = {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(effort.displayName)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ReasoningEffort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = {
                            onSelect(option)
                            menuOpen = false
                            onSaved()
                        })
                    }
                }
            }
        })
}

@Composable
private fun CompanionSettingsIdentity(characterName: String?, portraitUrl: String?, onDetails: () -> Unit) {
    val textResources = LocalResources.current
    val primary = MaterialTheme.colorScheme.primary
    val base = companionSettingsBackground()
    val name = characterName?.takeIf(String::isNotBlank) ?: textResources.getString(R.string.sessions_filter_agent_characters)
    Column(Modifier.fillMaxWidth().clipToBounds().drawBehind {
        drawRect(Brush.verticalGradient(listOf(lerp(base, primary, 0.13f), base)))
        drawOval(primary.copy(alpha = 0.055f), topLeft = Offset(size.width * 0.58f, -size.height * 0.72f),
            size = Size(size.width * 0.94f, size.height * 1.8f))
    }.padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(
                Brush.linearGradient(listOf(lerp(primary, Color.White, 0.17f), primary))), contentAlignment = Alignment.Center) {
                val portrait = resolveAvatarUrl(portraitUrl)
                if (!portrait.isNullOrBlank()) AsyncImage(portrait, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.take(1), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(textResources.getString(R.string.sessions_filter_agent_characters), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(onClick = onDetails, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Contacts, null, tint = primary, modifier = Modifier.size(26.dp))
                Text(textResources.getString(R.string.companion_chat_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompanionSettingsAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun companionChoiceColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary
)

/** Native full-height reading surface; the back target remains available while the keyboard is open. */
@Composable
internal fun CompanionReadingDialog(
    title: String, onBack: () -> Unit, onDismiss: () -> Unit = onBack,
    subtitle: String? = null, content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = companionSettingsBackground(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                CompanionReadingHeader(title, onBack, subtitle = subtitle)
                content()
            }
        }
    }
}

@Composable
internal fun CompanionReadingHeader(
    title: String, onBack: () -> Unit, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val textResources = LocalResources.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}
