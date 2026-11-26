package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Isolated SQLite + fake transport. Never opens the user's database or calls a provider. */
class ReplyAccountingAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var core:CompanionRepository
    private lateinit var vault:ModelVault
    @Before fun setup()=runBlocking {
        name="batch-review-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        store.add(listOf(Friend("a","合成甲","角色设定"),Friend("b","合成乙","角色设定")),false)
        vault=ModelVault(context,name);vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid","fake",128000,4096),"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,"fake")
    }
    @After fun close(){vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}
    private suspend fun closed(role:String="a",text:String="合成用户原话",spoken:String="确实显示的正文"):ChatTurn {
        val ledger=ChatLedger(store);val t=ledger.begin(role,text,"fake")
        assertTrue(ledger.finish(t.id,t.attempt,spoken));return ledger.all().single{it.id==t.id}
    }
    private fun processor(reply:suspend(TextChatRequest)->TextChatResult)=ReplyAccounting(store,ModelAccess(vault),object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=reply(request)
    })
    @Test fun explicitReviewSelectionOverridesChatAndInvalidatesFallback()=runBlocking {
        val access=ModelAccess(vault)
        val original=requireNotNull(access.reviewCredential())
        assertEquals("fake",original.config.id);assertTrue(access.reviewCurrent(original.stamp))
        vault.save(original.config.copy(id="review-only",name="回顾模型"),"synthetic-only")
        vault.setPurpose(ModelPurpose.REVIEW,"review-only")
        val explicit=requireNotNull(access.reviewCredential())
        assertEquals("review-only",explicit.config.id);assertFalse(access.reviewCurrent(original.stamp))
        assertEquals("fake",vault.purposeId(ModelPurpose.CHAT))
        vault.setPurpose(ModelPurpose.REVIEW,"")
        assertFalse(access.reviewCurrent(explicit.stamp));assertEquals("fake",access.reviewCredential()?.config?.id)
    }

    @Test fun factOnlyReviewHeatFollowsTheValidMessageAcrossEditsWithdrawalAndForgetting()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        suspend fun review(turn:ChatTurn,phase:String,id:String,content:String){
            val source=turn.userMessageId
            val response="""{"records":[{"source":$source,"record":{"备注":"附注","learning":{"phase":"$phase","id":"$id","kind":"interest","scope":"读书","content":"$content","evidence":[{"source":$source,"layer":"fact"}]}}}]}"""
            assertTrue(processor{TextChatResult.Success(response)}.drain("a"))
        }
        review(closed(text="想看这本书"),"propose","","偶尔一起读")
        val original=core.learningRecords("a").single().state
        for(mode in listOf("edit","retract","forget")){
            val turn=closed(text="今天又聊到了这本书")
            review(turn,"revise",original.id,"想继续一起读")
            val revised=core.learningRecords("a").single().state
            assertEquals(60.0,revised.temperature.anchor,.1);assertEquals(28,revised.temperature.days)
            when(mode){
                "forget"->core.forget("a",turn.userMessageId)
                else->SourceChanges(repo).run{confirm(preview("a",turn.userMessageId,"user","聊的是另外一本",mode=="retract"))}
            }
            val restored=core.learningRecords("a").single().state
            assertEquals(original.content,restored.content);assertEquals(original.temperature,restored.temperature)
        }
    }

    @Test fun noteOnlyReviewKeepsTheRelationshipEventAcceptedDuringChat()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val ledger=ChatLedger(store)
        val turn=ledger.begin("a","谢谢你陪我看电影","fake")
        val source=core.sourceFor(turn)
        val event=RelationInputContract.reviewEvent(JsonParser.parseString("""{"P":"轻"}""").asJsonObject,source)
        val accepted=requireNotNull(RelationInputContract.parse(event,source,System.currentTimeMillis(),0,emptyList()))
        assertTrue(core.accept(turn,ExperienceProposal(relationInput=accepted),core.snapshot("a")){true})
        assertTrue(ledger.finish(turn.id,turn.attempt,"我也很开心"))
        val response="""{"records":[{"source":${source.turnId},"record":{"关系输入":null,"innerNote":"下次也想一起看"}}]}"""
        assertTrue(processor{TextChatResult.Success(response)}.drain("a"))
        val view=core.relationship("a")
        assertEquals(.3,view.inputs.single{it.source==source}.p!!,0.0)
        assertTrue(view.modelMaterial.contains("下次也想一起看"))
    }
    @Test fun damagedNewerProposalDoesNotHideTheValidNote()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val turn=closed()
        val response="""{"records":[{"source":${turn.userMessageId},"record":{"关系输入":null,"innerNote":"想他了"}}]}"""
        assertTrue(processor{TextChatResult.Success(response)}.drain("a"))
        repo.tx{db->db.execSQL("""INSERT INTO companion_sources(role,source_id,revision,created,proposal,committed)
            SELECT role,source_id,revision+1,created,?,1 FROM companion_sources WHERE role=? AND source_id=?""",
            arrayOf<Any>("""{"innerNote":""","a",turn.userMessageId))}
        assertTrue(core.relationship("a").modelMaterial.contains("想他了"))
    }

    @Test fun committedNoteFollowsSourceEditsWithdrawalAndPermission()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        for(mode in listOf("edit","retract","forget","dispute")){
            val turn=closed(text="我们明天去看电影")
            val note="我还期待这场电影$mode"
            val response="""{"records":[{"source":${turn.userMessageId},"record":{"关系输入":{"P":"轻"},"innerNote":"$note"}}]}"""
            assertTrue(processor{TextChatResult.Success(response)}.drain("a"))
            assertTrue(core.relationship("a").modelMaterial.contains(note))
            assertFalse(core.relationship("b").modelMaterial.contains(note))
            val source=core.relationship("a").inputs.single{it.source.turnId==turn.userMessageId}.source
            when(mode){
                "forget"->core.forget("a",source.turnId)
                "dispute"->assertTrue(core.correctRelationshipRecord(source))
                else->SourceChanges(repo).run{confirm(preview("a",source.turnId,"user","计划变了",mode=="retract"))}
            }
            assertFalse(core.relationship("a").modelMaterial.contains(note))
        }
    }
}
