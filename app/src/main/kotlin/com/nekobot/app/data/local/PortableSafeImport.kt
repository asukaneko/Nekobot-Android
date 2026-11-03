package com.nekobot.app.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal data class PortableStagedFile(val key: String, val category: PortableDataCategory, val root: String,
    val relative: String, val source: File, val target: File)

class PreparedPortableImport internal constructor(
    val preview: PortableArchivePreview,
    internal val profile: String,
    internal val archive: PortableDataArchiveManager.ResolvedArchive,
    internal val entries: Map<String, ByteArray>,
    internal val stagedFiles: List<PortableStagedFile>,
    private val staging: File,
    internal val password: String
) : java.io.Closeable {
    internal val id = UUID.randomUUID().toString()
    internal var closed = false
    var result: PortableImportResult? = null
        internal set
    internal var recoveryPending = false
    @Synchronized override fun close() { if (!closed) { closed = true; archive.cleanup(); staging.deleteRecursively() } }
}

data class PortableImportFileDifference(val key: String, val label: String,
    val fileName: String = label.substringAfterLast('/'), val root: String = label.substringBefore('/'),
    val owner: PortableImportOwner = PortableImportOwner(),
    val currentBytes: Long = -1, val incomingBytes: Long = -1,
    val currentHash: String = "", val incomingHash: String = "")
data class PortableImportDependencyOption(val category: PortableDataCategory, val detail: PortableCategoryDetail)
data class PortableImportPreview internal constructor(
    val rows: PortableImportRowPlan,
    val fileCounts: PortableImportCounts,
    val fileDifferences: List<PortableImportFileDifference>,
    val settingDifferences: List<PortableImportFieldDifference>,
    val storyDifferent: Boolean,
    val issues: List<String>,
    val dependencyOptions: List<PortableImportDependencyOption>,
    internal val fingerprint: String,
    internal val preparedId: String,
    internal val selected: Map<PortableDataCategory, Set<String>>,
    internal val choices: PortableImportChoices,
    internal val fileBefore: Map<String, String?>,
    internal val fileAfter: Map<String, String>,
    internal val settingsBefore: JsonObject,
    internal val settingsIncoming: JsonObject?,
    internal val useStory: Boolean,
    internal val storySessionIds: Set<String>,
    internal val credentialFingerprint: String,
    internal val credentialTargets: Map<String, Set<String>>
)

internal class AndroidPortableImportSql(private val db: SupportSQLiteDatabase) : PortableImportSql {
    override fun execute(sql: String, args: List<Any?>) { db.execSQL(sql, args.toTypedArray()) }
    override fun query(sql: String, args: List<Any?>): List<JsonObject> = db.query(sql, args.toTypedArray()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(JsonObject().apply {
            cursor.columnNames.forEachIndexed { index, name ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> add(name, JsonNull.INSTANCE)
                    Cursor.FIELD_TYPE_INTEGER -> addProperty(name, cursor.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> addProperty(name, cursor.getDouble(index))
                    Cursor.FIELD_TYPE_BLOB -> add(name, JsonObject().apply { addProperty("__base64_blob__", java.util.Base64.getEncoder().encodeToString(cursor.getBlob(index))) })
                    else -> addProperty(name, cursor.getString(index))
                }
            }
        }) }
    }
}

private val portableImportMutex = Mutex()

