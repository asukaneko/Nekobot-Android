package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.google.gson.Gson

internal data class RelationshipView(val template:RelationTemplate,val start:RelationStart,val inputs:List<RelationInput>,val state:RelationState,val summary:String,
    val parameters:RelationParameters=template.parameters,val label:String=template.label,val modelMaterial:String="",val attributionIncomplete:Boolean=false)

/** The same valid, finished history used by memory: stopping does not erase a real user message. */
internal fun CompanionRepository.relationshipContacts(db:SQLiteDatabase,role:String,at:Long=clock()):List<Pair<SourceRef,Long>> {
    val repo=ProcessingRepository(store)
    return db.rawQuery("SELECT t.user_message_id,t.source_revision,m.created FROM chat_turns t JOIN messages m ON m.id=t.user_message_id WHERE t.friend_id=? AND t.state IN ('COMPLETED','SUPERSEDED') AND m.created<=? ORDER BY m.created,t.user_message_id",arrayOf(role,at.toString())).use{c->buildList{
        while(c.moveToNext()){
            val source=SourceRef(role,c.getLong(0),c.getLong(1))
            if(repo.sourceValid(db,source))add(source to c.getLong(2))
        }
    }}
}

/** The input lives in the existing versioned, backed-up companion_sources row. No score column. */
internal fun CompanionRepository.relationshipInputs(db:SQLiteDatabase,role:String,includeInvalid:Boolean=false):List<RelationInput> =
    db.rawQuery("SELECT source_id,revision,proposal FROM companion_sources WHERE role=? AND committed=1 ORDER BY source_id,revision",arrayOf(role)).use{c->buildList{
        while(c.moveToNext()){
            val source=SourceRef(role,c.getLong(0),c.getLong(1))
            val cache=RelationCaches.get(db,role);val raw=c.getString(2);val prior=cache.parsed[source]
            val row=(if(prior?.first==raw)prior.second else runCatching{Gson().fromJson(raw,ExperienceProposal::class.java).relationInput}.getOrNull().also{cache.parsed[source]=raw to it})?:continue
            if(row.source!=source||!RelationInputContract.validStored(row))continue
            val basis=row.repairBasis
            val effective=if(basis!=null&&(!refCurrent(db,basis)||!CompanionRepository.aiAllowed(db,role,basis.turnId)))row.copy(repairs=null,repairCompleted=false)else row
            if(includeInvalid||refCurrent(db,source)&&row.status=="valid")add(effective)
        }
    }}

