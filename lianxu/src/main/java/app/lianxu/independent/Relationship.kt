package app.lianxu.independent

import com.google.gson.JsonElement
import kotlin.math.exp
import kotlin.math.pow

/** Version 3 inputs, not accumulated scores. SourceRef is also remapped by full backups. */
internal data class RelationInput(val source:SourceRef,val at:Long,val day:Long,val category:String,
    val p:Double?=null,val c:Double?=null,val v:Double?=null,val b:Double?=null,
    val disappointment:Double?=null,val previous:SourceRef?=null,val correction:String?=null,
    val status:String="valid",val expectation:String?=null,val importance:String?=null,val gap:String?=null,
    val promise:Boolean=false,val fulfills:SourceRef?=null,val explains:SourceRef?=null,val excuse:String?=null,
    val explainedAt:Long?=null,val ignored:Double?=null,val sourceHash:String?=null,val formatVersion:Int=1,val releaseKind:String?=null,val explanationPolicy:Int=0,val repairs:SourceRef?=null,val repairCompleted:Boolean=false,val repairBasis:SourceRef?=null)
internal data class RelationRepairPair(val basis:SourceRef,val target:SourceRef)
internal data class RelationIssue(val field:String,val code:String)
internal data class RelationPromise(val source:SourceRef,val at:Long,val fulfilledBy:SourceRef?=null)
internal data class RelationParameters(val trustGain:Double,val trustLoss:Double,val closenessGain:Double,
    // engine 2: half-life, apology and missing fields below are unused, kept for JSON compatibility.
    val cooling:Double,val sensitivity:Double,val disappointmentHours:Double,val unhappyHours:Double,
    val happyHours:Double,val apology:Double,val missingMax:Double,val missingDays:Double,val proactive:Boolean,
    val silenceDays:Double=2.0,val resendDays:Double=1.0,val resendCount:Int=1,val dailyMax:Int=1,val tone:String="关心一句，不抱怨",val ignoredDisappointment:Double=0.0)
internal enum class RelationTemplate(val label:String,val parameters:RelationParameters) {
    DIRECT("黏人直率",RelationParameters(.22,.42,.22,.08,1.0,8.0,12.0,6.0,.7,.6,3.0,true,1.0,.5,2,3,"轻轻抱怨",.08)),
    RESERVED("独立慢热嘴硬",RelationParameters(.15,.42,.10,.08,.3,24.0,48.0,4.0,.4,.2,10.0,false,3.5,1.0,0,1,"装作随意",.03)),
    STEADY("温和稳定",RelationParameters(.22,.30,.18,.08,.6,6.0,8.0,8.0,.8,.4,5.0,true)),
    TSUNDERE("傲娇",RelationParameters(.18,.42,.15,.08,.8,12.0,24.0,6.0,.5,.5,4.0,true,2.0,1.0,1,2,"嘴硬",.06)),
    SUNNY("元气开朗",RelationParameters(.24,.35,.24,.08,.5,4.0,6.0,10.0,.9,.4,3.0,true,1.0,.5,1,2,"元气分享",.02)),
    QUIET("安静依赖",RelationParameters(.20,.45,.20,.08,.9,16.0,12.0,6.0,.7,.6,4.0,false,1.5,1.0,1,1,"小心翼翼",.06)),
    MATURE("成熟理性",RelationParameters(.20,.30,.12,.08,.4,8.0,12.0,6.0,.7,.2,7.0,true,4.0,1.0,0,1,"平和简短",.01));
    fun prompt(parameters:RelationParameters=this.parameters)= "主动交流语气：${parameters.tone}。"+if(this==STEADY)"不催促、不施压。"else ""
    companion object { fun named(value:String?)=entries.firstOrNull{it.name==value||it.label==value} }
}
internal enum class RelationStart(val trust:Double,val closeness:Double) {
    NEW(.30,.20), FRIENDS(.50,.40), LOVERS(.60,.60)
}
internal data class RelationCause(val source:SourceRef,val at:Long,val direction:String)
internal data class RelationState(val trust:Double,val closeness:Double,val tension:Double,val disappointment:Double,
    val happy:Double,val unhappy:Double,val missing:Double,val causes:Map<String,RelationCause>,val lastContact:Long?,val promises:List<RelationPromise> = emptyList(),val closenessPeak:Double=closeness,
    val hurt:Double=0.0,val low:Double=0.0,val explanations:List<RelationExplanation> = emptyList(),val excuses:Map<String,Int> = emptyMap(),
    val ledger:Map<SourceRef,RelationPending> = emptyMap(),val apologizable:Set<SourceRef> = emptySet(),val repairable:Set<SourceRef> = emptySet(),val repairPairs:Set<RelationRepairPair> = emptySet(),
    val moments:List<RelationMoment> = emptyList())