suspend fun PortableDataArchiveManager.prepareImport(input: InputStream, password: String): PreparedPortableImport = withContext(Dispatchers.IO) {
    val archive = resolveArchive(input, password)
    val staging = File(appContext.cacheDir, "portable-stage-${UUID.randomUUID()}").apply { check(mkdirs()) }
    try {
        val profile = ServiceContainer.prefs.activeDbName
        val categories = archive.preview.categories.mapTo(linkedSetOf()) { it.category }
        val entries = readSelectedDataEntries(archive.zipFile, categories)
        val files = mutableListOf<PortableStagedFile>()
        val seen = hashSetOf<String>()
        val manifest = JsonParser.parseString(String(readManifest(archive.zipFile), Charsets.UTF_8)).asJsonObject
        val inventory = manifest.getAsJsonArray("files")?.mapTo(hashSetOf()) { it.asJsonObject["path"].asString }.orEmpty()
        var expanded = 0L
        ZipFile(archive.zipFile).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = iterator.nextElement()
                val name = PortableArchiveValidator.entryName(entry.name.removeSuffix("/"))
                require(seen.add(name)) { "归档包含重复路径" }
                if (entry.isDirectory || !name.startsWith("files/")) continue
                require(manifest["version"].asInt < 3 || name in inventory) { "归档附件未登记校验清单" }
                val parts = name.split('/', limit = 4)
                require(parts.size == 4) { "归档附件路径无效" }
                val category = PortableDataCategory.fromId(parts[1]) ?: error("归档包含不支持的附件类别")
                require(category in categories) { "附件类别未登记" }
                val root = parts[2]
                val relative = parts[3]
                val target = if (category == PortableDataCategory.GLOBAL_MEMORY) {
                    require(root == "memory" && relative == "global-memory.md") { "全局记忆附件无效" }
                    globalMemoryFile()
                } else {
                    val base = attachmentRoots(category).firstOrNull { it.first == root }?.second ?: error("归档包含不支持的附件目录")
                    File(base, relative)
                }
                portableJournal(appContext).checked(target)
                val staged = File(staging, "${files.size}.bin")
                var count = 0L
                zip.getInputStream(entry).use { input -> staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n; expanded += n
                        require(count <= (if (category == PortableDataCategory.GLOBAL_MEMORY) 256L * 1024 else 64L * 1024 * 1024) && expanded <= 1024L * 1024 * 1024) { "归档附件超过大小限制" }
                        output.write(buffer, 0, n)
                    }
                } }
                if (category == PortableDataCategory.EXTENSIONS && root == "plugin_packages" && relative.substringAfterLast('/') == ".plugin-state.json")
                    staged.writeText("{\"enabled\":false,\"installedAt\":0}")
                files += PortableStagedFile(name, category, root, relative, staged, target)
            }
        }
        val catalog = PortableDataCategory.entries.map { category ->
            PortableImportContentSpec(category.id, category.tables.toSet(),
                attachmentRoots(category).mapTo(linkedSetOf()) { it.first } + if (category == PortableDataCategory.GLOBAL_MEMORY) setOf("memory") else emptySet(),
                when (category) {
                    PortableDataCategory.CONVERSATIONS -> mapOf("plot_story" to "data/conversations-story.json")
                    PortableDataCategory.CREDENTIALS -> mapOf("credentials_bundle" to "credentials/portable-credentials.json")
                    else -> emptyMap()
                }, category == PortableDataCategory.APP_SETTINGS)
        }
        val data = categories.mapNotNull { category -> entries["data/${category.id}.json"]?.let { category.id to it } }.toMap()
        val sidecars = setOf("data/conversations-story.json", "credentials/portable-credentials.json").filterTo(linkedSetOf(), entries::containsKey)
        val content = PortableImportContentInspector.inspect(catalog, data,
            files.map { PortableImportFileDescriptor(it.key, it.category.id, it.root) }, sidecars)
        val summaries = categories.mapNotNull { category ->
            val items = content.contents.filter { it.categoryId == category.id }
            if (items.isEmpty()) null else PortableCategorySummary(category, items.sumOf { it.rows }, items.sumOf { it.files },
                items.map { PortableCategoryDetail(it.detailKey, it.rows + it.files, it.files > 0) })
        }
        PreparedPortableImport(archive.preview.copy(categories = summaries), profile, archive, entries, files, staging, password)
    } catch (error: Throwable) { archive.cleanup(); staging.deleteRecursively(); throw error }
}

