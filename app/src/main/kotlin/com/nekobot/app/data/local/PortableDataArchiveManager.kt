package com.nekobot.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 可按类别选择的本地数据。顺序同时用于导入时的父子表写入顺序。 */
enum class PortableDataCategory(
    val id: String,
    internal val tables: List<String> = emptyList()
) {
    CONVERSATIONS(
        "conversations",
        listOf(
            "local_sessions",
            "local_messages",
            "local_message_variants",
            "local_agent_runs",
            "local_agent_tool_messages",
            "local_subagent_tasks",
            "local_subagent_messages",
            "local_subagent_tool_calls",
            "local_agent_notices",
            "local_message_favorites",
            "local_message_images",
            "local_experience_archives",
            "local_experience_sources",
            "local_experience_archive_jobs"
        )
    ),
    CHARACTERS("characters", listOf("local_characters")),
    WORLD_BOOKS("world_books", listOf("local_world_books", "local_world_book_entries")),
    MEMORIES(
        "memories",
        listOf(
            "local_character_states",
            "local_relationship_states",
            "local_character_memories",
            "local_state_snapshots"
        )
    ),
    AI_CONFIG("ai_config", listOf("local_ai_models", "local_failover_health")),
    EXTENSIONS(
        "extensions",
        listOf(
            "local_hooks",
            "local_hook_logs",
            "local_tasks",
            "local_workflows",
            "local_skills",
            "local_tools",
            "local_mcp_servers"
        )
    ),
    KNOWLEDGE("knowledge", listOf("local_knowledge_documents", "local_knowledge_chunks")),
    ANALYTICS("analytics", listOf("routing_decision_logs")),
    APP_SETTINGS("app_settings"),
    CREDENTIALS("credentials", listOf("local_api_keys", "local_oauth_accounts")),
    MEDIA("media"),
    WORKSPACE("workspace"),
    /** 自定义表情包：名称表 + stickers 目录中的图片文件。 */
    STICKERS("stickers", listOf("local_stickers")),
    GLOBAL_MEMORY("global_memory");

    companion object {
        fun fromId(id: String): PortableDataCategory? = entries.firstOrNull { it.id == id }
    }
}

data class PortableCategorySummary(
    val category: PortableDataCategory,
    val rowCount: Int = 0,
    val fileCount: Int = 0,
    val details: List<PortableCategoryDetail> = emptyList()
) {
    val itemCount: Int get() = rowCount + fileCount
}

data class PortableCategoryDetail(
    val key: String,
    val itemCount: Int,
    val isFile: Boolean = false
)

data class PortableArchivePreview(
    val exportedAt: String,
    val sourceVersion: String,
    val encrypted: Boolean,
    val categories: List<PortableCategorySummary>
)

data class PortableImportResult(
    val importedRows: Int,
    val importedFiles: Int,
    val categories: Int
)

/**
 * NekoBot 本地数据可携带归档。
 *
 * - 每个数据库类别独立保存为 data/<category>.json，导入可再次筛选。
 * - 文件类别使用固定白名单根目录，解压时校验 canonical path，拒绝路径穿越。
 * - Room 中的设备 Keystore 密文不会直接导出；凭据类别使用可移植清单，并强制加密整个归档。
 */
class PortableDataArchiveManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    suspend fun scanCurrent(): List<PortableCategorySummary> = withContext(Dispatchers.IO) {
        val db = activeDatabase()
        requirePortableTableCoverage(db)
        val currentSessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
        PortableDataCategory.entries.map { category ->
            val tableDetails = category.tables.map { table ->
                PortableCategoryDetail("table:$table", countRows(db, table))
            }
            val fileDetails = attachmentRoots(category).map { (rootId, root) ->
                val allowedWorkspaceRoots = if (category == PortableDataCategory.WORKSPACE) {
                    currentSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME
                } else null
                PortableCategoryDetail(
                    "root:$rootId",
                    countFiles(root, allowedWorkspaceRoots),
                    isFile = true
                )
            }
            val globalMemory = if (
                category == PortableDataCategory.GLOBAL_MEMORY && globalMemoryFile().isFile
            ) listOf(PortableCategoryDetail("global_memory", 1, isFile = true)) else emptyList()
            val settings = if (category == PortableDataCategory.APP_SETTINGS) {
                listOf(PortableCategoryDetail("app_settings", 1))
            } else emptyList()
            val credentialBundle = if (category == PortableDataCategory.CREDENTIALS) {
                listOf(PortableCategoryDetail("credentials_bundle", 1))
            } else emptyList()
            val plotStory = if (
                category == PortableDataCategory.CONVERSATIONS && currentSessionIds.isNotEmpty()
            ) listOf(PortableCategoryDetail(PLOT_STORY_DETAIL, 1)) else emptyList()
            val details = tableDetails + fileDetails + settings + globalMemory + credentialBundle + plotStory
            PortableCategorySummary(
                category = category,
                rowCount = details.filterNot { it.isFile }.sumOf(PortableCategoryDetail::itemCount),
                fileCount = details.filter { it.isFile }.sumOf(PortableCategoryDetail::itemCount),
                details = details
            )
        }
    }

    suspend fun export(
        selected: Set<PortableDataCategory>,
        password: String,
        output: OutputStream,
        appVersion: String,
        selectedDetails: Map<PortableDataCategory, Set<String>> = emptyMap()
    ): PortableArchivePreview = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "请至少选择一个导出类别" }
        if (PortableDataCategory.CREDENTIALS in selected) {
            require(password.trim().length >= MIN_PASSWORD_LENGTH) { "导出账号与凭据时，密码至少需要 8 位" }
        }

        val temp = File.createTempFile("portable-data-", ".zip", appContext.cacheDir)
        try {
            val db = activeDatabase()
            requirePortableTableCoverage(db)
            val currentSessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
            val summaries = mutableListOf<PortableCategorySummary>()
            val fileInventory = com.google.gson.JsonArray()
            val exportedAt = OffsetDateTime.now().toString()
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                PortableDataCategory.entries.filter(selected::contains).forEach { category ->
                    val detailKeys = selectedDetails[category]
                    val allDetails = detailKeys.isNullOrEmpty()
                    val selectedTables = if (allDetails) {
                        category.tables
                    } else {
                        category.tables.filter { "table:$it" in detailKeys }
                    }
                    val selectedRoots = if (allDetails) {
                        attachmentRoots(category)
                    } else {
                        attachmentRoots(category).filter { "root:${it.first}" in detailKeys }
                    }
                    var rowCount = 0
                    var fileCount = 0
                    val includePlotStory = category == PortableDataCategory.CONVERSATIONS &&
                        "local_sessions" in selectedTables &&
                        (allDetails || PLOT_STORY_DETAIL in detailKeys.orEmpty())
                    if (selectedTables.isNotEmpty() || category == PortableDataCategory.CONVERSATIONS) {
                        zip.putNextEntry(ZipEntry("data/${category.id}.json"))
                        rowCount = writeDatabaseCategory(zip, db, category, selectedTables)
                        zip.closeEntry()
                    }
                    if (includePlotStory) {
                        val sessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
                        val story = LocalPlotStoryStore.capture(
                            appContext,
                            ServiceContainerProfile.activeName(),
                            sessionIds
                        )
                        val bytes = gson.toJson(story).toByteArray(StandardCharsets.UTF_8)
                        require(bytes.size <= MAX_PLOT_STORY_BYTES) { "故事地图超过 16 MB 限制" }
                        putBytes(zip, PLOT_STORY_ENTRY, bytes)
                        rowCount++
                    }
                    if (category == PortableDataCategory.APP_SETTINGS && (allDetails || "app_settings" in detailKeys.orEmpty())) {
                        putBytes(zip, "data/${category.id}.json", captureAppSettings())
                        rowCount = 1
                    }
                    selectedRoots.forEach { (rootId, root) ->
                        val allowedWorkspaceRoots = if (category == PortableDataCategory.WORKSPACE) {
                            currentSessionIds + LocalWorkspaceStorage.SHARED_DIR_NAME
                        } else null
                        fileCount += writeDirectory(
                            zip,
                            category,
                            rootId,
                            root,
                            allowedWorkspaceRoots,
                            fileInventory
                        )
                    }
                    if (category == PortableDataCategory.GLOBAL_MEMORY && (allDetails || "global_memory" in detailKeys.orEmpty())) {
                        val memory = globalMemoryFile()
                        if (memory.isFile) {
                            val entryName = "files/${category.id}/memory/global-memory.md"
                            putFile(zip, entryName, memory)
                            fileInventory.add(fileInventoryEntry(
                                category = category,
                                rootId = "memory",
                                logicalPath = "global-memory.md",
                                archivePath = entryName,
                                size = memory.length(),
                                sha256 = sha256(memory)
                            ))
                            fileCount++
                        }
                    }
                    summaries += PortableCategorySummary(category, rowCount, fileCount)
                }

                var credentialsIncluded = false
                if (
                    PortableDataCategory.CREDENTIALS in selected &&
                    (selectedDetails[PortableDataCategory.CREDENTIALS].isNullOrEmpty() ||
                        "credentials_bundle" in selectedDetails[PortableDataCategory.CREDENTIALS].orEmpty())
                ) {
                    credentialsIncluded = true
                    val bundle = LocalDatabaseCredentialBundle.capture(db)
                    val encryptedBundle = LocalWebDavArchiveCodec.encrypt(
                        archive = bundle,
                        password = password,
                        profileName = ServiceContainerProfile.activeName()
                    )
                    putBytes(zip, CREDENTIALS_ENTRY, encryptedBundle)
                }

                val preview = PortableArchivePreview(
                    exportedAt = exportedAt,
                    sourceVersion = appVersion,
                    encrypted = password.isNotBlank(),
                    categories = summaries
                )
                putBytes(
                    zip,
                    MANIFEST_ENTRY,
                    manifestJson(
                        preview = preview,
                        databaseVersion = db.openHelper.readableDatabase.version,
                        profileName = ServiceContainerProfile.activeName(),
                        fileInventory = fileInventory,
                        credentialsIncluded = credentialsIncluded
                    )
                )
            }

            val targetFile = if (password.isBlank()) {
                temp
            } else {
                val encrypted = File.createTempFile(
                    "portable-data-encrypted-",
                    ".nbotcfg",
                    appContext.cacheDir
                )
                try {
                    LocalWebDavArchiveCodec.encryptFile(
                        archive = temp,
                        password = password,
                        profileName = ServiceContainerProfile.activeName(),
                        output = encrypted
                    )
                    require(encrypted.length() <= MAX_ENCRYPTED_ARCHIVE_BYTES) {
                        "加密归档超过 512 MB，请减少导出内容后重试"
                    }
                    encrypted
                } catch (error: Exception) {
                    encrypted.delete()
                    throw error
                }
            }
            try {
                targetFile.inputStream().buffered().use { input ->
                    BufferedOutputStream(output).use { input.copyTo(it) }
                }
            } finally {
                if (targetFile != temp) targetFile.delete()
            }
            PortableArchivePreview(exportedAt, appVersion, password.isNotBlank(), summaries)
        } finally {
            temp.delete()
        }
    }

    suspend fun inspect(input: InputStream, password: String): PortableArchivePreview =
        withContext(Dispatchers.IO) {
            val archive = resolveArchive(input, password)
            try {
                archive.preview
            } finally {
                archive.cleanup()
            }
        }

    suspend fun import(
        input: InputStream,
        password: String,
        selected: Set<PortableDataCategory>
    ): PortableImportResult = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "请至少选择一个导入类别" }
        val archive = resolveArchive(input, password)
        try {
            val available = archive.preview.categories.mapTo(linkedSetOf()) { it.category }
            require(selected.all { it in available }) { "所选类别不在归档中" }

            val entries = readSelectedDataEntries(archive.zipFile, selected)
            val importedStory = entries[PLOT_STORY_ENTRY]?.let(::parsePlotStory)
            val importedSessionIds = if (
                importedStory != null && PortableDataCategory.CONVERSATIONS in selected
            ) {
                parseImportedSessionIds(entries["data/conversations.json"])
            } else {
                emptySet()
            }
            selected.filter { it.tables.isNotEmpty() || it == PortableDataCategory.APP_SETTINGS }.forEach { category ->
                require(entries.containsKey("data/${category.id}.json")) {
                    "归档缺少 ${category.id} 数据"
                }
            }
            val db = activeDatabase()
            requirePortableTableCoverage(db)
            var importedRows = 0
            db.withTransaction {
                PortableDataCategory.entries.filter(selected::contains).forEach { category ->
                    if (category.tables.isEmpty()) return@forEach
                    val raw = entries["data/${category.id}.json"] ?: return@forEach
                    importedRows += restoreDatabaseCategory(
                        database = db,
                        category = category,
                        raw = raw,
                        preserveExistingSecrets = PortableDataCategory.CREDENTIALS !in selected,
                        rewriteMediaReferences = PortableDataCategory.MEDIA in selected ||
                            PortableDataCategory.WORLD_BOOKS in selected ||
                            PortableDataCategory.STICKERS in selected
                    )
                }
                if (PortableDataCategory.CONVERSATIONS in selected) {
                    invalidateExperiencesWithoutVisibleSources(db)
                }
                if (PortableDataCategory.CREDENTIALS in selected) {
                    require(password.trim().length >= MIN_PASSWORD_LENGTH) {
                        "导入账号与凭据时，请输入导出时设置的至少 8 位密码"
                    }
                    val encryptedCredentials = entries[CREDENTIALS_ENTRY]
                        ?: throw IllegalArgumentException("归档缺少可移植凭据清单")
                    val credentials = runCatching {
                        LocalWebDavArchiveCodec.decrypt(encryptedCredentials, password)
                    }.getOrElse { throw IllegalArgumentException("凭据密码错误或凭据清单已损坏", it) }
                    LocalDatabaseCredentialBundle.restore(db, credentials)
                }
            }

            val importedFiles = restoreSelectedFiles(archive.zipFile, selected)
            if (PortableDataCategory.CONVERSATIONS in selected && importedStory != null) {
                val currentSessionIds = db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }
                LocalPlotStoryStore.mergeImportedSessions(
                    appContext,
                    ServiceContainerProfile.activeName(),
                    importedSessionIds,
                    currentSessionIds,
                    importedStory
                )
            }
            if (PortableDataCategory.APP_SETTINGS in selected) {
                entries["data/${PortableDataCategory.APP_SETTINGS.id}.json"]?.let(::restoreAppSettings)
                importedRows++
            }

            // 原始 SQLite 合并和文件恢复完成后重建本地仓库，刷新长生命周期缓存及所有 Room Flow。
            com.nekobot.app.ServiceContainer.switchLocalDb(ServiceContainerProfile.activeName())
            val archiveIdentity = runCatching {
                JsonParser.parseString(
                    String(readManifest(archive.zipFile), StandardCharsets.UTF_8)
                ).asJsonObject.get("source_profile_id")?.asString.orEmpty()
            }.getOrDefault("")
            LocalDataCatalog.adoptProfileIdIfAbsent(
                appContext,
                ServiceContainerProfile.activeName(),
                archiveIdentity
            )
            val archiveSyncGroupId = runCatching {
                JsonParser.parseString(
                    String(readManifest(archive.zipFile), StandardCharsets.UTF_8)
                ).asJsonObject.get("source_sync_group_id")?.asString.orEmpty()
            }.getOrDefault("")
            LocalDataCatalog.adoptSyncGroupIdIfAbsent(
                appContext,
                ServiceContainerProfile.activeName(),
                archiveSyncGroupId
            )

            PortableImportResult(importedRows, importedFiles, selected.size)
        } finally {
            archive.cleanup()
        }
    }

    /** 选择性导入后，缺原消息或原消息已删除的档案不能继续参与召回。 */
    private fun invalidateExperiencesWithoutVisibleSources(db: NekobotDatabase) {
        db.openHelper.writableDatabase.execSQL(
            """
            UPDATE local_experience_archives SET status = 'stale'
            WHERE NOT EXISTS (
                SELECT 1 FROM local_experience_sources AS source
                WHERE source.archive_id = local_experience_archives.id
            ) OR EXISTS (
                SELECT 1 FROM local_experience_sources AS source
                LEFT JOIN local_messages AS message ON message.id = source.message_id
                WHERE source.archive_id = local_experience_archives.id
                  AND (message.id IS NULL OR message.session_id != local_experience_archives.session_id OR message.deleted != 0)
            )
            """.trimIndent()
        )
    }

    private fun activeDatabase(): NekobotDatabase =
        NekobotDatabase.get(appContext, ServiceContainerProfile.activeName())

    private fun parsePlotStory(raw: ByteArray): DbProfileStoryData {
        val root = runCatching { JsonParser.parseString(String(raw, StandardCharsets.UTF_8)).asJsonObject }
            .getOrElse { throw IllegalArgumentException("故事地图格式无效", it) }
        val graphJson = root.get("graphJson")?.takeUnless { it.isJsonNull }?.asString
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("故事地图缺少图谱")
        require(JsonParser.parseString(graphJson).isJsonObject) { "故事地图格式无效" }
        val choicesObject = root.getAsJsonObject("plotChoices")
            ?: throw IllegalArgumentException("剧情选项格式无效")
        val choices = linkedMapOf<String, String>()
        choicesObject.entrySet().forEach { (sessionId, rawChoices) ->
            require(sessionId.isNotBlank()) { "剧情选项包含空会话 ID" }
            val choicesJson = rawChoices.takeUnless { it.isJsonNull }?.asString
                ?: throw IllegalArgumentException("剧情选项格式无效")
            require(JsonParser.parseString(choicesJson).isJsonObject) { "剧情选项格式无效" }
            choices[sessionId] = choicesJson
        }
        return DbProfileStoryData(graphJson, choices)
    }

    private fun parseImportedSessionIds(raw: ByteArray?): Set<String> {
        if (raw == null) return emptySet()
        val rows = runCatching {
            JsonParser.parseString(String(raw, StandardCharsets.UTF_8))
                .asJsonObject.getAsJsonObject("tables")?.getAsJsonArray("local_sessions")
        }.getOrElse { throw IllegalArgumentException("会话归档格式无效", it) }
        return rows?.mapTo(linkedSetOf()) { row ->
            row.asJsonObject.get("id")?.takeUnless { it.isJsonNull }?.asString
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("会话归档缺少 ID")
        } ?: emptySet()
    }

    private fun countRows(db: NekobotDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `${safeName(table)}`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /**
     * 防止新增 Room 表后被数据迁移静默漏掉。归档声明必须与当前数据库表保持一致。
     */
    private fun requirePortableTableCoverage(db: NekobotDatabase) {
        LocalDataCatalog.validateRoomCoverage(db.openHelper.readableDatabase)
    }

    private fun writeDatabaseCategory(
        output: OutputStream,
        db: NekobotDatabase,
        category: PortableDataCategory,
        selectedTables: List<String> = category.tables
    ): Int {
        var count = 0
        val writer = JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.beginObject()
        writer.name("tables").beginObject()
        selectedTables.forEach { table ->
            writer.name(table).beginArray()
            db.openHelper.readableDatabase.query("SELECT * FROM `${safeName(table)}`").use { cursor ->
                while (cursor.moveToNext()) {
                    writer.beginObject()
                    cursor.columnNames.forEachIndexed { index, column ->
                        writer.name(column)
                        if (isSensitiveColumn(table, column)) {
                            writer.value("")
                        } else if (isDeviceLocalColumn(table, column)) {
                            writer.nullValue()
                        } else {
                            writeCursorValue(writer, cursor, index)
                        }
                    }
                    writer.endObject()
                    count++
                }
            }
            writer.endArray()
        }
        writer.endObject()
        writer.endObject()
        writer.flush()
        return count
    }

    private fun restoreDatabaseCategory(
        database: NekobotDatabase,
        category: PortableDataCategory,
        raw: ByteArray,
        preserveExistingSecrets: Boolean,
        rewriteMediaReferences: Boolean
    ): Int {
        val root = JsonParser.parseString(String(raw, StandardCharsets.UTF_8)).asJsonObject
        val tables = root.getAsJsonObject("tables") ?: throw IllegalArgumentException("类别数据格式无效")
        var restored = 0
        category.tables.forEach { table ->
            val rows = tables.getAsJsonArray(table) ?: return@forEach
            val allowedColumns = tableColumns(database, table)
            rows.forEach { element ->
                val row = element.asJsonObject
                val values = ContentValues()
                row.entrySet().forEach { (column, value) ->
                    if (column in allowedColumns) {
                        val portableValue = if (rewriteMediaReferences) {
                            rewriteMediaReference(table, column, value)
                        } else value
                        putJsonValue(values, column, portableValue)
                    }
                }
                if (preserveExistingSecrets) {
                    preserveSecretsFromExistingRow(database, table, values)
                }
                if (values.size() > 0) {
                    mergeRow(database, table, values)
                    restored++
                }
            }
        }
        return restored
    }

    /**
     * 先插入、冲突后原位更新，避免 SQLite REPLACE 删除父行并触发级联删除，
     * 从而保留归档之外、但属于同一会话或世界书的现有子记录。
     */
    private fun mergeRow(database: NekobotDatabase, table: String, values: ContentValues) {
        val writable = database.openHelper.writableDatabase
        val safeTable = safeName(table)
        val primaryKeys = tablePrimaryKeys(database, safeTable)
        require(primaryKeys.isNotEmpty() && primaryKeys.all(values::containsKey)) {
            "归档中的 $safeTable 记录缺少主键"
        }
        val inserted = writable.insert(
            safeTable,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            values
        )
        if (inserted != -1L) return

        val where = primaryKeys.joinToString(" AND ") { "`${safeName(it)}` = ?" }
        val bindArgs = primaryKeys.map(values::get).toTypedArray()
        val updated = writable.update(
            safeTable,
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            values,
            where,
            bindArgs
        )
        require(updated > 0) { "无法合并 $safeTable 记录，可能存在唯一键冲突" }
    }

    private fun tablePrimaryKeys(database: NekobotDatabase, table: String): List<String> =
        database.openHelper.readableDatabase.query("PRAGMA table_info(`${safeName(table)}`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            buildList {
                val indexed = mutableListOf<Pair<Int, String>>()
                while (cursor.moveToNext()) {
                    val order = cursor.getInt(primaryKeyIndex)
                    if (order > 0) indexed += order to cursor.getString(nameIndex)
                }
                indexed.sortedBy { it.first }.forEach { add(it.second) }
            }
        }

    private fun rewriteMediaReference(table: String, column: String, value: JsonElement): JsonElement {
        val supported = when (table) {
            "local_sessions" -> column in setOf("portrait", "sender_avatar", "character_avatar")
            "local_characters" -> column in setOf("portrait", "avatar")
            "local_world_books" -> column == "cover_url"
            "local_message_images" -> column in setOf("file_path", "reference_image_path")
            "local_messages" -> column == "audio_url"
            "local_stickers" -> column == "file_path"
            else -> false
        }
        if (!supported || value.isJsonNull || !value.isJsonPrimitive) return value
        val reference = value.asString
        val path = runCatching { Uri.parse(reference).path }.getOrNull() ?: return value
        val normalized = path.replace('\\', '/')
        val target = when {
            "/files/portraits/" in normalized -> {
                val relative = normalized.substringAfter("/files/portraits/")
                resolvePortablePath(File(appContext.filesDir, "portraits"), relative)
            }
            "/cache/portraits/" in normalized -> {
                val relative = normalized.substringAfter("/cache/portraits/")
                resolvePortablePath(File(appContext.cacheDir, "portraits"), relative)
            }
            "/files/worldbook_covers/" in normalized -> {
                val relative = normalized.substringAfter("/files/worldbook_covers/")
                resolvePortablePath(File(appContext.filesDir, "worldbook_covers"), relative)
            }
            "/files/tts/" in normalized -> {
                val relative = normalized.substringAfter("/files/tts/")
                resolvePortablePath(File(appContext.filesDir, "tts"), relative)
            }
            "/files/stickers/" in normalized -> {
                val relative = normalized.substringAfter("/files/stickers/")
                resolvePortablePath(File(appContext.filesDir, "stickers"), relative)
            }
            else -> null
        } ?: return value
        return com.google.gson.JsonPrimitive(Uri.fromFile(target).toString())
    }

    private fun resolvePortablePath(root: File, relative: String): File? {
        if (relative.isBlank()) return null
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relative).canonicalFile
        return target.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun preserveSecretsFromExistingRow(
        database: NekobotDatabase,
        table: String,
        values: ContentValues
    ) {
        val columns = sensitiveColumns(table)
        if (columns.isEmpty()) return
        val id = values.getAsString("id")?.takeIf { it.isNotBlank() } ?: return
        database.openHelper.readableDatabase.query(
            "SELECT ${columns.joinToString { "`${safeName(it)}`" }} FROM `${safeName(table)}` WHERE id = ? LIMIT 1",
            arrayOf(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            columns.forEachIndexed { index, column ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                    Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
                    else -> values.put(column, cursor.getString(index))
                }
            }
        }
    }

    private fun tableColumns(database: NekobotDatabase, table: String): Set<String> =
        database.openHelper.readableDatabase.query("PRAGMA table_info(`${safeName(table)}`)").use { cursor ->
            val index = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(index)) }
        }

    private fun writeCursorValue(writer: JsonWriter, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> writer.value(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> writer.value(cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> {
                writer.beginObject()
                writer.name(BLOB_KEY).value(Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                writer.endObject()
            }
            else -> writer.nullValue()
        }
    }

    private fun putJsonValue(values: ContentValues, key: String, value: JsonElement) {
        when {
            value.isJsonNull -> values.putNull(key)
            value.isJsonObject && value.asJsonObject.has(BLOB_KEY) ->
                values.put(key, Base64.decode(value.asJsonObject.get(BLOB_KEY).asString, Base64.DEFAULT))
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                values.put(key, if (value.asBoolean) 1 else 0)
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                val text = value.asString
                if (text.contains('.') || text.contains('e', true)) values.put(key, value.asDouble)
                else values.put(key, value.asLong)
            }
            else -> values.put(key, value.asString)
        }
    }

    private data class ResolvedArchive(
        val preview: PortableArchivePreview,
        val zipFile: File,
        val cleanupFiles: List<File>
    ) {
        fun cleanup() = cleanupFiles.forEach(File::delete)
    }

    private fun resolveArchive(input: InputStream, password: String): ResolvedArchive {
        val rawFile = File.createTempFile("portable-import-", ".bin", appContext.cacheDir)
        var zipFile = rawFile
        try {
            copyBounded(input, rawFile, MAX_INPUT_BYTES)
            val encrypted = !isZip(rawFile)
            if (encrypted) {
                require(password.isNotBlank()) { "此归档已加密，请输入密码" }
                require(rawFile.length() <= MAX_ENCRYPTED_ARCHIVE_BYTES) { "加密归档超过 512 MB 限制" }
                zipFile = File.createTempFile("portable-import-", ".zip", appContext.cacheDir)
                runCatching {
                    LocalWebDavArchiveCodec.decryptFile(
                        payload = rawFile,
                        password = password,
                        output = zipFile,
                        maxOutputBytes = MAX_EXPANDED_BYTES
                    )
                }.getOrElse { throw IllegalArgumentException("归档密码错误或文件已损坏", it) }
                require(isZip(zipFile)) { "不是有效的 NekoBot 数据归档" }
            }
            val preview = readPreview(zipFile, encrypted)
            return ResolvedArchive(
                preview = preview,
                zipFile = zipFile,
                cleanupFiles = if (zipFile == rawFile) listOf(rawFile) else listOf(rawFile, zipFile)
            )
        } catch (error: Exception) {
            rawFile.delete()
            if (zipFile != rawFile) zipFile.delete()
            throw error
        }
    }

    private fun readPreview(zipFile: File, encrypted: Boolean): PortableArchivePreview {
        val manifest = readManifest(zipFile)
        val root = JsonParser.parseString(String(manifest, StandardCharsets.UTF_8)).asJsonObject
        require(root.get("format")?.asString == FORMAT) { "不是有效的 NekoBot 数据归档" }
        val formatVersion = root.get("version")?.asInt ?: 0
        require(formatVersion in MIN_READABLE_FORMAT_VERSION..FORMAT_VERSION) { "不支持的数据归档版本" }
        if (formatVersion >= 3) {
            require(root.get("archive_id")?.asString?.isNotBlank() == true) { "归档缺少 archive_id" }
            require(root.get("source_profile_id")?.asString?.isNotBlank() == true) {
                "归档缺少档案稳定 ID"
            }
            require(root.getAsJsonArray("files") != null) { "归档缺少文件清单" }
            validateFileInventory(zipFile, root.getAsJsonArray("files"))
        }
        val databaseVersion = root.get("database_version")?.asInt ?: 0
        require(databaseVersion <= activeDatabase().openHelper.readableDatabase.version) {
            "归档来自更高版本的数据库，请先升级应用"
        }
        val summaries = root.getAsJsonArray("categories")?.map { item ->
            val obj = item.asJsonObject
            val category = PortableDataCategory.fromId(obj.get("id")?.asString.orEmpty())
                ?: throw IllegalArgumentException("归档包含当前版本不支持的数据类别")
            PortableCategorySummary(
                category = category,
                rowCount = obj.get("rows")?.asInt ?: 0,
                fileCount = obj.get("files")?.asInt ?: 0
            )
        }.orEmpty()
        return PortableArchivePreview(
            exportedAt = root.get("exported_at")?.asString.orEmpty(),
            sourceVersion = root.get("app_version")?.asString.orEmpty(),
            encrypted = encrypted,
            categories = summaries
        )
    }

    /** 新格式在预览和导入前校验每个可携带文件的大小和 SHA-256。 */
    private fun validateFileInventory(zipFile: File, inventory: JsonArray) {
        require(inventory.size() <= MAX_ENTRIES) { "归档文件清单过大" }
        val expected = linkedMapOf<String, Pair<Long, String>>()
        inventory.forEach { element ->
            val entry = element.asJsonObject
            val path = safeEntryName(entry.get("path")?.asString.orEmpty())
            require(path.startsWith("files/")) { "文件清单包含非文件路径" }
            val size = entry.get("size")?.asLong ?: error("文件清单缺少大小")
            val hash = entry.get("sha256")?.asString.orEmpty()
            require(size >= 0L && hash.matches(Regex("[0-9a-f]{64}"))) {
                "文件清单中的大小或哈希无效"
            }
            require(expected.put(path, size to hash) == null) { "文件清单包含重复路径：$path" }
        }

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            val found = linkedSetOf<String>()
            var totalBytes = 0L
            while (true) {
                val archiveEntry = zip.nextEntry ?: break
                val path = safeEntryName(archiveEntry.name)
                if (!archiveEntry.isDirectory) {
                    val descriptor = expected[path]
                    if (descriptor != null) {
                        require(found.add(path)) { "归档包含重复文件：$path" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            size += count
                            totalBytes += count
                            require(totalBytes <= MAX_EXPANDED_BYTES) { "归档解压后过大" }
                            digest.update(buffer, 0, count)
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        require(size == descriptor.first && hash == descriptor.second) {
                            "归档文件校验失败：$path"
                        }
                    }
                }
                zip.closeEntry()
            }
            val missing = expected.keys - found
            require(missing.isEmpty()) { "归档缺少文件：${missing.take(5).joinToString()}" }
        }
    }

    private fun readManifest(zipFile: File): ByteArray {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "归档条目过多" }
                if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                    return readBounded(zip, MAX_MANIFEST_BYTES)
                }
            }
        }
        throw IllegalArgumentException("归档缺少 manifest.json")
    }

    private fun readSelectedDataEntries(
        zipFile: File,
        selected: Set<PortableDataCategory>
    ): Map<String, ByteArray> {
        val dataNames = selected.mapTo(linkedSetOf()) { "data/${it.id}.json" }
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "归档条目过多" }
                val name = safeEntryName(entry.name)
                val wanted = name in dataNames ||
                    (PortableDataCategory.CREDENTIALS in selected && name == CREDENTIALS_ENTRY) ||
                    (PortableDataCategory.CONVERSATIONS in selected && name == PLOT_STORY_ENTRY)
                if (!entry.isDirectory && wanted) {
                    val entryLimit = if (name == PLOT_STORY_ENTRY) {
                        MAX_PLOT_STORY_BYTES
                    } else {
                        MAX_ENTRY_BYTES
                    }
                    val bytes = readBounded(zip, entryLimit)
                    total += bytes.size
                    require(total <= MAX_EXPANDED_BYTES) { "归档解压后过大" }
                    result[name] = bytes
                }
            }
        }
        return result
    }

    private fun attachmentRoots(category: PortableDataCategory): List<Pair<String, File>> =
        LocalDataCatalog.fileRoots(appContext, category)

    internal fun captureAppSettings(): ByteArray {
        val prefs = com.nekobot.app.ServiceContainer.prefs
        return JsonObject().apply {
            addProperty("chat_input_layout", prefs.chatInputLayoutMode.name)
            addProperty("recent_sessions_include_archived", prefs.recentSessionsIncludeArchived)
            addProperty("smart_routing_enabled", prefs.smartRoutingEnabled)
            addProperty("smart_routing_daily_budget_usd", prefs.smartRoutingDailyBudgetUsd)
            addProperty("rag_semantic_weight", prefs.ragSemanticWeight)
            addProperty("rag_top_k", prefs.ragTopK)
            addProperty("rag_mmr_lambda", prefs.ragMmrLambda)
            addProperty("rag_rerank_enabled", prefs.ragRerankEnabled)
            addProperty("rag_score_threshold", prefs.ragScoreThreshold)
            addProperty("rag_citation_enabled", prefs.ragCitationEnabled)
            addProperty("rag_auto_search_enabled", prefs.ragAutoSearchEnabled)
            addProperty("ab_test_enabled", prefs.abTestEnabled)
            addProperty("ab_test_split_ratio", prefs.abTestSplitRatio)
            addProperty("ab_test_control_model_id", prefs.abTestControlModelId)
            addProperty("ab_test_experiment_model_id", prefs.abTestExperimentModelId)
            addProperty("ab_test_name", prefs.abTestName)
            addProperty("follow_system_font_scale", prefs.followSystemFontScale)
            addProperty("character_view_mode", prefs.characterViewMode)
            addProperty("achievement_view_mode", prefs.achievementViewMode)
            addProperty("font_family", prefs.fontFamily)
            addProperty("custom_font_file", portableFileName(prefs.customFontPath))
            addProperty("custom_font_name", prefs.customFontName)
            addProperty("font_scale", prefs.fontScale)
            addProperty("chat_background_mode", prefs.chatBackgroundMode)
            addProperty("custom_chat_background_file", portableFileName(prefs.customChatBackgroundPath))
            addProperty("custom_chat_background_name", prefs.customChatBackgroundName)
            addProperty("chat_background_opacity", prefs.chatBackgroundOpacity)
            addProperty("font_color_override", prefs.fontColorOverride)
            addProperty("theme_color_override", prefs.themeColorOverride)
            addProperty("language", prefs.language)
        }.toString().toByteArray(StandardCharsets.UTF_8)
    }

    internal fun restoreAppSettings(raw: ByteArray) {
        val root = JsonParser.parseString(String(raw, StandardCharsets.UTF_8)).asJsonObject
        val prefs = com.nekobot.app.ServiceContainer.prefs
        root.string("chat_input_layout")?.let { prefs.chatInputLayoutMode = ChatInputLayoutMode.fromStorage(it) }
        root.bool("recent_sessions_include_archived")?.let { prefs.recentSessionsIncludeArchived = it }
        root.bool("smart_routing_enabled")?.let { prefs.smartRoutingEnabled = it }
        root.double("smart_routing_daily_budget_usd")?.let { prefs.smartRoutingDailyBudgetUsd = it }
        root.float("rag_semantic_weight")?.let { prefs.ragSemanticWeight = it }
        root.int("rag_top_k")?.let { prefs.ragTopK = it }
        root.float("rag_mmr_lambda")?.let { prefs.ragMmrLambda = it }
        root.bool("rag_rerank_enabled")?.let { prefs.ragRerankEnabled = it }
        root.float("rag_score_threshold")?.let { prefs.ragScoreThreshold = it }
        root.bool("rag_citation_enabled")?.let { prefs.ragCitationEnabled = it }
        root.bool("rag_auto_search_enabled")?.let { prefs.ragAutoSearchEnabled = it }
        root.bool("ab_test_enabled")?.let { prefs.abTestEnabled = it }
        root.float("ab_test_split_ratio")?.let { prefs.abTestSplitRatio = it }
        if (root.has("ab_test_control_model_id")) prefs.abTestControlModelId = root.string("ab_test_control_model_id")
        if (root.has("ab_test_experiment_model_id")) prefs.abTestExperimentModelId = root.string("ab_test_experiment_model_id")
        root.string("ab_test_name")?.let { prefs.abTestName = it }
        root.bool("follow_system_font_scale")?.let { prefs.followSystemFontScale = it }
        root.string("character_view_mode")?.let { prefs.characterViewMode = it }
        root.string("achievement_view_mode")?.let { prefs.achievementViewMode = it }
        root.string("font_family")?.let { prefs.fontFamily = it }
        prefs.customFontName = root.string("custom_font_name")
        root.float("font_scale")?.let { prefs.fontScale = it }
        root.string("chat_background_mode")?.let { prefs.chatBackgroundMode = it }
        prefs.customChatBackgroundName = root.string("custom_chat_background_name")
        root.float("chat_background_opacity")?.let { prefs.chatBackgroundOpacity = it }
        prefs.fontColorOverride = root.string("font_color_override")
        prefs.themeColorOverride = root.string("theme_color_override")
        root.string("language")?.let { prefs.language = it }

        prefs.customFontPath = root.string("custom_font_file")
            ?.let { safeLeafName(it) }
            ?.let { File(appContext.filesDir, "fonts/$it") }
            ?.takeIf(File::isFile)
            ?.let(Uri::fromFile)
            ?.toString()
        prefs.customChatBackgroundPath = root.string("custom_chat_background_file")
            ?.let { safeLeafName(it) }
            ?.let { File(appContext.filesDir, "chat_backgrounds/$it") }
            ?.takeIf(File::isFile)
            ?.let(Uri::fromFile)
            ?.toString()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asBoolean

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asInt

    private fun JsonObject.float(key: String): Float? =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asFloat

    private fun JsonObject.double(key: String): Double? =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asDouble

    private fun portableFileName(reference: String?): String? =
        reference
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it).path ?: it }
            ?.let(::File)
            ?.name

    private fun safeLeafName(value: String): String =
        File(value).name.also { require(it == value && it !in setOf(".", "..")) { "设置中的文件名无效" } }

    private fun writeDirectory(
        zip: ZipOutputStream,
        category: PortableDataCategory,
        rootId: String,
        root: File,
        allowedTopLevelDirectories: Set<String>? = null,
        fileInventory: JsonArray? = null
    ): Int {
        if (!root.isDirectory) return 0
        val canonicalRoot = root.canonicalFile
        var count = 0
        root.walkTopDown()
            .onEnter { directory ->
                !(category == PortableDataCategory.EXTENSIONS && rootId == "plugin_packages" &&
                    directory != root && directory.name.startsWith(".staging-"))
            }
            .forEach { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@forEach
            if (category == PortableDataCategory.EXTENSIONS && rootId == "plugin_files" &&
                file.name.startsWith(".")) return@forEach
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(canonicalRoot.path + File.separator)) { "文件路径越界" }
            require(file.length() <= MAX_ATTACHMENT_BYTES) { "文件 ${file.name} 超过 64 MB 限制" }
            val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            if (
                category == PortableDataCategory.WORKSPACE &&
                allowedTopLevelDirectories != null &&
                relative.substringBefore('/') !in allowedTopLevelDirectories
            ) return@forEach
            val entryName = "files/${category.id}/$rootId/$relative"
            if (category == PortableDataCategory.EXTENSIONS && rootId == "plugin_packages" &&
                file.name == PLUGIN_STATE_ENTRY
            ) {
                val bytes = gson.toJson(
                    PortablePluginState(enabled = false, installedAt = file.lastModified())
                ).toByteArray(StandardCharsets.UTF_8)
                putBytes(zip, entryName, bytes)
                fileInventory?.add(fileInventoryEntry(
                    category,
                    rootId,
                    relative,
                    entryName,
                    bytes.size.toLong(),
                    sha256(bytes)
                ))
            } else {
                putFile(zip, entryName, canonical)
                fileInventory?.add(fileInventoryEntry(
                    category,
                    rootId,
                    relative,
                    entryName,
                    canonical.length(),
                    sha256(canonical)
                ))
            }
            count++
        }
        return count
    }

    private fun fileInventoryEntry(
        category: PortableDataCategory,
        rootId: String,
        logicalPath: String,
        archivePath: String,
        size: Long,
        sha256: String
    ): JsonObject = JsonObject().apply {
        addProperty("category", category.id)
        addProperty("scope", LocalDataCatalog.descriptor(category).scope.name.lowercase())
        addProperty("root", rootId)
        addProperty("logical_id", "$rootId/$logicalPath")
        addProperty("path", archivePath)
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun archivePluginIds(zipFile: File): Set<String> = buildSet {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = runCatching { safeEntryName(entry.name) }.getOrNull() ?: continue
                val prefix = "files/${PortableDataCategory.EXTENSIONS.id}/plugin_packages/"
                if (!name.startsWith(prefix)) continue
                val relative = name.removePrefix(prefix)
                val pluginId = relative.substringBefore('/')
                if (pluginId.isNotBlank() && pluginId != relative && !pluginId.startsWith('.')) {
                    add(pluginId)
                }
            }
        }
    }

    private fun restoreSelectedFiles(
        zipFile: File,
        selected: Set<PortableDataCategory>
    ): Int {
        val roots = buildList {
            PortableDataCategory.entries.filter(selected::contains).forEach { category ->
                attachmentRoots(category).forEach { (rootId, root) ->
                    add("files/${category.id}/$rootId/" to root)
                }
            }
        }
        var count = 0
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "归档条目过多" }
                if (entry.isDirectory) continue
                val name = safeEntryName(entry.name)
                if (
                    PortableDataCategory.GLOBAL_MEMORY in selected &&
                    name == "files/${PortableDataCategory.GLOBAL_MEMORY.id}/memory/global-memory.md"
                ) {
                    val bytes = readBounded(zip, GLOBAL_MEMORY_MAX_BYTES)
                    val target = globalMemoryFile()
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    totalBytes += bytes.size
                    count++
                    continue
                }
                val match = roots.firstOrNull { (prefix, _) -> name.startsWith(prefix) } ?: continue
                val relative = name.removePrefix(match.first)
                if (relative.isBlank()) continue
                val canonicalRoot = match.second.canonicalFile.apply { mkdirs() }
                val target = File(canonicalRoot, relative).canonicalFile
                require(target.path.startsWith(canonicalRoot.path + File.separator)) { "归档文件路径越界" }
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, ".${target.name}.importing")
                try {
                    totalBytes += copyBounded(zip, temp, MAX_ATTACHMENT_BYTES)
                    require(totalBytes <= MAX_EXPANDED_BYTES) { "归档解压后过大" }
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } finally {
                    temp.delete()
                }
                count++
            }
        }
        if (PortableDataCategory.EXTENSIONS in selected) {
            archivePluginIds(zipFile).forEach { pluginId ->
                val pluginDirectory = File(appContext.filesDir, "plugins/$pluginId")
                if (pluginDirectory.isDirectory) {
                    File(pluginDirectory, PLUGIN_STATE_ENTRY).writeText(
                        gson.toJson(PortablePluginState(enabled = false)),
                        StandardCharsets.UTF_8
                    )
                    com.nekobot.app.ServiceContainer.pluginGrants.revoke(pluginId)
                }
            }
            com.nekobot.app.ServiceContainer.pluginManager.reload()
        }
        return count
    }

    private fun countFiles(root: File, allowedTopLevelDirectories: Set<String>? = null): Int =
        if (!root.isDirectory) 0 else root.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .count { file ->
                file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    (allowedTopLevelDirectories == null ||
                        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
                            .substringBefore('/') in allowedTopLevelDirectories)
            }

    /** 当前 Profile 的全局 Agent 记忆文件；记忆按数据库 Profile 隔离存放。 */
    private fun globalMemoryFile() = GlobalAgentMemoryStore.memoryFileFor(
        appContext,
        ServiceContainerProfile.activeName()
    )

    private fun manifestJson(
        preview: PortableArchivePreview,
        databaseVersion: Int,
        profileName: String,
        fileInventory: JsonArray,
        credentialsIncluded: Boolean
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", FORMAT_VERSION)
            addProperty("archive_id", UUID.randomUUID().toString())
            addProperty("source_profile_id", LocalDataCatalog.stableProfileId(appContext, profileName))
            addProperty("source_sync_group_id", LocalDataCatalog.stableSyncGroupId(appContext, profileName))
            addProperty("source_profile_name", profileName)
            addProperty("app_version", preview.sourceVersion)
            addProperty("database_version", databaseVersion)
            addProperty("exported_at", preview.exportedAt)
            addProperty("credentials_included", credentialsIncluded)
            add("categories", com.google.gson.JsonArray().apply {
                preview.categories.forEach { summary ->
                    val descriptor = LocalDataCatalog.descriptor(summary.category)
                    add(JsonObject().apply {
                        addProperty("id", summary.category.id)
                        addProperty("rows", summary.rowCount)
                        addProperty("files", summary.fileCount)
                        addProperty("scope", descriptor.scope.name.lowercase())
                        add("dependencies", JsonArray().apply {
                            descriptor.dependencies.sorted().forEach(::add)
                        })
                    })
                }
            })
            add("files", fileInventory)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(safeEntryName(name)))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(safeEntryName(name)))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun safeName(value: String): String {
        require(value.matches(Regex("[a-z0-9_]+"))) { "数据库对象名称无效" }
        return value
    }

    private fun safeEntryName(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == ".." }) { "归档路径无效" }
        return normalized
    }

    private fun sensitiveColumns(table: String): Set<String> = when (table) {
        "local_ai_models" -> setOf(
            "api_key", "proxy_url", "tts_headers", "tts_body_template", "stt_headers"
        )
        "local_mcp_servers" -> setOf("url", "headers_json", "args_json", "env_json")
        "local_api_keys" -> setOf("key")
        "local_oauth_accounts" -> setOf("encrypted_credentials")
        else -> emptySet()
    }

    private fun isSensitiveColumn(table: String, column: String): Boolean =
        column in sensitiveColumns(table)

    /** 完成事件的消费游标属于本机运行状态，不应在另一设备上复用。 */
    private fun isDeviceLocalColumn(table: String, column: String): Boolean =
        table == "local_agent_notices" && column == "consumed_by_run_id"

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun isZip(file: File): Boolean = file.inputStream().buffered().use { input ->
        val header = ByteArray(4)
        input.read(header) == header.size && isZip(header)
    }

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "数据归档超过大小限制" }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "数据归档超过大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private object ServiceContainerProfile {
        fun activeName(): String = com.nekobot.app.ServiceContainer.prefs.activeDbName
    }

    private data class PortablePluginState(
        val enabled: Boolean = false,
        val installedAt: Long = System.currentTimeMillis()
    )

    companion object {
        private const val FORMAT = "nekobot-portable-data"
        private const val FORMAT_VERSION = 3
        private const val MIN_READABLE_FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CREDENTIALS_ENTRY = "credentials/portable-credentials.json"
        private const val PLOT_STORY_ENTRY = "data/conversations-story.json"
        private const val PLOT_STORY_DETAIL = "plot_story"
        private const val PLUGIN_STATE_ENTRY = ".plugin-state.json"
        private const val MAX_PLOT_STORY_BYTES = 16L * 1024 * 1024
        private const val BLOB_KEY = "__base64_blob__"
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_ENTRIES = 50_000
        private const val MAX_INPUT_BYTES = 512L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private const val MAX_ATTACHMENT_BYTES = 64L * 1024 * 1024
        private const val MAX_ENCRYPTED_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val GLOBAL_MEMORY_MAX_BYTES = 256L * 1024
    }
}