internal object RelationInputContract {
    private val strength=mapOf("无" to 0.0,"轻" to .3,"中" to .6,"重" to .9)
    private val level=mapOf("低" to .3,"中" to .6,"高" to .9)
    private val gap=mapOf("小" to .2,"中" to .5,"大" to 1.0)
    val categories=setOf("关于你自己","关于她","关于关系","普通信息")
    fun validStored(row:RelationInput):Boolean = runCatching {
        row.explanationPolicy in 0..1&&row.formatVersion in 0..2&&(row.releaseKind==null||row.releaseKind in setOf("真误会","有原因"))&&row.source.turnId>0&&row.source.version>0&&row.category in categories&&row.status in setOf("valid","disputed")&&
            listOf(row.p,row.c,row.v,row.b).all{it==null||it in strength.values}&&
            (row.disappointment==null||row.disappointment.isFinite()&&row.disappointment in 0.0..1.0)&&
            (row.fulfills==null||row.fulfills.characterId==row.source.characterId&&row.fulfills.turnId<row.source.turnId&&row.fulfills.version>0)&&
            (row.explains==null||row.explains.characterId==row.source.characterId&&row.explains.turnId<row.source.turnId&&row.explains.version>0)&&
            (row.repairBasis==null||row.repairBasis==row.source||row.repairBasis.characterId==row.source.characterId&&row.repairBasis.turnId in 1 until row.source.turnId&&row.repairBasis.version>0)&&
            (row.repairs==null||row.repairCompleted&&row.repairs.characterId==row.source.characterId&&row.repairs.turnId in 1 until row.source.turnId&&row.repairs.version>0)&&
            (row.ignored==null||row.ignored.isFinite()&&row.ignored in 0.0..0.2)&&
            ((row.previous==null&&(row.correction==null||row.formatVersion>=2&&row.correction=="不介意了"))||(row.previous!=null&&row.previous.characterId==row.source.characterId&&row.previous.turnId<row.source.turnId&&row.previous.version>0&&row.correction in setOf("误会","道歉和好")))
    }.getOrDefault(false)
    fun parse(value:JsonElement?,source:SourceRef,at:Long,day:Long,previous:List<RelationInput>,
        ignored:(String,String)->Unit={field,code->Diagnostics.event("relation.input.ignored",mapOf("field" to field,"code" to code))}):RelationInput? {
        fun bad(field:String,code:String):Nothing? {ignored(field,code);return null}
        val j=value?.takeIf{it.isJsonObject}?.asJsonObject?:return bad("关系输入","OBJECT_REQUIRED")
        fun text(v:JsonElement?)=v?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
        fun id(v:JsonElement?):Long?=runCatching{v?.takeIf{it.isJsonPrimitive}?.asString?.toLong()}.getOrNull()
        if(id(j.get("消息"))!=source.turnId)return bad("消息","CURRENT_SOURCE_REQUIRED")
        val category=text(j.get("信息类别"))?.takeIf{it in categories}?:return bad("信息类别","OPTION_REQUIRED")
        fun option(key:String):Double?=strength[text(j.get(key))]?:bad(key,"INVALID_OPTION")
        val p=option("P");val c=option("C");val v=option("V");val b=option("B")
        val disappointed=j.get("没达到预期")?.takeUnless{it.isJsonNull}?.let{value->
            val d=value.takeIf{it.isJsonObject}?.asJsonObject
            val expectation=level[text(d?.get("期待"))];val importance=level[text(d?.get("重要性"))];val distance=gap[text(d?.get("落差"))]
            if(expectation==null||importance==null||distance==null)bad("没达到预期","INCOMPLETE_GROUP")else expectation*importance*distance
        }
        var target:SourceRef?=null;var correction:String?=null
        j.get("指向之前")?.takeUnless{it.isJsonNull}?.let{value->
            val obj=value.takeIf{it.isJsonObject}?.asJsonObject
            val old=previous.singleOrNull{it.source.turnId==id(obj?.get("消息"))&&it.source.characterId==source.characterId&&it.at<=at&&it.source.turnId<source.turnId&&it.status=="valid"&&((it.v?:0.0)>0||(it.b?:0.0)>0||(it.disappointment?:0.0)>0)}
            val mode=text(obj?.get("方式"))
            if(old==null||mode != "道歉和好")bad("指向之前","INVALID_TARGET_OR_MODE")
            else {target=old.source;correction=mode}
        }
        val d=j.get("没达到预期")?.takeIf{disappointed!=null&&it.isJsonObject}?.asJsonObject
        val promise=j.get("约定")?.let{if(it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean)it.asBoolean else {bad("约定","BOOLEAN_REQUIRED");false}}?:false
        // A missing/unknown promise is not an error and never manufactures positive input.
        val fulfills=previous.singleOrNull{it.promise&&it.status=="valid"&&it.source.turnId==id(j.get("兑现约定"))&&it.source.characterId==source.characterId&&it.source.turnId<source.turnId}?.source
        val basisProvided=j.has("弥补依据")
        val basis=if(!basisProvided)null else if(id(j.get("弥补依据"))==source.turnId)source else previous.singleOrNull{it.source.turnId==id(j.get("弥补依据"))&&it.source.characterId==source.characterId&&it.source.turnId<source.turnId&&it.at<=at&&it.status=="valid"}?.source
        if(basisProvided&&basis==null)bad("弥补依据","INVALID_BASIS")
        val fulfillmentRejected=j.get("兑现约定")?.takeUnless{it.isJsonNull}!=null&&fulfills==null
        val repairs=j.get("修复心结")?.takeUnless{it.isJsonNull}?.let{target->
            previous.singleOrNull{it.status=="valid"&&it.source.turnId==id(target)&&it.source.characterId==source.characterId&&it.source.turnId<source.turnId}?.source?:bad("修复心结","INVALID_TARGET")
        }
        val explanation=j.get("解释")?.takeIf{it.isJsonObject}?.asJsonObject
        val explains=previous.firstOrNull{it.source.turnId==id(explanation?.get("事件"))&&it.source.characterId==source.characterId&&it.source.turnId<source.turnId&&it.status=="valid"&&((it.v?:0.0)>0||(it.b?:0.0)>0||(it.disappointment?:0.0)>0||(it.ignored?:0.0)>0||it.gap!=null)}?.source
        if(explanation!=null&&explains==null)bad("解释","INVALID_TARGET")
        if(j.has("兑现约定")&&!j.get("兑现约定").isJsonNull&&fulfills==null)bad("兑现约定","INVALID_TARGET")
        return RelationInput(source,at,day,category,p,c,v,b,disappointed,target,correction,
            expectation=text(d?.get("期待")),importance=text(d?.get("重要性")),gap=text(d?.get("落差")),promise=promise,fulfills=fulfills,
            explains=explains,excuse=if(explanation!=null)RelationExcuses.category(text(explanation.get("类别")))else null,formatVersion=2,explanationPolicy=1,repairs=repairs,repairCompleted=j.get("弥补已完成")?.let{it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean&&it.asBoolean}==true&&!fulfillmentRejected&&(!basisProvided||basis!=null),repairBasis=basis).let{
                val kind=text(explanation?.get("判断"))
                if(explanation!=null&&kind !in setOf("真误会","有原因"))bad("解释","JUDGMENT_REQUIRED")
                if(explanation!=null)it.copy(
                    explains=explains?.takeIf{kind in setOf("真误会","有原因")},releaseKind=kind?.takeIf{it in setOf("真误会","有原因")})else it
            }
    }
    fun example(source:Long)="""{"消息":"$source","信息类别":"普通信息","P":"无","C":"无","V":"无","B":"无"}"""
    fun instruction(source:Long)="""
        调用 companion_record 时，参数顶层必填“关系输入”对象：${example(source)}；键名就叫“关系输入”，不要改名、不要写成字符串，也不要把里面的字段摊到顶层。
        “消息”原样填当前用户来源字符串“$source”，非正文/回复编号/自编序号；其余据原话选择，示例“无”非固定答案。
        信息类别仅关于你自己/关于她/关于关系/普通信息。P正向互动、C兑现、V失约、B越界，各仅无/轻/中/重，不写数字。
        邀约/约定/改期/兑现/感谢陪伴属关于关系，单独人物事实属关于你自己/关于她，无关客观信息/知识问答属普通信息；普通信息不能有P/C/V/B或约定。
        分享心事、感谢、一起开心是P；把约好的事做成是C；到时没做事后才说是V；伤人或无视明确边界是B。提前说明原因并给新时间的改期不算V，只填没达到预期：{期待:低/中/高,重要性:低/中/高,落差:小/中/大}，改期落差通常中。没有落空则省略这组。
        新明确约定加“约定”:true，已有约定改期填false、保留原编号。C须“兑现约定”:未兑现约定的来源编号；缺约定/已兑现则C无，不重复入账。有独立善意才P，兑现附带感谢不另计P。
        实际已完成且确实弥补旧事的行动才填“弥补已完成”:true、“修复心结”:旧事来源编号，仅选已列出/查得且可修复事件。补做失约/针对性补偿可算；同话题、无关善意/礼物/讨好、道歉、将来承诺不算。关联不确定就省略修复，独立兑现照记。无需预先作出承诺，缺旧约定仍C无，不编兑现约定。每次最多修复一件。新行动省略“弥补依据”即当前消息；旧行动必须填其消息编号，且本轮已列出或relation_event_search行动检索查到。未查得就省略弥补，勿编编号/冒充新行动。相同依据与心结仅一次，不同真实行动可再次缓解；可引用不等于已完成或相关，由模型判断。
        电影校准：邀周六看电影=关于关系/P轻/其余无/约定true；已有约定因加班提前改周日=关于关系/P/C/V/B全无/期待高/重要性中/落差中；“电影好看，谢谢陪我”=关于关系/C中/引用未兑现约定/P/V/B全无。仅事实相符才套用，缺约定不编C。
        普通信息不改变关系。仅凭没回复、晚回、几天不来不能填P/C/V/B或扣信任；程序不会因此替你增加失望或委屈。结合仍有效的聊天原话、消息状态和时间事实自主判断感受，不预设负面情绪，不推断已读或故意不回应。回忆或翻旧记录不是新经历。
        近期编号可直引；更早的解释/道歉/兑现按需relation_event_search，可换线索，想起或放弃即停。引用须已列出/查得且有对应可解释/可道歉和好/未兑现/可引用行动来源资格。未查得坦诚不记得，本轮不填解释/指向之前/兑现约定，不写分数或“已核对”。
        道歉：“指向之前”:{消息:旧编号,方式:道歉和好}，道歉和好只缓解那件事带来的不愉快。解释：“解释”:{事件:旧编号,判断:真误会/有原因,类别:工作忙/身体不舒服/忘了/家里有事/其他}；真误会=本没做错，有原因=做了但有理由，均仅引用可解释事件，内部记不介意，不重复V/失望。
        关系小结=程序算出的当前状态，历史feelings非当前心情。不得威胁、惩罚、拿离开吓人；嘴硬也不责怪合理加班/提前改期、不逼改现实安排，可期待下次相处。
    """.trimIndent()
}

internal object RelationshipProjector {
    const val HOUR=3_600_000L
    const val DAY=24*HOUR
    fun project(parameters:RelationParameters,inputs:List<RelationInput>,now:Long,start:RelationStart=RelationStart.NEW,
        lastContact:Long?=inputs.filter{it.at<=now}.maxOfOrNull{it.at},contactTimes:List<Long> = inputs.map{it.at},
        changes:List<RelationChange> = emptyList(),reachOuts:List<Long> = emptyList()):RelationState = RelationProjectionCache().project(parameters,inputs,now,start,
            (contactTimes+listOfNotNull(lastContact)).distinct(),changes,reachOuts)
}
