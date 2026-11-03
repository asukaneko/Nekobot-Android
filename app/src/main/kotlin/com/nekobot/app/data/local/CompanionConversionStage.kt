package com.nekobot.app.data.local

import android.content.Context
import androidx.room.withTransaction
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import java.io.File

internal data class CompanionConversionRequest(val modelId: String, val modelName: String, val inputTokens: Int,
    val generate: suspend (String, String) -> String,
    val generateStream: (suspend (String, String, LocalAiStreamCallbacks) -> String)? = null)
internal data class CompanionConversionRecord(val rules: String, val models: List<String>, val memories: Int = 0)

/** 临时库只含这次转换的资料，不注册为用户资料库，也不存接口配置。 */
internal class CompanionConversionStage(context: Context, directory: File, profile: String) : AutoCloseable {
    val db = NekobotDatabase.conversionStage(context, File(directory, "conversion.db"), profile)

    suspend fun prepare(job: CompanionTransferJobEntity, seed: suspend (NekobotDatabase) -> Unit) = db.withTransaction {
        if (db.companionTransferDao().get(job.id) != null) return@withTransaction
        seed(db)
        val session = requireNotNull(db.sessionDao().getById(job.targetSessionId))
        val scope = CompanionMemoryStore(db).ensureScope(session)
        // 自动旧记忆不作为新体系的已验证事实；人工记录和保护项继续保留。
        db.openHelper.writableDatabase.execSQL("UPDATE companion_memories SET current=0, provenance=? WHERE scopeId=? AND manual=0 AND locked=0 AND current=1",
            arrayOf(CompanionConversionRules.referenceProvenance, scope.id))
        // 与被移为参考的自动旧记忆关联的待确认问题一并退出有效记忆。
        db.openHelper.writableDatabase.execSQL("DELETE FROM companion_clarifications WHERE scopeId=? AND memoryId IN (SELECT id FROM companion_memories WHERE provenance=?)",
            arrayOf(scope.id, CompanionConversionRules.referenceProvenance))
        db.companionTransferDao().save(job.copy(phase = "conversion_seeded"))
    }

    suspend fun run(job: CompanionTransferJobEntity, request: CompanionConversionRequest, progress: (CompanionTransferProgress) -> Unit) {
        val session = requireNotNull(db.sessionDao().getById(job.targetSessionId))
        CompanionHistoryConversion(db).run(session, request.inputTokens, request.generate, request.generateStream) { done, total ->
            progress(CompanionTransferProgress("正在总结记忆", done, total))
        }
    }

    suspend fun count(job: CompanionTransferJobEntity): Int {
        val session = requireNotNull(db.sessionDao().getById(job.targetSessionId))
        val store = CompanionMemoryStore(db)
        return store.validatedMemories(store.ensureScope(session)).count { it.current }
    }

    /** 正式副本的原话已有相同新 ID；在调用者的最终事务内写入总结记忆结果。 */
    suspend fun importInto(destination: NekobotDatabase, job: CompanionTransferJobEntity) {
        val target = requireNotNull(destination.sessionDao().getById(job.targetSessionId))
        val targetDao = destination.companionMemoryDao()
        val scope = CompanionMemoryStore(destination).ensureScope(target)
        val stagedScope = requireNotNull(db.companionMemoryDao().scope(scope.id))
        val out = destination.openHelper.writableDatabase
        out.execSQL("DELETE FROM companion_sources WHERE memoryId IN (SELECT id FROM companion_memories WHERE scopeId=?)", arrayOf(scope.id))
        val tables = listOf("companion_memories", "companion_receipts", "companion_applied_ops", "companion_forgotten_sources", "companion_clarifications", "companion_pending", "companion_locked_reviews")
        for (table in tables) out.execSQL("DELETE FROM $table WHERE scopeId=?", arrayOf(scope.id))
        for (table in tables.filterNot { it == "companion_pending" }) {
            val filter = when (table) {
                "companion_memories" -> " AND provenance!='${CompanionConversionRules.referenceProvenance}'"
                "companion_applied_ops" -> " AND (memoryId IS NULL OR memoryId IN (SELECT id FROM companion_memories WHERE scopeId=? AND provenance!='${CompanionConversionRules.referenceProvenance}'))"
                else -> ""
            }
            db.openHelper.readableDatabase.query("SELECT * FROM $table WHERE scopeId=?$filter",
                if (table == "companion_applied_ops") arrayOf(scope.id, scope.id) else arrayOf(scope.id)).use { cursor ->
                while (cursor.moveToNext()) out.insertTransfer(table, cursor.transferJson())
            }
        }
        db.openHelper.readableDatabase.query("SELECT x.* FROM companion_sources x JOIN companion_memories m ON m.id=x.memoryId WHERE m.scopeId=? AND m.provenance!=?",
            arrayOf(scope.id, CompanionConversionRules.referenceProvenance)).use { cursor ->
            while (cursor.moveToNext()) out.insertTransfer("companion_sources", cursor.transferJson())
        }
        targetDao.saveScope(stagedScope.copy(baselineRowId = targetDao.lastRowId(target.id), archiveRowId = 0, archiveMessageId = null))
        // 最后再次用正式库的原话核对所有有效结果；不能把来源错误降级为成功。
        val saved = targetDao.memories(scope.id).filter { it.current && CompanionImportPolicy.usable(it) }
        val valid = CompanionMemoryStore(destination).validatedMemories(stagedScope).map { it.id }.toSet()
        require(saved.all { it.id in valid }) { "转换结果的原话核对失败，没有导入副本" }
    }

    override fun close() = db.close()
}