suspend fun PortableDataArchiveManager.previewImport(prepared: PreparedPortableImport,
    selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices = PortableImportChoices(),
    detailLimit: Int = 50): PortableImportPreview = withContext(Dispatchers.IO) {
    require(!prepared.closed && prepared.profile == ServiceContainer.prefs.activeDbName) { "资料库已切换，请重新选择文件" }
    require(selected.values.any { it.isNotEmpty() }) { "请至少选择一项资料" }
    val available = prepared.preview.categories.associate { it.category to it.details.map { d -> d.key }.toSet() }
    require(selected.all { (category, keys) -> keys.all { it in available[category].orEmpty() } }) { "选择已变化，请重新分析文件" }
    val db = activeDatabase()
    val job = currentCoroutineContext()
    val sql = AndroidPortableImportSql(db.openHelper.writableDatabase)
    val labelRows = linkedMapOf<String, Map<String, JsonObject>>()
    listOf("local_sessions", "local_characters", "companion_scopes").forEach { table ->
        val category = PortableDataCategory.entries.firstOrNull { table in it.tables }
        val raw = category?.let { prepared.entries["data/${it.id}.json"] }
        if (raw != null) {
            val names = linkedMapOf<String, JsonObject>()
            forEachPortableRow(raw, table) { row ->
                row["id"]?.takeUnless { it.isJsonNull }?.asString?.let { id -> names[id] = JsonObject().apply {
                    listOf("id", "name", "title", "character_id", "sessionId", "characterId").forEach { column -> row[column]?.let { add(column, it) } }
                } }
            }
            labelRows[table] = names
        }
    }
    val labels = PortableImportLabels(sql, labelRows)
    val rowPlan = db.withTransaction {
        PortableImportPlanner(sql, db.dbName, labels)
            .preview(portableRows(prepared, selected), choices, detailLimit) { job.ensureActive() }
    }
    val before = linkedMapOf<String, String?>()
    val after = linkedMapOf<String, String>()
    var added = 0; var same = 0; var different = 0; var updated = 0
    val differences = mutableListOf<PortableImportFileDifference>()
    val issues = rowPlan.issues.toMutableList()
    val dependencyOptions = mutableListOf<PortableImportDependencyOption>()
    prepared.preview.categories.forEach { summary -> summary.details.filter {
        it.key.removePrefix("table:") in rowPlan.requiredTables && it.key !in selected[summary.category].orEmpty()
    }.forEach { dependencyOptions += PortableImportDependencyOption(summary.category, it) } }
    if (ServiceContainer.generatingSessions.value.isNotEmpty()) issues += "还有会话正在生成，请先结束当前任务再导入"
    prepared.stagedFiles.filter { "root:${it.root}" in selected[it.category].orEmpty() }.forEach { file ->
        job.ensureActive()
        val target = portableJournal(appContext).checked(file.target)
        require(!target.exists() || target.isFile) { "附件目标不是普通文件" }
        val old = target.takeIf(File::isFile)?.let(PortableImportJournal::hash)
        before[file.key] = old
        after[file.key] = PortableImportJournal.hash(file.source)
        if (old == null) added++ else if (old == after[file.key]) same++ else {
            different++; if (file.key in choices.files) updated++
            if (differences.size < detailLimit) differences += PortableImportFileDifference(file.key, "${file.root}/${file.relative}",
                owner = labels.workspace(file.root, file.relative), currentBytes = target.length(), incomingBytes = file.source.length(),
                currentHash = old, incomingHash = after.getValue(file.key))
        }
    }
    val suppliedFiles = prepared.stagedFiles.filter { "root:${it.root}" in selected[it.category].orEmpty() }
        .map { it.target.canonicalPath }.toSet()
    rowPlan.localFiles.forEach { reference ->
        val file = File(if (reference.startsWith("file:")) android.net.Uri.parse(reference).path.orEmpty() else reference)
        if (runCatching { portableJournal(appContext).checked(file) }.isFailure || (!file.isFile && file.canonicalPath !in suppliedFiles)) {
            issues += "所选资料缺少本地附件，请补选附件类别或取消相关资料"
            prepared.stagedFiles.filter { it.target.canonicalPath == file.canonicalPath }.forEach { staged ->
                prepared.preview.categories.firstOrNull { it.category == staged.category }?.details?.firstOrNull { it.key == "root:${staged.root}" }
                    ?.let { dependencyOptions += PortableImportDependencyOption(staged.category, it) }
            }
        }
    }
    rowPlan.skillNames.forEach { name ->
        val folder = skillDirectoryName(name)
        val target = File(appContext.filesDir, "skills/$folder/SKILL.md")
        if (!target.isFile && target.canonicalPath !in suppliedFiles) {
            issues += "技能缺少SKILL.md文件，请补选技能文件，或取消该技能"
            prepared.preview.categories.firstOrNull { it.category == PortableDataCategory.EXTENSIONS }?.details?.firstOrNull { it.key == "root:skills" }
                ?.let { dependencyOptions += PortableImportDependencyOption(PortableDataCategory.EXTENSIONS, it) }
        }
    }
    val settingsBefore = captureAppSettingsRollback()
    val settingsIncoming = if ("app_settings" in selected[PortableDataCategory.APP_SETTINGS].orEmpty())
        prepared.entries["data/app_settings.json"]?.let { JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject } else null
    settingsIncoming?.let { incoming ->
        PortableImportSettingsPolicy.validate(settingsBefore, incoming)
        require(choices.settings.all(incoming::has)) { "设置选择已失效，请重新预览" }
        PortableImportSettingsPolicy.fileDirectories.forEach { (key, directory) ->
            if (key in choices.settings) PortableImportSettingsPolicy.fileName(incoming, key)?.let { name ->
                val file = portableJournal(appContext).checked(File(appContext.filesDir, "$directory/$name"))
                require(!file.exists() || file.isFile) { "设置附件不是普通文件" }
                if (!file.isFile && file.canonicalPath !in suppliedFiles) {
                    issues += "所选字体或背景设置缺少对应文件，请补选素材或取消此设置；不会将当前设置置空"
                    prepared.stagedFiles.filter { it.target.canonicalPath == file.canonicalPath }.forEach { staged ->
                        prepared.preview.categories.firstOrNull { it.category == staged.category }?.details?.firstOrNull { it.key == "root:${staged.root}" }
                            ?.let { dependencyOptions += PortableImportDependencyOption(staged.category, it) }
                    }
                }
            }
        }
    }
    val settings = settingsIncoming?.entrySet()?.filter { (name, value) ->
        require(settingsBefore.has(name)) { "应用设置包含当前版本不支持的字段" }
        value != settingsBefore[name]
    }?.map { PortableImportFieldDifference(it.key, safeSettingValue(it.key, settingsBefore[it.key]), safeSettingValue(it.key, it.value)) }.orEmpty()
    val story = "plot_story" in selected[PortableDataCategory.CONVERSATIONS].orEmpty()
    var storyDifferent = false
    var storyIds = emptySet<String>()
    if (story) {
        val incoming = parsePlotStory(prepared.entries.getValue("data/conversations-story.json"))
        val ids = parseImportedSessionIds(prepared.entries["data/conversations.json"]) + PortableArchiveValidator.storySessions(incoming.graphJson, incoming.plotChoices.keys)
        storyIds = ids
        // The existing parser checks graph references/collisions; only use local, disposable managers in preview.
        com.nekobot.app.data.local.ai.PlotGraphManager().replaceConversationsFromJson(ids, incoming.graphJson)
        val snapshot = LocalPlotStoryStore.snapshot(appContext, prepared.profile)
        fun graphFor(raw: String?): JsonElement {
            val manager = com.nekobot.app.data.local.ai.PlotGraphManager()
            raw?.let(manager::fromJson)
            return JsonParser.parseString(manager.toJsonForConversations(ids))
        }
        storyDifferent = graphFor(incoming.graphJson) != graphFor(snapshot.graphJson) ||
            incoming.plotChoices != snapshot.plotChoices.filterKeys { it in ids }
        val suppliedSessions = hashSetOf<String>()
        if ("table:local_sessions" in selected[PortableDataCategory.CONVERSATIONS].orEmpty())
            prepared.entries["data/conversations.json"]?.let { forEachPortableRow(it, "local_sessions") { row -> suppliedSessions += row["id"].asString } }
        val existing = db.sessionDao().listAll().map { it.id }.toSet()
        if ((ids - suppliedSessions - existing).isNotEmpty()) issues += "故事地图缺少关联会话，请补选会话资料或取消故事地图"
    }
    if ("credentials_bundle" in selected[PortableDataCategory.CREDENTIALS].orEmpty() && !choices.restoreCredentials)
        issues += "加密凭据需单独确认；也可以取消凭据清单，稍后自行登录或填写"
    if (choices.restoreCredentials && (!prepared.preview.encrypted || prepared.password.trim().length < 8))
        issues += "恢复凭据必须使用已加密归档及至少8位密码"
    val credentialTargets = if (choices.restoreCredentials && prepared.preview.encrypted && prepared.password.trim().length >= 8 &&
        "credentials_bundle" in selected[PortableDataCategory.CREDENTIALS].orEmpty()) {
        val raw = LocalWebDavArchiveCodec.decrypt(prepared.entries.getValue("credentials/portable-credentials.json"), prepared.password)
        PortableArchiveValidator.credentialTargets(raw)
    } else emptyMap()
    credentialTargets.forEach { (table, ids) ->
        val sql = AndroidPortableImportSql(db.openHelper.readableDatabase)
        val missing = ids.filter { sql.query("SELECT id FROM `${table}` WHERE id=?", listOf(it)).isEmpty() }.toMutableSet()
        PortableDataCategory.entries.firstOrNull { table in it.tables }?.let { category ->
            if ("table:$table" in selected[category].orEmpty()) prepared.entries["data/${category.id}.json"]?.let { raw ->
                forEachPortableRow(raw, table) { row -> row["id"]?.takeUnless(JsonElement::isJsonNull)?.asString?.let(missing::remove) }
            }
            if (missing.isNotEmpty()) {
                issues += "加密凭据缺少对应模型或账号配置，请补选相关配置或取消凭据；不会跳过后假报完成"
                prepared.preview.categories.firstOrNull { it.category == category }?.details?.firstOrNull { it.key == "table:$table" }
                    ?.let { dependencyOptions += PortableImportDependencyOption(category, it) }
            }
        }
    }
    val credentialFingerprint = if (choices.restoreCredentials) portableCredentialFingerprint(db.openHelper.readableDatabase) else ""
    val fingerprint = portableFingerprint(prepared, rowPlan, before, settingsBefore, choices, selected,
        if (story) Gson().toJson(LocalPlotStoryStore.snapshot(appContext, prepared.profile)) else "",
        (if (PortableDataCategory.EXTENSIONS in selected) snapshotPluginGrants(appContext).toString() else "") + credentialFingerprint + after.toSortedMap())
    PortableImportPreview(rowPlan, PortableImportCounts(added, same, different, updated), differences, settings,
        storyDifferent, issues.distinct().take(50), dependencyOptions.distinctBy { it.category.id + it.detail.key }, fingerprint, prepared.id,
        selected.mapValues { it.value.toSet() }, choices.copy(fields = choices.fields.mapValues { it.value.toSet() }), before, after,
        settingsBefore, settingsIncoming, storyDifferent && "plot_story" in choices.files, storyIds, credentialFingerprint, credentialTargets)
}

