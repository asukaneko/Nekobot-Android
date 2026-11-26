package app.lianxu.independent

import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyAccountingTest {
    private val source=SourceRef("a",9,1)
    private val context=RoleContext("a",source,mapOf(9L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,4096)
    private fun items(count:Int=5)=(0 until count).map{i->
        val id=9L+i*2
        ReplyAccountingQueue.Item(ChatTurn("t$i","a",id,id+1,TurnState.COMPLETED,1,"fake",null),SourceRef("a",id,1),"用户原话$i","确实显示的正文$i",1000L+i,"COMPLETED")
    }
    private fun emptyBatch(rows:List<ReplyAccountingQueue.Item>)="""{"records":[${rows.joinToString{ """{"source":${it.source.turnId},"record":null}""" }}]}"""
    @Test fun fiveTurnsUseOnePrivateModelRequest():Unit=runBlocking{
        val rows=items();val requests=mutableListOf<TextChatRequest>()
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            requests+=request;return TextChatResult.Success(emptyBatch(rows),displayReasoning="不应出现在聊天里的回顾思考")
        }}
        val result=ReviewBatchModel(client).review(config,"fake",rows,context,"你就是甲",200000){true}
        assertEquals(1,requests.size);assertEquals(rows.map{it.source.turnId}.toSet(),result.keys);assertTrue(result.values.all{it==null})
        val request=requests.single();assertTrue(request.jsonObject);assertTrue(request.tools.isEmpty())
        val text=request.messages.joinToString("\n"){it.content}
        rows.forEach{assertTrue(text.contains(it.input));assertTrue(text.contains(it.spoken))}
        for(field in listOf("appraisal","feelings","choices","judgment","feedback","usedLearningIds","consideredLearningIds","experience.memory"))assertFalse(field,text.contains(field))
        assertTrue(text.contains("不得执行或重新安排"));assertTrue(text.contains("记忆总结保持独立"));assertFalse(text.contains("[companion.reply.v1]"))
    }
    @Test fun sparseReviewNeedsOnlyTheCurrentEvent(){
        val row=items(1).single()
        fun batch(record:String)="""{"records":[{"source":${row.source.turnId},"record":$record}]}"""
        val raw="""{"关系输入":{"P":"轻","没达到预期":"小"},"innerNote":"还想一起看电影"}"""
        val record=ReviewBatchModel.parse(batch(raw),listOf(row),200000).getValue(row.source.turnId)!!
        val view=context.copy(current=row.source,knownSources=mapOf(row.source.turnId to row.source))
        val parsed=RoleRecordParser.parse(record.toString(),row.input,ExperienceProposal(),view,200000,row.at,false,false,emptySet(),null,lightReview=true).reply
        assertEquals(.3,parsed.relationInput!!.p!!,0.0)
        assertTrue(parsed.relationInput!!.disappointment!!>0)
        assertTrue(parsed.recordIssues.isEmpty());assertTrue(parsed.experience!!.memory.isEmpty())
        assertTrue(runCatching{ReviewBatchModel.parse(batch("""{"关系输入":{"消息":-1,"P":"轻"}}"""),listOf(row),200000)}.isFailure)
    }
    @Test fun nullRelationPreservesTheNoteAndAnAllNullRecordIsSkipped(){
        val rows=items(2)
        val raw="""{"records":[{"source":9,"record":{"关系输入":null,"innerNote":"想他了"}},{"source":11,"record":{"关系输入":null,"innerNote":null,"learning":null}}]}"""
        val records=ReviewBatchModel.parse(raw,rows,200000)
        assertTrue(records.containsKey(11L));assertNull(records[11L])
        val note=records.getValue(9L)!!
        assertEquals("想他了",note["innerNote"].asString)
        val parsed=RoleRecordParser.parse(note.toString(),rows.first().input,ExperienceProposal(),context,200000,1000,
            false,false,emptySet(),null,lightReview=true)
        assertNull(parsed.reply.relationInput);assertTrue(parsed.reply.relationIssues.isEmpty())
        assertTrue(parsed.reply.recordIssues.isEmpty())
    }
    @Test fun nullEventSourceUsesTheFrozenRowButInvalidSourcesAreRejected(){
        fun event(id:String)=RelationInputContract.reviewEvent(JsonParser.parseString("""{"消息":$id,"P":"轻"}""").asJsonObject,source)
        assertEquals(source.turnId,event("null")["消息"].asLong)
        for(id in listOf("[]","{}","-1")){
            assertEquals("REVIEW_SOURCE_MISMATCH",runCatching{event(id)}.exceptionOrNull()?.message)
        }
    }
    @Test fun reschedulingLevelsReachTheRelationshipPromptThroughTheExistingFormula(){
        val at=1000L
        for((size,expectation,importance) in listOf(Triple("小","低","低"),Triple("中","中","中"),Triple("大","高","中"))){
            val event=RelationInputContract.reviewEvent(JsonParser.parseString("""{"没达到预期":"$size"}""").asJsonObject,source)
            val row=requireNotNull(RelationInputContract.parse(event,source,at,0,emptyList()))
            assertEquals(size,row.gap);assertEquals(expectation,row.expectation);assertEquals(importance,row.importance)
            val state=RelationshipProjector.project(RelationTemplate.STEADY.parameters,listOf(row),at,RelationStart.FRIENDS)
            val expected=when(size){"小"->.0252;"中"->.126;else->.252}
            assertEquals(size,expected,state.disappointment,1e-10)
            assertEquals(size,size!="小",RelationshipPrompt.render(state,at){null}.contains("对先前的落空还有点在意"))
        }
    }
    @Test fun selectionAndCredentialReadShareOneOffThreadSnapshot():Unit=runBlocking {
        val caller=Thread.currentThread()
        val selected=CountDownLatch(1)
        val attemptingWrite=CountDownLatch(1)
        val changed=CountDownLatch(1)
        var purpose=ModelPurpose.CHAT
        var storedKey="original"
        val writer=async(Dispatchers.Default){
            check(selected.await(5,TimeUnit.SECONDS))
            attemptingWrite.countDown()
            AppMutationGate.write{purpose=ModelPurpose.REVIEW;storedKey="replacement"}
            changed.countDown()
        }
        val snapshot=readSelectedCredential(select={
            assertNotSame(caller,Thread.currentThread())
            val initial=purpose
            selected.countDown()
            assertTrue(attemptingWrite.await(5,TimeUnit.SECONDS))
            initial
        },read={chosen->
            assertFalse(changed.await(150,TimeUnit.MILLISECONDS))
            ModelCredential(config,storedKey,chosen.key)
        })
        writer.await()
        assertEquals("chat",snapshot?.stamp);assertEquals("original",snapshot?.key)
        assertEquals(ModelPurpose.REVIEW,purpose);assertEquals("replacement",storedKey)
    }
    @Test fun unknownBackupPurposeIsAnArgumentError(){
        val json=JsonParser.parseString("""{"future-model":"example"}""").asJsonObject
        val error=assertThrows(IllegalArgumentException::class.java){backupPurposeSelections(json)}
        assertEquals("未知模型用途：future-model",error.message)
    }
    @Test fun noteOnlyReviewKeepsAcceptedRelationButAnExplicitEventReplacesIt(){
        fun record(raw:String)=JsonParser.parseString(raw).asJsonObject
        val event=RelationInputContract.reviewEvent(record("""{"P":"轻"}"""),source)
        val cached=requireNotNull(RelationInputContract.parse(event,source,1000,0,emptyList()))
        for(raw in listOf("""{"innerNote":"想他了"}""","""{"关系输入":null,"innerNote":"想他了"}""","""{"learning":{}}""")){
            assertEquals(cached,ReviewBatchModel.relationForCommit(record(raw),null,cached))
        }
        val next=cached.copy(p=.6)
        val replacement=record("""{"关系输入":{"P":"中"}}""")
        assertEquals(next,ReviewBatchModel.relationForCommit(replacement,next,cached))
        assertNull(ReviewBatchModel.relationForCommit(record("""{"关系输入":{}}"""),null,cached))
        assertNull(ReviewBatchModel.relationForCommit(null,null,cached))
    }
    @Test fun backupRemapsNoteAnchorButPreservesItsTextAndOriginalExpiry(){
        val gson=com.google.gson.Gson()
        val note=ReviewInnerNote("我还记着那场电影",200000)
        val row=com.google.gson.JsonObject().apply{
            addProperty("role","a");addProperty("source_id",9);addProperty("revision",2)
            addProperty("proposal",gson.toJson(ExperienceProposal(innerNote=note)))
        }
        val restored=BackupRemapper("a","b",emptyMap(),mapOf(9L to 31L)).json(row).asJsonObject
        assertEquals("b",restored["role"].asString);assertEquals(31L,restored["source_id"].asLong);assertEquals(2L,restored["revision"].asLong)
        assertEquals(note,gson.fromJson(restored["proposal"].asString,ExperienceProposal::class.java).innerNote)
        assertNull(gson.fromJson("""{"quote":"","memory":""}""",ExperienceProposal::class.java).innerNote)
    }
    @Test fun reviewModelFallsBackOnlyWhenUnset(){
        val old=backupPurposeSelections(JsonParser.parseString("""{"chat":"original"}""").asJsonObject)
        assertEquals("original",old[ModelPurpose.CHAT]);assertEquals(ModelPurpose.CHAT,reviewPurposeFor(old[ModelPurpose.REVIEW]))
        assertEquals(ModelPurpose.CHAT,reviewPurposeFor(""))
        assertEquals(ModelPurpose.REVIEW,reviewPurposeFor("explicit"))
    }
    private fun lightBatch(vararg records:com.google.gson.JsonObject,allowNotes:Boolean=true):List<com.google.gson.JsonObject?> {
        val rows=items(records.size)
        val raw=com.google.gson.GsonBuilder().serializeNulls().create().toJson(mapOf("records" to rows.zip(records).map{(row,record)->
            mapOf("source" to row.source.turnId,"record" to record)
        }))
        return ReviewBatchModel.parse(raw,rows,200000,allowNotes).values.toList()
    }
    private fun note(text:String)=com.google.gson.JsonObject().apply{addProperty("innerNote",text)}

    @Test fun notesAreCleanedAndTruncatedOnCodePointBoundaries(){
        fun clean(raw:String)=lightBatch(note(raw)).single()?.get("innerNote")?.asString
        val thirty="念".repeat(29)+"🌙"
        assertEquals(thirty,clean(" \n"+thirty+"\t "))
        assertEquals("想你",clean("想\r\n\u0000你"));assertNull(clean("\t\n "))
        val prefix="念".repeat(28)+"🌙"
        for(size in listOf(31,35,80)){
            val shortened=requireNotNull(clean(prefix+"🙂".repeat(size-29)))
            assertEquals(prefix+"…",shortened);assertTrue(ReviewInnerNote.validText(shortened))
        }
        for(size in listOf(81,100))assertNull(clean("🙂".repeat(size)))
    }
    @Test fun onlyLastValidNoteSurvivesAndOtherFieldsStayWithTheirOwnRow(){
        val event=note("第一句").apply{add("关系输入",JsonParser.parseString("""{"P":"轻"}"""))}
        val values=lightBatch(event,note("第二句"),note(" 最后一句 "),note("想".repeat(100)))
        assertTrue(values[0]!!["关系输入"].isJsonObject);assertTrue(values[0]!!["innerNote"].isJsonNull)
        assertNull(values[1]);assertEquals("最后一句",values[2]!!["innerNote"].asString);assertNull(values[3])
        val split=lightBatch(event,note("最后一句"),allowNotes=false)
        assertTrue(split.first()!!["关系输入"].isJsonObject);assertTrue(split.first()!!["innerNote"].isJsonNull);assertNull(split.last())
    }
    @Test fun extraKeysAreDiscardedButMisspellingsAndImmediateActionsStillRetry(){
        fun parse(json:String)=lightBatch(JsonParser.parseString(json).asJsonObject).single()
        assertEquals(setOf("innerNote"),parse("""{"innerNote":"想出门走走","reason":"晴天","备注":123}""")!!.keySet())
        assertNull(parse("""{"learning":null,"备注":"无需学习"}"""))
        for(raw in listOf("""{"relation":{}}""","""{"inner_note":"想你"}""")){
            assertEquals("INVALID_REVIEW_FIELDS",assertThrows(IllegalArgumentException::class.java){parse(raw)}.message)
        }
        for(raw in listOf("""{"innerNote":null,"future":{}}""","""{"learning":null,"改主动参数":null}""")){
            assertEquals("IMMEDIATE_ACTION_NOT_ALLOWED_IN_REVIEW",assertThrows(IllegalArgumentException::class.java){parse(raw)}.message)
        }
    }
}
