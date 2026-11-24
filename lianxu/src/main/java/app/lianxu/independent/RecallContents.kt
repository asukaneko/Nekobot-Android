package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import org.json.JSONObject

internal const val MAX_RECALL_CONTENT_REFS=32

/** Display references only: no duplicate memory/chat body, no diagnostics payload. */
data class RecallContentRef(val kind:String,val id:String,val version:Int,val mode:String,val digest:String,val start:Int,val length:Int) {
    fun valid()=kind in setOf("memory","message")&&id.isNotBlank()&&id.length<=200&&version>0&&
        mode in setOf("full","gist","trace")&&digest.matches(Regex("[0-9a-f]{64}"))&&start in 0..1_000_000&&length in 1..600&&
        (kind!="message"||(mode=="full"&&(id.toLongOrNull()?:0)>0))
    /** IDs may be remapped by backup; version, form, range and both body/fragment are bound. */
    fun digestFor(body:String):String {
        require(start>=0&&length>0&&start.toLong()+length<=body.length)
        return stableDigest(listOf(kind,version,mode,start,length,stableDigest(body),body.substring(start,start+length)).joinToString("\u0000"))
    }
}
internal sealed interface RecallContentPart {
    data class Text(val value:String):RecallContentPart
    data class Status(val value:String,val detail:String=""):RecallContentPart
    data object Deleted:RecallContentPart
    data object Unavailable:RecallContentPart
}
internal data class RecallContentOrigin(val kind:String,val id:String,val version:Int?,val mode:String?)
internal fun unavailableRecallContents(items:List<ChatToolActivity>)=items.map{item->
    if(item.recallOutcome()==RecallDisplayOutcome.FOUND)RecallContentPart.Unavailable
    else RecallContentPart.Status(item.recallOutcome().label,if(item.recallOutcome()==RecallDisplayOutcome.ERROR)item.detail else "")
}.distinct()
internal fun <T> recallReadSnapshot(db:SQLiteDatabase,block:()->T):T {
    if(db.inTransaction())return block()
    if(Build.VERSION.SDK_INT>=35)db.beginTransactionReadOnly()else db.beginTransactionNonExclusive()
    try{val result=block();db.setTransactionSuccessful();return result}finally{db.endTransaction()}
}
internal object RecallContentSources {
    private fun sourceAllowed(db:SQLiteDatabase,role:String,ref:SourceRef)=ref.characterId==role&&CompanionRepository.aiAllowed(db,role,ref.turnId)&&db.rawQuery("SELECT 1 FROM chat_turns t LEFT JOIN message_state u ON u.message_id=t.user_message_id LEFT JOIN message_state a ON a.message_id=t.assistant_message_id WHERE t.friend_id=? AND t.user_message_id=? AND t.source_revision=? AND t.state IN ('COMPLETED','SUPERSEDED') AND COALESCE(u.deleted,0)=0 AND COALESCE(a.deleted,0)=0",arrayOf(role,ref.turnId.toString(),ref.version.toString())).use{it.moveToFirst()}
    private fun version(j:JSONObject)= (j.opt("version") as? Number)?.let{require(it.toDouble()==it.toInt().toDouble());it.toInt()}?:error("version")
    private fun deleted(j:JSONObject)=if(!j.has("userDeleted"))false else j.get("userDeleted") as? Boolean?:error("deleted")
    /** Null means currently deleted; historical body is not queried on that branch. */
    fun memory(db:SQLiteDatabase,role:String,id:String,revision:Int):StoredMemory? {
        val current=db.rawQuery("SELECT friend_id,version,json FROM memory_current WHERE id=?",arrayOf(id)).use{c->
            check(c.moveToFirst()&&c.getString(0)==role&&c.getInt(1)>=revision)
            JSONObject(c.getString(2)).also{check(it.getString("id")==id&&it.getString("character")==role&&version(it)==c.getInt(1))}
        }
        if(deleted(current))return null
        val old=db.rawQuery("SELECT json FROM memory_history WHERE memory_id=? AND version=?",arrayOf(id,revision.toString())).use{check(it.moveToFirst());JSONObject(it.getString(0))}
        check(old.getString("id")==id&&old.getString("character")==role&&version(old)==revision&&!deleted(old))
        return StoredMemory.from(old).also{m->check(m.manual||m.evidence.isNotEmpty());check(m.evidence.all{sourceAllowed(db,role,it.source)})}
    }
    fun plain(m:StoredMemory,mode:String)=when(mode){"full"->MemoryDates.display(m.text,m.eventDate);"gist"->m.gist?.takeIf{it.isNotBlank()}?:m.trace.orEmpty();"trace"->m.trace.orEmpty();else->error("mode")}
    fun message(db:SQLiteDatabase,role:String,id:String,revision:Int):String? {
        val current=db.rawQuery("SELECT COALESCE(s.revision,1),COALESCE(s.deleted,0) FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.id=? AND m.friend_id=?",arrayOf(id,role)).use{c->check(c.moveToFirst());c.getInt(0) to c.getInt(1)}
        if(current.second!=0)return null
        check(current.first>=revision)
        val source=db.rawQuery("SELECT t.user_message_id FROM chat_turns t LEFT JOIN message_state u ON u.message_id=t.user_message_id LEFT JOIN message_state a ON a.message_id=t.assistant_message_id WHERE t.friend_id=? AND (t.user_message_id=? OR t.assistant_message_id=?) AND t.state IN ('COMPLETED','SUPERSEDED') AND COALESCE(u.deleted,0)=0 AND COALESCE(a.deleted,0)=0",arrayOf(role,id,id)).use{check(it.moveToFirst());it.getLong(0)}
        check(CompanionRepository.aiAllowed(db,role,source))
        val saved=db.rawQuery("SELECT body,deleted FROM message_history WHERE message_id=? AND revision=?",arrayOf(id,revision.toString())).use{if(it.moveToFirst()){check(it.getInt(1)==0);it.getString(0)}else null}
        if(saved!=null)return saved
        check(revision==1&&current.first==1) // A never-edited initial message has no history row yet.
        return db.rawQuery("SELECT body FROM messages WHERE id=? AND friend_id=?",arrayOf(id,role)).use{check(it.moveToFirst());it.getString(0)}
    }
}