suspend fun PortableDataArchiveManager.commitImport(prepared: PreparedPortableImport, preview: PortableImportPreview,
    onProgress: (String) -> Unit = {}): PortableImportResult {
    val caller = currentCoroutineContext()
    return withContext(Dispatchers.IO) { portableImportMutex.withLock {
        require(preview.preparedId == prepared.id && !prepared.closed) { "导入预览已失效" }
        require(preview.issues.isEmpty()) { preview.issues.first() }
        prepared.result = null
        val db = activeDatabase()
        val journal = portableJournal(appContext)
        var record: PortableJournalRecord? = null
        var committed = false
        var repositoryClosed = false
        PortableImportGate.profile = prepared.profile
        val checkpoint = { caller.ensureActive(); require(ServiceContainer.prefs.activeDbName == prepared.profile) { "资料库已切换，导入已停止" }; Unit }
        try {
            checkpoint()
            onProgress("正在核对预览")
            val actual = previewImport(prepared, preview.selected, preview.choices, 0)
            require(actual.fingerprint == preview.fingerprint && actual.issues.isEmpty()) { "资料在预览后已变化，请重新预览；尚未写入" }
            val writes = prepared.stagedFiles.filter { it.key in preview.fileBefore &&
                (preview.fileBefore[it.key] == null || it.key in preview.choices.files) &&
                preview.fileBefore[it.key] != PortableImportJournal.hash(it.source) }
                .map { PortableFileWrite(it.key, it.target, it.source, preview.fileBefore[it.key], preview.fileAfter[it.key]) }.toMutableList()
            // Updating plugin code always disables that package; archive state/grants never authorize execution.
            val plugins = prepared.stagedFiles.filter { file -> writes.any { it.key == file.key } && file.root == "plugin_packages" }
                .map { it.relative.substringBefore('/') }.filter { it.isNotBlank() && !it.startsWith('.') }.toSet()
            plugins.forEach { id ->
                val target = File(appContext.filesDir, "plugins/$id/.plugin-state.json")
                if (writes.none { it.target == target }) {
                    val source = File(prepared.stagedFiles.first { it.root == "plugin_packages" && it.relative.startsWith("$id/") }.source.parentFile, "plugin-$id.state")
                    source.writeText("{\"enabled\":false,\"installedAt\":0}")
                    writes += PortableFileWrite("plugin-state:$id", target, source, target.takeIf(File::isFile)?.let(PortableImportJournal::hash))
                }
            }
            val state = JsonObject().apply {
                if (preview.settingDifferences.any { it.column in preview.choices.settings }) add("settings", preview.settingsBefore)
                if (preview.useStory) add("story", Gson().toJsonTree(LocalPlotStoryStore.snapshot(appContext, prepared.profile)))
                if (plugins.isNotEmpty()) add("grants", snapshotPluginGrants(appContext))
            }
            if (preview.rows.counts.added + preview.rows.counts.updated == 0 && writes.isEmpty() && state.size() == 0 &&
                !(preview.choices.restoreCredentials && "credentials_bundle" in preview.selected[PortableDataCategory.CREDENTIALS].orEmpty())) {
                val core = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM companion_memories WHERE alwaysProvide=1 AND current=1 AND valid=1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
                return@withLock PortableImportResult(0, 0, preview.selected.count { it.value.isNotEmpty() }, sameItems = preview.rows.counts.same + preview.fileCounts.same,
                    keptItems = preview.rows.counts.kept + preview.fileCounts.kept, coreMemories = core).also { prepared.result = it }
            }
            val tx = journal.prepare(db.dbName, prepared.profile, writes, state)
            record = tx
            prepared.recoveryPending = true
            repositoryClosed = true
            ServiceContainer.localRepository.close()
            var changed = 0
            val restoredArchives = linkedSetOf<String>()
            val newScopes = linkedSetOf<String>()
            val changedScopes = linkedSetOf<String>()
            val changedBodies = mutableListOf<Pair<String, String>>()
            onProgress("正在写入资料，可取消并撤销")
            db.withTransaction {
                val planner = PortableImportPlanner(AndroidPortableImportSql(db.openHelper.writableDatabase), db.dbName)
                val latestRows = planner.preview(portableRows(prepared, preview.selected), preview.choices, 0, checkpoint)
                require(latestRows.fingerprint == preview.rows.fingerprint && latestRows.issues.isEmpty()) { "资料在预览后已变化，请重新预览" }
                if (preview.choices.restoreCredentials) require(portableCredentialFingerprint(db.openHelper.readableDatabase) == preview.credentialFingerprint) { "凭据在预览后已变化，请重新预览" }
                changed = planner.apply(portableRows(prepared, preview.selected), preview.choices, checkpoint, latestRows.newKeys, latestRows.sourceMemoryStates) { table, row, isNew, fields ->
                    if (table == "local_experience_archives") restoredArchives += row["id"].asString
                    if (isNew && table == "companion_scopes") newScopes += row["id"].asString
                    if (table == "companion_memories") {
                        changedScopes += row["scopeId"].asString
                        if (!isNew && "content" in fields) changedBodies += row["scopeId"].asString to row["id"].asString
                    }
                    if (table == "companion_sources") {
                        db.openHelper.writableDatabase.query("SELECT scopeId FROM companion_memories WHERE id=?", arrayOf(row["memoryId"].asString)).use {
                            if (it.moveToFirst()) changedScopes += it.getString(0)
                        }
                    }
                }
                if (newScopes.isNotEmpty()) rebaseRestoredCompanionRows(db, emptySet(), newScopes)
                (changedScopes - newScopes).forEach { scope ->
                    db.openHelper.writableDatabase.execSQL("UPDATE companion_scopes SET revision=revision+1 WHERE id=?", arrayOf(scope))
                    db.openHelper.writableDatabase.execSQL("DELETE FROM companion_context_materials WHERE scopeId=?", arrayOf(scope))
                }
                changedBodies.forEach { (scope, id) -> db.openHelper.writableDatabase.execSQL("DELETE FROM companion_vectors WHERE scopeId=? AND itemId=? AND itemType IN ('memory','fact')", arrayOf(scope, id)) }
                restoredArchives.forEach { archive -> invalidatePortableExperiences(db.openHelper.writableDatabase, archive) }
                checkpoint()
                journal.install(tx, checkpoint)
                onProgress("正在提交设置")
                if (preview.settingsIncoming != null && state.has("settings")) {
                    PortableImportSettingsPolicy.apply(preview.settingsBefore, preview.settingsIncoming, preview.choices.settings,
                        { restoreAppSettings(it.toString().toByteArray()) },
                        { ServiceContainer.prefs.customFontPath = it }, { ServiceContainer.prefs.customChatBackgroundPath = it })
                    check(appContext.getSharedPreferences("nekobot_prefs", Context.MODE_PRIVATE).edit().commit()) { "无法保存应用设置" }
                }
                if (preview.useStory) {
                    val story = parsePlotStory(prepared.entries.getValue("data/conversations-story.json"))
                    LocalPlotStoryStore.mergeImportedSessions(appContext, prepared.profile,
                        preview.storySessionIds, db.sessionDao().listAll().mapTo(linkedSetOf()) { it.id }, story)
                }
                if (preview.choices.restoreCredentials && "credentials_bundle" in preview.selected[PortableDataCategory.CREDENTIALS].orEmpty()) {
                    require(prepared.preview.encrypted && prepared.password.trim().length >= 8)
                    val raw = LocalWebDavArchiveCodec.decrypt(prepared.entries.getValue("credentials/portable-credentials.json"), prepared.password)
                    LocalDatabaseCredentialBundle.restore(db, raw)
                    // Credential-only restoration must not reactivate an existing executable configuration.
                    val sql = AndroidPortableImportSql(db.openHelper.writableDatabase)
                    preview.credentialTargets.forEach { (table, ids) ->
                        val flags = sql.query("PRAGMA table_info(`${table}`)").map { it["name"].asString }.filter { it in setOf("enabled", "active") }
                        if (flags.isNotEmpty()) ids.forEach { id -> sql.execute("UPDATE `${table}` SET ${flags.joinToString { "`$it`=0" }} WHERE id=?", listOf(id)) }
                    }
                    changed += preview.credentialTargets.values.sumOf { it.size }
                }
                plugins.forEach { ServiceContainer.pluginGrants.revoke(it) }
                check(appContext.getSharedPreferences("nekobot_plugin_grants", Context.MODE_PRIVATE).edit().commit())
                // Modified Skill packages cannot inherit an existing enabled flag.
                val skillFolders = prepared.stagedFiles.filter { file -> writes.any { it.key == file.key } && file.root == "skills" }.map { it.relative.substringBefore('/') }.toSet()
                if (skillFolders.isNotEmpty()) db.skillDao().listAll().filter { skillDirectoryName(it.name) in skillFolders }.forEach { db.skillDao().setEnabled(it.id, false) }
                checkpoint()
                db.openHelper.writableDatabase.execSQL("INSERT INTO portable_import_commits(id,createdAt) VALUES (?,?)", arrayOf(tx.id, System.currentTimeMillis()))
            }
            committed = true
            val core = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM companion_memories WHERE alwaysProvide=1 AND current=1 AND valid=1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val result = PortableImportResult(changed, writes.count { !it.key.startsWith("plugin-state:") }, preview.selected.count { it.value.isNotEmpty() },
                preview.rows.counts.added, preview.rows.counts.updated, preview.rows.counts.same + preview.fileCounts.same,
                preview.rows.counts.kept + preview.fileCounts.kept, preview.settingDifferences.count { it.column in preview.choices.settings }, core,
                preview.rows.newKeys.count { it.startsWith("local_characters:") }, preview.rows.sameNameCopies,
                preview.credentialTargets.values.sumOf { it.size }, preview.useStory)
            prepared.result = result
            prepared.recoveryPending = false
            withContext(NonCancellable) { runCatching { journal.finish(tx) } }
            onProgress("导入完成")
            result
        } catch (error: Throwable) {
            if (!committed) withContext(NonCancellable) {
                record?.let { recovery ->
                    val durableCommit = db.openHelper.readableDatabase.query("SELECT 1 FROM portable_import_commits WHERE id=?", arrayOf(recovery.id)).use { it.moveToFirst() }
                    if (!durableCommit) journal.rollback(recovery) { restorePortableState(appContext, it) }
                    else {
                        val core = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM companion_memories WHERE alwaysProvide=1 AND current=1 AND valid=1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
                        prepared.result = PortableImportResult(preview.rows.counts.added + preview.rows.counts.updated,
                            preview.fileCounts.added + preview.fileCounts.updated, preview.selected.count { it.value.isNotEmpty() },
                            preview.rows.counts.added, preview.rows.counts.updated, preview.rows.counts.same + preview.fileCounts.same,
                            preview.rows.counts.kept + preview.fileCounts.kept,
                            settingsUpdated = preview.settingDifferences.count { it.column in preview.choices.settings }, coreMemories = core,
                            newCharacters = preview.rows.newKeys.count { it.startsWith("local_characters:") }, sameNameCopies = preview.rows.sameNameCopies,
                            credentialsRestored = preview.credentialTargets.values.sumOf { it.size }, storyUpdated = preview.useStory)
                        runCatching { journal.finish(recovery) }
                    }
                    prepared.recoveryPending = false
                }
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    if (repositoryClosed && ServiceContainer.prefs.activeDbName == prepared.profile) {
                        ServiceContainer.switchLocalDb(prepared.profile, portableImportOwner = true)
                        ServiceContainer.pluginManager.reload()
                    }
                } finally { PortableImportGate.profile = null }
            }
        }
    } }
}

