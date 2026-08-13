package com.nekobot.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocalPlotStoryStore
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 格式化文件大小为人类可读字符串 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format("%.2f GB", gb)
        mb >= 1 -> String.format("%.2f MB", mb)
        kb >= 1 -> String.format("%.2f KB", kb)
        else -> "$bytes B"
    }
}

/** 存储使用情况数据 */
data class StorageInfo(
    val cacheSize: Long,
    val dbSize: Long,
    val totalSize: Long
) {
    val cacheSizeText: String get() = formatFileSize(cacheSize)
    val dbSizeText: String get() = formatFileSize(dbSize)
    val totalSizeText: String get() = formatFileSize(totalSize)
}

/**
 * 数据维护界面 ViewModel：管理本地缓存/数据库的清理与导出。
 */
class DataMaintenanceViewModel : BaseViewModel() {

    private val _storageInfo = MutableStateFlow(StorageInfo(0, 0, 0))
    val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

    /** 导出结果消息 */
    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    /** 刷新存储使用情况 */
    fun refreshStorageInfo(context: Context) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { computeStorageInfo(context) }
            _storageInfo.value = info
        }
    }

    /** 清除缓存目录 */
    fun clearCache(context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                withContext(Dispatchers.IO) {
                    context.cacheDir?.let { dir -> dir.deleteRecursively(); dir.mkdirs() }
                }
                showToast(string(R.string.maintenance_cache_cleared))
                refreshStorageInfo(context)
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.maintenance_clear_cache_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    /** 清除所有本地数据（数据库） */
    fun clearAllData(context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                withContext(Dispatchers.IO) {
                    // 先让仓库 owner 失活并关闭所有已知连接，避免后台生成把剧情数据复活。
                    ServiceContainer.localRepository.close()
                    val profileNames = (
                        ServiceContainer.prefs.listDbProfiles().map { it.name } +
                            ServiceContainer.prefs.activeDbName
                        ).distinct()
                    profileNames.forEach { NekobotDatabase.closeProfile(it) }
                    LocalPlotStoryStore.clearAllProfiles(
                        context,
                        profileNames
                    )
                    // 删除所有数据库文件
                    context.databaseList().forEach { name ->
                        context.deleteDatabase(name)
                    }
                    // 清空后立即重建默认空库，避免页面继续持有已关闭的 Repository。
                    ServiceContainer.switchLocalDb(PrefsManager.DEFAULT_DB_NAME)
                }
                showToast(string(R.string.maintenance_all_data_cleared))
                refreshStorageInfo(context)
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.maintenance_clear_data_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    /** 导出本地数据库到缓存目录 */
    fun exportLocalData(context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = withContext(Dispatchers.IO) {
                    val dbName = "nekobot_local.db"
                    val dbFile = context.getDatabasePath(dbName)
                    if (!dbFile.exists()) {
                        return@withContext null
                    }
                    // 关闭数据库以确保数据完整
                    NekobotDatabase.get(context).close()

                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                    val target = File(context.cacheDir, "nekobot-backup-$timestamp.db")

                    // 复制主数据库文件及 WAL/SHM 文件
                    copyFile(dbFile, target)

                    // 同时复制 -wal 和 -shm 文件（如果存在）
                    val walFile = File("${dbFile.absolutePath}-wal")
                    val shmFile = File("${dbFile.absolutePath}-shm")
                    if (walFile.exists()) {
                        copyFile(walFile, File("${target.absolutePath}-wal"))
                    }
                    if (shmFile.exists()) {
                        copyFile(shmFile, File("${target.absolutePath}-shm"))
                    }

                    target
                }
                if (result != null) {
                    _exportResult.value = string(R.string.maintenance_exported_to_cache, result.name)
                    showToast(string(R.string.maintenance_exported_to_cache, result.name))
                } else {
                    showError(string(R.string.maintenance_no_db_file))
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.maintenance_export_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    /** 计算存储使用情况 */
    private fun computeStorageInfo(context: Context): StorageInfo {
        val cacheSize = computeDirSize(context.cacheDir)
        val dbSize = computeDirSize(context.getDatabasePath("nekobot_local.db").parentFile)
        return StorageInfo(
            cacheSize = cacheSize,
            dbSize = dbSize,
            totalSize = cacheSize + dbSize
        )
    }

    /** 递归计算目录大小 */
    private fun computeDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { size += computeDirSize(it) }
        return size
    }

    /** 复制文件 */
    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataMaintenanceScreen(onBack: () -> Unit) {
    val vm: DataMaintenanceViewModel = viewModel()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val storageInfo by vm.storageInfo.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showCacheFiles by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshStorageInfo(context)
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.maintenance_title), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshStorageInfo(context) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.maintenance_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (appMode == AppMode.LOCAL) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    error?.let {
                        ErrorBanner(message = it, onRetry = { vm.clearError() })
                    }

                    // 1. 存储使用情况
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_storage_usage), subtitle = stringResource(R.string.maintenance_storage_subtitle))
                        Spacer(Modifier.height(12.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_cache_size),
                            value = storageInfo.cacheSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_db_size),
                            value = storageInfo.dbSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_total_size),
                            value = storageInfo.totalSizeText,
                            highlighted = true
                        )
                    }

                    // 2. 清除缓存
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_clear_cache_title), subtitle = stringResource(R.string.maintenance_clear_cache_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_clear_cache_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.clearCache(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_clear_cache_title), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 3. 清除所有本地数据
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_clear_all_title), subtitle = stringResource(R.string.maintenance_clear_all_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_clear_all_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showClearDataDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_clear_all_title), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 4. 缓存文件（支持递归浏览）
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_cache_files), subtitle = stringResource(R.string.maintenance_cache_files_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_cache_desc_local),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showCacheFiles = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_open_cache_folder))
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    error?.let {
                        ErrorBanner(message = it, onRetry = { vm.clearError() })
                    }

                    // 1. 存储使用情况
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_storage_usage), subtitle = stringResource(R.string.maintenance_storage_subtitle))
                        Spacer(Modifier.height(12.dp))

                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_cache_size),
                            value = storageInfo.cacheSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_db_size),
                            value = storageInfo.dbSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.maintenance_total_size),
                            value = storageInfo.totalSizeText,
                            highlighted = true
                        )
                    }

                    // 2. 清除缓存
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_clear_cache_title), subtitle = stringResource(R.string.maintenance_clear_cache_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_clear_cache_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.clearCache(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_clear_cache_title), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 3. 清除所有本地数据
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_clear_all_title), subtitle = stringResource(R.string.maintenance_clear_all_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_clear_all_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showClearDataDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_clear_all_title), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 4. 导出本地数据
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_export_title), subtitle = stringResource(R.string.maintenance_export_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_export_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.exportLocalData(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_export_button))
                        }

                        exportResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 5. 缓存文件
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.maintenance_cache_files), subtitle = stringResource(R.string.maintenance_cache_files_subtitle))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.maintenance_cache_files_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showCacheFiles = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.maintenance_open_cache_folder))
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 清除数据确认对话框
    if (showClearDataDialog) {
        NekoDialog(
            onDismiss = { showClearDataDialog = false },
            title = stringResource(R.string.maintenance_confirm_clear_title),
            message = stringResource(R.string.maintenance_confirm_clear_msg),
            confirmText = stringResource(R.string.maintenance_confirm_clear),
            onConfirm = {
                showClearDataDialog = false
                vm.clearAllData(context)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { showClearDataDialog = false }
        )
    }

    // 缓存文件列表对话框
    if (showCacheFiles) {
        CacheFilesDialog(context = context, onDismiss = { showCacheFiles = false })
    }
}

