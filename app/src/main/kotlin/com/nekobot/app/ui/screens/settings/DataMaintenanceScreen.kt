package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
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
                showToast("缓存已清除")
                refreshStorageInfo(context)
            } catch (e: Exception) {
                showError(e.message ?: "清除缓存失败")
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
                    // 先关闭数据库连接
                    NekobotDatabase.get(context).close()
                    // 删除所有数据库文件
                    context.databaseList().forEach { name ->
                        context.deleteDatabase(name)
                    }
                }
                showToast("所有本地数据已清除")
                refreshStorageInfo(context)
            } catch (e: Exception) {
                showError(e.message ?: "清除数据失败")
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
                    _exportResult.value = "已导出到缓存: ${result.name}"
                    showToast("已导出到缓存: ${result.name}")
                } else {
                    showError("未找到本地数据库文件")
                }
            } catch (e: Exception) {
                showError(e.message ?: "导出失败")
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
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val storageInfo by vm.storageInfo.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val exportResult by vm.exportResult.collectAsState()
    val context = LocalContext.current

    var showClearDataDialog by remember { mutableStateOf(false) }

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
                title = { Text("数据维护", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (appMode != AppMode.LOCAL) {
                        IconButton(onClick = { vm.refreshStorageInfo(context) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "此功能仅服务器模式可用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        SectionHeader(title = "存储使用情况", subtitle = "本地缓存与数据库占用")
                        Spacer(Modifier.height(12.dp))

                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = "缓存大小",
                            value = storageInfo.cacheSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = "数据库大小",
                            value = storageInfo.dbSizeText
                        )
                        Spacer(Modifier.height(8.dp))
                        StorageRow(
                            icon = Icons.Filled.Storage,
                            label = "总占用",
                            value = storageInfo.totalSizeText,
                            highlighted = true
                        )
                    }

                    // 2. 清除缓存
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "清除缓存", subtitle = "删除临时缓存文件")
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "清除应用缓存目录中的临时文件，包括图片缓存、下载临时文件等。不会影响本地数据库。",
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
                            Text("清除缓存", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 3. 清除所有本地数据
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "清除所有本地数据", subtitle = "删除本地数据库")
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "警告：此操作将删除所有本地数据库（包括会话、消息、角色等数据），且不可恢复。请谨慎操作。",
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
                            Text("清除所有本地数据", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 4. 导出本地数据
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "导出本地数据", subtitle = "备份本地数据库到文件")
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "将本地数据库文件导出到应用缓存目录，可用于备份或迁移。",
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
                            Text("导出本地数据库")
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
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 清除数据确认对话框
    if (showClearDataDialog) {
        NekoDialog(
            onDismiss = { showClearDataDialog = false },
            title = "确认清除所有本地数据",
            message = "此操作将永久删除所有本地数据库（会话、消息、角色等），且不可恢复。确定继续吗？",
            confirmText = "确认清除",
            onConfirm = {
                showClearDataDialog = false
                vm.clearAllData(context)
            },
            cancelText = "取消",
            onCancel = { showClearDataDialog = false }
        )
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