private fun PortableDataArchiveManager.portableRows(prepared: PreparedPortableImport,
    selected: Map<PortableDataCategory, Set<String>>): PortableRowVisitor = { consume ->
    PortableDataCategory.entries.forEach { category ->
        val raw = prepared.entries["data/${category.id}.json"]
        if (raw != null) category.tables.filter { "table:$it" in selected[category].orEmpty() }.forEach { table ->
            forEachPortableRow(raw, table) { row ->
                val rewritten = row.deepCopy()
                row.entrySet().forEach { (column, value) -> rewritten.add(column, rewriteMediaReference(table, column, value)) }
                consume(PortableIncomingRow(table, rewritten))
            }
        }
    }
}

internal fun invalidatePortableExperiences(db: SupportSQLiteDatabase, archiveId: String) = db.execSQL(portableExperienceInvalidationSql, arrayOf(archiveId))

private fun portableCredentialFingerprint(db: SupportSQLiteDatabase): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sql = AndroidPortableImportSql(db)
    listOf("local_ai_models", "local_mcp_servers", "local_api_keys", "local_oauth_accounts").forEach { table ->
        sql.query("SELECT * FROM `$table` ORDER BY id").forEach { digest.update(it.toString().toByteArray()) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun portableFingerprint(prepared: PreparedPortableImport, rows: PortableImportRowPlan,
    files: Map<String, String?>, settings: JsonObject, choices: PortableImportChoices,
    selected: Map<PortableDataCategory, Set<String>>, story: String, grants: String): String {
    val gson = Gson()
    val state = JsonObject().apply {
        addProperty("profile", prepared.profile); addProperty("source", PortableImportJournal.hash(prepared.archive.zipFile))
        addProperty("rows", rows.fingerprint); add("files", gson.toJsonTree(files.toSortedMap()))
        add("settings", settings); add("choices", gson.toJsonTree(choices))
        add("selected", gson.toJsonTree(selected.mapKeys { it.key.id }.toSortedMap().mapValues { it.value.sorted() }))
        addProperty("story", story); addProperty("grants", grants)
    }
    return MessageDigest.getInstance("SHA-256").digest(state.toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun PortableDataArchiveManager.captureAppSettingsRollback(): JsonObject =
    JsonParser.parseString(String(captureAppSettings(), Charsets.UTF_8)).asJsonObject.apply {
        addProperty("_font_path", ServiceContainer.prefs.customFontPath)
        addProperty("_background_path", ServiceContainer.prefs.customChatBackgroundPath)
    }

private fun safeSettingValue(key: String, value: JsonElement?): String =
    if (key !in PortableImportSettingsPolicy.fileDirectories && (key.contains("file") || key.contains("path"))) "本机文件"
    else value?.takeUnless(JsonElement::isJsonNull)?.asString?.take(120) ?: "未设置"

private fun portableJournal(context: Context) = PortableImportJournal(File(context.filesDir, "portable_import_transactions"), listOf(context.filesDir, context.cacheDir))

private fun snapshotPluginGrants(context: Context): JsonObject = Gson().toJsonTree(context.getSharedPreferences("nekobot_plugin_grants", Context.MODE_PRIVATE).all).asJsonObject

private fun restorePortableState(context: Context, record: PortableJournalRecord) {
    val manager = PortableDataArchiveManager(context)
    record.state["settings"]?.asJsonObject?.let { settings ->
        manager.restoreAppSettings(settings.toString().toByteArray())
        ServiceContainer.prefs.customFontPath = settings["_font_path"]?.takeUnless(JsonElement::isJsonNull)?.asString
        ServiceContainer.prefs.customChatBackgroundPath = settings["_background_path"]?.takeUnless(JsonElement::isJsonNull)?.asString
        check(context.getSharedPreferences("nekobot_prefs", Context.MODE_PRIVATE).edit().commit())
    }
    record.state["story"]?.let { LocalPlotStoryStore.restore(context, record.profile, Gson().fromJson(it, LocalPlotStoryProfileSnapshot::class.java)) }
    record.state["grants"]?.asJsonObject?.let { grants ->
        val editor = context.getSharedPreferences("nekobot_plugin_grants", Context.MODE_PRIVATE).edit().clear()
        grants.entrySet().forEach { (key, value) -> when {
            value.isJsonArray -> editor.putStringSet(key, value.asJsonArray.map { it.asString }.toSet())
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> editor.putBoolean(key, value.asBoolean)
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> editor.putLong(key, value.asLong)
            !value.isJsonNull -> editor.putString(key, value.asString)
        } }
        check(editor.commit())
    }
}

/** Called after PrefsManager exists, before any repository/database starts using files. */
internal fun recoverPendingPortableImport(context: Context) {
    portableJournal(context).recover({ record ->
        val database = context.getDatabasePath(record.database)
        if (!database.isFile) false else android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='portable_import_commits'", null).use { it.moveToFirst() }
            exists && db.rawQuery("SELECT 1 FROM portable_import_commits WHERE id=?", arrayOf(record.id)).use { it.moveToFirst() }
        }
    }) { restorePortableState(context, it) }
}