internal fun CompanionRepository.relationship(db:SQLiteDatabase,role:String,at:Long=clock()):RelationshipView {
    // Also serialize first-read rebuilds. A query-time decay is never saved as an event snapshot.
    if(!db.inTransaction()){
        return db.transaction { relationship(db,role,at) }
    }
    val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(role))).use{if(it.moveToFirst())RolePromptStore.decode(it.getString(0))else RolePrompts()}
    val template=RelationTemplate.named(prompts.relationTemplate)?:RelationTemplate.STEADY
    val start=runCatching{RelationStart.valueOf(prompts.relationStart)}.getOrDefault(RelationStart.NEW)
    val rows=relationshipInputs(db,role)
    val history=RelationStorage.read(db,role)
    val changes=history.changes.filter{it.at<=at}
    val initial=history.changes.firstOrNull()?.old?:template.parameters
    val parameters=changes.lastOrNull()?.new?:initial
    val program=history.activeProgramInputs().filter{row->CompanionRepository.aiAllowed(db,role,row.source.turnId)&&db.rawQuery("SELECT 1 FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.friend_id=? AND m.id=? AND COALESCE(s.deleted,0)=0",arrayOf(role,row.source.turnId.toString())).use{it.moveToFirst()}}
    val contacts=relationshipContacts(db,role,at).map{it.second}
    val time=clock()
    val inputs=rows+program;val contactTimes=contacts.ifEmpty{rows.map{it.at}};val reach=relationshipSent(db,role).map{it.at}
    val cache=RelationCaches.get(db,role)
    val state=if(at<time-RelationFeeling.HISTORY_SLACK){
        // 查过去某一刻：临时按各笔算一遍，不动存下的当前感觉。
        RelationProjectionCache().project(initial,inputs,at,start,contactTimes,history.changes,reach,history.corrections,template.name,at,readOnly=true)
    } else {
        // 存下的当前感觉：只加新来的、撤回没了或改了的那几笔；未来时刻只往后淡，不存。
        val saved=history.withoutAutomaticSilence(RelationFeeling.read(db,role))
        val result=RelationFeeling.update(role,saved,parameters,initial,start,inputs,contactTimes,reach,history.changes,minOf(at,time),
            printer={row->cache.prints[row.source]?.takeIf{it.first==row}?.second?:RelationFeeling.fingerprint(row).also{cache.prints[row.source]=row to it}},
            decode={raw->if(raw==cache.feelingRaw)cache.feeling?.copy()?.also{it.parameters=parameters}else RelationSnapshotCodec.decode(raw,start,parameters)})
        if(result.changed)RelationFeeling.write(db,role,result.saved)
        if(result.rebuilt)Diagnostics.event("relation.feeling.rebuilt",mapOf("inputs" to result.added))
        else if(result.retracted>0)Diagnostics.event("relation.feeling.retracted",mapOf("count" to result.retracted,"added" to result.added))
        cache.feelingRaw=result.saved.cursor;cache.feeling=result.cursor.copy()
        result.cursor.copy().also{it.advance(at);it.compact()}.state()
    }
    fun quote(ref:SourceRef):String?=if(!refCurrent(db,ref)&&program.none{it.source==ref})null else db.rawQuery("SELECT body FROM messages WHERE friend_id=? AND id=?",arrayOf(role,ref.turnId.toString())).use{if(it.moveToFirst())it.getString(0)else null}
    val attributionIncomplete=RelationFeeling.read(db,role)?.attributionIncomplete==true
    val summary=(if(attributionIncomplete)RelationshipPrompt.UNVERIFIED+"\n" else "")+RelationshipSummary.render(state,at,store.persona(db).addressee(),::quote)
    val modelMaterial=if(attributionIncomplete)RelationshipPrompt.UNVERIFIED else RelationshipPrompt.render(state,at,::quote)
    val label=changes.lastOrNull()?.label?:template.label
    val activeTemplate=RelationTemplate.named(label)?:RelationTemplate.entries.firstOrNull{label=="自定义（基于${it.label}）"}?:template
    return RelationshipView(activeTemplate,start,rows,state,summary,parameters,label,modelMaterial,attributionIncomplete)
}
internal suspend fun CompanionRepository.relationship(role:String,at:Long=clock())=ProcessingRepository(store).read{relationship(it,role,at)}

internal fun CompanionRepository.relationshipForecast(db:SQLiteDatabase,role:String):Pair<RelationshipView,(Long)->RelationState> {
    val view=relationship(db,role)
    val cursor=RelationCaches.get(db,role).feeling?.copy()
    return view to {time->if(cursor!=null&&time>=(cursor.at?:Long.MIN_VALUE))cursor.copy().also{it.advance(time);it.compact()}.state()else relationship(db,role,time).state}
}
internal suspend fun CompanionRepository.relationshipHistory(role:String)=ProcessingRepository(store).read{relationshipInputs(it,role,true)}

/** User correction affects relationship interpretation only; source text and all history remain. */
internal suspend fun CompanionRepository.correctRelationshipRecord(source:SourceRef,voided:Boolean=true)=ProcessingRepository(store).tx{db->
    val raw=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=? AND committed=1",arrayOf(source.characterId,source.turnId.toString(),source.version.toString())).use{if(it.moveToFirst())it.getString(0)else null}?:return@tx false
    val p=Gson().fromJson(raw,ExperienceProposal::class.java);val row=p.relationInput?:return@tx false
    val status=if(voided)"disputed"else"valid";if(row.status==status)return@tx false
    db.execSQL("UPDATE companion_sources SET proposal=? WHERE role=? AND source_id=? AND revision=?",arrayOf<Any>(Gson().toJson(p.copy(relationInput=row.copy(status=status))),source.characterId,source.turnId,source.version))
    val h=RelationStorage.read(db,source.characterId);val correction=RelationCorrection(source,clock(),voided)
    RelationStorage.write(db,source.characterId,h.copy(corrections=h.corrections+correction))
    Diagnostics.event("relation.record.corrected",mapOf("message" to source.turnId,"at" to correction.at,"voided" to voided))
    CompanionRepository.bump(db,source.characterId);relationship(db,source.characterId);true
}
internal suspend fun CompanionRepository.restoreRelationshipRecord(source:SourceRef)=correctRelationshipRecord(source,false)
internal suspend fun CompanionRepository.undoRelationshipCorrection(source:SourceRef)=correctRelationshipRecord(source,false)
internal suspend fun CompanionRepository.changeRelationshipTemplate(role:String,template:RelationTemplate)=changeRelationshipParameters(role,template.parameters,template.label)
