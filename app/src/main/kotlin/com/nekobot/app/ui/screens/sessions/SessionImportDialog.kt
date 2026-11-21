package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.sessionimport.ImportableConversation
import com.nekobot.app.data.local.sessionimport.SessionImportFormat
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import kotlin.math.roundToInt

/**
 * 会话导入弹窗：选择格式 →（归档格式）选择来源 → 勾选会话 → 导入。
 *
 * 格式列表由 [SessionImportFormat.formats] 驱动，后续新增格式只需在注册表追加一项，
 * 并在 [formatIcon] 与格式点击分支里补上对应的选择入口。
 */
@Composable
fun SessionImportDialog(
    state: SessionImportUiState,
    onDismiss: () -> Unit,
    onChooseFormat: (SessionImportFormat) -> Unit,
    onPickNekobotFile: () -> Unit,
    onPickDeepSeekZip: () -> Unit,
    onLoadDeepSeekFromUrl: (String) -> Unit,
    onToggleConversation: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onBack: () -> Unit,
    onConfirmImport: () -> Unit
) {
    var urlInput by rememberSaveable { mutableStateOf("") }

    val confirmText = when (state.step) {
        SessionImportStep.FORMAT -> stringResource(R.string.common_close)
        SessionImportStep.SOURCE -> stringResource(R.string.session_import_from_url)
        SessionImportStep.CONVERSATIONS -> stringResource(R.string.session_import_confirm, state.selectedCount)
    }
    val confirmEnabled = when (state.step) {
        SessionImportStep.FORMAT -> true
        SessionImportStep.SOURCE -> urlInput.isNotBlank() && !state.busy
        SessionImportStep.CONVERSATIONS -> state.selectedCount > 0 && !state.busy
    }
    val confirmAction = when (state.step) {
        SessionImportStep.FORMAT -> onDismiss
        SessionImportStep.SOURCE -> ({ onLoadDeepSeekFromUrl(urlInput.trim()) })
        SessionImportStep.CONVERSATIONS -> onConfirmImport
    }
    val hint = when (state.step) {
        SessionImportStep.FORMAT -> stringResource(R.string.session_import_format_hint)
        SessionImportStep.SOURCE -> stringResource(R.string.session_import_deepseek_hint)
        SessionImportStep.CONVERSATIONS -> null
    }

    NekoDialog(
        onDismiss = { if (!state.busy) onDismiss() },
        title = stringResource(R.string.session_import_title),
        message = hint,
        confirmText = confirmText,
        confirmEnabled = confirmEnabled,
        onConfirm = confirmAction,
        cancelText = if (state.step == SessionImportStep.FORMAT) null else stringResource(R.string.common_cancel),
        onCancel = if (state.step == SessionImportStep.FORMAT) null else onDismiss,
        extraActionText = if (state.step == SessionImportStep.FORMAT) null else stringResource(R.string.session_import_back),
        extraActionEnabled = !state.busy,
        onExtraAction = if (state.step == SessionImportStep.FORMAT) null else onBack,
        dismissOnClickOutside = !state.busy
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when (state.step) {
                SessionImportStep.FORMAT -> SessionImportFormatList(onChooseFormat, onPickNekobotFile)
                SessionImportStep.SOURCE -> DeepSeekSourcePicker(
                    state = state,
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    onPickZip = onPickDeepSeekZip
                )
                SessionImportStep.CONVERSATIONS -> ConversationSelection(
                    state = state,
                    onToggleConversation = onToggleConversation,
                    onToggleSelectAll = onToggleSelectAll
                )
            }
        }
    }
}

@Composable
private fun SessionImportFormatList(
    onChooseFormat: (SessionImportFormat) -> Unit,
    onPickNekobotFile: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SessionImportFormat.formats.forEachIndexed { index, format ->
            NekoChoiceRow(
                title = stringResource(format.labelRes),
                subtitle = stringResource(format.descriptionRes),
                onClick = {
                    onChooseFormat(format)
                    // 单文件格式直接打开文件选择器；归档格式在弹窗内继续选择来源
                    if (!format.needsArchiveSelection) onPickNekobotFile()
                },
                leading = {
                    Icon(
                        imageVector = formatIcon(format),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                },
                showDivider = index != SessionImportFormat.formats.lastIndex
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.session_import_note),
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeepSeekSourcePicker(
    state: SessionImportUiState,
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onPickZip: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.session_import_url_label)) },
            placeholder = { Text("https://…zip") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onPickZip,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.session_import_pick_zip))
        }
        if (state.reading) {
            Spacer(Modifier.height(14.dp))
            ArchiveProgress(state)
        }
        ImportErrorText(state.error)
    }
}

@Composable
private fun ConversationSelection(
    state: SessionImportUiState,
    onToggleConversation: (String) -> Unit,
    onToggleSelectAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.session_import_archive_summary,
                state.archiveName.orEmpty(),
                state.conversations.size
            ),
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSelectAll)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = state.allSelected, onCheckedChange = { onToggleSelectAll() })
            Text(stringResource(R.string.session_import_select_all), style = nekoRowTitleStyle())
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.session_import_selected,
                    state.selectedCount,
                    state.conversations.size
                ),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            items(state.conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id in state.selectedIds,
                    onToggle = { onToggleConversation(conversation.id) }
                )
            }
        }
        if (state.importing) {
            Spacer(Modifier.height(12.dp))
            val fraction = if (state.totalSessions > 0) {
                state.processedSessions.toFloat() / state.totalSessions
            } else {
                0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.session_import_importing,
                    state.processedSessions,
                    state.totalSessions
                ),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ImportErrorText(state.error)
    }
}

@Composable
private fun ConversationRow(
    conversation: ImportableConversation,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                style = nekoRowTitleStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.session_import_message_count,
                    conversation.messageCount,
                    conversation.updatedAt?.take(10) ?: "—"
                ),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArchiveProgress(state: SessionImportUiState) {
    val fraction = state.downloadFraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.session_import_downloading, (fraction * 100).roundToInt()),
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.session_import_reading),
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImportErrorText(error: String?) {
    if (error.isNullOrBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(
        text = error,
        style = nekoCaptionStyle(),
        color = MaterialTheme.colorScheme.error
    )
}

private fun formatIcon(format: SessionImportFormat) = when (format) {
    SessionImportFormat.NEKOBOT -> Icons.Outlined.DataObject
    SessionImportFormat.DEEPSEEK -> Icons.Outlined.FolderZip
}
