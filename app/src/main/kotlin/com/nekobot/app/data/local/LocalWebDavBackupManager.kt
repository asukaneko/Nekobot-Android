package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalMessageImageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalStickerEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import com.nekobot.app.data.local.oauth.OAuthSecretStore
import com.nekobot.app.data.local.security.SecurePreferenceStore
import com.nekobot.app.data.model.WebDavBackupRequest
import com.nekobot.app.data.model.WebDavConfig
import com.nekobot.app.data.model.WebDavTestRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val FULL_RESTORE_JOURNAL_DIR = ".webdav_restore"
private const val MIN_RESTORE_ROLLBACK_FREE_BYTES = 32L * 1024L * 1024L

/**
 * 本地模式 WebDAV 备份。
 *
 * 路径和原仓库保持一致：`{WebDAV 根地址}/nekobot/config.nbotcfg`。备份文件使用
 * PBKDF2-HMAC-SHA256 + Fernet 加密，内部保存当前 Room 数据库、该数据库对应的
 * Token 用量、成就解锁状态，以及用户选择包含的立绘。
 */
class LocalWebDavBackupManager(
    context: Context,
    private val prefs: PrefsManager
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val oauthSecrets = OAuthSecretStore()
    private val configPrefs =
        appContext.getSharedPreferences(CONFIG_PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs = SecurePreferenceStore(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 主动触发旧版 WebDAV 明文密码迁移。 */
    fun migrateStoredSecrets() {
        securePrefs.getString(SECURE_KEY_PASSWORD, configPrefs, KEY_PASSWORD)
        securePrefs.getString(
            SECURE_KEY_ENCRYPTION_PASSWORD,
            configPrefs,
            KEY_ENCRYPTION_PASSWORD
        )
    }

    fun getConfig(): WebDavConfig {
        val url = configPrefs.getString(KEY_URL, "").orEmpty()
        val password = securePrefs.getString(
            SECURE_KEY_PASSWORD,
            configPrefs,
            KEY_PASSWORD
        ).orEmpty()
        val encryptionPassword =
            securePrefs.getString(
                SECURE_KEY_ENCRYPTION_PASSWORD,
                configPrefs,
                KEY_ENCRYPTION_PASSWORD
            ).orEmpty()
        return WebDavConfig(
            enabled = configPrefs.getBoolean(KEY_ENABLED, false),
            url = url.takeIf { it.isNotBlank() },
            username = configPrefs.getString(KEY_USERNAME, "").orEmpty()
                .takeIf { it.isNotBlank() },
            password = mask(password).takeIf { it.isNotBlank() },
            encryptionPassword = mask(encryptionPassword).takeIf { it.isNotBlank() },
            lastBackupAt = configPrefs.getString(KEY_LAST_BACKUP_AT, "").orEmpty()
                .takeIf { it.isNotBlank() },
            lastSyncAt = configPrefs.getString(KEY_LAST_SYNC_AT, "").orEmpty()
                .takeIf { it.isNotBlank() },
            lastError = configPrefs.getString(KEY_LAST_ERROR, "").orEmpty()
                .takeIf { it.isNotBlank() },
            lastFileSize = configPrefs.getLong(KEY_LAST_FILE_SIZE, 0L)
                .takeIf { it > 0L },
            lastModified = configPrefs.getString(KEY_LAST_MODIFIED, "").orEmpty()
                .takeIf { it.isNotBlank() },
            resolvedFileUrl = runCatching { resolveFileUrl(url) }.getOrNull(),
            hasPassword = password.isNotBlank(),
            hasEncryptionPassword = encryptionPassword.isNotBlank(),
            autoIncrementalSyncEnabled = configPrefs.getBoolean(
                KEY_AUTO_INCREMENTAL_SYNC_ENABLED,
                false
            ),
            autoIncrementalSyncIntervalHours = configPrefs.getInt(
                KEY_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS,
                DEFAULT_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS
            ).coerceIn(MIN_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS, MAX_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS),
            incrementalSyncMaxVersions = configPrefs.getInt(
                KEY_INCREMENTAL_SYNC_MAX_VERSIONS,
                DEFAULT_INCREMENTAL_SYNC_MAX_VERSIONS
            ).coerceIn(MIN_INCREMENTAL_SYNC_MAX_VERSIONS, MAX_INCREMENTAL_SYNC_MAX_VERSIONS)
        )
    }

    fun saveConfig(config: WebDavConfig): JsonObject {
        val editor = configPrefs.edit()
        config.enabled?.let { editor.putBoolean(KEY_ENABLED, it) }
        config.url?.let { editor.putString(KEY_URL, it.trim()) }
        config.username?.let { editor.putString(KEY_USERNAME, it.trim()) }
        config.password
            ?.takeIf { it.isNotBlank() && '*' !in it }
            ?.let {
                securePrefs.putString(SECURE_KEY_PASSWORD, it)
                editor.remove(KEY_PASSWORD)
            }
        config.encryptionPassword
            ?.takeIf { it.isNotBlank() && '*' !in it }
            ?.let {
                securePrefs.putString(SECURE_KEY_ENCRYPTION_PASSWORD, it)
                editor.remove(KEY_ENCRYPTION_PASSWORD)
            }
        config.autoIncrementalSyncEnabled?.let {
            editor.putBoolean(KEY_AUTO_INCREMENTAL_SYNC_ENABLED, it)
        }
        config.autoIncrementalSyncIntervalHours?.let {
            editor.putInt(
                KEY_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS,
                it.coerceIn(
                    MIN_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS,
                    MAX_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS
                )
            )
        }
        config.incrementalSyncMaxVersions?.let {
            editor.putInt(
                KEY_INCREMENTAL_SYNC_MAX_VERSIONS,
                it.coerceIn(
                    MIN_INCREMENTAL_SYNC_MAX_VERSIONS,
                    MAX_INCREMENTAL_SYNC_MAX_VERSIONS
                )
            )
        }
        editor.apply()
        LocalWebDavSyncScheduler.configure(
            appContext,
            webDavEnabled = config.enabled ?: configPrefs.getBoolean(KEY_ENABLED, false),
            autoIncrementalSyncEnabled = config.autoIncrementalSyncEnabled
                ?: configPrefs.getBoolean(KEY_AUTO_INCREMENTAL_SYNC_ENABLED, false),
            intervalHours = config.autoIncrementalSyncIntervalHours
                ?: configPrefs.getInt(
                    KEY_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS,
                    DEFAULT_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS
                )
        )
        return successJson().apply {
            add("config", gson.toJsonTree(getConfig()))
        }
    }

    suspend fun testConnection(request: WebDavTestRequest): JsonObject =
        withContext(Dispatchers.IO) {
            val raw = rawConfig(
                urlOverride = request.url,
                usernameOverride = request.username,
                passwordOverride = request.password
            )
            val baseUrl = normalizeBaseUrl(raw.url)
            val fileUrl = resolveFileUrl(baseUrl)
            val result = successJson().apply {
                addProperty("ok", false)
                addProperty("exists", false)
                addProperty("folder_exists", false)
                addProperty("folder_created", false)
                addProperty("resolved_file_url", fileUrl)
            }

            try {
                execute(
                    Request.Builder().url(baseUrl).head(),
                    raw
                ).use { response ->
                    if (response.code == 401) {
                        return@withContext result.apply {
                            addProperty("status_code", response.code)
                            addProperty("message", "认证失败 (HTTP 401)")
                        }
                    }
                }

                val folder = ensureFolder(raw)
                result.addProperty("folder_exists", folder.exists)
                result.addProperty("folder_created", folder.created)
                if (!folder.ok) {
                    result.addProperty("status_code", folder.statusCode)
                    result.addProperty("message", folder.message)
                    return@withContext result
                }

                execute(Request.Builder().url(fileUrl).head(), raw).use { response ->
                    result.addProperty("status_code", response.code)
                    when (response.code) {
                        200 -> {
                            result.addProperty("ok", true)
                            result.addProperty("exists", true)
                            result.addProperty(
                                "last_modified",
                                response.header("Last-Modified").orEmpty()
                            )
                            result.addProperty(
                                "content_length",
                                response.header("Content-Length")?.toLongOrNull() ?: 0L
                            )
                            result.addProperty("message", "连接成功，远程备份文件已存在")
                        }

                        404 -> {
                            result.addProperty("ok", true)
                            result.addProperty(
                                "message",
                                "连接成功，nekobot/ 文件夹已就绪，备份后会创建配置文件"
                            )
                        }

                        403 -> {
                            // 坚果云等服务可能拒绝 HEAD，但仍允许 PUT/GET。
                            result.addProperty("ok", true)
                            result.addProperty(
                                "message",
                                "服务器拒绝 HEAD 检查，但备份和恢复仍可正常使用"
                            )
                        }

                        401 -> result.addProperty("message", "认证失败 (HTTP 401)")
                        else -> result.addProperty(
                            "message",
                            "服务器返回异常状态码：HTTP ${response.code}"
                        )
                    }
                }
                result
            } catch (e: Exception) {
                result.apply {
                    addProperty("message", readableError("连接失败", e))
                }
            }
        }

    suspend fun remoteInfo(): JsonObject = withContext(Dispatchers.IO) {
        val raw = rawConfig()
        val baseUrl = normalizeBaseUrl(raw.url)
        val fileUrl = resolveFileUrl(baseUrl)
        val info = successJson().apply {
            addProperty("ok", false)
            addProperty("exists", false)
            addProperty("size", 0L)
            addProperty("file_size", 0L)
            addProperty("last_modified", "")
            addProperty("file_url", fileUrl)
            addProperty("resolved_file_url", fileUrl)
        }

        try {
            val propfindBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                  </D:prop>
                </D:propfind>
            """.trimIndent().toRequestBody(XML_MEDIA_TYPE)
            execute(
                Request.Builder()
                    .url(fileUrl)
                    .method("PROPFIND", propfindBody)
                    .header("Depth", "0"),
                raw
            ).use { response ->
                info.addProperty("status_code", response.code)
                if (response.code in listOf(200, 207)) {
                    val body = response.body?.string().orEmpty()
                    val size = findDavValue(body, "getcontentlength")?.toLongOrNull() ?: 0L
                    val modified = findDavValue(body, "getlastmodified")
                        ?: response.header("Last-Modified").orEmpty()
                    info.addProperty("ok", true)
                    info.addProperty("exists", true)
                    info.addProperty("size", size)
                    info.addProperty("file_size", size)
                    info.addProperty("last_modified", modified)
                    return@withContext info
                }
                if (response.code == 404) {
                    info.addProperty("ok", true)
                    info.addProperty("message", "远程备份文件尚未创建")
                    return@withContext info
                }
            }

            execute(Request.Builder().url(fileUrl).head(), raw).use { response ->
                info.addProperty("status_code", response.code)
                when (response.code) {
                    200 -> {
                        val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        info.addProperty("ok", true)
                        info.addProperty("exists", true)
                        info.addProperty("size", size)
                        info.addProperty("file_size", size)
                        info.addProperty(
                            "last_modified",
                            response.header("Last-Modified").orEmpty()
                        )
                    }

                    404 -> {
                        info.addProperty("ok", true)
                        info.addProperty("message", "远程备份文件尚未创建")
                    }

                    403 -> {
                        // 信息查询受限不代表备份失败。
                        info.addProperty("ok", true)
                        info.addProperty("exists", true)
                        info.addProperty(
                            "message",
                            "服务器不允许读取文件元信息，备份和恢复仍可使用"
                        )
                    }

                    else -> info.addProperty("message", "HTTP ${response.code}")
                }
            }
            info
        } catch (e: Exception) {
            info.apply { addProperty("message", readableError("查询失败", e)) }
        }
    }

    suspend fun backup(request: WebDavBackupRequest): JsonObject =
        withContext(Dispatchers.IO) {
            val raw = rawConfig()
            val encryptionPassword =
                request.password?.trim().takeUnless { it.isNullOrBlank() }
                    ?: raw.encryptionPassword
            require(encryptionPassword.isNotBlank()) {
                "未设置加密密码，请先在配置中填写或本次提供"
            }
            val folder = ensureFolder(raw)
            if (!folder.ok) error(folder.message)

            try {
                val archive = buildArchive(request.includePortraits == true)
                try {
                    val payload = File.createTempFile(
                        "webdav-backup-",
                        ".nbotcfg",
                        appContext.cacheDir
                    )
                    try {
                    LocalWebDavArchiveCodec.encryptFile(
                        archive = archive,
                        password = encryptionPassword,
                        profileName = prefs.activeDbName,
                        output = payload
                    )
                    val fileUrl = resolveFileUrl(raw.url)
                    execute(
                        Request.Builder().url(fileUrl).put(payload.asRequestBody(BINARY_MEDIA_TYPE)),
                        raw
                    ).use { response ->
                        if (response.code !in listOf(200, 201, 204)) {
                            error("WebDAV 服务器拒绝上传 (HTTP ${response.code})")
                        }
                        val now = nowIso()
                        val modified = response.header("Last-Modified").orEmpty()
                        updateStatus(
                            lastBackupAt = now,
                            lastError = "",
                            lastFileSize = payload.length(),
                            lastModified = modified
                        )
                        successJson().apply {
                            addProperty("ok", true)
                            addProperty("size", payload.length())
                            addProperty("uploaded_at", now)
                            addProperty("status_code", response.code)
                            addProperty("last_modified", modified)
                            addProperty("file_url", fileUrl)
                        }
                    }
                    } finally {
                        payload.delete()
                    }
                } finally {
                    archive.delete()
                }
            } catch (e: Exception) {
                val message = readableError("备份失败", e)
                updateStatus(lastError = message)
                throw IllegalStateException(message, e)
            }
        }

    suspend fun sync(request: WebDavBackupRequest): JsonObject =
        withContext(Dispatchers.IO) {
            val raw = rawConfig()
            val encryptionPassword =
                request.password?.trim().takeUnless { it.isNullOrBlank() }
                    ?: raw.encryptionPassword
            require(encryptionPassword.isNotBlank()) {
                "未设置加密密码，请先在配置中填写或本次提供"
            }

            try {
                val fileUrl = resolveFileUrl(raw.url)
                val stagingDirectory = File(
                    appContext.cacheDir,
                    "webdav-sync-${UUID.randomUUID()}"
                ).apply { check(mkdirs()) { "无法创建 WebDAV 暂存目录" } }
                try {
                    val payload = File(stagingDirectory, "remote.nbotcfg")
                    val archive = File(stagingDirectory, "archive.zip")
                    execute(
                        Request.Builder().url(fileUrl).get(),
                        raw
                    ).use { response ->
                        when (response.code) {
                            200 -> {
                                val body = response.body ?: error("WebDAV 返回空响应")
                                require(body.contentLength() <= MAX_ENCRYPTED_ARCHIVE_SIZE || body.contentLength() < 0L) {
                                    "远程备份文件超过大小限制"
                                }
                                body.byteStream().use { input ->
                                    copyBounded(input, payload, MAX_ENCRYPTED_ARCHIVE_SIZE)
                                }
                            }
                            404 -> error("远程备份文件不存在，请先执行备份")
                            else -> error("WebDAV 服务器返回异常 (HTTP ${response.code})")
                        }
                    }
                    require(payload.length() > 0L) { "远程备份文件内容为空" }
                    LocalWebDavArchiveCodec.decryptFile(payload, encryptionPassword, archive)
                    restoreArchive(archive, request.includePortraits == true)

                    val now = nowIso()
                    updateStatus(
                        lastSyncAt = now,
                        lastError = "",
                        lastFileSize = payload.length()
                    )
                    successJson().apply {
                        addProperty("ok", true)
                        addProperty("size", payload.length())
                        addProperty("synced_at", now)
                        addProperty("file_url", fileUrl)
                    }
                } finally {
                    stagingDirectory.deleteRecursively()
                }
            } catch (e: Exception) {
                val message = readableError("恢复失败", e)
                updateStatus(lastError = message)
                throw IllegalStateException(message, e)
            }
        }

    /** 下载、解密并校验全量包，在正式覆盖本地档案前返回内容概览。 */
    suspend fun previewSync(request: WebDavBackupRequest): JsonObject = withContext(Dispatchers.IO) {
        val raw = rawConfig()
        val encryptionPassword = request.password?.trim().takeUnless { it.isNullOrBlank() }
            ?: raw.encryptionPassword
        require(encryptionPassword.isNotBlank()) {
            "未设置加密密码，请先在配置中填写或本次提供"
        }
        val stagingDirectory = File(appContext.cacheDir, "webdav-preview-${UUID.randomUUID()}")
            .apply { check(mkdirs()) { "无法创建 WebDAV 预览目录" } }
        try {
            val payload = File(stagingDirectory, "remote.nbotcfg")
            val archive = File(stagingDirectory, "archive.zip")
            val fileUrl = resolveFileUrl(raw.url)
            execute(Request.Builder().url(fileUrl).get(), raw).use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body ?: error("WebDAV 返回空响应")
                        require(body.contentLength() <= MAX_ENCRYPTED_ARCHIVE_SIZE || body.contentLength() < 0L) {
                            "远程备份文件超过大小限制"
                        }
                        body.byteStream().use { input -> copyBounded(input, payload, MAX_ENCRYPTED_ARCHIVE_SIZE) }
                    }
                    404 -> error("远程备份文件不存在，请先执行备份")
                    else -> error("WebDAV 服务器返回异常 (HTTP ${response.code})")
                }
            }
            require(payload.length() > 0L) { "远程备份文件内容为空" }
            LocalWebDavArchiveCodec.decryptFile(payload, encryptionPassword, archive)
            val entries = unzipToFiles(archive, stagingDirectory)
            validateWebDavCatalogFiles(entries)
            val database = entries[ENTRY_DATABASE] ?: error("备份包中缺少数据库")
            require(database.length() >= SQLITE_HEADER.size && FileInputStream(database).use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
            }) { "备份包中的数据库格式无效" }
            val metadata = entries[ENTRY_METADATA]?.let { file ->
                JsonParser.parseString(String(readBounded(file, MAX_SMALL_ENTRY_BYTES), Charsets.UTF_8)).asJsonObject
            }
            val summary = scanArchiveSummary(database, entries, metadata)
            summary.apply {
                addProperty("ok", true)
                addProperty("file_url", fileUrl)
                addProperty("encrypted_size", payload.length())
            }
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private fun scanArchiveSummary(
        databaseFile: File,
        entries: Map<String, File>,
        metadata: JsonObject?
    ): JsonObject {
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        val counts = linkedMapOf<String, Pair<Int, Int>>()
        try {
            PortableDataCategory.entries.forEach { category ->
                var rows = 0
                category.tables.forEach { table ->
                    val exists = database.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table)
                    ).use { it.moveToFirst() }
                    if (exists) {
                        val safeTable = safeSqlIdentifier(table)
                        rows += database.rawQuery("SELECT COUNT(*) FROM `$safeTable`", null).use {
                            if (it.moveToFirst()) it.getInt(0) else 0
                        }
                    }
                }
                counts[category.id] = rows to 0
            }
        } finally {
            database.close()
        }
        entries.keys.forEach { path ->
            val categoryId = when {
                path.startsWith(ENTRY_CATALOG_FILES_PREFIX) ->
                    path.removePrefix(ENTRY_CATALOG_FILES_PREFIX).substringBefore('/')
                path.startsWith(ENTRY_MESSAGE_AUDIO_PREFIX) || path.startsWith(ENTRY_PORTRAITS_PREFIX) ||
                    path.startsWith(ENTRY_LOCAL_PORTRAITS_PREFIX) ||
                    path.startsWith(ENTRY_WORLD_BOOK_COVERS_PREFIX) -> PortableDataCategory.MEDIA.id
                path.startsWith(ENTRY_STICKERS_PREFIX) -> PortableDataCategory.STICKERS.id
                path == ENTRY_GLOBAL_MEMORY -> PortableDataCategory.GLOBAL_MEMORY.id
                else -> null
            } ?: return@forEach
            val (rows, files) = counts[categoryId] ?: (0 to 0)
            counts[categoryId] = rows to files + 1
        }
        val totalRows = counts.values.sumOf { it.first }
        val totalFiles = counts.values.sumOf { it.second }
        return JsonObject().apply {
            addProperty("archive_version", metadata?.get("version")?.asInt ?: 1)
            addProperty("archive_id", metadata?.get("archive_id")?.asString.orEmpty())
            addProperty("created_at", metadata?.get("created_at")?.asString.orEmpty())
            addProperty("database_version", metadata?.get("database_version")?.asInt ?: 0)
            addProperty("includes_credentials", entries.containsKey(ENTRY_CREDENTIALS))
            addProperty("total_rows", totalRows)
            addProperty("total_files", totalFiles)
            add("categories", JsonArray().apply {
                PortableDataCategory.entries.forEach { category ->
                    val (rows, files) = counts[category.id] ?: (0 to 0)
                    if (rows > 0 || files > 0) add(JsonObject().apply {
                        addProperty("id", category.id)
                        addProperty("rows", rows)
                        addProperty("files", files)
                    })
                }
            })
        }
    }

    /**
     * 双向增量同步。远端只追加本次变化的加密 delta，manifest 保存每条记录的最新版本指针。
     * 本地和远端同时修改时按 updatedAt 选择较新的版本，并统计冲突数量。
     */
    suspend fun incrementalSync(request: WebDavBackupRequest): JsonObject = withContext(Dispatchers.IO) {
        var retries = 0
        while (true) {
            try {
                return@withContext incrementalSyncAttempt(request)
            } catch (error: IllegalStateException) {
                if (error.cause !is ConcurrentManifestUpdateException || retries >= MAX_SYNC_CAS_RETRIES) {
                    throw error
                }
                retries++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("增量同步重试结束")
    }

    private suspend fun incrementalSyncAttempt(request: WebDavBackupRequest): JsonObject =
        withContext(Dispatchers.IO) {
            val raw = rawConfig()
            val encryptionPassword =
                request.password?.trim().takeUnless { it.isNullOrBlank() }
                    ?: raw.encryptionPassword
            require(encryptionPassword.isNotBlank()) {
                "未设置加密密码，请先在配置中填写"
            }
            val folder = ensureFolder(raw)
            if (!folder.ok) error(folder.message)
            ensureIncrementalFolders(raw)
            ensureConditionalWriteSupport(raw)

            try {
                val profileName = prefs.activeDbName
                val rootUrl = resolveIncrementalRootUrl(raw.url, profileName)
                val profileId = LocalDataCatalog.stableProfileId(appContext, profileName)
                val baselineKey = "$KEY_SYNC_V2_BASE_PREFIX$profileId"
                val journalKey = "$KEY_SYNC_V2_JOURNAL_PREFIX$profileId"
                val stagingDirectory = File(
                    appContext.cacheDir,
                    "webdav-incremental-${UUID.randomUUID()}"
                ).apply { check(mkdirs()) { "无法创建增量同步暂存目录" } }
                try {
                recoverPendingIncrementalSync(
                    profileName = profileName,
                    rootUrl = rootUrl,
                    raw = raw,
                    password = encryptionPassword,
                    stagingDirectory = stagingDirectory,
                    baselineKey = baselineKey,
                    journalKey = journalKey
                )
                migrateLegacyIncrementalNamespace(
                    profileName = profileName,
                    profileId = profileId,
                    rootUrl = rootUrl,
                    raw = raw,
                    password = encryptionPassword,
                    stagingDirectory = stagingDirectory,
                    newBaselineKey = baselineKey
                )
                val manifestUrl = "${rootUrl}manifest.nksync"
                var remoteManifestPayload: ByteArray? = null
                var remoteManifestEtag: String? = null
                val remoteManifest = execute(
                    Request.Builder().url(manifestUrl).get(),
                    raw
                ).use { response ->
                    when (response.code) {
                        200 -> {
                            remoteManifestPayload = response.body?.bytes() ?: byteArrayOf()
                            remoteManifestEtag = response.header("ETag")
                            val plain = LocalWebDavArchiveCodec.decrypt(
                                remoteManifestPayload!!,
                                encryptionPassword
                            )
                            gson.fromJson(
                                plain.toString(Charsets.UTF_8),
                                WebDavSyncManifest::class.java
                            ) ?: WebDavSyncManifest()
                        }
                        404 -> WebDavSyncManifest()
                        else -> error("读取增量同步清单失败 (HTTP ${response.code})")
                    }
                }
                if (remoteManifest.revision > 0L && remoteManifest.coverage.isEmpty()) {
                    remoteManifest.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
                }
                require(remoteManifest.version <= 2) { "增量清单版本高于当前应用支持版本" }
                require(remoteManifestPayload == null || remoteManifest.version == 2) {
                    "v2 同步空间中发现旧格式清单；请保留远端数据并重新执行迁移"
                }
                val syncGroupId = LocalDataCatalog.stableSyncGroupId(appContext, profileName)
                require(remoteManifest.syncGroupId.isBlank() || remoteManifest.syncGroupId == syncGroupId) {
                    "远端同步集合 ID 与当前档案不匹配"
                }
                remoteManifest.version = 2
                if (remoteManifest.profileId.isBlank()) remoteManifest.profileId = profileId
                remoteManifest.syncGroupId = syncGroupId
                require(remoteManifestPayload == null || !remoteManifestEtag.isNullOrBlank()) {
                    "WebDAV 服务器没有返回 ETag，无法安全进行多设备增量同步"
                }

                val baseline = configPrefs.getString(baselineKey, null)
                    ?.let { json ->
                        runCatching {
                            gson.fromJson(json, WebDavSyncManifest::class.java)
                        }.getOrNull()
                    }
                    ?: WebDavSyncManifest()
                if (baseline.revision > 0L && baseline.coverage.isEmpty()) {
                    baseline.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
                }
                val db = NekobotDatabase.get(appContext, profileName)
                val localRecords = collectIncrementalRecords(db)
                val now = nowIso()
                val outgoing = linkedMapOf<String, WebDavSyncRecord>()
                val incoming = linkedMapOf<String, WebDavSyncRecord>()
                val remoteNeeded = linkedMapOf<String, WebDavSyncIndexEntry>()
                var conflicts = 0
                val conflictDetails = JsonArray()

                fun localRecordFor(key: String): WebDavSyncRecord? =
                    localRecords[key] ?: baseline.records[key]
                        ?.takeIf { key.substringBefore(':') in baseline.coverage }
                        ?.takeUnless {
                            it.deleted || key.substringBefore(':') in APPEND_ONLY_INCREMENTAL_TYPES
                        }
                        ?.let { LocalWebDavIncrementalLogic.tombstone(key, now) }

                val allKeys = linkedSetOf<String>().apply {
                    addAll(baseline.records.keys)
                    addAll(localRecords.keys)
                    addAll(remoteManifest.records.keys)
                }
                allKeys.forEach { key ->
                    val baseIndex = baseline.records[key]
                    val localRecord = localRecordFor(key)
                    val localCandidate = localRecord?.let {
                        LocalWebDavIncrementalLogic.indexOf(it, "")
                    }
                    val remoteIndex = remoteManifest.records[key]
                    val localType = key.substringBefore(':')
                    val localChanged = !(
                        localCandidate == null && baseIndex != null && localType !in baseline.coverage
                    ) && LocalWebDavIncrementalLogic.changed(localCandidate, baseIndex)
                    val remoteChanged = LocalWebDavIncrementalLogic.changed(remoteIndex, baseIndex)

                    when {
                        localChanged && !remoteChanged && localRecord != null -> {
                            outgoing[key] = localRecord
                        }
                        !localChanged && remoteChanged && remoteIndex != null -> {
                            remoteNeeded[key] = remoteIndex
                        }
                        localChanged && remoteChanged && localRecord != null && remoteIndex != null -> {
                            if (
                                localCandidate?.hash == remoteIndex.hash &&
                                localCandidate.deleted == remoteIndex.deleted
                            ) {
                                return@forEach
                            }
                            conflicts++
                            val localWins = when {
                                localRecord.updatedAt > remoteIndex.updatedAt -> true
                                localRecord.updatedAt < remoteIndex.updatedAt -> false
                                else -> localRecord.hash >= remoteIndex.hash
                            }
                            conflictDetails.add(JsonObject().apply {
                                addProperty("key", key)
                                addProperty("type", localRecord.type)
                                addProperty("id", localRecord.id)
                                addProperty("local_updated_at", localRecord.updatedAt)
                                addProperty("remote_updated_at", remoteIndex.updatedAt)
                                addProperty("local_hash", localRecord.hash)
                                addProperty("remote_hash", remoteIndex.hash)
                                addProperty("resolution", if (localWins) "local" else "remote")
                            })
                            val localConflictId = UUID.randomUUID().toString()
                            val remoteConflictId = UUID.randomUUID().toString()
                            conflictDetails.get(conflictDetails.size() - 1).asJsonObject.apply {
                                addProperty("local_conflict_copy_key", "$TYPE_CONFLICT_COPY:$localConflictId")
                                addProperty("remote_conflict_copy_key", "$TYPE_CONFLICT_COPY:$remoteConflictId")
                            }
                            if (localWins) {
                                outgoing[key] = localRecord
                            }
                            // 两侧版本都先暂存，用户可以稍后在历史页明确选择，不依赖时间戳自动丢弃一侧。
                            remoteNeeded[key] = remoteIndex
                        }
                    }
                }

                incoming.putAll(
                    loadRemoteIncrementalRecords(
                        remoteNeeded,
                        rootUrl,
                        raw,
                        encryptionPassword,
                        stagingDirectory
                    )
                )
                conflictDetails.forEach { element ->
                    val conflict = element.asJsonObject
                    val key = conflict.get("key")?.asString.orEmpty()
                    val localRecord = localRecordFor(key)
                        ?: error("冲突副本缺少本地记录：$key")
                    val remoteRecord = incoming[key]
                        ?: error("冲突副本缺少远端记录：$key")
                    val localCopyKey = conflict.get("local_conflict_copy_key")?.asString.orEmpty()
                    val remoteCopyKey = conflict.get("remote_conflict_copy_key")?.asString.orEmpty()
                    val localId = localCopyKey.substringAfter(':')
                    val remoteId = remoteCopyKey.substringAfter(':')
                    outgoing[localCopyKey] = createConflictCopy(localRecord, key, "local", localId, now)
                    outgoing[remoteCopyKey] = createConflictCopy(remoteRecord, key, "remote", remoteId, now)
                    if (conflict.get("resolution")?.asString == "local") incoming.remove(key)
                }
                remoteManifest.coverage.addAll(SUPPORTED_INCREMENTAL_TYPES)
                var uploadedBytes = 0L
                if (outgoing.isNotEmpty()) {
                    val nextRevision = remoteManifest.revision + 1L
                    val deltaName = "delta-${nextRevision}-${UUID.randomUUID()}.nksync"
                    val uploadedRecords = mutableListOf<WebDavSyncRecord>()
                    val uploadedByKey = linkedMapOf<String, WebDavSyncRecord>()
                    outgoing.values.forEach { record ->
                        val originalKey = if (record.type == TYPE_CONFLICT_COPY) {
                            record.value?.get("original_key")?.takeUnless { it.isJsonNull }?.asString
                        } else null
                        val previousUpload = originalKey?.let(uploadedByKey::get)
                        val canReuseAttachments = previousUpload != null &&
                            record.attachments.keys == previousUpload.attachments.keys &&
                            record.attachments.all { (name, reference) ->
                                previousUpload.attachments[name]?.let { uploaded ->
                                    uploaded.size == reference.size && uploaded.sha256 == reference.sha256
                                } == true
                            }
                        val uploaded = if (canReuseAttachments) {
                            record.copy(
                                attachments = requireNotNull(previousUpload).attachments,
                                localAttachments = emptyMap()
                            )
                        } else {
                            uploadRecordAttachments(
                                record,
                                rootUrl,
                                raw,
                                encryptionPassword,
                                profileName,
                                stagingDirectory
                            )
                        }
                        uploadedRecords += uploaded
                        uploadedByKey[record.key] = uploaded
                    }
                    uploadedBytes += outgoing.values
                        .flatMap { it.localAttachments.values }
                        .distinctBy { it.absolutePath }
                        .sumOf(File::length)
                    val delta = WebDavSyncDelta(
                        revision = nextRevision,
                        deviceId = getOrCreateSyncDeviceId(),
                        createdAt = now,
                        records = uploadedRecords
                    )
                    val encryptedDelta = writeEncryptedIncrementalDelta(
                        delta,
                        encryptionPassword,
                        profileName,
                        stagingDirectory
                    )
                    putIncrementalFile("$rootUrl$deltaName", encryptedDelta, raw)
                    uploadedBytes += encryptedDelta.length()

                    if (remoteManifestPayload != null && remoteManifest.revision > 0L) {
                        val historyUrl =
                            "${rootUrl}history/manifest-${remoteManifest.revision}.nksync"
                        putIncrementalFile(historyUrl, remoteManifestPayload!!, raw)
                    }
                    outgoing.values.forEach { record ->
                        val uploadedRecord = requireNotNull(uploadedByKey[record.key])
                        remoteManifest.records[record.key] =
                            LocalWebDavIncrementalLogic.indexOf(uploadedRecord, deltaName)
                    }
                    remoteManifest.revision = nextRevision
                    remoteManifest.updatedAt = now
                    val encryptedManifest = LocalWebDavArchiveCodec.encrypt(
                        gson.toJson(remoteManifest).toByteArray(Charsets.UTF_8),
                        encryptionPassword,
                        profileName
                    )
                    val builder = Request.Builder()
                        .url(manifestUrl)
                        .put(encryptedManifest.toRequestBody(BINARY_MEDIA_TYPE))
                    if (!remoteManifestEtag.isNullOrBlank()) {
                        builder.header("If-Match", remoteManifestEtag!!)
                    } else if (remoteManifestPayload == null) {
                        builder.header("If-None-Match", "*")
                    }
                    execute(builder, raw).use { response ->
                        if (response.code == 412) {
                            throw ConcurrentManifestUpdateException()
                        }
                        if (response.code !in listOf(200, 201, 204)) {
                            error("更新增量同步清单失败 (HTTP ${response.code})")
                        }
                    }
                    uploadedBytes += encryptedManifest.size
                }

                val journal = WebDavSyncJournal(
                    profileName = profileName,
                    targetManifest = remoteManifest.copy(
                        records = remoteManifest.records.toMutableMap()
                    ),
                    incomingKeys = incoming.keys.toList(),
                    conflictDetailsJson = gson.toJson(conflictDetails),
                    updatedAt = now
                )
                check(
                    configPrefs.edit()
                        .putString(journalKey, gson.toJson(journal))
                        .commit()
                ) { "无法保存增量同步恢复日志，尚未应用远端记录" }
                if (incoming.isNotEmpty()) {
                    applyIncrementalRecords(db, incoming.values.toList())
                }
                check(
                    configPrefs.edit()
                        .putString(baselineKey, gson.toJson(remoteManifest))
                        .putString(KEY_LAST_CONFLICTS, gson.toJson(conflictDetails))
                        .remove(journalKey)
                        .commit()
                ) { "本地数据已应用，但同步基线提交失败；下次同步将重放恢复日志" }
                pruneIncrementalHistory(
                    raw = raw,
                    rootUrl = rootUrl,
                    password = encryptionPassword,
                    currentManifest = remoteManifest,
                    maxVersions = configPrefs.getInt(
                        KEY_INCREMENTAL_SYNC_MAX_VERSIONS,
                        DEFAULT_INCREMENTAL_SYNC_MAX_VERSIONS
                    )
                )
                updateStatus(
                    lastSyncAt = now,
                    lastError = "",
                    lastFileSize = uploadedBytes.takeIf { it > 0L }
                )
                successJson().apply {
                    addProperty("ok", true)
                    addProperty("synced_at", now)
                    addProperty("revision", remoteManifest.revision)
                    addProperty("uploaded", outgoing.size)
                    addProperty("downloaded", incoming.size)
                    addProperty("conflicts", conflicts)
                    add("conflict_details", conflictDetails)
                    addProperty("uploaded_bytes", uploadedBytes)
                    addProperty("incremental", true)
                }
                } finally {
                    stagingDirectory.deleteRecursively()
                }
            } catch (e: Exception) {
                val message = readableError("增量同步失败", e)
                updateStatus(lastError = message)
                throw IllegalStateException(message, e)
            }
        }

    private suspend fun recoverPendingIncrementalSync(
        profileName: String,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        stagingDirectory: File,
        baselineKey: String,
        journalKey: String
    ) {
        val json = configPrefs.getString(journalKey, null) ?: return
        val journal = runCatching {
            gson.fromJson(json, WebDavSyncJournal::class.java)
        }.getOrNull() ?: error("增量同步恢复日志损坏，请保留数据并联系支持")
        require(journal.profileName == profileName) { "增量同步恢复日志所属档案不匹配" }

        val requested = journal.incomingKeys.associateWith { key ->
            journal.targetManifest.records[key]
                ?: error("增量同步恢复日志缺少远端记录：$key")
        }
        val records = loadRemoteIncrementalRecords(
            requested = requested,
            rootUrl = rootUrl,
            raw = raw,
            password = password,
            stagingDirectory = stagingDirectory
        )
        if (records.isNotEmpty()) {
            val db = NekobotDatabase.get(appContext, profileName)
            applyIncrementalRecords(db, records.values.toList())
        }

        check(
            configPrefs.edit()
                .putString(baselineKey, gson.toJson(journal.targetManifest))
                .putString(KEY_LAST_CONFLICTS, journal.conflictDetailsJson)
                .remove(journalKey)
                .commit()
        ) { "同步修订已恢复，但本机基线提交失败；下次同步将继续恢复" }
    }

    /** 首次进入 v2 空间时复制 v1 清单、历史、delta 与所引用对象；旧空间保持只读。 */
    private fun migrateLegacyIncrementalNamespace(
        profileName: String,
        profileId: String,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        stagingDirectory: File,
        newBaselineKey: String
    ) {
        val newManifestUrl = "${rootUrl}manifest.nksync"
        val newManifestExists = execute(Request.Builder().url(newManifestUrl).get(), raw).use {
            when (it.code) {
                200 -> true
                404 -> false
                else -> error("检查 v2 增量清单失败 (HTTP ${it.code})")
            }
        }
        if (newManifestExists) return

        val legacyRoot = resolveLegacyIncrementalRootUrl(raw.url, profileName)
        val legacyCurrentPayload = execute(
            Request.Builder().url("${legacyRoot}manifest.nksync").get(),
            raw
        ).use { response ->
            when (response.code) {
                200 -> response.body?.bytes() ?: byteArrayOf()
                404 -> return
                else -> error("读取 v1 增量清单失败 (HTTP ${response.code})")
            }
        }
        val legacyCurrent = parseIncrementalManifest(legacyCurrentPayload, password)
        require(legacyCurrent.version <= 1) { "旧增量清单版本高于当前迁移器支持版本" }
        if (legacyCurrent.revision > 0L && legacyCurrent.coverage.isEmpty()) {
            legacyCurrent.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
        }

        val historicalManifests = linkedMapOf<Long, Pair<ByteArray, WebDavSyncManifest>>()
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop/></D:propfind>
        """.trimIndent().toRequestBody(XML_MEDIA_TYPE)
        execute(
            Request.Builder().url("${legacyRoot}history/")
                .method("PROPFIND", propfindBody).header("Depth", "1"),
            raw
        ).use { response ->
            if (response.code !in listOf(200, 207, 404)) {
                error("读取 v1 历史目录失败 (HTTP ${response.code})")
            }
            if (response.code != 404) {
                val revisions = Regex("manifest-(\\d+)\\.nksync", RegexOption.IGNORE_CASE)
                    .findAll(response.body?.string().orEmpty())
                    .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
                    .distinct()
                    .take(MAX_INCREMENTAL_SYNC_MAX_VERSIONS)
                    .toList()
                revisions.forEach { revision ->
                    val payload = execute(
                        Request.Builder().url("${legacyRoot}history/manifest-$revision.nksync").get(),
                        raw
                    ).use { historyResponse ->
                        if (historyResponse.code != 200) return@forEach
                        historyResponse.body?.bytes() ?: byteArrayOf()
                    }
                    val manifest = parseIncrementalManifest(payload, password)
                    if (manifest.revision == revision && manifest.version <= 1) {
                        if (manifest.coverage.isEmpty()) manifest.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
                        historicalManifests[revision] = payload to manifest
                    }
                }
            }
        }

        val snapshots = historicalManifests.values.map { it.second } + legacyCurrent
        val deltaKeys = linkedMapOf<String, MutableSet<String>>()
        snapshots.forEach { manifest ->
            manifest.records.forEach { (key, index) ->
                if (!index.deleted) {
                    require(index.delta.matches(Regex("delta-[A-Za-z0-9-]+\\.nksync"))) {
                        "v1 清单包含无效 delta 路径：$key"
                    }
                    deltaKeys.getOrPut(index.delta, ::linkedSetOf).add(key)
                }
            }
        }

        val copiedObjects = linkedSetOf<String>()
        deltaKeys.forEach { (deltaName, keys) ->
            val encrypted = File(stagingDirectory, "v1-$deltaName")
            val plain = File(stagingDirectory, "v1-${UUID.randomUUID()}.json")
            try {
                downloadIncrementalFile(
                    "$legacyRoot$deltaName",
                    encrypted,
                    raw,
                    MAX_INCREMENTAL_ENCRYPTED_DELTA_BYTES
                )
                putIncrementalFile("$rootUrl$deltaName", encrypted, raw)
                LocalWebDavArchiveCodec.decryptFile(
                    encrypted,
                    password,
                    plain,
                    MAX_INCREMENTAL_DELTA_BYTES
                )
                keys.toList().chunked(128).forEach { batch ->
                    val records = WebDavSyncDeltaStreamReader.readSelected(plain, batch.toSet(), stagingDirectory)
                    records.values.forEach { record ->
                        record.attachments.values.flatMap(WebDavSyncFileRef::chunks).forEach { chunk ->
                            require(chunk.matches(Regex("objects/[A-Za-z0-9-]+\\.nksync"))) {
                                "v1 delta 包含无效文件对象路径"
                            }
                            if (copiedObjects.add(chunk)) {
                                val objectFile = File(stagingDirectory, "v1-object-${UUID.randomUUID()}.nbotcfg")
                                try {
                                    downloadIncrementalFile(
                                        "$legacyRoot$chunk",
                                        objectFile,
                                        raw,
                                        MAX_ENCRYPTED_SYNC_CHUNK_BYTES
                                    )
                                    putIncrementalFile("$rootUrl$chunk", objectFile, raw)
                                } finally {
                                    objectFile.delete()
                                }
                            }
                        }
                    }
                }
            } finally {
                encrypted.delete()
                plain.delete()
            }
        }

        val targetProfileId = profileId
        val targetSyncGroupId = LocalDataCatalog.stableSyncGroupId(appContext, profileName)
        historicalManifests.forEach { (revision, pair) ->
            val migrated = pair.second.apply {
                version = 2
                this.profileId = targetProfileId
                this.syncGroupId = targetSyncGroupId
            }
            val encrypted = LocalWebDavArchiveCodec.encrypt(
                gson.toJson(migrated).toByteArray(Charsets.UTF_8),
                password,
                profileName
            )
            putIncrementalFile("${rootUrl}history/manifest-$revision.nksync", encrypted, raw)
        }
        legacyCurrent.version = 2
        legacyCurrent.profileId = targetProfileId
        legacyCurrent.syncGroupId = targetSyncGroupId
        val migratedCurrent = LocalWebDavArchiveCodec.encrypt(
            gson.toJson(legacyCurrent).toByteArray(Charsets.UTF_8),
            password,
            profileName
        )
        val created = execute(
            Request.Builder().url(newManifestUrl)
                .put(migratedCurrent.toRequestBody(BINARY_MEDIA_TYPE))
                .header("If-None-Match", "*"),
            raw
        )
        created.use {
            if (it.code == 412) return
            if (it.code !in listOf(200, 201, 204)) {
                error("提交 v2 初始清单失败 (HTTP ${it.code})")
            }
        }

        val legacyBaselineKey = "$KEY_SYNC_BASE_PREFIX$profileName"
        if (!configPrefs.contains(newBaselineKey)) {
            val baseline = configPrefs.getString(legacyBaselineKey, null)?.let { json ->
                runCatching { gson.fromJson(json, WebDavSyncManifest::class.java) }.getOrNull()
            }
            if (baseline != null) {
                if (baseline.coverage.isEmpty()) baseline.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
                baseline.version = 2
                baseline.profileId = targetProfileId
                baseline.syncGroupId = targetSyncGroupId
                check(configPrefs.edit().putString(newBaselineKey, gson.toJson(baseline)).commit()) {
                    "v2 清单已迁移，但无法迁移本机同步基线"
                }
            }
        }
    }

    private fun parseIncrementalManifest(payload: ByteArray, password: String): WebDavSyncManifest {
        val plain = LocalWebDavArchiveCodec.decrypt(payload, password)
        return gson.fromJson(plain.toString(Charsets.UTF_8), WebDavSyncManifest::class.java)
            ?: error("增量同步清单格式无效")
    }

    /** 列出当前增量清单、历史修订以及最近一次同步的逐条冲突决策。 */
    suspend fun incrementalHistory(request: WebDavBackupRequest): JsonObject = withContext(Dispatchers.IO) {
        val raw = rawConfig()
        val encryptionPassword = request.password?.trim().takeUnless { it.isNullOrBlank() }
            ?: raw.encryptionPassword
        require(encryptionPassword.isNotBlank()) { "未设置加密密码" }
        val folder = ensureFolder(raw)
        if (!folder.ok) error(folder.message)
        ensureIncrementalFolders(raw)

        val profileName = prefs.activeDbName
        val rootUrl = resolveIncrementalRootUrl(raw.url, profileName)
        val migrationDirectory = File(
            appContext.cacheDir,
            "webdav-history-migration-${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建历史迁移暂存目录" } }
        try {
            migrateLegacyIncrementalNamespace(
                profileName = profileName,
                profileId = LocalDataCatalog.stableProfileId(appContext, profileName),
                rootUrl = rootUrl,
                raw = raw,
                password = encryptionPassword,
                stagingDirectory = migrationDirectory,
                newBaselineKey = "$KEY_SYNC_V2_BASE_PREFIX${LocalDataCatalog.stableProfileId(appContext, profileName)}"
            )
        } finally {
            migrationDirectory.deleteRecursively()
        }
        val manifestUrl = "${rootUrl}manifest.nksync"
        var currentManifest: WebDavSyncManifest? = null
        execute(Request.Builder().url(manifestUrl).get(), raw).use { response ->
            when (response.code) {
                200 -> {
                    val encrypted = response.body?.bytes() ?: byteArrayOf()
                    val plain = LocalWebDavArchiveCodec.decrypt(encrypted, encryptionPassword)
                    currentManifest = gson.fromJson(plain.toString(Charsets.UTF_8), WebDavSyncManifest::class.java)
                    require(currentManifest?.version == 2) { "当前 WebDAV 同步清单不是 v2 格式" }
                }
                404 -> Unit
                else -> error("读取增量同步清单失败 (HTTP ${response.code})")
            }
        }

        val historyUrl = "${rootUrl}history/"
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop><D:getcontentlength/><D:getlastmodified/></D:prop>
            </D:propfind>
        """.trimIndent().toRequestBody(XML_MEDIA_TYPE)
        val entries = linkedMapOf<Long, JsonObject>()
        execute(
            Request.Builder().url(historyUrl).method("PROPFIND", propfindBody).header("Depth", "1"),
            raw
        ).use { response ->
            if (response.code !in listOf(200, 207, 404)) {
                error("读取 WebDAV 历史目录失败 (HTTP ${response.code})")
            }
            if (response.code != 404) {
                val xml = response.body?.string().orEmpty()
                val responseBlocks = Regex(
                    "<(?:[A-Za-z]+:)?response\\b[\\s\\S]*?</(?:[A-Za-z]+:)?response>",
                    RegexOption.IGNORE_CASE
                ).findAll(xml)
                responseBlocks.forEach { match ->
                    val block = match.value
                    val revision = Regex("manifest-(\\d+)\\.nksync", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.getOrNull(1)?.toLongOrNull()
                        ?: return@forEach
                    val size = Regex(
                        "<(?:[A-Za-z]+:)?getcontentlength[^>]*>([^<]*)",
                        RegexOption.IGNORE_CASE
                    ).find(block)?.groupValues?.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                    val modified = Regex(
                        "<(?:[A-Za-z]+:)?getlastmodified[^>]*>([^<]*)",
                        RegexOption.IGNORE_CASE
                    ).find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    entries[revision] = JsonObject().apply {
                        addProperty("revision", revision)
                        addProperty("current", false)
                        addProperty("size", size)
                        addProperty("last_modified", modified)
                    }
                }
            }
        }
        currentManifest?.let { manifest ->
            entries[manifest.revision] = JsonObject().apply {
                addProperty("revision", manifest.revision)
                addProperty("current", true)
                addProperty("updated_at", manifest.updatedAt)
                addProperty("record_count", manifest.records.size)
            }
        }
        val revisions = JsonArray().apply {
            entries.toSortedMap(compareByDescending<Long> { it }).values.forEach(::add)
        }
        val storedConflicts = configPrefs.getString(KEY_LAST_CONFLICTS, null)
            ?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
            ?: JsonArray()
        val remoteConflicts = currentManifest?.let { manifest ->
            val conflictDirectory = File(
                appContext.cacheDir,
                "webdav-conflict-index-${UUID.randomUUID()}"
            ).apply { check(mkdirs()) { "无法创建冲突列表暂存目录" } }
            try {
                readRemoteConflictDetails(manifest, rootUrl, raw, encryptionPassword, conflictDirectory)
            } finally {
                conflictDirectory.deleteRecursively()
            }
        } ?: JsonArray()
        val conflictsByKey = linkedMapOf<String, JsonObject>()
        storedConflicts.forEach { element ->
            val detail = element.asJsonObject
            val key = detail.get("key")?.asString.orEmpty()
            if (key.isNotBlank()) conflictsByKey[key] = detail
        }
        remoteConflicts.forEach { element ->
            val detail = element.asJsonObject
            val key = detail.get("key")?.asString.orEmpty()
            if (key.isNotBlank()) conflictsByKey[key] = detail
        }
        val conflicts = JsonArray().apply { conflictsByKey.values.forEach(::add) }
        configPrefs.edit().putString(KEY_LAST_CONFLICTS, gson.toJson(conflicts)).apply()
        successJson().apply {
            addProperty("ok", true)
            addProperty("profile", profileName)
            addProperty("current_revision", currentManifest?.revision ?: 0L)
            addProperty("current_updated_at", currentManifest?.updatedAt.orEmpty())
            addProperty("record_count", currentManifest?.records?.size ?: 0)
            add("revisions", revisions)
            add("conflict_details", conflicts)
        }
    }

    /** 把冲突副本中的指定一侧作为新修订提交，并在本地 journal 保护下应用。 */
    suspend fun resolveIncrementalConflict(
        conflictCopyKey: String,
        request: WebDavBackupRequest
    ): JsonObject = withContext(Dispatchers.IO) {
        require(conflictCopyKey.startsWith("$TYPE_CONFLICT_COPY:")) { "冲突副本键无效" }
        val raw = rawConfig()
        val encryptionPassword = request.password?.trim().takeUnless { it.isNullOrBlank() }
            ?: raw.encryptionPassword
        require(encryptionPassword.isNotBlank()) { "未设置加密密码" }
        val folder = ensureFolder(raw)
        if (!folder.ok) error(folder.message)
        ensureIncrementalFolders(raw)

        val profileName = prefs.activeDbName
        val rootUrl = resolveIncrementalRootUrl(raw.url, profileName)
        val manifestUrl = "${rootUrl}manifest.nksync"
        val stagingDirectory = File(
            appContext.cacheDir,
            "webdav-conflict-${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建冲突恢复暂存目录" } }
        try {
            var currentPayload: ByteArray
            var currentEtag: String?
            val currentManifest = execute(Request.Builder().url(manifestUrl).get(), raw).use { response ->
                if (response.code != 200) error("读取当前增量同步清单失败 (HTTP ${response.code})")
                currentPayload = response.body?.bytes() ?: byteArrayOf()
                currentEtag = response.header("ETag")
                val plain = LocalWebDavArchiveCodec.decrypt(currentPayload, encryptionPassword)
                gson.fromJson(plain.toString(Charsets.UTF_8), WebDavSyncManifest::class.java)
                    ?: error("当前增量同步清单格式无效")
            }
            require(!currentEtag.isNullOrBlank()) {
                "WebDAV 服务器没有返回 ETag，无法安全提交冲突解决结果"
            }
            val copyIndex = currentManifest.records[conflictCopyKey]
                ?.takeUnless { it.deleted }
                ?: error("冲突副本已不存在，请刷新历史记录")
            val copyRecord = loadRemoteIncrementalRecords(
                requested = mapOf(conflictCopyKey to copyIndex),
                rootUrl = rootUrl,
                raw = raw,
                password = encryptionPassword,
                stagingDirectory = stagingDirectory
            )[conflictCopyKey] ?: error("无法读取冲突副本")
            require(copyRecord.type == TYPE_CONFLICT_COPY) { "远端记录不是冲突副本" }
            val copyValue = copyRecord.value ?: error("冲突副本内容为空")
            val originalKey = copyValue.get("original_key")?.asString.orEmpty()
            require(originalKey.isNotBlank() && originalKey.substringBefore(':') != TYPE_CONFLICT_COPY) {
                "冲突副本原始记录键无效"
            }
            val selected = gson.fromJson(
                copyValue.getAsJsonObject("record"),
                WebDavSyncRecord::class.java
            ) ?: error("冲突副本记录格式无效")
            require(selected.key == originalKey && selected.type == originalKey.substringBefore(':')) {
                "冲突副本记录与原始键不匹配"
            }
            val now = nowIso()
            val restored = selected.copy(
                updatedAt = now,
                attachments = copyRecord.attachments,
                localAttachments = copyRecord.localAttachments
            )
            val conflicts = configPrefs.getString(KEY_LAST_CONFLICTS, null)
                ?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
                ?: JsonArray()
            val remainingConflicts = JsonArray()
            var resolvedDetail: JsonObject? = null
            conflicts.forEach { element ->
                val detail = element.asJsonObject
                val detailKey = detail.get("key")?.asString.orEmpty()
                if (detailKey == originalKey) resolvedDetail = detail else remainingConflicts.add(detail)
            }
            val conflictDetail = resolvedDetail ?: error("冲突记录已被处理，请刷新历史记录")
            val relatedCopyKeys = listOf(
                conflictDetail.get("local_conflict_copy_key")?.asString,
                conflictDetail.get("remote_conflict_copy_key")?.asString,
                conflictDetail.get("conflict_copy_key")?.asString
            ).filterNotNull().filter(String::isNotBlank).distinct()

            val revision = currentManifest.revision + 1L
            val deltaName = "delta-$revision-${UUID.randomUUID()}.nksync"
            val uploaded = uploadRecordAttachments(
                restored,
                rootUrl,
                raw,
                encryptionPassword,
                profileName,
                stagingDirectory
            )
            val delta = WebDavSyncDelta(
                revision = revision,
                deviceId = getOrCreateSyncDeviceId(),
                createdAt = now,
                records = listOf(uploaded) + relatedCopyKeys.map {
                    LocalWebDavIncrementalLogic.tombstone(it, now)
                }
            )
            val encryptedDelta = writeEncryptedIncrementalDelta(
                delta,
                encryptionPassword,
                profileName,
                stagingDirectory
            )
            putIncrementalFile("$rootUrl$deltaName", encryptedDelta, raw)
            putIncrementalFile(
                "${rootUrl}history/manifest-${currentManifest.revision}.nksync",
                File(stagingDirectory, "current-manifest.nksync").apply {
                    writeBytes(currentPayload)
                },
                raw
            )

            relatedCopyKeys.forEach { copyKey ->
                currentManifest.records[copyKey] = LocalWebDavIncrementalLogic.indexOf(
                    LocalWebDavIncrementalLogic.tombstone(copyKey, now),
                    deltaName
                )
            }
            currentManifest.records[originalKey] = LocalWebDavIncrementalLogic.indexOf(uploaded, deltaName)
            currentManifest.revision = revision
            currentManifest.updatedAt = now
            val encryptedManifest = LocalWebDavArchiveCodec.encrypt(
                gson.toJson(currentManifest).toByteArray(Charsets.UTF_8),
                encryptionPassword,
                profileName
            )
            val response = execute(
                Request.Builder()
                    .url(manifestUrl)
                    .put(encryptedManifest.toRequestBody(BINARY_MEDIA_TYPE))
                    .header("If-Match", currentEtag!!),
                raw
            )
            response.use {
                if (it.code == 412) error("远端清单已变化，请刷新冲突列表后重试")
                if (it.code !in listOf(200, 201, 204)) {
                    error("提交冲突解决结果失败 (HTTP ${it.code})")
                }
            }

            val profileId = LocalDataCatalog.stableProfileId(appContext, profileName)
            val baselineKey = "$KEY_SYNC_V2_BASE_PREFIX$profileId"
            val journalKey = "$KEY_SYNC_V2_JOURNAL_PREFIX$profileId"
            val journal = WebDavSyncJournal(
                profileName = profileName,
                targetManifest = currentManifest.copy(records = currentManifest.records.toMutableMap()),
                incomingKeys = listOf(originalKey),
                conflictDetailsJson = gson.toJson(remainingConflicts),
                updatedAt = now
            )
            check(configPrefs.edit().putString(journalKey, gson.toJson(journal)).commit()) {
                "远端冲突解决已提交，但无法保存本地恢复日志；请立即重试增量同步"
            }
            applyIncrementalRecords(NekobotDatabase.get(appContext, profileName), listOf(restored))
            check(
                configPrefs.edit()
                    .putString(baselineKey, gson.toJson(currentManifest))
                    .putString(KEY_LAST_CONFLICTS, gson.toJson(remainingConflicts))
                    .remove(journalKey)
                    .commit()
            ) { "冲突版本已应用，但本地同步基线提交失败；下次同步将重放恢复日志" }
            pruneIncrementalHistory(
                raw = raw,
                rootUrl = rootUrl,
                password = encryptionPassword,
                currentManifest = currentManifest,
                maxVersions = configPrefs.getInt(
                    KEY_INCREMENTAL_SYNC_MAX_VERSIONS,
                    DEFAULT_INCREMENTAL_SYNC_MAX_VERSIONS
                )
            )
            successJson().apply {
                addProperty("ok", true)
                addProperty("revision", revision)
                addProperty("resolved_key", originalKey)
            }
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    /**
     * 把指定历史清单恢复为一个新的单调递增修订，并将同一快照应用到当前 Room 数据库。
     * 旧清单和 delta 只读保留，因此恢复操作本身仍可再次回退。
     */
    suspend fun restoreIncrementalRevision(
        revision: Long,
        request: WebDavBackupRequest
    ): JsonObject = withContext(Dispatchers.IO) {
        require(revision > 0L) { "revision 必须大于 0" }
        val raw = rawConfig()
        val encryptionPassword = request.password?.trim().takeUnless { it.isNullOrBlank() }
            ?: raw.encryptionPassword
        require(encryptionPassword.isNotBlank()) { "未设置加密密码" }
        val folder = ensureFolder(raw)
        if (!folder.ok) error(folder.message)
        ensureIncrementalFolders(raw)

        val profileName = prefs.activeDbName
        val rootUrl = resolveIncrementalRootUrl(raw.url, profileName)
        val manifestUrl = "${rootUrl}manifest.nksync"
        lateinit var currentPayload: ByteArray
        lateinit var currentManifest: WebDavSyncManifest
        var currentEtag: String? = null
        execute(Request.Builder().url(manifestUrl).get(), raw).use { response ->
            if (response.code != 200) error("读取当前增量清单失败 (HTTP ${response.code})")
            currentPayload = response.body?.bytes() ?: byteArrayOf()
            currentEtag = response.header("ETag")
            val plain = LocalWebDavArchiveCodec.decrypt(currentPayload, encryptionPassword)
            currentManifest = gson.fromJson(plain.toString(Charsets.UTF_8), WebDavSyncManifest::class.java)
                ?: error("当前增量清单格式无效")
        }
        require(currentManifest.version == 2) { "当前增量清单不是 v2 格式" }
        require(!currentEtag.isNullOrBlank()) {
            "WebDAV 服务器没有返回 ETag，无法安全恢复历史修订"
        }
        if (revision == currentManifest.revision) {
            return@withContext successJson().apply {
                addProperty("ok", true)
                addProperty("revision", revision)
                addProperty("new_revision", revision)
                addProperty("already_current", true)
            }
        }

        val selectedPayload = execute(
            Request.Builder().url("${rootUrl}history/manifest-$revision.nksync").get(),
            raw
        ).use { response ->
            if (response.code != 200) error("历史修订 $revision 不存在 (HTTP ${response.code})")
            response.body?.bytes() ?: byteArrayOf()
        }
        val selectedPlain = LocalWebDavArchiveCodec.decrypt(selectedPayload, encryptionPassword)
        val selectedManifest = gson.fromJson(
            selectedPlain.toString(Charsets.UTF_8),
            WebDavSyncManifest::class.java
        ) ?: error("历史修订格式无效")
        require(selectedManifest.revision == revision) { "历史修订编号不匹配" }
        if (selectedManifest.revision > 0L && selectedManifest.coverage.isEmpty()) {
            selectedManifest.coverage.addAll(LEGACY_INCREMENTAL_COVERAGE)
        }

        val db = NekobotDatabase.get(appContext, profileName)
        val stagingDirectory = File(
            appContext.cacheDir,
            "webdav-incremental-restore-${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建历史恢复暂存目录" } }
        try {
            val recordsFromHistory = loadRemoteIncrementalRecords(
                selectedManifest.records,
                rootUrl,
                raw,
                encryptionPassword,
                stagingDirectory
            ).values.toList()
            val restoredRecords = recordsFromHistory.map { record ->
                uploadRecordAttachments(
                    record,
                    rootUrl,
                    raw,
                    encryptionPassword,
                    profileName,
                    stagingDirectory
                ).copy(localAttachments = record.localAttachments)
            }.toMutableList()
            val restoredKeys = selectedManifest.records.keys
            val now = nowIso()
            collectIncrementalRecords(db).keys
                .filter { key ->
                    key !in restoredKeys && key.substringBefore(':') in selectedManifest.coverage
                }
                .forEach { restoredRecords += LocalWebDavIncrementalLogic.tombstone(it, now) }

            val newRevision = currentManifest.revision + 1L
            val restoreDeltaName = "delta-$newRevision-restore-${UUID.randomUUID()}.nksync"
            val restoreDelta = WebDavSyncDelta(
                revision = newRevision,
                deviceId = getOrCreateSyncDeviceId(),
                createdAt = now,
                records = restoredRecords
            )
            val encryptedRestoreDelta = writeEncryptedIncrementalDelta(
                restoreDelta,
                encryptionPassword,
                profileName,
                stagingDirectory
            )
            putIncrementalFile("$rootUrl$restoreDeltaName", encryptedRestoreDelta, raw)
            val restoredManifest = WebDavSyncManifest(
                version = 2,
                profileId = currentManifest.profileId.ifBlank {
                    LocalDataCatalog.stableProfileId(appContext, profileName)
                },
                syncGroupId = currentManifest.syncGroupId.ifBlank {
                    LocalDataCatalog.stableSyncGroupId(appContext, profileName)
                },
                revision = newRevision,
                updatedAt = now,
                coverage = selectedManifest.coverage.toMutableSet(),
                records = linkedMapOf<String, WebDavSyncIndexEntry>().apply {
                    restoredRecords.forEach { record ->
                        put(record.key, LocalWebDavIncrementalLogic.indexOf(record, restoreDeltaName))
                    }
                }
            )
            putIncrementalFile(
                "${rootUrl}history/manifest-${currentManifest.revision}.nksync",
                currentPayload,
                raw
            )
            val restoredPayload = LocalWebDavArchiveCodec.encrypt(
                gson.toJson(restoredManifest).toByteArray(Charsets.UTF_8),
                encryptionPassword,
                profileName
            )
            val builder = Request.Builder().url(manifestUrl)
                .put(restoredPayload.toRequestBody(BINARY_MEDIA_TYPE))
                .header("If-Match", currentEtag!!)
            execute(builder, raw).use { response ->
                if (response.code == 412) error("远端数据已被其他设备更新，请刷新历史后重试")
                if (response.code !in listOf(200, 201, 204)) {
                    error("恢复历史修订失败 (HTTP ${response.code})")
                }
            }

            val profileId = LocalDataCatalog.stableProfileId(appContext, profileName)
            val baselineKey = "$KEY_SYNC_V2_BASE_PREFIX$profileId"
            val journalKey = "$KEY_SYNC_V2_JOURNAL_PREFIX$profileId"
            val restoreJournal = WebDavSyncJournal(
                profileName = profileName,
                targetManifest = restoredManifest.copy(
                    records = restoredManifest.records.toMutableMap()
                ),
                incomingKeys = restoredManifest.records.keys.toList(),
                conflictDetailsJson = "[]",
                updatedAt = now
            )
            check(
                configPrefs.edit()
                    .putString(journalKey, gson.toJson(restoreJournal))
                    .commit()
            ) { "无法保存历史恢复日志，尚未应用恢复数据" }
            applyIncrementalRecords(db, restoredRecords)
            check(
                configPrefs.edit()
                    .putString(baselineKey, gson.toJson(restoredManifest))
                    .putString(KEY_LAST_CONFLICTS, "[]")
                    .remove(journalKey)
                    .commit()
            ) { "历史数据已恢复，但本机基线提交失败；下次同步将继续恢复" }
            updateStatus(
                lastSyncAt = now,
                lastError = "",
                lastFileSize = encryptedRestoreDelta.length() + restoredPayload.size
            )
            successJson().apply {
                addProperty("ok", true)
                addProperty("restored_revision", revision)
                addProperty("new_revision", newRevision)
                addProperty("record_count", restoredManifest.records.size)
                addProperty("restored_at", now)
            }
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private suspend fun collectIncrementalRecords(
        db: NekobotDatabase
    ): Map<String, WebDavSyncRecord> {
        val records = linkedMapOf<String, WebDavSyncRecord>()
        db.sessionDao().listAll().forEach { entity ->
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_SESSION,
                entity.id,
                entity.updatedAt,
                gson.toJsonTree(entity).asJsonObject
            )
            records[record.key] = record
        }
        db.messageDao().listAll().forEach { entity ->
            val audioFile = messageAudioFile(entity.audioUrl)?.takeIf(File::isFile)
            if (audioFile != null) {
                require(audioFile.length() <= MAX_MESSAGE_AUDIO_BYTES) { "聊天音频文件过大" }
            }
            val value = gson.toJsonTree(entity).asJsonObject.apply {
                audioFile?.let {
                    addProperty("audioUrl", it.name)
                    addProperty("audio_url", it.name)
                    addProperty("audio_file_name", it.name)
                }
            }
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_MESSAGE,
                entity.id,
                entity.audioUpdatedAt ?: entity.createdAt,
                value,
                audioFile?.let { mapOf("audio" to it) }.orEmpty()
            )
            records[record.key] = record
        }
        db.messageImageDao().listAll().forEach { entity ->
            val file = messageImageFile(entity.fileName)
                ?.takeIf { entity.status == LocalRepository.MESSAGE_IMAGE_STATUS_COMPLETED && it.isFile }
            if (file != null) require(file.length() <= MAX_MESSAGE_IMAGE_BYTES) { "消息生图文件过大" }
            val value = gson.toJsonTree(entity).asJsonObject.apply {
                // 设备间同步使用稳定的文件名，不能把绝对 filesDir 路径写入哈希。
                addProperty("file_path", entity.fileName.orEmpty())
                addProperty("filePath", entity.fileName.orEmpty())
                addProperty("reference_image_path", stablePortraitReference(entity.referenceImagePath))
                addProperty("reference_image_mime_type", entity.referenceImageMimeType.orEmpty())
            }
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_MESSAGE_IMAGE,
                entity.id,
                entity.updatedAt,
                value,
                file?.let { mapOf("image" to it) }.orEmpty()
            )
            records[record.key] = record
        }
        db.stickerDao().listAll().forEach { entity ->
            val file = stickerFile(entity.fileName)?.takeIf(File::isFile)
            if (file != null) require(file.length() <= MAX_STICKER_BYTES) { "表情图片过大" }
            val value = gson.toJsonTree(entity).asJsonObject.apply {
                // 同步记录使用稳定文件名，恢复端按 file_name 重新拼绝对路径。
                addProperty("file_path", entity.fileName)
                addProperty("filePath", entity.fileName)
            }
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_STICKER,
                entity.id,
                entity.updatedAt,
                value,
                file?.let { mapOf("image" to it) }.orEmpty()
            )
            records[record.key] = record
        }
        db.characterDao().listAll().forEach { entity ->
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_CHARACTER,
                entity.id,
                entity.updatedAt,
                gson.toJsonTree(entity).asJsonObject
            )
            records[record.key] = record
        }
        val books = db.worldBookDao().listAll()
        val bookUpdatedAt = books.associate { it.id to it.updatedAt }
        books.forEach { entity ->
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_WORLD_BOOK,
                entity.id,
                entity.updatedAt,
                gson.toJsonTree(entity).asJsonObject
            )
            records[record.key] = record
        }
        db.worldBookDao().listAllEntries().forEach { entity ->
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_WORLD_BOOK_ENTRY,
                entity.id,
                bookUpdatedAt[entity.bookId].orEmpty(),
                gson.toJsonTree(entity).asJsonObject
            )
            records[record.key] = record
        }
        records.putAll(collectGenericIncrementalRecords(db))
        collectIncrementalAppSettings()?.let { records[it.key] = it }
        collectIncrementalCredentials(db)?.let { records[it.key] = it }
        collectIncrementalProfileState(db).forEach { records[it.key] = it }
        collectIncrementalFiles(db).forEach { records[it.key] = it }
        return records
    }

    private suspend fun collectGenericIncrementalRecords(db: NekobotDatabase): Map<String, WebDavSyncRecord> {
        val database = db.openHelper.readableDatabase
        val result = linkedMapOf<String, WebDavSyncRecord>()
        GENERIC_INCREMENTAL_CATEGORIES.forEach { category ->
            genericTables(category).forEach { table ->
                val primaryKey = primaryKeyColumns(database, table)
                require(primaryKey.isNotEmpty()) { "增量数据表缺少主键：$table" }
                val orderedMessages = table == "local_agent_tool_messages"
                val ordinals = linkedMapOf<Pair<String, String>, Int>()
                val orderBy = if (orderedMessages) " ORDER BY `session_id`, `run_id`, `id`" else ""
                database.query("SELECT * FROM `${safeSqlIdentifier(table)}`$orderBy").use { cursor ->
                    while (cursor.moveToNext()) {
                        val valueRow = normalizeIncrementalRow(table, cursorRow(cursor))
                        val keyValues = if (orderedMessages) {
                            val sessionId = valueRow.get("session_id").asString
                            val runId = valueRow.get("run_id").asString
                            val ordinal = ordinals.getOrDefault(sessionId to runId, 0)
                            ordinals[sessionId to runId] = ordinal + 1
                            valueRow.addProperty("id", 0L)
                            JsonArray().apply {
                                add(sessionId)
                                add(runId)
                                add(ordinal)
                            }
                        } else {
                            JsonArray().apply { primaryKey.forEach { add(valueRow.get(it)) } }
                        }
                        val encodedKey = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(gson.toJson(keyValues).toByteArray(Charsets.UTF_8))
                        val recordId = "$table:$encodedKey"
                        val updatedAt = listOf("updated_at", "updatedAt", "created_at", "createdAt")
                            .firstNotNullOfOrNull { column ->
                                valueRow.get(column)?.takeIf { it.isJsonPrimitive }?.asString
                            }
                            ?: "0"
                        val value = JsonObject().apply {
                            addProperty("table", table)
                            add("primary_key", keyValues)
                            add("row", valueRow)
                        }
                        val record = LocalWebDavIncrementalLogic.record(
                            type = category.id,
                            id = recordId,
                            updatedAt = updatedAt,
                            value = value
                        )
                        result[record.key] = record
                    }
                }
            }
        }
        return result
    }

    private fun genericTables(category: PortableDataCategory): List<String> = category.tables.filterNot {
        it in SPECIAL_INCREMENTAL_TABLES
    }

    /** 密钥只进入已由用户密码加密的凭据记录；运行态字段不跨设备复用。 */
    private fun normalizeIncrementalRow(table: String, source: JsonObject): JsonObject =
        source.deepCopy().apply {
            when (table) {
                "local_ai_models" -> listOf(
                    "api_key", "proxy_url", "tts_headers", "tts_body_template", "stt_headers"
                ).forEach { addProperty(it, "") }
                "local_api_keys" -> addProperty("key", "")
                "local_oauth_accounts" -> addProperty("encrypted_credentials", "")
                "local_mcp_servers" -> {
                    listOf("url", "headers_json", "args_json", "env_json").forEach { add(it, com.google.gson.JsonNull.INSTANCE) }
                    addProperty("connected", false)
                    addProperty("tool_count", 0)
                    add("last_connected_at", com.google.gson.JsonNull.INSTANCE)
                }
                "local_agent_notices" -> {
                    add("consumed_by_run_id", com.google.gson.JsonNull.INSTANCE)
                    addProperty("delivery_state", "pending")
                    add("delivered_at", com.google.gson.JsonNull.INSTANCE)
                }
                "local_agent_runs" -> if (get("status")?.asString in setOf("running", "active")) {
                    addProperty("status", "interrupted")
                }
                "local_subagent_tasks" -> if (get("status")?.asString in setOf("running", "active")) {
                    addProperty("status", "interrupted")
                    add("source_device_id", com.google.gson.JsonNull.INSTANCE)
                }
                "local_subagent_tool_calls" -> if (get("status")?.asString in setOf("running", "started")) {
                    addProperty("status", "unknown")
                    addProperty("recovery_decision", "result_unknown")
                }
                "local_tasks", "local_workflows" -> if (get("status")?.asString == "running") {
                    addProperty("status", "paused")
                }
            }
        }

    private fun collectIncrementalAppSettings(): WebDavSyncRecord? {
        val value = JsonParser.parseString(
            String(PortableDataArchiveManager(appContext).captureAppSettings(), Charsets.UTF_8)
        ).asJsonObject
        return LocalWebDavIncrementalLogic.record(
            type = TYPE_APP_SETTINGS,
            id = "current",
            updatedAt = "0",
            value = value
        )
    }

    private suspend fun collectIncrementalCredentials(db: NekobotDatabase): WebDavSyncRecord? {
        val bytes = LocalDatabaseCredentialBundle.capture(db)
        val value = JsonObject().apply { addProperty("bundle", String(bytes, Charsets.UTF_8)) }
        return LocalWebDavIncrementalLogic.record(
            type = TYPE_CREDENTIALS,
            id = "current",
            updatedAt = "0",
            value = value
        )
    }

    private suspend fun collectIncrementalProfileState(db: NekobotDatabase): List<WebDavSyncRecord> {
        val profileName = prefs.activeDbName
        val dbName = "$profileName.db"
        val tokenPrefs = appContext.getSharedPreferences("token_usage_$dbName", Context.MODE_PRIVATE)
        val tokenRecords = runCatching {
            JsonParser.parseString(tokenPrefs.getString("records", "[]") ?: "[]").asJsonArray
        }.getOrDefault(JsonArray())
        val result = tokenRecords.mapNotNull { element ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = value.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            LocalWebDavIncrementalLogic.record(
                type = TYPE_TOKEN_USAGE,
                id = id,
                updatedAt = value.get("timestamp")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                value = value
            )
        }.toMutableList()

        val achievementPrefs = appContext.getSharedPreferences(ACHIEVEMENT_PREF_NAME, Context.MODE_PRIVATE)
        val achievementKey = AchievementManager.storageKeyForScope("local:$profileName")
        result += LocalWebDavIncrementalLogic.record(
            type = TYPE_ACHIEVEMENTS,
            id = "current",
            updatedAt = "0",
            value = JsonObject().apply {
                addProperty("value", achievementPrefs.getString(achievementKey, "").orEmpty())
            }
        )

        val sessions = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
        val story = LocalPlotStoryStore.capture(appContext, profileName, sessions)
        val storyJson = gson.toJson(story)
        require(storyJson.toByteArray(Charsets.UTF_8).size <= MAX_INCREMENTAL_PLOT_STORY_BYTES) {
            "剧情地图超过 16 MB 增量同步限制"
        }
        result += LocalWebDavIncrementalLogic.record(
            type = TYPE_PLOT_STORY,
            id = "current",
            updatedAt = "0",
            value = JsonParser.parseString(storyJson).asJsonObject
        )
        return result
    }

    private suspend fun collectIncrementalFiles(db: NekobotDatabase): List<WebDavSyncRecord> {
        val result = mutableListOf<WebDavSyncRecord>()
        val profileName = prefs.activeDbName
        val allowedSessions = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
        LocalDataCatalog.descriptors.forEach { descriptor ->
            descriptor.category.let { category ->
                LocalDataCatalog.fileRoots(appContext, category).forEach { (rootId, root) ->
                    if (category == PortableDataCategory.MEDIA && rootId == "cached_portraits") return@forEach
                    if (!root.isDirectory) return@forEach
                    val canonicalRoot = root.canonicalFile
                    root.walkTopDown().onEnter { directory ->
                        !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                            (category != PortableDataCategory.WORKSPACE || directory == root ||
                                directory.relativeTo(root).invariantSeparatorsPath.substringBefore('/') in
                                allowedSessions + LocalWorkspaceStorage.SHARED_DIR_NAME) &&
                            !(category == PortableDataCategory.EXTENSIONS && rootId == "plugin_packages" &&
                                directory.name.startsWith(".staging-"))
                    }.filter { file ->
                        file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                            !(category == PortableDataCategory.EXTENSIONS && rootId == "plugin_packages" &&
                                (file.name == PLUGIN_STATE_ENTRY || file.toPath().any { it.toString().startsWith(".staging-") })) &&
                            !(category == PortableDataCategory.EXTENSIONS && rootId == "plugin_files" &&
                                file.toPath().any { it.toString().startsWith(".") })
                    }.forEach { file ->
                        val relative = file.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                        require(isSafeRelativePath(relative)) { "同步文件路径无效：$relative" }
                        require(file.length() <= MAX_INCREMENTAL_FILE_BYTES) {
                            "增量同步文件超过 64 MB：${file.name}"
                        }
                        val value = JsonObject().apply {
                            addProperty("category", category.id)
                            addProperty("root", rootId)
                            addProperty("path", relative)
                        }
                        val id = encodeFileRecordId(category.id, rootId, relative)
                        result += LocalWebDavIncrementalLogic.record(
                            type = TYPE_FILE,
                            id = id,
                            updatedAt = file.lastModified().toString(),
                            value = value,
                            localAttachments = mapOf("file" to file)
                        )
                    }
                }
            }
        }
        val memoryFile = GlobalAgentMemoryStore.memoryFileFor(appContext, profileName)
        if (memoryFile.isFile) {
            require(memoryFile.length() <= MAX_INCREMENTAL_FILE_BYTES) { "Agent 长期记忆文件超过 64 MB" }
            val value = JsonObject().apply {
                addProperty("category", PortableDataCategory.GLOBAL_MEMORY.id)
                addProperty("root", "memory")
                addProperty("path", "global-memory.md")
            }
            result += LocalWebDavIncrementalLogic.record(
                type = TYPE_FILE,
                id = encodeFileRecordId(PortableDataCategory.GLOBAL_MEMORY.id, "memory", "global-memory.md"),
                updatedAt = memoryFile.lastModified().toString(),
                value = value,
                localAttachments = mapOf("file" to memoryFile)
            )
        }
        return result
    }

    private fun encodeFileRecordId(categoryId: String, rootId: String, relativePath: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(JsonArray().apply {
                add(categoryId)
                add(rootId)
                add(relativePath)
            }).toByteArray(Charsets.UTF_8)
        )

    private fun tableColumns(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        database.query("PRAGMA table_info(`${safeSqlIdentifier(table)}`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun primaryKeyColumns(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): List<String> = database.query("PRAGMA table_info(`${safeSqlIdentifier(table)}`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val primaryIndex = cursor.getColumnIndexOrThrow("pk")
        buildList {
            val ordered = mutableListOf<Pair<Int, String>>()
            while (cursor.moveToNext()) {
                val order = cursor.getInt(primaryIndex)
                if (order > 0) ordered += order to cursor.getString(nameIndex)
            }
            ordered.sortedBy { it.first }.forEach { add(it.second) }
        }
    }

    private fun cursorRow(cursor: Cursor): JsonObject = JsonObject().apply {
        for (index in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(index)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> add(name, com.google.gson.JsonNull.INSTANCE)
                Cursor.FIELD_TYPE_INTEGER -> addProperty(name, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> addProperty(name, cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> addProperty(name, cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> add(name, JsonObject().apply {
                    addProperty("__webdav_blob", Base64.getEncoder().encodeToString(cursor.getBlob(index)))
                })
            }
        }
    }

    private fun safeSqlIdentifier(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_]+"))) { "数据库标识符无效" }
        return value
    }

    private suspend fun applyIncrementalRecords(
        db: NekobotDatabase,
        records: List<WebDavSyncRecord>
    ) {
        db.withTransaction {
            records.filter { it.deleted }
                .sortedWith(compareBy<WebDavSyncRecord> {
                    if (it.type in GENERIC_INCREMENTAL_TYPE_IDS) 0 else 1
                }.thenBy { record ->
                    if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) {
                        -genericTableOrder(record)
                    } else when (record.type) {
                        TYPE_WORLD_BOOK_ENTRY -> 0
                        TYPE_MESSAGE -> 1
                        TYPE_MESSAGE_IMAGE -> 2
                        TYPE_SESSION -> 3
                        TYPE_WORLD_BOOK -> 4
                        TYPE_CHARACTER -> 5
                        else -> 6
                    }
                }.thenByDescending { record ->
                    if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) genericRecordOrdinal(record) else 0
                })
                .forEach { record ->
                    when (record.type) {
                        TYPE_SESSION -> db.sessionDao().deleteById(record.id)
                        TYPE_MESSAGE -> {
                            db.messageDao().getById(record.id)?.audioUrl?.let(::deleteMessageAudioFile)
                            db.messageDao().deleteById(record.id)
                        }
                        TYPE_MESSAGE_IMAGE -> {
                            db.messageImageDao().getById(record.id)?.filePath?.let(::deleteMessageImageFile)
                            db.messageImageDao().deleteById(record.id)
                        }
                        TYPE_STICKER -> {
                            db.stickerDao().getById(record.id)?.fileName?.let(::deleteStickerFile)
                            db.stickerDao().deleteById(record.id)
                        }
                        TYPE_CHARACTER -> db.characterDao().deleteById(record.id)
                        TYPE_WORLD_BOOK -> db.worldBookDao().deleteById(record.id)
                        TYPE_WORLD_BOOK_ENTRY -> db.worldBookDao().deleteEntryById(record.id)
                        TYPE_FILE, TYPE_APP_SETTINGS, TYPE_CREDENTIALS, TYPE_TOKEN_USAGE,
                        TYPE_ACHIEVEMENTS, TYPE_PLOT_STORY -> Unit
                        else -> if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) {
                            deleteGenericIncrementalRecord(db, record)
                        }
                    }
                }

            records.filterNot { it.deleted }
                .sortedWith(compareBy<WebDavSyncRecord> { record ->
                    if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) {
                        100 + genericTableOrder(record)
                    } else when (record.type) {
                        TYPE_CREDENTIALS -> 10_000
                        TYPE_APP_SETTINGS -> 10_001
                        TYPE_CHARACTER -> 0
                        TYPE_WORLD_BOOK -> 1
                        TYPE_SESSION -> 2
                        TYPE_WORLD_BOOK_ENTRY -> 3
                        TYPE_MESSAGE -> 4
                        TYPE_MESSAGE_IMAGE -> 5
                        else -> 6
                    }
                }.thenBy { record ->
                    if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) genericRecordOrdinal(record) else 0
                })
                .forEach { record ->
                    val value = requireNotNull(record.value) { "同步记录内容为空：${record.key}" }
                    when (record.type) {
                        TYPE_SESSION -> {
                            val entity = gson.fromJson(value, LocalSessionEntity::class.java)
                            if (db.sessionDao().getById(entity.id) == null) {
                                db.sessionDao().upsert(entity)
                            } else {
                                db.sessionDao().update(entity)
                            }
                        }
                        TYPE_MESSAGE -> db.messageDao().upsert(
                            restoreSyncedMessage(value, record.localAttachments["audio"])
                        )
                        TYPE_MESSAGE_IMAGE -> db.messageImageDao().upsert(
                            restoreSyncedMessageImage(value, record.localAttachments["image"])
                        )
                        TYPE_STICKER -> db.stickerDao().upsert(
                            restoreSyncedSticker(value, record.localAttachments["image"])
                        )
                        TYPE_CHARACTER -> {
                            val entity = gson.fromJson(value, LocalCharacterEntity::class.java)
                            if (db.characterDao().getById(entity.id) == null) {
                                db.characterDao().upsert(entity)
                            } else {
                                db.characterDao().update(entity)
                            }
                        }
                        TYPE_WORLD_BOOK -> {
                            val entity = gson.fromJson(value, LocalWorldBookEntity::class.java)
                            if (db.worldBookDao().getById(entity.id) == null) {
                                db.worldBookDao().upsert(entity)
                            } else {
                                db.worldBookDao().update(entity)
                            }
                        }
                        TYPE_WORLD_BOOK_ENTRY -> db.worldBookDao().upsertEntry(
                            gson.fromJson(value, LocalWorldBookEntryEntity::class.java)
                        )
                        TYPE_APP_SETTINGS -> PortableDataArchiveManager(appContext).restoreAppSettings(
                            gson.toJson(value).toByteArray(Charsets.UTF_8)
                        )
                        TYPE_CREDENTIALS -> {
                            val bundle = value.get("bundle")?.asString.orEmpty()
                            if (bundle.isNotBlank()) {
                                LocalDatabaseCredentialBundle.restore(db, bundle.toByteArray(Charsets.UTF_8))
                            }
                        }
                        TYPE_FILE -> Unit
                        TYPE_TOKEN_USAGE, TYPE_ACHIEVEMENTS, TYPE_PLOT_STORY -> Unit
                        else -> if (record.type in GENERIC_INCREMENTAL_TYPE_IDS) {
                            upsertGenericIncrementalRecord(db, record, value)
                        }
                    }
                }
        }
        val fileRecords = records.filter { it.type == TYPE_FILE }
        fileRecords.forEach { applyIncrementalFileRecord(db, it) }
        val changedPlugins = fileRecords.mapNotNull(::pluginIdFromFileRecord).toSet()
        changedPlugins.forEach { pluginId ->
            val state = File(appContext.filesDir, "plugins/$pluginId/$PLUGIN_STATE_ENTRY")
            state.parentFile?.mkdirs()
            state.writeText("{\"enabled\":false,\"installedAt\":${System.currentTimeMillis()}}", Charsets.UTF_8)
            ServiceContainer.pluginGrants.revoke(pluginId)
        }
        if (changedPlugins.isNotEmpty()) ServiceContainer.pluginManager.reload()
        applyIncrementalTokenUsage(records.filter { it.type == TYPE_TOKEN_USAGE })
        applyIncrementalAchievementState(records.filter { it.type == TYPE_ACHIEVEMENTS })
        applyIncrementalPlotStory(records.filter { it.type == TYPE_PLOT_STORY }, db)
    }

    private fun applyIncrementalTokenUsage(records: List<WebDavSyncRecord>) {
        if (records.isEmpty()) return
        val prefs = appContext.getSharedPreferences(
            "token_usage_${prefs.activeDbName}.db",
            Context.MODE_PRIVATE
        )
        val current = runCatching {
            JsonParser.parseString(prefs.getString("records", "[]") ?: "[]").asJsonArray
        }.getOrDefault(JsonArray())
        val byId = linkedMapOf<String, JsonObject>()
        current.forEach { element ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            value.get("id")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                ?.let { byId[it] = value }
        }
        records.forEach { record ->
            if (record.deleted) byId.remove(record.id)
            else record.value?.let { byId[record.id] = it }
        }
        val merged = byId.values.sortedBy {
            it.get("timestamp")?.takeUnless { value -> value.isJsonNull }?.asString.orEmpty()
        }.takeLast(5_000)
        check(prefs.edit().putString("records", gson.toJson(merged)).commit()) {
            "恢复 Token 用量失败"
        }
    }

    private fun applyIncrementalAchievementState(records: List<WebDavSyncRecord>) {
        val record = records.lastOrNull() ?: return
        val key = AchievementManager.storageKeyForScope("local:${prefs.activeDbName}")
        val editor = appContext.getSharedPreferences(ACHIEVEMENT_PREF_NAME, Context.MODE_PRIVATE).edit()
        if (record.deleted) editor.remove(key)
        else {
            val value = record.value?.get("value")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
        }
        check(editor.commit()) { "恢复成就进度失败" }
    }

    private suspend fun applyIncrementalPlotStory(
        records: List<WebDavSyncRecord>,
        db: NekobotDatabase
    ) {
        val record = records.lastOrNull() ?: return
        val currentSessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
        val story = if (record.deleted) DbProfileStoryData("{}", emptyMap()) else {
            gson.fromJson(record.value, DbProfileStoryData::class.java)
                ?: DbProfileStoryData("{}", emptyMap())
        }
        LocalPlotStoryStore.mergeImportedSessions(
            context = appContext,
            databaseName = prefs.activeDbName,
            importedSessionIds = currentSessionIds,
            currentSessionIds = currentSessionIds,
            story = story
        )
    }

    private fun genericTableOrder(record: WebDavSyncRecord): Int {
        val category = GENERIC_INCREMENTAL_CATEGORIES.first { it.id == record.type }
        val table = record.value?.get("table")?.takeUnless { it.isJsonNull }?.asString
            ?: record.id.substringBefore(':')
        return GENERIC_INCREMENTAL_CATEGORIES.indexOf(category) * 100 +
            genericTables(category).indexOf(table).coerceAtLeast(0)
    }

    private fun genericRecordOrdinal(record: WebDavSyncRecord): Int =
        if (record.id.substringBefore(':') == "local_agent_tool_messages") {
            val key = record.value?.getAsJsonArray("primary_key") ?: runCatching {
                JsonParser.parseString(
                    String(Base64.getUrlDecoder().decode(record.id.substringAfter(':')), Charsets.UTF_8)
                ).asJsonArray
            }.getOrNull()
            key?.takeIf { it.size() == 3 }?.get(2)?.asInt ?: 0
        } else {
            0
        }

    private fun deleteGenericIncrementalRecord(db: NekobotDatabase, record: WebDavSyncRecord) {
        val table = record.id.substringBefore(':')
        val category = GENERIC_INCREMENTAL_CATEGORIES.firstOrNull { it.id == record.type }
            ?: error("不支持的增量数据类别：${record.type}")
        require(table in genericTables(category)) { "增量记录包含未登记的数据表：$table" }
        val encodedKey = record.id.substringAfter(':', "")
        require(encodedKey.isNotBlank()) { "增量删除记录缺少主键：${record.key}" }
        val keyValues = JsonParser.parseString(
            String(Base64.getUrlDecoder().decode(encodedKey), Charsets.UTF_8)
        ).asJsonArray
        if (table == "local_agent_tool_messages") {
            require(keyValues.size() == 3) { "工具轨迹记录主键结构无效" }
            val database = db.openHelper.writableDatabase
            val sessionId = keyValues[0].asString
            val runId = keyValues[1].asString
            val ordinal = keyValues[2].asInt
            val ids = database.query(
                "SELECT `id` FROM `local_agent_tool_messages` WHERE `session_id` = ? AND `run_id` = ? ORDER BY `id`",
                arrayOf(sessionId, runId)
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            val id = ids.getOrNull(ordinal) ?: return
            database.delete("local_agent_tool_messages", "`id` = ?", arrayOf(id.toString()))
            return
        }
        val primaryKey = primaryKeyColumns(db.openHelper.writableDatabase, table)
        require(primaryKey.size == keyValues.size()) { "增量删除记录主键结构不匹配：${record.key}" }
        val selection = primaryKey.joinToString(" AND ") { "`${safeSqlIdentifier(it)}` = ?" }
        val args = keyValues.map(::jsonSqlValue).toTypedArray()
        db.openHelper.writableDatabase.delete(table, selection, args)
    }

    private fun upsertGenericIncrementalRecord(
        db: NekobotDatabase,
        record: WebDavSyncRecord,
        value: JsonObject
    ) {
        val category = GENERIC_INCREMENTAL_CATEGORIES.firstOrNull { it.id == record.type }
            ?: error("不支持的增量数据类别：${record.type}")
        val table = value.get("table")?.asString.orEmpty()
        require(table in genericTables(category)) { "增量记录包含未登记的数据表：$table" }
        val row = value.getAsJsonObject("row") ?: error("增量行记录内容为空：${record.key}")
        val database = db.openHelper.writableDatabase
        val columns = tableColumns(database, table).toSet()
        val values = ContentValues()
        val orderedToolMessageId = if (table == "local_agent_tool_messages") {
            val key = value.getAsJsonArray("primary_key") ?: error("工具轨迹记录主键缺失")
            require(key.size() == 3) { "工具轨迹记录主键结构无效" }
            val sessionId = key[0].asString
            val runId = key[1].asString
            val ordinal = key[2].asInt
            val ids = database.query(
                "SELECT `id` FROM `local_agent_tool_messages` WHERE `session_id` = ? AND `run_id` = ? ORDER BY `id`",
                arrayOf(sessionId, runId)
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            require(ordinal <= ids.size) { "工具轨迹缺少前序记录，无法安全恢复" }
            ids.getOrNull(ordinal)
        } else null
        row.entrySet().forEach { (column, raw) ->
            require(column in columns) { "增量记录包含未知列：$table.$column" }
            if (table == "local_agent_tool_messages" && column == "id") return@forEach
            putSqlValue(values, column, raw)
        }
        orderedToolMessageId?.let { values.put("id", it) }
        check(database.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values) != -1L) {
            "写入增量数据失败：$table"
        }
    }

    private suspend fun applyIncrementalFileRecord(db: NekobotDatabase, record: WebDavSyncRecord) {
        val target = resolveIncrementalFileTarget(db, record.id)
        if (record.deleted) {
            if (target.isFile && !target.delete()) error("无法删除已同步文件：${target.name}")
            return
        }
        val source = record.localAttachments["file"]
            ?: error("同步文件内容缺失：${record.id}")
        require(source.isFile && source.length() <= MAX_INCREMENTAL_FILE_BYTES) { "同步文件无效或过大" }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.syncing")
        try {
            source.inputStream().buffered().use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    private fun pluginIdFromFileRecord(record: WebDavSyncRecord): String? {
        if (record.type != TYPE_FILE) return null
        val parts = decodeFileRecordId(record.id)
        if (parts.first != PortableDataCategory.EXTENSIONS.id || parts.second != "plugin_packages" ||
            parts.third.split('/').any { it.startsWith(".staging-") }
        ) return null
        val pluginId = parts.third.substringBefore('/')
        return pluginId.takeIf { it.isNotBlank() && it != parts.third && !it.startsWith('.') }
    }

    private suspend fun resolveIncrementalFileTarget(db: NekobotDatabase, recordId: String): File {
        val (categoryId, rootId, relative) = decodeFileRecordId(recordId)
        require(isSafeRelativePath(relative)) { "同步文件路径无效" }
        if (categoryId == PortableDataCategory.GLOBAL_MEMORY.id && rootId == "memory") {
            require(relative == "global-memory.md") { "全局记忆文件路径无效" }
            return GlobalAgentMemoryStore.memoryFileFor(appContext, prefs.activeDbName).canonicalFile
        }
        val category = PortableDataCategory.fromId(categoryId) ?: error("未知同步文件类别")
        val root = LocalDataCatalog.fileRoots(appContext, category)
            .firstOrNull { it.first == rootId }?.second?.canonicalFile
            ?: error("同步文件根目录无效：$categoryId/$rootId")
        if (category == PortableDataCategory.WORKSPACE) {
            val currentSessionIds = db.sessionDao()
                .listAll().mapTo(linkedSetOf()) { it.id }
            require(relative.substringBefore('/') in currentSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME) {
                "同步文件不属于当前档案工作区"
            }
        }
        if (category == PortableDataCategory.EXTENSIONS && rootId == "plugin_packages") {
            require(!relative.split('/').any { it.startsWith(".staging-") }) { "插件暂存文件不参与同步" }
            require(relative.substringAfter('/', "").isNotBlank()) { "插件目录项不参与同步" }
        }
        if (category == PortableDataCategory.EXTENSIONS && rootId == "plugin_files") {
            require(!relative.split('/').any { it.startsWith('.') }) { "隐藏插件文件不参与同步" }
        }
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "同步文件路径越界" }
        return target
    }

    private fun decodeFileRecordId(recordId: String): Triple<String, String, String> {
        val parts = JsonParser.parseString(
            String(Base64.getUrlDecoder().decode(recordId), Charsets.UTF_8)
        ).asJsonArray
        require(parts.size() == 3) { "同步文件记录 ID 无效" }
        return Triple(parts[0].asString, parts[1].asString, parts[2].asString)
    }

    private fun putSqlValue(values: ContentValues, column: String, raw: com.google.gson.JsonElement) {
        when (val value = jsonSqlValue(raw)) {
            null -> values.putNull(column)
            is String -> values.put(column, value)
            is Int -> values.put(column, value)
            is Long -> values.put(column, value)
            is Double -> values.put(column, value)
            is Float -> values.put(column, value)
            is Short -> values.put(column, value)
            is ByteArray -> values.put(column, value)
            else -> error("不支持的增量字段类型：$column")
        }
    }

    private fun jsonSqlValue(value: com.google.gson.JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject && value.asJsonObject.has("__webdav_blob") ->
            Base64.getDecoder().decode(value.asJsonObject.get("__webdav_blob").asString)
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> if (value.asBoolean) 1 else 0
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
            val text = value.asString
            if (text.contains('.') || text.contains('e', true)) value.asDouble else value.asLong
        }
        else -> value.asString
    }

    private fun messageImageFile(fileName: String?): File? {
        if (fileName.isNullOrBlank() || !fileName.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            return null
        }
        val root = File(appContext.filesDir, "portraits").canonicalFile
        val target = File(root, fileName).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /** 表情图片文件（filesDir/stickers/<file_name>）；越界或非法名返回 null。 */
    private fun stickerFile(fileName: String?): File? {
        if (fileName.isNullOrBlank() || !fileName.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            return null
        }
        val root = File(appContext.filesDir, "stickers").canonicalFile
        val target = File(root, fileName).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun messageAudioFile(reference: String?): File? {
        if (reference.isNullOrBlank()) return null
        val fileName = runCatching {
            android.net.Uri.parse(reference).path?.let(::File)?.name ?: File(reference).name
        }.getOrNull()
        return messageAudioFileByName(fileName)
    }

    private fun messageAudioFileByName(fileName: String?): File? {
        if (fileName.isNullOrBlank() || !fileName.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            return null
        }
        val root = File(appContext.filesDir, "tts").canonicalFile
        val target = File(root, fileName).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun deleteMessageAudioFile(reference: String) {
        messageAudioFile(reference)?.let { file -> runCatching { file.delete() } }
    }

    private fun restoreSyncedMessage(value: JsonObject, attachmentFile: File? = null): LocalMessageEntity {
        val entity = gson.fromJson(value, LocalMessageEntity::class.java)
        val fileName = value.get("audio_file_name")?.asString?.takeIf { it.isNotBlank() }
        val target = messageAudioFileByName(fileName)
        val encoded = value.get("audio_file_base64")?.asString.orEmpty()
        if (target != null && attachmentFile?.isFile == true) {
            require(attachmentFile.length() <= MAX_MESSAGE_AUDIO_BYTES) { "聊天音频文件过大" }
            target.parentFile?.mkdirs()
            attachmentFile.inputStream().buffered().use { input ->
                FileOutputStream(target).buffered().use(input::copyTo)
            }
        } else if (target != null && encoded.isNotBlank()) {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size.toLong() <= MAX_MESSAGE_AUDIO_BYTES) { "聊天音频文件过大" }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        val restoredAudioUrl = target?.takeIf(File::isFile)
            ?.let { android.net.Uri.fromFile(it).toString() }
            ?: entity.audioUrl?.takeIf {
                it.startsWith("http://") || it.startsWith("https://") || it.startsWith("content://")
            }
        return entity.copy(audioUrl = restoredAudioUrl)
    }

    private fun deleteMessageImageFile(reference: String) {
        val fileName = android.net.Uri.parse(reference).path?.let(::File)?.name ?: return
        messageImageFile(fileName)?.let { file -> runCatching { file.delete() } }
    }

    private fun deleteStickerFile(fileName: String) {
        stickerFile(fileName)?.let { file -> runCatching { file.delete() } }
    }

    /** 恢复同步来的表情：写入图片文件并按当前设备路径重建 file_path。 */
    private fun restoreSyncedSticker(value: JsonObject, attachmentFile: File? = null): LocalStickerEntity {
        val entity = gson.fromJson(value, LocalStickerEntity::class.java)
        val fileName = value.get("file_path")?.asString?.takeIf { it.isNotBlank() } ?: entity.fileName
        val target = stickerFile(fileName)
        val encoded = value.get("file_base64")?.asString.orEmpty()
        if (target != null && attachmentFile?.isFile == true) {
            require(attachmentFile.length() <= MAX_STICKER_BYTES) { "表情图片过大" }
            target.parentFile?.mkdirs()
            attachmentFile.inputStream().buffered().use { input ->
                FileOutputStream(target).buffered().use(input::copyTo)
            }
        } else if (target != null && encoded.isNotBlank()) {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size.toLong() <= MAX_STICKER_BYTES) { "表情图片过大" }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return entity.copy(
            fileName = fileName,
            filePath = target?.takeIf { it.isFile }
                ?.let { android.net.Uri.fromFile(it).toString() }
                ?: entity.filePath
        )
    }

    private fun restoreSyncedMessageImage(
        value: JsonObject,
        attachmentFile: File? = null
    ): LocalMessageImageEntity {
        val entity = gson.fromJson(value, LocalMessageImageEntity::class.java)
        val fileName = value.get("file_path")?.asString?.takeIf { it.isNotBlank() }
            ?: entity.fileName
        val target = messageImageFile(fileName)
        val encoded = value.get("file_base64")?.asString.orEmpty()
        if (target != null && attachmentFile?.isFile == true) {
            require(attachmentFile.length() <= MAX_MESSAGE_IMAGE_BYTES) { "消息生图文件过大" }
            target.parentFile?.mkdirs()
            attachmentFile.inputStream().buffered().use { input ->
                FileOutputStream(target).buffered().use(input::copyTo)
            }
        } else if (target != null && encoded.isNotBlank()) {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size.toLong() <= MAX_MESSAGE_IMAGE_BYTES) { "消息生图文件过大" }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        val available = target?.isFile == true
        return entity.copy(
            fileName = fileName,
            filePath = target?.takeIf { available }?.let { android.net.Uri.fromFile(it).toString() },
            referenceImagePath = restorePortraitReference(value.get("reference_image_path")?.asString),
            referenceImageMimeType = value.get("reference_image_mime_type")?.asString
                ?.takeIf { it.isNotBlank() },
            status = if (entity.status == LocalRepository.MESSAGE_IMAGE_STATUS_COMPLETED && !available) {
                LocalRepository.MESSAGE_IMAGE_STATUS_FAILED
            } else {
                entity.status
            },
            errorMessage = if (entity.status == LocalRepository.MESSAGE_IMAGE_STATUS_COMPLETED && !available) {
                "同步后未找到图片文件"
            } else {
                entity.errorMessage
            }
        )
    }

    private fun stablePortraitReference(reference: String?): String {
        if (reference.isNullOrBlank()) return ""
        val path = runCatching { android.net.Uri.parse(reference).path }.getOrNull()
        val root = File(appContext.filesDir, "portraits").canonicalFile
        val file = path?.let(::File)?.let { runCatching { it.canonicalFile }.getOrNull() }
        return if (file != null && file.path.startsWith(root.path + File.separator)) {
            file.relativeTo(root).invariantSeparatorsPath
        } else {
            reference
        }
    }

    private fun restorePortraitReference(reference: String?): String? {
        if (reference.isNullOrBlank()) return null
        if (reference.startsWith("http://") || reference.startsWith("https://") || reference.startsWith("content://")) {
            return reference
        }
        val root = File(appContext.filesDir, "portraits").canonicalFile
        val target = runCatching { File(root, reference).canonicalFile }.getOrNull()
        return if (target != null && target.path.startsWith(root.path + File.separator)) {
            target.takeIf { it.isFile }?.let { android.net.Uri.fromFile(it).toString() }
        } else {
            reference
        }
    }

    /**
     * 当前 manifest 始终保留；历史目录只保存其余版本，合计不超过用户设置的上限。
     * 仅在 manifest 成功写入后调用，避免同步失败时错误删除可恢复的版本。
     */
    private fun pruneIncrementalHistory(
        raw: RawConfig,
        rootUrl: String,
        password: String,
        currentManifest: WebDavSyncManifest,
        maxVersions: Int
    ) {
        val historyUrl = "${rootUrl}history/"
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop/></D:propfind>
        """.trimIndent().toRequestBody(XML_MEDIA_TYPE)
        val historyRevisions = mutableSetOf<Long>()
        execute(
            Request.Builder().url(historyUrl).method("PROPFIND", propfindBody).header("Depth", "1"),
            raw
        ).use { response ->
            if (response.code == 404) return
            if (response.code !in listOf(200, 207)) {
                error("读取 WebDAV 历史目录失败 (HTTP ${response.code})")
            }
            val xml = response.body?.string().orEmpty()
            Regex("manifest-(\\d+)\\.nksync", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
                .forEach(historyRevisions::add)
        }

        val maxHistoryVersions = (maxVersions.coerceIn(
            MIN_INCREMENTAL_SYNC_MAX_VERSIONS,
            MAX_INCREMENTAL_SYNC_MAX_VERSIONS
        ) - 1).coerceAtLeast(0)
        val orderedRevisions = historyRevisions.sortedDescending()
        val keptHistoryRevisions = orderedRevisions.take(maxHistoryVersions)
        orderedRevisions.drop(maxHistoryVersions)
            .forEach { revision ->
                execute(
                    Request.Builder().url("${historyUrl}manifest-$revision.nksync").delete(),
                    raw
                ).use { response ->
                    if (response.code !in listOf(200, 202, 204, 404)) {
                        error("清理 WebDAV 历史版本失败 (HTTP ${response.code})")
                    }
                }
            }

        runCatching {
            pruneUnreferencedIncrementalObjects(
                raw = raw,
                rootUrl = rootUrl,
                password = password,
                manifests = buildList {
                    add(currentManifest)
                    keptHistoryRevisions.forEach { revision ->
                        val encrypted = execute(
                            Request.Builder().url("${historyUrl}manifest-$revision.nksync").get(),
                            raw
                        ).use { response ->
                            if (response.code != 200) error("读取保留历史修订失败 (HTTP ${response.code})")
                            response.body?.bytes() ?: byteArrayOf()
                        }
                        val plain = LocalWebDavArchiveCodec.decrypt(encrypted, password)
                        val manifest = gson.fromJson(
                            plain.toString(Charsets.UTF_8),
                            WebDavSyncManifest::class.java
                        ) ?: error("保留历史清单格式无效")
                        require(manifest.revision == revision) { "保留历史清单编号不匹配" }
                        add(manifest)
                    }
                }
            )
        }
    }

    /** 仅清除超过保留期、且不再被当前/保留历史清单引用的不可变文件对象。 */
    private fun pruneUnreferencedIncrementalObjects(
        raw: RawConfig,
        rootUrl: String,
        password: String,
        manifests: List<WebDavSyncManifest>
    ) {
        val deltaKeys = linkedMapOf<String, MutableSet<String>>()
        manifests.forEach { manifest ->
            manifest.records.forEach { (key, index) ->
                if (!index.deleted && index.delta.matches(Regex("delta-[A-Za-z0-9-]+\\.nksync"))) {
                    deltaKeys.getOrPut(index.delta, ::linkedSetOf).add(key)
                }
            }
        }
        val referencedObjects = linkedSetOf<String>()
        val stagingDirectory = File(
            appContext.cacheDir,
            "webdav-gc-${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建历史清理暂存目录" } }
        try {
            deltaKeys.forEach { (deltaName, keys) ->
                val encrypted = File(stagingDirectory, "${UUID.randomUUID()}.nbotcfg")
                val plain = File(stagingDirectory, "${UUID.randomUUID()}.json")
                try {
                    downloadIncrementalFile(
                        "$rootUrl$deltaName",
                        encrypted,
                        raw,
                        MAX_INCREMENTAL_ENCRYPTED_DELTA_BYTES
                    )
                    LocalWebDavArchiveCodec.decryptFile(
                        encrypted,
                        password,
                        plain,
                        MAX_INCREMENTAL_DELTA_BYTES
                    )
                    val records = WebDavSyncDeltaStreamReader.readSelected(
                        plain,
                        keys,
                        stagingDirectory
                    )
                    require(records.keys.containsAll(keys)) { "保留的增量版本中缺少记录" }
                    records.values.forEach { record ->
                        record.attachments.values.flatMap(WebDavSyncFileRef::chunks).forEach { objectName ->
                            require(objectName.matches(Regex("objects/[a-f0-9-]{36}\\.nksync"))) {
                                "增量附件对象名无效"
                            }
                            referencedObjects += objectName.removePrefix("objects/")
                        }
                    }
                } finally {
                    encrypted.delete()
                    plain.delete()
                }
            }

            val cutoff = System.currentTimeMillis() - INCREMENTAL_OBJECT_GC_GRACE_MS
            val deltaFiles = listDavChildren(rootUrl, raw)
                .filter { it.name.matches(Regex("delta-[A-Za-z0-9-]+\\.nksync")) }
            deltaFiles.filter { it.name !in deltaKeys && it.lastModifiedAt?.let { time -> time < cutoff } == true }
                .forEach { deleteIncrementalFile("$rootUrl${it.name}", raw) }

            val objectsUrl = "${rootUrl}objects/"
            listDavChildren(objectsUrl, raw)
                .filter { it.name.matches(Regex("[a-f0-9-]{36}\\.nksync")) }
                .filter { it.name !in referencedObjects && it.lastModifiedAt?.let { time -> time < cutoff } == true }
                .forEach { deleteIncrementalFile("$objectsUrl${it.name}", raw) }
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private fun listDavChildren(url: String, raw: RawConfig): List<DavChildEntry> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:getlastmodified/></D:prop></D:propfind>
        """.trimIndent().toRequestBody(XML_MEDIA_TYPE)
        val xml = execute(
            Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1"),
            raw
        ).use { response ->
            if (response.code == 404) return emptyList()
            if (response.code !in listOf(200, 207)) error("读取 WebDAV 文件清单失败 (HTTP ${response.code})")
            response.body?.string().orEmpty()
        }
        val blocks = Regex(
            "<(?:(?:[A-Za-z][\\w.-]*):)?response\\b[^>]*>(.*?)</(?:(?:[A-Za-z][\\w.-]*):)?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return blocks.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = findDavValue(block, "href") ?: return@mapNotNull null
            val name = href.substringBefore('?').trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) return@mapNotNull null
            val modified = findDavValue(block, "getlastmodified")
                ?.let { value -> runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            DavChildEntry(name, modified)
        }.toList()
    }

    private fun deleteIncrementalFile(url: String, raw: RawConfig) {
        execute(Request.Builder().url(url).delete(), raw).use { response ->
            if (response.code !in listOf(200, 202, 204, 404)) {
                error("清理 WebDAV 增量文件失败 (HTTP ${response.code})")
            }
        }
    }

    private fun ensureIncrementalFolders(raw: RawConfig) {
        val base = "${normalizeBaseUrl(raw.url)}/$BACKUP_FOLDER/"
        val group = encodePathSegment(LocalDataCatalog.stableSyncGroupId(appContext, prefs.activeDbName))
        listOf(
            "${base}sync/",
            "${base}sync/v2/",
            "${base}sync/v2/$group/",
            "${base}sync/v2/$group/history/",
            "${base}sync/v2/$group/objects/"
        ).forEach { url ->
            execute(
                Request.Builder()
                    .url(url)
                    .method("MKCOL", ByteArray(0).toRequestBody(null)),
                raw
            ).use { response ->
                if (response.code !in listOf(200, 201, 204, 405)) {
                    error("创建增量同步目录失败 (HTTP ${response.code})")
                }
            }
        }
    }

    private fun putIncrementalFile(url: String, bytes: ByteArray, raw: RawConfig) {
        execute(
            Request.Builder().url(url).put(bytes.toRequestBody(BINARY_MEDIA_TYPE)),
            raw
        ).use { response ->
            if (response.code !in listOf(200, 201, 204)) {
                error("上传增量版本失败 (HTTP ${response.code})")
            }
        }
    }

    private fun putIncrementalFile(url: String, file: File, raw: RawConfig) {
        require(file.isFile && file.length() > 0L) { "增量文件为空" }
        execute(
            Request.Builder().url(url).put(file.asRequestBody(BINARY_MEDIA_TYPE)),
            raw
        ).use { response ->
            if (response.code !in listOf(200, 201, 204)) {
                error("上传增量版本失败 (HTTP ${response.code})")
            }
        }
    }

    private fun loadRemoteIncrementalRecords(
        requested: Map<String, WebDavSyncIndexEntry>,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        stagingDirectory: File
    ): Map<String, WebDavSyncRecord> {
        val result = linkedMapOf<String, WebDavSyncRecord>()
        val deltas = linkedMapOf<String, MutableSet<String>>()
        requested.forEach { (key, index) ->
            if (index.deleted) {
                result[key] = LocalWebDavIncrementalLogic.tombstone(key, index.updatedAt)
            } else {
                require(index.delta.matches(Regex("delta-[A-Za-z0-9-]+\\.nksync"))) {
                    "远端同步索引包含无效 delta 路径：$key"
                }
                deltas.getOrPut(index.delta, ::linkedSetOf).add(key)
            }
        }
        deltas.forEach { (deltaName, keys) ->
            val encrypted = File(stagingDirectory, "download-${UUID.randomUUID()}.nbotcfg")
            val plain = File(stagingDirectory, "delta-${UUID.randomUUID()}.json")
            try {
                downloadIncrementalFile(
                    "$rootUrl$deltaName",
                    encrypted,
                    raw,
                    MAX_INCREMENTAL_ENCRYPTED_DELTA_BYTES
                )
                LocalWebDavArchiveCodec.decryptFile(
                    encrypted,
                    password,
                    plain,
                    MAX_INCREMENTAL_DELTA_BYTES
                )
                val deltaRecords = WebDavSyncDeltaStreamReader.readSelected(plain, keys, stagingDirectory)
                keys.forEach { key ->
                    val record = deltaRecords[key]
                        ?: error("增量版本中缺少记录：$key")
                    val restoredAttachments = record.localAttachments.toMutableMap()
                    record.attachments.forEach { (name, reference) ->
                        if (reference.chunks.isNotEmpty()) {
                            restoredAttachments[name] = downloadIncrementalAttachment(
                                reference,
                                record.type,
                                rootUrl,
                                raw,
                                password,
                                stagingDirectory
                            )
                        }
                    }
                    result[key] = record.copy(localAttachments = restoredAttachments)
                }
            } finally {
                encrypted.delete()
                plain.delete()
            }
        }
        return result
    }

    /** 历史页只读取冲突 JSON 元数据，不下载冲突附件。 */
    private fun readRemoteConflictDetails(
        manifest: WebDavSyncManifest,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        stagingDirectory: File
    ): JsonArray {
        val requested = manifest.records
            .filter { (key, index) -> key.startsWith("$TYPE_CONFLICT_COPY:") && !index.deleted }
        if (requested.isEmpty()) return JsonArray()
        val byDelta = requested.entries.groupBy({ it.value.delta }, { it.key })
        val copies = linkedMapOf<String, WebDavSyncRecord>()
        byDelta.forEach { (deltaName, keys) ->
            require(deltaName.matches(Regex("delta-[A-Za-z0-9-]+\\.nksync"))) {
                "冲突副本包含无效 delta 路径"
            }
            val encrypted = File(stagingDirectory, "conflict-${UUID.randomUUID()}.nbotcfg")
            val plain = File(stagingDirectory, "conflict-${UUID.randomUUID()}.json")
            try {
                downloadIncrementalFile(
                    "$rootUrl$deltaName",
                    encrypted,
                    raw,
                    MAX_INCREMENTAL_ENCRYPTED_DELTA_BYTES
                )
                LocalWebDavArchiveCodec.decryptFile(
                    encrypted,
                    password,
                    plain,
                    MAX_INCREMENTAL_DELTA_BYTES
                )
                copies.putAll(WebDavSyncDeltaStreamReader.readSelected(plain, keys.toSet(), stagingDirectory))
            } finally {
                encrypted.delete()
                plain.delete()
            }
        }
        val sidesByKey = copies.values.mapNotNull { record ->
            val value = record.value ?: return@mapNotNull null
            val originalKey = value.get("original_key")?.asString.orEmpty()
            val side = value.get("side")?.asString.orEmpty()
            val nested = value.get("record")?.takeUnless { it.isJsonNull }
                ?.let { runCatching { gson.fromJson(it, WebDavSyncRecord::class.java) }.getOrNull() }
            if (originalKey.isBlank() || nested == null || side !in setOf("local", "remote")) {
                null
            } else {
                Triple(originalKey, side, record to nested)
            }
        }.groupBy({ it.first }, { it.second to it.third })

        return JsonArray().apply {
            sidesByKey.forEach { (originalKey, versions) ->
                val local = versions.firstOrNull { it.first == "local" }?.second ?: return@forEach
                val remote = versions.firstOrNull { it.first == "remote" }?.second ?: return@forEach
                val localCopy = local.first
                val localVersion = local.second
                val remoteCopy = remote.first
                val remoteVersion = remote.second
                val currentHash = manifest.records[originalKey]?.hash
                add(JsonObject().apply {
                    addProperty("key", originalKey)
                    addProperty("type", localVersion.type)
                    addProperty("id", localVersion.id)
                    addProperty("local_updated_at", localVersion.updatedAt)
                    addProperty("remote_updated_at", remoteVersion.updatedAt)
                    addProperty("local_hash", localVersion.hash)
                    addProperty("remote_hash", remoteVersion.hash)
                    addProperty(
                        "resolution",
                        if (currentHash == localVersion.hash) "local" else "remote"
                    )
                    addProperty("local_conflict_copy_key", localCopy.key)
                    addProperty("remote_conflict_copy_key", remoteCopy.key)
                })
            }
        }
    }

    private fun uploadRecordAttachments(
        record: WebDavSyncRecord,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        profileName: String,
        stagingDirectory: File
    ): WebDavSyncRecord {
        val needingUpload = record.localAttachments.filter { (name, _) ->
            record.attachments[name]?.chunks.isNullOrEmpty()
        }
        if (needingUpload.isEmpty()) return record
        val uploaded = needingUpload.mapValues { (name, source) ->
            val reference = record.attachments[name]
                ?: error("同步附件缺少校验信息：${record.key}/$name")
            uploadIncrementalAttachment(
                source,
                reference,
                record.type,
                rootUrl,
                raw,
                password,
                profileName,
                stagingDirectory
            )
        }
        return record.copy(attachments = record.attachments + uploaded, localAttachments = emptyMap())
    }

    private fun uploadIncrementalAttachment(
        source: File,
        expected: WebDavSyncFileRef,
        recordType: String,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        profileName: String,
        stagingDirectory: File
    ): WebDavSyncFileRef {
        val maxBytes = maxAttachmentBytes(recordType)
        require(source.isFile && source.length() <= maxBytes) { "同步附件超过大小限制" }
        val digest = MessageDigest.getInstance("SHA-256")
        val chunkNames = mutableListOf<String>()
        var total = 0L
        FileInputStream(source).buffered().use { input ->
            while (true) {
                val plainChunk = File(stagingDirectory, "chunk-${UUID.randomUUID()}.plain")
                val encryptedChunk = File(stagingDirectory, "chunk-${UUID.randomUUID()}.nbotcfg")
                var chunkSize = 0L
                try {
                    FileOutputStream(plainChunk).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (chunkSize < SYNC_CHUNK_SIZE) {
                            val read = input.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), SYNC_CHUNK_SIZE - chunkSize).toInt()
                            )
                            if (read < 0) break
                            if (read == 0) continue
                            chunkSize += read
                            total += read
                            require(total <= maxBytes) { "同步附件超过大小限制" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    if (chunkSize == 0L) break
                    LocalWebDavArchiveCodec.encryptFile(
                        plainChunk,
                        password,
                        profileName,
                        encryptedChunk
                    )
                    val objectName = "objects/${UUID.randomUUID()}.nksync"
                    putIncrementalFile("$rootUrl$objectName", encryptedChunk, raw)
                    chunkNames += objectName
                } finally {
                    plainChunk.delete()
                    encryptedChunk.delete()
                }
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(total == expected.size && actualHash == expected.sha256) {
            "同步期间附件发生变化，请重试：${source.name}"
        }
        return expected.copy(chunks = chunkNames)
    }

    private fun downloadIncrementalAttachment(
        reference: WebDavSyncFileRef,
        recordType: String,
        rootUrl: String,
        raw: RawConfig,
        password: String,
        stagingDirectory: File
    ): File {
        val maxBytes = maxAttachmentBytes(recordType)
        require(reference.size in 0..maxBytes) { "同步附件大小无效" }
        require(reference.sha256.matches(Regex("[a-f0-9]{64}"))) { "同步附件哈希无效" }
        require(reference.chunks.isNotEmpty() || reference.size == 0L) { "同步附件缺少分块" }
        require(reference.chunks.size <= MAX_SYNC_CHUNKS) { "同步附件分块过多" }
        val combined = File(stagingDirectory, "attachment-${UUID.randomUUID()}.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(combined).buffered().use { output ->
                reference.chunks.forEach { objectName ->
                    require(objectName.matches(Regex("objects/[a-f0-9-]{36}\\.nksync"))) {
                        "同步附件对象路径无效"
                    }
                    val encrypted = File(stagingDirectory, "object-${UUID.randomUUID()}.nbotcfg")
                    val plain = File(stagingDirectory, "object-${UUID.randomUUID()}.plain")
                    try {
                        downloadIncrementalFile(
                            "$rootUrl$objectName",
                            encrypted,
                            raw,
                            MAX_ENCRYPTED_SYNC_CHUNK_BYTES
                        )
                        LocalWebDavArchiveCodec.decryptFile(
                            encrypted,
                            password,
                            plain,
                            SYNC_CHUNK_SIZE
                        )
                        require(plain.length() <= SYNC_CHUNK_SIZE) { "同步附件分块过大" }
                        total += plain.length()
                        require(total <= maxBytes && total <= reference.size) { "同步附件超过大小限制" }
                        plain.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    } finally {
                        encrypted.delete()
                        plain.delete()
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(total == reference.size && actualHash == reference.sha256) {
                "同步附件校验失败"
            }
            return combined
        } catch (error: Throwable) {
            combined.delete()
            throw error
        }
    }

    private fun downloadIncrementalFile(url: String, target: File, raw: RawConfig, maxBytes: Long) {
        execute(Request.Builder().url(url).get(), raw).use { response ->
            if (response.code != 200) error("读取增量文件失败 (HTTP ${response.code})")
            val body = response.body ?: error("WebDAV 返回空响应")
            require(body.contentLength() < 0L || body.contentLength() <= maxBytes) {
                "增量文件超过大小限制"
            }
            body.byteStream().use { copyBounded(it, target, maxBytes) }
        }
        require(target.length() > 0L) { "远端增量文件为空" }
    }

    private fun writeEncryptedIncrementalDelta(
        delta: WebDavSyncDelta,
        password: String,
        profileName: String,
        stagingDirectory: File
    ): File {
        val plain = File(stagingDirectory, "delta-${UUID.randomUUID()}.json")
        val encrypted = File(stagingDirectory, "delta-${UUID.randomUUID()}.nbotcfg")
        try {
            FileOutputStream(plain).buffered().use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer -> gson.toJson(delta, writer) }
            }
            require(plain.length() <= MAX_INCREMENTAL_DELTA_BYTES) { "本次增量过大，请分批同步" }
            LocalWebDavArchiveCodec.encryptFile(plain, password, profileName, encrypted)
            return encrypted
        } finally {
            plain.delete()
        }
    }

    private fun maxAttachmentBytes(recordType: String): Long = when (recordType) {
        TYPE_MESSAGE -> MAX_MESSAGE_AUDIO_BYTES
        TYPE_MESSAGE_IMAGE -> MAX_MESSAGE_IMAGE_BYTES
        TYPE_STICKER -> MAX_STICKER_BYTES
        TYPE_FILE -> MAX_INCREMENTAL_FILE_BYTES
        TYPE_CONFLICT_COPY -> maxOf(MAX_MESSAGE_AUDIO_BYTES, MAX_MESSAGE_IMAGE_BYTES)
        else -> error("不支持的同步附件类型：$recordType")
    }

    private fun createConflictCopy(
        source: WebDavSyncRecord,
        originalKey: String,
        side: String,
        id: String,
        updatedAt: String
    ): WebDavSyncRecord {
        val value = JsonObject().apply {
            addProperty("original_key", originalKey)
            addProperty("side", side)
            add("record", gson.toJsonTree(source.copy(localAttachments = emptyMap())))
        }
        val generated = LocalWebDavIncrementalLogic.record(
            type = TYPE_CONFLICT_COPY,
            id = id,
            updatedAt = updatedAt,
            value = value,
            localAttachments = source.localAttachments
        )
        return generated.copy(
            attachments = generated.attachments + source.attachments,
            localAttachments = source.localAttachments
        )
    }

    private fun resolveIncrementalRootUrl(baseUrl: String, profileName: String): String =
        "${normalizeBaseUrl(baseUrl)}/$BACKUP_FOLDER/sync/v2/" +
            "${encodePathSegment(LocalDataCatalog.stableSyncGroupId(appContext, profileName))}/"

    private fun resolveLegacyIncrementalRootUrl(baseUrl: String, profileName: String): String =
        "${normalizeBaseUrl(baseUrl)}/$BACKUP_FOLDER/sync/${encodePathSegment(profileName)}/"

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun getOrCreateSyncDeviceId(): String {
        val current = configPrefs.getString(KEY_SYNC_DEVICE_ID, "").orEmpty()
        if (current.isNotBlank()) return current
        return UUID.randomUUID().toString().also {
            configPrefs.edit().putString(KEY_SYNC_DEVICE_ID, it).apply()
        }
    }

    private suspend fun buildArchive(includePortraits: Boolean): File {
        val profileName = prefs.activeDbName
        val dbName = "$profileName.db"
        val db = NekobotDatabase.get(appContext, profileName)
        LocalDataCatalog.validateRoomCoverage(db.openHelper.readableDatabase)
        db.aiModelDao().migrateStoredSecrets()
        db.mcpServerDao().migrateStoredSecrets()
        db.apiKeyDao().migrateStoredSecrets()
        runCatching {
            var busy = 0
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .use { cursor ->
                    if (cursor.moveToFirst()) {
                        busy = cursor.getInt(0)
                    }
                }
            check(busy == 0) { "数据库正忙，请稍后重试" }
        }.getOrElse { throw IllegalStateException("数据库写入检查点失败：${it.message}", it) }

        val dbFile = appContext.getDatabasePath(dbName)
        require(dbFile.isFile && dbFile.length() > 0L) { "当前本地数据库不存在" }

        val output = File.createTempFile("webdav-backup-", ".zip", appContext.cacheDir)
        try {
            ZipOutputStream(
                BufferedOutputStream(SizeLimitedOutputStream(output.outputStream(), MAX_ARCHIVE_SIZE))
            ).use { zip ->
                putZipEntry(zip, ENTRY_DATABASE, dbFile)
                putZipEntry(
                    zip,
                    ENTRY_CREDENTIALS,
                    gson.toJson(buildCredentialBundle(db)).toByteArray()
                )
                val portableArchiveManager = PortableDataArchiveManager(appContext)
                putZipEntry(zip, ENTRY_APP_SETTINGS, portableArchiveManager.captureAppSettings())
                val globalMemory = GlobalAgentMemoryStore.memoryFileFor(appContext, profileName)
                if (globalMemory.isFile) putZipEntry(zip, ENTRY_GLOBAL_MEMORY, globalMemory)
                val currentSessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
                val catalogArchive = writeCatalogFiles(zip, profileName, currentSessionIds)

                val metadata = JsonObject().apply {
                    addProperty("version", 3)
                    addProperty("archive_id", UUID.randomUUID().toString())
                    addProperty("profile_id", LocalDataCatalog.stableProfileId(appContext, profileName))
                    addProperty("sync_group_id", LocalDataCatalog.stableSyncGroupId(appContext, profileName))
                    addProperty("profile_name", profileName)
                    addProperty("database_name", dbName)
                    addProperty("database_version", db.openHelper.readableDatabase.version)
                    addProperty("created_at", nowIso())
                    addProperty("includes_portraits", includePortraits)
                    addProperty("includes_message_audio", true)
                    add("catalog_roots", JsonArray().apply { catalogArchive.first.forEach(::add) })
                    add("catalog_files", catalogArchive.second)
                    add("categories", JsonArray().apply {
                        LocalDataCatalog.descriptors.forEach { descriptor ->
                            add(JsonObject().apply {
                                addProperty("id", descriptor.id)
                                addProperty("scope", descriptor.scope.name.lowercase())
                                add("dependencies", JsonArray().apply {
                                    descriptor.dependencies.sorted().forEach(::add)
                                })
                            })
                        }
                    })
                }
                putZipEntry(zip, ENTRY_METADATA, gson.toJson(metadata).toByteArray())

                val tokenPrefs =
                    appContext.getSharedPreferences("token_usage_$dbName", Context.MODE_PRIVATE)
                putZipEntry(
                    zip,
                    ENTRY_TOKEN_USAGE,
                    gson.toJson(serializePreferences(tokenPrefs)).toByteArray()
                )

                val achievementPrefs =
                    appContext.getSharedPreferences(ACHIEVEMENT_PREF_NAME, Context.MODE_PRIVATE)
                val achievement = JsonObject().apply {
                    val key = AchievementManager.storageKeyForScope("local:$profileName")
                    addProperty("storage_key", key)
                    addProperty("value", achievementPrefs.getString(key, "").orEmpty())
                }
                putZipEntry(
                    zip,
                    ENTRY_ACHIEVEMENTS,
                    gson.toJson(achievement).toByteArray()
                )

                val audioDir = File(appContext.filesDir, "tts")
                audioDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        require(file.length() <= MAX_MESSAGE_AUDIO_BYTES) { "聊天音频文件过大" }
                        val relative = file.relativeTo(audioDir)
                            .invariantSeparatorsPath
                            .takeIf(::isSafeRelativePath)
                            ?: return@forEach
                        putZipEntry(zip, "$ENTRY_MESSAGE_AUDIO_PREFIX$relative", file)
                    }

                if (includePortraits) {
                    val portraitDir = File(appContext.cacheDir, "portraits/$profileName")
                    portraitDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(portraitDir)
                                .invariantSeparatorsPath
                                .takeIf { isSafeRelativePath(it) }
                                ?: return@forEach
                            require(file.length() <= MAX_PORTRAIT_BYTES) { "立绘文件过大" }
                            putZipEntry(zip, "$ENTRY_PORTRAITS_PREFIX$relative", file)
                        }

                    // 消息生图与角色立绘都保存在 filesDir/portraits；独立前缀保留原有缓存立绘的恢复语义。
                    val localPortraitDir = File(appContext.filesDir, "portraits")
                    localPortraitDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(localPortraitDir)
                                .invariantSeparatorsPath
                                .takeIf { isSafeRelativePath(it) }
                                ?: return@forEach
                            require(file.length() <= MAX_PORTRAIT_BYTES) { "立绘文件过大" }
                            putZipEntry(zip, "$ENTRY_LOCAL_PORTRAITS_PREFIX$relative", file)
                        }

                    val coverDir = File(appContext.filesDir, "worldbook_covers")
                    coverDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(coverDir)
                                .invariantSeparatorsPath
                                .takeIf(::isSafeRelativePath)
                                ?: return@forEach
                            require(file.length() <= MAX_PORTRAIT_BYTES) { "世界书封面过大" }
                            putZipEntry(zip, "$ENTRY_WORLD_BOOK_COVERS_PREFIX$relative", file)
                        }

                    // 自定义表情包图片
                    val stickerDir = File(appContext.filesDir, "stickers")
                    stickerDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(stickerDir)
                                .invariantSeparatorsPath
                                .takeIf(::isSafeRelativePath)
                                ?: return@forEach
                            require(file.length() <= MAX_STICKER_BYTES) { "表情图片过大" }
                            putZipEntry(zip, "$ENTRY_STICKERS_PREFIX$relative", file)
                        }
                }
            }
            require(output.length() <= MAX_ARCHIVE_SIZE) { "备份包超过 1 GB 限制" }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    /** WebDAV 全量包补齐数据目录里除旧媒体专用前缀外的可移植文件根。 */
    private fun writeCatalogFiles(
        zip: ZipOutputStream,
        profileName: String,
        currentSessionIds: Set<String>
    ): Pair<List<String>, JsonArray> {
        var totalBytes = 0L
        val fileInventory = JsonArray()
        CATALOG_BACKUP_ROOTS.forEach { (categoryId, rootId) ->
            val category = PortableDataCategory.fromId(categoryId)
                ?: error("未知数据目录类别：$categoryId")
            val root = LocalDataCatalog.fileRoots(appContext, category)
                .firstOrNull { it.first == rootId }?.second
                ?: error("数据目录缺少文件根：$categoryId/$rootId")
            if (!root.isDirectory) return@forEach
            val canonicalRoot = root.canonicalFile
            root.walkTopDown()
                .onEnter { directory ->
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        !(categoryId == PortableDataCategory.EXTENSIONS.id &&
                            rootId == "plugin_packages" && directory != root &&
                            directory.name.startsWith(".staging-"))
                }
                .forEach { file ->
                    if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                    if (categoryId == PortableDataCategory.EXTENSIONS.id && rootId == "plugin_files" &&
                        file.relativeTo(root).invariantSeparatorsPath.split('/').any { it.startsWith('.') }
                    ) return@forEach
                    val canonicalFile = file.canonicalFile
                    require(canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
                        "数据目录包含越界文件：${file.name}"
                    }
                    val relative = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                    if (categoryId == PortableDataCategory.WORKSPACE.id &&
                        relative.substringBefore('/') !in currentSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME
                    ) return@forEach
                    if (rootId == "cached_portraits" && relative.substringBefore('/') != profileName) {
                        return@forEach
                    }
                    require(isSafeRelativePath(relative)) { "数据目录包含不安全路径：$relative" }
                    require(file.length() <= MAX_CATALOG_FILE_BYTES) { "文件 ${file.name} 超过归档单文件上限" }
                    totalBytes += file.length()
                    require(totalBytes <= MAX_ARCHIVE_SIZE) { "可移植文件总量超过 1 GB 限制" }
                    val entryName = "$ENTRY_CATALOG_FILES_PREFIX$categoryId/$rootId/$relative"
                    if (categoryId == PortableDataCategory.EXTENSIONS.id &&
                        rootId == "plugin_packages" && file.name == PLUGIN_STATE_ENTRY
                    ) {
                        val bytes = "{\"enabled\":false,\"installedAt\":${file.lastModified()}}"
                            .toByteArray(Charsets.UTF_8)
                        putZipEntry(
                            zip,
                            entryName,
                            bytes
                        )
                        fileInventory.add(catalogFileEntry(entryName, bytes.size.toLong(), sha256(bytes)))
                    } else {
                        putZipEntry(zip, entryName, canonicalFile)
                        fileInventory.add(catalogFileEntry(entryName, canonicalFile.length(), sha256(canonicalFile)))
                    }
                }
        }
        return CATALOG_BACKUP_ROOTS.map { "${it.first}/${it.second}" } to fileInventory
    }

    private fun catalogFileEntry(path: String, size: Long, sha256: String): JsonObject = JsonObject().apply {
        addProperty("path", path)
        addProperty("size", size)
        addProperty("sha256", sha256)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun restoreCatalogFiles(
        entries: Map<String, File>,
        profileName: String,
        currentSessionIds: Set<String>
    ) {
        val roots = CATALOG_BACKUP_ROOTS.associate { (categoryId, rootId) ->
            val category = PortableDataCategory.fromId(categoryId)
                ?: error("未知数据目录类别：$categoryId")
            val root = LocalDataCatalog.fileRoots(appContext, category)
                .firstOrNull { it.first == rootId }?.second
                ?: error("数据目录缺少文件根：$categoryId/$rootId")
            "$ENTRY_CATALOG_FILES_PREFIX$categoryId/$rootId/" to Triple(categoryId, rootId, root)
        }
        entries.forEach { (name, source) ->
            val matching = roots.entries.firstOrNull { name.startsWith(it.key) } ?: return@forEach
            val relative = name.removePrefix(matching.key)
            if (!isSafeRelativePath(relative)) return@forEach
            val (categoryId, rootId, root) = matching.value
            if (categoryId == PortableDataCategory.WORKSPACE.id &&
                relative.substringBefore('/') !in currentSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME
            ) return@forEach
            if (categoryId == PortableDataCategory.EXTENSIONS.id && rootId == "plugin_packages" &&
                relative.split('/').any { it.startsWith(".staging-") }
            ) return@forEach
            if (categoryId == PortableDataCategory.EXTENSIONS.id && rootId == "plugin_files" &&
                relative.split('/').any { it.startsWith('.') }
            ) return@forEach
            if (rootId == "cached_portraits" && relative.substringBefore('/') != profileName) {
                return@forEach
            }
            val canonicalRoot = root.canonicalFile
            val target = File(canonicalRoot, relative).canonicalFile
            require(target.path.startsWith(canonicalRoot.path + File.separator)) { "归档文件路径越界" }
            target.parentFile?.mkdirs()
            if (categoryId == PortableDataCategory.EXTENSIONS.id &&
                rootId == "plugin_packages" && target.name == PLUGIN_STATE_ENTRY
            ) {
                target.writeText(
                    "{\"enabled\":false,\"installedAt\":${System.currentTimeMillis()}}",
                    Charsets.UTF_8
                )
            } else {
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private suspend fun restoreArchive(archive: File, includePortraits: Boolean) {
        val extractionDirectory = File(
            appContext.cacheDir,
            "webdav-extract-${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建 WebDAV 解压目录" } }
        try {
            val entries = unzipToFiles(archive, extractionDirectory)
            validateWebDavCatalogFiles(entries)
            val databaseFile = entries[ENTRY_DATABASE] ?: error("备份包中缺少数据库")
            require(
                databaseFile.length() >= SQLITE_HEADER.size &&
                    FileInputStream(databaseFile).use { input ->
                        val header = ByteArray(SQLITE_HEADER.size)
                        input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
                    }
            ) { "备份包中的数据库格式无效" }

            val profileName = prefs.activeDbName
            val dbName = "$profileName.db"
            val destination = appContext.getDatabasePath(dbName)
            destination.parentFile?.mkdirs()
            val restoreJournal = createFullRestoreJournal(
                profileName = profileName,
                dbName = dbName,
                includePortraits = includePortraits,
                entries = entries,
                restoredSessionIds = readArchiveSessionIds(databaseFile)
            )
            val databaseStaging = File(
                destination.parentFile,
                "$dbName.${UUID.randomUUID()}.webdav.tmp"
            )

            runCatching {
                databaseFile.copyTo(databaseStaging, overwrite = true)

                ServiceContainer.localRepository.close()
                NekobotDatabase.closeProfile(profileName)
                listOf(
                    destination,
                    appContext.getDatabasePath("$dbName-journal"),
                    appContext.getDatabasePath("$dbName-wal"),
                    appContext.getDatabasePath("$dbName-shm")
                ).forEach { file -> if (file.exists() && !file.delete()) error("无法替换数据库") }

                if (!databaseStaging.renameTo(destination)) {
                    databaseStaging.copyTo(destination, overwrite = true)
                    databaseStaging.delete()
                }

                appContext.getSharedPreferences(
                    "token_usage_$dbName",
                    Context.MODE_PRIVATE
                ).edit().clear().commit()
                AchievementManager.clearScope("local:$profileName")

                entries[ENTRY_TOKEN_USAGE]?.let { rawFile ->
                    val tokenPrefs = appContext.getSharedPreferences(
                        "token_usage_$dbName",
                        Context.MODE_PRIVATE
                    )
                    val tokenUsage = JsonParser.parseString(
                        String(readBounded(rawFile, MAX_SMALL_ENTRY_BYTES), Charsets.UTF_8)
                    ).asJsonObject
                    restorePreferences(tokenPrefs, tokenUsage)
                }

                entries[ENTRY_ACHIEVEMENTS]?.let { rawFile ->
                    val obj = JsonParser.parseString(
                        String(readBounded(rawFile, MAX_SMALL_ENTRY_BYTES), Charsets.UTF_8)
                    ).asJsonObject
                    val key = AchievementManager.storageKeyForScope("local:$profileName")
                    val value = obj.get("value")?.asString.orEmpty()
                    appContext.getSharedPreferences(
                        ACHIEVEMENT_PREF_NAME,
                        Context.MODE_PRIVATE
                    ).edit().apply {
                        if (value.isBlank()) remove(key) else putString(key, value)
                    }.commit()
                }

                val audioEntries = entries.filterKeys { it.startsWith(ENTRY_MESSAGE_AUDIO_PREFIX) }
                audioEntries.forEach { (path, file) ->
                    val relative = path.removePrefix(ENTRY_MESSAGE_AUDIO_PREFIX)
                    if (isSafeRelativePath(relative)) {
                        require(file.length() <= MAX_MESSAGE_AUDIO_BYTES) { "聊天音频文件过大" }
                        val audioDir = File(appContext.filesDir, "tts").canonicalFile
                        val target = File(audioDir, relative).canonicalFile
                        if (target.path.startsWith(audioDir.path + File.separator)) {
                            target.parentFile?.mkdirs()
                            file.copyTo(target, overwrite = true)
                        }
                    }
                }

                val portraitEntries = entries.filterKeys { it.startsWith(ENTRY_PORTRAITS_PREFIX) }
                if (includePortraits && portraitEntries.isNotEmpty()) {
                    val portraitDir = File(appContext.cacheDir, "portraits/$profileName")
                    if (portraitDir.exists()) portraitDir.deleteRecursively()
                    portraitEntries.forEach { (path, file) ->
                        val relative = path.removePrefix(ENTRY_PORTRAITS_PREFIX)
                        if (isSafeRelativePath(relative)) {
                            File(portraitDir, relative).apply {
                                parentFile?.mkdirs()
                                file.copyTo(this, overwrite = true)
                            }
                        }
                    }
                }

                val localPortraitEntries = entries.filterKeys {
                    it.startsWith(ENTRY_LOCAL_PORTRAITS_PREFIX)
                }
                if (includePortraits && localPortraitEntries.isNotEmpty()) {
                    val portraitDir = File(appContext.filesDir, "portraits")
                    localPortraitEntries.forEach { (path, file) ->
                        val relative = path.removePrefix(ENTRY_LOCAL_PORTRAITS_PREFIX)
                        if (isSafeRelativePath(relative)) {
                            File(portraitDir, relative).apply {
                                parentFile?.mkdirs()
                                file.copyTo(this, overwrite = true)
                            }
                        }
                    }
                }

                val worldBookCoverEntries = entries.filterKeys {
                    it.startsWith(ENTRY_WORLD_BOOK_COVERS_PREFIX)
                }
                if (includePortraits && worldBookCoverEntries.isNotEmpty()) {
                    val coverDir = File(appContext.filesDir, "worldbook_covers")
                    if (coverDir.exists()) coverDir.deleteRecursively()
                    worldBookCoverEntries.forEach { (path, file) ->
                        val relative = path.removePrefix(ENTRY_WORLD_BOOK_COVERS_PREFIX)
                        if (isSafeRelativePath(relative)) {
                            File(coverDir, relative).apply {
                                parentFile?.mkdirs()
                                file.copyTo(this, overwrite = true)
                            }
                        }
                    }
                }

                // 自定义表情包：按文件名合并恢复，避免覆盖本机已有表情。
                val stickerEntries = entries.filterKeys { it.startsWith(ENTRY_STICKERS_PREFIX) }
                if (includePortraits && stickerEntries.isNotEmpty()) {
                    val stickerDir = File(appContext.filesDir, "stickers").canonicalFile
                    stickerEntries.forEach { (path, file) ->
                        val relative = path.removePrefix(ENTRY_STICKERS_PREFIX)
                        if (!isSafeRelativePath(relative)) return@forEach
                        val target = runCatching {
                            File(stickerDir, relative).canonicalFile
                        }.getOrNull() ?: return@forEach
                        if (target.path.startsWith(stickerDir.path + File.separator)) {
                            target.parentFile?.mkdirs()
                            file.copyTo(target, overwrite = true)
                        }
                    }
                }

                ServiceContainer.switchLocalDb(profileName)
                val restoredDb = NekobotDatabase.get(appContext, profileName)
                restoredDb.openHelper.writableDatabase
                restoreCatalogFiles(
                    entries = entries,
                    profileName = profileName,
                    currentSessionIds = restoredDb.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
                )
                entries[ENTRY_GLOBAL_MEMORY]?.let { source ->
                    val target = GlobalAgentMemoryStore.memoryFileFor(appContext, profileName)
                    require(source.length() <= MAX_SMALL_ENTRY_BYTES) { "Agent 长期记忆文件过大" }
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                entries[ENTRY_APP_SETTINGS]?.let { source ->
                    PortableDataArchiveManager(appContext).restoreAppSettings(
                        readBounded(source, MAX_SMALL_ENTRY_BYTES)
                    )
                }
                normalizeRestoredMessageAudioUrls(restoredDb)
                normalizeRestoredStickerPaths(restoredDb)
                entries[ENTRY_CREDENTIALS]?.let { rawFile ->
                    restoreCredentialBundle(
                        restoredDb,
                        readBounded(rawFile, MAX_SMALL_ENTRY_BYTES)
                    )
                }
                if (entries.keys.any { it.startsWith(ENTRY_CATALOG_FILES_PREFIX) }) {
                    val pluginIds = entries.keys.asSequence()
                        .filter { it.startsWith(ENTRY_CATALOG_PLUGIN_PREFIX) }
                        .map { it.removePrefix(ENTRY_CATALOG_PLUGIN_PREFIX).substringBefore('/') }
                        .filter { it.isNotBlank() && !it.startsWith('.') }
                        .toSet()
                    pluginIds.forEach { pluginId ->
                        val stateFile = File(appContext.filesDir, "plugins/$pluginId/.plugin-state.json")
                        stateFile.parentFile?.mkdirs()
                        stateFile.writeText(
                            "{\"enabled\":false,\"installedAt\":${System.currentTimeMillis()}}",
                            Charsets.UTF_8
                        )
                        ServiceContainer.pluginGrants.revoke(pluginId)
                    }
                    ServiceContainer.pluginManager.reload()
                }
                entries[ENTRY_METADATA]?.let { metadataFile ->
                    val restoredMetadata = JsonParser.parseString(
                        String(readBounded(metadataFile, MAX_SMALL_ENTRY_BYTES), Charsets.UTF_8)
                    ).asJsonObject
                    LocalDataCatalog.adoptProfileIdIfAbsent(
                        appContext,
                        profileName,
                        restoredMetadata.get("profile_id")?.asString.orEmpty()
                    )
                    LocalDataCatalog.adoptSyncGroupIdIfAbsent(
                        appContext,
                        profileName,
                        restoredMetadata.get("sync_group_id")?.asString.orEmpty()
                    )
                }
            }.onFailure { failure ->
                runCatching {
                    ServiceContainer.localRepository.close()
                    NekobotDatabase.closeProfile(profileName)
                    rollbackFullRestoreJournal(appContext, restoreJournal)
                    ServiceContainer.switchLocalDb(profileName)
                }
                throw failure
            }

            markFullRestoreCommitted(appContext, restoreJournal)
            restoreJournal.directory.deleteRecursively()
            databaseStaging.delete()
        } finally {
            extractionDirectory.deleteRecursively()
        }
    }

    private data class FullRestoreTransaction(
        val directory: File,
        var journal: FullRestoreJournal
    )

    private fun readArchiveSessionIds(databaseFile: File): Set<String> {
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        return try {
            database.rawQuery("SELECT id FROM local_sessions", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        } finally {
            database.close()
        }
    }

    private fun createFullRestoreJournal(
        profileName: String,
        dbName: String,
        includePortraits: Boolean,
        entries: Map<String, File>,
        restoredSessionIds: Set<String>
    ): FullRestoreTransaction {
        val targets = linkedSetOf<File>()
        fun addTarget(file: File) {
            val canonical = file.canonicalFile
            val dataRoot = appContext.dataDir.canonicalPath + File.separator
            val cacheRoot = appContext.cacheDir.canonicalPath + File.separator
            require(canonical.path.startsWith(dataRoot) || canonical.path.startsWith(cacheRoot)) {
                "恢复目标超出应用数据目录"
            }
            targets += canonical
        }

        val database = appContext.getDatabasePath(dbName)
        listOf(database, appContext.getDatabasePath("$dbName-journal"),
            appContext.getDatabasePath("$dbName-wal"), appContext.getDatabasePath("$dbName-shm"))
            .forEach(::addTarget)
        addTarget(File(appContext.dataDir, "shared_prefs"))

        entries.keys.filter { it.startsWith(ENTRY_MESSAGE_AUDIO_PREFIX) }.forEach { name ->
            val relative = name.removePrefix(ENTRY_MESSAGE_AUDIO_PREFIX)
            if (isSafeRelativePath(relative)) addTarget(File(appContext.filesDir, "tts/$relative"))
        }
        if (includePortraits) {
            if (entries.keys.any { it.startsWith(ENTRY_PORTRAITS_PREFIX) }) {
                addTarget(File(appContext.cacheDir, "portraits/$profileName"))
            }
            entries.keys.filter { it.startsWith(ENTRY_LOCAL_PORTRAITS_PREFIX) }.forEach { name ->
                val relative = name.removePrefix(ENTRY_LOCAL_PORTRAITS_PREFIX)
                if (isSafeRelativePath(relative)) addTarget(File(appContext.filesDir, "portraits/$relative"))
            }
            if (entries.keys.any { it.startsWith(ENTRY_WORLD_BOOK_COVERS_PREFIX) }) {
                addTarget(File(appContext.filesDir, "worldbook_covers"))
            }
            entries.keys.filter { it.startsWith(ENTRY_STICKERS_PREFIX) }.forEach { name ->
                val relative = name.removePrefix(ENTRY_STICKERS_PREFIX)
                if (isSafeRelativePath(relative)) addTarget(File(appContext.filesDir, "stickers/$relative"))
            }
        }
        val catalogRoots = CATALOG_BACKUP_ROOTS.associate { (categoryId, rootId) ->
            val category = PortableDataCategory.fromId(categoryId) ?: error("未知归档类别")
            val root = LocalDataCatalog.fileRoots(appContext, category)
                .firstOrNull { it.first == rootId }?.second ?: error("归档文件根目录缺失")
            "$ENTRY_CATALOG_FILES_PREFIX$categoryId/$rootId/" to Triple(categoryId, rootId, root)
        }
        entries.forEach { (name, _) ->
            val (prefix, descriptor) = catalogRoots.entries.firstOrNull { name.startsWith(it.key) }
                ?.let { it.key to it.value } ?: return@forEach
            val relative = name.removePrefix(prefix)
            if (!isSafeRelativePath(relative)) return@forEach
            val (categoryId, rootId, root) = descriptor
            if (categoryId == PortableDataCategory.WORKSPACE.id &&
                relative.substringBefore('/') !in restoredSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME
            ) return@forEach
            if (categoryId == PortableDataCategory.EXTENSIONS.id && rootId == "plugin_packages" &&
                (relative.split('/').any { it.startsWith(".staging-") } || relative == PLUGIN_STATE_ENTRY)
            ) return@forEach
            if (categoryId == PortableDataCategory.EXTENSIONS.id && rootId == "plugin_files" &&
                relative.split('/').any { it.startsWith('.') }
            ) return@forEach
            addTarget(File(root, relative))
        }
        entries[ENTRY_GLOBAL_MEMORY]?.let {
            addTarget(GlobalAgentMemoryStore.memoryFileFor(appContext, profileName))
        }
        entries.keys.asSequence()
            .filter { it.startsWith(ENTRY_CATALOG_PLUGIN_PREFIX) }
            .map { it.removePrefix(ENTRY_CATALOG_PLUGIN_PREFIX).substringBefore('/') }
            .filter { it.isNotBlank() && !it.startsWith('.') }
            .distinct()
            .forEach { addTarget(File(appContext.filesDir, "plugins/$it/$PLUGIN_STATE_ENTRY")) }

        val normalizedTargets = targets.sortedBy { it.path.length }.filterIndexed { index, target ->
            targets.none { parent ->
                parent != target && parent.isDirectory &&
                    target.path.startsWith(parent.path + File.separator)
            }
        }
        val backupBytes = normalizedTargets.sumOf(::treeSize)
        require(appContext.filesDir.usableSpace >= backupBytes + MIN_RESTORE_ROLLBACK_FREE_BYTES) {
            "设备剩余空间不足，无法安全保留恢复回滚点"
        }

        val transactionDirectory = File(
            appContext.filesDir,
            "$FULL_RESTORE_JOURNAL_DIR/${UUID.randomUUID()}"
        ).apply { check(mkdirs()) { "无法创建恢复事务目录" } }
        val snapshotDirectory = File(transactionDirectory, "snapshots").apply {
            check(mkdirs()) { "无法创建恢复回滚目录" }
        }
        val journalEntries = normalizedTargets.mapIndexed { index, target ->
            val snapshotName = index.toString()
            if (target.exists()) {
                copyPathWithoutLinks(target, File(snapshotDirectory, snapshotName))
            }
            FullRestoreJournalEntry(target.path, snapshotName, target.exists())
        }
        val previousSettings = Base64.getEncoder().encodeToString(
            PortableDataArchiveManager(appContext).captureAppSettings()
        )
        val journal = FullRestoreJournal("prepared", journalEntries, previousSettings)
        FullWebDavRestoreRecovery.writeJournal(transactionDirectory, journal)
        return FullRestoreTransaction(transactionDirectory, journal)
    }

    private fun treeSize(file: File): Long {
        if (!file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::treeSize) ?: error("无法读取恢复目标目录：${file.name}")
    }

    private fun copyPathWithoutLinks(source: File, target: File) {
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) return
        if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory) { "无法创建回滚目录" }
            val children = source.listFiles() ?: error("无法读取恢复目标目录：${source.name}")
            children.forEach { copyPathWithoutLinks(it, File(target, it.name)) }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun rollbackFullRestoreJournal(
        context: Context,
        transaction: FullRestoreTransaction
    ) {
        FullWebDavRestoreRecovery.rollback(context, transaction.directory, transaction.journal)
        val settings = runCatching {
            Base64.getDecoder().decode(transaction.journal.previousAppSettingsBase64)
        }.getOrNull()
        if (settings != null) runCatching {
            PortableDataArchiveManager(appContext).restoreAppSettings(settings)
        }
    }

    private fun markFullRestoreCommitted(
        context: Context,
        transaction: FullRestoreTransaction
    ) {
        transaction.journal = transaction.journal.copy(state = "committed")
        FullWebDavRestoreRecovery.writeJournal(transaction.directory, transaction.journal)
    }

    private fun validateWebDavCatalogFiles(entries: Map<String, File>) {
        val metadataFile = entries[ENTRY_METADATA] ?: return
        val metadata = JsonParser.parseString(
            String(readBounded(metadataFile, MAX_SMALL_ENTRY_BYTES), Charsets.UTF_8)
        ).asJsonObject
        val version = metadata.get("version")?.asInt ?: 0
        if (version < 3) return
        require(metadata.get("archive_id")?.asString?.isNotBlank() == true) {
            "WebDAV 归档缺少 archive_id"
        }
        require(metadata.get("profile_id")?.asString?.isNotBlank() == true) {
            "WebDAV 归档缺少档案稳定 ID"
        }
        val inventory = metadata.getAsJsonArray("catalog_files")
            ?: error("WebDAV 归档缺少文件校验清单")
        val expected = linkedMapOf<String, Pair<Long, String>>()
        inventory.forEach { element ->
            val item = element.asJsonObject
            val path = item.get("path")?.asString.orEmpty()
            require(isSafeRelativePath(path) && path.startsWith(ENTRY_CATALOG_FILES_PREFIX)) {
                "WebDAV 归档文件清单包含不安全路径"
            }
            val size = item.get("size")?.asLong ?: error("WebDAV 归档文件清单缺少大小")
            val hash = item.get("sha256")?.asString.orEmpty()
            require(size >= 0L && hash.matches(Regex("[0-9a-f]{64}"))) {
                "WebDAV 归档文件清单校验信息无效"
            }
            require(expected.put(path, size to hash) == null) { "WebDAV 归档文件清单存在重复路径" }
        }
        val actualPaths = entries.keys.filter { it.startsWith(ENTRY_CATALOG_FILES_PREFIX) }.toSet()
        require(actualPaths == expected.keys) { "WebDAV 归档文件清单与实际内容不一致" }
        expected.forEach { (path, descriptor) ->
            val file = entries[path] ?: error("WebDAV 归档缺少文件：$path")
            require(file.length() == descriptor.first && sha256(file) == descriptor.second) {
                "WebDAV 归档文件校验失败：${path.removePrefix(ENTRY_CATALOG_FILES_PREFIX)}"
            }
        }
    }

    private suspend fun normalizeRestoredMessageAudioUrls(db: NekobotDatabase) {
        db.messageDao().listAll().forEach { entity ->
            val file = messageAudioFile(entity.audioUrl) ?: return@forEach
            if (!file.isFile) return@forEach
            val normalized = android.net.Uri.fromFile(file).toString()
            if (entity.audioUrl != normalized) {
                db.messageDao().updateAudioUrl(
                    id = entity.id,
                    audioUrl = normalized,
                    updatedAt = entity.audioUpdatedAt ?: nowIso()
                )
            }
        }
    }

    /** 恢复后把表情的 file_path 重新指向当前设备的 stickers 目录。 */
    private suspend fun normalizeRestoredStickerPaths(db: NekobotDatabase) {
        db.stickerDao().listAll().forEach { entity ->
            val file = stickerFile(entity.fileName) ?: return@forEach
            if (!file.isFile) return@forEach
            val normalized = android.net.Uri.fromFile(file).toString()
            if (entity.filePath != normalized) {
                db.stickerDao().upsert(entity.copy(filePath = normalized))
            }
        }
    }

    /**
     * Keystore 密文是设备绑定的。WebDAV 外层已经由用户密码加密，因此在包内额外保存
     * 一份最小化明文凭据清单，恢复到新设备时再用新设备 Keystore 重新封装。
     */
    private suspend fun buildCredentialBundle(db: NekobotDatabase): JsonObject =
        JsonObject().apply {
            add(
                "ai_models",
                gson.toJsonTree(
                    db.aiModelDao().listAll().map { model ->
                        mapOf(
                            "id" to model.id,
                            "api_key" to model.apiKey,
                            "proxy_url" to model.proxyUrl,
                            "tts_headers" to model.ttsHeaders,
                            "tts_body_template" to model.ttsBodyTemplate,
                            "stt_headers" to model.sttHeaders
                        )
                    }
                )
            )
            add(
                "api_keys",
                gson.toJsonTree(
                    db.apiKeyDao().listAll().map { key ->
                        mapOf("id" to key.id, "key" to key.key)
                    }
                )
            )
            add(
                "mcp_servers",
                gson.toJsonTree(
                    db.mcpServerDao().listAll().map { server ->
                        mapOf(
                            "id" to server.id,
                            "url" to server.url,
                            "headers_json" to server.headersJson,
                            "args_json" to server.argsJson,
                            "env_json" to server.envJson
                        )
                    }
                )
            )
            add(
                "oauth_accounts",
                gson.toJsonTree(
                    db.oauthAccountDao().listAll().mapNotNull { account ->
                        runCatching {
                            mapOf(
                                "id" to account.id,
                                "credentials" to oauthSecrets.decrypt(account.encryptedCredentials)
                            )
                        }.getOrNull()
                    }
                )
            )
        }

    private suspend fun restoreCredentialBundle(db: NekobotDatabase, raw: ByteArray) {
        val root = JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject
        root.getAsJsonArray("ai_models")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString.orEmpty()
            val existing = db.aiModelDao().getById(id) ?: return@forEach
            db.aiModelDao().upsert(
                existing.copy(
                    apiKey = obj.stringOrEmpty("api_key"),
                    proxyUrl = obj.stringOrEmpty("proxy_url"),
                    ttsHeaders = obj.stringOrEmpty("tts_headers"),
                    ttsBodyTemplate = obj.stringOrEmpty("tts_body_template"),
                    sttHeaders = obj.stringOrEmpty("stt_headers")
                )
            )
        }
        root.getAsJsonArray("api_keys")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString.orEmpty()
            val existing = db.apiKeyDao().getById(id) ?: return@forEach
            db.apiKeyDao().upsert(existing.copy(key = obj.stringOrEmpty("key")))
        }
        root.getAsJsonArray("mcp_servers")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString.orEmpty()
            val existing = db.mcpServerDao().getById(id) ?: return@forEach
            db.mcpServerDao().upsert(
                existing.copy(
                    url = obj.stringOrNull("url"),
                    headersJson = obj.stringOrNull("headers_json"),
                    argsJson = obj.stringOrNull("args_json"),
                    envJson = obj.stringOrNull("env_json")
                )
            )
        }
        root.getAsJsonArray("oauth_accounts")?.forEach { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString.orEmpty()
            val credentials = obj.stringOrEmpty("credentials")
            val existing = db.oauthAccountDao().getById(id) ?: return@forEach
            if (credentials.isNotEmpty()) {
                db.oauthAccountDao().upsert(
                    existing.copy(encryptedCredentials = oauthSecrets.encrypt(credentials))
                )
            }
        }
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun unzipToFiles(archive: File, stagingDirectory: File): Map<String, File> {
        val entries = linkedMapOf<String, File>()
        var totalSize = 0L
        val canonicalRoot = stagingDirectory.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                require(isSafeRelativePath(name)) { "备份包包含不安全路径" }
                if (!entry.isDirectory) {
                    require(entries.size < MAX_ARCHIVE_ENTRIES) { "备份包文件数量异常" }
                    require(name !in entries) { "备份包包含重复文件：$name" }
                    val target = File(canonicalRoot, name).canonicalFile
                    require(target.path.startsWith(canonicalRoot.path + File.separator)) {
                        "备份包包含不安全路径"
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            totalSize += read
                            require(totalSize <= MAX_ARCHIVE_SIZE) { "备份包解压后过大" }
                            output.write(buffer, 0, read)
                        }
                    }
                    entries[name] = target
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        BufferedOutputStream(FileOutputStream(target)).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "远程备份文件超过大小限制" }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun readBounded(file: File, maxBytes: Long): ByteArray {
        require(file.length() <= maxBytes) { "备份包中的元数据过大" }
        return BufferedInputStream(FileInputStream(file)).use { input ->
            val output = ByteArrayOutputStream(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun ensureFolder(raw: RawConfig): FolderResult {
        val folderUrl = resolveFolderUrl(raw.url)
        try {
            execute(
                Request.Builder()
                    .url(folderUrl)
                    .method("PROPFIND", ByteArray(0).toRequestBody(null))
                    .header("Depth", "0"),
                raw
            ).use { response ->
                if (response.code in listOf(200, 207)) {
                    return FolderResult(true, true, false, response.code, "文件夹已存在")
                }
                if (response.code == 401) {
                    return FolderResult(false, false, false, response.code, "认证失败 (HTTP 401)")
                }
                // 403/404 等继续尝试 MKCOL；部分服务拒绝 PROPFIND 但允许写入。
            }

            execute(
                Request.Builder()
                    .url(folderUrl)
                    .method("MKCOL", ByteArray(0).toRequestBody(null)),
                raw
            ).use { response ->
                return when (response.code) {
                    200, 201, 204 ->
                        FolderResult(true, true, true, response.code, "文件夹已创建")

                    405 ->
                        FolderResult(true, true, false, response.code, "文件夹已存在")

                    409 ->
                        FolderResult(
                            false,
                            false,
                            false,
                            response.code,
                            "父目录不存在，请检查 WebDAV 根地址"
                        )

                    401, 403 ->
                        FolderResult(
                            false,
                            false,
                            false,
                            response.code,
                            "无权限创建文件夹 (HTTP ${response.code})"
                        )

                    else ->
                        FolderResult(
                            false,
                            false,
                            false,
                            response.code,
                            "创建文件夹失败 (HTTP ${response.code})"
                        )
                }
            }
        } catch (e: Exception) {
            return FolderResult(false, false, false, null, readableError("连接失败", e))
        }
    }

    private fun ensureConditionalWriteSupport(raw: RawConfig) {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${normalizeBaseUrl(raw.url)}|${raw.username}".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        val preferenceKey = "$KEY_SYNC_CAS_PROBE_PREFIX$fingerprint"
        if (configPrefs.getBoolean(preferenceKey, false)) return

        val probeUrl = "${resolveIncrementalRootUrl(raw.url, prefs.activeDbName)}" +
            ".conditional-probe-${UUID.randomUUID()}.tmp"
        try {
            var firstEtag: String? = null
            val firstCode = execute(
                Request.Builder()
                    .url(probeUrl)
                    .put(ByteArray(0).toRequestBody(BINARY_MEDIA_TYPE))
                    .header("If-None-Match", "*"),
                raw
            ).use {
                firstEtag = it.header("ETag")
                it.code
            }
            require(firstCode in listOf(200, 201, 204)) {
                "WebDAV 服务器不支持 If-None-Match 条件写入 (HTTP $firstCode)"
            }
            val secondCode = execute(
                Request.Builder()
                    .url(probeUrl)
                    .put(ByteArray(0).toRequestBody(BINARY_MEDIA_TYPE))
                    .header("If-None-Match", "*"),
                raw
            ).use { it.code }
            require(secondCode == 412) {
                "WebDAV 服务器未执行 If-None-Match 条件写入，已停止增量同步以避免覆盖并发数据"
            }
            val currentEtag = firstEtag ?: execute(Request.Builder().url(probeUrl).get(), raw).use { response ->
                if (response.code != 200) error("读取 WebDAV 条件写入探测资源失败")
                response.header("ETag")
            }
            require(!currentEtag.isNullOrBlank()) {
                "WebDAV 服务器没有为对象提供 ETag，无法安全进行多设备增量同步"
            }
            val invalidMatchCode = execute(
                Request.Builder()
                    .url(probeUrl)
                    .put(ByteArray(0).toRequestBody(BINARY_MEDIA_TYPE))
                    .header("If-Match", "\"codex-invalid-etag\""),
                raw
            ).use { it.code }
            require(invalidMatchCode == 412) {
                "WebDAV 服务器未执行 If-Match 条件写入，已停止增量同步以避免覆盖并发数据"
            }
            val validMatchCode = execute(
                Request.Builder()
                    .url(probeUrl)
                    .put(ByteArray(0).toRequestBody(BINARY_MEDIA_TYPE))
                    .header("If-Match", currentEtag),
                raw
            ).use { it.code }
            require(validMatchCode in listOf(200, 204)) {
                "WebDAV 服务器拒绝有效的 If-Match 条件写入 (HTTP $validMatchCode)"
            }
            check(configPrefs.edit().putBoolean(preferenceKey, true).commit()) {
                "无法保存 WebDAV 条件写入探测结果"
            }
        } finally {
            runCatching { execute(Request.Builder().url(probeUrl).delete(), raw).use { } }
        }
    }

    private fun execute(builder: Request.Builder, raw: RawConfig): okhttp3.Response {
        builder.header("User-Agent", USER_AGENT)
        if (raw.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(raw.username, raw.password))
        }
        return client.newCall(builder.build()).execute()
    }

    private fun rawConfig(
        urlOverride: String? = null,
        usernameOverride: String? = null,
        passwordOverride: String? = null
    ): RawConfig = RawConfig(
        url = urlOverride?.takeIf { it.isNotBlank() }
            ?: configPrefs.getString(KEY_URL, "").orEmpty(),
        username = usernameOverride?.takeIf { it.isNotBlank() }
            ?: configPrefs.getString(KEY_USERNAME, "").orEmpty(),
        password = passwordOverride?.takeIf { it.isNotBlank() }
            ?: securePrefs.getString(
                SECURE_KEY_PASSWORD,
                configPrefs,
                KEY_PASSWORD
            ).orEmpty(),
        encryptionPassword =
            securePrefs.getString(
                SECURE_KEY_ENCRYPTION_PASSWORD,
                configPrefs,
                KEY_ENCRYPTION_PASSWORD
            ).orEmpty()
    )

    private fun updateStatus(
        lastBackupAt: String? = null,
        lastSyncAt: String? = null,
        lastError: String? = null,
        lastFileSize: Long? = null,
        lastModified: String? = null
    ) {
        configPrefs.edit().apply {
            lastBackupAt?.let { putString(KEY_LAST_BACKUP_AT, it) }
            lastSyncAt?.let { putString(KEY_LAST_SYNC_AT, it) }
            lastError?.let { putString(KEY_LAST_ERROR, it) }
            lastFileSize?.let { putLong(KEY_LAST_FILE_SIZE, it) }
            lastModified?.let { putString(KEY_LAST_MODIFIED, it) }
        }.apply()
    }

    private fun serializePreferences(sharedPreferences: SharedPreferences): JsonObject =
        JsonObject().apply {
            sharedPreferences.all.forEach { (key, value) ->
                val item = JsonObject()
                when (value) {
                    is String -> {
                        item.addProperty("type", "string")
                        item.addProperty("value", value)
                    }

                    is Boolean -> {
                        item.addProperty("type", "boolean")
                        item.addProperty("value", value)
                    }

                    is Int -> {
                        item.addProperty("type", "int")
                        item.addProperty("value", value)
                    }

                    is Long -> {
                        item.addProperty("type", "long")
                        item.addProperty("value", value)
                    }

                    is Float -> {
                        item.addProperty("type", "float")
                        item.addProperty("value", value)
                    }

                    is Set<*> -> {
                        item.addProperty("type", "string_set")
                        item.add("value", JsonArray().apply {
                            value.filterIsInstance<String>().forEach(::add)
                        })
                    }

                    else -> return@forEach
                }
                add(key, item)
            }
        }

    private fun restorePreferences(sharedPreferences: SharedPreferences, data: JsonObject) {
        val editor = sharedPreferences.edit().clear()
        data.entrySet().forEach { (key, element) ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val value = item.get("value") ?: return@forEach
            when (item.get("type")?.asString) {
                "string" -> editor.putString(key, value.asString)
                "boolean" -> editor.putBoolean(key, value.asBoolean)
                "int" -> editor.putInt(key, value.asInt)
                "long" -> editor.putLong(key, value.asLong)
                "float" -> editor.putFloat(key, value.asFloat)
                "string_set" -> editor.putStringSet(
                    key,
                    value.asJsonArray.map { it.asString }.toSet()
                )
            }
        }
        check(editor.commit()) { "恢复本地统计失败" }
    }

    private fun putZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putZipEntry(zip: ZipOutputStream, name: String, file: File) {
        require(file.isFile) { "备份文件不存在：${file.name}" }
        zip.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun successJson(): JsonObject = JsonObject().apply {
        addProperty("success", true)
    }

    private fun readableError(prefix: String, error: Throwable): String {
        val detail = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        return if (detail.isNullOrBlank() || detail == prefix) prefix else "$prefix：$detail"
    }

    private fun findDavValue(xml: String, name: String): String? {
        val pattern = Regex(
            """<(?:(?:[A-Za-z][\w.-]*):)?$name\b[^>]*>(.*?)</(?:(?:[A-Za-z][\w.-]*):)?$name>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(xml)?.groupValues?.getOrNull(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 规范化 WebDAV 根地址。
     *
     * 只接受 HTTPS：这里的请求会带 HTTP Basic 凭据（用户名 + 密码），并上传加密备份包，
     * 明文端点等于把凭据与备份直接暴露给链路上的观察者。release 的网络安全配置已禁止
     * 明文流量，这里再在输入校验层挡一次，避免依赖运行时策略。
     */
    private fun normalizeBaseUrl(url: String): String {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://")) {
            "WebDAV 根地址必须以 https:// 开头"
        }
        return normalized
    }

    private fun resolveFolderUrl(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/$BACKUP_FOLDER/"

    private fun resolveFileUrl(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/$BACKUP_FOLDER/$BACKUP_FILENAME"

    private fun mask(value: String): String = when {
        value.isBlank() -> ""
        value.length <= 4 -> "*".repeat(value.length)
        else -> value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2)
    }

    private fun nowIso(): String = OffsetDateTime.now().toString()

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        val parts = path.replace('\\', '/').split('/')
        return parts.none { it.isBlank() || it == "." || it == ".." }
    }

    private data class RawConfig(
        val url: String,
        val username: String,
        val password: String,
        val encryptionPassword: String
    )

    private data class FolderResult(
        val ok: Boolean,
        val exists: Boolean,
        val created: Boolean,
        val statusCode: Int?,
        val message: String
    )

    private data class DavChildEntry(
        val name: String,
        val lastModifiedAt: Long?
    )

    private class ConcurrentManifestUpdateException : IOException(
        "远端数据已被其他设备更新，正在重新读取并合并"
    )

    private companion object {
        const val CONFIG_PREF_NAME = "nekobot_local_webdav"
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_ENCRYPTION_PASSWORD = "encryption_password"
        const val SECURE_KEY_PASSWORD = "webdav_password"
        const val SECURE_KEY_ENCRYPTION_PASSWORD = "webdav_encryption_password"
        const val KEY_LAST_BACKUP_AT = "last_backup_at"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_FILE_SIZE = "last_file_size"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val KEY_SYNC_DEVICE_ID = "sync_device_id"
        const val KEY_AUTO_INCREMENTAL_SYNC_ENABLED = "auto_incremental_sync_enabled"
        const val KEY_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS = "auto_incremental_sync_interval_hours"
        const val KEY_INCREMENTAL_SYNC_MAX_VERSIONS = "incremental_sync_max_versions"
        const val KEY_SYNC_BASE_PREFIX = "sync_base_"
        const val KEY_SYNC_JOURNAL_PREFIX = "sync_journal_"
        const val KEY_SYNC_V2_BASE_PREFIX = "sync_v2_base_"
        const val KEY_SYNC_V2_JOURNAL_PREFIX = "sync_v2_journal_"
        const val KEY_SYNC_CAS_PROBE_PREFIX = "sync_cas_probe_"
        const val KEY_LAST_CONFLICTS = "last_incremental_conflicts"
        const val MAX_SYNC_CAS_RETRIES = 2

        const val DEFAULT_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS = 6
        const val MIN_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS = 1
        const val MAX_AUTO_INCREMENTAL_SYNC_INTERVAL_HOURS = 24 * 7
        const val DEFAULT_INCREMENTAL_SYNC_MAX_VERSIONS = 10
        const val MIN_INCREMENTAL_SYNC_MAX_VERSIONS = 1
        const val MAX_INCREMENTAL_SYNC_MAX_VERSIONS = 50
        const val INCREMENTAL_OBJECT_GC_GRACE_MS = 7L * 24L * 60L * 60L * 1000L

        const val BACKUP_FOLDER = "nekobot"
        const val BACKUP_FILENAME = "config.nbotcfg"
        const val USER_AGENT = "NekoBot-Android-WebDAV/1.0"

        const val ENTRY_DATABASE = "database.sqlite"
        const val ENTRY_CREDENTIALS = "credentials.json"
        const val ENTRY_METADATA = "metadata.json"
        const val ENTRY_APP_SETTINGS = "app-settings.json"
        const val ENTRY_GLOBAL_MEMORY = "global-memory.md"
        const val ENTRY_TOKEN_USAGE = "token-usage.json"
        const val ENTRY_ACHIEVEMENTS = "achievements.json"
        const val ENTRY_PORTRAITS_PREFIX = "portraits/"
        const val ENTRY_LOCAL_PORTRAITS_PREFIX = "local-portraits/"
        const val ENTRY_WORLD_BOOK_COVERS_PREFIX = "worldbook-covers/"
        const val ENTRY_MESSAGE_AUDIO_PREFIX = "message-audio/"
        const val ENTRY_STICKERS_PREFIX = "stickers/"
        const val ENTRY_CATALOG_FILES_PREFIX = "catalog-files/"
        const val ENTRY_CATALOG_PLUGIN_PREFIX = "catalog-files/extensions/plugin_packages/"
        const val PLUGIN_STATE_ENTRY = ".plugin-state.json"
        private const val MAX_CATALOG_FILE_BYTES = 256L * 1024L * 1024L
        private val CATALOG_BACKUP_ROOTS = listOf(
            PortableDataCategory.WORKSPACE.id to "workspace",
            PortableDataCategory.EXTENSIONS.id to "skills",
            PortableDataCategory.EXTENSIONS.id to "plugin_packages",
            PortableDataCategory.EXTENSIONS.id to "plugin_files",
            PortableDataCategory.MEDIA.id to "chat_backgrounds",
            PortableDataCategory.MEDIA.id to "fonts"
        )
        const val ACHIEVEMENT_PREF_NAME = "nekobot_achievements"
        const val TYPE_SESSION = "session"
        const val TYPE_MESSAGE = "message"
        const val TYPE_MESSAGE_IMAGE = "message_image"
        const val TYPE_CHARACTER = "character"
        const val TYPE_WORLD_BOOK = "world_book"
        const val TYPE_WORLD_BOOK_ENTRY = "world_book_entry"
        const val TYPE_STICKER = "sticker"
        const val TYPE_FILE = "file"
        const val TYPE_APP_SETTINGS = "app_settings"
        const val TYPE_CREDENTIALS = "credentials_bundle"
        const val TYPE_TOKEN_USAGE = "token_usage_record"
        const val TYPE_ACHIEVEMENTS = "achievements"
        const val TYPE_PLOT_STORY = "plot_story"
        const val TYPE_CONFLICT_COPY = "conflict_copy"
        private val LEGACY_INCREMENTAL_COVERAGE = setOf(
            TYPE_SESSION,
            TYPE_MESSAGE,
            TYPE_MESSAGE_IMAGE,
            TYPE_CHARACTER,
            TYPE_WORLD_BOOK,
            TYPE_WORLD_BOOK_ENTRY,
            TYPE_STICKER
        )
        private val GENERIC_INCREMENTAL_CATEGORIES = PortableDataCategory.entries.filter {
            it.tables.isNotEmpty()
        }
        private val SPECIAL_INCREMENTAL_TABLES = setOf(
            "local_sessions",
            "local_messages",
            "local_message_images",
            "local_characters",
            "local_world_books",
            "local_world_book_entries",
            "local_stickers"
        )
        private val GENERIC_INCREMENTAL_TYPE_IDS = GENERIC_INCREMENTAL_CATEGORIES.mapTo(linkedSetOf()) { it.id }
        private val SUPPORTED_INCREMENTAL_TYPES = LEGACY_INCREMENTAL_COVERAGE +
            GENERIC_INCREMENTAL_TYPE_IDS + setOf(
                TYPE_FILE,
                TYPE_APP_SETTINGS,
                TYPE_CREDENTIALS,
                TYPE_TOKEN_USAGE,
                TYPE_ACHIEVEMENTS,
                TYPE_PLOT_STORY
            )
        private val APPEND_ONLY_INCREMENTAL_TYPES = setOf(TYPE_TOKEN_USAGE)
        const val MAX_ARCHIVE_ENTRIES = 5_000
        const val MAX_ARCHIVE_SIZE = 1024L * 1024L * 1024L
        const val MAX_ENCRYPTED_ARCHIVE_SIZE = 1400L * 1024L * 1024L
        const val MAX_SMALL_ENTRY_BYTES = 16L * 1024L * 1024L
        const val MAX_MESSAGE_IMAGE_BYTES = 64L * 1024L * 1024L
        const val MAX_MESSAGE_AUDIO_BYTES = 64L * 1024L * 1024L
        const val MAX_PORTRAIT_BYTES = 64L * 1024L * 1024L
        const val MAX_STICKER_BYTES = 16L * 1024L * 1024L
        private const val MAX_INCREMENTAL_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_INCREMENTAL_PLOT_STORY_BYTES = 16L * 1024L * 1024L
        private const val SYNC_CHUNK_SIZE = 8L * 1024L * 1024L
        private const val MAX_ENCRYPTED_SYNC_CHUNK_BYTES = 12L * 1024L * 1024L
        private const val MAX_SYNC_CHUNKS = 8
        private const val MAX_INCREMENTAL_DELTA_BYTES = 256L * 1024L * 1024L
        private const val MAX_INCREMENTAL_ENCRYPTED_DELTA_BYTES = 384L * 1024L * 1024L

        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}

private data class FullRestoreJournalEntry(
    val targetPath: String,
    val snapshotName: String,
    val existed: Boolean
)

private data class FullRestoreJournal(
    val state: String,
    val entries: List<FullRestoreJournalEntry>,
    val previousAppSettingsBase64: String
)

/** 应用启动时在数据库打开前回滚被进程中断的全量恢复事务。 */
fun recoverPendingLocalWebDavRestore(context: Context) {
    FullWebDavRestoreRecovery.recover(context.applicationContext)
}

private object FullWebDavRestoreRecovery {
    private val gson = Gson()

    fun writeJournal(directory: File, journal: FullRestoreJournal) {
        val target = File(directory, "journal.json")
        val temp = File(directory, "journal.json.tmp")
        FileOutputStream(temp).use { output ->
            output.write(gson.toJson(journal).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun rollback(context: Context, directory: File, journal: FullRestoreJournal) {
        val transactionRoot = directory.canonicalFile
        val snapshotsRoot = File(transactionRoot, "snapshots").canonicalFile
        require(snapshotsRoot.path.startsWith(transactionRoot.path + File.separator)) {
            "恢复回滚目录无效"
        }
        val allowedRoots = listOf(context.dataDir.canonicalPath, context.cacheDir.canonicalPath)
        journal.entries.asReversed().forEach { entry ->
            val target = File(entry.targetPath).canonicalFile
            require(allowedRoots.any { target.path.startsWith(it + File.separator) }) {
                "恢复日志包含越界目标路径"
            }
            val snapshot = File(snapshotsRoot, entry.snapshotName).canonicalFile
            require(snapshot.path.startsWith(snapshotsRoot.path + File.separator)) {
                "恢复日志包含越界回滚路径"
            }
            if (target.exists() && !target.deleteRecursively()) {
                error("无法移除中断恢复写入：${target.name}")
            }
            if (entry.existed) {
                require(snapshot.exists()) { "恢复回滚副本缺失：${entry.snapshotName}" }
                copyPathWithoutLinks(snapshot, target)
            }
        }
    }

    fun recover(context: Context) {
        val root = File(context.filesDir, FULL_RESTORE_JOURNAL_DIR)
        if (!root.isDirectory) return
        root.listFiles()?.sortedBy { it.lastModified() }?.forEach { transaction ->
            if (!transaction.isDirectory) {
                transaction.delete()
                return@forEach
            }
            val journalFile = File(transaction, "journal.json")
            if (!journalFile.isFile) {
                transaction.deleteRecursively()
                return@forEach
            }
            val journal = gson.fromJson(journalFile.readText(Charsets.UTF_8), FullRestoreJournal::class.java)
                ?: error("全量恢复日志为空")
            when (journal.state) {
                "committed" -> Unit
                "prepared" -> rollback(context, transaction, journal)
                else -> error("全量恢复日志状态无效：${journal.state}")
            }
            if (!transaction.deleteRecursively()) error("无法清理全量恢复事务目录")
        }
    }

    private fun copyPathWithoutLinks(source: File, target: File) {
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) return
        if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory) { "无法创建恢复回滚目录" }
            val children = source.listFiles() ?: error("无法读取恢复回滚目录：${source.name}")
            children.forEach { copyPathWithoutLinks(it, File(target, it.name)) }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }
}

private class SizeLimitedOutputStream(
    output: OutputStream,
    private val limit: Long
) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        out.write(buffer, offset, length)
        written += length
    }

    private fun ensureCapacity(length: Int) {
        if (length < 0 || written + length > limit) {
            throw IOException("备份包超过 1 GB 限制")
        }
    }
}

/** 与原仓库 config.nbotcfg 相同的 PBKDF2 + Fernet 外层格式。 */
internal object LocalWebDavArchiveCodec {
    private const val KDF_ITERATIONS = 390_000
    private const val FERNET_VERSION: Byte = 0x80.toByte()
    private const val BASE64_CHUNK_CHARS = 8192
    private const val MAX_HEADER_VALUE_CHARS = 16 * 1024
    private val gson = Gson()
    private val random = SecureRandom()

    fun encrypt(
        archive: ByteArray,
        password: String,
        profileName: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        require(password.isNotBlank()) { "加密密码不能为空" }
        val salt = ByteArray(16).also(random::nextBytes)
        val token = encryptFernet(archive, password.trim(), salt, timestampSeconds)
        val outer = JsonObject().apply {
            addProperty("version", 1)
            addProperty("type", "nbot_config_bundle")
            addProperty("encrypted", true)
            addProperty("algorithm", "fernet")
            addProperty("kdf", "pbkdf2_hmac_sha256")
            addProperty("iterations", KDF_ITERATIONS)
            addProperty("source_format", "nekobot_android_room_v1")
            addProperty("profile_name", profileName)
            addProperty("salt", Base64.getUrlEncoder().encodeToString(salt))
            addProperty("exported_at", OffsetDateTime.now().toString())
            addProperty("payload", Base64.getUrlEncoder().encodeToString(token))
        }
        return gson.toJson(outer).toByteArray(Charsets.UTF_8)
    }

    fun decrypt(payload: ByteArray, password: String): ByteArray {
        require(password.isNotBlank()) { "解密密码不能为空" }
        val outer = runCatching {
            JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        }.getOrElse { throw IllegalArgumentException("远程备份不是有效 JSON", it) }
        require(outer.get("type")?.asString == "nbot_config_bundle") {
            "不是有效的 NekoBot 配置包"
        }
        require(outer.get("source_format")?.asString == "nekobot_android_room_v1") {
            "远程文件不是 Android 本地模式备份"
        }
        require(outer.get("encrypted")?.asBoolean == true) { "远程备份未加密" }
        val iterations = outer.get("iterations")?.asInt ?: KDF_ITERATIONS
        require(iterations == KDF_ITERATIONS) { "不支持的密钥派生参数" }
        val salt = runCatching {
            Base64.getUrlDecoder().decode(outer.get("salt")?.asString.orEmpty())
        }.getOrElse { throw IllegalArgumentException("备份盐值无效", it) }
        val token = runCatching {
            Base64.getUrlDecoder().decode(outer.get("payload")?.asString.orEmpty())
        }.getOrElse { throw IllegalArgumentException("备份负载无效", it) }
        return decryptFernet(token, password.trim(), salt)
    }

    /** 对大型归档按块加密，输出仍使用旧版 nbotcfg JSON + Fernet token 格式。 */
    fun encryptFile(archive: File, password: String, profileName: String, output: File) {
        require(password.isNotBlank()) { "加密密码不能为空" }
        val salt = ByteArray(16).also(random::nextBytes)
        val key = deriveKey(password.trim(), salt)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)
        val iv = ByteArray(16).also(random::nextBytes)
        val timestampSeconds = System.currentTimeMillis() / 1000L
        val tokenFile = File.createTempFile("webdav-fernet-", ".token", output.parentFile)
        try {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(signingKey, "HmacSHA256"))
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            }
            val header = byteArrayOf(FERNET_VERSION) +
                ByteBuffer.allocate(8).putLong(timestampSeconds).array() + iv
            FileOutputStream(tokenFile).use { rawOutput ->
                val signedOutput = MacUpdatingOutputStream(rawOutput, mac)
                signedOutput.write(header)
                CipherOutputStream(signedOutput, cipher).use { cipherOutput ->
                    BufferedInputStream(FileInputStream(archive)).use { input ->
                        input.copyTo(cipherOutput)
                    }
                }
            }
            FileOutputStream(tokenFile, true).use { it.write(mac.doFinal()) }

            val metadata = JsonObject().apply {
                addProperty("version", 1)
                addProperty("type", "nbot_config_bundle")
                addProperty("encrypted", true)
                addProperty("algorithm", "fernet")
                addProperty("kdf", "pbkdf2_hmac_sha256")
                addProperty("iterations", KDF_ITERATIONS)
                addProperty("source_format", "nekobot_android_room_v1")
                addProperty("profile_name", profileName)
                addProperty("salt", Base64.getUrlEncoder().encodeToString(salt))
                addProperty("exported_at", OffsetDateTime.now().toString())
            }
            val prefix = gson.toJson(metadata).removeSuffix("}") + ",\"payload\":\""
            FileOutputStream(output).buffered().use { jsonOutput ->
                jsonOutput.write(prefix.toByteArray(Charsets.UTF_8))
                Base64.getUrlEncoder().wrap(NonClosingOutputStream(jsonOutput)).use { base64Output ->
                    BufferedInputStream(FileInputStream(tokenFile)).use { input ->
                        input.copyTo(base64Output)
                    }
                }
                jsonOutput.write("\"}".toByteArray(Charsets.UTF_8))
            }
        } finally {
            tokenFile.delete()
        }
    }

    /**
     * 逐块解析外层 Base64、校验 Fernet HMAC，再将解密 ZIP 写入文件。
     * 这样同步大备份时堆内存只保留固定大小缓冲区。
     */
    fun decryptFile(
        payload: File,
        password: String,
        output: File,
        maxOutputBytes: Long = 1024L * 1024L * 1024L
    ) {
        require(password.isNotBlank()) { "解密密码不能为空" }
        val tokenFile = File.createTempFile("webdav-fernet-", ".token", output.parentFile)
        try {
            val metadata = decodeEnvelope(payload, tokenFile)
            require(metadata.get("type")?.asString == "nbot_config_bundle") {
                "不是有效的 NekoBot 配置包"
            }
            require(metadata.get("source_format")?.asString == "nekobot_android_room_v1") {
                "远程文件不是 Android 本地模式备份"
            }
            require(metadata.get("encrypted")?.asBoolean == true) { "远程备份未加密" }
            val iterations = metadata.get("iterations")?.asInt ?: KDF_ITERATIONS
            require(iterations == KDF_ITERATIONS) { "不支持的密钥派生参数" }
            val salt = runCatching {
                Base64.getUrlDecoder().decode(metadata.get("salt")?.asString.orEmpty())
            }.getOrElse { throw IllegalArgumentException("备份盐值无效", it) }
            require(salt.size == 16) { "备份盐值无效" }
            decryptFernetFile(tokenFile, password.trim(), salt, output, maxOutputBytes)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            tokenFile.delete()
        }
    }

    private fun decodeEnvelope(payload: File, tokenFile: File): JsonObject {
        val metadata = JsonObject()
        var foundPayload = false
        PushbackReader(InputStreamReader(FileInputStream(payload), StandardCharsets.UTF_8), 2).use { reader ->
            require(nextNonWhitespace(reader) == '{'.code) { "远程备份不是有效 JSON" }
            while (true) {
                val key = readJsonString(reader, MAX_HEADER_VALUE_CHARS)
                require(if (key == "payload") !foundPayload else !metadata.has(key)) {
                    "远程备份包含重复字段"
                }
                require(nextNonWhitespace(reader) == ':'.code) { "远程备份 JSON 格式无效" }
                if (key == "payload") {
                    decodeBase64String(reader, tokenFile)
                    foundPayload = true
                } else {
                    metadata.add(key, readJsonScalar(reader))
                }
                when (nextNonWhitespace(reader)) {
                    ','.code -> continue
                    '}'.code -> break
                    else -> error("远程备份 JSON 格式无效")
                }
            }
            require(nextNonWhitespace(reader) == -1) { "远程备份 JSON 尾部包含无效数据" }
        }
        require(foundPayload && tokenFile.length() >= 73L) { "备份负载格式无效" }
        return metadata
    }

    private fun decodeBase64String(reader: PushbackReader, tokenFile: File) {
        require(nextNonWhitespace(reader) == '"'.code) { "备份负载格式无效" }
        FileOutputStream(tokenFile).buffered().use { output ->
            val chunk = StringBuilder(BASE64_CHUNK_CHARS)
            while (true) {
                val value = reader.read()
                require(value >= 0) { "备份负载不完整" }
                if (value == '"'.code) break
                val char = value.toChar()
                require(char.isLetterOrDigit() || char == '-' || char == '_' || char == '=') {
                    "备份负载包含无效字符"
                }
                chunk.append(char)
                if (chunk.length == BASE64_CHUNK_CHARS) {
                    output.write(Base64.getUrlDecoder().decode(chunk.toString()))
                    chunk.setLength(0)
                }
            }
            if (chunk.isNotEmpty()) {
                output.write(runCatching { Base64.getUrlDecoder().decode(chunk.toString()) }
                    .getOrElse { throw IllegalArgumentException("备份负载无效", it) })
            }
        }
    }

    private fun readJsonString(reader: PushbackReader, maxChars: Int): String {
        require(nextNonWhitespace(reader) == '"'.code) { "远程备份 JSON 字符串无效" }
        val result = StringBuilder()
        while (true) {
            val value = reader.read()
            require(value >= 0) { "远程备份 JSON 字符串未结束" }
            when (val char = value.toChar()) {
                '"' -> return result.toString()
                '\\' -> {
                    val escaped = reader.read()
                    require(escaped >= 0) { "远程备份 JSON 转义无效" }
                    result.append(
                        when (val escape = escaped.toChar()) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = CharArray(4)
                                repeat(4) { index ->
                                    val digit = reader.read()
                                    require(digit >= 0) { "远程备份 JSON 转义无效" }
                                    hex[index] = digit.toChar()
                                }
                                hex.concatToString().toIntOrNull(16)?.toChar()
                                    ?: error("远程备份 JSON 转义无效")
                            }
                            else -> error("远程备份 JSON 转义无效")
                        }
                    )
                }
                else -> {
                    require(char.code >= 0x20) { "远程备份 JSON 包含控制字符" }
                    result.append(char)
                }
            }
            require(result.length <= maxChars) { "远程备份元数据过大" }
        }
    }

    private fun readJsonScalar(reader: PushbackReader): com.google.gson.JsonElement {
        val first = nextNonWhitespace(reader)
        if (first == '"'.code) {
            reader.unread(first)
            return com.google.gson.JsonPrimitive(readJsonString(reader, MAX_HEADER_VALUE_CHARS))
        }
        require(first >= 0) { "远程备份 JSON 值缺失" }
        val value = StringBuilder().append(first.toChar())
        while (true) {
            val char = reader.read()
            if (char < 0 || char == ','.code || char == '}'.code) {
                if (char >= 0) reader.unread(char)
                break
            }
            value.append(char.toChar())
            require(value.length <= MAX_HEADER_VALUE_CHARS) { "远程备份元数据过大" }
        }
        return runCatching { JsonParser.parseString(value.toString().trim()) }
            .getOrElse { throw IllegalArgumentException("远程备份 JSON 值无效", it) }
    }

    private fun nextNonWhitespace(reader: PushbackReader): Int {
        while (true) {
            val value = reader.read()
            if (value < 0 || !value.toChar().isWhitespace()) return value
        }
    }

    private fun decryptFernetFile(
        tokenFile: File,
        password: String,
        salt: ByteArray,
        output: File,
        maxOutputBytes: Long
    ) {
        val tokenLength = tokenFile.length()
        require(tokenLength >= 73L && (tokenLength - 57L) % 16L == 0L) {
            "备份负载格式无效"
        }
        val signedLength = tokenLength - 32L
        val key = deriveKey(password, salt)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
        }
        FileInputStream(tokenFile).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = signedLength
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "备份负载不完整" }
                mac.update(buffer, 0, read)
                remaining -= read
            }
        }
        val suppliedMac = ByteArray(32)
        RandomAccessFile(tokenFile, "r").use { input ->
            require(input.readUnsignedByte() == (FERNET_VERSION.toInt() and 0xff)) {
                "备份负载格式无效"
            }
            input.seek(signedLength)
            input.readFully(suppliedMac)
        }
        require(MessageDigest.isEqual(suppliedMac, mac.doFinal())) {
            "密码错误或备份文件已损坏"
        }

        val iv = ByteArray(16)
        RandomAccessFile(tokenFile, "r").use { input ->
            input.seek(9L)
            input.readFully(iv)
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        }
        val ciphertextLength = tokenLength - 57L
        try {
            FileInputStream(tokenFile).use { input ->
                var skipped = 0L
                while (skipped < 25L) {
                    val count = input.skip(25L - skipped)
                    if (count <= 0L) {
                        require(input.read() >= 0) { "备份负载不完整" }
                        skipped++
                    } else skipped += count
                }
                SizeLimitedOutputStream(
                    FileOutputStream(output).buffered(),
                    maxOutputBytes
                ).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = ciphertextLength
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(read > 0) { "备份负载不完整" }
                        cipher.update(buffer, 0, read)?.let { out.write(it) }
                        remaining -= read
                    }
                    cipher.doFinal()?.let(out::write)
                }
            }
        } catch (error: Exception) {
            output.delete()
            throw IllegalArgumentException("密码错误或备份文件已损坏", error)
        }
    }

    private class MacUpdatingOutputStream(
        output: OutputStream,
        private val mac: Mac
    ) : java.io.FilterOutputStream(output) {
        override fun write(value: Int) {
            mac.update(value.toByte())
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            mac.update(buffer, offset, length)
            out.write(buffer, offset, length)
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : java.io.FilterOutputStream(output) {
        override fun close() = flush()
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    private fun encryptFernet(
        plaintext: ByteArray,
        password: String,
        salt: ByteArray,
        timestampSeconds: Long
    ): ByteArray {
        val key = deriveKey(password, salt)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            IvParameterSpec(iv)
        )
        val ciphertext = cipher.doFinal(plaintext)
        val signed = ByteArrayOutputStream().apply {
            write(byteArrayOf(FERNET_VERSION))
            write(ByteBuffer.allocate(8).putLong(timestampSeconds).array())
            write(iv)
            write(ciphertext)
        }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
        }.doFinal(signed)
        return signed + mac
    }

    private fun decryptFernet(
        token: ByteArray,
        password: String,
        salt: ByteArray
    ): ByteArray {
        require(token.size >= 73 && token[0] == FERNET_VERSION) {
            "备份负载格式无效"
        }
        val key = deriveKey(password, salt)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)
        val signed = token.copyOfRange(0, token.size - 32)
        val suppliedMac = token.copyOfRange(token.size - 32, token.size)
        val expectedMac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
        }.doFinal(signed)
        require(MessageDigest.isEqual(suppliedMac, expectedMac)) {
            "密码错误或备份文件已损坏"
        }
        val iv = token.copyOfRange(9, 25)
        val ciphertext = token.copyOfRange(25, token.size - 32)
        return runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(encryptionKey, "AES"),
                    IvParameterSpec(iv)
                )
                doFinal(ciphertext)
            }
        }.getOrElse { throw IllegalArgumentException("密码错误或备份文件已损坏", it) }
    }
}
