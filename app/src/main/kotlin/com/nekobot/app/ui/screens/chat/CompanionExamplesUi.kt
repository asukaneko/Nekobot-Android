package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionExample
import com.nekobot.app.ui.components.BorderlessOutlinedTextField
import com.nekobot.app.ui.components.GlassCard

@Composable
internal fun CompanionExamplesOffer(viewModel: ChatViewModel) {
    val row by viewModel.companionExamples.collectAsStateWithLifecycle()
    if (row?.state != "offered") return
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = scheme.primaryContainer
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.companion_examples_offer),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer
            )
            TextButton(
                onClick = viewModel::selectCompanionExamples,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp)
            ) {
                Text(stringResource(R.string.companion_examples_enable), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = viewModel::declineCompanionExamples,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp)
            ) {
                Text(
                    stringResource(R.string.companion_examples_decline),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = viewModel::declineCompanionExamples, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Close,
                    stringResource(R.string.common_close),
                    Modifier.size(16.dp),
                    tint = scheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
internal fun CompanionExamplesDisabledNotice(name: String, onDismiss: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.companion_examples_disabled_notice, name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
        if (onDismiss != null) {
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 32.dp)
            ) {
                Text(
                    stringResource(R.string.companion_examples_notice_dismiss),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CompanionExamplesEditor(
    name: String,
    pairs: List<CompanionExample>,
    state: String,
    onChange: (List<CompanionExample>) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var user by remember { mutableStateOf("") }
    var assistant by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    fun edit(index: Int) {
        user = pairs.getOrNull(index)?.user.orEmpty()
        assistant = pairs.getOrNull(index)?.assistant.orEmpty()
        editing = index
    }
    Column {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.companion_examples_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(
                        Icons.Default.Info,
                        stringResource(R.string.companion_examples_help),
                        Modifier.size(18.dp),
                        tint = scheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { edit(pairs.size) }, enabled = pairs.size < 20 && state != "selecting") {
                    Text(stringResource(R.string.companion_examples_add), color = scheme.primary)
                }
            }
            HorizontalDivider(color = scheme.outlineVariant, thickness = 0.5.dp)
            pairs.forEachIndexed { index, pair ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontWeight = FontWeight.Bold)) {
                                append(stringResource(R.string.companion_examples_user_label)); append("：")
                            }
                            append(pair.user)
                        }, Modifier.weight(1f).padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        IconButton(onClick = { edit(index) }, enabled = state != "selecting") {
                            Icon(
                                Icons.Default.Edit,
                                stringResource(R.string.companion_examples_edit),
                                Modifier.size(17.dp),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onChange(pairs.filterIndexed { i, _ -> i != index }) }, enabled = state != "selecting") {
                            Icon(
                                Icons.Default.DeleteOutline,
                                stringResource(R.string.companion_examples_delete),
                                Modifier.size(18.dp),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)) { append("$name：") }
                        append(pair.assistant)
                    }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                HorizontalDivider(color = scheme.outlineVariant, thickness = 0.5.dp)
            }
            val active = state == "active" && pairs.isNotEmpty()
            Text(
                stringResource(when {
                    state == "disabled" -> R.string.companion_examples_inactive
                    active -> R.string.companion_examples_active
                    state == "selecting" -> R.string.companion_examples_selecting
                    state == "failed" -> R.string.companion_examples_failed
                    else -> R.string.companion_examples_empty
                }),
                Modifier.padding(top = 10.dp),
                color = if (active) scheme.primary else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (showInfo) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp),
                color = scheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, scheme.onSurface.copy(alpha = 0.10f))
            ) {
                Text(
                    stringResource(R.string.companion_examples_help_body),
                    Modifier.padding(12.dp),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    editing?.let { index ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.companion_examples_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BorderlessOutlinedTextField(
                        value = user,
                        onValueChange = { if (it.length <= 2000) user = it },
                        label = { Text(stringResource(R.string.companion_examples_user_label)) }
                    )
                    BorderlessOutlinedTextField(
                        value = assistant,
                        onValueChange = { if (it.length <= 4000) assistant = it },
                        label = { Text(name) }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = user.isNotBlank() && assistant.isNotBlank(), onClick = {
                    onChange(pairs.toMutableList().apply {
                        if (index < size) set(index, CompanionExample(user.trim(), assistant.trim()))
                        else add(CompanionExample(user.trim(), assistant.trim()))
                    })
                    editing = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}
