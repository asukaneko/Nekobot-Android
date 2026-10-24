package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.theme.parseHexColor
import com.nekobot.app.ui.components.bubbleContentColor

/** Only provided inside a single-session companion chat; shared Agent/role UI keeps its own style. */
internal val LocalCompanionChatName = staticCompositionLocalOf<String?> { null }
internal val LocalCompanionAvatarFallback = staticCompositionLocalOf<String?> { null }
internal val LocalCompanionShowReasoning = staticCompositionLocalOf { false }
/** 打开本会话「角色记忆」页；非单会话角色为 null。 */
internal val LocalOpenCompanionMemory = staticCompositionLocalOf<(() -> Unit)?> { null }
enum class CompanionChatUtility { TERMINAL, BROWSER, RESTORE_ARCHIVE, VIEW_ARCHIVE }

internal data class CompanionChatPalette(
    val background: Color, val surface: Color, val outgoing: Color,
    val ink: Color, val secondary: Color, val accent: Color,
    val outgoingInk: Color = Color(0xFF172411),
    val incoming: Color = Color.White,
    val incomingInk: Color = Color.Black
)

@Composable
internal fun companionChatPalette(messageColorOverride: String? = null, assistantColorOverride: String? = null): CompanionChatPalette =
    companionChatPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f,
        parseHexColor(messageColorOverride), parseHexColor(assistantColorOverride))

/** 用户与 AI 气泡分别取色；未设置 AI 覆盖色时使用白色。 */
internal fun companionChatPalette(dark: Boolean, messageColorOverride: Color? = null, assistantColorOverride: Color? = null): CompanionChatPalette {
    val palette = if (dark) {
        CompanionChatPalette(Color(0xFF191D1E), Color(0xFF2A3031), Color(0xFF355B45),
            Color(0xFFF0F4F2), Color(0xFFB4BEBA), Color(0xFF92C5A9), Color(0xFFF0F6F2))
    } else {
        CompanionChatPalette(Color(0xFFF0F4F3), Color(0xFFFEFEFE), Color(0xFFD5EBD2),
            Color(0xFF202927), Color(0xFF75807C), Color(0xFF547E68), Color(0xFF172A20))
    }
    val incoming = assistantColorOverride ?: Color.White
    return palette.copy(
        outgoing = messageColorOverride ?: palette.outgoing,
        outgoingInk = messageColorOverride?.let { bubbleContentColor(it) } ?: palette.outgoingInk,
        incoming = incoming,
        incomingInk = bubbleContentColor(incoming)
    )
}

/** A display preference only; NONE applies to new requests, never to saved messages. */
@Composable
internal fun CompanionReasoningSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = companionChatPalette()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Psychology, null, Modifier.size(22.dp), tint = palette.ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.companion_chat_show_reasoning),
                style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            Text(stringResource(R.string.companion_chat_show_reasoning_hint),
                style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent))
    }
}

/** Reuses saved-run recovery, without exposing the Agent execution panel. */
@Composable
internal fun CompanionReplyRecovery(onResume: () -> Unit, onDismiss: () -> Unit) {
    val palette = companionChatPalette()
    Column(Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.companion_chat_reply_unfinished),
            color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.companion_chat_resume_reply), color = palette.accent) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.companion_chat_dismiss_reply), color = palette.secondary) }
        }
    }
}

/** Compact optional disclosure; hidden/empty reasoning never leaves a blank card. */
@Composable
internal fun CompanionReasoningSection(messageId: String?, reasoning: String?, enabled: Boolean) {
    if (!enabled || reasoning.isNullOrBlank()) return
    var expanded by remember(messageId) { mutableStateOf(false) }
    val palette = companionChatPalette()
    val dark = palette.background.luminance() < 0.5f
    val container = if (dark) Color(0xFF342F27) else Color(0xFFF6F1E8)
    val content = if (dark) palette.secondary else Color(0xFF6B6150)
    Surface(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("companion_reasoning"),
        shape = RoundedCornerShape(12.dp), color = container,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (dark) Color(0xFF514838) else Color(0xFFEBE3D3))) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("💭", color = content)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.companion_inner_thought), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    Modifier.size(20.dp), tint = content)
            }
            if (expanded) {
                SelectionContainer {
                    Text(reasoning, Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = content)
                }
            }
        }
    }
}

@Composable
internal fun CompanionTimeLabel(label: String) {
    Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.labelMedium, color = companionChatPalette().secondary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** The composer reuses the existing send, draft, attachment and microphone handlers. */
@Composable
internal fun CompanionChatInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hasAttachments: Boolean,
    busy: Boolean,
    enabled: Boolean,
    attachmentsOpen: Boolean,
    onAttachments: () -> Unit,
    onSticker: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val palette = companionChatPalette()
    val hasDraft = value.text.isNotBlank() || hasAttachments
    Column(Modifier.background(palette.background)) {
        HorizontalDivider(color = palette.secondary.copy(alpha = 0.12f), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onVoice, enabled = enabled && !busy) {
                Icon(Icons.Default.Mic, stringResource(R.string.chat_voice_input), tint = palette.ink)
            }
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                .background(palette.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.text.isEmpty()) Text(stringResource(R.string.companion_chat_input),
                    color = palette.secondary, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
                    cursorBrush = SolidColor(palette.accent))
            }
            IconButton(onClick = onSticker) {
                Icon(Icons.Default.SentimentSatisfiedAlt, stringResource(R.string.companion_chat_stickers), tint = palette.ink)
            }
            if (hasDraft) {
                Button(onClick = onSend, enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.surface)) {
                    Text(stringResource(R.string.chat_send))
                }
            } else if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.StopCircle, stringResource(R.string.chat_stop_generation), tint = palette.accent)
                }
            } else {
                IconButton(onClick = onAttachments) {
                    Icon(if (attachmentsOpen) Icons.Default.Close else Icons.Default.AddCircleOutline,
                        stringResource(R.string.companion_chat_attachments), tint = palette.ink)
                }
            }
        }
    }
}

@Composable
internal fun CompanionAttachmentPanel(enabled: Boolean, onImage: () -> Unit, onFile: () -> Unit) {
    val palette = companionChatPalette()
    Row(Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        for ((label, icon, action) in listOf(
            Triple(stringResource(R.string.companion_chat_photos), Icons.Default.Image, onImage),
            Triple(stringResource(R.string.companion_chat_files), Icons.Default.AttachFile, onFile)
        )) {
            Column(Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = action).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(palette.surface),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(28.dp), tint = palette.ink.copy(alpha = if (enabled) 1f else 0.4f))
                }
                Spacer(Modifier.height(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = palette.secondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionChatSheet(
    title: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null,
    scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    val palette = companionChatPalette()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface, contentColor = palette.ink) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.common_close)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(bottom = 24.dp), content = content)
    }
}

@Composable
internal fun CompanionSettingsRow(
    title: String, icon: ImageVector, subtitle: String? = null,
    enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit
) {
    val palette = companionChatPalette()
    val color = if (destructive) MaterialTheme.colorScheme.error else palette.ink
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = color.copy(alpha = if (enabled) 1f else 0.4f))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color.copy(alpha = if (enabled) 1f else 0.4f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun CompanionClearMessagesDialog(enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    val keyword = stringResource(R.string.companion_chat_clear_keyword)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.companion_chat_clear)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_clear_current_confirm))
                OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, singleLine = true,
                    label = { Text(stringResource(R.string.companion_chat_clear_type, keyword)) })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled && confirmation.trim() == keyword) {
            Text(stringResource(R.string.companion_chat_clear), color = if (enabled && confirmation.trim() == keyword)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
