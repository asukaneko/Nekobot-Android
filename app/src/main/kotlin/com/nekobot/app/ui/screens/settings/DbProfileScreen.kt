package com.nekobot.app.ui.screens.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LoginRecord
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地 DB Profile 管理 ViewModel：维护 profile 列表、激活态、远程导入与切换。
 *
 * - profiles：本地已记录的 db profile 列表（来自 PrefsManager.listDbProfiles）
 * - activeName：当前激活的 db 名
 * - loginRecords：已保存的远程服务器登录记录，供导入下拉选择
 */
class DbProfileViewModel : ViewModel() {

    private val prefs: PrefsManager get() = ServiceContainer.prefs

    private val _profiles = MutableStateFlow<List<PrefsManager.DbProfile>>(emptyList())
    val profiles: StateFlow<List<PrefsManager.DbProfile>> = _profiles.asStateFlow()

    private val _activeName = MutableStateFlow(PrefsManager.DEFAULT_DB_NAME)
    val activeName: StateFlow<String> = _activeName.asStateFlow()

    private val _loginRecords = MutableStateFlow<List<LoginRecord>>(emptyList())
    val loginRecords: StateFlow<List<LoginRecord>> = _loginRecords.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { reload() }

    fun reload() {
        _profiles.value = prefs.listDbProfiles()
        _activeName.value = prefs.activeDbName
        _loginRecords.value = prefs.listLoginRecords()
    }

    fun clearToast() { _toast.value = null }

    /** 切换激活 db profile。 */
    fun switchTo(profileName: String) {
        if (profileName == _activeName.value) return
        prefs.activeDbName = profileName
        ServiceContainer.switchLocalDb(profileName)
        _activeName.value = profileName
        _toast.value = ServiceContainer.localizedContext?.getString(R.string.dbprofile_switched_to, displayName(profileName)) ?: ""
    }

