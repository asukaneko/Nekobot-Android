package com.nekobot.app.data.local.diagnostics

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ProblemDiagnosticsTest {
    @Test fun metadataRejectsBodiesCredentialsAndArbitraryStatus() {
        val safe = DiagnosticMetadata.clean(mapOf("rounds" to 3, "tokens" to 200, "status" to "found",
            "body" to "private message", "api_key" to "secret", "model_ref" to "not a hash", "reason" to "private text"))
        assertEquals(mapOf("rounds" to "3", "tokens" to "200", "status" to "found"), safe)
    }
    @Test fun attemptsStaySeparateAndProfilesAreIsolated() {
        val dir = Files.createTempDirectory("diagnostic-store").toFile()
        try {
            val store = ProblemDiagnosticStore(dir)
            store.append("a", "p", "s", "t", "reply", "reply_saved", mapOf("variant" to 0))
            store.append("b", "p", "s", "t", "reply", "reply_saved", mapOf("variant" to 1))
            store.append("c", "other", "s", "t", "reply", "model_start", emptyMap())
            assertEquals(2, store.list("p").size)
            assertEquals(1, store.list("other").size)
            assertFalse(dir.listFiles()!!.any { it.readText().contains("\"session\":\"s\"") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun operationCannotChangeItsDatabaseOrConversation() {
        val dir = Files.createTempDirectory("diagnostic-owner").toFile()
        try {
            val store = ProblemDiagnosticStore(dir)
            store.append("a", "p", "s", "t", "reply", "model_start", emptyMap())
            assertThrows(IllegalArgumentException::class.java) { store.append("a", "p", "different", "t", "reply", "model_start", emptyMap()) }
            assertEquals(1, store.list("p").single().events.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun profileNameAndRoomFilenameKeepInspectAndCommitInOneRecord() {
        val dir = Files.createTempDirectory("diagnostic-profile-alias").toFile()
        try {
            val store=ProblemDiagnosticStore(dir)
            store.append("import","nekobot_local","","","import","import_start",mapOf("stage" to "inspect"))
            store.append("import","nekobot_local.db","","","import","import_result",mapOf("stage" to "commit","updated_rows" to 0,"kept" to 1))
            val op=store.list("nekobot_local.db").single()
            assertEquals(2,op.events.size); assertEquals("1",op.events.last().values["kept"])
            assertEquals(op,store.list("nekobot_local").single())
        } finally { dir.deleteRecursively() }
    }
    @Test fun eventLimitPreservesBeginningAndLatestAndMarksLoss() {
        val dir = Files.createTempDirectory("diagnostic-limit").toFile()
        try {
            val store = ProblemDiagnosticStore(dir, maxEvents = 3)
            repeat(8) { store.append("a", "p", "s", "t", "reply", "recall_result", mapOf("rounds" to it)) }
            val op = store.list("p").single()
            assertEquals(listOf("0", "6", "7"), op.events.map { it.values["rounds"] })
            assertEquals(5, op.dropped)
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun recordLimitDoesNotTouchUnrelatedFiles() {
        val dir = Files.createTempDirectory("diagnostic-prune").toFile()
        try {
            val unrelated = dir.resolve("original-chat.txt").apply { writeText("keep") }
            val store = ProblemDiagnosticStore(dir, maxRecords = 2)
            repeat(5) { store.append("a$it", "p", "s", "t", "reply", "model_start", emptyMap()) }
            assertEquals(2, store.list("p").size)
            assertEquals("keep", unrelated.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptRecordIsNotPresentedAsAValidOperation() {
        val dir = Files.createTempDirectory("diagnostic-corrupt").toFile()
        try {
            dir.resolve(diagnosticRef("bad")+".json").writeText("{broken")
            assertTrue(ProblemDiagnosticStore(dir).list("p").isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun optionalTextRemovesCredentialFormsUrlsPathsAndAttachments() {
        val clean = DiagnosticText.clean("""ordinary text
            |Authorization: Bearer protected-value
            |Cookie: session=protected-cookie
            |api_key="protected-key" password=protected-password
            |https://example.test/private?secret=protected-query
            |C:\Users\private\file.txt /data/user/0/private/file
            |data:image/png;base64,protected-image
            |sk-protected-credential
        """.trimMargin())
        assertTrue(clean.contains("ordinary text"))
        listOf("protected-value", "protected-cookie", "protected-key", "protected-password", "protected-query",
            "private", "protected-image", "sk-protected").forEach { assertFalse(it, clean.contains(it)) }
    }
    @Test fun credentialJsonSubtreesAreRemovedBeforeExport() {
        val clean = DiagnosticText.json("""{"query":"tea","credentials":{"token":"very-private"},"response":{"api_key":"secret","status":"empty"}}""")
        assertFalse(clean.contains("very-private")); assertFalse(clean.contains("secret"))
        assertEquals("empty", JsonParser.parseString(clean).asJsonObject["response"].asJsonObject["status"].asString)
    }
    @Test fun invalidAndOversizedOptionalTextIsExplicitlyIncomplete() {
        assertTrue(DiagnosticText.json("{broken").contains("无法完整读取"))
        assertTrue(DiagnosticText.clean("茶".repeat(120_001)).endsWith("[正文达到上限，余下未附带]"))
    }
    @Test fun jsonEncodedToolArgumentsDoNotBypassCredentialFiltering() {
        val clean = DiagnosticText.json("""{"function":{"arguments":"{\"query\":\"tea\",\"api_key\":\"small-private-key\"}"}}""")
        assertTrue(clean.contains("tea")); assertFalse(clean.contains("small-private-key"))
    }
    @Test fun zipContainsExactlyThePreviewedFilesWithReadableUtf8() {
        val files = linkedMapOf("问题说明.txt" to "测试说明", "诊断记录.json" to "{}", "运行日志.log" to "记录")
        val zip = ProblemDiagnosticBundle(files, emptyList()).zip()
        val actual = linkedMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(zip), Charsets.UTF_8).use { input ->
            while (true) { val entry = input.nextEntry ?: break; actual[entry.name] = input.readBytes().toString(Charsets.UTF_8); input.closeEntry() }
        }
        assertEquals(files, actual); assertFalse(actual.containsKey("本次资料.txt"))
    }
    @Test fun zipRejectsUnexpectedPathsButKeepsFullLargeRequests() {
        assertThrows(IllegalArgumentException::class.java) { ProblemDiagnosticBundle(mapOf("../secret" to "x"), emptyList()).zip() }
        val text = "长提示".repeat(400_000) + "末尾完整"
        val zip = ProblemDiagnosticBundle(mapOf("完整提示词.txt" to text), emptyList()).zip()
        ZipInputStream(ByteArrayInputStream(zip), Charsets.UTF_8).use {
            assertEquals("完整提示词.txt", it.nextEntry.name)
            assertEquals(text, it.readBytes().toString(Charsets.UTF_8))
        }
    }
}
