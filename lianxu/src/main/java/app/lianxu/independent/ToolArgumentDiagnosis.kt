package app.lianxu.independent

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Read-only explanations, invoked exclusively after the original validator rejects. */
internal object ToolArgumentDiagnosis {
    fun retryExhausted(name:String,attempts:Int,result:Map<String,Any>):Map<String,Any> {
        if(attempts<3||result["success"]!=false)return result
        val notice=when(name){
            CompanionRecord.NAME -> "本轮 companion_record 已失败 3 次，下一步不再提供这个工具；不要再调用它，直接按人设自然回话。"
            JointControl.NAME -> "本轮 companion_memory_control 已失败 3 次，下一步不再提供这个工具；不要再调用它，按真实结果自然说明，不要声称已执行。"
            else -> return result
        }
        val detail=result["detail"] as? String
        return result+("detail" to if(detail.isNullOrEmpty())notice else "$detail\n$notice")
    }

    data class Problem(val field:String,val expected:String,val actual:String)
    fun actual(v:JsonElement?):String = when {
        v==null -> "缺失"
        v.isJsonNull -> "空值 null"
        v.isJsonObject -> "对象"
        v.isJsonArray -> "数组 ${v.toString().take(60)}"
        v.asJsonPrimitive.isString -> "字符串 ${v.toString().take(60)}"
        v.asJsonPrimitive.isBoolean -> "布尔值 $v"
        else -> "数字 ${v.toString().take(60)}"
    }
    fun failure(base:Map<String,Any>,error:Exception,diagnose:()->List<Problem>):Map<String,Any> = try {
        val all=diagnose();val shown=all.take(12)
        base+mapOf("problems" to shown.map{mapOf("field" to it.field,"expected" to it.expected,"actual" to it.actual)},
            "detail" to if(all.isEmpty())"这次调用报告失败，还不能确认已经完成；没有查明具体哪个字段出了问题（${error.javaClass.simpleName}），不要猜原因。" else
                "这次调用没有通过检查。参数有 ${all.size} 处不对："+shown.joinToString("；"){"${it.field}：${it.actual}，应${it.expected}"}+(if(all.size>12)"；另有 ${all.size-12} 处" else ""))
    }catch(_:Exception){base}

