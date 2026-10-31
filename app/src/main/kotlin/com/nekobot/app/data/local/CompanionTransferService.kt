package com.nekobot.app.data.local

import android.content.Context
import android.os.StatFs
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal fun companionTransferName(characterName: String): String = characterName

internal data class CompanionTransferPreview(
    val jobId: String, val name: String, val sourceName: String, val messages: Long, val variants: Long,
    val memories: Int, val skippedMemories: Long, val reviewNotes: List<String>, val attachments: Int,
    val missingAttachments: List<String>, val remoteAttachments: Int, val requiredBytes: Long,
    val completedSessionId: String? = null
)
internal data class CompanionTransferProgress(val phase: String, val processed: Long = 0, val total: Long = 0)
internal data class CompanionTransferRecordInfo(val total: Long?, val processed: Long?)
internal data class CompanionTransferReport(val snapshot: TransferSnapshot, val files: TransferFiles,
    val memory: TransferMemorySelection, val sourceName: String, val conversion: CompanionConversionRecord? = null)

/** 不调用模型，不改变原角色。文件先验证，数据库和成功回执在同一事务内提交。 */
internal class CompanionTransferService(
    private val context: Context,
    private val db: NekobotDatabase,
    private val guard: suspend (List<String>) -> Unit = {},
    private val checkpoint: (String) -> Unit = {}
) {
    private val gson = Gson()
    private val dao = db.companionTransferDao()
    private val files = CompanionTransferFiles(context)
    private val base get() = File(context.filesDir, "companion_transfers/${transferHash(db.dbName).take(24)}").canonicalFile

    suspend fun preview(sourceId: String, forConversion: Boolean = false): CompanionTransferPreview = withContext(Dispatchers.IO) { lock.withLock {
        guard(listOf(sourceId))
        val jobId = UUID.randomUUID().toString()
        val directory = File(base, jobId)
        val snapshot = db.withTransaction { CompanionTransferSnapshot(db).write(sourceId, directory) }
        guard(snapshot.sessionIds)
        val media = files.inspect(directory, snapshot)
        val fingerprint = transferHash(snapshot.fingerprint + gson.toJson(media) + if (forConversion) ":ai:${CompanionConversionRules.version}" else "")
        val existing = dao.find(db.dbName, sourceId, fingerprint)
        if (existing != null) {
            removeOwnedDirectory(directory)
            return@withLock toPreview(existing)
        }
        val memory = CompanionTransferMemory(db).selection(directory, snapshot)
        val name = companionTransferName(snapshot.characterName)
        val report = CompanionTransferReport(snapshot, media, memory, db.sessionDao().getById(sourceId)?.name.orEmpty())
        val now = Instant.now().toString()
        val job = CompanionTransferJobEntity(jobId, db.dbName, sourceId, fingerprint,
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), name, "preview", directory.path, gson.toJson(report), now, now)
        dao.save(job)
        toPreview(job)
    } }

    suspend fun execute(jobId: String, name: String, conversion: CompanionConversionRequest? = null,
        progress: (CompanionTransferProgress) -> Unit = {}): String = withContext(Dispatchers.IO) { lock.withLock {
        var job = requireNotNull(dao.get(jobId)) { "迁入记录不存在，请重新预览" }
        require(job.profile == db.dbName) { "资料库已切换，请重新预览" }
        require(!job.phase.startsWith("deleting")) { "这条记录正在清理，请返回列表完成删除后再转换" }
        if (job.phase == "complete" || job.phase == "backup_deleted") {
            require(db.sessionDao().getById(job.targetSessionId) != null) { "这次迁入的会话已被删除，请从迁入记录核对" }
            return@withLock job.targetSessionId
        }
        require(name.isNotBlank()) { "请填写新角色名称" }
        var report = gson.fromJson(job.reportJson, CompanionTransferReport::class.java)
        require(report.conversion == null || conversion != null) { "这是未完成的模型转换，请选择转换模型后继续" }
        if (conversion != null) {
            require(report.conversion?.rules.let { it == null || it == CompanionConversionRules.version }) { "转换规则已更新，请重新预览后转换" }
            report = report.copy(conversion = CompanionConversionRecord(CompanionConversionRules.version,
                (report.conversion?.models.orEmpty() + conversion.modelName).distinct()))
            job = job.copy(reportJson = gson.toJson(report))
        }
        val directory = ownedDirectory(job.directory)
        val targetDirectory = File(context.filesDir, "workspace/${job.targetSessionId}").canonicalFile
        require(targetDirectory.parentFile == File(context.filesDir, "workspace").canonicalFile)
        guard(report.snapshot.sessionIds)
        report.snapshot.verify(directory)
        require(report.files.missing.isEmpty()) { "有附件无法读取，请修复后重新预览" }
        val required = toPreview(job).requiredBytes * if (conversion == null) 1 else 3
        require(StatFs(context.filesDir.path).availableBytes > required + 16L * 1024 * 1024) { "存储空间不足，请腾出空间后重试" }
        var stage: CompanionConversionStage? = null
        try {
            job = job.copy(targetName = name.trim(), phase = "backup", updatedAt = Instant.now().toString())
            dao.save(job); progress(CompanionTransferProgress("正在校验备份")); checkpoint("backup")
            files.backup(report.files, directory, targetDirectory)
            report.snapshot.verify(directory)
            checkpoint("files")
            if (conversion != null) {
                // 调用模型前先检查原资料，完成后还会在最终事务中再次检查。
                val current = db.withTransaction { CompanionTransferSnapshot(db).write(job.sourceSessionId, File(directory, "recheck")) }
                require(current.fingerprint == report.snapshot.fingerprint) { "原会话或记忆已变化，请返回并重新预览" }
                job = job.copy(phase = "converting"); dao.save(job)
                stage = CompanionConversionStage(context, directory, db.dbName)
                stage.prepare(job) { staging ->
                    CompanionTransferService(context, staging).copyRows(job, report, directory, targetDirectory, progress, sourceDb = db)
                }
                stage.run(job, conversion.copy(generate = { system, material ->
                    guard(report.snapshot.sessionIds)
                    conversion.generate(system, material).also { guard(report.snapshot.sessionIds) }
                }, generateStream = conversion.generateStream?.let { stream -> { system, material, callbacks ->
                    guard(report.snapshot.sessionIds)
                    stream(system, material, callbacks).also { guard(report.snapshot.sessionIds) }
                } }), progress)
                report = report.copy(conversion = report.conversion!!.copy(memories = stage.count(job)))
                job = job.copy(reportJson = gson.toJson(report))
                checkpoint("converted")
            }
            job = job.copy(phase = "copying"); dao.save(job)
            db.withTransaction {
                coroutineContext.ensureActive()
                guard(report.snapshot.sessionIds)
                // 在写事务中重新扫描来源；与预览不同则完全不提交。
                val recheck = File(directory, "recheck")
                val current = CompanionTransferSnapshot(db).write(job.sourceSessionId, recheck)
                require(current.fingerprint == report.snapshot.fingerprint) { "原会话或记忆已变化，请返回并重新预览" }
                checkpoint("verified")
                copyRows(job, report, directory, targetDirectory, progress)
                stage?.importInto(db, job)
                checkpoint("before_commit")
                dao.save(job.copy(phase = "complete", updatedAt = Instant.now().toString()))
            }
            checkpoint("after_commit")
            progress(CompanionTransferProgress("迁入完成", report.snapshot.counts.getValue("local_messages"), report.snapshot.counts.getValue("local_messages")))
            job.targetSessionId
        } catch (error: Exception) {
            // 成功事务回执已存在时即使界面中断也保留成功，重试返回同一目标。
            withContext(NonCancellable) {
                if (db.isOpen && dao.get(job.id)?.phase != "complete") dao.save(job.copy(phase = if (error is CancellationException) "cancelled" else "failed"))
            }
            throw error
        } finally { stage?.close() }
    } }

    suspend fun records(): List<CompanionTransferJobEntity> = withContext(Dispatchers.IO) { dao.list(db.dbName) }

    /** 读取实际批次回执，兼容尚未加入错误报告功能时创建的旧任务；不启动模型或迁移临时库。 */
    suspend fun recordInfo(jobs: List<CompanionTransferJobEntity>): Map<String, CompanionTransferRecordInfo> = withContext(Dispatchers.IO) {
        guard(emptyList())
        if (!lock.tryLock()) return@withContext emptyMap()
        try {
            jobs.filter { it.profile == db.dbName }.associate { job ->
                val total = runCatching { gson.fromJson(job.reportJson, CompanionTransferReport::class.java)
                    .snapshot.counts.getValue("local_messages") }.getOrNull()
                val processed = runCatching {
                    when {
                        job.phase.startsWith("deleting") -> null
                        job.phase in setOf("complete", "backup_deleted") -> null
                        job.phase == "preview" -> 0L
                        else -> {
                            val stage = File(ownedDirectory(job.directory), "conversion.db")
                            if (!stage.isFile) null else android.database.sqlite.SQLiteDatabase.openDatabase(
                                stage.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                                    android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { staging ->
                                staging.rawQuery("SELECT COUNT(*) FROM companion_receipts r JOIN companion_scopes s ON s.id=r.scopeId WHERE s.sessionId=? AND s.characterId=?",
                                    arrayOf(job.targetSessionId, job.targetCharacterId)).use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                                }
                            }
                        }
                    }
                }.getOrNull()
                job.id to CompanionTransferRecordInfo(total, processed)
            }
        } finally { lock.unlock() }
    }

    /** 与本次备份一起保留最近五次脱敏错误；不混入含有旧记忆正文的迁入回执。 */
    suspend fun saveErrorReport(jobId: String, report: String) = withContext(Dispatchers.IO) { lock.withLock {
        val file = errorReportFile(jobId) ?: return@withLock false
        val reports = readErrorReports(file)
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson((listOf(report.take(10_000)) + reports).take(5)).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    } }

    suspend fun errorReports(jobId: String): String? = withContext(Dispatchers.IO) {
        guard(emptyList())
        errorReportFile(jobId)?.let { readErrorReports(it) }?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n──────── 较早的错误报告 ────────\n\n")
    }

    private suspend fun errorReportFile(jobId: String): android.util.AtomicFile? {
        val job = dao.get(jobId) ?: return null
        require(job.profile == db.dbName) { "资料库已切换，请重新打开记录" }
        if (job.phase == "backup_deleted" || job.phase.startsWith("deleting")) return null
        val directory = ownedDirectory(job.directory)
        if (!directory.isDirectory) return null
        return android.util.AtomicFile(File(directory, "conversion-errors.json"))
    }

    private fun readErrorReports(file: android.util.AtomicFile): List<String> = runCatching {
        file.openRead().use { input ->
            // 报告有长度及数量上限，损坏文件也不无限读取。
            val bytes = ByteArray(256 * 1024 + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= 256 * 1024)
            gson.fromJson(String(bytes, 0, count, Charsets.UTF_8), Array<String>::class.java).toList().take(5)
        }
    }.getOrDefault(emptyList())

    suspend fun savedPreview(jobId: String): CompanionTransferPreview = withContext(Dispatchers.IO) {
        guard(emptyList())
        toPreview(requireNotNull(dao.get(jobId)) { "迁入记录不存在" })
    }

    suspend fun receipt(jobId: String): String = withContext(Dispatchers.IO) {
        guard(emptyList())
        val job = requireNotNull(dao.get(jobId)) { "迁入记录不存在" }
        val report = gson.fromJson(job.reportJson, CompanionTransferReport::class.java)
        buildString {
            appendLine("${job.targetName}\n原会话：${report.sourceName}")
            appendLine("${if(job.phase in setOf("complete", "backup_deleted")) "已完成" else "尚未完成，可继续处理"} · ${job.createdAt}")
            appendLine("原话 ${report.snapshot.counts["local_messages"]} 条，候选 ${report.snapshot.counts["local_message_variants"]} 条")
            appendLine("本地附件 ${report.files.assets.size} 个。")
            if(job.phase in setOf("complete", "backup_deleted")) {
                appendLine("原话与附件备份已按 SHA-256 校验；记忆正文已独立保存。")
            } else appendLine("本次操作尚未完成，不能视为副本已交付。")
            if (report.conversion == null) appendLine("这是一份旧版复制记录，没有使用模型重新整理历史。")
            else {
                appendLine("转换模型：${report.conversion.models.joinToString("、")}")
                appendLine("转换规则：${report.conversion.rules}")
                if (job.phase in setOf("complete", "backup_deleted")) appendLine("整理后有效记忆：${report.conversion.memories} 条。已核对原话编号与逐字引用；语义内容可在角色记忆中查看。")
                else appendLine("已完成批次保存在本次转换备份中，点击转换可继续。")
            }
            appendLine("归档来源分组：${report.snapshot.sessionIds.size} 个；原话合并到新主会话并保留原始时间与顺序。")
            appendLine("本机记录编号：${job.id}")
            if(job.phase == "backup_deleted") appendLine("本次备份已由用户删除，新会话内容不受影响。")
            else {
                val directory = ownedDirectory(job.directory)
                appendLine("\n需要核对的旧摘要（未作为事实注入）：")
                for (row in transferRows(directory, "local_character_memories")) {
                    if (report.conversion != null || CompanionTransferMemory(db).exclusion(row, report.snapshot) != null) appendLine("${row.text("title").orEmpty()}\n${row.text("content").orEmpty()}\n")
                }
                for (row in transferRows(directory, "local_experience_archives")) appendLine("${row.text("source_started_at")} — ${row.text("source_ended_at")}\n${row.text("summary").orEmpty()}\n")
            }
        }
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) { lock.withLock {
        val job = requireNotNull(dao.get(jobId))
        require(job.phase !in setOf("complete", "backup_deleted")) { "已完成的迁入不能取消，可在会话列表管理副本" }
        dao.save(job.copy(phase = "cancelled"))
    } }

    suspend fun deleteBackup(jobId: String) = withContext(Dispatchers.IO) { lock.withLock {
        val job = requireNotNull(dao.get(jobId))
        require(job.phase in setOf("complete", "backup_deleted")) { "未完成的迁入需要备份恢复，不能删除备份" }
        removeOwnedDirectory(ownedDirectory(job.directory))
        dao.save(job.copy(phase = "backup_deleted"))
    } }

    /** 只清理指定作业。先留删除标记，清理成功再删记录；中途退出或清理失败可重试。 */
    suspend fun deleteRecord(jobId: String) = withContext(Dispatchers.IO) {
        check(lock.tryLock()) { "有迁入或转换正在处理，请先停止后再删除记录" }
        try {
            guard(emptyList())
            val job = dao.get(jobId) ?: return@withContext
            require(job.profile == db.dbName) { "资料库已切换，请重新打开记录" }
            val directory = ownedDirectory(job.directory)
            require(directory.name == job.id) { "本次备份目录归属无法确认，未删除记录" }
            val completed = job.phase in setOf("complete", "backup_deleted", "deleting_complete")
            // 目标是本作业生成的 UUID；原会话和已有正式副本的目录绝不清理。
            val workspace = File(context.filesDir, "workspace").canonicalFile
            require(runCatching { UUID.fromString(job.targetSessionId) }.isSuccess && job.targetSessionId != job.sourceSessionId) {
                "本次临时附件目录归属无法确认，未删除记录"
            }
            val target = File(workspace, job.targetSessionId).canonicalFile
            require(target.parentFile == workspace && target.name == job.targetSessionId) { "临时附件目录校验失败，未删除记录" }
            val hasTarget = db.sessionDao().getById(job.targetSessionId) != null || db.characterDao().getById(job.targetCharacterId) != null
            withContext(NonCancellable) {
                dao.save(job.copy(phase = if (completed || hasTarget) "deleting_complete" else "deleting",
                    updatedAt = Instant.now().toString()))
                if (!completed && !hasTarget) removeTransferTree(target)
                removeOwnedDirectory(directory)
                dao.delete(job.id, db.dbName)
            }
        } finally { lock.unlock() }
    }

    private fun toPreview(job: CompanionTransferJobEntity): CompanionTransferPreview {
        require(!job.phase.startsWith("deleting")) { "这条记录尚未清理完，请在列表点击删除后重新创建" }
        val report = gson.fromJson(job.reportJson, CompanionTransferReport::class.java)
        val rawBytes = ownedDirectory(job.directory).listFiles()?.filter { it.extension == "jsonl" }?.sumOf { it.length() } ?: 0
        return CompanionTransferPreview(job.id, job.targetName, report.sourceName,
            report.snapshot.counts.getValue("local_messages"), report.snapshot.counts.getValue("local_message_variants"),
            report.memory.accepted + report.snapshot.counts.getValue("companion_memories").toInt(),
            report.snapshot.excludedMemories + report.memory.skipped.values.sum(),
            report.memory.skipped.map { "${it.key}：${it.value} 条" } + listOf("依据原话重新整理记忆，旧自动记忆只作参考", "状态来自所选会话，原角色及原会话保留"),
            report.files.assets.size, report.files.missing, report.files.remote, rawBytes * 3 + report.files.bytes * 2,
            job.targetSessionId.takeIf { job.phase in setOf("complete", "backup_deleted") })
    }

    private suspend fun copyRows(job: CompanionTransferJobEntity, report: CompanionTransferReport,
        directory: File, targetDirectory: File, progress: (CompanionTransferProgress) -> Unit, sourceDb: NekobotDatabase = db) {
        val sql = db.openHelper.writableDatabase
        val snapshot = report.snapshot
        fun id(type: String, old: String) = UUID.nameUUIDFromBytes("${job.id}\u0000$type\u0000$old".toByteArray()).toString()
        fun rewrite(row: JsonObject, session: String = snapshot.sourceId) = files.rewrite(row, session, report.files, targetDirectory)
        val character = rewrite(transferRows(directory, "local_characters").single())
        character.addProperty("id", job.targetCharacterId); character.addProperty("name", job.targetName)
        sql.insertTransfer("local_characters", character)
        val original = requireNotNull(sourceDb.sessionDao().getById(job.sourceSessionId))
        val sessionMedia = rewrite(transferRows(directory, "local_sessions").first { it.text("id") == original.id })
        FusedCompanionSchema.installTriggers(sql)
        val target = original.copy(id = job.targetSessionId, name = job.targetName,
            characterId = job.targetCharacterId, characterName = job.targetName,
            characterAvatar = character.text("avatar"), portrait = character.text("portrait"),
            senderAvatar = sessionMedia.text("sender_avatar"),
            firstMessage = null, archiveSessionId = null, sessionMode = "agent", companionMode = true, userPersona = null,
            companionSpeakingPrompt = "", companionThinkingPrompt = null, companionThinkingEnabled = null,
            inheritCharacter = true, inheritCharacterGreeting = false, longConversationEnabled = true,
            longConversationTailUntilId = null, groupId = null, characterIds = null, groupConfig = null,
            groupActiveSpeaker = null, groupTurnCount = 0,
            agentTodos = null, agentGoal = null, agentSpec = null, proactiveChat = null,
            isPublic = false, shareConfig = null, promptStackDebug = null, composedSystemPrompt = null,
            plotMode = false, archived = false, pinned = false, messageCount = snapshot.counts.getValue("local_messages").toInt(),
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString())
        db.sessionDao().upsert(target)
        var copied = 0L
        for (sourceRow in transferRows(directory, "local_messages")) {
            coroutineContext.ensureActive()
            val row = rewrite(sourceRow, requireNotNull(sourceRow.text("session_id")))
            row.addProperty("id", id("message", requireNotNull(row.text("id"))))
            row.addProperty("session_id", target.id); row.add("routing_decision_id", com.google.gson.JsonNull.INSTANCE)
            sql.insertTransfer("local_messages", row)
            copied++
            if (copied % 256 == 0L) progress(CompanionTransferProgress("正在复制原话", copied, snapshot.counts.getValue("local_messages")))
        }
        for (table in listOf("local_message_variants", "local_message_images")) for (sourceRow in transferRows(directory, table)) {
            coroutineContext.ensureActive()
            val row = rewrite(sourceRow, requireNotNull(sourceRow.text("session_id")))
            row.addProperty("id", id(table, requireNotNull(row.text("id"))))
            row.addProperty("session_id", target.id)
            val messageId = id("message", requireNotNull(row.text("message_id")))
            require(db.messageDao().getById(messageId) != null) { "附件或候选回复的原话已缺失" }
            row.addProperty("message_id", messageId)
            if (table == "local_message_images" && row.text("status") in setOf("queued", "running")) {
                row.addProperty("status", "failed"); row.addProperty("error_message", "旧生成任务未迁入执行，可在新会话重新发起")
            }
            sql.insertTransfer(table, row)
        }
        for (table in listOf("local_world_books", "local_world_book_entries")) for (sourceRow in transferRows(directory, table)) {
            val row = rewrite(sourceRow)
            row.addProperty("id", id(table, requireNotNull(row.text("id"))))
            if (table == "local_world_books") row.addProperty("character_id", target.characterId)
            else row.addProperty("book_id", id("local_world_books", requireNotNull(row.text("book_id"))))
            sql.insertTransfer(table, row)
        }
        copyState(directory, target, snapshot, ::id)
        progress(CompanionTransferProgress("正在复制独立记忆"))
        CompanionTransferMemory(db, sourceDb).copy(directory, snapshot, target, ::id, ::rewrite)
        val targetScope = CompanionMemoryStore(db).ensureScope(target)
        CompanionUserProfile.converted(original.userPersona, targetScope.id, target.createdAt)?.let {
            db.companionMemoryDao().saveMemory(it)
        }
        sql.query("SELECT COUNT(*) FROM local_messages WHERE session_id=?", arrayOf(target.id)).use {
            require(it.moveToFirst() && it.getLong(0) == snapshot.counts.getValue("local_messages")) { "原话数量校验失败" }
        }
        db.sessionDao().update(target.copy(messageCount = db.messageDao().countBySession(target.id)))
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "副本引用校验失败，未提交迁入" } }
    }

    private suspend fun copyState(directory: File, target: LocalSessionEntity, snapshot: TransferSnapshot, id: (String, String) -> String) {
        val sql = db.openHelper.writableDatabase
        val snapshots = transferRows(directory, "local_state_snapshots").filter { it.text("character_id") == snapshot.characterId }.toList()
        for (old in snapshots) {
            val row = old.deepCopy()
            row.addProperty("id", id("state", requireNotNull(row.text("id"))))
            row.addProperty("session_id", target.id); row.addProperty("character_id", target.characterId)
            row.addProperty("target_id", sessionRelationshipTargetId(target.id)); sql.insertTransfer("local_state_snapshots", row)
        }
        val latest = snapshots.filter { it.text("session_id") == snapshot.sourceId }.maxByOrNull { it.text("timestamp").orEmpty() }
        val relationship = latest?.let { row -> RelationshipState(characterId = target.characterId!!, targetId = sessionRelationshipTargetId(target.id),
            affection = row.get("affection").asInt, trust = row.get("trust").asInt, familiarity = row.get("familiarity").asInt,
            dependency = row.get("dependency").asInt, security = row.get("security").asInt, jealousy = row.get("jealousy").asInt,
            updatedAt = row.text("timestamp").orEmpty()) }
            ?: transferRows(directory, "local_relationship_states").firstOrNull { it.text("target_id") == "session:${snapshot.sourceId}" }?.let {
                RelationshipState.fromJson(it.text("data_json").orEmpty()).copy(characterId = target.characterId!!, targetId = sessionRelationshipTargetId(target.id))
            }
        val selectedState = relationship ?: relationshipStateFromInitial(target.characterId!!, sessionRelationshipTargetId(target.id),
            LocalProfileRepository(db.characterDao()).getById(target.characterId)?.initialState.orEmpty(), Instant.now().toString())
        val selectedJson = JsonParser.parseString(selectedState.toJson()).asJsonObject.apply {
            // 明确区分角色卡初始值和已经评估过的积累，初始值不能把迁入聊天标记为已评估。
            addProperty("companion_initial_only", relationship == null)
        }.toString()
        db.relationshipDao().upsert(LocalRelationshipStateEntity(UUID.randomUUID().toString(),
            target.characterId!!, sessionRelationshipTargetId(target.id), selectedJson, selectedState.updatedAt))
        transferRows(directory, "local_character_states").firstOrNull { it.text("scope_id") == snapshot.sourceId }?.let { row ->
            row.addProperty("id", id("character-state", requireNotNull(row.text("id"))))
            row.addProperty("character_id", target.characterId); row.addProperty("scope_id", target.id)
            val data = JsonParser.parseString(row.text("data_json")).asJsonObject
            data.addProperty("character_id", target.characterId); data.addProperty("scope_id", target.id)
            row.addProperty("data_json", data.toString()); sql.insertTransfer("local_character_states", row)
        }
    }

    private fun ownedDirectory(path: String): File = File(path).canonicalFile.also {
        require(it.parentFile == base && runCatching { UUID.fromString(it.name) }.isSuccess) { "迁入目录校验失败" }
    }
    private fun removeOwnedDirectory(directory: File) = removeTransferTree(ownedDirectory(directory.path))

    /** 调用前已核对目录归属。默认不跟随内部符号链接，防止越界清理。 */
    private fun removeTransferTree(directory: File) {
        if (!directory.exists()) return
        java.nio.file.Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                if (failure != null) throw failure
                java.nio.file.Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
    companion object { private val lock = Mutex() }
}
