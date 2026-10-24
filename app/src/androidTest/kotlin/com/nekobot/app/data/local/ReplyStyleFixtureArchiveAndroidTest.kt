package com.nekobot.app.data.local

import android.os.Build
import androidx.activity.ComponentActivity
import com.nekobot.app.ui.theme.NekobotTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.ai.FusedCompanionPolicy
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Generates only fictional data using the production exporter, then imports it into a second isolated profile. */
@RunWith(AndroidJUnit4::class)
class ReplyStyleFixtureArchiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val gson = Gson()
    private val characterId = "294bd9c4-c1a5-47b8-9a2f-2833b073fc31"
    private val sessionId = "46ad824b-0599-4603-b7d2-aae2b8228cf5"
    private val roleName = "林小满（气泡复测）"
    private val finalLine = "今天也想听你说说，最近过得怎么样？"
    private val stamp = "2026-09-01 10:00:00"
    private val exportTables = setOf("local_sessions", "local_messages", "companion_scopes", "companion_memories", "companion_sources", "companion_receipts", "companion_owners")

    @Test fun generateNativeArchiveAndVerifyImportRecallAndIsolation() = runBlocking {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val previous = ServiceContainer.prefs.activeDbName
        val sourceProfile = "fictional_recall_source_${UUID.randomUUID()}"
        val targetProfile = "fictional_recall_target_${UUID.randomUUID()}"
        val output = File(compose.activity.getExternalFilesDir(null), "reply-style-test-delivery").apply { mkdirs() }
        val archive = File(output, "林小满_气泡复测_纯虚构.zip")
        val profiles = listOf(sourceProfile, targetProfile)
        fun db() = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        try {
            ServiceContainer.switchLocalDb(sourceProfile)
            val source = db()
            source.characterDao().upsert(LocalCharacterEntity(characterId, roleName,
                description = "完全虚构的回查测试角色，与测试者是熟悉的朋友。所有经历都是为测试编写的。",
                personality = "温柔、轻快，喜欢读书和闲聊。说话自然简短，不把不确定的往事说成事实。",
                createdAt = stamp, updatedAt = stamp))
            val session = LocalSessionEntity(sessionId, roleName, characterId = characterId, characterName = roleName,
                sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, companionMode = true,
                createdAt = stamp, updatedAt = "2026-09-20 10:00:00")
            source.sessionDao().upsert(session)
            val history = mutableListOf<LocalMessageEntity>()
            fun add(role: String, text: String): LocalMessageEntity {
                val time = LocalDateTime.of(2026,9,1,10,0).plusHours(history.size.toLong())
                val authoredText = if(role == "assistant" && text.count { it == '。' } >= 2)
                    text.replaceFirst("。", "。<||>") else text
                val row = LocalMessageEntity("fb628960-bubbles-fixture-${history.size.toString().padStart(4,'0')}", sessionId,
                    role, authoredText, timestamp = time.toString()+"+08:00", createdAt = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                history += row
                return row
            }
            val meeting = add("user", "今天又想起我们最初相遇那天了。我们第一次认识是在2026年8月17日，星禾书店门口。那是我们初次见面，我借你一把红伞，你回送了我一枚银杏叶书签。")
            add("assistant", "当然记得那把红伞，书签是我夹在书里很久的那枚，送给你正好。")
            val oldTea = add("user", "我现在最喜欢绿茶，清清爽爽的，喝着很舒服。")
            add("assistant", "好，和你一起喝茶的时候，我会想到这个。")
            val newTea = add("user", "跟你更正一下饮品偏好：之前爱喝绿茶，那是以前了；现在我更喜欢不加糖的红茶。以后问起我现在爱喝什么，以这次更正为准。")
            add("assistant", "记住啦，口味会变，以后就按你现在喜欢的来。")
            val promise = add("user", "我们约好了2026年10月24日星期六下午四点半，到白沙湾灯塔入口碰头，再一起散步。这个约定还没完成，也没有取消。")
            add("assistant", "好呀，到时候慢慢走，不赶路。")
            val longNote = add("user", buildString {
                appendLine("这封长信叫《灯塔便签》。请原样留着：前面是日常观察，最后一行才是我们约定核对的那句话。")
                repeat(130) { i -> appendLine("第${i+1}页：午后我把桌上的纸重新排好，窗外有光落在杯沿，屋里很安静。我写下几句普通的日常观察，又停下来听了一会儿风声。桌上的笔记还没写完，我想等晚一点再接着写，不急着给今天一个结论。") }
                append("灯塔便签的最后一句是：海风把纸页吹到第七行。")
            })
            add("assistant", "这封长信我留在这里了，有空我们可以再翻翻。")
            val daily = listOf(
                "我今天把书架重新擦了一遍，把散着的纸按大小放进盒子里。午后光线很舒服，桌上那杯水已经不烫了，我就边收拾边听窗外的鸟叫。事情虽然小，做完之后屋子显得清爽了不少，晚上想早点休息。",
                "刚才在楼下走了一圈，树影一块一块落在小路上。有人带着小狗慢慢走，我在路边停下来系好鞋带，回家的时候顺手把垃圾带走了。也没有什么特别的事，就是想把这样普通的一小段日常讲给你听。",
                "今天试着画了几片叶子，有一片的轮廓总是画不好。我换了支铅笔重新试，最后觉得不必每根线都那么整齐。画纸放在桌边晾着，旁边还有没收起来的橡皮屑，等一会儿再慢慢整理。",
                "我把床单换好了，晾衣绳上的夹子还够用。今天的风比较轻，窗帘偶尔鼓起来一点，又轻轻落回去。等衣服晒干之后，我想把它们叠整齐放进柜子，给明天早上留点不那么匆忙的时间。"
            )
            repeat(160) { i ->
                add("user", "第${i+1}次日常闲聊：" + daily[i % daily.size])
                add("assistant", "我在听呢。这样的日常也挺好，把手边的事情慢慢做好就行。你刚才说的那点小变化，让今天和昨天又有了一点不同，等你收拾好了就歇一会儿。今天这段闲聊就留在这里，想起什么再接着跟我说。")
            }
            add("user", "今天先聊到这里，下次再接着聊。")
            add("assistant", finalLine)
            history.forEach { source.messageDao().upsert(it) }
            val store = CompanionMemoryStore(source)
            val scope = store.ensureScope(session)
            source.companionMemoryDao().saveScope(scope.copy(embeddingModelId = "", historyAccess = true,
                archiveMessageId = history.last().id, archiveRowId = source.companionMemoryDao().lastRowId(sessionId)))
            source.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO companion_owners(characterId,sessionId) VALUES(?,?)", arrayOf(characterId, sessionId))
            val indexes = listOf(
                Triple(meeting, "初识相遇的经过聊过，具体细节在那段原话中。", "episode"),
                Triple(newTea, "饮品偏好曾更正，需要以较晚的原话确认现在的喜好。", "user_profile"),
                Triple(promise, "有一次还没完成的散步约定，时间地点在原话中。", "promise"),
                Triple(longNote, "保存过一封题为《灯塔便签》的长信。", "episode")
            )
            indexes.forEachIndexed { i, (row, text, category) ->
                val memory = CompanionMemoryEntity("fb628960-bubbles-index-$i", scope.id, "fictional-index-$i", 1,
                    "fact", "测试者与林小满", text, "active", category = category,
                    createdAt = row.createdAt, updatedAt = row.createdAt, provenance = "imported_verified")
                source.companionMemoryDao().saveMemory(memory)
                source.companionMemoryDao().saveSources(listOf(CompanionSourceEntity(memory.id,row.id,
                    CompanionMemoryPolicy.fingerprint(row),row.content.take(90),row.createdAt.take(10),row.role)))
            }
            source.companionMemoryDao().saveReceipts(history.map { CompanionReceiptEntity(scope.id,it.id,CompanionMemoryPolicy.fingerprint(it)) })
            source.openHelper.writableDatabase.execSQL("UPDATE local_sessions SET message_count=?,last_message=?,updated_at=? WHERE id=?",
                arrayOf<Any>(history.size, finalLine, history.last().createdAt, sessionId))
            archive.outputStream().use { PortableDataArchiveManager(compose.activity).export(
                setOf(PortableDataCategory.CHARACTERS, PortableDataCategory.CONVERSATIONS), "", it, "1.1.15",
                mapOf(PortableDataCategory.CHARACTERS to setOf("table:local_characters"),
                    PortableDataCategory.CONVERSATIONS to exportTables.mapTo(linkedSetOf()) { table -> "table:$table" })) }

            ServiceContainer.switchLocalDb(targetProfile)
            var target = db()
            val sentinelId = "unrelated-fictional-${UUID.randomUUID()}"
            val sentinel = LocalCharacterEntity(sentinelId,"保留基线（合成）",description="不能被测试包改动",createdAt=stamp,updatedAt=stamp)
            target.characterDao().upsert(sentinel)
            val sentinelSession = LocalSessionEntity("$sentinelId-session","保留会话（合成）",characterId=sentinelId,companionMode=true,
                sessionMode="agent",inheritCharacter=true,longConversationEnabled=true,createdAt=stamp,updatedAt=stamp)
            target.sessionDao().upsert(sentinelSession)
            val sentinelScope = CompanionMemoryStore(target).ensureScope(sentinelSession)
            val sentinelMessage = LocalMessageEntity("$sentinelId-message",sentinelSession.id,"user","这是未参与导入的合成原话。",timestamp=stamp,createdAt=stamp)
            target.messageDao().upsert(sentinelMessage)
            val beforeScope = requireNotNull(target.companionMemoryDao().scope(sentinelScope.id))
            val recallSettings = ServiceContainer.prefs.companionRecallSettings(target.dbName,sentinelSession.id)
            val retrievalSettings = ServiceContainer.prefs.companionRetrievalSettings(target.dbName)
            val models = target.aiModelDao().listAll()
            val manager = PortableDataArchiveManager(compose.activity)
            var imported = 0
            repeat(2) { attempt ->
                archive.inputStream().use { input -> manager.prepareImport(input, "").use { prepared ->
                    assertEquals(setOf(PortableDataCategory.CHARACTERS,PortableDataCategory.CONVERSATIONS),prepared.preview.categories.map { it.category }.toSet())
                    val selection = prepared.preview.categories.associate { it.category to it.details.mapTo(linkedSetOf()) { d -> d.key } }
                    val preview = manager.previewImport(prepared,selection)
                    assertEquals(emptyList<String>(),preview.issues)
                    val result = manager.commitImport(prepared,preview)
                    if(attempt==0) { imported=result.importedRows; assertTrue(imported>history.size) }
                    else assertEquals("重导不应再次写入",0,result.importedRows)
                } }
            }
            target = db()
            assertEquals(sentinel,target.characterDao().getById(sentinelId))
            assertEquals(sentinelSession,target.sessionDao().getById(sentinelSession.id))
            assertEquals(sentinelMessage,target.messageDao().getById(sentinelMessage.id))
            assertEquals(beforeScope,target.companionMemoryDao().scope(sentinelScope.id))
            assertEquals(models,target.aiModelDao().listAll())
            assertEquals(recallSettings,ServiceContainer.prefs.companionRecallSettings(target.dbName,sentinelSession.id))
            assertEquals(retrievalSettings,ServiceContainer.prefs.companionRetrievalSettings(target.dbName))
            val importedSession = requireNotNull(target.sessionDao().getById(sessionId))
            assertTrue(importedSession.companionMode && importedSession.inheritCharacter && importedSession.longConversationEnabled)
            val importedHistory = target.messageDao().listBySession(sessionId)
            assertEquals(history,importedHistory)
            val importedStore = CompanionMemoryStore(target)
            val importedScope = importedStore.ensureScope(importedSession)
            assertEquals(target.dbName,importedScope.profile)
            assertEquals("",importedScope.embeddingModelId)
            assertEquals(sessionId,target.companionMemoryDao().owner(characterId)?.sessionId)
            assertEquals(4,importedStore.validatedMemories(importedScope).size)
            assertEquals(0,target.companionMemoryDao().pending(importedScope.id).size)
            val reader = LocalAgentRecallReader(target)
            for ((query,row) in listOf("初次见面" to meeting,"红茶" to newTea,"白沙湾" to promise,"灯塔便签" to longNote)) {
                assertTrue("导入后找不到原话：$query",reader.searchHistory(sessionId,listOf(query),8).any { it.id==row.id })
                assertEquals(row.content,reader.readHistory(sessionId,row.id,0,0).single().content)
            }
            assertTrue(reader.searchHistory(sessionId,listOf("出生城市"),8).isEmpty())
            val recent = FusedCompanionPolicy.recent(importedHistory,8000)
            assertTrue(recent.isNotEmpty())
            assertTrue(listOf(meeting,oldTea,newTea,promise,longNote).none { anchor -> recent.any { it.id==anchor.id } })
            compose.setContent { NekobotTheme { com.nekobot.app.ui.screens.chat.ModernChatScreen(sessionId,onBack={}) } }
            compose.waitUntil(20000) { compose.onAllNodesWithText(finalLine,substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(finalLine,substring=true).assertIsDisplayed()
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(500)
            val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"测试角色导入后.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            shot.recycle()
            File(output,"fixture-validation.json").writeText(gson.toJson(mapOf("character_id" to characterId,"session_id" to sessionId,
                "messages" to history.size,"memories" to indexes.size,"source_links" to indexes.size,"receipts" to history.size,
                "imported_rows" to imported,"repeat_import_writes" to 0,"unrelated_data_unchanged" to true,
                "models_and_existing_settings_unchanged" to true,"recent_messages" to recent.size,"answers_outside_recent_context" to true,
                "native_export_import" to true,"history_search_and_read" to true,"actual_chat_screen" to true,"external_model_calls" to 0,
                "assistant_multi_bubble_examples" to history.count { it.role=="assistant" && "<||>" in it.content },
                "long_source_chars" to longNote.content.length,"database_version" to target.openHelper.readableDatabase.version)))
        } finally {
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            ServiceContainer.switchLocalDb(previous)
            profiles.forEach { ServiceContainer.prefs.removeDbProfile(it); NekobotDatabase.deleteProfileFile(compose.activity,it) }
        }
    }
}
