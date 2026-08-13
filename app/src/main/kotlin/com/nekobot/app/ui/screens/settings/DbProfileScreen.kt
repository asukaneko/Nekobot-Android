package com.nekobot.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.DbProfileArchiveCodec
import com.nekobot.app.data.local.DbProfilePortraitSource
import com.nekobot.app.data.local.DbProfileStoryData
import com.nekobot.app.data.local.ExtractedDbProfileArchive
import com.nekobot.app.data.local.LoginRecord
import com.nekobot.app.data.local.LocalDatabaseCredentialBundle
import com.nekobot.app.data.local.LocalPlotStoryProfileSnapshot
import com.nekobot.app.data.local.LocalPlotStoryStore
import com.nekobot.app.data.local.LocalWebDavArchiveCodec
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 本地 DB Profile 管理 ViewModel：维护 profile 列表、激活态、远程导入与切换。
 *
 * - profiles：本地已记录的 db profile 列表（来自 PrefsManager.listDbProfiles）
 * - activeName：当前激活的 db 名
 * - loginRecords：已保存的远程服务器登录记录，供导入下拉选择
 */
class DbProfileViewModel : ViewModel() {

    private val prefs: PrefsManager get() = ServiceContainer.prefs
    private fun string(resId: Int, vararg args: Any): String =
        ServiceContainer.appContext?.getString(resId, *args) ?: ServiceContainer.getString(resId)

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
            val wasActive = prefs.activeDbName == profileName
            val ctx = ServiceContainer.appContext
            if (ctx != null) {
                if (wasActive) ServiceContainer.localRepository.close()
                NekobotDatabase.deleteProfileFile(ctx, profileName)
                runCatching { LocalPlotStoryStore.clearProfile(ctx, profileName) }
                clearProfileSidecarData(ctx, profileName)
                // 清理导入的立绘目录
                runCatching {
                    File(ctx.cacheDir, "portraits/$profileName").deleteRecursively()
                    importedPortraitDir(ctx, profileName).deleteRecursively()
                }
            }
            prefs.removeDbProfile(profileName)
            if (wasActive) {
                ServiceContainer.switchLocalDb(PrefsManager.DEFAULT_DB_NAME)
            }
            withContext(Dispatchers.Main) {
                if (wasActive) {
                    _activeName.value = PrefsManager.DEFAULT_DB_NAME
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
            val wasActive = prefs.activeDbName == profileName
            if (wasActive) ServiceContainer.localRepository.close()
            val result = ServiceContainer.unified.importNbotConfigFromRemote(
                url = record.serverUrl,
                token = record.token,
                password = password,
                profileName = profileName,
                displayName = displayName
            )
            _importing.value = false
            if (result.success) {
                val context = ServiceContainer.appContext
                val storyPrepared = context != null && runCatching {
                    // nbotcfg 不含故事地图；同名覆盖也必须显式清空，不能复活旧 profile 数据。
                    LocalPlotStoryStore.replace(
                        context = context,
                        databaseName = profileName,
                        allowedImportedSessionIds = emptySet(),
                        story = null
                    )
                }.isSuccess
                if (!storyPrepared) {
                    if (wasActive) ServiceContainer.switchLocalDb(profileName)
                    _toast.value = ServiceContainer.getString(R.string.dbprofile_import_file_failed)
                    return@launch
                }
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
                if (wasActive) ServiceContainer.switchLocalDb(profileName)
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
     * 1. 从数据库收集角色、会话实际引用的本地头像、立绘与故事地图
     * 2. 关闭目标 profile 的 db 连接（强制 WAL 刷盘）
     * 3. 将 .db / .db-wal / .db-shm、图片、URI 映射与剧情状态打包到 Downloads
     * 4. 若导出的是当前激活 db，重开连接以恢复正常使用
     */
    fun exportToDownloads(profileName: String, backupPassword: String) {
        exportProfile(
            profileName,
            destinationUri = null,
            requestedFileName = null,
            backupPassword = backupPassword
        )
    }

    /** Android 8/9 通过系统文件选择器取得写入 URI，避免依赖旧存储权限。 */
    fun exportToUri(
        profileName: String,
        destinationUri: Uri,
        fileName: String,
        backupPassword: String
    ) {
        exportProfile(profileName, destinationUri, fileName, backupPassword)
    }

    fun suggestedExportFileName(profileName: String): String {
        val profile = _profiles.value.firstOrNull { it.name == profileName }
        return buildExportFileName(profile?.displayName.orEmpty(), profileName)
    }

    private fun exportProfile(
        profileName: String,
        destinationUri: Uri?,
        requestedFileName: String?,
        backupPassword: String
    ) {
        if (backupPassword.trim().length < 8) {
            _toast.value = ServiceContainer.getString(R.string.dbprofile_backup_password_too_short)
            return
        }
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
                // 活动库先停止生成，避免导出过程中继续追加 DB 行或图谱节点。
                if (isActive) ServiceContainer.localRepository.close()
                val targetDb = NekobotDatabase.get(ctx, profileName)
                targetDb.aiModelDao().migrateStoredSecrets()
                targetDb.mcpServerDao().migrateStoredSecrets()
                targetDb.apiKeyDao().migrateStoredSecrets()
                val credentialBundle = LocalDatabaseCredentialBundle.capture(targetDb)
                val encryptedCredentials = LocalWebDavArchiveCodec.encrypt(
                    archive = credentialBundle,
                    password = backupPassword,
                    profileName = profileName
                )
                val portraitSources = collectPortraitSources(ctx, targetDb)
                val sessionIds = targetDb.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
                LocalPlotStoryStore.migrateLegacyProfile(ctx, profileName, sessionIds)
                // 关闭目标 profile 的 db 连接，确保 WAL 刷盘
                NekobotDatabase.closeProfile(profileName)
                Thread.sleep(100)
                val story = LocalPlotStoryStore.capture(ctx, profileName, sessionIds)
                val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
                val dbFile = ctx.getDatabasePath(dbName)
                if (!dbFile.exists()) {
                    if (isActive) ServiceContainer.switchLocalDb(profileName)
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
                // 流式打包到 Downloads，避免数据库和多张立绘同时驻留内存
                val zipFileName = requestedFileName
                    ?.takeIf { it.isNotBlank() }
                    ?: buildExportFileName(displayNameStr, profileName)
                val saved = if (destinationUri != null) {
                    writeArchiveToUri(
                        ctx,
                        destinationUri,
                        entries,
                        portraitSources,
                        story,
                        encryptedCredentials
                    )
                } else {
                    writeArchiveToDownloads(
                        ctx,
                        zipFileName,
                        entries,
                        portraitSources,
                        story,
                        encryptedCredentials
                    )
                }
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

    private fun buildExportFileName(displayName: String, profileName: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifBlank { profileName }
        return "nekobot_${safeName}_$timestamp.zip"
    }

    /**
     * 从本地文件导入数据库。支持 .zip（包含 .db 及可选的 -wal/-shm）或直接 .db 文件。
     *
     * @param uri 用户选择的文件 URI
     * @param displayNameStr 新 db 的显示名
     */
    fun importFromFile(uri: Uri, displayNameStr: String, backupPassword: String) {
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
            val previousActive = prefs.activeDbName
            val previousProfile = prefs.listDbProfiles().firstOrNull { it.name == profileName }
            var repositoryClosed = false
            var databaseRollback: DatabaseImportRollback? = null
            var portraitRestore: PortraitRestoreTransaction? = null
            var storyRestore: StoryRestoreTransaction? = null
            var committed = false
            try {
                migrateLegacyPlotStoryProfile(ctx, profileName)
                // 只读取文件头识别格式；ZIP 直接流式解压，避免额外保留一份完整压缩包。
                val archive = ctx.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                    input.mark(16)
                    val header = ByteArray(16)
                    var headerSize = 0
                    while (headerSize < header.size) {
                        val count = input.read(header, headerSize, header.size - headerSize)
                        if (count < 0) break
                        headerSize += count
                    }
                    input.reset()
                    when {
                        isZipBytes(header) -> {
                            DbProfileArchiveCodec.extractArchive(input)
                                ?: throw IllegalStateException(string(R.string.dbprofile_zip_db_missing))
                        }
                        headerSize >= 16 && isSqliteBytes(header) -> {
                            ExtractedDbProfileArchive(main = readBytesWithLimit(input))
                        }
                        else -> throw IllegalStateException(string(R.string.dbprofile_unknown_file_format))
                    }
                } ?: throw IllegalStateException(string(R.string.dbprofile_read_selected_file_failed))
                if (!isSqliteBytes(archive.main)) {
                    throw IllegalStateException(string(R.string.dbprofile_invalid_database))
                }
                val credentialBundle = archive.encryptedCredentials?.let { encrypted ->
                    if (backupPassword.isBlank()) {
                        throw IllegalArgumentException(string(R.string.dbprofile_backup_password_required))
                    }
                    LocalWebDavArchiveCodec.decrypt(encrypted, backupPassword)
                }

                // 先完整备份同名数据库；后续任一步失败都会恢复原文件和原激活 profile。
                if (profileName == previousActive) {
                    ServiceContainer.localRepository.close()
                    repositoryClosed = true
                }
                val rollback = prepareDatabaseRollback(ctx, profileName)
                databaseRollback = rollback
                replaceDatabaseFiles(ctx, profileName, archive)

                // 验证 db 可打开（触发迁移），失败则抛出异常
                val importedDb = runCatching {
                    NekobotDatabase.get(ctx, profileName).also {
                        it.openHelper.writableDatabase
                    }
                }.onFailure { e ->
                    throw IllegalStateException(string(R.string.dbprofile_database_open_failed, e.message.orEmpty()))
                }.getOrThrow()
                val importedSessionIds = importedDb.sessionDao().listAll()
                    .mapTo(linkedSetOf()) { it.id }

                // 图片目录替换与五个 URI 字段改写也属于同一导入事务。
                val portraitTransaction = restoreEmbeddedPortraits(ctx, profileName, archive)
                portraitRestore = portraitTransaction
                importedDb.withTransaction {
                    if (credentialBundle != null) {
                        LocalDatabaseCredentialBundle.restore(importedDb, credentialBundle)
                    } else {
                        importedDb.aiModelDao().migrateStoredSecrets()
                        importedDb.mcpServerDao().migrateStoredSecrets()
                        importedDb.apiKeyDao().migrateStoredSecrets()
                    }
                    rewritePortraitReferences(importedDb, portraitTransaction.references)
                }

                // 故事地图与剧情选项位于 Room 之外，按目标 profile 整体替换并纳入失败回滚。
                val storyTransaction = StoryRestoreTransaction(
                    context = ctx,
                    profileName = profileName,
                    previous = LocalPlotStoryStore.snapshot(ctx, profileName)
                )
                storyRestore = storyTransaction
                LocalPlotStoryStore.replace(
                    context = ctx,
                    databaseName = profileName,
                    allowedImportedSessionIds = importedSessionIds,
                    story = archive.story
                )

                // 自动切换成功后才清理旧统计并注册新 profile。
                ServiceContainer.switchLocalDb(profileName)
                clearProfileSidecarData(ctx, profileName)

                prefs.saveDbProfile(
                    PrefsManager.DbProfile(
                        name = profileName,
                        displayName = displayNameStr,
                        source = "local",
                        createdAt = System.currentTimeMillis()
                    )
                )
                portraitTransaction.commit()
                rollback.cleanup()
                committed = true
                withContext(Dispatchers.Main) {
                    _importing.value = false
                    _activeName.value = profileName
                    reload()
                    _toast.value = ServiceContainer.localizedContext?.getString(
                        R.string.dbprofile_import_file_success, displayNameStr
                    ) ?: ""
                }
            } catch (e: Exception) {
                val rollback = databaseRollback
                if (!committed && rollback != null) {
                    runCatching { storyRestore?.rollback() }
                    runCatching { portraitRestore?.rollback() }
                    val databaseRestored = runCatching {
                        restoreDatabaseRollback(ctx, profileName, rollback)
                    }.isSuccess
                    if (databaseRestored) rollback.cleanup()
                    if (previousProfile != null) {
                        prefs.saveDbProfile(previousProfile)
                    } else {
                        prefs.removeDbProfile(profileName)
                    }
                    prefs.activeDbName = previousActive
                    runCatching { ServiceContainer.switchLocalDb(previousActive) }
                } else if (!committed && repositoryClosed) {
                    runCatching { ServiceContainer.switchLocalDb(previousActive) }
                }
                if (e is CancellationException) throw e
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
    private fun isSqliteBytes(bytes: ByteArray): Boolean {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        return bytes.size >= header.size &&
            bytes.copyOfRange(0, header.size).contentEquals(header)
    }

    private fun readBytesWithLimit(
        input: InputStream,
        maxBytes: Long = 512L * 1024 * 1024
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                total += count
                require(total <= maxBytes) { string(R.string.dbprofile_database_too_large) }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private data class DatabaseImportRollback(val directory: File) {
        fun cleanup() {
            runCatching { directory.deleteRecursively() }
        }
    }

    private data class StoryRestoreTransaction(
        val context: Context,
        val profileName: String,
        val previous: LocalPlotStoryProfileSnapshot
    ) {
        fun rollback() {
            LocalPlotStoryStore.restore(
                context = context,
                databaseName = profileName,
                snapshot = previous
            )
        }
    }

    private suspend fun migrateLegacyPlotStoryProfile(context: Context, profileName: String) {
        if (!LocalPlotStoryStore.isLegacyProfilePending(context, profileName)) return
        val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
        if (!context.getDatabasePath(dbName).isFile) {
            LocalPlotStoryStore.clearProfile(context, profileName)
            return
        }
        val sessionIds = try {
            NekobotDatabase.get(context, profileName)
                .sessionDao()
                .listAll()
                .mapTo(linkedSetOf()) { it.id }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 保持 pending 与 legacy 原样；只有有效备份完整写入后才允许推进迁移标记。
            return
        }
        LocalPlotStoryStore.migrateLegacyProfile(context, profileName, sessionIds)
    }

    /** 关闭目标库后复制完整 SQLite 文件族，供同名导入失败时恢复。 */
    private fun prepareDatabaseRollback(
        context: Context,
        profileName: String
    ): DatabaseImportRollback {
        NekobotDatabase.closeProfile(profileName)
        val directory = File(
            context.cacheDir,
            "db-profile-rollback-${UUID.randomUUID()}"
        )
        check(directory.mkdirs()) { string(R.string.dbprofile_rollback_dir_failed) }
        try {
            profileDatabaseFiles(context, profileName)
                .filter { it.isFile }
                .forEach { file -> file.copyTo(File(directory, file.name), overwrite = true) }
        } catch (e: Exception) {
            directory.deleteRecursively()
            throw e
        }
        return DatabaseImportRollback(directory)
    }

    private fun replaceDatabaseFiles(
        context: Context,
        profileName: String,
        archive: ExtractedDbProfileArchive
    ) {
        NekobotDatabase.closeProfile(profileName)
        profileDatabaseFiles(context, profileName).forEach { file ->
            if (file.exists() && !file.delete()) {
                error(string(R.string.dbprofile_replace_old_database_failed, file.name))
            }
        }
        val mainDbName = "$profileName.db"
        context.getDatabasePath(mainDbName).apply {
            parentFile?.mkdirs()
            writeBytes(archive.main)
        }
        archive.wal?.let { context.getDatabasePath("$mainDbName-wal").writeBytes(it) }
        archive.shm?.let { context.getDatabasePath("$mainDbName-shm").writeBytes(it) }
    }

    private fun restoreDatabaseRollback(
        context: Context,
        profileName: String,
        rollback: DatabaseImportRollback
    ) {
        NekobotDatabase.closeProfile(profileName)
        profileDatabaseFiles(context, profileName).forEach { file ->
            if (file.exists() && !file.delete()) {
                error(string(R.string.dbprofile_cleanup_failed_import, file.name))
            }
        }
        rollback.directory.listFiles().orEmpty().forEach { backup ->
            val destination = context.getDatabasePath(backup.name)
            destination.parentFile?.mkdirs()
            backup.copyTo(destination, overwrite = true)
        }
    }

    private fun profileDatabaseFiles(context: Context, profileName: String): List<File> {
        val dbName = "$profileName.db"
        return listOf(
            context.getDatabasePath(dbName),
            context.getDatabasePath("$dbName-journal"),
            context.getDatabasePath("$dbName-wal"),
            context.getDatabasePath("$dbName-shm")
        )
    }

    /** 收集角色与会话字段实际引用、且位于应用私有目录内的本地图片。 */
    private suspend fun collectPortraitSources(
        context: Context,
        db: NekobotDatabase
    ): List<DbProfilePortraitSource> {
        val references = linkedSetOf<String>()
        db.characterDao().listAll().forEach { character ->
            listOf(character.avatar, character.portrait)
                .filterNotNull()
                .filterTo(references) { it.isNotBlank() }
        }
        db.sessionDao().listAll().forEach { session ->
            listOf(session.portrait, session.senderAvatar, session.characterAvatar)
                .filterNotNull()
                .filterTo(references) { it.isNotBlank() }
        }
        return references.mapNotNull { reference ->
            resolveAppPrivateFile(context, reference)?.let { file ->
                DbProfilePortraitSource(reference, file)
            }
        }
    }

    /** 仅允许备份应用已知立绘目录内的 file URI，避免把其他私有文件带入备份。 */
    private fun resolveAppPrivateFile(context: Context, reference: String): File? {
        val candidate = runCatching {
            val uri = Uri.parse(reference)
            when {
                uri.scheme.equals("file", ignoreCase = true) ->
                    uri.path?.takeIf { it.isNotBlank() }?.let(::File)
                uri.scheme.isNullOrBlank() && reference.startsWith(File.separator) -> File(reference)
                else -> null
            }
        }.getOrNull()?.canonicalFile ?: return null
        if (!candidate.isFile) return null

        val candidatePath = candidate.path
        val allowed = listOf(
            File(context.filesDir, "portraits"),
            File(context.cacheDir, "portraits")
        ).any { root ->
            val rootPath = root.canonicalFile.path
            candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        }
        return candidate.takeIf { allowed }
    }

    private class PortraitRestoreTransaction(
        val references: Map<String, String>,
        private val target: File? = null,
        private val backup: File? = null
    ) {
        fun commit() {
            runCatching { backup?.deleteRecursively() }
        }

        fun rollback() {
            val destination = target ?: return
            destination.deleteRecursively()
            val original = backup
            if (original != null && original.exists() && !original.renameTo(destination)) {
                error(ServiceContainer.getString(R.string.dbprofile_restore_portrait_dir_failed))
            }
        }
    }

    /** 将内嵌图片恢复到持久目录；返回值会把旧目录保留至整个导入事务提交。 */
    private fun restoreEmbeddedPortraits(
        context: Context,
        profileName: String,
        archive: ExtractedDbProfileArchive
    ): PortraitRestoreTransaction {
        if (archive.portraits.isEmpty() || archive.portraitReferences.isEmpty()) {
            return PortraitRestoreTransaction(emptyMap())
        }

        val target = importedPortraitDir(context, profileName)
        val parent = target.parentFile ?: error(string(R.string.dbprofile_create_portrait_dir_failed))
        val staging = File(parent, ".${target.name}.restoring")
        val backup = File(parent, ".${target.name}.backup")
        parent.mkdirs()
        staging.deleteRecursively()
        staging.mkdirs()

        val restoredEntries = linkedMapOf<String, String>()
        try {
            archive.portraits.entries.sortedBy { it.key }.forEachIndexed { index, (entry, bytes) ->
                val rawExtension = entry.substringAfterLast('/').substringAfterLast('.', "")
                    .lowercase(Locale.ROOT)
                val extension = rawExtension
                    .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                    ?.let { ".$it" }
                    .orEmpty()
                val fileName = "${index.toString().padStart(4, '0')}$extension"
                File(staging, fileName).writeBytes(bytes)
                restoredEntries[entry] = Uri.fromFile(File(target, fileName)).toString()
            }

            backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) {
                error(string(R.string.dbprofile_replace_portrait_dir_failed))
            }
            try {
                if (!staging.renameTo(target)) {
                    check(staging.copyRecursively(target, overwrite = true)) {
                        string(R.string.dbprofile_save_portrait_failed)
                    }
                    staging.deleteRecursively()
                }
            } catch (e: Exception) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw e
            }
        } finally {
            staging.deleteRecursively()
        }

        val references = archive.portraitReferences.mapNotNull { (oldReference, entry) ->
            restoredEntries[entry]?.let { oldReference to it }
        }.toMap(linkedMapOf())
        return PortraitRestoreTransaction(references, target, backup)
    }

    /** 同时改写角色表与会话快照中的全部立绘/头像字段。 */
    private suspend fun rewritePortraitReferences(
        db: NekobotDatabase,
        references: Map<String, String>
    ) {
        if (references.isEmpty()) return
        fun rewrite(value: String?): String? = value?.let { references[it] ?: it }

        db.sessionDao().listAll().forEach { session ->
            val portrait = rewrite(session.portrait)
            val senderAvatar = rewrite(session.senderAvatar)
            val characterAvatar = rewrite(session.characterAvatar)
            if (
                portrait != session.portrait ||
                senderAvatar != session.senderAvatar ||
                characterAvatar != session.characterAvatar
            ) {
                db.sessionDao().updatePortraits(
                    session.id,
                    portrait,
                    senderAvatar,
                    characterAvatar
                )
            }
        }
        db.characterDao().listAll().forEach { character ->
            val portrait = rewrite(character.portrait)
            val avatar = rewrite(character.avatar)
            if (portrait != character.portrait || avatar != character.avatar) {
                db.characterDao().updatePortraits(character.id, portrait, avatar)
            }
        }
    }

    private fun importedPortraitDir(context: Context, profileName: String): File =
        File(context.filesDir, "portraits/profiles/$profileName")

    /** Android 10+ 通过 MediaStore 流式写入 Downloads。 */
    private fun writeArchiveToDownloads(
        context: Context,
        fileName: String,
        databaseFiles: List<Pair<String, File>>,
        portraitSources: List<DbProfilePortraitSource>,
        story: DbProfileStoryData,
        encryptedCredentials: ByteArray
    ): Boolean {
        var insertedUri: Uri? = null
        return try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                string(R.string.dbprofile_export_location_required)
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { output ->
                DbProfileArchiveCodec.writeArchive(
                    output,
                    databaseFiles,
                    portraitSources,
                    story,
                    encryptedCredentials
                )
            } ?: error(string(R.string.dbprofile_open_download_failed))
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            true
        } catch (_: Exception) {
            insertedUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            false
        }
    }

    /** Android 8/9 使用 SAF 返回的 URI，无需申请旧版外部存储权限。 */
    private fun writeArchiveToUri(
        context: Context,
        destinationUri: Uri,
        databaseFiles: List<Pair<String, File>>,
        portraitSources: List<DbProfilePortraitSource>,
        story: DbProfileStoryData,
        encryptedCredentials: ByteArray
    ): Boolean = try {
        context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
            DbProfileArchiveCodec.writeArchive(
                output,
                databaseFiles,
                portraitSources,
                story,
                encryptedCredentials
            )
        } ?: error(string(R.string.dbprofile_open_export_failed))
        true
    } catch (_: Exception) {
        runCatching { context.contentResolver.delete(destinationUri, null, null) }
        false
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
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeName by vm.activeName.collectAsStateWithLifecycle()
    val loginRecords by vm.loginRecords.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PrefsManager.DbProfile?>(null) }
    var showImportFilePrompt by remember { mutableStateOf(false) }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var exportTargetProfile by remember { mutableStateOf<String?>(null) }
    var pendingLegacyExportProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLegacyExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLegacyExportPassword by remember { mutableStateOf<String?>(null) }

    // 文件选择器：选择 .zip 或 .db 文件
    val pickFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingFileUri = uri
            showImportFilePrompt = true
        }
    }

    // Android 8/9 没有 MediaStore Downloads，使用 SAF 让用户选择保存位置。
    val createExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
            "application/zip"
        )
    ) { uri ->
        val profileName = pendingLegacyExportProfile
        val fileName = pendingLegacyExportFileName
        val backupPassword = pendingLegacyExportPassword
        pendingLegacyExportProfile = null
        pendingLegacyExportFileName = null
        pendingLegacyExportPassword = null
        if (uri != null && profileName != null && fileName != null && backupPassword != null) {
            vm.exportToUri(profileName, uri, fileName, backupPassword)
        }
    }

    // 模式切换时自动刷新（兜底）
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
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
                            onExport = { exportTargetProfile = profile.name }
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

    exportTargetProfile?.let { profileName ->
        var backupPassword by remember(profileName) { mutableStateOf("") }
        var confirmPassword by remember(profileName) { mutableStateOf("") }
        val normalizedPassword = backupPassword.trim()
        val passwordsMatch = normalizedPassword == confirmPassword.trim()
        NekoDialog(
            onDismiss = { exportTargetProfile = null },
            title = stringResource(R.string.dbprofile_export_password_title),
            confirmText = stringResource(R.string.dbprofile_export_to_downloads),
            confirmEnabled = normalizedPassword.length >= 8 && passwordsMatch && !importing,
            onConfirm = {
                exportTargetProfile = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vm.exportToDownloads(profileName, normalizedPassword)
                } else {
                    val fileName = vm.suggestedExportFileName(profileName)
                    pendingLegacyExportProfile = profileName
                    pendingLegacyExportFileName = fileName
                    pendingLegacyExportPassword = normalizedPassword
                    createExportLauncher.launch(fileName)
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.dbprofile_export_password_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = { Text(stringResource(R.string.dbprofile_backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.dbprofile_backup_password_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                )
                if (normalizedPassword.isNotEmpty() && normalizedPassword.length < 8) {
                    Text(
                        stringResource(R.string.dbprofile_backup_password_too_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                    Text(
                        stringResource(R.string.dbprofile_backup_password_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
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
        var backupPassword by remember(pendingFileUri) { mutableStateOf("") }
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
                    vm.importFromFile(uri, displayNameInput.trim(), backupPassword.trim())
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
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = { Text(stringResource(R.string.dbprofile_backup_password_import)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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
