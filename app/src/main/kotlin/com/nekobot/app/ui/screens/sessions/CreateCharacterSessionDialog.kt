package com.nekobot.app.ui.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INHERIT
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassDialogSurface
import com.nekobot.app.ui.components.RelationshipStateSourceSelector
import com.nekobot.app.ui.components.resolveAvatarUrl

internal data class SessionCreationUiState(
    val characters: List<CharacterPreset> = emptyList(),
    val loading: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null
)

/** 角色选择与会话配置共用一个滚动区域，底部操作始终可见。 */
@Composable
internal fun CreateCharacterSessionDialog(
    state: SessionCreationUiState,
    isLocalMode: Boolean,
    userId: String?,
    onDismiss: () -> Unit,
    onCreate: (CreateSessionRequest) -> Unit,
    onLoadCharacters: () -> Unit,
    /** 预先选中的角色（首页 banner 固定角色直接新建时传入）。 */
    initialCharacterId: String? = null
) {
    val defaultName = stringResource(R.string.sessions_new_default)
    val characters = remember(state.characters) {
        state.characters.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
    }
    val presetCharacterId = initialCharacterId?.takeIf { it.isNotBlank() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCharacter = characters.firstOrNull { it.id == selectedId }
    var choosingCharacter by rememberSaveable { mutableStateOf(presetCharacterId == null) }
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var firstMessage by rememberSaveable { mutableStateOf("") }
    var firstMessageEdited by rememberSaveable { mutableStateOf(false) }
    var editingGreeting by rememberSaveable { mutableStateOf(false) }
    var relationshipExpanded by rememberSaveable { mutableStateOf(false) }
    var relationshipStateSource by rememberSaveable { mutableStateOf(RELATIONSHIP_STATE_SOURCE_INHERIT) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val matches = remember(characters, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) characters else characters.filter { character ->
            character.displayName.contains(keyword, ignoreCase = true) ||
                character.description.orEmpty().contains(keyword, ignoreCase = true) ||
                character.tags.orEmpty().any { it.contains(keyword, ignoreCase = true) }
        }
    }

    LaunchedEffect(Unit) { onLoadCharacters() }
    LaunchedEffect(choosingCharacter, query) { listState.scrollToItem(0) }
    // 只同步自动填充的开场白；用户主动清空也视为编辑。
    LaunchedEffect(selectedCharacter?.id, selectedCharacter?.firstMessage) {
        if (!firstMessageEdited) firstMessage = selectedCharacter?.firstMessage.orEmpty()
    }
    LaunchedEffect(characters, state.loading) {
        if (!state.loading && selectedId != null && selectedCharacter == null) {
            selectedId = null
            choosingCharacter = true
        }
    }
    // 预选角色：角色列表加载完成后自动选中；角色卡已不存在则退回选择列表
    LaunchedEffect(characters, state.loading, presetCharacterId) {
        if (presetCharacterId == null) return@LaunchedEffect
        val preset = characters.firstOrNull { it.id == presetCharacterId }
        if (preset != null) {
            if (selectedId == null) {
                selectedId = preset.id
                if (!firstMessageEdited) firstMessage = preset.firstMessage.orEmpty()
                choosingCharacter = false
            }
        } else if (!state.loading && state.characters.isNotEmpty()) {
            choosingCharacter = true
        }
    }

    fun selectCharacter(character: CharacterPreset?) {
        selectedId = character?.id
        if (!firstMessageEdited) firstMessage = character?.firstMessage.orEmpty()
        query = ""
        choosingCharacter = false
        focusManager.clearFocus()
    }

    Dialog(
        onDismissRequest = { if (!state.creating) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !state.creating,
            dismissOnClickOutside = !state.creating
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            // 横屏键盘可能占去大部分高度，优先保留输入区和底部操作。
            val compactWindow = maxHeight < 240.dp
            GlassDialogSurface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compactWindow) 4.dp else 12.dp)
                    .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = if (compactWindow) maxHeight else maxHeight * 0.9f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column {
                    if (!compactWindow) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.session_create_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).semantics { heading() }
                            )
                            IconButton(onClick = onDismiss, enabled = !state.creating) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                            .testTag("session_create_content"),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (choosingCharacter) {
                            item("search") {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier.fillMaxWidth().testTag("session_character_search"),
                                    enabled = !state.creating,
                                    label = { Text(stringResource(R.string.session_create_search)) },
                                    placeholder = { Text(stringResource(R.string.session_create_search_hint)) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = if (query.isNotEmpty()) {{
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Outlined.Close, stringResource(R.string.sessions_clear_input))
                                        }
                                    }} else null,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                                )
                            }
                            item("count") {
                                Text(
                                    stringResource(R.string.session_create_character_count, matches.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.loading) {
                                item("loading") {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else if (matches.isEmpty()) {
                                item("empty") {
                                    Text(
                                        stringResource(if (query.isBlank()) R.string.sessions_no_characters else R.string.session_create_no_results),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                items(matches, key = { "character:${it.id}" }) { character ->
                                    CharacterChoiceRow(
                                        character = character,
                                        selected = selectedId == character.id,
                                        enabled = !state.creating,
                                        onClick = { selectCharacter(character) }
                                    )
                                }
                            }
                            item("without_character") {
                                TextButton(
                                    onClick = { selectCharacter(null) },
                                    enabled = !state.creating,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) { Text(stringResource(R.string.session_create_without_character)) }
                            }
                        } else {
                            item("selected_character") {
                                Surface(
                                    onClick = { choosingCharacter = true; focusManager.clearFocus() },
                                    enabled = !state.creating,
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.testTag("session_change_character")
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CharacterChoiceAvatar(selectedCharacter, Modifier.size(56.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                selectedCharacter?.displayName ?: stringResource(R.string.session_create_without_character),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            characterSummary(selectedCharacter)?.let { summary ->
                                                Text(summary, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.session_create_change_character),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            item("name") {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    modifier = Modifier.fillMaxWidth().testTag("session_create_name"),
                                    enabled = !state.creating,
                                    label = { Text(stringResource(R.string.sessions_name_optional)) },
                                    placeholder = { Text(defaultName) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                                )
                            }
                            item("greeting") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SessionCreationDisclosure(
                                        title = stringResource(R.string.session_create_greeting),
                                        expanded = editingGreeting,
                                        enabled = !state.creating,
                                        onClick = { editingGreeting = !editingGreeting; focusManager.clearFocus() }
                                    )
                                    if (editingGreeting) {
                                        OutlinedTextField(
                                            value = firstMessage,
                                            onValueChange = { firstMessage = it; firstMessageEdited = true },
                                            enabled = !state.creating,
                                            label = { Text(stringResource(R.string.sessions_first_message_optional)) },
                                            minLines = 3,
                                            maxLines = 5,
                                            modifier = Modifier.fillMaxWidth().testTag("session_create_greeting")
                                        )
                                        if (firstMessageEdited && selectedCharacter != null) {
                                            TextButton(
                                                onClick = { firstMessageEdited = false; firstMessage = selectedCharacter.firstMessage.orEmpty() },
                                                enabled = !state.creating
                                            ) { Text(stringResource(R.string.session_create_restore_greeting)) }
                                        }
                                    } else {
                                        Text(
                                            firstMessage.ifBlank { stringResource(R.string.session_create_no_greeting) },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                            if (isLocalMode && selectedCharacter != null) {
                                item("relationship") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SessionCreationDisclosure(
                                            title = stringResource(R.string.sessions_relationship_state_source),
                                            expanded = relationshipExpanded,
                                            enabled = !state.creating,
                                            onClick = { relationshipExpanded = !relationshipExpanded; focusManager.clearFocus() }
                                        )
                                        if (relationshipExpanded) {
                                            RelationshipStateSourceSelector(
                                                selectedSource = relationshipStateSource,
                                                onSourceSelected = { relationshipStateSource = it },
                                                initialState = selectedCharacter.state
                                            )
                                        } else {
                                            Text(
                                                stringResource(if (relationshipStateSource == RELATIONSHIP_STATE_SOURCE_INHERIT)
                                                    R.string.sessions_relationship_state_inherit else R.string.sessions_relationship_state_initial),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    state.error?.let { error ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (choosingCharacter) TextButton(onClick = onLoadCharacters, enabled = !state.loading && !state.creating) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().padding(if (compactWindow) 8.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !state.creating, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(
                            enabled = !state.creating && !state.loading && !choosingCharacter,
                            onClick = {
                                focusManager.clearFocus()
                                val character = selectedCharacter
                                onCreate(CreateSessionRequest(
                                    name = name.ifBlank { defaultName }, sessionMode = "character",
                                    characterId = character?.id,
                                    systemPrompt = character?.systemPrompt?.takeIf { it.isNotBlank() },
                                    firstMessage = firstMessage.takeIf { firstMessageEdited || it.isNotBlank() },
                                    scenario = character?.scenario?.takeIf { it.isNotBlank() },
                                    senderName = character?.displayName, senderAvatar = character?.avatar,
                                    senderPortrait = character?.portrait, userId = userId,
                                    relationshipStateSource = relationshipStateSource
                                ))
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("session_create_submit"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (state.creating) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (state.creating) R.string.session_create_creating else R.string.common_create))
                        }
                    }
                }
            }
        }
        BackHandler(enabled = choosingCharacter && selectedCharacter != null && !state.creating) {
            choosingCharacter = false
            focusManager.clearFocus()
        }
    }
}

private fun characterSummary(character: CharacterPreset?): String? = character?.let {
    it.description?.takeIf(String::isNotBlank) ?: it.tags?.takeIf { tags -> tags.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun CharacterChoiceRow(character: CharacterPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("session_character_${character.id}")
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CharacterChoiceAvatar(character, Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(character.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                characterSummary(character)?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.session_create_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CharacterChoiceAvatar(character: CharacterPreset?, modifier: Modifier = Modifier) {
    val image = remember(character?.portrait, character?.avatar) {
        resolveAvatarUrl(character?.portrait?.takeIf(String::isNotBlank)) ?: resolveAvatarUrl(character?.avatar)
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Person, null, Modifier.fillMaxSize(0.5f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        if (image != null) AsyncImage(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun SessionCreationDisclosure(title: String, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            stringResource(if (expanded) R.string.session_create_collapse else R.string.session_create_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
