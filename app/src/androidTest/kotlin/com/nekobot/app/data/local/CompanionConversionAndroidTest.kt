package com.nekobot.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.CompanionConversionRules
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 资料转换的端到端回归（记忆 v2 协议）。
 *
 * 模型返回 JSONL：每行一条操作，最后一行必须是 `{"done":true}`；
 * 证据用本次请求里的 S 编号引用 `target` 行，引用不成立就整批失败、不落库。
 */
class CompanionConversionAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()

    private suspend fun source(db: NekobotDatabase): LocalSessionEntity {
        val id = UUID.randomUUID().toString()
        db.characterDao().upsert(LocalCharacterEntity(id, "转换测试", createdAt = "old", updatedAt = "old"))
        return LocalSessionEntity("s-$id", "旧窗口", characterId = id, createdAt = "old", updatedAt = "old")
            .also { db.sessionDao().upsert(it) }
    }

    private suspend fun message(db: NekobotDatabase, source: LocalSessionEntity, id: String,
        text: String, role: String = "user") =
        db.messageDao().upsert(LocalMessageEntity(id, source.id, role, text,
            timestamp = "2026-10-01", createdAt = "2026-10-01"))

    /** 一条 add 操作（v2 JSONL 行）。 */
    private fun addLine(evidence: String, content: String, category: String,
        kind: String = "fact", dateType: String = "day", modelDate: String = "") = Gson().toJson(
        linkedMapOf(
            "op" to "add",
            "category" to category,
            "kind" to kind,
            "content" to content,
            "evidence" to listOf(evidence),
            "date_type" to dateType,
            "model_date" to modelDate
        )
    )

    private fun doneLine() = "{\"done\":true}"

    private fun lines(vararg ops: String) = (ops.toList() + doneLine()).joinToString("\n")

    @Test fun conversionWritesEvidenceBackedMemoriesAndRecordsReceipt() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            message(db, source, "u1", "我们一起做了生日蛋糕，最后烤成功了。")
            message(db, source, "a1", "一起做蛋糕很开心。", "assistant")
            message(db, source, "u2", "我喜欢茉莉茶。")
            db.memoryDao().upsert(
                LocalCharacterMemoryEntity("old", source.characterId!!, "local-user", "long",
                    "user_persona", "旧错误", "", "用户喜欢开飞机", 1, createdAt = "old")
            )
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            var requests = 0
            val targetId = service.execute(preview.jobId, "新角色",
                CompanionConversionRequest("chosen", "我的普通模型", 16000, { rules, material ->
                    requests++
                    assertEquals(CompanionConversionRules.prompt, rules)
                    // 模型未完成前正式库没有半成品
                    assertEquals(1, db.sessionDao().count())
                    assertFalse(material.contains("apiKey"))
                    val input = JsonParser.parseString(material).asJsonObject
                    val rows = input["target"].asJsonArray.map { it.asJsonObject }
                    assertTrue(rows.all { it.has("no") && it.has("text") && it.has("role") })
                    assertTrue(rows.none { it.has("id") })
                    assertTrue(input["legacy_reference"].toString().contains("飞机"))
                    val cake = rows.first { it["text"].asString.contains("蛋糕") }
                    val tea = rows.first { it["text"].asString.contains("茉莉茶") }
                    lines(
                        addLine(cake["no"].asString, "一起做生日蛋糕并做成功了。", "episode", kind = "episode"),
                        addLine(tea["no"].asString, "玩家喜欢茉莉茶。", "user_profile")
                    )
                }))
            assertEquals(1, requests)
            val target = db.sessionDao().getById(targetId)!!
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(target)
            val memories = store.validatedMemories(scope).filter { it.current }
            assertEquals(setOf("episode", "user_profile"), memories.map { it.category }.toSet())
            assertFalse(memories.any { it.content.contains("飞机") })
            assertTrue(memories.all { it.eventDate == null })
            assertTrue(db.companionMemoryDao().pending(scope.id).isEmpty())
            assertTrue(service.receipt(preview.jobId).contains("我的普通模型"))
            // 重复执行不重调模型、不重复落库
            assertEquals(targetId, service.execute(preview.jobId, "重复",
                CompanionConversionRequest("chosen", "我的普通模型", 16000, { _, _ -> error("不得重调") })))
            db.sessionDao().deleteById(source.id)
            db.characterDao().deleteById(source.characterId)
            assertEquals(2, store.validatedMemories(scope).count { it.current })
            assertEquals(3, db.messageDao().listBySession(target.id).size)
        } finally { db.close() }
    }

    @Test fun sourceChangedDuringConversionPublishesNothing() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            message(db, source, "u", "我喜欢茶")
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            var thrown: Throwable? = null
            try {
                service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, material ->
                    val no = JsonParser.parseString(material).asJsonObject["target"].asJsonArray
                        .single().asJsonObject["no"].asString
                    message(db, source, "u", "我更改了原资料")
                    lines(addLine(no, "玩家喜欢茶。", "user_profile"))
                }))
            } catch (error: Throwable) { thrown = error }
            assertNotNull("源资料变化后必须终止转换", thrown)
            assertEquals(1, db.sessionDao().count())
            assertEquals("我更改了原资料", db.messageDao().getById("u")!!.content)
        } finally { db.close() }
    }

    @Test fun missingDoneLinePublishesNothing() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            message(db, source, "u", "我喜欢茉莉茶。")
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            var thrown: Throwable? = null
            try {
                service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, material ->
                    val no = JsonParser.parseString(material).asJsonObject["target"].asJsonArray
                        .single().asJsonObject["no"].asString
                    // 只有操作行、没有结束标记
                    addLine(no, "玩家喜欢茉莉茶。", "user_profile")
                }))
            } catch (error: Throwable) { thrown = error }
            assertNotNull("缺少 done 结束标记时整批失败", thrown)
            assertEquals(1, db.sessionDao().count())
        } finally { db.close() }
    }

    @Test fun invalidEvidenceIsDroppedInsteadOfPublishing() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            message(db, source, "u", "我喜欢茉莉茶。")
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            // S9 在本次请求里不存在：这一条被丢弃，不会写入任何记忆
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本",
                CompanionConversionRequest("m", "模型", 14000, { _, _ ->
                    lines(addLine("S9", "玩家喜欢茉莉茶。", "user_profile"))
                })))!!
            val store = CompanionMemoryStore(db)
            assertTrue(store.validatedMemories(store.ensureScope(target)).isEmpty())
        } finally { db.close() }
    }

    @Test fun preservesManualProtectionAndDoesNotSendForgottenOrExpiredSources() = runBlocking {
        val db = database()
        try {
            val normal = source(db)
            val source = normal.copy(sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true)
            db.sessionDao().update(source)
            val store = CompanionMemoryStore(db)
            val old = store.ensureScope(source)
            for ((id, text) in listOf("forgotten" to "已经要求遗忘的往事", "expired" to "限时活动暗号", "current" to "我喜欢茶"))
                message(db, source, id, text)
            val expired = db.messageDao().getById("expired")!!
            db.companionMemoryDao().saveMemory(
                CompanionMemoryEntity("expired-memory", old.id, "expired-key", 1, "fact", "玩家", "限时活动暗号",
                    "active", createdAt = "old", updatedAt = "old", expiresAt = "2020-01-01")
            )
            db.companionMemoryDao().saveSources(
                listOf(CompanionSourceEntity("expired-memory", expired.id,
                    CompanionMemoryPolicy.fingerprint(expired), expired.content, "2026-10-01", "user"))
            )
            db.companionMemoryDao().saveForgotten(listOf(CompanionForgottenSourceEntity(old.id, "forgotten")))
            db.companionMemoryDao().saveMemory(
                CompanionMemoryEntity("manual", old.id, "manual-key", 1, "fact", "玩家", "手动保护的记忆",
                    "active", manual = true, locked = true, alwaysProvide = true, createdAt = "old", updatedAt = "old")
            )
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本",
                CompanionConversionRequest("m", "模型", 14000, { _, raw ->
                    val input = JsonParser.parseString(raw).asJsonObject
                    assertFalse(input["target"].toString().contains("遗忘"))
                    assertFalse(input["target"].toString().contains("限时活动"))
                    assertTrue(input["existing"].toString().contains("手动保护"))
                    doneLine()
                })))!!
            val scope = store.ensureScope(target)
            val saved = store.validatedMemories(scope).filter { it.current }
            assertEquals(1, saved.size)
            assertTrue(saved.single().manual && saved.single().locked && saved.single().alwaysProvide)
            assertEquals(1, db.companionMemoryDao().forgotten(scope.id).size)
        } finally { db.close() }
    }
}
