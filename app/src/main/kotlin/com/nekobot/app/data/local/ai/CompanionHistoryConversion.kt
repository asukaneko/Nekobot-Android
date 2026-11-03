package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.local.isLocalCommandMessage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 仅由隔离的转换资料库调用。成功批次的回执与记忆一起持久化，未完成尾回合也处理。 */
internal class CompanionHistoryConversion(private val db: NekobotDatabase) {
    suspend fun run(session: LocalSessionEntity, inputTokens: Int,
        generate: suspend (String, String) -> String,
        generateStream: (suspend (String, String, LocalAiStreamCallbacks) -> String)? = null,
        progress: (Long, Long) -> Unit) {
        val store = CompanionMemoryStore(db)
        val dao = db.companionMemoryDao()
        val scope = store.ensureScope(session)
        val history = store.permittedHistory(session, db.messageDao().listBySession(session.id))
            .filter { !it.deleted && it.role in setOf("user", "assistant") && !it.isLocalCommandMessage() }
        val legacy = dao.conversionReferences(scope.id)
        // 已有明确来源的失效/到期条目不从同一旧原话再次提取。
        val blocked = legacy.filter { !CompanionImportPolicy.usable(it) || it.status in setOf("cancelled", "retracted") }
            .flatMap { dao.sources(it.id) }.map { it.messageId }.toSet()
        db.withTransaction { FusedSourceExclusions.save(dao, scope.id, history.filter { it.id in blocked }) }
        val eligible = FusedSourceExclusions.eligible(dao, scope.id, history)
        val total = eligible.size.toLong()
        val receipts = db.openHelper.readableDatabase.query("SELECT messageId FROM companion_receipts WHERE scopeId=?", arrayOf(scope.id)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        var done = eligible.count { it.id in receipts }.toLong()
        progress(done, total)
        val budgets = FusedCompanionPolicy.budgets(inputTokens)
        // 参考只辅助排除近期矛盾；避免长篇近况淹没当前待总结记忆的旧段。
        val recent = FusedCompanionPolicy.recent(eligible, minOf(2000, inputTokens / 8))
        val units = FusedCompanionPolicy.turns(eligible).filter { unit -> unit.any { it.id !in receipts } }
        val batch = mutableListOf<LocalMessageEntity>()
        suspend fun flush() {
            if (batch.isEmpty()) return
            coroutineContext.ensureActive()
            val ids = batch.map { it.id }.toSet()
            store.convertBatch(session, batch.toList(), recent.filterNot { it.id in ids }, legacy, inputTokens, generate, generateStream)
            done += batch.count { it.id !in receipts }
            progress(done, total)
            batch.clear()
        }
        for (unit in units) {
            if (batch.isNotEmpty() && FusedCompanionPolicy.tokens(batch + unit) > budgets.batch) flush()
            batch += unit
        }
        flush()
        // 这些来源有排除理由，而非模型已总结记忆；不生成伪造的模型回执。
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM companion_pending WHERE scopeId=?", arrayOf(scope.id))
        }
    }
}
