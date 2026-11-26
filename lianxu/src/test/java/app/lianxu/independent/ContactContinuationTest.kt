package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ContactContinuationTest {
    private val now=1800000000000L
    private fun decision(extra:String)=CompanionContracts.decisionForReply("""{"action":"send","reason":"继续聊共同话题","text":"想到刚才说的那件事了"$extra}""",now,0)
    @Test fun contactToolCannotOfferOrAcceptRetiredFrequencySettings(){
        val definition=ImmediateContact.definition(1,now).toString()
        val record=CompanionRecord.definition(1,false).toString()
        val ref=SourceRef("a",1,1);val context=RoleContext("a",ref,mapOf(1L to ref),emptyList(),emptyList(),emptyList(),emptyList())
        assertFalse(record.contains("改主动参数"))
        assertFalse(definition.contains("改主动参数"))
        for(key in listOf("silenceDays","resendDays","resendCount","dailyMax")){
            assertFalse(definition.contains(key));assertFalse(record.contains(key))
            val raw="""{"改主动参数":{"消息":"1","参数":{"$key":3},"她的话":"等你确认"}}"""
            assertTrue(runCatching{ImmediateContact.parse(raw,"更改频率",context,now)}.isFailure)
        }
        assertEquals(setOf("tone"),RelationParameterRules.modelAllowed)
    }
    @Test fun sendMayChooseAnotherTimeWithoutFifteenMinuteMinimum(){
        val d=decision(",\"nextWhen\":\"1秒后\"")
        assertEquals(now+1000,d.nextAt)
        assertEquals(d,CompanionContracts.decisionForReply(Gson().toJson(d),now,0))
    }
    @Test fun absentNullOrInvalidTimeNeverInventsAContinuationOrLosesReply(){
        for(extra in listOf("",",\"nextWhen\":null",",\"nextWhen\":\"晚点\"",",\"nextAt\":${now-1}",",\"nextWhen\":\"1秒后\",\"nextAt\":$now")){
            val d=decision(extra);assertEquals("send",d.action);assertTrue(d.text.isNotBlank());assertNull(d.nextAt)
        }
    }
    @Test fun silenceAndCancellationCannotLeaveAContinuation(){
        for(action in listOf("silent","cancel"))assertNull(CompanionContracts.decisionForReply("""{"action":"$action","reason":"先放下","nextWhen":"1秒后"}""",now,0).nextAt)
    }
    @Test fun deferralStillRequiresAnExplicitValidTime(){
        assertEquals(now,CompanionContracts.decisionForReply("""{"action":"defer","reason":"现在再考虑","nextWhen":"现在"}""",now,0).nextAt)
        assertEquals("silent",CompanionContracts.decisionForReply("""{"action":"defer","reason":"以后","nextWhen":null}""",now,0).action)
    }
}
