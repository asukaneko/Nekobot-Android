package com.nekobot.app.ui.screens.chat

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
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
import java.io.OutputStream

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

    // ---- 共享工作区 ----
    private val _sharedFiles = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val sharedFiles: StateFlow<List<WorkspaceFile>> = _sharedFiles.asStateFlow()

    private val _sharedPath = MutableStateFlow("")
    val sharedPath: StateFlow<String> = _sharedPath.asStateFlow()

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

    /** 长按后在当前工作区内把文件或文件夹放入目标文件夹。 */
    fun moveWithinPrivate(sourcePath: String, targetPath: String) {
        if (sessionId.isBlank() || sourcePath == targetPath) return
        launchResult(
            block = { unified.moveWorkspaceFile(sessionId, sourcePath, targetPath) },
            onSuccess = {
                showToast("已移动到 $targetPath")
                load()
            }
        )
    }

    fun moveWithinShared(sourcePath: String, targetPath: String) {
        if (sourcePath == targetPath) return
        launchResult(
            block = { unified.moveSharedFile(sourcePath, targetPath) },
            onSuccess = {
                showToast("已移动到 $targetPath")
                loadShared()
            }
        )
    }

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

    /** 移动文件到共享工作区 */
    fun moveToShared(workspacePath: String) {
        if (sessionId.isBlank()) return
        launchResult(
            block = { unified.moveToShared(sessionId, workspacePath) },
            onSuccess = {
                showToast(string(R.string.shared_file_moved_to_shared, workspacePath.substringAfterLast('/')))
                load()
                loadShared()
            }
        )
    }

    // ==================== 共享工作区操作 ====================

    /** 加载共享工作区文件列表 */
    fun loadShared(path: String = _sharedPath.value) {
        val normalizedPath = normalizeWorkspaceBrowserPath(path)
        if (_sharedPath.value != normalizedPath) {
            _sharedFiles.value = emptyList()
        }
        _sharedPath.value = normalizedPath
        launchResult(
            block = { unified.listSharedFiles(normalizedPath.ifBlank { null }) },
            onSuccess = { elem ->
                if (_sharedPath.value == normalizedPath) {
                    _sharedFiles.value = parseFiles(elem)
                }
            },
            onError = {
                if (_sharedPath.value == normalizedPath) {
                    _sharedFiles.value = emptyList()
                }
            }
        )
    }

    fun openSharedDirectory(path: String) = loadShared(path)

    fun navigateSharedUp(): Boolean {
        val current = _sharedPath.value
        if (current.isBlank()) return false
        loadShared(parentWorkspaceBrowserPath(current))
        return true
    }

    /** 上传文件到共享工作区 */
    fun uploadShared(context: Context, uri: Uri, onDone: () -> Unit) {
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
                when (val res = unified.uploadSharedFile(part)) {
                    is Resource.Success -> {
                        showToast(string(R.string.shared_upload_success))
                        loadShared()
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

    /** 删除共享工作区文件 */
    fun deleteShared(filename: String) {
        launchResult(
            block = { unified.deleteSharedFile(filename) },
            onSuccess = {
                showToast(string(R.string.shared_delete_success))
                loadShared()
            }
        )
    }

    /** 把共享文件移回当前会话 */
    fun moveSharedToPrivate(filename: String) {
        if (sessionId.isBlank()) return
        launchResult(
            block = { unified.moveSharedToPrivate(filename, sessionId) },
            onSuccess = {
                showToast(string(R.string.shared_file_moved_to_private, filename.substringAfterLast('/')))
                loadShared()
                load()
            }
        )
    }

    /** 准备共享文件到缓存目录供预览 */
    suspend fun prepareSharedForOpen(context: Context, sharedPath: String): File? = withContext(Dispatchers.IO) {
        try {
            val target = workspacePreviewCacheFile(context, sharedPath)
            FileOutputStream(target).use { output ->
                if (!copySharedFileTo(sharedPath, output)) {
                    target.delete()
                    return@withContext null
                }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    /** 把共享文件保存到 Downloads */
    suspend fun saveSharedToDownloads(context: Context, file: WorkspaceFile): String? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val resolver = context.contentResolver
            var targetUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType.ifBlank { guessMime(file.name) })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                val copied = resolver.openOutputStream(targetUri, "w")?.use { output ->
                    copySharedFileTo(file.path, output)
                } == true
                if (!copied) {
                    resolver.delete(targetUri, null, null)
                    return@withContext null
                }
                resolver.update(
                    targetUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                file.name
            } catch (e: Exception) {
                targetUri?.let { runCatching { resolver.delete(it, null, null) } }
                null
            }
        }

    /** Android 9 及以下：写入系统选择器返回的目标 Uri */
    suspend fun saveSharedToUri(context: Context, file: WorkspaceFile, targetUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                    copySharedFileTo(file.path, output)
                } == true
            } catch (e: Exception) {
                false
            }
        }

    /** 复制共享文件到输出流 */
    private suspend fun copySharedFileTo(sharedPath: String, output: OutputStream): Boolean {
        val localFile = unified.downloadSharedFileLocal(sharedPath)
        if (localFile != null && localFile.isFile) {
            localFile.inputStream().use { input -> input.copyTo(output) }
            return true
        }
        val response = unified.downloadSharedFile(sharedPath)
        if (response == null || !response.isSuccessful) return false
        val body = response.body() ?: return false
        body.use { responseBody ->
            responseBody.byteStream().use { input -> input.copyTo(output) }
        }
        return true
    }

    /** 把工作区文件准备到应用缓存目录，供预览或外部应用打开。 */
    suspend fun prepareForOpen(context: Context, workspacePath: String): File? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext null
        try {
            val target = workspacePreviewCacheFile(context, workspacePath)
            FileOutputStream(target).use { output ->
                if (!copyWorkspaceFileTo(workspacePath, output)) {
                    target.delete()
                    return@withContext null
                }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把工作区文件流式写入公共 Download 目录。
     *
     * Android 10+ 通过 MediaStore.Downloads 写入，避免把大文件先读进内存。
     * Android 9 及以下由页面层改走系统“创建文档”选择器。
     */
    suspend fun saveToDownloads(context: Context, file: WorkspaceFile): String? =
        withContext(Dispatchers.IO) {
            if (sessionId.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext null
            }
            val resolver = context.contentResolver
            var targetUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType.ifBlank { guessMime(file.name) })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                val copied = resolver.openOutputStream(targetUri, "w")?.use { output ->
                    copyWorkspaceFileTo(file.path, output)
                } == true
                if (!copied) {
                    resolver.delete(targetUri, null, null)
                    return@withContext null
                }
                resolver.update(
                    targetUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                file.name
            } catch (e: Exception) {
                targetUri?.let { runCatching { resolver.delete(it, null, null) } }
                null
            }
        }

    /** Android 9 及以下：写入系统“创建文档”选择器返回的目标 Uri。 */
    suspend fun saveToUri(context: Context, file: WorkspaceFile, targetUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            if (sessionId.isBlank()) return@withContext false
            try {
                context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                    copyWorkspaceFileTo(file.path, output)
                } == true
            } catch (e: Exception) {
                false
            }
        }

    // ---- helpers ----

    /** 本地模式直接读工作区文件；远程模式从接口响应流式复制。 */
    private suspend fun copyWorkspaceFileTo(
        workspacePath: String,
        output: OutputStream
    ): Boolean {
        val localFile = unified.downloadWorkspaceFileLocal(sessionId, workspacePath)
        if (localFile != null && localFile.isFile) {
            localFile.inputStream().use { input -> input.copyTo(output) }
            return true
        }
        val response = unified.downloadWorkspaceFile(sessionId, workspacePath)
        if (response == null || !response.isSuccessful) return false
        val body = response.body() ?: return false
        body.use { responseBody ->
            responseBody.byteStream().use { input -> input.copyTo(output) }
        }
        return true
    }

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
    val files by viewModel.files.collectAsStateWithLifecycle()
    val sharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val sharedPath by viewModel.sharedPath.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tabIndex by remember { mutableStateOf(0) } // 0=会话工作区, 1=共享工作区
    var deletingFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    var movingFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    var draggingFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<java.io.File?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingLegacyDownload by remember { mutableStateOf<Pair<WorkspaceFile, Boolean>?>(null) } // (file, isShared)
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(sessionId) { viewModel.init(sessionId) }
    LaunchedEffect(tabIndex) {
        if (tabIndex == 1 && sharedFiles.isEmpty()) viewModel.loadShared()
    }
    LaunchedEffect(toast) {
        if (toast != null) { snackbarHost.showSnackbar(toast!!); viewModel.clearToast() }
    }
    LaunchedEffect(error) {
        if (error != null) { snackbarHost.showSnackbar(error!!); viewModel.clearError() }
    }
    val activePath = if (tabIndex == 0) currentPath else sharedPath
    BackHandler(enabled = activePath.isNotBlank()) {
        if (tabIndex == 0) viewModel.navigateUp() else viewModel.navigateSharedUp()
    }

    // 文件选择器：根据当前 Tab 上传到不同工作区
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            if (tabIndex == 0) viewModel.upload(context, uri) {}
            else viewModel.uploadShared(context, uri) {}
        }
    }
    val createDownloadDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingLegacyDownload
        pendingLegacyDownload = null
        if (uri == null || pending == null) {
            downloading = null
        } else {
            val (file, isShared) = pending
            scope.launch {
                val saved = if (isShared) viewModel.saveSharedToUri(context, file, uri)
                             else viewModel.saveToUri(context, file, uri)
                downloading = null
                snackbarHost.showSnackbar(
                    if (saved) {
                        context.getString(R.string.workspace_downloaded_to, file.name)
                    } else {
                        context.getString(R.string.workspace_download_failed)
                    }
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (activePath.isBlank()) {
                            stringResource(R.string.workspace_title)
                        } else {
                            "${stringResource(R.string.workspace_title)} / $activePath"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (tabIndex == 0) {
                                if (!viewModel.navigateUp()) onBack()
                            } else {
                                if (!viewModel.navigateSharedUp()) onBack()
                            }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab 切换栏
            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text(stringResource(R.string.shared_workspace_tab_session)) }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = {
                        tabIndex = 1
                        viewModel.loadShared()
                    },
                    text = { Text(stringResource(R.string.shared_workspace_tab_shared)) }
                )
            }

            draggingFile?.let { source ->
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在移动：${source.name}，点击目标文件夹放置",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { draggingFile = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val displayFiles = if (tabIndex == 0) files else sharedFiles
                when {
                    displayFiles.isEmpty() && loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    displayFiles.isEmpty() -> {
                        EmptyState(
                            title = if (tabIndex == 0) stringResource(R.string.workspace_empty_title)
                                    else stringResource(R.string.shared_workspace_empty),
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
                            items(displayFiles, key = { it.path }) { f ->
                                val isShared = tabIndex == 1
                                WorkspaceFileItem(
                                    file = f,
                                    isSharedMode = isShared,
                                    downloading = downloading == f.path,
                                    previewLoading = previewLoading && previewFileName == f.path,
                                    onOpenDirectory = {
                                        val source = draggingFile
                                        if (source != null) {
                                            draggingFile = null
                                            if (source.path != f.path && !f.path.startsWith("${source.path}/")) {
                                                if (isShared) viewModel.moveWithinShared(source.path, f.path)
                                                else viewModel.moveWithinPrivate(source.path, f.path)
                                            }
                                        } else if (isShared) viewModel.openSharedDirectory(f.path)
                                        else viewModel.openDirectory(f.path)
                                    },
                                    onLongClick = { draggingFile = f },
                                    onDelete = { deletingFile = f },
                                    onMove = { movingFile = f },
                                    onDownload = {
                                        if (downloading == null) {
                                            downloading = f.path
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                                pendingLegacyDownload = f to isShared
                                                createDownloadDocument.launch(f.name)
                                            } else {
                                                scope.launch {
                                                    val savedName = if (isShared) viewModel.saveSharedToDownloads(context, f)
                                                                    else viewModel.saveToDownloads(context, f)
                                                    downloading = null
                                                    snackbarHost.showSnackbar(
                                                        if (savedName != null) {
                                                            context.getString(R.string.workspace_downloaded_to, savedName)
                                                        } else {
                                                            context.getString(R.string.workspace_download_failed)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onPreview = {
                                        if (!previewLoading && previewFile == null) {
                                            previewFileName = f.path
                                            previewLoading = true
                                            scope.launch {
                                                val saved = if (isShared) viewModel.prepareSharedForOpen(context, f.path)
                                                             else viewModel.prepareForOpen(context, f.path)
                                                previewLoading = false
                                                if (saved != null) {
                                                    when {
                                                        isPlainTextWorkspaceFile(f.name, f.mimeType) -> {
                                                            openLocalWorkspaceFile(
                                                                context = context,
                                                                file = saved,
                                                                forceChooser = true
                                                            )
                                                            previewFileName = ""
                                                        }
                                                        isPdfWorkspaceFile(f.name, f.mimeType) -> {
                                                            openLocalWorkspaceFile(context, saved)
                                                            previewFileName = ""
                                                        }
                                                        else -> previewFile = saved
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
    }

    // 删除确认
    if (deletingFile != null) {
        val isShared = tabIndex == 1
        NekoDialog(
            onDismiss = { deletingFile = null },
            title = stringResource(R.string.workspace_delete_file_title),
            message = stringResource(R.string.workspace_delete_confirm, deletingFile!!.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val f = deletingFile!!
                deletingFile = null
                if (isShared) viewModel.deleteShared(f.path) else viewModel.delete(f.path)
            }
        )
    }

    // 移动文件确认
    if (movingFile != null) {
        val isShared = tabIndex == 1
        NekoDialog(
            onDismiss = { movingFile = null },
            title = stringResource(if (isShared) R.string.shared_move_to_private else R.string.shared_move_to_shared),
            message = stringResource(
                if (isShared) R.string.shared_file_moved_to_private else R.string.shared_file_moved_to_shared,
                movingFile!!.name
            ),
            confirmText = stringResource(if (isShared) R.string.shared_move_to_private else R.string.shared_move_to_shared),
            onConfirm = {
                val f = movingFile!!
                movingFile = null
                if (isShared) viewModel.moveSharedToPrivate(f.path) else viewModel.moveToShared(f.path)
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
@OptIn(ExperimentalFoundationApi::class)
private fun WorkspaceFileItem(
    file: WorkspaceFile,
    isSharedMode: Boolean = false,
    downloading: Boolean,
    previewLoading: Boolean,
    onOpenDirectory: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !previewLoading,
                    onClick = { if (file.isDirectory) onOpenDirectory() else onPreview() },
                    onLongClick = onLongClick
                )
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
            // 移动文件按钮：共享模式→移回会话，会话模式→移到共享
            IconButton(onClick = onMove) {
                if (isSharedMode) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shared_move_to_private), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.FolderShared, contentDescription = stringResource(R.string.shared_move_to_shared), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
