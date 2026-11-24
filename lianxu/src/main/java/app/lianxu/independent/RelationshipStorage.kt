package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson

/** Versioned evidence only; no authoritative cumulative score. Included in complete role backup. */
internal data class ParameterReceipt(val source:SourceRef,val old:RelationParameters,val undone:Boolean=false,val oldLabel:String?=null,val keys:Set<String>?=null)
internal fun RelationHistory.receiptKeys(receipt:ParameterReceipt):Set<String> {
    val oldJson=Gson().toJsonTree(receipt.old).asJsonObject
    return receipt.keys?:changes.firstOrNull{it.old==receipt.old}?.let{change->val next=Gson().toJsonTree(change.new).asJsonObject;RelationParameterRules.storedProposalKeys.filter{oldJson[it]!=next[it]}.toSet()}.orEmpty()
}
internal fun RelationHistory.activeParameterReceipts()=receipts.orEmpty().filter{receipt->
    // Old receipts did not identify their changed keys. Consider every matching change,
    // not just the first: repeated returns to the same old parameters are ambiguous.
    val oldJson=Gson().toJsonTree(receipt.old).asJsonObject
    val keys=receipt.keys?:changes.filter{it.old==receipt.old}.flatMap{change->
        val next=Gson().toJsonTree(change.new).asJsonObject
        RelationParameterRules.storedProposalKeys.filter{oldJson[it]!=next[it]}
    }.toSet()
    keys.isNotEmpty()&&keys.all{it in RelationParameterRules.modelAllowed}
}
internal fun RelationHistory.reverseParameters(source:SourceRef,current:RelationParameters,label:String,at:Long,afterMessage:Long):RelationHistory? {
    val receipt=receipts.orEmpty().firstOrNull{it.source==source&&!it.undone}?:return null
    val oldJson=Gson().toJsonTree(receipt.old).asJsonObject
    val changed=receiptKeys(receipt)
    val restored=RelationParameterRules.apply(current,changed.associateWith{oldJson[it].asString})
    return copy(changes=changes+RelationChange(at,current,restored,receipt.oldLabel?:label,afterMessage),receipts=receipts.orEmpty().map{if(it.source==source)it.copy(undone=true)else it})
}
internal data class RelationHistory(val version:Int=1,val changes:List<RelationChange> = emptyList(),val receipts:List<ParameterReceipt> = emptyList(),
    val corrections:List<RelationCorrection> = emptyList(),val programInputs:List<RelationInput> = emptyList(),val acceptedProposals:List<SourceRef> = emptyList())
/** Retain old records for history/export, but stop applying automatic silence judgments. Model inputs live separately in companion_sources. */
internal fun RelationHistory.activeProgramInputs()=programInputs.filter{it.ignored==null}
/** Rebuild only a cache that actually included these retired records; replay removes their indirect effects too. */
internal fun RelationHistory.withoutAutomaticSilence(saved:RelationFeelingSaved?)=saved?.takeUnless{s->
    programInputs.any{it.ignored!=null&&"${it.source.turnId}:${it.source.version}" in s.ledger}
}
internal object RelationStorage {
    fun key(role:String)="relationship-history:$role"
    fun lastMessage(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM messages WHERE friend_id=?",arrayOf(role)).use{it.moveToFirst();it.getLong(0)}
    fun read(db:SQLiteDatabase,role:String):RelationHistory = db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(role))).use{
        if(!it.moveToFirst())RelationHistory()else runCatching{Gson().fromJson(it.getString(0),RelationHistory::class.java).also{h->require(h.version==1)}.let{h->
            h.copy(changes=h.changes.orEmpty().map{c->c.copy(old=RelationParameterRules.clamp(c.old),new=RelationParameterRules.clamp(c.new))},corrections=h.corrections.orEmpty(),programInputs=h.programInputs.orEmpty(),acceptedProposals=h.acceptedProposals.orEmpty())
        }}.getOrDefault(RelationHistory())
    }
    fun write(db:SQLiteDatabase,role:String,history:RelationHistory){
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(role));put("value",Gson().toJson(history))},SQLiteDatabase.CONFLICT_REPLACE)
    }
}
internal class RelationDatabaseCache(db:SQLiteDatabase,role:String) {
    val parsed=mutableMapOf<SourceRef,Pair<String,RelationInput?>>()
    val prints=mutableMapOf<SourceRef,Pair<RelationInput,String>>()
    var feelingRaw:String?=null
    var feeling:RelationCursor?=null
}
internal object RelationCaches {
    private val databases=java.util.WeakHashMap<SQLiteDatabase,MutableMap<String,RelationDatabaseCache>>()
    @Synchronized fun get(db:SQLiteDatabase,role:String)=databases.getOrPut(db){mutableMapOf()}.getOrPut(role){RelationDatabaseCache(db,role)}
}
internal suspend fun CompanionRepository.relationshipChangeHistory(role:String)=ProcessingRepository(store).read{RelationStorage.read(it,role).changes}
internal suspend fun CompanionRepository.relationshipCorrectionHistory(role:String)=ProcessingRepository(store).read{RelationStorage.read(it,role).corrections}
internal suspend fun CompanionRepository.relationshipMood(role:String,at:Long=clock())=RelationshipSummary.mood(relationship(role,at).state)
internal suspend fun CompanionRepository.relationshipExplanations(role:String,at:Long=clock())=relationship(role,at).state.explanations
internal suspend fun CompanionRepository.changeRelationshipCustom(role:String,custom:RelationCustom)=
    changeRelationshipParameters(role,custom.parameters,"自定义（基于${custom.base.label}）")

