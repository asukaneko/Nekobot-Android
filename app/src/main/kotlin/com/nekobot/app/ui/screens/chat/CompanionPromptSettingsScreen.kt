package com.nekobot.app.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CompanionPromptSettings
import com.nekobot.app.data.local.ai.CompanionPromptLanguage
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.local.ai.characterThinkingPromptForEditing
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.ui.components.ThinkingSourceSettingsDialog
import com.nekobot.app.ui.screens.characters.CharacterDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class PromptLoadState(val settings: CompanionPromptSettings? = null, val failed: Boolean = false)

@Composable
private fun promptSettingsState(sessionId: String, retry: Int): State<PromptLoadState> =
    produceState(PromptLoadState(), sessionId, retry) {
        value = PromptLoadState()
        try {
            ServiceContainer.localRepository.companionPrompts.observe(sessionId).collect {
                value = PromptLoadState(it)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = PromptLoadState(failed = true)
        }
    }

@Composable
internal fun CompanionThinkingControls(sessionId: String, effort: ReasoningEffort, onSaved: () -> Unit) {
    val resources = LocalResources.current
    var retry by remember { mutableIntStateOf(0) }
    val state by promptSettingsState(sessionId, retry)
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = state.settings
    if (settings != null) {
        CompanionPreferenceRow(resources.getString(R.string.companion_prompt_thinking_switch), Icons.Outlined.PersonOutline,
            resources.getString(R.string.companion_prompt_thinking_switch_hint),
            modifier = Modifier.testTag("companion-thinking-switch"), checked = settings.thinkingEnabled,
            enabled = !saving && effort != ReasoningEffort.NONE,
            onCheckedChange = { enabled ->
                scope.launch {
                    saving = true
                    failed = false
                    try {
                        ServiceContainer.localRepository.companionPrompts.setThinkingEnabled(sessionId, enabled)
                        onSaved()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            })
    } else if (!state.failed) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
    if (state.failed || failed) {
        Text(resources.getString(if (state.failed) R.string.companion_prompt_load_error else R.string.companion_prompt_save_error),
            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        if (state.failed) TextButton(onClick = { retry++ }) { Text(resources.getString(R.string.common_retry)) }
    }
    CompanionPreferenceDivider()
    CompanionPreferenceRow(resources.getString(R.string.settings_thinking_source_title), Icons.Outlined.Psychology,
        resources.getString(R.string.settings_thinking_source_summary),
        modifier = Modifier.testTag("character-thinking-source-entry"), onClick = { showSource = true })
    if (showSource) ThinkingSourceSettingsDialog(onDismiss = { showSource = false })
}

@Composable
internal fun CompanionPromptSettingsScreen(
    sessionId: String, characterId: String, characterName: String?, effort: ReasoningEffort,
    onBack: () -> Unit, onSaved: () -> Unit
) {
    val resources = LocalResources.current
    var retry by remember { mutableIntStateOf(0) }
    val state by promptSettingsState(sessionId, retry)
    val settings = state.settings
    var persona by rememberSaveable(sessionId) { mutableStateOf(false) }
    if (persona) {
        CharacterDetailScreen(characterId, onBack = { persona = false })
        BackHandler { persona = false }
        return
    }
    if (settings == null) {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize(), color = companionSettingsBackground()) {
            Column(Modifier.safeDrawingPadding()) {
                CompanionReadingHeader(resources.getString(R.string.companion_prompts_title), onBack)
                if (state.failed) {
                    Text(resources.getString(R.string.companion_prompt_load_error), Modifier.padding(20.dp))
                    TextButton(onClick = { retry++ }) { Text(resources.getString(R.string.common_retry)) }
                } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
            }
        }
        return
    }
    CompanionPromptSettingsContent(settings, characterName, effort,
        onBack = onBack, onPersona = { persona = true }, onSaved = onSaved,
        onSave = { thinking, draft ->
            val store = ServiceContainer.localRepository.companionPrompts
            if (thinking) store.saveThinking(sessionId, draft) else store.saveSpeaking(sessionId, draft)
        })
}

@Composable
internal fun CompanionPromptSettingsContent(
    settings: CompanionPromptSettings, characterName: String?, effort: ReasoningEffort,
    onBack: () -> Unit, onPersona: () -> Unit, onSaved: () -> Unit,
    onSave: suspend (thinking: Boolean, draft: String) -> Unit
) {
    val resources = LocalResources.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var original by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val goBack: () -> Unit = {
        if (!saving) {
            if (editing == null) onBack()
            else if (draft != original) discard = true
            else editing = null
        }
    }
    fun edit(thinking: Boolean) {
        draft = if (thinking) characterThinkingPromptForEditing(settings.thinking) else settings.speakingForEditing
        original = draft
        failed = false
        editing = if (thinking) "thinking" else "speaking"
    }
    BackHandler(onBack = goBack)
    Surface(Modifier.fillMaxSize().testTag("companion-prompt-settings"), color = companionSettingsBackground()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            CompanionReadingHeader(resources.getString(when (editing) {
                "thinking" -> R.string.companion_prompt_thinking
                "speaking" -> R.string.companion_prompt_speaking
                else -> R.string.companion_prompts_title
            }), goBack, subtitle = characterName)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(resources.getString(R.string.companion_prompts_scope), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (editing == null) {
                    CompanionPreferenceGroup {
                        CompanionPreferenceRow(resources.getString(R.string.companion_prompt_persona), Icons.Outlined.PersonOutline,
                            resources.getString(R.string.companion_prompt_persona_hint),
                            modifier = Modifier.testTag("companion-persona-entry"), onClick = onPersona)
                        CompanionPreferenceDivider()
                        CompanionPreferenceRow(resources.getString(R.string.companion_prompt_speaking), Icons.Outlined.ChatBubbleOutline,
                            resources.getString(R.string.companion_prompt_speaking_hint),
                            modifier = Modifier.testTag("companion-speaking-entry"),
                            trailing = { PromptStatus(settings.speakingPrompt.isNotBlank()) }, onClick = { edit(false) })
                        CompanionPreferenceDivider()
                        CompanionPreferenceRow(resources.getString(R.string.companion_prompt_thinking), Icons.Outlined.Psychology,
                            resources.getString(R.string.companion_prompt_thinking_hint),
                            modifier = Modifier.testTag("companion-thinking-entry"),
                            trailing = { PromptStatus(settings.thinkingPrompt.isNotBlank()) }, onClick = { edit(true) })
                    }
                } else {
                    Text(resources.getString(R.string.companion_prompt_edit_hint), style = MaterialTheme.typography.bodySmall)
                    if (editing == "thinking" && (!settings.thinkingEnabled || effort == ReasoningEffort.NONE)) {
                        Text(resources.getString(R.string.companion_prompt_inactive_hint), color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(draft, { draft = it; failed = false },
                        modifier = Modifier.fillMaxWidth().testTag("companion-prompt-editor"),
                        enabled = !saving, minLines = 10, maxLines = 16)
                    Text(resources.getString(R.string.companion_prompt_restore_hint), style = MaterialTheme.typography.bodySmall)
                    if (failed) Text(resources.getString(R.string.companion_prompt_save_error), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("companion-prompt-save-error"))
                }
            }
            if (editing != null) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !saving, onClick = {
                    draft = if (editing == "thinking") DEFAULT_CHARACTER_THINKING_PROMPT else CompanionPromptLanguage.speakingRule
                    failed = false
                }, modifier = Modifier.testTag("companion-prompt-reset")) {
                    Text(resources.getString(R.string.chat_quick_bar_reset_default))
                }
                Button(enabled = !saving, onClick = {
                    val thinking = editing == "thinking"
                    saving = true
                    failed = false
                    scope.launch {
                        try {
                            onSave(thinking, draft)
                            editing = null
                            onSaved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                }, modifier = Modifier.testTag("companion-prompt-save")) {
                    Text(resources.getString(R.string.common_save))
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(resources.getString(R.string.companion_prompt_discard_title)) },
        confirmButton = { TextButton(onClick = { discard = false; editing = null }) { Text(resources.getString(R.string.companion_prompt_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(resources.getString(R.string.companion_prompt_keep_editing)) } })
}

@Composable
private fun PromptStatus(custom: Boolean) {
    Text(LocalResources.current.getString(if (custom) R.string.companion_prompt_custom else R.string.companion_prompt_default),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
