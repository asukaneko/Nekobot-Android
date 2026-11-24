package app.lianxu.independent

import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object RelationshipRecall {
    const val NAME="relation_event_search"
    const val LIMIT=6 // 原设计每轮工具循环保险。
    const val RECENT_BYTES=900 // 近期事件 JSON 的 UTF-8 预算。
    const val SEARCH_CHARS=1450 // 回想事件数组字符预算（String.length）。
    const val SEARCH_LIMIT=5
    const val SEARCH_DAYS=3650L // 未指定时间时回看窗口。
    const val NEGATIVE_HOURS=48L
    const val PROMISE_DAYS=14L
    const val RECENT_LIMIT=10
    val definition=mapOf("type" to "function","function" to mapOf("name" to NAME,"description" to "需要时回想仍记着的关系事件；换线索再查，想起来或放弃就停。",
        "parameters" to mapOf("type" to "object","properties" to mapOf("query" to mapOf("type" to "string"),"kind" to mapOf("type" to "string","enum" to listOf("负面","约定","行动","全部")),"days_ago_min" to mapOf("type" to "integer"),"days_ago_max" to mapOf("type" to "integer"),"limit" to mapOf("type" to "integer","minimum" to 1,"maximum" to 5)))))
    fun matches(row:RelationInput,state:RelationState,kind:String):Boolean {
        val negative=row.source in state.ledger||row.source in state.apologizable||row.source in state.repairable
        return kind=="行动"||kind=="全部"||negative&&kind=="负面"||kind=="约定"&&state.promises.any{it.source==row.source&&it.fulfilledBy==null}
    }
    fun event(row:RelationInput,state:RelationState,content:String):Map<String,Any> = mapOf(
        "消息" to row.source.turnId,"可引用行动来源" to true,"时间" to time(row.at),"内容" to content,
        "可解释" to (row.source in state.ledger),"可道歉和好" to (row.source in state.apologizable),
        "可修复心结" to (row.source in state.repairable),"未兑现约定" to state.promises.any{it.source==row.source&&it.fulfilledBy==null})
    fun time(at:Long)=DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    fun recent(rows:List<RelationInput>,state:RelationState,now:Long,content:(SourceRef)->String={""}):List<Map<String,Any>> {
        val negatives=rows.filter{now-it.at in 0..NEGATIVE_HOURS*RelationshipProjector.HOUR&&(it.source in state.ledger||it.source in state.apologizable||it.source in state.repairable)}.sortedByDescending{it.at}.filter{content(it.source).isNotBlank()}.take(RECENT_LIMIT).map{row->
            mapOf("消息" to row.source.turnId,"时间" to time(row.at),"内容" to content(row.source),"可解释" to (row.source in state.ledger),"可道歉和好" to (row.source in state.apologizable),"可修复心结" to (row.source in state.repairable))}
        val promises=state.promises.filter{it.fulfilledBy==null&&now-it.at in 0..PROMISE_DAYS*RelationshipProjector.DAY}.sortedByDescending{it.at}.filter{content(it.source).isNotBlank()}.take(RECENT_LIMIT).map{mapOf("消息" to it.source.turnId,"时间" to time(it.at),"未兑现约定" to true,"内容" to content(it.source))}
        val selected=mutableListOf<Map<String,Any>>()
        for(row in negatives+promises){if(Gson().toJson(selected+listOf(row)).toByteArray().size>RECENT_BYTES)break;selected.add(row)}
        return selected
    }
    fun content(source:SourceRef,original:String,memories:List<StoredMemory>,clarity:(StoredMemory)->Double):String {
        val m=memories.filter{it.evidence.any{e->e.source==source}}.maxByOrNull{clarity(it)}?:return original
        return MemoryForgetting.text(m,clarity(m)){original}
    }
    fun resolver(repo:ProcessingRepository,db:android.database.sqlite.SQLiteDatabase,role:String,now:Long):(SourceRef)->String {
        val memories=repo.usableMemories(db,role)
        val clarity=memories.associate{it.id to MemoryForgetting.state(db,it,now).clarity(now)}
        return {source->val original=db.rawQuery("SELECT body FROM messages WHERE friend_id=? AND id=?",arrayOf(role,source.turnId.toString())).use{if(it.moveToFirst())it.getString(0)else ""}
            content(source,original,memories){clarity.getValue(it.id)}}
    }
    fun validate(row:RelationInput,state:RelationState,seen:Set<Long>,known:Set<SourceRef> = emptySet(),issue:(String,String)->Unit):RelationInput {
        fun allowed(ref:SourceRef?,field:String,valid:Boolean,code:String):SourceRef? {
            if(ref==null)return null
            if(!valid){issue(field,code);return null}
            if(ref.turnId !in seen){issue(field,"NOT_SEEN");return null};return ref
        }
        val explains=allowed(row.explains,"解释",row.explains in state.ledger,"NOT_IN_LEDGER")
        val previous=allowed(row.previous,"指向之前",(row.correction=="误会"&&row.formatVersion<2)||row.previous in state.apologizable&&row.correction!="误会","NOT_APOLOGIZABLE")
        val fulfills=allowed(row.fulfills,"兑现约定",state.promises.any{it.source==row.fulfills&&it.fulfilledBy==null},"NOT_OPEN_PROMISE")
        // Decide before clearing fulfills: a rejected claim must not become an unpromised repair.
        val basis=row.repairBasis?:row.source
        val basisValid=basis==row.source||basis.characterId==row.source.characterId&&basis.turnId in 1 until row.source.turnId&&basis in known&&basis.turnId in seen
        if(row.repairBasis!=null&&!basisValid)issue("弥补依据","INVALID_OR_UNSEEN_BASIS")
        val repairCompleted=basisValid&&row.repairCompleted&&(row.fulfills==null||fulfills!=null)
        val repairs=allowed(row.repairs,"修复心结",repairCompleted&&row.repairs in state.repairable&&RelationRepairPair(basis,row.repairs!!) !in state.repairPairs,"REQUIRES_COMPLETED_REPAIR_AND_GRUDGE")
        if(explains!=null&&row.explanationPolicy>=1)return row.copy(p=null,c=row.c?.takeIf{fulfills!=null},v=null,b=null,disappointment=null,previous=null,correction="不介意了",promise=false,fulfills=fulfills,explains=explains,repairs=repairs,repairCompleted=repairCompleted,repairBasis=row.repairBasis.takeIf{basisValid})
        return row.copy(explains=explains,previous=previous,correction=if(explains!=null&&row.formatVersion>=2)"不介意了"else if(previous==null)null else row.correction,fulfills=fulfills,repairs=repairs,repairCompleted=repairCompleted,repairBasis=row.repairBasis.takeIf{basisValid})
    }
}
