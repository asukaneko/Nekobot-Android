package com.nekobot.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/** 工作区文件项 */
@Immutable
data class WorkspaceFile(
    val name: String,
    val type: String,        // "file" | "directory"
    val size: Long,
    val path: String,
    val mimeType: String = ""
) {
    val isDirectory get() = type == "directory"
}

/** 工作区页 ViewModel：列出/上传/删除/下载会话工作区文件。 */
class WorkspaceViewModel : BaseViewModel() {

    private val _files = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val files: StateFlow<List<WorkspaceFile>> = _files.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private var sessionId: String = ""

    fun init(id: String) {
        if (id == sessionId && _files.value.isNotEmpty()) return
        sessionId = id
        load("")
    }

    fun load(path: String = _currentPath.value) {
        if (sessionId.isBlank()) return
        val normalizedPath = normalizeWorkspaceBrowserPath(path)
        if (_currentPath.value != normalizedPath) {
            _files.value = emptyList()
        }
        _currentPath.value = normalizedPath
        launchResult(
            block = {
                unified.listWorkspaceFiles(
                    sessionId,
                    normalizedPath.ifBlank { null }
                )
            },
            onSuccess = { elem ->
                if (_currentPath.value == normalizedPath) {
                    _files.value = parseFiles(elem)
                }
            },
            onError = {
                if (_currentPath.value == normalizedPath) {
                    _files.value = emptyList()
                }
            }
        )
    }

    fun openDirectory(path: String) = load(path)

    fun navigateUp(): Boolean {
        val current = _currentPath.value
        if (current.isBlank()) return false
        load(parentWorkspaceBrowserPath(current))
        return true
    }

