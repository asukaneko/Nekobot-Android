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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.SANDBOX_SHARED_MOUNT
import com.nekobot.app.data.local.ai.SANDBOX_WORKSPACE_MOUNT
import com.nekobot.app.data.local.ai.terminal.LocalTerminalSession
import com.nekobot.app.data.local.ai.terminal.TerminalEmulator
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassDropdownMenu
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoPrimaryAction
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.NekoSheetNote
import com.nekobot.app.ui.components.nekoRowTitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 工作区文件项 */
@Immutable
data class WorkspaceFile(
    val name: String,
    val type: String,        // "file" | "directory"
    val size: Long,
    val path: String,
    val mimeType: String = "",
    val modifiedAt: Long = 0L   // 最近修改时间（epoch 毫秒，0 = 未知，如服务端未返回）
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

    // ===== 沙箱终端（与聊天页共用同一个会话 shell）=====

    /** 终端入口是否可用：本地模式 + Agent 会话才有沙箱。 */
    private val _terminalAvailable = MutableStateFlow(false)
    internal val terminalAvailable: StateFlow<Boolean> = _terminalAvailable.asStateFlow()

    private val _terminalState = MutableStateFlow(LocalTerminalSession.State.IDLE)
    internal val terminalState: StateFlow<LocalTerminalSession.State> = _terminalState.asStateFlow()

    /** 终端的原始 PTY 输出，界面层收集后喂给终端仿真器。 */
    private val _terminalOutput = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val terminalOutput: SharedFlow<ByteArray> = _terminalOutput.asSharedFlow()

    private var terminal: LocalTerminalSession? = null
    private var terminalOutputJob: Job? = null
    private var terminalStateJob: Job? = null

    fun init(id: String) {
        if (id == sessionId && _files.value.isNotEmpty()) return
        sessionId = id
        load("")
        refreshTerminalAvailability()
    }

    private fun refreshTerminalAvailability() {
        if (!isLocalMode) return
        viewModelScope.launch {
            _terminalAvailable.value = when (val res = unified.getSession(sessionId)) {
                is Resource.Success -> res.data?.sessionMode.equals("agent", ignoreCase = true)
                else -> false
            }
        }
    }

    /**
     * 打开会话沙箱终端并定位到 [directory]（沙箱内绝对路径）。
     *
     * 终端尚未启动时通过 PRoot 的 -w 直接落在目标目录；
     * shell 已在运行则注入一条 cd 命令。与聊天页共用同一个 shell。
     */
    fun openTerminal(directory: String, cols: Int, rows: Int) {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            val session = terminal?.takeIf { it.sessionId == sessionId }
                ?: unified.sandboxTerminal(sessionId)
            if (session == null) {
                _terminalState.value = LocalTerminalSession.State.UNAVAILABLE
                return@launch
            }
            if (terminal !== session) {
                terminal = session
                terminalOutputJob?.cancel()
                terminalOutputJob = viewModelScope.launch {
                    session.output.collect { chunk -> _terminalOutput.emit(chunk) }
                }
                terminalStateJob?.cancel()
                terminalStateJob = viewModelScope.launch {
                    session.state.collect { state -> _terminalState.value = state }
                }
            }
            if (session.isRunning) {
                session.setWindowSize(cols, rows)
                // 先清掉半行输入再 cd，避免和用户没敲完的命令拼在一起
                session.sendBytes(byteArrayOf(TERMINAL_CTRL_U))
                session.sendText("cd ${shellQuote(directory)}\r")
            } else {
                session.start(cols, rows, workingDir = directory)
            }
        }
    }

    /** 转发一段原始字节（按键、控制码、粘贴文本）。 */
    fun sendTerminalBytes(bytes: ByteArray) {
        terminal?.takeIf { it.sessionId == sessionId }?.sendBytes(bytes)
    }

    /** 终端可见尺寸变化：同步给 PTY。 */
    fun resizeTerminal(cols: Int, rows: Int) {
        terminal?.takeIf { it.sessionId == sessionId }?.setWindowSize(cols, rows)
    }

    /** 重启终端：结束当前 shell 后按上次的目标目录重新拉起。 */
    fun restartTerminal() {
        terminal?.takeIf { it.sessionId == sessionId }?.let { session ->
            session.stop()
            session.start()
        }
    }

    /** 单引号包裹，处理路径里的空格与特殊字符。 */
    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

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

    // ---- 移动到…（选择目标文件夹后批量移动，与系统文件管理器一致） ----

    /** 目标文件夹选择器状态：当前目录 + 其下子文件夹。 */
    @Immutable
    data class MovePickerState(
        val path: String = "",
        val folders: List<WorkspaceFile> = emptyList(),
        val loading: Boolean = false
    )

    private val _movePicker = MutableStateFlow(MovePickerState())
    val movePicker: StateFlow<MovePickerState> = _movePicker.asStateFlow()

    /** 读取 [path] 下的子文件夹，供「移动到…」选择目标目录。 */
    fun loadMoveFolders(path: String, isShared: Boolean) {
        val normalized = normalizeWorkspaceBrowserPath(path)
        if (!isShared && sessionId.isBlank()) return
        _movePicker.value = MovePickerState(path = normalized, loading = true)
        viewModelScope.launch {
            val folders = when (
                val res = if (isShared) {
                    unified.listSharedFiles(normalized.ifBlank { null })
                } else {
                    unified.listWorkspaceFiles(sessionId, normalized.ifBlank { null })
                }
            ) {
                is Resource.Success -> parseFiles(res.data).filter { it.isDirectory }
                is Resource.Error -> emptyList()
                is Resource.Loading -> emptyList()
            }
            if (_movePicker.value.path == normalized) {
                _movePicker.value = MovePickerState(normalized, folders, loading = false)
            }
        }
    }

    /** 当前工作区内批量移动：逐项移动并汇总成功/失败数量。 */
    fun moveWithin(sources: Collection<String>, targetPath: String, isShared: Boolean) {
        if (!isShared && sessionId.isBlank()) return
        val target = normalizeWorkspaceBrowserPath(targetPath)
        // 已在目标目录内的项跳过（与文件管理器一致），避免自我覆盖
        val paths = sources.filter {
            it.isNotBlank() &&
                normalizeWorkspaceBrowserPath(it) != target &&
                parentWorkspaceBrowserPath(it) != target
        }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            val counts = try {
                runBatch(paths) { path ->
                    if (isShared) unified.moveSharedFile(path, target)
                    else unified.moveWorkspaceFile(sessionId, path, target)
                }
            } finally {
                setLoading(false)
            }
            if (isShared) loadShared() else load()
            showBatchToast(counts, R.string.workspace_move_done, R.string.workspace_move_partial)
        }
    }

    /** 批量移到共享工作区。 */
    fun moveSelectedToShared(sources: Collection<String>) {
        if (sessionId.isBlank()) return
        val paths = sources.filter { it.isNotBlank() }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            val counts = try {
                runBatch(paths) { path -> unified.moveToShared(sessionId, path) }
            } finally {
                setLoading(false)
            }
            load()
            loadShared()
            showBatchToast(counts, R.string.workspace_move_done, R.string.workspace_move_partial)
        }
    }

    /** 批量把共享文件移回当前会话。 */
    fun moveSelectedSharedToPrivate(sources: Collection<String>) {
        if (sessionId.isBlank()) return
        val paths = sources.filter { it.isNotBlank() }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            val counts = try {
                runBatch(paths) { path -> unified.moveSharedToPrivate(path, sessionId) }
            } finally {
                setLoading(false)
            }
            loadShared()
            load()
            showBatchToast(counts, R.string.workspace_move_done, R.string.workspace_move_partial)
        }
    }

    /** 批量删除所选文件/文件夹。 */
    fun deleteFiles(files: Collection<WorkspaceFile>, isShared: Boolean) {
        if (!isShared && sessionId.isBlank()) return
        val paths = files.map { it.path }.filter { it.isNotBlank() }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            val counts = try {
                runBatch(paths) { path ->
                    if (isShared) unified.deleteSharedFile(path)
                    else unified.deleteWorkspaceFile(sessionId, path)
                }
            } finally {
                setLoading(false)
            }
            if (isShared) loadShared() else load()
            showBatchToast(counts, R.string.workspace_delete_done, R.string.workspace_delete_partial)
        }
    }

    /** 在当前目录下新建文件夹（只取单层名称）。 */
    fun createFolder(folderName: String, isShared: Boolean) {
        if (!isShared && sessionId.isBlank()) return
        val name = folderName.trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\') ||
            name == "." || name == ".."
        ) {
            showError(string(R.string.workspace_folder_name_invalid))
            return
        }
        val base = if (isShared) _sharedPath.value else _currentPath.value
        val path = if (base.isBlank()) name else "$base/$name"
        viewModelScope.launch {
            setLoading(true)
            val result = try {
                if (isShared) unified.createSharedFolder(path)
                else unified.createWorkspaceFolder(sessionId, path)
            } finally {
                setLoading(false)
            }
            if (result.isOperationOk()) {
                showToast(string(R.string.shared_folder_created))
                if (isShared) loadShared() else load()
            } else {
                val message = (result as? Resource.Error)?.message
                showError(message?.takeIf { it.isNotBlank() }
                    ?: string(R.string.workspace_create_folder_failed))
            }
        }
    }

    /** 逐项执行，返回 (成功, 失败)；单次失败不影响其余项。 */
    private suspend fun runBatch(
        paths: List<String>,
        operation: suspend (String) -> Resource<JsonElement>
    ): Pair<Int, Int> {
        var done = 0
        var failed = 0
        for (path in paths) {
            if (operation(path).isOperationOk()) done++ else failed++
        }
        return done to failed
    }

    /** 本地实现的失败放在 JSON 的 success 里，这里统一判定操作结果。 */
    private fun Resource<JsonElement>.isOperationOk(): Boolean = when (this) {
        is Resource.Success -> {
            val obj = data.takeIf { it.isJsonObject }?.asJsonObject
            when {
                obj == null -> true
                obj.has("success") -> runCatching { obj.get("success").asBoolean }.getOrDefault(true)
                obj.has("error") -> false
                else -> true
            }
        }
        is Resource.Error -> false
        is Resource.Loading -> false
    }

    private fun showBatchToast(counts: Pair<Int, Int>, doneRes: Int, partialRes: Int) {
        val (done, failed) = counts
        if (done == 0 && failed == 0) return
        showToast(
            if (failed == 0) string(doneRes, done)
            else string(partialRes, done, failed)
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
        mimeType = get("mime_type")?.asString ?: "",
        modifiedAt = runCatching { get("modified_at")?.asLong }.getOrNull() ?: 0L
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
            "md", "markdown" -> "text/markdown"
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
    val terminalAvailable by viewModel.terminalAvailable.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tabIndex by remember { mutableStateOf(0) } // 0=会话工作区, 1=共享工作区
    // 多选：长按进入、点按切换，与系统文件管理器一致；切换目录/Tab 自动退出
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<WorkspaceDeleteRequest?>(null) }
    var moveRequest by remember { mutableStateOf<WorkspaceMoveRequest?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<java.io.File?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var previewFileMime by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    // Android 9 及以下：系统「创建文档」选择器一次只能存一个文件，用下标驱动队列
    var legacyDownloads by remember { mutableStateOf<List<Pair<WorkspaceFile, Boolean>>>(emptyList()) }
    var legacyIndex by remember { mutableIntStateOf(-1) }
    var legacySaved by remember { mutableIntStateOf(0) }
    var showTerminal by remember { mutableStateOf(false) }
    var terminalDir by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }

    val isSharedTab = tabIndex == 1
    val activePath = if (isSharedTab) sharedPath else currentPath
    val displayFiles = if (isSharedTab) sharedFiles else files
    val selectedFiles = remember(displayFiles, selectedPaths) {
        displayFiles.filter { it.path in selectedPaths }
    }
    val movePicker by viewModel.movePicker.collectAsStateWithLifecycle()

    // 新建文件夹：只允许单层名称，避免输入 / 或 .. 跳出当前目录
    val trimmedFolderName = folderName.trim()
    val folderNameValid = trimmedFolderName.isNotEmpty() &&
        trimmedFolderName != "." &&
        trimmedFolderName != ".." &&
        !trimmedFolderName.contains('/') &&
        !trimmedFolderName.contains('\\')

    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedPaths = emptySet()
    }
    val toggleSelection: (WorkspaceFile) -> Unit = { file ->
        val next = if (file.path in selectedPaths) selectedPaths - file.path else selectedPaths + file.path
        selectedPaths = next
        selectionMode = next.isNotEmpty()
    }

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
    // 目录或来源切换后旧选中项已不在列表里，直接退出多选
    LaunchedEffect(currentPath, sharedPath, tabIndex) { exitSelection() }
    // 打开「移动到…」时从当前目录开始浏览
    LaunchedEffect(moveRequest) {
        val request = moveRequest ?: return@LaunchedEffect
        viewModel.loadMoveFolders(
            if (request.isShared) viewModel.sharedPath.value else viewModel.currentPath.value,
            request.isShared
        )
    }

    // 终端仿真器按会话持有：关掉浮层再打开时输出与回滚历史仍在
    val terminalEmulator = remember(sessionId) { TerminalEmulator() }
    LaunchedEffect(terminalEmulator) {
        viewModel.terminalOutput.collect { bytes -> terminalEmulator.feed(bytes) }
    }
    BackHandler(enabled = selectionMode || activePath.isNotBlank()) {
        when {
            selectionMode -> exitSelection()
            tabIndex == 0 -> viewModel.navigateUp()
            else -> viewModel.navigateSharedUp()
        }
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
        val index = legacyIndex
        val item = legacyDownloads.getOrNull(index)
        when {
            item == null -> {
                legacyDownloads = emptyList()
                legacyIndex = -1
                downloading = null
            }
            // 在系统选择器里取消：结束整条下载队列
            uri == null -> {
                legacyDownloads = emptyList()
                legacyIndex = -1
                downloading = null
            }
            else -> scope.launch {
                val (file, isShared) = item
                val saved = if (isShared) viewModel.saveSharedToUri(context, file, uri)
                            else viewModel.saveToUri(context, file, uri)
                if (saved) legacySaved += 1
                legacyIndex = index + 1
            }
        }
    }
    // 队列推进：上一份写完后弹下一个文件的保存位置
    LaunchedEffect(legacyIndex, legacyDownloads) {
        val queue = legacyDownloads
        val item = queue.getOrNull(legacyIndex)
        if (item != null) {
            downloading = item.first.path
            createDownloadDocument.launch(item.first.name)
        } else if (legacyIndex >= 0 && queue.isNotEmpty()) {
            val saved = legacySaved
            val total = queue.size
            legacyDownloads = emptyList()
            legacyIndex = -1
            legacySaved = 0
            downloading = null
            snackbarHost.showSnackbar(
                when {
                    saved == total && total == 1 ->
                        context.getString(R.string.workspace_downloaded_to, queue.first().first.name)
                    saved == total -> context.getString(R.string.workspace_download_done, saved)
                    else -> context.getString(R.string.workspace_download_partial, saved, total - saved)
                }
            )
        }
    }

    // 批量下载：Android 10+ 直接写公共 Download，旧系统逐个走系统选择器
    val startDownload: (List<WorkspaceFile>, Boolean) -> Unit = { targets, isShared ->
        val downloadable = targets.filter { !it.isDirectory }
        when {
            downloadable.isEmpty() -> {
                scope.launch {
                    snackbarHost.showSnackbar(context.getString(R.string.workspace_download_no_target))
                }
            }
            downloading != null -> Unit
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                legacyDownloads = downloadable.map { it to isShared }
                legacySaved = 0
                legacyIndex = 0
            }
            else -> scope.launch {
                var savedCount = 0
                var failedCount = 0
                for (file in downloadable) {
                    downloading = file.path
                    val savedName = if (isShared) viewModel.saveSharedToDownloads(context, file)
                                    else viewModel.saveToDownloads(context, file)
                    if (savedName != null) savedCount++ else failedCount++
                }
                downloading = null
                snackbarHost.showSnackbar(
                    when {
                        failedCount == 0 && savedCount == 1 ->
                            context.getString(R.string.workspace_downloaded_to, downloadable.first().name)
                        failedCount == 0 -> context.getString(R.string.workspace_download_done, savedCount)
                        else -> context.getString(R.string.workspace_download_partial, savedCount, failedCount)
                    }
                )
            }
        }
    }

    val openPreview: (WorkspaceFile, Boolean) -> Unit = { file, isShared ->
        if (!previewLoading && previewFile == null) {
            previewFileName = file.path
            previewFileMime = file.mimeType
            previewLoading = true
            scope.launch {
                val saved = if (isShared) viewModel.prepareSharedForOpen(context, file.path)
                            else viewModel.prepareForOpen(context, file.path)
                previewLoading = false
                if (saved != null) {
                    when {
                        // Markdown / 纯文本（txt）等走内置预览弹窗，不交给外部应用
                        isBuiltInPreviewable(file.name, file.mimeType) -> {
                            previewFile = saved
                        }
                        isPdfWorkspaceFile(file.name, file.mimeType) -> {
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

    // 点按：多选时切换选中；否则文件夹进入、文件预览
    val openFile: (WorkspaceFile) -> Unit = { file ->
        when {
            selectionMode -> toggleSelection(file)
            file.isDirectory -> {
                if (isSharedTab) viewModel.openSharedDirectory(file.path)
                else viewModel.openDirectory(file.path)
            }
            else -> openPreview(file, isSharedTab)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                val allSelected = displayFiles.isNotEmpty() && selectedPaths.size >= displayFiles.size
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.workspace_selected_count, selectedPaths.size),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = exitSelection) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                if (allSelected) exitSelection()
                                else selectedPaths = displayFiles.map { it.path }.toSet()
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.common_deselect_all
                                    else R.string.common_select_all
                                )
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            } else {
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
                        IconButton(
                            onClick = {
                                folderName = ""
                                creatingFolder = true
                            }
                        ) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = stringResource(R.string.shared_create_folder),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { pickFile.launch("*/*") }, enabled = !uploading) {
                            Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.workspace_upload_file), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                WorkspaceSelectionBar(
                    actions = listOf(
                        WorkspaceBarAction(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            label = stringResource(R.string.workspace_move_to_folder),
                            enabled = selectedFiles.isNotEmpty(),
                            onClick = { moveRequest = WorkspaceMoveRequest(selectedFiles, isSharedTab) }
                        ),
                        WorkspaceBarAction(
                            icon = if (isSharedTab) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.FolderShared,
                            label = stringResource(
                                if (isSharedTab) R.string.shared_move_to_private
                                else R.string.shared_move_to_shared
                            ),
                            enabled = selectedFiles.isNotEmpty(),
                            onClick = {
                                val paths = selectedFiles.map { it.path }
                                exitSelection()
                                if (isSharedTab) viewModel.moveSelectedSharedToPrivate(paths)
                                else viewModel.moveSelectedToShared(paths)
                            }
                        ),
                        WorkspaceBarAction(
                            icon = Icons.Filled.Download,
                            label = stringResource(R.string.workspace_download),
                            enabled = selectedFiles.any { !it.isDirectory },
                            onClick = { startDownload(selectedFiles, isSharedTab) }
                        ),
                        WorkspaceBarAction(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.common_delete),
                            enabled = selectedFiles.isNotEmpty(),
                            destructive = true,
                            onClick = { pendingDelete = WorkspaceDeleteRequest(selectedFiles, isSharedTab) }
                        )
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab 切换栏：切换工作区时退出多选，避免误操作另一个工作区
            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = {
                        exitSelection()
                        tabIndex = 0
                    },
                    text = { Text(stringResource(R.string.shared_workspace_tab_session)) }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = {
                        exitSelection()
                        tabIndex = 1
                        viewModel.loadShared()
                    },
                    text = { Text(stringResource(R.string.shared_workspace_tab_shared)) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    displayFiles.isEmpty() && loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    displayFiles.isEmpty() -> {
                        EmptyState(
                            title = if (isSharedTab) stringResource(R.string.shared_workspace_empty)
                                    else stringResource(R.string.workspace_empty_title),
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
                                WorkspaceFileItem(
                                    file = f,
                                    isSharedMode = isSharedTab,
                                    downloading = downloading == f.path,
                                    previewLoading = previewLoading && previewFileName == f.path,
                                    terminalAvailable = terminalAvailable,
                                    selectionMode = selectionMode,
                                    selected = f.path in selectedPaths,
                                    onClick = { openFile(f) },
                                    onLongClick = {
                                        if (selectionMode) {
                                            toggleSelection(f)
                                        } else {
                                            selectionMode = true
                                            selectedPaths = setOf(f.path)
                                        }
                                    },
                                    onDelete = {
                                        pendingDelete = WorkspaceDeleteRequest(listOf(f), isSharedTab)
                                    },
                                    onMove = {
                                        moveRequest = WorkspaceMoveRequest(listOf(f), isSharedTab)
                                    },
                                    onMoveAcross = {
                                        if (isSharedTab) viewModel.moveSelectedSharedToPrivate(listOf(f.path))
                                        else viewModel.moveSelectedToShared(listOf(f.path))
                                    },
                                    onOpenTerminal = {
                                        // 浏览器里的相对路径映射到沙箱内挂载点
                                        val mount = if (isSharedTab) SANDBOX_SHARED_MOUNT else SANDBOX_WORKSPACE_MOUNT
                                        terminalDir = "$mount/${f.path.trim('/')}"
                                        showTerminal = true
                                    },
                                    onDownload = { startDownload(listOf(f), isSharedTab) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认：单项显示文件名，多选显示数量
    pendingDelete?.let { request ->
        NekoDialog(
            onDismiss = { pendingDelete = null },
            title = stringResource(R.string.workspace_delete_file_title),
            message = if (request.files.size == 1) {
                stringResource(R.string.workspace_delete_confirm, request.files.first().name)
            } else {
                stringResource(R.string.workspace_delete_selected_confirm, request.files.size)
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                pendingDelete = null
                exitSelection()
                viewModel.deleteFiles(request.files, request.isShared)
            }
        )
    }

    // 移动到…：先选目标文件夹，再统一移动
    moveRequest?.let { request ->
        WorkspaceMovePickerSheet(
            request = request,
            picker = movePicker,
            onNavigate = { path -> viewModel.loadMoveFolders(path, request.isShared) },
            onUp = { viewModel.loadMoveFolders(parentWorkspaceBrowserPath(movePicker.path), request.isShared) },
            onConfirm = {
                val target = movePicker.path
                moveRequest = null
                exitSelection()
                viewModel.moveWithin(request.files.map { it.path }, target, request.isShared)
            },
            onDismiss = { moveRequest = null }
        )
    }

    // 新建文件夹：在当前目录下创建，名称非法时禁用「创建」
    if (creatingFolder) {
        NekoDialog(
            onDismiss = { creatingFolder = false },
            title = stringResource(R.string.shared_create_folder),
            confirmText = stringResource(R.string.common_create),
            confirmEnabled = folderNameValid,
            onConfirm = {
                val name = trimmedFolderName
                creatingFolder = false
                folderName = ""
                viewModel.createFolder(name, isSharedTab)
            },
            onCancel = { creatingFolder = false }
        ) {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it.take(64) },
                label = { Text(stringResource(R.string.shared_folder_name)) },
                singleLine = true,
                isError = folderName.isNotEmpty() && !folderNameValid,
                supportingText = {
                    if (folderName.isNotEmpty() && !folderNameValid) {
                        Text(stringResource(R.string.workspace_folder_name_invalid))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 文件预览 Dialog：支持图片/文本/HTML/PDF
    val pf = previewFile
    if (pf != null) {
        FilePreviewDialog(
            fileName = File(previewFileName).name,
            file = pf,
            mimeType = previewFileMime,
            onDismiss = {
                previewFile = null
                previewFileName = ""
                previewFileMime = ""
            }
        )
    }

    // 沙箱终端浮层：与聊天页共用同一个 shell，进入时 cd 到来源文件夹
    if (showTerminal) {
        LaunchedEffect(Unit) {
            viewModel.openTerminal(terminalDir, TerminalEmulator.DEFAULT_COLS, TerminalEmulator.DEFAULT_ROWS)
        }
        SandboxTerminalOverlay(
            emulator = terminalEmulator,
            state = terminalState,
            onRestart = { viewModel.restartTerminal() },
            onSendBytes = { bytes -> viewModel.sendTerminalBytes(bytes) },
            onResize = { cols, rows -> viewModel.resizeTerminal(cols, rows) },
            onDismiss = { showTerminal = false },
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
    terminalAvailable: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onMoveAcross: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        borderWidth = if (selected) 1 else 0,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !previewLoading,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            // 多选时左侧给勾选框，点按整行即可切换
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
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
                val secondary = if (file.isDirectory) stringResource(R.string.workspace_folder) else formatSize(file.size)
                Text(
                    if (file.modifiedAt > 0L) "$secondary · ${formatModifiedTime(file.modifiedAt)}" else secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 多选时不显示「⋯」，避免与批量操作冲突
            if (!selectionMode) {
                // 右侧操作收入「⋯」菜单，避免一排图标挤占文件名空间
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = !downloading) {
                        if (downloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.workspace_file_actions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    GlassDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (file.isDirectory && terminalAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_open_terminal)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Terminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onOpenTerminal()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_move_to_folder)) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (isSharedMode) R.string.shared_move_to_private else R.string.shared_move_to_shared)) },
                            leadingIcon = {
                                Icon(
                                    if (isSharedMode) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.FolderShared,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMoveAcross()
                            }
                        )
                        if (!file.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_download)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 批量操作请求：记录来源工作区，切 Tab 后也不会作用到另一个工作区。 */
private data class WorkspaceDeleteRequest(val files: List<WorkspaceFile>, val isShared: Boolean)

private data class WorkspaceMoveRequest(val files: List<WorkspaceFile>, val isShared: Boolean)

/** 多选底栏的一项操作。 */
private data class WorkspaceBarAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** 多选底栏：移动到…、跨工作区、下载、删除。 */
@Composable
private fun WorkspaceSelectionBar(actions: List<WorkspaceBarAction>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                val tint = when {
                    !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    action.destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 「移动到…」目标文件夹选择：从当前目录开始逐层浏览，避免把文件拖进看不见的目录。
 *
 * 选中项自身或其子目录不可作为目标，已在该目录时禁用确认。
 */
@Composable
private fun WorkspaceMovePickerSheet(
    request: WorkspaceMoveRequest,
    picker: WorkspaceViewModel.MovePickerState,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetPath = picker.path
    val blocked = request.files.any {
        it.isDirectory && (targetPath == it.path || targetPath.startsWith("${it.path}/"))
    }
    val alreadyHere = request.files.isNotEmpty() && request.files.all {
        parentWorkspaceBrowserPath(it.path) == targetPath
    }
    val hint = when {
        blocked -> stringResource(R.string.workspace_move_here_blocked)
        alreadyHere -> stringResource(R.string.workspace_move_here_already)
        else -> null
    }
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = stringResource(R.string.workspace_move_picker_title),
            description = stringResource(R.string.workspace_move_picker_desc),
            onClose = onDismiss
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (targetPath.isBlank()) stringResource(R.string.workspace_root) else "/$targetPath",
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUp, enabled = targetPath.isNotBlank()) {
                Text(stringResource(R.string.workspace_up_level))
            }
        }
        NekoDivider()
        when {
            picker.loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            picker.folders.isEmpty() -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.workspace_move_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(picker.folders, key = { it.path }) { folder ->
                    NekoChoiceRow(
                        title = folder.name,
                        showChevron = false,
                        leading = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = { onNavigate(folder.path) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NekoPrimaryAction(
            text = stringResource(R.string.workspace_move_here),
            leadingIcon = Icons.AutoMirrored.Filled.DriveFileMove,
            trailingChevron = false,
            enabled = !picker.loading && !blocked && !alreadyHere,
            onClick = onConfirm
        )
        hint?.let { NekoSheetNote(it) }
    }
}


private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/** 最近修改时间：SimpleDateFormat 默认走设备时区与地区。 */
private fun formatModifiedTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Ctrl+U：注入 cd 前先清掉 shell 里半行输入。 */
private const val TERMINAL_CTRL_U = 0x15.toByte()
