package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ContactToolContractTest {
    private val now=1800000000000L
    private val current=SourceRef("a",57,3)
    private val old=SourceRef("a",9,2)
    private val action=RoleActualAction(old,"acknowledge","chat",10,"合成已保存回复",emptyList(),emptyList(),"current_request",null,null)
    private fun context(actions:List<RoleActualAction> = listOf(action))=RoleContext("a",current,mapOf(57L to current,9L to old),emptyList(),actions,emptyList(),emptyList())
    private fun raw(source:Long=57,layer:String="fact",message:String="")="""{"future":{"kind":"follow_up","reason":"用户请求稍后回访","when":"30秒后","evidence":[{"source":$source,"layer":"$layer"$message}]}}"""
    private fun error(raw:String,ctx:RoleContext=context()):Exception=try{ImmediateContact.parse(raw,"稍后问我",ctx,now);throw AssertionError("invalid contact accepted")}catch(e:Exception){e}
    private fun feedback(raw:String,ctx:RoleContext=context())=ToolArgumentDiagnosis.writeFailure(ImmediateContact.NAME,raw,"稍后问我",emptySet(),mapOf("success" to false,"error" to "invalid_arguments"),error(raw,ctx),contactSource=current.turnId)
    private fun fields(result:Map<String,Any>)=(result["problems"] as List<*>).map{(it as Map<*,*>)["field"]}

    @Test fun schemaHasRealNestedTypesAndUsesTheCurrentSourceInItsCompleteExample(){
        val function=JsonParser.parseString(Gson().toJson(ImmediateContact.definition(57,now))).asJsonObject.getAsJsonObject("function")
        val future=function.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("future")
        assertTrue(future.get("description").asString.contains("\"source\":57"));assertTrue(future.get("description").asString.contains("\"layer\":\"fact\""))
        val p=future.getAsJsonObject("properties");assertEquals("string",p.getAsJsonObject("when").get("type").asString)
        assertEquals(setOf("kind","reason","when","evidence"),future.getAsJsonArray("required").map{it.asString}.toSet())
        val variants=p.getAsJsonObject("evidence").getAsJsonObject("items").getAsJsonArray("anyOf")
        val a=variants.single{it.asJsonObject.getAsJsonObject("properties").getAsJsonObject("layer").getAsJsonArray("enum")[0].asString=="action"}.asJsonObject
        assertEquals("integer",a.getAsJsonObject("properties").getAsJsonObject("message").get("type").asString)
        assertTrue(a.getAsJsonArray("required").any{it.asString=="message"})
        assertTrue(Gson().toJson(ImmediateContact.definition(943,now)).contains("\\\"source\\\":943"))
    }
    @Test fun currentFactStillUsesTheRealSourceVersionAndAllowsThirtySeconds(){
        val future=ImmediateContact.parse(raw(),"稍后问我",context(),now).flow!!.future!!
        assertEquals(now+30000,future.timing.notBefore);assertEquals(current,future.evidence.single().source)
    }
    @Test fun allThreeObservedWrongCurrentActionsPreserveTheActualSelfSupportRejection(){
        for(message in listOf(",\"message\":\"纯合成未来打算，不得出现在反馈\"",",\"message\":\"纯合成用户请求，不得出现在反馈\"","")){
            val value=raw(layer="action",message=message)
            assertEquals("SELF_SUPPORT",(error(value) as RoleContractViolation).code)
            val result=feedback(value);assertEquals(false,result["success"]);assertEquals("invalid_arguments",result["error"]);assertEquals("SELF_SUPPORT",result["validation_code"])
            assertTrue(fields(result).contains("future.evidence[0].layer"));assertTrue(result["detail"].toString().contains("fact"))
            assertFalse(result.toString().contains("不得出现在反馈"))
        }
    }
    @Test fun actionMustUseAnIntegerAndAnActuallyCommittedOldReply(){
        val valid=raw(9,"action",",\"message\":10")
        assertEquals(10L,ImmediateContact.parse(valid,"稍后问我",context(),now).flow!!.future!!.evidence.single().messageId)
        for(message in listOf(",\"message\":\"合成文字\"","")){
            val result=feedback(raw(9,"action",message));assertTrue(fields(result).contains("future.evidence[0].message"));assertEquals("NUMBER_message",result["validation_code"])
        }
        val missing=feedback(valid,context(emptyList()));assertEquals("NO_REAL_ACTION",missing["validation_code"]);assertTrue(fields(missing).contains("future.evidence"))
    }
    @Test fun unknownSourcesAndBadShapesAreRejectedWithoutGuessingTheAllowedList(){
        val unknown=feedback(raw(999));assertEquals("UNKNOWN_SOURCE",unknown["validation_code"]);assertTrue(fields(unknown).contains("future.evidence"))
        val shape=feedback("""{"future":{"kind":"bad","reason":{},"when":"30秒后","evidence":[]}}""")
        assertTrue(fields(shape).containsAll(listOf("future.kind","future.reason","future.evidence")))
    }
    @Test fun declaredInterpretationRevisionMustMatchTheActuallyProvidedOldVersion(){
        val flow=RoleUnderstanding(appraisal=null,feelings=emptyList(),goal=null,choices=emptyList(),selected="",judgment=null,feedback=emptyList(),learning=null,usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,dependencies=emptyList(),revision=4)
        val ctx=context().copy(entries=listOf(RoleEntry(old,flow,null)))
        val valid=raw(9,"interpretation",",\"interpretationRevision\":4")
        assertEquals(4,ImmediateContact.parse(valid,"稍后问我",ctx,now).flow!!.future!!.evidence.single().interpretationRevision)
        for(extra in listOf("",",\"interpretationRevision\":3",",\"interpretationRevision\":\"4\"")){
            val result=feedback(raw(9,"interpretation",extra),ctx)
            assertEquals("INTERPRETATION_REVISION",result["validation_code"]);assertTrue(fields(result).any{it.toString().startsWith("future.evidence")})
        }
    }
    @Test fun knownRootErrorsAreSpecificAndDoNotClaimUnexecutedTimeValidation(){
        val empty=feedback("{}");assertEquals("EMPTY_CONTACT",empty["validation_code"]);assertEquals(listOf("$"),fields(empty))
        val parameters=feedback("""{"改主动参数":{}}""");assertEquals("INVALID_CONTACT_FIELDS",parameters["validation_code"]);assertEquals(listOf("$"),fields(parameters))
        val root=feedback("""{"extra":1,"future":{}}""");assertEquals(listOf("$"),fields(root));assertFalse(root.toString().contains("时间校验"))
    }
    @Test fun stateFailuresDoNotInventParameterProblemsOrTurnSuccessIntoFailure(){
        val result=ToolArgumentDiagnosis.writeFailure(ImmediateContact.NAME,raw(),"",emptySet(),mapOf("success" to false,"error" to "invalid_arguments"),IllegalStateException("contact_permission_disabled"),contactSource=57)
        assertEquals(emptyList<Any>(),result["problems"]);assertFalse(result.containsKey("validation_code"))
        assertTrue(result["detail"].toString().contains("没有查明"))
    }
}
