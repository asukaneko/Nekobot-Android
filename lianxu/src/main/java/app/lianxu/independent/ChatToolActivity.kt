package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * Display receipts only. Never fed into dialogue, memory or relationship scoring.
 *
 * [title] is the whole human phrase the user reads ("让我想一想"). The pill appears as soon as the
 * tool starts and the same pill is updated in place: on failure its whole text is replaced by the
 * agreed outcome ("没想起来" / "没记下"), never glued on as "让我想一想，没想起来", so it never reads
 * as giving up before thinking. [kind] chooses the replacement text only (reads vs writes).
 */
data class ChatToolActivity(val id:String,val attempt:Int,val title:String,val state:String,val before:String="",val detail:String="",val kind:String?=null,val tool:String?=null,val reasoningAt:Int=0,val reasoningKnown:Boolean=false,val operation:String?=null,val memoryId:String?=null,val recallGroup:String?=null,val recallRefs:List<RecallContentRef?>?=null) {
    fun label():String {
        if(tool=="agent_memory_update")return when(state){
            "running","staged"->"正在记住"
            "success"->if(!memoryId.isNullOrBlank())"我记住了"else"结果未确认"
            "failed"->"没记住"
            else->"结果未确认"
        }
        return when(state){
        "failed"->when(kind){WRITE->"没记下";READ->"没想起来";else->title}
        // A turn that ended before the tool reported keeps the honest "not confirmed" wording.
        "unknown"->title+"，结果未确认"
        // 回想出结果就换成“想起来了”；进行中仍是“让我想一想”。
        "success"->if(tool in RECALL_TOOLS)"想起来了" else title
        else->title
        }
    }
    /** Only a committed write that points at a real stored row may be opened; a staged/failed receipt never is. */
    val canOpenMemory:Boolean get()=state=="success"&&memoryId!=null&&(tool!="agent_memory_update"||memoryId.isNotBlank())
    companion object {
        const val READ="read"
        const val WRITE="write"
        /** Control receipts (遗忘/更正/停止提醒) are neither reads nor memory writes; 2.1 defines no failure text for them. */
        const val OTHER="other"
        /** Display-only: a failed bookkeeping pill whose attempt also has a successful one. Never stored. */
        const val SUPERSEDED="superseded"
        /** 查聊天与查记忆的回想调用：同次响应合并，成功统一说“想起来了”。 */
        val RECALL_TOOLS=setOf("agent_recall_search","agent_memory_read","agent_history_search")
    }
}
/**
 * Receipts that know where they sat in the thinking stream. Rows stored before the position existed
 * carry no marker and keep the old block display; a real offset of zero is a valid position.
 */
/**
 * One turn attempt shows one outcome for bookkeeping. When the same attempt has a companion_record that
 * succeeded (or is staged to land with the reply), its failed siblings (a second parallel call, or the
 * try before a resend) are marked [ChatToolActivity.SUPERSEDED] for display: no "没记下" pill, while
 * any text the model wrote before that call still shows. Only the copy on screen changes; the stored
 * journal and diagnostics keep the failure. An attempt with no successful record keeps "没记下".
 */
