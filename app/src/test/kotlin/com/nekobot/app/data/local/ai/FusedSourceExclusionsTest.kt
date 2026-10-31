package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FusedSourceExclusionsTest {
    private fun dao(c: Connection) = Proxy.newProxyInstance(CompanionMemoryDao::class.java.classLoader,
        arrayOf(CompanionMemoryDao::class.java)) { _, method, args ->
        when (method.name) {
            "appliedOpKeys" -> c.prepareStatement("SELECT opKey FROM companion_applied_ops WHERE scopeId=? AND substr(opKey,1,length(?))=?").use { s ->
                s.setString(1, args!![0] as String); s.setString(2, args[1] as String); s.setString(3, args[1] as String)
                s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            "insertAppliedOp" -> { val row = args!![0] as CompanionAppliedOpEntity
                c.prepareStatement("INSERT INTO companion_applied_ops VALUES(?,?,?,?)").use { s ->
                    s.setString(1,row.scopeId); s.setString(2,row.opKey); s.setString(3,row.memoryId); s.setString(4,row.createdAt); s.executeUpdate()
                }; Unit
            }
            else -> error(method.name)
        }
    } as CompanionMemoryDao

    @Test fun exclusionsSurviveReopenButDoNotBecomeSummaryReceiptsOrHideOriginalLookup() = runBlocking {
        val file = File.createTempFile("synthetic-exclusion-", ".db")
        val original = LocalMessageEntity("old", "session", "user", "已撤回的旧说法", timestamp = "2026-01-01", createdAt = "2026-01-01")
        val current = original.copy(id = "current", content = "当前新消息")
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { it.execute("CREATE TABLE companion_applied_ops(scopeId TEXT,opKey TEXT,memoryId TEXT,createdAt TEXT,PRIMARY KEY(scopeId,opKey))") }
                FusedSourceExclusions.save(dao(c), "scope", listOf(original))
                FusedSourceExclusions.save(dao(c), "scope", listOf(original)) // resume is idempotent
            }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                val raw = listOf(original, current)
                val eligible = FusedSourceExclusions.eligible(dao(c), "scope", raw)
                assertEquals(listOf(current), eligible)
                assertEquals(raw, FusedSourceExclusions.eligible(dao(c), "other-scope", raw))
                assertEquals(original, raw.single { it.id == "old" }) // explicit lookup uses the retained raw rows
                assertEquals(listOf(original.copy(content = "用户修改过的新说法")), FusedSourceExclusions.eligible(dao(c), "scope", listOf(original.copy(content = "用户修改过的新说法"))))
                val plan = FusedCompanionPolicy.windowPlan(eligible, emptySet(), currentMessageId = current.id)
                assertEquals(listOf(current), plan.window)
                c.createStatement().use { s -> s.executeQuery("SELECT count(*),memoryId FROM companion_applied_ops").use { r ->
                    assertTrue(r.next()); assertEquals(1, r.getInt(1)); assertNull(r.getString(2))
                } }
            }
        } finally { file.delete() }
    }
}
