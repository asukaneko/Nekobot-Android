package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ContactExposureTest {
    private val now=1800000000000L
    private val current=SourceRef("a",57,3)
    private fun context()=RoleContext("a",current,mapOf(57L to current),emptyList(),emptyList(),emptyList(),emptyList())
    private fun parameters()="""{"改主动参数":{"消息":"57","参数":{"tone":"温柔些"},"她的话":"那我说得温柔些。"}}"""
    private fun future()="""{"future":{"kind":"follow_up","reason":"用户请求稍后回访","when":"30秒后","evidence":[{"source":57,"layer":"fact"}]}}"""
    @Test fun disabledContactRejectsBothSchedulesAndSuggestions(){
        assertTrue(runCatching{ImmediateContact.parse(parameters(),"改主动参数",context(),now,scheduling=false)}.isFailure)
        val rejected=runCatching{ImmediateContact.parse(future(),"稍后聊",context(),now,scheduling=false)}.exceptionOrNull()
        assertTrue(rejected is RoleContractViolation)
        assertEquals("SCHEDULING_DISABLED",(rejected as RoleContractViolation).code)
    }
    @Test fun onDefinitionStillOffersTheRealScheduleEntry(){
        val encoded=Gson().toJson(ImmediateContact.definition(57,now))
        assertTrue(encoded.contains("\"future\""))
        assertTrue(encoded.contains("\"when\""))
        assertTrue(encoded.contains("\"evidence\""))
        assertFalse(encoded.contains("改主动参数"))
    }
    @Test fun staleScheduleFeedbackNeverEchoesTheRetiredSchema(){
        val result=ToolArgumentDiagnosis.writeFailure(ImmediateContact.NAME,future(),"稍后聊",emptySet(),
            mapOf("success" to false,"error" to "invalid_arguments"),RoleContractViolation("SCHEDULING_DISABLED"),contactSource=57)
        assertEquals("SCHEDULING_DISABLED",result["validation_code"])
        assertFalse(result.toString().contains("future"))
        assertFalse(result.toString().contains("notBefore"))
    }
}
