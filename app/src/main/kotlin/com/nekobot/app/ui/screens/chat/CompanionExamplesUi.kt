package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionExample

@Composable
internal fun CompanionExamplesOffer(viewModel: ChatViewModel) {
    val row by viewModel.companionExamples.collectAsStateWithLifecycle()
    if (row?.state != "offered") return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp), color = Color(0xFFF1E9FB)) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.companion_examples_offer), Modifier.weight(1f), fontSize = 11.sp,
                lineHeight = 16.sp, color = Color(0xFF4A4458))
            Button(onClick = viewModel::selectCompanionExamples, shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E5CC4)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.defaultMinSize(minWidth = 48.dp)) {
                Text(stringResource(R.string.companion_examples_enable), fontSize = 11.sp)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = viewModel::declineCompanionExamples, shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFCAC4D0)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp)) {
                Text(stringResource(R.string.companion_examples_decline), fontSize = 11.sp, color = Color(0xFF4A4458))
            }
            IconButton(onClick = viewModel::declineCompanionExamples, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.common_close), Modifier.size(16.dp), tint = Color(0xFF8A8590))
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
        Text(stringResource(R.string.companion_examples_disabled_notice, name),
            color = Color(0xFF8A8590), textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 19.sp)
        if (onDismiss != null) {
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 32.dp)) {
                Text(stringResource(R.string.companion_examples_notice_dismiss), fontSize = 12.sp, color = Color(0xFF8A8590))
            }
        }
    }
}

@Composable
internal fun CompanionExamplesEditor(name: String, pairs: List<CompanionExample>, state: String,
    onChange: (List<CompanionExample>) -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var user by remember { mutableStateOf("") }
    var assistant by remember { mutableStateOf("") }
    val purple = Color(0xFF7E5CC4)
    fun edit(index: Int) {
        user = pairs.getOrNull(index)?.user.orEmpty()
        assistant = pairs.getOrNull(index)?.assistant.orEmpty()
        editing = index
    }
    Column {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 1.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.companion_examples_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showInfo = !showInfo }) {
                        Icon(Icons.Default.Info, stringResource(R.string.companion_examples_help), Modifier.size(18.dp), tint = purple)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { edit(pairs.size) }, enabled = pairs.size < 20 && state != "selecting") {
                        Text(stringResource(R.string.companion_examples_add), color = purple)
                    }
                }
                HorizontalDivider(color = Color(0xFFE9E7EC))
                pairs.forEachIndexed { index, pair ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = Color(0xFF7A757F), fontWeight = FontWeight.Bold)) { append(stringResource(R.string.companion_examples_user_label)); append("：") }
                                append(pair.user)
                            }, Modifier.weight(1f).padding(top = 10.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { edit(index) }, enabled = state != "selecting") {
                                Icon(Icons.Default.Edit, stringResource(R.string.companion_examples_edit), Modifier.size(17.dp), tint = Color(0xFF7A757F))
                            }
                            IconButton(onClick = { onChange(pairs.filterIndexed { i, _ -> i != index }) }, enabled = state != "selecting") {
                                Icon(Icons.Default.DeleteOutline, stringResource(R.string.companion_examples_delete), Modifier.size(18.dp), tint = Color(0xFF7A757F))
                            }
                        }
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = purple, fontWeight = FontWeight.Bold)) { append("$name：") }
                            append(pair.assistant)
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(color = Color(0xFFE9E7EC))
                }
                val active = state == "active" && pairs.isNotEmpty()
                Text(stringResource(when {
                    state == "disabled" -> R.string.companion_examples_inactive
                    active -> R.string.companion_examples_active
                    state == "selecting" -> R.string.companion_examples_selecting
                    state == "failed" -> R.string.companion_examples_failed
                    else -> R.string.companion_examples_empty
                }), Modifier.padding(top = 10.dp), color = if (active) Color(0xFF3A9063) else Color(0xFF8A8590), fontSize = 12.sp)
            }
        }
        if (showInfo) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
            Box(Modifier.padding(start = 84.dp).offset(y = (-6).dp).size(12.dp).rotate(45f).background(Color(0xFF3B3546)))
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF3B3546)) {
                Text(stringResource(R.string.companion_examples_help_body), Modifier.padding(12.dp), color = Color.White,
                    fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
    editing?.let { index ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.companion_examples_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { if (it.length <= 2000) user = it }, label = { Text(stringResource(R.string.companion_examples_user_label)) })
                OutlinedTextField(assistant, { if (it.length <= 4000) assistant = it }, label = { Text(name) })
            } },
            confirmButton = { TextButton(enabled = user.isNotBlank() && assistant.isNotBlank(), onClick = {
                onChange(pairs.toMutableList().apply { if (index < size) set(index, CompanionExample(user.trim(), assistant.trim())) else add(CompanionExample(user.trim(), assistant.trim())) })
                editing = null
            }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
