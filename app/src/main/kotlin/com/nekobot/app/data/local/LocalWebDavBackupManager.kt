package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
            hasEncryptionPassword = encryptionPassword.isNotBlank()
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
        editor.apply()
        LocalWebDavSyncScheduler.configure(
            appContext,
            config.enabled ?: configPrefs.getBoolean(KEY_ENABLED, false)
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
                val payload = LocalWebDavArchiveCodec.encrypt(
                    archive = archive,
                    password = encryptionPassword,
                    profileName = prefs.activeDbName
                )
                val fileUrl = resolveFileUrl(raw.url)
                val body = payload.toRequestBody(BINARY_MEDIA_TYPE)
                execute(
                    Request.Builder().url(fileUrl).put(body),
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
                        lastFileSize = payload.size.toLong(),
                        lastModified = modified
                    )
                    successJson().apply {
                        addProperty("ok", true)
                        addProperty("size", payload.size)
                        addProperty("uploaded_at", now)
                        addProperty("status_code", response.code)
                        addProperty("last_modified", modified)
                        addProperty("file_url", fileUrl)
                    }
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
                val payload = execute(
                    Request.Builder().url(fileUrl).get(),
                    raw
                ).use { response ->
                    when (response.code) {
                        200 -> response.body?.bytes() ?: byteArrayOf()
                        404 -> error("远程备份文件不存在，请先执行备份")
                        else -> error("WebDAV 服务器返回异常 (HTTP ${response.code})")
                    }
                }
                require(payload.isNotEmpty()) { "远程备份文件内容为空" }
                val archive = LocalWebDavArchiveCodec.decrypt(payload, encryptionPassword)
                restoreArchive(
                    archive = archive,
                    includePortraits = request.includePortraits == true
                )

                val now = nowIso()
                updateStatus(
                    lastSyncAt = now,
                    lastError = "",
                    lastFileSize = payload.size.toLong()
                )
                successJson().apply {
                    addProperty("ok", true)
                    addProperty("size", payload.size)
                    addProperty("synced_at", now)
                    addProperty("file_url", fileUrl)
                }
            } catch (e: Exception) {
                val message = readableError("恢复失败", e)
                updateStatus(lastError = message)
                throw IllegalStateException(message, e)
            }
        }

    /**
     * 双向增量同步。远端只追加本次变化的加密 delta，manifest 保存每条记录的最新版本指针。
     * 本地和远端同时修改时按 updatedAt 选择较新的版本，并统计冲突数量。
     */
    suspend fun incrementalSync(request: WebDavBackupRequest): JsonObject =
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

            try {
                val profileName = prefs.activeDbName
                val rootUrl = resolveIncrementalRootUrl(raw.url, profileName)
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

                val baselineKey = "$KEY_SYNC_BASE_PREFIX$profileName"
                val baseline = configPrefs.getString(baselineKey, null)
                    ?.let { json ->
                        runCatching {
                            gson.fromJson(json, WebDavSyncManifest::class.java)
                        }.getOrNull()
                    }
                    ?: WebDavSyncManifest()
                val db = NekobotDatabase.get(appContext, profileName)
                val localRecords = collectIncrementalRecords(db)
                val localIndex = localRecords.mapValues { (_, record) ->
                    LocalWebDavIncrementalLogic.indexOf(record, "")
                }
                val now = nowIso()
                val outgoing = linkedMapOf<String, WebDavSyncRecord>()
                val incoming = linkedMapOf<String, WebDavSyncRecord>()
                var conflicts = 0
                val deltaCache = mutableMapOf<String, WebDavSyncDelta>()

                fun localRecordFor(key: String): WebDavSyncRecord? =
                    localRecords[key] ?: baseline.records[key]
                        ?.takeUnless { it.deleted }
                        ?.let { LocalWebDavIncrementalLogic.tombstone(key, now) }

                fun loadRemoteRecord(
                    key: String,
                    index: WebDavSyncIndexEntry
                ): WebDavSyncRecord {
                    if (index.deleted) {
                        return LocalWebDavIncrementalLogic.tombstone(key, index.updatedAt)
                    }
                    require(index.delta.isNotBlank()) { "远端同步索引缺少版本文件：$key" }
                    val delta = deltaCache.getOrPut(index.delta) {
                        val encrypted = execute(
                            Request.Builder().url("$rootUrl${index.delta}").get(),
                            raw
                        ).use { response ->
                            if (response.code != 200) {
                                error("读取增量版本失败 (HTTP ${response.code})")
                            }
                            response.body?.bytes() ?: byteArrayOf()
                        }
                        val plain = LocalWebDavArchiveCodec.decrypt(encrypted, encryptionPassword)
                        gson.fromJson(
                            plain.toString(Charsets.UTF_8),
                            WebDavSyncDelta::class.java
                        )
                    }
                    return delta.records.firstOrNull { it.key == key }
                        ?: error("增量版本中缺少记录：$key")
                }

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
                    val localChanged = LocalWebDavIncrementalLogic.changed(localCandidate, baseIndex)
                    val remoteChanged = LocalWebDavIncrementalLogic.changed(remoteIndex, baseIndex)

                    when {
                        localChanged && !remoteChanged && localRecord != null -> {
                            outgoing[key] = localRecord
                        }
                        !localChanged && remoteChanged && remoteIndex != null -> {
                            incoming[key] = loadRemoteRecord(key, remoteIndex)
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
                            if (localWins) {
                                outgoing[key] = localRecord
                            } else {
                                incoming[key] = loadRemoteRecord(key, remoteIndex)
                            }
                        }
                    }
                }

                if (incoming.isNotEmpty()) {
                    applyIncrementalRecords(db, incoming.values.toList())
                }

                var uploadedBytes = 0L
                if (outgoing.isNotEmpty()) {
                    val nextRevision = remoteManifest.revision + 1L
                    val deltaName = "delta-${nextRevision}-${UUID.randomUUID()}.nksync"
                    val delta = WebDavSyncDelta(
                        revision = nextRevision,
                        deviceId = getOrCreateSyncDeviceId(),
                        createdAt = now,
                        records = outgoing.values.toList()
                    )
                    val encryptedDelta = LocalWebDavArchiveCodec.encrypt(
                        gson.toJson(delta).toByteArray(Charsets.UTF_8),
                        encryptionPassword,
                        profileName
                    )
                    putIncrementalFile("$rootUrl$deltaName", encryptedDelta, raw)
                    uploadedBytes += encryptedDelta.size

                    if (remoteManifestPayload != null && remoteManifest.revision > 0L) {
                        val historyUrl =
                            "${rootUrl}history/manifest-${remoteManifest.revision}.nksync"
                        putIncrementalFile(historyUrl, remoteManifestPayload!!, raw)
                    }
                    outgoing.values.forEach { record ->
                        remoteManifest.records[record.key] =
                            LocalWebDavIncrementalLogic.indexOf(record, deltaName)
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
                            error("远端数据已被其他设备更新，请重新同步")
                        }
                        if (response.code !in listOf(200, 201, 204)) {
                            error("更新增量同步清单失败 (HTTP ${response.code})")
                        }
                    }
                    uploadedBytes += encryptedManifest.size
                }

                configPrefs.edit()
                    .putString(baselineKey, gson.toJson(remoteManifest))
                    .apply()
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
                    addProperty("uploaded_bytes", uploadedBytes)
                    addProperty("incremental", true)
                }
            } catch (e: Exception) {
                val message = readableError("增量同步失败", e)
                updateStatus(lastError = message)
                throw IllegalStateException(message, e)
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
            val record = LocalWebDavIncrementalLogic.record(
                TYPE_MESSAGE,
                entity.id,
                entity.createdAt,
                gson.toJsonTree(entity).asJsonObject
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
        return records
    }

    private suspend fun applyIncrementalRecords(
        db: NekobotDatabase,
        records: List<WebDavSyncRecord>
    ) {
        db.withTransaction {
            records.filter { it.deleted }
                .sortedBy { record ->
                    when (record.type) {
                        TYPE_WORLD_BOOK_ENTRY -> 0
                        TYPE_MESSAGE -> 1
                        TYPE_SESSION -> 2
                        TYPE_WORLD_BOOK -> 3
                        TYPE_CHARACTER -> 4
                        else -> 5
                    }
                }
                .forEach { record ->
                    when (record.type) {
                        TYPE_SESSION -> db.sessionDao().deleteById(record.id)
                        TYPE_MESSAGE -> db.messageDao().deleteById(record.id)
                        TYPE_CHARACTER -> db.characterDao().deleteById(record.id)
                        TYPE_WORLD_BOOK -> db.worldBookDao().deleteById(record.id)
                        TYPE_WORLD_BOOK_ENTRY -> db.worldBookDao().deleteEntryById(record.id)
                    }
                }

            records.filterNot { it.deleted }
                .sortedBy { record ->
                    when (record.type) {
                        TYPE_CHARACTER -> 0
                        TYPE_WORLD_BOOK -> 1
                        TYPE_SESSION -> 2
                        TYPE_WORLD_BOOK_ENTRY -> 3
                        TYPE_MESSAGE -> 4
                        else -> 5
                    }
                }
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
                            gson.fromJson(value, LocalMessageEntity::class.java)
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
                    }
                }
        }
    }

    private fun ensureIncrementalFolders(raw: RawConfig) {
        val profile = encodePathSegment(prefs.activeDbName)
        val base = "${normalizeBaseUrl(raw.url)}/$BACKUP_FOLDER/"
        listOf(
            "${base}sync/",
            "${base}sync/$profile/",
            "${base}sync/$profile/history/"
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

    private fun resolveIncrementalRootUrl(baseUrl: String, profileName: String): String =
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

    private suspend fun buildArchive(includePortraits: Boolean): ByteArray {
        val profileName = prefs.activeDbName
        val dbName = "$profileName.db"
        val db = NekobotDatabase.get(appContext, profileName)
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

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            putZipEntry(zip, ENTRY_DATABASE, dbFile.readBytes())
            putZipEntry(
                zip,
                ENTRY_CREDENTIALS,
                gson.toJson(buildCredentialBundle(db)).toByteArray()
            )

            val metadata = JsonObject().apply {
                addProperty("version", 2)
                addProperty("profile_name", profileName)
                addProperty("database_name", dbName)
                addProperty("created_at", nowIso())
                addProperty("includes_portraits", includePortraits)
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

            if (includePortraits) {
                val portraitDir = File(appContext.cacheDir, "portraits/$profileName")
                portraitDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relative = file.relativeTo(portraitDir)
                            .invariantSeparatorsPath
                            .takeIf { isSafeRelativePath(it) }
                            ?: return@forEach
                        putZipEntry(zip, "$ENTRY_PORTRAITS_PREFIX$relative", file.readBytes())
                    }
            }
        }
        return output.toByteArray()
    }

    private suspend fun restoreArchive(archive: ByteArray, includePortraits: Boolean) {
        val entries = unzip(archive)
        val databaseBytes = entries[ENTRY_DATABASE]
            ?: error("备份包中缺少数据库")
        require(
            databaseBytes.size >= SQLITE_HEADER.size &&
                databaseBytes.copyOfRange(0, SQLITE_HEADER.size)
                    .contentEquals(SQLITE_HEADER)
        ) { "备份包中的数据库格式无效" }

        val profileName = prefs.activeDbName
        val dbName = "$profileName.db"
        val destination = appContext.getDatabasePath(dbName)
        destination.parentFile?.mkdirs()
        val rollback = File(appContext.cacheDir, "webdav-rollback-$dbName")
        val staging = File(destination.parentFile, "$dbName.webdav.tmp")

        runCatching {
            if (destination.exists()) destination.copyTo(rollback, overwrite = true)
            staging.writeBytes(databaseBytes)

            ServiceContainer.localRepository.close()
            NekobotDatabase.closeProfile(profileName)
            listOf(
                destination,
                appContext.getDatabasePath("$dbName-journal"),
                appContext.getDatabasePath("$dbName-wal"),
                appContext.getDatabasePath("$dbName-shm")
            ).forEach { file -> if (file.exists() && !file.delete()) error("无法替换数据库") }

            if (!staging.renameTo(destination)) {
                staging.copyTo(destination, overwrite = true)
                staging.delete()
            }

            appContext.getSharedPreferences(
                "token_usage_$dbName",
                Context.MODE_PRIVATE
            ).edit().clear().commit()
            AchievementManager.clearScope("local:$profileName")

            entries[ENTRY_TOKEN_USAGE]?.let { raw ->
                val tokenPrefs =
                    appContext.getSharedPreferences("token_usage_$dbName", Context.MODE_PRIVATE)
                restorePreferences(
                    tokenPrefs,
                    JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject
                )
            }

            entries[ENTRY_ACHIEVEMENTS]?.let { raw ->
                val obj = JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject
                val key = AchievementManager.storageKeyForScope("local:$profileName")
                val value = obj.get("value")?.asString.orEmpty()
                appContext.getSharedPreferences(
                    ACHIEVEMENT_PREF_NAME,
                    Context.MODE_PRIVATE
                ).edit().apply {
                    if (value.isBlank()) remove(key) else putString(key, value)
                }.commit()
            }

            val portraitEntries = entries.filterKeys { it.startsWith(ENTRY_PORTRAITS_PREFIX) }
            if (includePortraits && portraitEntries.isNotEmpty()) {
                val portraitDir = File(appContext.cacheDir, "portraits/$profileName")
                if (portraitDir.exists()) portraitDir.deleteRecursively()
                portraitEntries.forEach { (path, bytes) ->
                    val relative = path.removePrefix(ENTRY_PORTRAITS_PREFIX)
                    if (isSafeRelativePath(relative)) {
                        File(portraitDir, relative).apply {
                            parentFile?.mkdirs()
                            writeBytes(bytes)
                        }
                    }
                }
            }

            ServiceContainer.switchLocalDb(profileName)
            val restoredDb = NekobotDatabase.get(appContext, profileName)
            restoredDb.openHelper.writableDatabase
            entries[ENTRY_CREDENTIALS]?.let { raw ->
                restoreCredentialBundle(restoredDb, raw)
            }
        }.onFailure { failure ->
            runCatching {
                NekobotDatabase.closeProfile(profileName)
                if (rollback.exists()) {
                    rollback.copyTo(destination, overwrite = true)
                }
                ServiceContainer.switchLocalDb(profileName)
            }
            throw failure
        }

        rollback.delete()
        staging.delete()
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

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                require(isSafeRelativePath(name)) { "备份包包含不安全路径" }
                if (!entry.isDirectory) {
                    require(entries.size < MAX_ARCHIVE_ENTRIES) { "备份包文件数量异常" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        totalSize += read
                        require(totalSize <= MAX_ARCHIVE_SIZE) { "备份包解压后过大" }
                        output.write(buffer, 0, read)
                    }
                    entries[name] = output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
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

    private fun normalizeBaseUrl(url: String): String {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "WebDAV 根地址必须以 http:// 或 https:// 开头"
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
        const val KEY_SYNC_BASE_PREFIX = "sync_base_"

        const val BACKUP_FOLDER = "nekobot"
        const val BACKUP_FILENAME = "config.nbotcfg"
        const val USER_AGENT = "NekoBot-Android-WebDAV/1.0"

        const val ENTRY_DATABASE = "database.sqlite"
        const val ENTRY_CREDENTIALS = "credentials.json"
        const val ENTRY_METADATA = "metadata.json"
        const val ENTRY_TOKEN_USAGE = "token-usage.json"
        const val ENTRY_ACHIEVEMENTS = "achievements.json"
        const val ENTRY_PORTRAITS_PREFIX = "portraits/"
        const val ACHIEVEMENT_PREF_NAME = "nekobot_achievements"
        const val TYPE_SESSION = "session"
        const val TYPE_MESSAGE = "message"
        const val TYPE_CHARACTER = "character"
        const val TYPE_WORLD_BOOK = "world_book"
        const val TYPE_WORLD_BOOK_ENTRY = "world_book_entry"
        const val MAX_ARCHIVE_ENTRIES = 5_000
        const val MAX_ARCHIVE_SIZE = 1024L * 1024L * 1024L

        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}

/** 与原仓库 config.nbotcfg 相同的 PBKDF2 + Fernet 外层格式。 */
internal object LocalWebDavArchiveCodec {
    private const val KDF_ITERATIONS = 390_000
    private const val FERNET_VERSION: Byte = 0x80.toByte()
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
