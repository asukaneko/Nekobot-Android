package com.nekobot.app.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ImportedFavoriteSnapshot
import com.nekobot.app.data.local.ImportedFavoriteSnapshotResult
import com.nekobot.app.data.local.ImportedFavoriteSnapshotOwner
import com.nekobot.app.data.local.importedSnapshotTimestamp
import com.nekobot.app.data.local.readImportedFavoriteSnapshots
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local, active-profile session only. Neither this loader nor its views writes any user data. */
@Composable
internal fun ImportedFavoriteSnapshotsEntry(sessionId: String) {
    val context = LocalContext.current
    val revision by ServiceContainer.dataSourceRevision.collectAsState()
    val mode by ServiceContainer.appModeFlow.collectAsState()
    val profile = ServiceContainer.prefs.activeDbName
    var snapshots by remember(sessionId, profile, revision, mode) {
        mutableStateOf(ImportedFavoriteSnapshotResult())
    }
    LaunchedEffect(sessionId, profile, revision, mode) {
        if (!ServiceContainer.prefs.isLocalMode) return@LaunchedEffect
        val repository = ServiceContainer.localRepository
        val owner = ImportedFavoriteSnapshotOwner(profile, revision, repository)
        fun stillActive() = owner.permits(ServiceContainer.prefs.isLocalMode,
            ServiceContainer.prefs.activeDbName, ServiceContainer.dataSourceRevision.value,
            ServiceContainer.localRepository)
        snapshots = try {
            withContext(Dispatchers.IO) {
                if (!stillActive() || repository.getSession(sessionId) == null) {
                    ImportedFavoriteSnapshotResult()
                } else {
                    val result = readImportedFavoriteSnapshots(context.filesDir, sessionId, ::stillActive)
                    if (stillActive() && repository.getSession(sessionId) != null) result
                    else ImportedFavoriteSnapshotResult()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImportedFavoriteSnapshotResult(unreadable = true)
        }
    }
    if (ServiceContainer.prefs.isLocalMode) ImportedFavoriteSnapshotsSection(snapshots)
}

@Composable
internal fun ImportedFavoriteSnapshotsSection(result: ImportedFavoriteSnapshotResult) {
    var open by remember(result) { mutableStateOf(false) }
    if (result.items.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Column(
                Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.imported_history_favorites_title),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.imported_history_favorites_summary, result.items.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (result.unreadable) {
        Text(stringResource(R.string.imported_history_favorites_unreadable), Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) ImportedFavoriteSnapshotsDialog(result.items) { open = false }
}

@Composable
internal fun ImportedFavoriteSnapshotsDialog(items: List<ImportedFavoriteSnapshot>, onDismiss: () -> Unit) {
    var selected by remember(items) { mutableStateOf<ImportedFavoriteSnapshot?>(null) }
    val close = { if (selected != null) selected = null else onDismiss() }
    BackHandler(onBack = close)
    key(selected) {
        NekoDialog(
            onDismiss = close,
            title = stringResource(R.string.imported_history_favorites_title),
            message = if (selected == null) stringResource(R.string.imported_history_favorites_read_only) else null,
            confirmText = stringResource(R.string.common_close),
            onConfirm = onDismiss,
            cancelText = null,
            contentScrollable = selected != null
        ) {
            val snapshot = selected
            if (snapshot == null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items, key = { index, _ -> "imported-snapshot:$index" }) { _, item ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().clickable { selected = item }.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.personaName.ifBlank { stringResource(R.string.imported_history_original_persona_unknown) },
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (item.favoriteTime.isNotBlank()) Text(
                                    stringResource(R.string.imported_history_favorite_time, importedSnapshotTimestamp(item.favoriteTime)),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.forwardedContent.ifBlank { item.messageContent },
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.imported_history_read_full),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.imported_history_back_to_snapshots))
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(snapshot.personaName.ifBlank { stringResource(R.string.imported_history_original_persona_unknown) },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (snapshot.favoriteTime.isNotBlank()) Text(
                        stringResource(R.string.imported_history_favorite_time, importedSnapshotTimestamp(snapshot.favoriteTime)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot.messageTime.isNotBlank()) Text(
                        stringResource(R.string.imported_history_message_time, importedSnapshotTimestamp(snapshot.messageTime)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot.messageContent.isNotBlank()) SnapshotText(
                        stringResource(R.string.imported_history_message_content), snapshot.messageContent)
                    if (snapshot.forwardedContent.isNotBlank()) SnapshotText(
                        stringResource(R.string.imported_history_forwarded_content), snapshot.forwardedContent)
                }
            }
        }
    }
}

@Composable
private fun SnapshotText(label: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(content, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** Shows the stored prompt verbatim. It does not generate an image or call a model. */
@Composable
internal fun ImportedImageDescriptionButton(prompt: String) {
    if (prompt.isBlank()) return
    var open by remember(prompt) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, stringResource(R.string.imported_history_image_description), tint = Color.White)
    }
    if (open) NekoDialog(
        onDismiss = { open = false },
        title = stringResource(R.string.imported_history_image_description),
        confirmText = stringResource(R.string.common_close),
        onConfirm = { open = false },
        cancelText = null,
        contentScrollable = true
    ) {
        SelectionContainer { Text(prompt, style = MaterialTheme.typography.bodyLarge) }
    }
}