internal suspend fun CompanionRepository.changeRelationshipParameters(role:String,parameters:RelationParameters,label:String,undoSource:SourceRef?=null)=ProcessingRepository(store).tx{db->
    val view=relationship(db,role);val history=RelationStorage.read(db,role);val clamped=RelationParameterRules.clamp(parameters)
    val updated=if(undoSource!=null)history.reverseParameters(undoSource,view.parameters,label,clock(),RelationStorage.lastMessage(db,role))?:return@tx
        else history.copy(changes=history.changes+RelationChange(clock(),view.parameters,clamped,label,RelationStorage.lastMessage(db,role)))
    RelationStorage.write(db,role,updated)
    CompanionRepository.bump(db,role);relationship(db,role);CompanionRuntime.changed()
}
internal suspend fun CompanionRepository.relationshipParameterProposals(role:String)=ProcessingRepository(store).read{db->
    val accepted=RelationStorage.read(db,role).acceptedProposals
    db.rawQuery("SELECT source_id,revision,proposal FROM companion_sources WHERE role=? AND committed=1",arrayOf(role)).use{c->buildList{
        while(c.moveToNext()){
            val source=SourceRef(role,c.getLong(0),c.getLong(1));if(!refCurrent(db,source)||source in accepted)continue
            val p=runCatching{Gson().fromJson(c.getString(2),ExperienceProposal::class.java).parameterProposal}.getOrNull()?:continue
            if(p.source==source&&RelationParameterRules.activeProposal(p.values))add(p)
        }
    }}
}
/** Both explicit ask and in-chat suggestions are inert until this method is called. */
internal suspend fun CompanionRepository.confirmRelationshipParameters(role:String,proposal:RelationParameterProposal,use:Boolean)=ProcessingRepository(store).tx{db->
    if(!RelationParameterRules.activeProposal(proposal.values))return@tx false
    val h=RelationStorage.read(db,role)
    if(proposal.source!=null&&(proposal.source.characterId!=role||!refCurrent(db,proposal.source)||proposal.source in h.acceptedProposals))return@tx false
    val view=relationship(db,role)
    val updated=runCatching{RelationParameterRules.apply(view.parameters,proposal.values)}.getOrNull()?:return@tx false
    RelationStorage.write(db,role,h.copy(changes=if(use)h.changes+RelationChange(clock(),view.parameters,updated,view.label,RelationStorage.lastMessage(db,role))else h.changes,
        acceptedProposals=h.acceptedProposals+listOfNotNull(proposal.source),receipts=h.receipts.orEmpty()+if(use&&proposal.source!=null)listOf(ParameterReceipt(proposal.source,view.parameters,oldLabel=view.label,keys=proposal.values.keys))else emptyList()))
    CompanionRepository.bump(db,role);relationship(db,role);CompanionRuntime.changed();true
}
internal suspend fun CompanionRepository.relationshipParameterReceipts(role:String)=ProcessingRepository(store).read{RelationStorage.read(it,role).activeParameterReceipts()}
