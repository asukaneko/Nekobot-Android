package com.nekobot.app.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProblemDiagnosticScreen(sessionId: String? = null, messageId: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ServiceContainer.localRepository }
    val collector = remember(repository) { repository.problemDiagnosticCollector() }
    var selectedScope by remember { mutableStateOf(if (messageId == null) DiagnosticScope.RUNTIME else DiagnosticScope.REPLY) }
    var selectedReply by remember { mutableStateOf<DiagnosticReplyChoice?>(messageId?.let { DiagnosticReplyChoice(it, sessionId.orEmpty(), "") }) }
    var choices by remember { mutableStateOf(emptyList<DiagnosticReplyChoice>()) }
    var choosingScope by remember { mutableStateOf(false) }
    var choosingReply by remember { mutableStateOf(false) }
    var includeText by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var bundle by remember { mutableStateOf<ProblemDiagnosticBundle?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }
    val savedText = stringResource(R.string.problem_diag_saved)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingZip
        pendingZip = null
        if (uri == null || bytes == null) { busy = false }
        else scope.launch {
            try {
                check(ServiceContainer.localRepository === repository)
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("destination unavailable")
                    output.use { it.write(bytes); it.flush() }
                }
                Toast.makeText(context, savedText, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(collector, sessionId) {
        try { choices = collector.replies(sessionId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    fun back() { if (!busy) { if (bundle != null) { bundle = null; expanded = null } else onBack() } }
    BackHandler { back() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(if (bundle == null) R.string.problem_diag_title else R.string.problem_diag_preview),
            modifier = Modifier.testTag(if (bundle == null) "problem-diagnostic-form-title" else "problem-diagnostic-preview-title")) },
            navigationIcon = { IconButton(onClick = { back() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("problem-diagnostic-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("problem-diagnostic-busy")) }
            if (failed) item { Text(stringResource(R.string.problem_diag_failed), color = MaterialTheme.colorScheme.error) }
            val preview = bundle
            if (preview == null) {
                item {
                    Text(stringResource(R.string.problem_diag_range), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { choosingScope = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("problem-diagnostic-scope")) {
                        Text(stringResource(when(selectedScope) { DiagnosticScope.REPLY -> R.string.problem_diag_reply; DiagnosticScope.IMPORT -> R.string.problem_diag_import; DiagnosticScope.RUNTIME -> R.string.problem_diag_runtime }))
                    }
                    if (selectedScope == DiagnosticScope.REPLY) OutlinedButton(onClick = { choosingReply = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("problem-diagnostic-reply")) {
                        Text(selectedReply?.label?.takeIf { it.isNotBlank() } ?: stringResource(if (selectedReply == null) R.string.problem_diag_choose_reply else R.string.problem_diag_selected_reply))
                    }
                }
                item { Text(stringResource(R.string.problem_diag_collect), style = MaterialTheme.typography.titleMedium) }
                listOf(R.string.problem_diag_app, R.string.problem_diag_model, R.string.problem_diag_recall, R.string.problem_diag_sources).forEach { label ->
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary); Text(stringResource(label))
                    } }
                }
                item { HorizontalDivider() }
                item {
                    Text(stringResource(R.string.problem_diag_optional), style = MaterialTheme.typography.titleMedium)
                    Row {
                        Checkbox(checked = includeText, onCheckedChange = { includeText = it }, enabled = !busy && selectedScope == DiagnosticScope.REPLY,
                            modifier = Modifier.testTag("problem-diagnostic-include-text"))
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(stringResource(R.string.problem_diag_include_text))
                            Text(stringResource(R.string.problem_diag_text_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { OutlinedTextField(description, { description = it.take(2000) }, enabled = !busy,
                    label = { Text(stringResource(R.string.problem_diag_description)) }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item { Text(stringResource(R.string.problem_diag_missing_note), style = MaterialTheme.typography.bodySmall) }
                item { Button(onClick = {
                    busy = true; failed = false
                    val target = selectedReply; val selected = selectedScope; val details = includeText; val note = description
                    scope.launch {
                        try {
                            check(ServiceContainer.localRepository === repository)
                            bundle = collector.collect(selected, target?.sessionId, target?.id, details, note)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }, enabled = !busy && (selectedScope != DiagnosticScope.REPLY || selectedReply != null),
                    modifier = Modifier.fillMaxWidth().testTag("problem-diagnostic-preview")) { Text(stringResource(R.string.problem_diag_preview)) } }
            } else {
                item { Text(stringResource(if (includeText) R.string.problem_diag_with_text else R.string.problem_diag_basic),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                item { Text(stringResource(R.string.problem_diag_checks)) }
                item { Text(stringResource(R.string.problem_diag_evidence_only), style = MaterialTheme.typography.bodySmall) }
                item { HorizontalDivider(); Text(stringResource(R.string.problem_diag_files), style = MaterialTheme.typography.titleMedium) }
                preview.files.forEach { (name, body) ->
                    item(key = name) { OutlinedButton(onClick = { expanded = if (expanded == name) null else name },
                        modifier = Modifier.fillMaxWidth().testTag("problem-diagnostic-file-$name")) { Text(name) } }
                    if (expanded == name) body.chunked(2500).forEachIndexed { i, chunk ->
                        item(key = "$name-$i") { SelectionContainer { Text(chunk, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                item { Text(stringResource(if (includeText) R.string.problem_diag_text_present else R.string.problem_diag_text_absent),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                item { Text(stringResource(R.string.problem_diag_privacy), style = MaterialTheme.typography.bodySmall) }
                if (preview.missing.isNotEmpty()) item { Text(stringResource(R.string.problem_diag_missing, preview.missing.size),
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("problem-diagnostic-missing")) }
                item { Button(onClick = {
                    busy = true; failed = false
                    scope.launch {
                        try {
                            check(ServiceContainer.localRepository === repository)
                            pendingZip = withContext(Dispatchers.IO) { preview.zip() }
                            saveLauncher.launch("ProblemDiagnostic_${System.currentTimeMillis()}.zip")
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { pendingZip = null; busy = false; failed = true }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("problem-diagnostic-save")) { Text(stringResource(R.string.problem_diag_save)) } }
                item { TextButton(onClick = { back() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.problem_diag_adjust)) } }
            }
        }
    }
    if (choosingScope) AlertDialog(onDismissRequest = { choosingScope = false }, title = { Text(stringResource(R.string.problem_diag_range)) },
        text = { Column { DiagnosticScope.entries.forEach { option -> TextButton(onClick = { selectedScope = option; includeText = false; choosingScope = false }) {
            Text(stringResource(when(option) { DiagnosticScope.REPLY -> R.string.problem_diag_reply; DiagnosticScope.IMPORT -> R.string.problem_diag_import; DiagnosticScope.RUNTIME -> R.string.problem_diag_runtime }))
        } } } }, confirmButton = { TextButton(onClick = { choosingScope = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (choosingReply) AlertDialog(onDismissRequest = { choosingReply = false }, title = { Text(stringResource(R.string.problem_diag_choose_reply)) },
        text = { LazyColumn(Modifier.heightIn(max = 380.dp)) {
            if (choices.isEmpty()) item { Text(stringResource(R.string.problem_diag_no_replies)) }
            choices.forEach { reply -> item(key = reply.id) { TextButton(onClick = { selectedReply = reply; choosingReply = false }) { Text(reply.label) } } }
        } }, confirmButton = { TextButton(onClick = { choosingReply = false }) { Text(stringResource(R.string.common_cancel)) } })
}
