package com.nekobot.app.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 按表流式保存选定资料；不读取模型、授权、全局记忆或其他会话的表。 */
internal class CompanionTransferSnapshot(private val db: NekobotDatabase) {
    suspend fun write(sourceId: String, directory: File): TransferSnapshot {
        check(directory.mkdirs() || directory.isDirectory) { "无法建立迁入备份目录" }
        val source = requireNotNull(db.sessionDao().getById(sourceId)) { "原会话已不存在，请重新选择" }
        require(source.canTransferToCompanion()) { "这里只能迁入本机单角色会话，请重新选择" }
        val character = requireNotNull(db.characterDao().getById(source.characterId!!)) { "原角色已不存在" }
        val chain = mutableListOf<LocalSessionEntity>()
        var current: LocalSessionEntity? = source
        while (current != null) {
            val row = current
            require(chain.none { it.id == row.id }) { "原话归档存在循环，请先修复归档关联" }
            require(row.characterId == source.characterId && !row.sessionMode.equals("group", true)) { "原话归档属于其他角色，未开始迁入" }
            chain += row
            current = row.archiveSessionId?.let { requireNotNull(db.sessionDao().getById(it)) { "关联的原话归档已缺失，未开始迁入" } }
        }
        val sql = db.openHelper.readableDatabase
        val args = chain.map { it.id }.toTypedArray()
        val marks = args.joinToString(",") { "?" }
        require(!sql.query("SELECT 1 FROM companion_scopes WHERE sessionId IN ($marks) AND characterId != ? LIMIT 1", arrayOf(*args, character.id)).use { it.moveToFirst() }) {
            "这份原话曾绑定其他角色，无法确认归属，请先核对原资料"
        }
        val counts = linkedMapOf<String, Long>()
        val hashes = linkedMapOf<String, String>()
        suspend fun save(table: String, where: String, parameters: Array<out Any?>, order: String = "rowid") {
            val file = File(directory, "$table.jsonl")
            var count = 0L
            file.bufferedWriter().use { writer ->
                sql.query("SELECT * FROM $table WHERE $where ORDER BY $order", parameters).use { cursor ->
                    while (cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        val row = cursor.transferJson()
                        // 分享口令和运行时调试内容不属于选定聊天资料。
                        if (table == "local_sessions") listOf("share_config", "prompt_stack_debug", "composed_system_prompt").forEach { row.add(it, JsonNull.INSTANCE) }
                        writer.appendLine(row.toString()); count++
                    }
                }
            }
            counts[table] = count
            hashes[table] = transferHash(file)
        }
        save("local_characters", "id=?", arrayOf(character.id))
        save("local_sessions", "id IN ($marks)", args)
        save("local_messages", "session_id IN ($marks)", args, "created_at,rowid")
        for (table in listOf("local_message_variants", "local_message_images", "local_state_snapshots", "local_experience_archives")) {
            save(table, "session_id IN ($marks)", args)
        }
        save("local_experience_sources", "archive_id IN (SELECT id FROM local_experience_archives WHERE session_id IN ($marks))", args)
        save("local_character_states", "character_id=? AND scope_id IN ($marks)", arrayOf(character.id, *args))
        save("local_relationship_states", "character_id=? AND target_id IN (${args.joinToString(",") { "?" }})", arrayOf(character.id, *args.map { "session:$it" }.toTypedArray()))
        val memoryWhere = "character_id=? AND target_id='local-user' AND (conversation_id IS NULL OR conversation_id='' OR conversation_id IN ($marks)) AND (source_session_id IS NULL OR source_session_id='' OR source_session_id IN ($marks)) AND NOT EXISTS (SELECT 1 FROM local_messages m WHERE m.id IN (source_start_message_id,source_end_message_id,source_turn_id) AND m.session_id NOT IN ($marks))"
        save("local_character_memories", memoryWhere, arrayOf(character.id, *args, *args, *args))
        val excludedMemories = sql.query("SELECT COUNT(*) FROM local_character_memories WHERE character_id=?", arrayOf(character.id)).use { it.moveToFirst(); it.getLong(0) } - counts.getValue("local_character_memories")
        save("local_world_books", "character_id=?", arrayOf(character.id))
        save("local_world_book_entries", "book_id IN (SELECT id FROM local_world_books WHERE character_id=?)", arrayOf(character.id))
        val scopes = "SELECT id FROM companion_scopes WHERE sessionId IN ($marks) AND characterId=?"
        val scopeArgs = arrayOf(*args, character.id)
        save("companion_scopes", "sessionId IN ($marks) AND characterId=?", scopeArgs)
        save("companion_memories", "scopeId IN ($scopes)", scopeArgs)
        save("companion_sources", "memoryId IN (SELECT id FROM companion_memories WHERE scopeId IN ($scopes))", scopeArgs)
        save("companion_forgotten_sources", "scopeId IN ($scopes)", scopeArgs)
        save("companion_clarifications", "scopeId IN ($scopes)", scopeArgs)
        save("companion_locked_reviews", "scopeId IN ($scopes)", scopeArgs)
        val fingerprint = transferHash(hashes.entries.joinToString("\n") { "${it.key}:${it.value}:${counts[it.key]}" })
        return TransferSnapshot(source.id, character.id, character.name, chain.map { it.id }, counts, hashes, fingerprint, excludedMemories).also {
            File(directory, "manifest.json").writeText(Gson().toJson(it))
        }
    }
}

internal data class TransferSnapshot(
    val sourceId: String, val characterId: String, val characterName: String,
    val sessionIds: List<String>, val counts: Map<String, Long>, val hashes: Map<String, String>, val fingerprint: String,
    val excludedMemories: Long
) {
    fun verify(directory: File) {
        hashes.forEach { (table, hash) -> check(transferHash(File(directory, "$table.jsonl")) == hash) { "迁入备份校验失败，请重新预览" } }
    }
}

internal fun Cursor.transferJson() = JsonObject().also { row ->
    for (index in 0 until columnCount) when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> row.add(getColumnName(index), JsonNull.INSTANCE)
        Cursor.FIELD_TYPE_INTEGER -> row.addProperty(getColumnName(index), getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> row.addProperty(getColumnName(index), getDouble(index))
        Cursor.FIELD_TYPE_STRING -> row.addProperty(getColumnName(index), getString(index))
        else -> error("迁入资料含不支持的字段类型")
    }
}

internal fun transferRows(directory: File, table: String): Sequence<JsonObject> = sequence {
    File(directory, "$table.jsonl").bufferedReader().useLines { lines ->
        for (line in lines) yield(JsonParser.parseString(line).asJsonObject)
    }
}

internal fun SupportSQLiteDatabase.insertTransfer(table: String, row: JsonObject) {
    val values = ContentValues()
    for ((key, value) in row.entrySet()) when {
        value.isJsonNull -> values.putNull(key)
        value.asJsonPrimitive.isNumber -> if (value.toString().contains('.')) values.put(key, value.asDouble) else values.put(key, value.asLong)
        value.asJsonPrimitive.isBoolean -> values.put(key, if (value.asBoolean) 1 else 0)
        else -> values.put(key, value.asString)
    }
    insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
}

internal fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
internal fun JsonObject.flag(key: String) = text(key) in setOf("1", "true")
internal fun transferHash(text: String): String = transferDigest(text.toByteArray())
internal fun transferHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) { val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
private fun transferDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