/** Origins come from the exact selected candidate, including cached query pages. */
internal class RecallContentCapture(private val repo:ProcessingRepository,private val role:String) {
    @Suppress("UNCHECKED_CAST")
    suspend fun capture(raw:List<Map<String,Any>>?,output:Map<String,Any>,origins:List<RecallContentOrigin>):List<RecallContentRef?>?=repo.read{db->recallReadSnapshot(db){
        try {
            val duplicate=(output["visible_duplicates"] as? Number)?.toDouble()?.let{it>0}==true
            val unclipped=output["context_limited"]!=true&&output["budget_limited"]!=true&&output["page_blocked_by_capacity"]!=true&&output["recall_stopped"]!=true
            val finalRows=output["results"] as? List<Map<String,Any>>?:emptyList()
            val covered=mutableSetOf<String>()
            val resolved:List<Map<String,Any>?> = finalRows.map{row->
                if(row["in_reference"]==true&&row["truncated"]==true)null else if(row["in_reference"]==true){
                    val matches=raw.orEmpty().mapNotNull{candidate->
                        val id=candidate["id"] as? String;val body=candidate["content"] as? String
                        if(candidate["message_id"]!=null||id==null||body==null)null else{
                            val offset=body.indexOf('：')+1;val summary=body.substring(offset).take(120)
                            val short=if(id.length>9)id.take(9)+"…"else id
                            if(row["content"]=="$short：$summary（已在上方参考资料中）")candidate+("content" to summary)+("text_offset" to offset)else null
                        }
                    }
                    matches.singleOrNull()?.also{covered+=it["id"].toString()}
                }else row.also{(it["id"] as? String)?.let(covered::add)}
            }
            val restoreHistory=duplicate&&unclipped&&output["status"] in setOf("found","already_in_context")
            var rows=if(restoreHistory)raw.orEmpty().filter{it["message_id"]!=null}+resolved.filter{it?.get("message_id")==null}else resolved
            if(restoreHistory&&raw.orEmpty().any{it["message_id"]==null&&it["id"]?.toString() !in covered})rows=rows+listOf(null)
            check(rows.isNotEmpty())
            val selected=if(rows.size>MAX_RECALL_CONTENT_REFS)rows.take(MAX_RECALL_CONTENT_REFS-1)else rows
            val refs=selected.map{row->if(row==null)null else try{
                val text=(row["snippet"]?:row["content"]) as? String?:error("body")
                val message=row["message_id"]?.toString()
                if(message!=null){
                    val version=origins.single{it.kind=="message"&&it.id==message}.version?:error("version")
                    val body=RecallContentSources.message(db,role,message,version)?:error("deleted")
                    check(stableDigest(body)==row["source_revision"])
                    val start=(row["text_offset"] as? Number)?.toInt()?:error("offset")
                    check(start>=0&&start.toLong()+text.length<=body.length&&body.substring(start,start+text.length)==text)
                    val ref=RecallContentRef("message",message,version,"full","",start,text.length)
                    ref.copy(digest=ref.digestFor(body))
                }else{
                    check(row["source_type"]=="companion_memory")
                    val id=row["id"] as? String?:error("id")
                    val origin=origins.single{it.kind=="memory"&&it.id==id}
                    val version=origin.version?:error("version");val mode=origin.mode?:error("mode")
                    val m=RecallContentSources.memory(db,role,id,version)?:error("deleted")
                    val plain=RecallContentSources.plain(m,mode);val view=if(mode=="full")repo.describe(m)else plain
                    val offset=(row["text_offset"] as? Number)?.toInt()?:0
                    check(text.isNotBlank()&&offset>=0&&offset.toLong()+text.length<=view.length&&view.substring(offset,offset+text.length)==text)
                    val prefix=if(mode=="full")MemoryFacets.categoryLabel(m).length+1 else 0
                    val start=maxOf(offset,prefix)-prefix;val end=minOf(offset+text.length,prefix+plain.length)-prefix
                    check(end>start)
                    val ref=RecallContentRef("memory",id,version,mode,"",start,end-start)
                    ref.copy(digest=ref.digestFor(plain))
                }.also{check(it.valid())}
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}}.distinct()
            if(rows.size>MAX_RECALL_CONTENT_REFS)(refs+null).distinct()else refs
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}
    }}
}

