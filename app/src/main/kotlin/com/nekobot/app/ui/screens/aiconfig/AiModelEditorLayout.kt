package com.nekobot.app.ui.screens.aiconfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

/** 创建与编辑共用完整页面，底部操作始终可见。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiModelEditorLayout(
    isEditing: Boolean,
    step: Int,
    busy: Boolean,
    hasChanges: Boolean,
    error: String?,
    onStep: (Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val labels = listOf(
        stringResource(R.string.model_setup_basics),
        stringResource(R.string.model_setup_connection),
        stringResource(R.string.model_setup_options)
    )
    val hints = listOf(
        stringResource(R.string.model_setup_basics_hint),
        stringResource(R.string.model_setup_connection_hint),
        stringResource(R.string.model_setup_options_hint)
    )
    var discard by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = { if (!busy) { if (hasChanges) discard = true else onBack() } }
    BackHandler { if (!busy) { if (step > 0) onStep(step - 1) else leave() } }
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("ai-model-editor-page"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isEditing) R.string.aimodel_editor_dialog_title_edit else R.string.aimodel_editor_dialog_title_new)) },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) OutlinedButton(
                            enabled = !busy,
                            onClick = { focus.clearFocus(); onStep(step - 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.model_setup_previous)) }
                        Button(enabled = !busy, onClick = { focus.clearFocus(); onNext() },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("model-setup-next")) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(if (step == 2) {
                                if (isEditing) R.string.common_save else R.string.model_setup_create
                            } else R.string.model_setup_next))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, title ->
                        Surface(onClick = { focus.clearFocus(); onStep(index) }, enabled = !busy && index <= step,
                            modifier = Modifier.weight(1f).semantics { selected = index == step },
                            shape = RoundedCornerShape(12.dp),
                            color = if (index == step) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (index < step) Icon(Icons.Default.Check, null, Modifier.size(20.dp))
                                else Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                                Text(title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(labels[step], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(hints[step], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
                Spacer(Modifier.height(8.dp))
            }
            if (busy) {
                // 阻止加载和保存期间继续改动表单。
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                )) { com.nekobot.app.ui.components.LoadingOverlay(visible = true) }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.model_setup_discard_title)) },
        text = { Text(stringResource(R.string.model_setup_discard_hint)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.model_setup_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.model_setup_keep_editing)) } }
    )
}

@Composable
internal fun ModelEditorSection(title: String, expandable: Boolean = false, forceExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(!expandable) }
    val expansionState = stringResource(if (expanded || forceExpanded) R.string.model_setup_expanded else R.string.model_setup_collapsed)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expandable) TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionState },
                contentPadding = PaddingValues(0.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (expanded || forceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            } else Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (expanded || forceExpanded) content()
        }
    }
}
