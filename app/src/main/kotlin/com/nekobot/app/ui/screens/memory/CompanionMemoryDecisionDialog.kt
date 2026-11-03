package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.R
import com.nekobot.app.ui.screens.chat.CompanionMessageDecisionState

/** 按已确认预览保留纵向操作；长正文单独滚动，窄屏和键盘出现时动作仍可达。 */
@Composable
internal fun MemoryDecisionDialog(title: String, description: String, busy: Boolean, onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        body()
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
        }
    }
}

@Composable
internal fun CompanionMessageMemoryDialog(state: CompanionMessageDecisionState, busy: Boolean,
    error: String?, onChoice: (Boolean?) -> Unit) {
    var expanded by rememberSaveable(state.change) { mutableStateOf(false) }
    MemoryDecisionDialog(stringResource(R.string.memory_v2_linked_title), stringResource(when {
        state.change.selectedIds.size > 1 -> R.string.memory_v2_bulk_delete_question
        state.change.regenerate -> R.string.memory_v2_regenerate_question
        state.change.content != null -> R.string.memory_v2_edit_question
        else -> R.string.memory_v2_delete_question
    }), busy, onDismiss = { onChoice(null) }, actions = {
        Button(onClick = { onChoice(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.memory_v2_delete_together))
        }
        FilledTonalButton(onClick = { onChoice(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(if (state.change.content == null) R.string.memory_v2_keep_delete else R.string.memory_v2_keep_edit))
        }
        TextButton(onClick = { onChoice(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.memory_v2_cancel))
        }
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                state.visibleMemories(expanded).forEachIndexed { index, memory ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text(memory.content, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.memories.size > 5) TextButton(onClick = { expanded = !expanded }, enabled = !busy) {
            Text(if (expanded) stringResource(R.string.memory_v2_collapse) else stringResource(R.string.memory_v2_more, state.memories.size - 5))
        }
    }
}
