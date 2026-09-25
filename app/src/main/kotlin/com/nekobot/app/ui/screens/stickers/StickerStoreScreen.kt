package com.nekobot.app.ui.screens.stickers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.data.local.LocalStickerStorage
import com.nekobot.app.data.local.StickerArchiveReader
import com.nekobot.app.data.local.db.LocalStickerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 表情包管理页：批量导入图片/压缩包、重命名、删除。
 *
 * 表情名即聊天正文 `[名称]` 的引用键；导入的图片随数据库归档与 WebDAV 备份一起迁移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerStoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: StickerStoreViewModel = viewModel()
    val stickers by vm.stickers.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var detailSticker by remember { mutableStateOf<LocalStickerEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalStickerEntity?>(null) }
    // 批量选择：长按进入选择模式，可全选（仅当前搜索结果）后批量删除
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDeleteRequested by remember { mutableStateOf(false) }

    // 表情被删除（含批量删除）后清理已失效的选中项
    LaunchedEffect(stickers) {
        val valid = stickers.mapTo(HashSet()) { it.id }
        selectedIds = selectedIds.filterTo(HashSet()) { it in valid }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 批量选择图片：名称取文件名，导入前可在预览中修改。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readImageDraft(context, uri) }
            }
            vm.addDrafts(items)
        }
    }
    // 导入导出的表情压缩包（图片名即表情名）。
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val items = withContext(Dispatchers.IO) { readZipDrafts(context, uri) }
            if (items.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.sticker_import_zip_empty), Toast.LENGTH_SHORT).show()
            } else {
                vm.addDrafts(items)
            }
        }
    }

    val filtered = remember(stickers, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) stickers
        else stickers.filter { it.name.contains(keyword, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(stringResource(R.string.sticker_selected_count, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.sticker_manage_title))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds = emptySet()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        if (selectionMode) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_cancel)
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        val allSelected = filtered.isNotEmpty() &&
                            filtered.all { it.id in selectedIds }
                        TextButton(
                            onClick = {
                                selectedIds = if (allSelected) {
                                    emptySet()
                                } else {
                                    filtered.mapTo(HashSet()) { it.id }
                                }
                            },
                            enabled = filtered.isNotEmpty()
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.sticker_select_none
                                    else R.string.sticker_select_all
                                )
                            )
                        }
                        IconButton(
                            onClick = { batchDeleteRequested = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.sticker_batch_delete_action)
                            )
                        }
                    } else {
                        IconButton(onClick = { pickImages.launch("image/*") }) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = stringResource(R.string.sticker_import_images)
                            )
                        }
                        IconButton(
                            onClick = {
                                pickZip.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                )
                            }
                        ) {
                            Icon(
                                Icons.Filled.FolderZip,
                                contentDescription = stringResource(R.string.sticker_import_zip)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = stringResource(R.string.sticker_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }

            if (stickers.isEmpty()) {
                StickerEmptyState(
                    onImportImages = { pickImages.launch("image/*") },
                    onImportZip = {
                        pickZip.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                        )
                    }
                )
            } else {
                if (stickers.size > 12) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.sticker_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                    )
                }
                Text(
                    text = stringResource(R.string.sticker_count, filtered.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 92.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { sticker ->
                        StickerManageCell(
                            sticker = sticker,
                            selectionMode = selectionMode,
                            selected = sticker.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = selectedIds.toggle(sticker.id)
                                } else {
                                    detailSticker = sticker
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selectedIds = selectedIds + sticker.id
                            }
                        )
                    }
                }
            }
        }
    }

    if (drafts.isNotEmpty()) {
        StickerImportReviewDialog(
            drafts = drafts,
            onNameChange = vm::updateDraftName,
            onRemove = vm::removeDraft,
            onConfirm = { vm.confirmImport() },
            onDismiss = { vm.clearDrafts() }
        )
    }

    detailSticker?.let { sticker ->
        StickerDetailDialog(
            sticker = sticker,
            onRename = { newName ->
                vm.rename(sticker.id, newName)
                detailSticker = null
            },
            onDelete = {
                deleteTarget = sticker
                detailSticker = null
            },
            onDismiss = { detailSticker = null }
        )
    }

    deleteTarget?.let { sticker ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.sticker_delete_title)) },
            text = { Text(stringResource(R.string.sticker_delete_message, sticker.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(sticker.id)
                        deleteTarget = null
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (batchDeleteRequested) {
        AlertDialog(
            onDismissRequest = { batchDeleteRequested = false },
            title = { Text(stringResource(R.string.sticker_delete_title)) },
            text = { Text(stringResource(R.string.sticker_batch_delete_message, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMany(selectedIds)
                        batchDeleteRequested = false
                        selectionMode = false
                        selectedIds = emptySet()
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteRequested = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 空状态：引导从图片或压缩包导入。 */
@Composable
private fun StickerEmptyState(
    onImportImages: () -> Unit,
    onImportZip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.EmojiEmotions,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sticker_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onImportImages) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sticker_import_images))
            }
            TextButton(onClick = onImportZip) {
                Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sticker_import_zip))
            }
        }
    }
}

/**
 * 表情网格单元：图片 + 名称。
 *
 * 普通模式点击进入详情编辑；长按进入批量选择模式，选择模式下点击切换选中状态。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerManageCell(
    sticker: LocalStickerEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = sticker.filePath,
                contentDescription = sticker.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = sticker.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 选中集合的增删切换。 */
private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/** 导入预览：逐条校对名称，确认后批量写入。 */
@Composable
private fun StickerImportReviewDialog(
    drafts: List<StickerDraft>,
    onNameChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sticker_import_review_title, drafts.size)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sticker_import_review_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(drafts) { index, draft ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { onNameChange(index, it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.sticker_name_hint)) }
                            )
                            IconButton(onClick = { onRemove(index) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.sticker_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 表情详情：预览 + 重命名 + 删除。 */
@Composable
private fun StickerDetailDialog(
    sticker: LocalStickerEntity,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(sticker.id) { mutableStateOf(sticker.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sticker.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = sticker.filePath,
                    contentDescription = sticker.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.sticker_name_hint)) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text(stringResource(R.string.sticker_rename_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}

/** 读取单张图片为导入草稿；类型不支持时返回 null。 */
private fun readImageDraft(context: Context, uri: Uri): StickerDraft? {
    val resolver = context.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: return null
    val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
    if (!LocalStickerStorage.isSupportedImage(mimeType, displayName)) return null
    return StickerDraft(
        name = LocalStickerStorage.defaultNameFromFileName(displayName),
        uri = uri.toString(),
        mimeType = mimeType,
        sourceName = displayName,
        source = "import"
    )
}

/** 从压缩包读取表情草稿（图片名即名称）。 */
private fun readZipDrafts(context: Context, uri: Uri): List<StickerDraft> = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        StickerArchiveReader.readZip(input).map { item ->
            StickerDraft(
                name = item.name,
                bytes = item.bytes,
                mimeType = item.mimeType,
                sourceName = item.sourceName,
                source = item.source
            )
        }
    }.orEmpty()
}.getOrDefault(emptyList())
