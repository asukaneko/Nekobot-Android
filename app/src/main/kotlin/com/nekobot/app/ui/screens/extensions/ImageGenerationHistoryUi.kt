package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.data.local.ImageHistoryEntry
import com.nekobot.app.ui.components.NekoDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图片生成实验室的历史记录：图片保存在应用私有目录，重启或清缓存后仍可查看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageHistoryDialog(
    entries: List<ImageHistoryEntry>,
    uriOf: (ImageHistoryEntry) -> String,
    onDismiss: () -> Unit,
    onPreview: (ImageHistoryEntry) -> Unit,
    onReusePrompt: (ImageHistoryEntry) -> Unit,
    onUseReference: (ImageHistoryEntry) -> Unit,
    onDelete: (ImageHistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var confirmClear by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.playground_history), fontWeight = FontWeight.SemiBold)
                            if (entries.isNotEmpty()) {
                                Text(stringResource(R.string.playground_history_count, entries.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                    },
                    actions = {
                        if (entries.isNotEmpty()) {
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(Icons.Default.DeleteOutline, stringResource(R.string.playground_history_clear))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                if (entries.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.History, null, modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.playground_history_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            ImageHistoryRow(entry, uriOf(entry), formatter.format(Date(entry.createdAt)),
                                onPreview = { onPreview(entry) }, onReusePrompt = { onReusePrompt(entry) },
                                onUseReference = { onUseReference(entry) }, onDelete = { onDelete(entry) })
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        NekoDialog(
            onDismiss = { confirmClear = false },
            title = stringResource(R.string.playground_history_clear),
            message = stringResource(R.string.playground_history_clear_confirm),
            destructive = true,
            onConfirm = { confirmClear = false; onClear() }
        )
    }
}

@Composable
private fun ImageHistoryRow(
    entry: ImageHistoryEntry,
    uri: String,
    timestamp: String,
    onPreview: () -> Unit,
    onReusePrompt: () -> Unit,
    onUseReference: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onPreview).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(uri, stringResource(R.string.imggen_generated_image), contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(listOf(entry.size, entry.modelName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timestamp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreHoriz, stringResource(R.string.playground_more_actions))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.playground_reuse_prompt)) },
                        onClick = { expanded = false; onReusePrompt() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.playground_use_reference)) },
                        onClick = { expanded = false; onUseReference() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                        onClick = { expanded = false; onDelete() })
                }
            }
        }
    }
}