/**
 * 缓存文件列表对话框：支持递归浏览 cacheDir 下的文件和子目录，
 * 点击文件夹进入下一级，点击文件用 FileProvider 打开。
 */
@Composable
private fun CacheFilesDialog(context: android.content.Context, onDismiss: () -> Unit) {
    // 根目录 = cacheDir，禁止向上越界
    val rootDir = remember { context.cacheDir }
    // 当前所在目录，初始为根目录
    var currentDir by remember { mutableStateOf(rootDir) }
    // 当前目录下的条目（目录在前，文件在后，均按名称排序）
    val entries = remember(currentDir) {
        currentDir.listFiles()?.toList()?.sortedWith(
            compareBy<java.io.File> { !it.isDirectory }  // 目录在前
                .thenBy { it.name.lowercase() }            // 名称升序（不区分大小写）
        ) ?: emptyList()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 顶部：标题 + 返回上级按钮（当不在根目录时显示）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentDir != rootDir) {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.maintenance_cache_files_count, entries.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        // 显示当前相对路径（cacheDir 为根）
                        val relPath = remember(currentDir) {
                            val rootAbs = rootDir.absolutePath.trimEnd('/')
                            val curAbs = currentDir.absolutePath.trimEnd('/')
                            if (curAbs.length <= rootAbs.length) "/"
                            else curAbs.substring(rootAbs.length)
                        }
                        Text(
                            text = relPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.maintenance_cache_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries, key = { it.absolutePath }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (file.isDirectory) {
                                            // 点击文件夹：进入下一级
                                            currentDir = file
                                        } else {
                                            // 点击文件：用 FileProvider 打开
                                            try {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/octet-stream")
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, context.getString(R.string.maintenance_cannot_open_file, e.message), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (file.isDirectory)
                                            stringResource(R.string.maintenance_folder)
                                        else formatFileSize(file.length()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (file.isDirectory) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

/** 存储信息行：图标 + 标签 + 值 */
@Composable
private fun StorageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    highlighted: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium
        )
    }
}
