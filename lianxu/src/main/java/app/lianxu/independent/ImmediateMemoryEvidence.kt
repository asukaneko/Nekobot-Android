package app.lianxu.independent

import org.json.JSONObject

/** Only numbered, verbatim user messages actually supplied in this request can be cited. */
internal object ImmediateMemoryEvidence {
    fun base(old:StoredMemory?,proposed:StoredMemory?,alreadyResolved:Boolean=false):StoredMemory?=
        if(alreadyResolved)old?:throw ProcessingRejected("SOURCE_CHANGED")else proposed?:old
    fun visible(proof:MemoryEvidence,messages:List<WireMessage>,current:Boolean=false)=messages.any{m->
        fun matches(body:String)=if(current&&m.sourceMessageId.isEmpty())body==proof.snapshot
            else m.sourceMessageId==proof.source.turnId.toString()&&m.sourcePrefix in 0..body.length&&body.substring(m.sourcePrefix)==proof.snapshot
        // Match literal user text first. The short suffix is no longer sent, but old queued
        // requests may still carry it, so only the current message may be matched without it.
        m.role=="user"&&(matches(m.content)||current&&matches(m.content.removeSuffix("\n\n"+CompanionPrompt.THINKING_REMINDER)))
    }
    fun aliases(current:MemoryEvidence,history:List<MemoryEvidence>,messages:List<WireMessage>):Map<String,MemoryEvidence> = buildMap{
        if(visible(current,messages,true))put("S1",current)
        history.filter{it.side=="user"&&it.source.characterId==current.source.characterId&&it.source.turnId!=current.source.turnId&&visible(it,messages)}
            .distinctBy{it.source}.forEachIndexed{index,proof->put("S${index+2}",proof)}
    }
    fun select(args:JSONObject,available:Map<String,MemoryEvidence>):MemoryEvidence {
        val e=args.optJSONArray("evidence")
        rejectUnless(e?.length()==1,"EVIDENCE")
        val alias=e!!.opt(0) as? String?:throw ProcessingRejected("EVIDENCE")
        return available[alias]?:throw ProcessingRejected("EVIDENCE")
    }
    fun quote(proof:MemoryEvidence,args:JSONObject):MemoryEvidence {
        if(!args.has("quote"))return proof
        val quote=args.opt("quote") as? String?:throw ProcessingRejected("USER_EVIDENCE")
        rejectUnless(quote.isNotBlank()&&quote in proof.snapshot,"USER_EVIDENCE")
        return proof.copy(quote=quote)
    }
    fun withDateType(args:JSONObject,inherited:String?):JSONObject=JSONObject(args.toString()).apply{
        if(!has("date_type")&&has("time_text"))put("date_type",inherited?:"day")
    }
    fun dateProblem(args:JSONObject,proof:MemoryEvidence,content:String,contentSupplied:Boolean=args.optString("content").isNotBlank()):Pair<String,String>? {
        val expression=args.optString("time_text");val type=args.optString("date_type","day")
        if(expression.isNotEmpty()&&expression !in proof.quote)return "time_text" to "逐字引用所选用户原话中的时间词，不能改引当前确认句或编造日期"
        val dated=contentSupplied&&app.lianxu.independent.time.TimeWordEngine.hasExpression(content)||args.has("quote")&&app.lianxu.independent.time.TimeWordEngine.hasExpression(proof.quote)
        if(expression.isBlank()&&(type=="birthday"||dated))return "time_text" to "日期事实须补对应时间词，不能删掉日期字段绕过校验；若仅记非日期事实，请只引对应用户证据片段，不要绑定另一事实的日期"
        if(type=="habit"&&(expression.isNotBlank()||dated))return "date_type" to "具体日期不能标成habit来丢弃，使用day或birthday并引用原话时间词"
        if(expression.isNotBlank()&&MemoryDates.resolve(expression,proof.sourceAt,proof.zone)==null&&MemoryDates.birthday(expression).second==null)return "time_text" to "引用所选事实中可解析的单日时间词，不能用无关词代替日期"
        if(expression.isNotBlank()&&!app.lianxu.independent.time.TimeWordEngine.isWholeExpression(expression,proof.snapshot,proof.quote))return "time_text" to "引用完整时间词，不能从11月等词中截取1月或删去已知年份"
        if(type=="birthday"&&MemoryDates.birthday(expression).second==null&&MemoryDates.resolve(expression,proof.sourceAt,proof.zone)==null)return "time_text" to "提供可解析的原话生日时间词；无出生年份时不会猜年份"
        return null
    }
    fun dates(args:JSONObject,proof:MemoryEvidence,content:String,contentSupplied:Boolean=args.optString("content").isNotBlank()):Pair<String?,String?> {
        dateProblem(args,proof,content,contentSupplied)?.let{(field,expected)->throw ProcessingRejected("DATE_EVIDENCE","$field：应$expected")}
        val expression=args.optString("time_text").takeIf{it.isNotBlank()}
        if(args.optString("date_type")=="habit")return null to null
        if(args.optString("date_type")=="birthday"){
            val birthday=MemoryDates.birthday(expression)
            // Relative birthdays identify the annual day, never an invented birth year.
            return if(birthday.second!=null)birthday else null to MemoryDates.resolve(expression,proof.sourceAt,proof.zone)?.takeLast(5)
        }
        return MemoryDates.resolve(expression,proof.sourceAt,proof.zone) to null
    }
    fun stamp(proof:MemoryEvidence,op:JSONObject):JSONObject=op.put("source_role",proof.source.characterId).put("source_turn",proof.source.turnId).put("source_version",proof.source.version)
        .put("source_digest",stableDigest(proof.snapshot)).put("source_at",proof.sourceAt).put("source_zone",proof.zone).put("source_quote",proof.quote)
    fun revalidate(op:JSONObject,valid:List<MemoryEvidence>):MemoryEvidence = valid.singleOrNull{
        it.side=="user"&&it.source.characterId==op.optString("source_role")&&it.source.turnId==op.optLong("source_turn",-1)&&it.source.version==op.optLong("source_version",-1)&&
            stableDigest(it.snapshot)==op.optString("source_digest")&&it.sourceAt==op.optLong("source_at",-1)&&it.zone==op.optString("source_zone")
    }?.let{source->val quote=op.optString("source_quote");rejectUnless(quote.isNotBlank()&&quote in source.snapshot,"SOURCE_CHANGED");source.copy(quote=quote)}?:throw ProcessingRejected("SOURCE_CHANGED")
}
