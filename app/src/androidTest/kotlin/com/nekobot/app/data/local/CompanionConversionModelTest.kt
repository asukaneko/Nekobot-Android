package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CompanionConversionRules
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

/** 经真实 Repository 和 HTTP 客户端验证选模，接口回复由本机夹具提供。 */
class CompanionConversionModelTest {
    @Test fun conversionUsesChosenModelAndLeavesChatSelectionAlone() = exercise("stop")
    @Test fun providerContentFilterStopsWithoutFormatRepairOrPartialImport() = exercise("content_filter")

    private fun exercise(finishReason: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldProfile = ServiceContainer.prefs.activeDbName
        val oldMode = ServiceContainer.prefs.appMode
        val profile = "conversion-selection-${UUID.randomUUID()}"
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        var payload = ""
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val worker = thread {
            runCatching { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = socket.getInputStream().buffered()
                fun line(): String { val out = java.io.ByteArrayOutputStream(); while(true) { val c=input.read(); if(c<0||c==10) break; if(c!=13) out.write(c) }; return out.toString("UTF-8") }
                var size = 0
                while (true) { val line = line(); if(line.isEmpty()) break; if(line.startsWith("Content-Length:", true)) size=line.substringAfter(':').trim().toInt() }
                val body = ByteArray(size); var offset=0
                while(offset<size) { val n=input.read(body,offset,size-offset); check(n>0); offset+=n }
                payload = body.toString(Charsets.UTF_8); requests.incrementAndGet()
                // 即使正文看起来是合法 JSON，也必须尊重服务商的终止原因。
                // 记忆 v2 协议要求最后一行是 {"done":true}，否则按「未完成」处理。
                val content = if (finishReason == "stop") "{\"done\":true}\n" else "{\"changes\":[]}"
                val response = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to finishReason)), "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5))).toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
            } }
        }
        try {
            ServiceContainer.switchAppMode(AppMode.LOCAL); ServiceContainer.switchLocalDb(profile)
            val db = NekobotDatabase.get(context, profile); val repo = ServiceContainer.localRepository
            db.characterDao().upsert(LocalCharacterEntity("c", "测试", createdAt = "old", updatedAt = "old"))
            db.sessionDao().upsert(LocalSessionEntity("s", "测试", characterId = "c", createdAt = "old", updatedAt = "old"))
            db.messageDao().upsert(LocalMessageEntity("m", "s", "user", "我喜欢茶", timestamp = "2026-01-01", createdAt = "2026-01-01"))
            repo.upsertAiModel(LocalAiModelEntity("active", "日常聊天", "openai_chat", apiKey = "synthetic", baseUrl = "http://127.0.0.1:1", model = "must-not-be-called", active = true, createdAt = "old"))
            repo.upsertAiModel(LocalAiModelEntity("chosen", "转换选择", "openai_chat", apiKey = "synthetic", baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "chosen-normal-model", maxContextLength = 16000, supportsTools = false, createdAt = "old"))
            repo.selectCompanionConversionModel("chosen")
            assertEquals("chosen", repo.selectedCompanionConversionModel())
            val preview = repo.previewCompanionTransfer("s")
            if (finishReason == "stop") repo.convertCompanionTransfer(preview.jobId, "转换副本", "chosen") {}
            else {
                try {
                    repo.convertCompanionTransfer(preview.jobId, "转换副本", "chosen") {}
                    fail("服务商拒绝后不应导入")
                } catch (blocked: CompanionConversionBlockedException) {
                    assertTrue(blocked.message.orEmpty().contains("服务商拒绝"))
                    assertTrue(blocked.technicalDetail.contains("content_filter"))
                }
                assertEquals(1, db.sessionDao().count())
                assertEquals("我喜欢茶", db.messageDao().getById("m")!!.content)
            }
            assertEquals(1, requests.get())
            val json = JsonParser.parseString(payload).asJsonObject
            assertEquals("chosen-normal-model", json["model"].asString)
            assertEquals(CompanionConversionRules.prompt, json["messages"].asJsonArray.first().asJsonObject["content"].asString)
            assertFalse(json.has("tools")); assertEquals("active", repo.getActiveModel()!!.id)
            if (finishReason == "stop") repo.deleteCompanionTransferBackup(preview.jobId)
        } finally {
            server.close(); worker.join(1000)
            ServiceContainer.switchLocalDb(oldProfile); ServiceContainer.switchAppMode(oldMode)
            context.deleteDatabase("$profile.db")
        }
    }
}