internal suspend fun ProcessingRepository.recallContents(role:String,items:List<ChatToolActivity>):List<RecallContentPart> = read{db->
    val own=!db.inTransaction();var started=false
    try {
        if(own){if(Build.VERSION.SDK_INT>=35)db.beginTransactionReadOnly()else db.beginTransactionNonExclusive();started=true}
        check(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(role)).use{it.moveToFirst()})
        val parts=items.flatMap{item->when(item.recallOutcome()){
            RecallDisplayOutcome.FOUND->{val refs=item.recallRefs;if(refs.isNullOrEmpty())listOf(RecallContentPart.Unavailable)else refs.map{ref->
                if(ref==null)RecallContentPart.Unavailable else
                try{
                    check(ref.valid())
                    val body=if(ref.kind=="memory")RecallContentSources.memory(db,role,ref.id,ref.version)?.let{RecallContentSources.plain(it,ref.mode)}else RecallContentSources.message(db,role,ref.id,ref.version)
                    if(body==null)RecallContentPart.Deleted else{check(ref.digest==ref.digestFor(body));RecallContentPart.Text(body.substring(ref.start,ref.start+ref.length))}
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){RecallContentPart.Unavailable}
            }}
            else->listOf(RecallContentPart.Status(item.recallOutcome().label,if(item.recallOutcome()==RecallDisplayOutcome.ERROR)item.detail else ""))
        }}.distinct()
        if(own)db.setTransactionSuccessful()
        parts
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){unavailableRecallContents(items)}finally{if(started)db.endTransaction()}
}
