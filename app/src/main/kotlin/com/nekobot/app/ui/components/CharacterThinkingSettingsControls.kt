package com.nekobot.app.ui.components

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.local.ai.characterThinkingPromptForEditing
import com.nekobot.app.data.local.ai.characterThinkingPromptToSave
import com.nekobot.app.data.model.ReasoningEffort

/** 所有角色／群聊入口共用同一份全局偏好；重新进入时读取最新值。 */
@Composable
internal fun CharacterThinkingSettingsControls(
    effort: ReasoningEffort,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSaved: () -> Unit = {},
    menuRows: (@Composable (
        switchModifier: Modifier,
        promptModifier: Modifier,
        enabled: Boolean,
        checked: Boolean
    ) -> Unit)? = null
) {
    val textResources = LocalResources.current
    val prefs = ServiceContainer.prefs
    val characterThinkingSettings by prefs.characterThinkingSettings.collectAsStateWithLifecycle()
    var promptDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    val thinkingControlsEnabled = effort != ReasoningEffort.NONE
    LaunchedEffect(thinkingControlsEnabled) {
        if (!thinkingControlsEnabled) promptDraft = null
    }
    if (menuRows != null) {
        menuRows(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-switch")
                .toggleable(
                    value = characterThinkingSettings.enabled,
                    enabled = thinkingControlsEnabled,
                    role = Role.Switch,
                    onValueChange = { value ->
                        prefs.characterThinkingEnabled = value
                        onSaved()
                    }
                ),
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-prompt-entry")
                .clickable(enabled = thinkingControlsEnabled, role = Role.Button) {
                    promptDraft = characterThinkingPromptForEditing(prefs.getCharacterThinkingSettings())
                },
            thinkingControlsEnabled,
            characterThinkingSettings.enabled
        )
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .testTag("character-thinking-switch")
                    .toggleable(value = characterThinkingSettings.enabled, enabled = thinkingControlsEnabled,
                        role = Role.Switch, onValueChange = { enabled ->
                            prefs.characterThinkingEnabled = enabled
                            onSaved()
                        }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CharacterThinkingRowLabel(Icons.Outlined.PersonOutline, textResources.getString(R.string.single_role_character_perspective_thinking_beta),
                    textResources.getString(R.string.single_role_think_in_chinese_in_the_character_s_first_person_voice), thinkingControlsEnabled, Modifier.weight(1f), compact)
                Switch(checked = characterThinkingSettings.enabled, onCheckedChange = null, enabled = thinkingControlsEnabled)
            }
            HorizontalDivider(Modifier.padding(start = if (compact) 56.dp else 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .testTag("character-thinking-prompt-entry")
                    .clickable(enabled = thinkingControlsEnabled, role = Role.Button) {
                        promptDraft = characterThinkingPromptForEditing(prefs.getCharacterThinkingSettings())
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CharacterThinkingRowLabel(Icons.Outlined.Edit, textResources.getString(R.string.single_role_custom_thinking_prompt_beta),
                    textResources.getString(R.string.single_role_leave_blank_to_use_the_default), thinkingControlsEnabled, Modifier.weight(1f), compact)
                Icon(Icons.Default.ChevronRight, null, Modifier.size(24.dp),
                    tint = if (thinkingControlsEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
        HorizontalDivider(Modifier.padding(start = if (compact) 56.dp else 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-source-entry")
                .clickable(role = Role.Button) { showSourceDialog = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CharacterThinkingRowLabel(Icons.Outlined.Psychology,
                textResources.getString(R.string.settings_thinking_source_title),
                textResources.getString(R.string.settings_thinking_source_summary),
                enabled = true, Modifier.weight(1f), compact)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (thinkingControlsEnabled && promptDraft != null) {
        GlassAlertDialog(
            onDismissRequest = { promptDraft = null },
            title = { Text(textResources.getString(R.string.single_role_custom_thinking_prompt_beta)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(textResources.getString(R.string.single_role_leave_blank_to_use_the_default))
                    OutlinedTextField(
                        value = promptDraft.orEmpty(),
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth().testTag("character-thinking-prompt-editor"),
                        label = { Text(textResources.getString(R.string.single_role_thinking_prompt)) },
                        minLines = 8,
                        maxLines = 12,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(onClick = { promptDraft = DEFAULT_CHARACTER_THINKING_PROMPT }) { Text(textResources.getString(R.string.chat_quick_bar_reset_default)) }
                    TextButton(onClick = {
                        prefs.characterThinkingPrompt = characterThinkingPromptToSave(promptDraft.orEmpty())
                        promptDraft = null
                        onSaved()
                    }) { Text(textResources.getString(R.string.common_save)) }
                }
            }
        )
    }
    if (showSourceDialog) {
        ThinkingSourceSettingsDialog(onDismiss = { showSourceDialog = false })
    }
}

@Composable
private fun CharacterThinkingRowLabel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 16.dp)) {
        Box(Modifier.size(if (compact) 42.dp else 52.dp).clip(RoundedCornerShape(if (compact) 12.dp else 14.dp))
            .background(if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(if (compact) 24.dp else 26.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else disabledColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (compact) FontWeight.Medium else FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor)
        }
    }
}