    fun diagnoseRecord(raw:String,input:String,needsExperience:Boolean):List<Problem> {
        val root=runCatching{JsonParser.parseString(raw)}.getOrNull()
        if(root?.isJsonObject!=true)return listOf(Problem("$","为 JSON 对象",actual(root)))
        if(!needsExperience)return emptyList()
        val value=root.asJsonObject.get("experience")
        if(value?.isJsonObject!=true)return listOf(Problem("experience","必填对象",actual(value)))
        val j=value.asJsonObject;val out=mutableListOf<Problem>()
        fun check(key:String,expected:String,ok:Boolean){if(!ok)out+=Problem("experience.$key",expected,actual(j.get(key)))}
        // JointExperience.parse: explicit required key set, including fixed control fields.
        val required=setOf("quote","memory","emotion","valence","relationship","control","targets","reconsider","claimKind","controlIntent","requestQuote")
        required.filterNot(j::has).forEach{check(it,when(it){"control"->"必填，固定为字符串 \"none\"";"targets"->"必填，固定为空数组 []";"reconsider"->"必填，固定为空值 null";else->"必填"},false)}
        // CompanionContracts.experience.text: supplied text must be a string (no coercion).
        for(key in required-setOf("valence","targets","reconsider"))if(j.has(key))check(key,"为字符串",j[key].isJsonPrimitive&&j[key].asJsonPrimitive.isString)
        fun str(key:String)=j[key]?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
        // JointExperience.parse fixed fields; CompanionContracts target conversion is not used here.
        if(j.has("control")&&str("control")!=null)check("control","固定为字符串 \"none\"",str("control")=="none")
        if(j.has("targets"))check("targets","固定为空数组 []",j["targets"].isJsonArray&&j["targets"].asJsonArray.isEmpty)
        if(j.has("reconsider"))check("reconsider","固定为空值 null",j["reconsider"].isJsonNull)
        // CompanionContracts.experience enum checks, including its empty-string defaults.
        val enums=mapOf("emotion" to setOf("","unknown","calm","happy","sad","anxious","angry"),"relationship" to setOf("","none","appreciation","boundary"),"claimKind" to setOf("reported_event","plan","hypothesis","fiction","quoted_report","interaction","none"),"controlIntent" to setOf("none","forget","stop_reminding","correct","uncertain"))
        enums.forEach{(key,allowed)->str(key)?.let{check(key,"为允许值 ${allowed.joinToString()}",it in allowed||key in setOf("claimKind","controlIntent")&&it.isBlank())}}
        // CompanionContracts.long and valence checks: exact number, range, unknown emotion requires zero.
        val n=runCatching{j["valence"].takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asBigDecimal?.longValueExact()}.getOrNull()
        if(j.has("valence"))check("valence","为整数 -1、0、1；未知情绪时为 0",n in -1L..1L&&(str("emotion") !in setOf("unknown","")||n==0L))
        // CompanionContracts.experience memory length and current quotation evidence.
        str("memory")?.let{check("memory","长度不超过 1200 字",it.length<=1200)}
        if(str("memory")?.isNotEmpty()==true||str("emotion") !in setOf(null,"","unknown")||str("relationship") !in setOf(null,"","none"))str("quote")?.let{check("quote","为当前原话中的非空引用",it.isNotBlank()&&it in input)}
        // JointExperience.parse requires current request evidence when controlIntent is non-none.
        if(str("controlIntent") !in setOf(null,"","none"))str("requestQuote")?.let{check("requestQuote","为当前原话中的非空直接要求",it.isNotBlank()&&it in input)}
        // RoleUnderstandingContract lenient parts and both relation contracts swallow issues: do not report them.
        return out
    }
    fun writeFailure(name:String,raw:String,input:String,knownTargets:Set<String>,base:Map<String,Any>,error:Exception,memoryEvidence:Map<String,MemoryEvidence>?=null,contactSource:Long?=null):Map<String,Any> {
        if(name !in setOf("agent_memory_update",JointControl.NAME,CompanionRecord.NAME,ImmediateContact.NAME))return base
        // MemoryTools.stage state guards are not argument errors; never diagnose them as fields.
        if(base["error"] in setOf("SOURCE_CHANGED","TOOL_LIMIT","TOOLS_DISABLED","STALE"))return base+mapOf("detail" to "当前状态不允许这次操作，无需修改参数重试。")
        if(name==ImmediateContact.NAME){
            val knownCodes=setOf("MISSING_CONTACT_TIME","INVALID_CONTACT_TIME","CONTACT_TIME_IN_PAST","AMBIGUOUS_CONTACT_TIME","AMBIGUOUS_OR_MISSING_LOCAL_TIME","INVALID_CONTACT_DURATION","INVALID_CONTACT_NUMBER","EMPTY_CONTACT","INVALID_CONTACT_FIELDS","SCHEDULING_DISABLED")
            val code=(error as? RoleContractViolation)?.code?:error.message?.takeIf{it in knownCodes}
            val result=if(code!=null)base+("validation_code" to code)else base
            return failure(result,error){diagnoseContact(raw,contactSource,code)}
        }
        if(error is ProcessingRejected&&error.code=="DATE_EVIDENCE"&&error.detail.isNotBlank())return base+mapOf("detail" to "这次调用没有通过检查。${error.detail}")
        if(base["error"] !in setOf("invalid_arguments","FORMAT","EVIDENCE","USER_EVIDENCE","DATE_EVIDENCE","UNKNOWN_TARGET"))return base
        return failure(base,error){when(name){JointControl.NAME->diagnoseControl(org.json.JSONObject(raw),input);CompanionRecord.NAME->diagnoseRecord(raw,input,false);else->diagnoseMemoryUpdate(org.json.JSONObject(raw),input,knownTargets,memoryEvidence)}}
    }
    /** Structural checks plus the real parser's rejection code; never copy contact prose into errors. */
    fun diagnoseContact(raw:String,current:Long?,code:String?):List<Problem> {
        val root=runCatching{JsonParser.parseString(raw)}.getOrNull()
        fun kind(v:JsonElement?):String=when{v==null->"缺失";v.isJsonNull->"空值 null";v.isJsonObject->"对象";v.isJsonArray->"数组";v.asJsonPrimitive.isString->"字符串";v.asJsonPrimitive.isBoolean->"布尔值";else->"数字"}
        if(root?.isJsonObject!=true)return listOf(Problem("$","为JSON对象",kind(root)))
        if(code=="SCHEDULING_DISABLED")return listOf(Problem("$","当前角色已关闭主动联系","无法预约"))
        val j=root.asJsonObject;val out=mutableListOf<Problem>()
        if(j.keySet().any{it!="future"})return listOf(Problem("$","仅包含future","包含未支持字段"))
        if(code=="EMPTY_CONTACT")return listOf(Problem("$","提供有效future","未提供有效安排或建议"))
        val f=j.get("future")?:return out
        if(f.isJsonNull)return out
        if(!f.isJsonObject)return out+Problem("future","为联系机会对象",kind(f))
        val value=f.asJsonObject
        fun text(v:JsonElement?)=v?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
        fun integer(v:JsonElement?)=runCatching{v?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asBigDecimal?.longValueExact()}.getOrNull()
        if(text(value.get("kind")) !in setOf("interest_share","shared_continuation","follow_up","actual_result"))out+=Problem("future.kind","为允许的联系类型",kind(value.get("kind")))
        if(text(value.get("reason"))?.let{it.isNotBlank()&&it.length<=1200}!=true)out+=Problem("future.reason","为1至1200字的非空理由",kind(value.get("reason")))
        if(!value.has("when")&&!value.has("notBefore"))out+=Problem("future.when","为明确且不早于本轮当前时间的自然时间","缺失")
        else if(code in setOf("MISSING_CONTACT_TIME","INVALID_CONTACT_TIME","CONTACT_TIME_IN_PAST","AMBIGUOUS_CONTACT_TIME","AMBIGUOUS_OR_MISSING_LOCAL_TIME","INVALID_CONTACT_DURATION","INVALID_CONTACT_NUMBER"))out+=Problem("future.when","为明确且不早于本轮当前时间的自然时间，不混用when/notBefore","原时间校验拒绝：$code")
        val evidence=value.get("evidence")
        if(evidence?.isJsonArray!=true||evidence.asJsonArray.isEmpty)out+=Problem("future.evidence","为非空证据数组",kind(evidence))
        else evidence.asJsonArray.forEachIndexed{index,e->
            val path="future.evidence[$index]"
            if(!e.isJsonObject)out+=Problem(path,"为证据对象",kind(e))else{
                val item=e.asJsonObject;val source=integer(item.get("source"));val layer=text(item.get("layer"))
                if(source==null)out+=Problem("$path.source","为已提供的用户消息整数编号",kind(item.get("source")))
                if(layer !in setOf("fact","interpretation","action"))out+=Problem("$path.layer","为fact、interpretation或action",kind(item.get("layer")))
                if(code=="SELF_SUPPORT"&&current!=null&&source==current&&layer!="fact")out+=Problem("$path.layer","当前用户来源${current}只能为fact；待执行计划不是已完成行动，不填写message",layer?.takeIf{it in setOf("action","interpretation")}?:kind(item.get("layer")))
                if(layer=="action"&&integer(item.get("message"))==null)out+=Problem("$path.message","为已提交角色回复的整数编号，不能写回复文字或未来打算",kind(item.get("message")))
                if(layer=="interpretation"&&integer(item.get("interpretationRevision"))==null)out+=Problem("$path.interpretationRevision","为本轮已提供旧解释的当前版本整数编号",kind(item.get("interpretationRevision")))
            }
        }
        val actualReason=when(code){
            "UNKNOWN_SOURCE"->"仅引用本轮已提供的有效来源编号"
            "NO_REAL_ACTION","INVALID_ACTION_DEPENDENCY"->"行动证据引用已有角色回复编号及其真实来源"
            "NO_INTERPRETATION","INVALID_INTERPRETATION_DEPENDENCY","INTERPRETATION_REVISION"->"旧解释必须存在且提供本轮已知的当前版本整数编号"
            "SELF_SUPPORT"->"当前用户来源只能为fact，不能用自身计划或解释自证"
            else->null
        }
        if(actualReason!=null&&out.none{it.field.endsWith(".layer")&&code=="SELF_SUPPORT"})out+=Problem("future.evidence",actualReason,"原来源校验拒绝：$code")
        return out
    }
    fun diagnoseMemoryUpdate(args:org.json.JSONObject,input:String,knownTargets:Set<String>,memoryEvidence:Map<String,MemoryEvidence>?=null):List<Problem> {
        val out=mutableListOf<Problem>()
        val json=JsonParser.parseString(args.toString()).asJsonObject
        fun check(key:String,expected:String,ok:Boolean){if(!ok)out+=Problem(key,expected,actual(json[key]))}
        val available=memoryEvidence?:mapOf("S1" to MemoryEvidence(SourceRef("diagnosis",1,1),"user",input,input,0,"UTC"))
        val proof=runCatching{ImmediateMemoryEvidence.select(args,available)}.getOrNull()
        check("evidence","为只包含一个本次已提供用户来源编号的数组（${available.keys.joinToString()}）",proof!=null)
        // MemoryTools.stage op getString and FORMAT enum; org.json string coercion stays authoritative.
        val op=runCatching{args.getString("op")}.getOrNull()
        check("op","为 add、update、cancel、complete 或 retract",op in setOf("add","update","cancel","complete","retract"))
        // MemoryTools.stage target: add requires empty; other operations resolve the registered reference.
        if(op=="add")check("target","新增时为空",args.optString("target").isEmpty())
        else if(op in setOf("update","cancel","complete","retract"))check("target","为已返回的有效目标编号",runCatching{args.getString("target") in knownTargets}.getOrDefault(false))
        // MemoryTools.stage content/category/about fall back to stored base on non-add operations.
        // Missing update fields are not provable errors without that base; only check supplied values.
        val content=args.optString("content")
        if(op=="add"||content.isNotBlank())check("content","为非空内容，最多 4000 字",content.isNotBlank()&&content.length<=4000)
        val fact=proof?.let{runCatching{ImmediateMemoryEvidence.quote(it,args)}.getOrNull()}
        if(proof!=null&&fact==null)check("quote","逐字引用所选用户原话中的非空片段；content可以概括，quote不能改写或来自助手话语",false)
        val category=args.optString("category")
        if(op=="add"||category.isNotBlank())check("category","为有效现代分类："+MemoryCategory.entries.filter{it.modern}.joinToString{it.name},runCatching{MemoryCategory.valueOf(category).modern}.getOrDefault(false))
        val about=args.optString("about")
        if(op=="add"||about.isNotBlank())check("about","为 USER、CHARACTER 或 BOTH",runCatching{MemoryAbout.valueOf(about)}.isSuccess)
        // Diagnose the same selected source/date policy as staging, never recommend dropping dates.
        fact?.let{ImmediateMemoryEvidence.dateProblem(args,it,content)?.let{(field,expected)->check(field,expected,false)}}
        check("date_type","为 day、habit 或 birthday",args.optString("date_type","day") in setOf("day","habit","birthday"))
        return out
    }
    fun diagnoseControl(args:org.json.JSONObject,input:String,allowed:Set<Long>?=null):List<Problem> {
        val json=JsonParser.parseString(args.toString()).asJsonObject
        val out=mutableListOf<Problem>()
        fun check(key:String,expected:String,ok:Boolean){if(!ok)out+=Problem(key,expected,actual(json[key]))}
        // JointControl.parse getString + exact authorization/action checks (Android coercion retained).
        check("authorization","必填，固定为 current_direct_request",runCatching{args.getString("authorization")=="current_direct_request"}.getOrDefault(false))
        check("action","为 forget、correct 或 stop_reminding",runCatching{args.getString("action") in setOf("forget","correct","stop_reminding")}.getOrDefault(false))
        // CompanionContracts.experience: controlQuote/requestQuote must quote current input, not blank.
        check("quote","为当前原话中的非空引用",runCatching{args.getString("quote").let{it.isNotBlank()&&it in input}}.getOrDefault(false))
        // JointControl.parse getJSONArray and Number requirement, then CompanionContracts exact long conversion.
        val targets=args.optJSONArray("targets")
        check("targets","为非空数字编号数组",targets!=null&&targets.length()>0)
        if(targets!=null)for(i in 0 until targets.length()){
            val v=targets.get(i)
            val id=runCatching{if(v is Number)v.toString().toBigDecimal().longValueExact() else null}.getOrNull()
            if(id==null)out+=Problem("targets[$i]","为整数数字编号",actual(json["targets"].asJsonArray[i]))
            // CompanionContracts.experience allowedTargets; only report when authoritative snapshot is available.
            else if(allowed!=null&&id !in allowed)out+=Problem("targets[$i]","为当前允许的来源编号",actual(json["targets"].asJsonArray[i]))
        }
        return out
    }
}