    /** 删除指定 profile（默认 db 不可删除）。 */
    fun delete(profileName: String) {
        if (profileName == PrefsManager.DEFAULT_DB_NAME) {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_default_no_delete)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = ServiceContainer.appContext
            if (ctx != null) {
                NekobotDatabase.deleteProfileFile(ctx, profileName)
                clearProfileSidecarData(ctx, profileName)
                // 清理导入的立绘目录
                runCatching {
                    java.io.File(ctx.cacheDir, "portraits/$profileName").deleteRecursively()
                }
            }
            prefs.removeDbProfile(profileName)
            withContext(Dispatchers.Main) {
                if (_activeName.value == profileName) {
                    _activeName.value = PrefsManager.DEFAULT_DB_NAME
                    ServiceContainer.switchLocalDb(PrefsManager.DEFAULT_DB_NAME)
                }
                reload()
                _toast.value = ServiceContainer.localizedContext?.getString(R.string.dbprofile_deleted, profileName) ?: ""
            }
        }
    }

    /**
     * 从远程服务器下载 nbotcfg 并导入为新 db profile。
     *
     * 密码由本地自动随机生成，传给服务端加密配置包，本地用同一密码解密。
     * 用户无需输入密码，整个过程对用户透明。
     */
    fun importFromRemote(
        record: LoginRecord,
        displayName: String
    ) {
        if (displayName.isBlank()) {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_input_display_name)
            return
        }
        val profileName = sanitizeProfileName(displayName)
        // 随机密码：仅用于本次 HTTP 传输的加解密，用户无感
        val password = generateRandomPassword()
        _importing.value = true
        viewModelScope.launch {
            val result = ServiceContainer.unified.importNbotConfigFromRemote(
                url = record.serverUrl,
                token = record.token,
                password = password,
                profileName = profileName,
                displayName = displayName
            )
            _importing.value = false
            if (result.success) {
                prefs.saveDbProfile(
                    PrefsManager.DbProfile(
                        name = profileName,
                        displayName = displayName,
                        source = "imported",
                        createdAt = System.currentTimeMillis()
                    )
                )
                reload()
                // 自动切换到新导入的 db
                prefs.activeDbName = profileName
                ServiceContainer.switchLocalDb(profileName)
                _activeName.value = profileName
                _toast.value = result.message
            } else {
                _toast.value = result.message
            }
        }
    }

    /** 生成 16 字节随机密码（Base64 编码），用于本次导出/导入的加解密。 */
    private fun generateRandomPassword(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * 导出指定 profile 的数据库到下载目录。
     *
     * 流程：
     * 1. 关闭目标 profile 的 db 连接（强制 WAL 刷盘）
     * 2. 收集 .db / .db-wal / .db-shm 文件
     * 3. 打包成 ZIP 写入 Downloads（Android 10+ 走 MediaStore，9- 直接写公共目录）
     * 4. 若导出的是当前激活 db，重开连接以恢复正常使用
     */
    fun exportToDownloads(profileName: String) {
        val profile = _profiles.value.firstOrNull { it.name == profileName } ?: return
        val displayNameStr = profile.displayName
        val ctx = ServiceContainer.appContext ?: run {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_export_failed)
            return
        }
        val isActive = profileName == _activeName.value
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetDb = NekobotDatabase.get(ctx, profileName)
                targetDb.aiModelDao().migrateStoredSecrets()
                targetDb.mcpServerDao().migrateStoredSecrets()
                targetDb.apiKeyDao().migrateStoredSecrets()
                // 关闭目标 profile 的 db 连接，确保 WAL 刷盘
                NekobotDatabase.closeProfile(profileName)
                Thread.sleep(100)
                val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
                val dbFile = ctx.getDatabasePath(dbName)
                if (!dbFile.exists()) {
                    withContext(Dispatchers.Main) {
                        _importing.value = false
                        _toast.value = ServiceContainer.getString(R.string.dbprofile_export_no_db)
                    }
                    return@launch
                }
                // 收集 db + -wal + -shm
                val entries = mutableListOf<Pair<String, File>>()
                entries.add(dbFile.name to dbFile)
                listOf("$dbName-wal", "$dbName-shm").forEach { suffix ->
                    val f = ctx.getDatabasePath(suffix)
                    if (f.exists()) entries.add(f.name to f)
                }
                // 打包成 ZIP
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val safeName = displayNameStr.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                    .ifBlank { profileName }
                val zipFileName = "nekobot_${safeName}_$timestamp.zip"
                val baos = ByteArrayOutputStream()
                ZipOutputStream(baos).use { zos ->
                    entries.forEach { (entryName, file) ->
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val zipBytes = baos.toByteArray()
                val saved = writeBytesToDownloads(ctx, zipFileName, "application/zip", zipBytes)
                // 恢复 db 连接：激活 db 需要走 switchLocalDb 重建 LocalRepository
                if (isActive) {
                    ServiceContainer.switchLocalDb(profileName)
                }
                withContext(Dispatchers.Main) {
                    _importing.value = false
                    _toast.value = if (saved) {
                        ServiceContainer.localizedContext?.getString(
                            R.string.dbprofile_export_success, zipFileName
                        ) ?: ""
                    } else {
                        ServiceContainer.getString(R.string.dbprofile_export_failed)
                    }
                }
            } catch (e: Exception) {
                // 异常时也尝试恢复连接
                if (isActive) ServiceContainer.switchLocalDb(profileName)
                withContext(Dispatchers.Main) {
                    _importing.value = false
                    _toast.value = ServiceContainer.localizedContext?.getString(
                        R.string.dbprofile_export_failed_reason, e.message ?: ""
                    ) ?: ""
                }
            }
        }
    }

    /**
     * 从本地文件导入数据库。支持 .zip（包含 .db 及可选的 -wal/-shm）或直接 .db 文件。
     *
     * @param uri 用户选择的文件 URI
     * @param displayNameStr 新 db 的显示名
     */
    fun importFromFile(uri: Uri, displayNameStr: String) {
        if (displayNameStr.isBlank()) {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_input_display_name)
            return
        }
        val ctx = ServiceContainer.appContext ?: run {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_import_file_failed)
            return
        }
        val profileName = sanitizeProfileName(displayNameStr)
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选文件")
                // 识别格式：ZIP（PK\x03\x04）或 SQLite 文件头（"SQLite format 3\0"）
                val mainDb: ByteArray
                val walBytes: ByteArray?
                val shmBytes: ByteArray?
                when {
                    isZipBytes(bytes) -> {
                        val extracted = extractDbFromZip(bytes)
                            ?: throw IllegalStateException("ZIP 中未找到 .db 文件")
                        mainDb = extracted.main
                        walBytes = extracted.wal
                        shmBytes = extracted.shm
                    }
                    isSqliteBytes(bytes) -> {
                        mainDb = bytes
                        walBytes = null
                        shmBytes = null
                    }
                    else -> throw IllegalStateException("无法识别的文件格式：请选择 .zip 或 .db 文件")
                }
                // 关闭并清空目标 profile（若已存在）
                NekobotDatabase.deleteProfileFile(ctx, profileName)
                clearProfileSidecarData(ctx, profileName)
                Thread.sleep(100)
                // 写入新的 db 文件
                val mainDbName = "$profileName.db"
                val dbFile = ctx.getDatabasePath(mainDbName)
                dbFile.parentFile?.mkdirs()
                dbFile.writeBytes(mainDb)
                walBytes?.let {
                    ctx.getDatabasePath("$mainDbName-wal").writeBytes(it)
                }
                shmBytes?.let {
                    ctx.getDatabasePath("$mainDbName-shm").writeBytes(it)
                }
                // 注册到 PrefsManager
                prefs.saveDbProfile(
                    PrefsManager.DbProfile(
                        name = profileName,
                        displayName = displayNameStr,
                        source = "local",
                        createdAt = System.currentTimeMillis()
                    )
                )
                // 验证 db 可打开（触发迁移），失败则抛出异常
                runCatching {
                    NekobotDatabase.get(ctx, profileName).openHelper.writableDatabase
                }.onFailure { e ->
                    // 打开失败：清理文件并提示
                    NekobotDatabase.deleteProfileFile(ctx, profileName)
                    prefs.removeDbProfile(profileName)
                    throw IllegalStateException("数据库无法打开（可能文件损坏或版本不兼容）：${e.message}")
                }
                // 自动切换到新导入的 db
                prefs.activeDbName = profileName
                ServiceContainer.switchLocalDb(profileName)
                withContext(Dispatchers.Main) {
                    _importing.value = false
                    _activeName.value = profileName
                    reload()
                    _toast.value = ServiceContainer.localizedContext?.getString(
                        R.string.dbprofile_import_file_success, displayNameStr
                    ) ?: ""
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _importing.value = false
                    _toast.value = ServiceContainer.localizedContext?.getString(
                        R.string.dbprofile_import_file_failed_reason, e.message ?: ""
                    ) ?: ""
                }
            }
        }
    }

    /** ZIP 文件头识别：PK\x03\x04 */
    private fun isZipBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** SQLite 文件头识别："SQLite format 3\0"（前 16 字节） */
    private fun isSqliteBytes(bytes: ByteArray): Boolean =
        bytes.size >= 16 && String(bytes, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3"

    /** 解压 ZIP 并提取 .db 主文件及同名 -wal / -shm（若有）。 */
    private data class ExtractedDb(
        val main: ByteArray,
        val wal: ByteArray? = null,
        val shm: ByteArray? = null
    )

    private fun extractDbFromZip(zipBytes: ByteArray): ExtractedDb? {
        val rawEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val buf = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        buf.write(buffer, 0, n)
                    }
                    rawEntries[name] = buf.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // 找到主 .db 文件（不含 -wal / -shm 后缀）
        val mainDbEntry = rawEntries.keys.firstOrNull {
            it.endsWith(".db") && !it.endsWith(".db-wal") && !it.endsWith(".db-shm")
        } ?: return null
        val baseName = mainDbEntry.removeSuffix(".db")
        return ExtractedDb(
            main = rawEntries[mainDbEntry]!!,
            wal = rawEntries["$baseName.db-wal"],
            shm = rawEntries["$baseName.db-shm"]
        )
    }

    /** 写入字节到 Downloads 目录（兼容 Android 10+ 作用域存储与 9- 公共目录）。 */
    private fun writeBytesToDownloads(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(collection, values) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                File(downloadsDir, fileName).outputStream().use { it.write(bytes) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun displayName(name: String): String =
        _profiles.value.firstOrNull { it.name == name }?.displayName ?: name

    /** 数据库被删除或同名替换时，同步清理独立于 Room 文件的统计与成就数据。 */
    private fun clearProfileSidecarData(context: Context, profileName: String) {
        val normalizedName = profileName.removeSuffix(".db")
        context.getSharedPreferences(
            "token_usage_$normalizedName.db",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        com.nekobot.app.data.local.AchievementManager.clearScope("local:$normalizedName")
    }

    /** 显示名 → db 文件名（仅保留字母数字下划线，避免文件名非法字符）。 */
    private fun sanitizeProfileName(raw: String): String {
        val base = raw.trim().ifBlank { "imported" }
        val sanitized = base.map { c ->
            if (c.isLetterOrDigit() || c == '_') c else '_'
        }.joinToString("").trim('_').ifBlank { "imported" }
        // 确保不与默认 db 冲突
        return if (sanitized == PrefsManager.DEFAULT_DB_NAME) "${sanitized}_2" else sanitized
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbProfileScreen(onBack: () -> Unit) {
    val vm: DbProfileViewModel = viewModel()
    val profiles by vm.profiles.collectAsState()
    val activeName by vm.activeName.collectAsState()
    val loginRecords by vm.loginRecords.collectAsState()
    val importing by vm.importing.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PrefsManager.DbProfile?>(null) }
    var showImportFilePrompt by remember { mutableStateOf(false) }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }

    // 文件选择器：选择 .zip 或 .db 文件
    val pickFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingFileUri = uri
            showImportFilePrompt = true
        }
    }

    // 模式切换时自动刷新（兜底）
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    LaunchedEffect(appMode) { vm.reload() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dbprofile_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.dbprofile_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.dbprofile_import_remote), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前激活 db 提示卡片
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.dbprofile_current_db),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        profiles.firstOrNull { it.name == activeName }?.displayName ?: activeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.dbprofile_internal_id, activeName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                stringResource(R.string.dbprofile_saved_list),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (profiles.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.dbprofile_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(profiles, key = { it.name }) { profile ->
                        DbProfileCard(
                            profile = profile,
                            isActive = profile.name == activeName,
                            isDefault = profile.name == PrefsManager.DEFAULT_DB_NAME,
                            onSwitch = { vm.switchTo(profile.name) },
                            onDelete = { deleteTarget = profile },
                            onExport = { vm.exportToDownloads(profile.name) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dbprofile_import_button))
            }

            // 从本地文件导入
            OutlinedButton(
                onClick = { pickFileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-sqlite3", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dbprofile_import_file_button))
            }

            Text(
                stringResource(R.string.dbprofile_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        LoadingOverlay(visible = importing)
    }

    if (showImportDialog) {
        ImportFromRemoteDialog(
            loginRecords = loginRecords,
            importing = importing,
            onDismiss = { showImportDialog = false },
            onConfirm = { record, displayName ->
                vm.importFromRemote(record, displayName)
            }
        )
        // 导入完成后关闭对话框
        LaunchedEffect(importing) {
            if (!importing && toast != null) {
                showImportDialog = false
            }
        }
    }

    // 从文件导入：输入显示名
    if (showImportFilePrompt && pendingFileUri != null) {
        var displayNameInput by remember(pendingFileUri) {
            mutableStateOf(pendingFileUri?.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim() ?: "")
        }
        NekoDialog(
            onDismiss = {
                showImportFilePrompt = false
                pendingFileUri = null
            },
            title = stringResource(R.string.dbprofile_import_file_dialog_title),
            confirmText = stringResource(R.string.dbprofile_start_import),
            confirmEnabled = displayNameInput.isNotBlank() && !importing,
            onConfirm = {
                val uri = pendingFileUri
                if (uri != null && displayNameInput.isNotBlank()) {
                    vm.importFromFile(uri, displayNameInput.trim())
                    showImportFilePrompt = false
                    pendingFileUri = null
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val fileName = pendingFileUri?.lastPathSegment?.substringAfterLast('/') ?: ""
                if (fileName.isNotBlank()) {
                    Text(
                        stringResource(R.string.dbprofile_selected_file, fileName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = displayNameInput,
                    onValueChange = { displayNameInput = it },
                    label = { Text(stringResource(R.string.dbprofile_new_display_name)) },
                    placeholder = { Text(stringResource(R.string.dbprofile_display_name_placeholder)) },
                    singleLine = true,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.dbprofile_import_file_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    deleteTarget?.let { profile ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.dbprofile_delete_title),
            message = stringResource(R.string.dbprofile_delete_msg, profile.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                vm.delete(profile.name)
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun DbProfileCard(
    profile: PrefsManager.DbProfile,
    isActive: Boolean,
    isDefault: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 按下时背景色加深，作为可点击切换的视觉反馈
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedContainerColor = if (isPressed && !isActive) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = !isActive
            ) { onSwitch() },
        containerColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else pressedContainerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val sourceLabel = when (profile.source) {
                    "imported" -> stringResource(R.string.dbprofile_source_imported)
                    "local" -> stringResource(R.string.dbprofile_source_local)
                    else -> profile.source
                }
                val dateLabel = if (profile.createdAt > 0) {
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    sdf.format(Date(profile.createdAt))
                } else "—"
                Text(
                    "$sourceLabel · $dateLabel · ${profile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dbprofile_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isActive) stringResource(R.string.dbprofile_current_active) else stringResource(R.string.dbprofile_switch_to)) },
                        onClick = {
                            menuExpanded = false
                            if (!isActive) onSwitch()
                        },
                        enabled = !isActive
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dbprofile_export_to_downloads)) },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            if (!isDefault) onDelete()
                        },
                        enabled = !isDefault
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportFromRemoteDialog(
    loginRecords: List<LoginRecord>,
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LoginRecord, String) -> Unit
) {
    var selectedRecord by remember { mutableStateOf(loginRecords.firstOrNull()) }
    var displayName by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dbprofile_import_dialog_title),
        confirmText = if (importing) stringResource(R.string.dbprofile_importing) else stringResource(R.string.dbprofile_start_import),
        confirmEnabled = !importing && selectedRecord != null,
        onConfirm = {
            val rec = selectedRecord
            if (rec != null) {
                onConfirm(rec, displayName)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loginRecords.isEmpty()) {
                Text(
                    stringResource(R.string.dbprofile_no_records),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    stringResource(R.string.dbprofile_select_server),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    OutlinedTextField(
                        value = selectedRecord?.let { "${it.username} @ ${it.serverUrl}" } ?: stringResource(R.string.dbprofile_please_select),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !importing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !importing) { menuExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { menuExpanded = true }, enabled = !importing) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        loginRecords.forEach { rec ->
                            DropdownMenuItem(
                                text = { Text("${rec.username} @ ${rec.serverUrl}") },
                                onClick = {
                                    selectedRecord = rec
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.dbprofile_new_display_name)) },
                placeholder = { Text(stringResource(R.string.dbprofile_display_name_placeholder)) },
                singleLine = true,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            )

            if (importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.dbprofile_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    stringResource(R.string.dbprofile_dialog_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