    /** 上传 Uri 指向的文件到工作区 */
    fun upload(context: Context, uri: Uri, onDone: () -> Unit) {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            _uploading.value = true
            try {
                val (name, bytes) = withContext(Dispatchers.IO) { readUri(context, uri) } ?: run {
                    showError(string(R.string.workspace_read_failed))
                    return@launch
                }
                val mediaType = guessMime(name).toMediaTypeOrNull()
                val body = bytes.toRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("file", name, body)
                when (val res = unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        showToast(string(R.string.workspace_uploaded, name))
                        load("")
                        onDone()
                    }
                    is Resource.Error -> showError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.workspace_upload_failed))
            } finally {
                _uploading.value = false
            }
        }
    }

    /** 删除工作区文件 */
    fun delete(workspacePath: String) {
        if (sessionId.isBlank()) return
        launchResult(
            block = { unified.deleteWorkspaceFile(sessionId, workspacePath) },
            onSuccess = { load() }
        )
    }

    /** 下载工作区文件到应用缓存目录，返回本地 File。 */
    suspend fun download(context: Context, workspacePath: String): File? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext null
        try {
            // 本地模式：直接复制本地工作区文件
            val localFile = unified.downloadWorkspaceFileLocal(sessionId, workspacePath)
            val target = workspacePreviewCacheFile(context, workspacePath)
            if (localFile != null && localFile.exists()) {
                localFile.copyTo(target, overwrite = true)
                return@withContext target
            }
            // 远程模式：走 retrofit Response
            val resp = unified.downloadWorkspaceFile(sessionId, workspacePath)
            if (resp != null && resp.isSuccessful) {
                val body = resp.body() ?: return@withContext null
                FileOutputStream(target).use { body.byteStream().copyTo(it) }
                target
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ---- helpers ----

    private fun parseFiles(elem: com.google.gson.JsonElement): List<WorkspaceFile> {
        val arr = if (elem.isJsonObject) {
            elem.asJsonObject.getAsJsonArray("files") ?: return emptyList()
        } else if (elem.isJsonArray) {
            elem.asJsonArray
        } else return emptyList()
        return arr.mapNotNull { it.asJsonObject?.let { o -> o.toFile() } }
    }

    private fun JsonObject.toFile(): WorkspaceFile = WorkspaceFile(
        name = get("name")?.asString ?: "",
        type = get("type")?.asString ?: "file",
        size = get("size")?.asLong ?: 0L,
        path = get("path")?.asString ?: "",
        mimeType = get("mime_type")?.asString ?: ""
    )

    private fun readUri(context: Context, uri: Uri): Pair<String, ByteArray>? {
        return try {
            val name = queryFileName(context, uri) ?: "file"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            name to bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun workspacePreviewCacheFile(context: Context, workspacePath: String): File {
        val safeName = File(workspacePath).name.ifBlank { "workspace_file" }
        val pathHash = workspacePath.hashCode().toUInt().toString(16)
        val directory = File(context.cacheDir, "workspace_previews/$pathHash").apply { mkdirs() }
        return File(directory, safeName)
    }
}

internal fun normalizeWorkspaceBrowserPath(path: String): String {
    val parts = mutableListOf<String>()
    path.replace('\\', '/').split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

internal fun parentWorkspaceBrowserPath(path: String): String =
    normalizeWorkspaceBrowserPath(path).substringBeforeLast('/', "")

/**
 * 工作区浏览器页：列出会话工作区文件，支持上传/删除/下载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    val viewModel: WorkspaceViewModel = viewModel()
    val files by viewModel.files.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deletingFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<java.io.File?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(sessionId) { viewModel.init(sessionId) }
    LaunchedEffect(toast) {
        if (toast != null) { snackbarHost.showSnackbar(toast!!); viewModel.clearToast() }
    }
    LaunchedEffect(error) {
        if (error != null) { snackbarHost.showSnackbar(error!!); viewModel.clearError() }
    }
    BackHandler(enabled = currentPath.isNotBlank()) {
        viewModel.navigateUp()
    }

    // 文件选择器：选取要上传的文件
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.upload(context, uri) {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (currentPath.isBlank()) {
                            stringResource(R.string.workspace_title)
                        } else {
                            "${stringResource(R.string.workspace_title)} / $currentPath"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!viewModel.navigateUp()) onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { pickFile.launch("*/*") }, enabled = !uploading) {
                        Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.workspace_upload_file), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                files.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                files.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.workspace_empty_title),
                        hint = stringResource(R.string.workspace_empty_hint),
                        icon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files, key = { it.path }) { f ->
                            WorkspaceFileItem(
                                file = f,
                                downloading = downloading == f.path,
                                previewLoading = previewLoading && previewFileName == f.path,
                                onOpenDirectory = { viewModel.openDirectory(f.path) },
                                onDelete = { deletingFile = f },
                                onDownload = {
                                    if (downloading == null) {
                                        downloading = f.path
                                        scope.launch {
                                            val saved = viewModel.download(context, f.path)
                                            downloading = null
                                            if (saved != null) {
                                                snackbarHost.showSnackbar(context.getString(R.string.workspace_downloaded_to, saved.name))
                                            } else {
                                                snackbarHost.showSnackbar(context.getString(R.string.workspace_download_failed))
                                            }
                                        }
                                    }
                                },
                                onPreview = {
                                    if (!previewLoading && previewFile == null) {
                                        previewFileName = f.path
                                        previewLoading = true
                                        scope.launch {
                                            val saved = viewModel.download(context, f.path)
                                            previewLoading = false
                                            if (saved != null) {
                                                if (isPdfWorkspaceFile(f.name, f.mimeType)) {
                                                    openLocalWorkspaceFile(context, saved)
                                                    previewFileName = ""
                                                } else {
                                                    previewFile = saved
                                                }
                                            } else {
                                                snackbarHost.showSnackbar(context.getString(R.string.workspace_preview_load_failed))
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    if (deletingFile != null) {
        NekoDialog(
            onDismiss = { deletingFile = null },
            title = stringResource(R.string.workspace_delete_file_title),
            message = stringResource(R.string.workspace_delete_confirm, deletingFile!!.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val f = deletingFile!!
                deletingFile = null
                viewModel.delete(f.path)
            }
        )
    }

    // 文件预览 Dialog：支持图片/文本/HTML/PDF
    val pf = previewFile
    if (pf != null) {
        FilePreviewDialog(
            fileName = File(previewFileName).name,
            file = pf,
            onDismiss = {
                previewFile = null
                previewFileName = ""
            }
        )
    }
}

@Composable
private fun WorkspaceFileItem(
    file: WorkspaceFile,
    downloading: Boolean,
    previewLoading: Boolean,
    onOpenDirectory: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !previewLoading) {
                    if (file.isDirectory) onOpenDirectory() else onPreview()
                }
        ) {
            Icon(
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (file.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (previewLoading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    if (file.isDirectory) stringResource(R.string.workspace_folder) else formatSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!file.isDirectory) {
                IconButton(onClick = onDownload, enabled = !downloading) {
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.workspace_download), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
