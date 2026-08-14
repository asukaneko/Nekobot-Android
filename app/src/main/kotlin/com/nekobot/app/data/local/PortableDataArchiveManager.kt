package com.nekobot.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.OffsetDateTime
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
        listOf("local_sessions", "local_messages", "local_agent_runs", "local_message_favorites")
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

    suspend fun scanCurrent(): List<PortableCategorySummary> = withContext(Dispatchers.IO) {
        val db = activeDatabase()
        PortableDataCategory.entries.map { category ->
            val tableDetails = category.tables.map { table ->
                PortableCategoryDetail("table:$table", countRows(db, table))
            }
            val fileDetails = attachmentRoots(category).map { (rootId, root) ->
                PortableCategoryDetail("root:$rootId", countFiles(root), isFile = true)
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
            val details = tableDetails + fileDetails + settings + globalMemory + credentialBundle
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
            val summaries = mutableListOf<PortableCategorySummary>()
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
                    if (selectedTables.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("data/${category.id}.json"))
                        rowCount = writeDatabaseCategory(zip, db, category, selectedTables)
                        zip.closeEntry()
                    }
                    if (category == PortableDataCategory.APP_SETTINGS && (allDetails || "app_settings" in detailKeys.orEmpty())) {
                        putBytes(zip, "data/${category.id}.json", captureAppSettings())
                        rowCount = 1
                    }
                    selectedRoots.forEach { (rootId, root) ->
                        fileCount += writeDirectory(zip, category, rootId, root)
                    }
                    if (category == PortableDataCategory.GLOBAL_MEMORY && (allDetails || "global_memory" in detailKeys.orEmpty())) {
                        val memory = globalMemoryFile()
                        if (memory.isFile) {
                            putFile(zip, "files/${category.id}/memory/global-memory.md", memory)
                            fileCount++
                        }
                    }
                    summaries += PortableCategorySummary(category, rowCount, fileCount)
                }

                if (
                    PortableDataCategory.CREDENTIALS in selected &&
                    (selectedDetails[PortableDataCategory.CREDENTIALS].isNullOrEmpty() ||
                        "credentials_bundle" in selectedDetails[PortableDataCategory.CREDENTIALS].orEmpty())
                ) {
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
                putBytes(zip, MANIFEST_ENTRY, manifestJson(preview, db.openHelper.readableDatabase.version))
            }

            val target = if (password.isBlank()) {
                temp.inputStream()
            } else {
                require(temp.length() <= MAX_ENCRYPTED_ARCHIVE_BYTES) {
                    "加密归档超过 256 MB，请取消大型文件类别后重试"
                }
                val encrypted = LocalWebDavArchiveCodec.encrypt(
                    archive = temp.readBytes(),
                    password = password,
                    profileName = ServiceContainerProfile.activeName()
                )
                ByteArrayInputStream(encrypted)
            }
            target.use { input -> BufferedOutputStream(output).use { input.copyTo(it) } }
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
            selected.filter { it.tables.isNotEmpty() || it == PortableDataCategory.APP_SETTINGS }.forEach { category ->
                require(entries.containsKey("data/${category.id}.json")) {
                    "归档缺少 ${category.id} 数据"
                }
            }
            val db = activeDatabase()
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
                        rewriteMediaReferences = PortableDataCategory.MEDIA in selected
                    )
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
            if (PortableDataCategory.APP_SETTINGS in selected) {
                entries["data/${PortableDataCategory.APP_SETTINGS.id}.json"]?.let(::restoreAppSettings)
                importedRows++
            }

            // 原始 SQLite 合并和文件恢复完成后重建本地仓库，刷新长生命周期缓存及所有 Room Flow。
            com.nekobot.app.ServiceContainer.switchLocalDb(ServiceContainerProfile.activeName())

            PortableImportResult(importedRows, importedFiles, selected.size)
        } finally {
            archive.cleanup()
        }
    }

    private fun activeDatabase(): NekobotDatabase =
        NekobotDatabase.get(appContext, ServiceContainerProfile.activeName())

    private fun countRows(db: NekobotDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `${safeName(table)}`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
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
                require(rawFile.length() <= MAX_ENCRYPTED_ARCHIVE_BYTES) { "加密归档超过 256 MB 限制" }
                val decrypted = runCatching {
                    LocalWebDavArchiveCodec.decrypt(rawFile.readBytes(), password)
                }.getOrElse { throw IllegalArgumentException("归档密码错误或文件已损坏", it) }
                require(isZip(decrypted)) { "不是有效的 NekoBot 数据归档" }
                zipFile = File.createTempFile("portable-import-", ".zip", appContext.cacheDir)
                zipFile.writeBytes(decrypted)
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
        require(root.get("version")?.asInt == FORMAT_VERSION) { "不支持的数据归档版本" }
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
                    (PortableDataCategory.CREDENTIALS in selected && name == CREDENTIALS_ENTRY)
                if (!entry.isDirectory && wanted) {
                    val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                    total += bytes.size
                    require(total <= MAX_EXPANDED_BYTES) { "归档解压后过大" }
                    result[name] = bytes
                }
            }
        }
        return result
    }

    private fun attachmentRoots(category: PortableDataCategory): List<Pair<String, File>> = when (category) {
        PortableDataCategory.MEDIA -> listOf(
            "portraits" to File(appContext.filesDir, "portraits"),
            "cached_portraits" to File(appContext.cacheDir, "portraits"),
            "chat_backgrounds" to File(appContext.filesDir, "chat_backgrounds"),
            "fonts" to File(appContext.filesDir, "fonts")
        )
        PortableDataCategory.WORKSPACE -> listOf("workspace" to File(appContext.filesDir, "workspace"))
        PortableDataCategory.EXTENSIONS -> listOf("skills" to File(appContext.filesDir, "skills"))
        else -> emptyList()
    }

    private fun captureAppSettings(): ByteArray {
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

    private fun restoreAppSettings(raw: ByteArray) {
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
        root: File
    ): Int {
        if (!root.isDirectory) return 0
        val canonicalRoot = root.canonicalFile
        var count = 0
        root.walkTopDown().forEach { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@forEach
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(canonicalRoot.path + File.separator)) { "文件路径越界" }
            require(file.length() <= MAX_ATTACHMENT_BYTES) { "文件 ${file.name} 超过 64 MB 限制" }
            val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            putFile(zip, "files/${category.id}/$rootId/$relative", canonical)
            count++
        }
        return count
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
        return count
    }

    private fun countFiles(root: File): Int =
        if (!root.isDirectory) 0 else root.walkTopDown().count { it.isFile && !Files.isSymbolicLink(it.toPath()) }

    private fun globalMemoryFile() = File(appContext.filesDir, "agent/global-memory.md")

    private fun manifestJson(preview: PortableArchivePreview, databaseVersion: Int): ByteArray {
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", FORMAT_VERSION)
            addProperty("app_version", preview.sourceVersion)
            addProperty("database_version", databaseVersion)
            addProperty("exported_at", preview.exportedAt)
            add("categories", com.google.gson.JsonArray().apply {
                preview.categories.forEach { summary ->
                    add(JsonObject().apply {
                        addProperty("id", summary.category.id)
                        addProperty("rows", summary.rowCount)
                        addProperty("files", summary.fileCount)
                    })
                }
            })
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

    companion object {
        private const val FORMAT = "nekobot-portable-data"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CREDENTIALS_ENTRY = "credentials/portable-credentials.json"
        private const val BLOB_KEY = "__base64_blob__"
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_ENTRIES = 50_000
        private const val MAX_INPUT_BYTES = 512L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private const val MAX_ATTACHMENT_BYTES = 64L * 1024 * 1024
        private const val MAX_ENCRYPTED_ARCHIVE_BYTES = 256L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024L * 1024
        private const val GLOBAL_MEMORY_MAX_BYTES = 256L * 1024
    }
}
