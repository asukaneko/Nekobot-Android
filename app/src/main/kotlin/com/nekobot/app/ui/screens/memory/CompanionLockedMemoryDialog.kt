package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionLockedReviewItem

@Composable
internal fun CompanionLockedMemoryDialog(item: CompanionLockedReviewItem, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onResolve: (String, String?) -> Unit, initiallyEditing: Boolean = false) {
    var editing by rememberSaveable(item.review.id) { mutableStateOf(initiallyEditing) }
    var content by rememberSaveable(item.review.id) { mutableStateOf(item.locked.content) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) { if (editing) { focus.requestFocus(); keyboard?.show() } }
    MemoryDecisionDialog(stringResource(R.string.memory_v2_review_title), stringResource(R.string.memory_v2_review_question),
        busy, onDismiss, actions = {
            if (editing) {
                Button(onClick = { keyboard?.hide(); onResolve("edit", content.trim()) },
                    enabled = !busy && content.isNotBlank() && content.length <= 1500,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.memory_v2_save)) }
                TextButton(onClick = { keyboard?.hide(); editing = false; content = item.locked.content }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.memory_v2_cancel_edit)) }
            } else {
                Button(onClick = { editing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.memory_v2_edit))
                }
                FilledTonalButton(onClick = { onResolve("keep", null) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.memory_v2_keep)) }
                TextButton(onClick = { onResolve("delete", null) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.memory_v2_delete_locked), color = MaterialTheme.colorScheme.error)
                }
            }
        }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.memory_v2_locked), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (editing) OutlinedTextField(content, { content = it }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().focusRequester(focus), shape = RoundedCornerShape(12.dp),
            minLines = 2, maxLines = 6, isError = content.length > 1500,
            supportingText = { Text(stringResource(R.string.single_role_1_s_1500_characters, content.length)) })
        else MemoryReviewText(item.locked.content)
        Text(stringResource(R.string.memory_v2_confirmed), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.confirmed.voided) Text(stringResource(memoryVoidReason(item.confirmed.voidedReason)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MemoryReviewText(item.confirmed.content)
    }
}

@Composable
private fun MemoryReviewText(content: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SelectionContainer { Text(content, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}
