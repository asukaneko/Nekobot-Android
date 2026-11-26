package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue

/** Synthetic C1/C2 records for data and UI checks; no provider or network involved. */
internal class LearningRecordsFixture(val store:Store) {
    var now=1_800_000_000_000L
    val core=CompanionRepository(store){now}
    val ledger=ChatLedger(store)

    suspend fun event(role:String="a",input:String,commit:Boolean=true,change:(JSONObject,RoleContext)->Unit={_,_->}):ChatTurn {
        now+=10_000
        core.beginInput(role)
        val turn=ledger.begin(role,input,"synthetic-learning-records")
        store.writableDatabase.execSQL("UPDATE messages SET created=? WHERE friend_id=? AND id=?",arrayOf<Any>(now,role,turn.userMessageId))
        val source=core.sourceFor(turn)
        val context=core.roleContext(role,source)
        val wire=JSONObject(RoleModelPayload.json(source.turnId))
        change(wire,context)
        val fact=ExperienceProposal(quote=input,memory=input,claimKind="interaction")
        val flow=RoleUnderstandingContract.parse(wire.toString(),input,fact,context,now,false,false)
        val proposal=fact.copy(understanding=flow)
        assertTrue(core.accept(turn,proposal,core.snapshot(role)){true})
        if(commit){
            val snapshot=core.snapshot(role)
            assertTrue(ledger.finishWithMemory(turn.id,turn.attempt,"合成回复：我先听你说。",
                expectedStamp=ledger.context(turn.id).stamp,onCompleted={core.finish(it,turn,snapshot,proposal)},
                displayReasoning="NATIVE_REASONING_NOT_LEARNING_EVIDENCE"){core.current(store.readableDatabase,snapshot)})
        }else ledger.fail(turn.id,turn.attempt,ChatFailure.NETWORK)
        return ledger.all().single{it.id==turn.id}
    }

    fun evidence(source:Long,layer:String="fact",message:Long?=null)=JSONObject().put("source",source).put("layer",layer).apply{if(message!=null)put("message",message)}
    fun learning(id:String,phase:String,refs:JSONArray,content:String="明确想倾诉时，先听对方说完，再询问是否需要建议")=
        JSONObject().put("id",id).put("kind","habit").put("scope","明确想倾诉的聊天")
            .put("content",content).put("phase",phase).put("evidence",refs)

    suspend fun proposeHabit(role:String="a",input:String="今天只想把烦心事说出来，先别急着给建议")=
        event(role,input){wire,c->wire.put("learning",learning("","propose",JSONArray().put(evidence(c.current.turnId))))}

    suspend fun reviseHabit(first:ChatTurn,role:String="a",input:String="上次先听我说很有帮助，等我说完再问要不要建议就好")=
        event(role,input){wire,c->
            wire.getJSONObject("appraisal").put("eventRelation","progress")
            val id=c.learning.single{it.source.turnId==first.userMessageId}.id
            wire.put("learning",learning(id,"revise",JSONArray().put(evidence(c.current.turnId))
                .put(evidence(first.userMessageId,"action",first.assistantMessageId))
                .put(evidence(first.userMessageId,"interpretation")),"先倾听；说完后询问是否需要建议"))
        }
}