internal fun mergeRecordPills(activities:List<ChatToolActivity>):List<ChatToolActivity> {
    val landed=activities.filter{it.tool==CompanionRecord.NAME&&it.state in setOf("success","staged")}.map{it.attempt}.toSet()
    if(landed.isEmpty())return activities
    return activities.map{if(it.tool==CompanionRecord.NAME&&it.state=="failed"&&it.attempt in landed)it.copy(state=ChatToolActivity.SUPERSEDED) else it}
}
internal fun placedReceipts(activities:List<ChatToolActivity>,attempt:Int?)=activities.filter{it.reasoningKnown&&it.attempt==attempt}
internal object ChatToolJournal {
    const val PREFIX="chat_tool_display:"
    fun key(turn:String)=PREFIX+turn
    /** One turn never shows more pills than this; a backup carrying more is not one of ours. */
    private const val MAX_BACKUP_ROWS=200
    private val BACKUP_FIELDS=setOf("id","attempt","title","state","before","detail","kind","tool","reasoningAt","reasoningKnown","operation","memoryId","recallGroup","recallRefs")
    /** A finished turn only ever keeps settled receipts; running/staged are settled before export. */
    private val BACKUP_STATES=setOf("success","failed","unknown")
    private val TOOL_NAME=Regex("[A-Za-z0-9_.\\-]{1,100}")
    private fun rows(raw:String):List<ChatToolActivity> =
        runCatching{Gson().fromJson<List<ChatToolActivity>>(raw,object:TypeToken<List<ChatToolActivity>>(){}.type).orEmpty()}.getOrDefault(emptyList())
    /**
     * The copy that goes into a backup. The backup does not resume in-flight turns, so a receipt still
     * running or staged is settled exactly as an interrupted turn would settle it; a link to a memory row
     * that is not in this export is dropped, leaving a pill that shows but cannot be opened.
     */
    fun forBackup(raw:String,memoryIds:Set<String>,messageIds:Set<Long> = emptySet()):String = Gson().toJson(rows(raw).map{row->
        val settled=if(row.state=="running"||row.state=="staged")settleRows(listOf(row),row.attempt,committed=false,recordSaved=false,memorySaved=false).single() else row
        settled.copy(memoryId=settled.memoryId?.takeIf{it in memoryIds},recallRefs=settled.recallRefs?.map{ref->ref?.takeIf{if(it.kind=="memory")it.id in memoryIds else it.id.toLongOrNull() in messageIds}}?.takeIf{refs->refs.any{it!=null}})
    })
    /** Strict format check for a backup value: only the fields and states a receipt really has, within their limits. */
    fun validateBackup(raw:String,memoryIds:Set<String>,messageIds:Set<Long> = emptySet()){
        fun fail():Nothing=throw IllegalArgumentException("工具胶囊回执格式无效")
        val rows=runCatching{JsonParser.parseString(raw)}.getOrNull()?.takeIf{it.isJsonArray}?.asJsonArray?:fail()
        if(rows.size()>MAX_BACKUP_ROWS)fail()
        val ids=hashSetOf<String>()
        rows.forEach{element->
            val o=element.takeIf{it.isJsonObject}?.asJsonObject?:fail()
            if(!o.keySet().all{it in BACKUP_FIELDS})fail()
            fun text(key:String,max:Int,required:Boolean=false,blank:Boolean=true):String? {
                val v=o.get(key);if(v==null||v.isJsonNull){if(required)fail();return null}
                if(!v.isJsonPrimitive||!v.asJsonPrimitive.isString)fail()
                val s=v.asString;if(s.length>max||(!blank&&s.isBlank()))fail();return s
            }
            fun whole(key:String,range:LongRange,required:Boolean){
                val v=o.get(key);if(v==null||v.isJsonNull){if(required)fail();return}
                if(!v.isJsonPrimitive||!v.asJsonPrimitive.isNumber)fail()
                val d=v.asDouble;if(!d.isFinite()||d!=Math.floor(d)||d<range.first||d>range.last)fail()
            }
            if(!ids.add(text("id",100,required=true,blank=false)!!))fail()
            whole("attempt",0L..100_000L,required=true)
            text("title",100,required=true,blank=false)
            if(text("state",20,required=true) !in BACKUP_STATES)fail()
            text("before",16000);text("detail",500)
            text("kind",20)?.let{if(it !in setOf(ChatToolActivity.READ,ChatToolActivity.WRITE,ChatToolActivity.OTHER))fail()}
            text("tool",100)?.let{if(!TOOL_NAME.matches(it))fail()}
            whole("reasoningAt",0L..Int.MAX_VALUE.toLong(),required=false)
            o.get("reasoningKnown")?.takeUnless{it.isJsonNull}?.let{if(!it.isJsonPrimitive||!it.asJsonPrimitive.isBoolean)fail()}
            text("operation",200)
            text("recallGroup",100,blank=false)
            o.get("recallRefs")?.takeUnless{it.isJsonNull}?.let{refs->
                if(!refs.isJsonArray||refs.asJsonArray.size() !in 1..MAX_RECALL_CONTENT_REFS)fail()
                refs.asJsonArray.filterNot{it.isJsonNull}.forEach{element->
                    val ref=element.takeIf{it.isJsonObject}?.asJsonObject?:fail()
                    if(ref.keySet()!=setOf("kind","id","version","mode","digest","start","length"))fail()
                    listOf("kind","id","mode","digest").forEach{k->if(ref[k]?.isJsonPrimitive!=true||!ref[k].asJsonPrimitive.isString)fail()}
                    listOf("version","start","length").forEach{k->val v=ref[k]?:fail();if(!v.isJsonPrimitive||!v.asJsonPrimitive.isNumber||v.asDouble!=v.asInt.toDouble())fail()}
                    val value=runCatching{Gson().fromJson(ref,RecallContentRef::class.java)}.getOrNull()?:fail()
                    if(!value.valid()||(value.kind=="memory"&&value.id !in memoryIds)||(value.kind=="message"&&value.id.toLongOrNull() !in messageIds))fail()
                }
            }
            text("memoryId",200)?.let{if(it !in memoryIds)fail()}
        }
    }
    /** Restore: the memory rows got new ids, so each link follows its row; a link with no row is dropped. */
    fun remapBackup(raw:String,memoryIds:Map<String,String>,messageIds:Map<Long,Long> = emptyMap()):String {
        val rows=JsonParser.parseString(raw).asJsonArray
        val out=JsonArray()
        rows.forEach{e->val o=e.asJsonObject.deepCopy()
            o.get("memoryId")?.takeUnless{it.isJsonNull}?.asString?.let{old->val next=memoryIds[old];if(next==null)o.remove("memoryId") else o.addProperty("memoryId",next)}
            o.get("recallRefs")?.takeUnless{it.isJsonNull}?.asJsonArray?.let{refs->
                refs.forEachIndexed{index,element->if(!element.isJsonNull){val ref=element.asJsonObject;val old=ref["id"].asString
                    val next=if(ref["kind"].asString=="memory")memoryIds[old]else old.toLongOrNull()?.let{messageIds[it]?.toString()}
                    if(next==null)refs.set(index,com.google.gson.JsonNull.INSTANCE)else ref.addProperty("id",next)
                }}
                if(refs.none{!it.isJsonNull})o.remove("recallRefs")
            }
            out.add(o)
        }
        return out.toString()
    }
    fun read(db:SQLiteDatabase,turn:String):List<ChatToolActivity> = db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(turn))).use{c->
        if(!c.moveToFirst())emptyList() else rows(c.getString(0))
    }
    fun put(db:SQLiteDatabase,turn:String,item:ChatToolActivity){
        val rows=read(db,turn).toMutableList();val index=rows.indexOfFirst{it.id==item.id}
        if(index<0)rows+=item else rows[index]=item
        save(db,turn,rows)
    }
    /**
     * A staged write only becomes a success when the turn really commits it. [committed] is the
     * turn's own save result (including a commit that landed just before a cancellation),
     * [recordSaved] tells whether the protocol-C record survived the post-control re-validation and
     * [memorySaved] whether the staged memory candidates were committed in the same transaction.
     * Anything staged that did not land is honestly "没记下".
     */
    fun settle(db:SQLiteDatabase,turn:String,attempt:Int,committed:Boolean,recordSaved:Boolean,memorySaved:Boolean){
        val rows=read(db,turn)
        val settled=settleRows(rows,attempt,committed,recordSaved,memorySaved)
        if(settled!=rows)save(db,turn,settled)
    }
    internal fun settleRows(rows:List<ChatToolActivity>,attempt:Int,committed:Boolean,recordSaved:Boolean,memorySaved:Boolean):List<ChatToolActivity> = rows.map{item->
        if(item.attempt!=attempt)item else when(item.state){
            "running"->item.copy(state="unknown",detail="本轮已结束，操作结果未确认")
            "staged"->{
                // A memory receipt only counts as saved when this commit really linked it to a stored row.
                val linked=!item.memoryId.isNullOrBlank()
                val saved=committed&&when(item.tool){
                    CompanionRecord.NAME->recordSaved
                    "agent_memory_update"->memorySaved&&linked
                    else->true
                }
                if(saved)item.copy(state="success",detail="") else item.copy(state="failed",detail=when{
                    item.tool=="agent_memory_update"&&committed&&memorySaved&&!linked->"这条还在待确认，没有写成记忆"
                    committed->"根据后续操作，这条没有保存"
                    else->"回话没完成，这条没存下"
                })
            }
            else->item
        }
    }
    private fun save(db:SQLiteDatabase,turn:String,rows:List<ChatToolActivity>){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(turn));put("value",Gson().toJson(rows))},SQLiteDatabase.CONFLICT_REPLACE)}
    /**
     * The reply transaction is the only place that knows which row each staged write produced. Store
     * that link on its receipt so the pill can later read the real row; a write that produced no row
     * (a candidate kept for review, or a rejected stage) keeps no link and stays unopenable.
     */
    fun linkMemory(db:SQLiteDatabase,turn:String,attempt:Int,written:Map<String,String>){
        if(written.isEmpty())return
        val rows=read(db,turn)
        val linked=rows.map{row->val id=row.operation?.takeIf{row.attempt==attempt&&row.state=="staged"}?.let{written[it]};if(id==null)row else row.copy(memoryId=id)}
        if(linked!=rows)save(db,turn,linked)
    }
    fun start(call:ChatToolCall,attempt:Int,before:String,recallGroup:String?=null):ChatToolActivity {
        // Gson keeps this display mapping readable on the JVM; the Android org.json stub is not available in unit tests.
        val action=runCatching{Gson().fromJson(call.arguments,Map::class.java)?.get("action")?.toString()}.getOrNull().orEmpty()
        val (title,kind)=when(call.name){
            ImmediateContact.NAME->"安排联系" to ChatToolActivity.OTHER
            JointControl.NAME->when(action){"forget"->"遗忘" to ChatToolActivity.OTHER;"correct"->"更正" to ChatToolActivity.OTHER;"stop_reminding"->"停止提醒" to ChatToolActivity.OTHER;else->"记忆操作" to ChatToolActivity.OTHER}
            "agent_memory_update"->"正在记住" to ChatToolActivity.WRITE
            CompanionRecord.NAME->"嗯…我记住了" to ChatToolActivity.WRITE
            RelationshipRecall.NAME->"让我想想以前" to ChatToolActivity.READ
            "agent_history_search","agent_history_read"->"让我查一下聊天记录" to ChatToolActivity.READ
            else->"让我想一想" to ChatToolActivity.READ
        }
        return ChatToolActivity(java.util.UUID.randomUUID().toString(),attempt,title,"running",before.take(16000),kind=kind,tool=call.name,recallGroup=recallGroup?.takeIf{call.name in ChatToolActivity.RECALL_TOOLS})
    }
    /**
     * A read that came back empty is "没找到", not a success: recall/history/relation reads return
     * `success=true` with an empty list when nothing matches. A page that still has rows stays a
     * success even when more candidates remain, and a page blocked by the output budget is a
     * capacity boundary rather than a match.
     */
    private fun emptyRead(item:ChatToolActivity,result:Map<String,Any>):Boolean {
        if(item.kind!=ChatToolActivity.READ)return false
        val rows=result["results"]?:result["messages"]?:result["events"]?:return false
        // Chat snippets already visible in this context are removed from results, while the
        // real recall protocol keeps status=found and a positive visible_duplicates count.
        if(item.tool in ChatToolActivity.RECALL_TOOLS&&result["status"] in setOf("found","already_in_context")&&
            (result["visible_duplicates"] as? Number)?.toDouble()?.let{it>0}==true&&
            result["page_blocked_by_capacity"]!=true&&result["recall_stopped"]!=true)return false
        return (rows as? List<*>)?.isEmpty()==true
    }
    fun result(item:ChatToolActivity,result:Map<String,Any>):ChatToolActivity {
        val failed=result["success"]==false||result.containsKey("error")||result["status"] in setOf("error","quota_exhausted","context_full","disabled")
        val empty=!failed&&emptyRead(item,result)
        val staged=result["status"]=="staged_until_reply_commit"
        return item.copy(state=if(failed||empty)"failed"else if(staged)"staged"else"success",operation=result["operation"]?.toString()?.takeIf{it.isNotBlank()}?:item.operation,detail=when{
            failed->"操作未完成，角色会根据结果继续回应"
            empty&&result["page_blocked_by_capacity"]==true->"这一页超出容量，没取到内容"
            // 回想一条都没命中：只留“没想起来”这一颗，不再挂一行解释小字；其它读工具保持原文案。
            empty->if(item.tool in ChatToolActivity.RECALL_TOOLS)"" else "没找到符合的内容"
            staged->if(item.tool==CompanionRecord.NAME)"回话成功后保存"else"候选已暂存，尚未正式保存"
            item.title=="遗忘"->"已停止使用相关来源，原聊天仍保留"
            else->""
        })
    }
}

/**
 * Protocol C: the visible reply is plain text, so the normal path streams everything through.
 * The first non-whitespace character decides once: '{' keeps compatibility with a model that still
 * answers in the old JSON envelope (only its `reply` field is surfaced), anything else is prose and
 * never gets swallowed as JSON.
 */
internal class ChatDisplayDecoder {
    private val prefix=StringBuilder();private val json=ReplyTextPreview();private var mode=0
    fun append(text:String):String {
        if(mode==1)return json.append(text)
        if(mode==2)return text
        prefix.append(text);val first=prefix.firstOrNull{!it.isWhitespace()}?:return ""
        mode=if(first=='{')1 else 2
        val buffered=prefix.toString();prefix.clear()
        return if(mode==1)json.append(buffered)else buffered
    }
}
