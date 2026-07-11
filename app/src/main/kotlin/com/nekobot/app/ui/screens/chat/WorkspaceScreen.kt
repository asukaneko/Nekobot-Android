package com.nekobot.app.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
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

    private var sessionId: String = ""

    fun init(id: String) {
        if (id == sessionId && _files.value.isNotEmpty()) return
        sessionId = id
        load()
    }

    fun load() {
        if (sessionId.isBlank()) return
        launchResult(
            block = { unified.listWorkspaceFiles(sessionId, null) },
            onSuccess = { elem -> _files.value = parseFiles(elem) },
            onError = { _files.value = emptyList() }
        )
    }

    /** 上传 Uri 指向的文件到工作区 */
    fun upload(context: Context, uri: Uri, onDone: () -> Unit) {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            _uploading.value = true
            try {
                val (name, bytes) = withContext(Dispatchers.IO) { readUri(context, uri) } ?: run {
                    showError("读取文件失败")
                    return@launch
                }
                val mediaType = guessMime(name).toMediaTypeOrNull()
                val body = bytes.toRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("file", name, body)
                when (val res = unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        showToast("已上传: $name")
                        load()
                        onDone()
                    }
                    is Resource.Error -> showError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: "上传失败")
            } finally {
                _uploading.value = false
            }
        }
    }

    /** 删除工作区文件 */
    fun delete(filename: String) {
        if (sessionId.isBlank()) return
        launchResult(
            block = { unified.deleteWorkspaceFile(sessionId, filename) },
            onSuccess = { load() }
        )
    }

    /** 下载工作区文件到应用缓存目录，返回本地 File。 */
    suspend fun download(context: Context, filename: String): File? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext null
        try {
            val resp = unified.downloadWorkspaceFile(sessionId, filename)
            if (resp != null && resp.isSuccessful) {
                val body = resp.body() ?: return@withContext null
                val target = File(context.cacheDir, "workspace_$filename")
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
}

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deletingFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(sessionId) { viewModel.init(sessionId) }
    LaunchedEffect(toast) {
        if (toast != null) { snackbarHost.showSnackbar(toast!!); viewModel.clearToast() }
    }
    LaunchedEffect(error) {
        if (error != null) { snackbarHost.showSnackbar(error!!); viewModel.clearError() }
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
                title = { Text("工作区", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { pickFile.launch("*/*") }, enabled = !uploading) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "上传文件", tint = MaterialTheme.colorScheme.onSurface)
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
                        title = "工作区为空",
                        hint = "点击右上角上传文件到工作区",
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
                                downloading = downloading == f.name,
                                onDelete = { deletingFile = f },
                                onDownload = {
                                    if (downloading == null) {
                                        downloading = f.name
                                        scope.launch {
                                            val saved = viewModel.download(context, f.name)
                                            downloading = null
                                            if (saved != null) {
                                                snackbarHost.showSnackbar("已下载到: ${saved.name}")
                                            } else {
                                                snackbarHost.showSnackbar("下载失败")
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
            title = "删除文件",
            message = "确认删除 \"${deletingFile!!.name}\" 吗？",
            confirmText = "删除",
            onConfirm = {
                val f = deletingFile!!
                deletingFile = null
                viewModel.delete(f.name)
            }
        )
    }
}

@Composable
private fun WorkspaceFileItem(
    file: WorkspaceFile,
    downloading: Boolean,
    onDelete: () -> Unit,
    onDownload: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (file.isDirectory) "文件夹" else formatSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!file.isDirectory) {
                IconButton(onClick = onDownload, enabled = !downloading) {
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = "下载", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
