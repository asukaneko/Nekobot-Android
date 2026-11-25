package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson

/**
 * 存下来的「当前感觉」和已经记进去的各笔指纹。每次只把新来的一笔加上、把没了或改了的那笔撤回，不从头重算。
 * 放在 relationship-cache: 前缀下，不进备份；恢复备份或换引擎版本后没有它，就从各笔重新记一遍。
 */
/** [contactPrint] 记下已经算进去的各次联系（个数和时间和），撤回消息让其中一次没了时能认出来；旧存档没有它就不查。 */
internal data class RelationFeelingSaved(val engine:Int,val start:String,val ledger:Map<String,String>,val lastContact:Long?,val lastReach:Long?,val cursor:String,val contactPrint:String?=null,
    val merged:Map<String,RelationImpact>?=null,val fulfillmentSources:Map<String,String>?=null,val unattributedMerged:Boolean?=null,val attributionIncomplete:Boolean=false,
    val archivedUndo:Map<String,RelationUndo>?=null,val unverifiableSources:Set<String>?=null)
internal class RelationFeelingResult(val cursor:RelationCursor,val saved:RelationFeelingSaved,val changed:Boolean,val retracted:Int,val added:Int,val rebuilt:Boolean)

internal object RelationFeeling {
    val ENGINE=RelationDecay.ENGINE*100+RelationSnapshotCodec.CODEC
    const val HISTORY_SLACK=60_000L
    fun key(role:String)="relationship-cache:$role:feeling"
    private fun id(ref:SourceRef)="${ref.turnId}:${ref.version}"
    private fun ref(role:String,key:String)=key.split(':').takeIf{it.size==2}?.let{SourceRef(role,it[0].toLong(),it[1].toLong())}
    private fun withAttribution(saved:RelationFeelingSaved,cursor:RelationCursor)=saved.copy(
        merged=cursor.mergedImpacts.mapKeys{id(it.key)},fulfillmentSources=cursor.fulfillmentSources.mapKeys{id(it.key)}.mapValues{id(it.value)},
        unattributedMerged=cursor.unattributedMerged,archivedUndo=cursor.archivedUndo.mapKeys{id(it.key)},
        unverifiableSources=saved.unverifiableSources?.intersect(saved.ledger.keys))
    fun fingerprint(row:RelationInput)=RelationSnapshotCodec.fingerprint(row)
    private fun contactPrint(times:List<Long>)=times.distinct().let{"${it.size}:${it.sum()}"}
    fun read(db:SQLiteDatabase,role:String):RelationFeelingSaved? = db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(role))).use{
        if(!it.moveToFirst())null else runCatching{Gson().fromJson(it.getString(0),RelationFeelingSaved::class.java)?.takeIf{s->s.ledger!=null&&s.cursor!=null&&s.start!=null}}.getOrNull()
    }
    fun write(db:SQLiteDatabase,role:String,saved:RelationFeelingSaved){
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(role));put("value",Gson().toJson(saved))},SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun update(role:String,saved:RelationFeelingSaved?,parameters:RelationParameters,initial:RelationParameters,start:RelationStart,inputs:List<RelationInput>,
        contacts:List<Long>,reachOuts:List<Long>,changes:List<RelationChange>,eventTime:Long,
        printer:(RelationInput)->String=::fingerprint,decode:(String)->RelationCursor?={RelationSnapshotCodec.decode(it,start,parameters)}):RelationFeelingResult {
        val effective=relationEffectiveInputs(inputs,eventTime)
        val prints=effective.associate{id(it.source) to printer(it)}
        val lastContact=contacts.filter{it<=eventTime}.maxOrNull();val lastReach=reachOuts.filter{it<=eventTime}.maxOrNull()
        val print=contactPrint(contacts.filter{it<=eventTime})
        val restored=saved?.takeIf{it.engine==ENGINE&&it.start==start.name}?.let{decode(it.cursor)}
        if(saved==null||restored==null){
            // 第一次（或缓存没了）：按时间把各笔记一遍，得到当前感觉。
            val cache=RelationProjectionCache(trackMerged=true)
            cache.project(initial,inputs,eventTime,start,contacts,changes,reachOuts,emptyList(),"",eventTime,readOnly=true)
            val cursor=cache.forecast()?:RelationCursor(start,parameters)
            cursor.parameters=parameters
            cursor.trackMerged=true
            return RelationFeelingResult(cursor,withAttribution(RelationFeelingSaved(ENGINE,start.name,prints,lastContact,lastReach,RelationSnapshotCodec.encode(cursor),print,unverifiableSources=emptySet()),cursor),true,0,effective.size,true)
        }
        val cursor=restored;cursor.parameters=parameters
        cursor.trackMerged=true
        cursor.mergedImpacts.clear();saved.merged?.forEach{(key,value)->ref(role,key)?.let{cursor.mergedImpacts[it]=value}}
        cursor.archivedUndo.clear();saved.archivedUndo?.forEach{(key,value)->ref(role,key)?.let{cursor.archivedUndo[it]=value}}
        cursor.fulfillmentSources.clear()
        if(saved.fulfillmentSources!=null)saved.fulfillmentSources.forEach{(key,value)->ref(role,key)?.let{source->ref(role,value)?.let{cursor.fulfillmentSources[source]=it}}}
        else cursor.undo.forEach{(source,u)->u.fulfilled?.let{cursor.fulfillmentSources[source]=it}}
        cursor.unattributedMerged=saved.unattributedMerged?:cursor.impacts.keys.any{it.turnId<0}
        val legacySources=saved.unverifiableSources?:saved.ledger.keys
        val gone=saved.ledger.filter{(k,v)->prints[k]!=v}.keys.mapNotNull{k->k.split(':').takeIf{it.size==2}?.let{SourceRef(role,it[0].toLong(),it[1].toLong())}}
        // Old anonymous buckets cannot be split accurately. Keep the old numbers and unresolved
        // ledger intact rather than inventing a deduction or replaying unrelated experiences.
        if(saved.attributionIncomplete||gone.any{source->
            id(source) in legacySources&&(
                cursor.unattributedMerged&&source !in cursor.impacts&&source !in cursor.mergedImpacts||
                cursor.undo[source]==null&&cursor.archivedUndo[source]==null&&
                    (cursor.impacts[source]?:cursor.mergedImpacts[source])?.at?.let{eventTime-it>RelationDecay.UNDO_DAYS*RelationshipProjector.DAY}==true)||
                (cursor.undo[source]?:cursor.archivedUndo[source])?.adjusts.orEmpty().any{a->id(a.target) in saved.ledger&&id(a.target) in legacySources&&a.target !in cursor.impacts&&a.target !in cursor.mergedImpacts}
        }){
            val next=withAttribution(saved.copy(attributionIncomplete=true,unverifiableSources=legacySources),cursor)
            return RelationFeelingResult(cursor,next,!saved.attributionIncomplete,0,0,false)
        }
        gone.sortedByDescending{it.turnId}.forEach{cursor.retract(it)}
        val fresh=effective.filter{saved.ledger[id(it.source)]!=prints[id(it.source)]}
        data class Step(val at:Long,val kind:Int,val order:Long,val row:RelationInput?=null)
        val steps=(contacts.filter{it<=eventTime&&it>(saved.lastContact?:Long.MIN_VALUE)}.distinct().map{Step(it,0,0)}+
            reachOuts.filter{it<=eventTime&&it>(saved.lastReach?:Long.MIN_VALUE)}.map{Step(it,1,0)}+
            fresh.map{Step(it.at,2,it.source.turnId,it)}).sortedWith(compareBy<Step>{it.at}.thenBy{it.kind}.thenBy{it.order})
        for(step in steps)when(step.kind){
            0->if(cursor.contact.let{it==null||step.at>=it})cursor.contact(step.at)
            1->cursor.reachOut(step.at)
            else->cursor.apply(step.row!!)
        }
        // 撤回的消息当没发生过：它带来的那次「联系过」也退掉。只有想念和最后联系时间跟联系有关，
        // 所以只把这两样换成不含那次联系重新走一遍的结果，其余各笔照旧只退那一笔、不重算。
        val contactGone=saved.contactPrint!=null&&saved.lastContact!=null&&contactPrint(contacts.filter{it<=saved.lastContact})!=saved.contactPrint
        if(contactGone){
            val replay=RelationProjectionCache().also{it.project(initial,inputs,eventTime,start,contacts,changes,reachOuts,emptyList(),"",eventTime,readOnly=true)}.forecast()
            if(replay!=null){
                // 存下的感觉停在哪一刻，就把重走的结果也走到那一刻再对齐（不把存下的往后推）。
                val time=maxOf(cursor.at?:eventTime,replay.at?:Long.MIN_VALUE)
                cursor.advance(time);replay.advance(time)
                cursor.missing=replay.missing;cursor.contact=replay.contact
            }
        }
        cursor.compact()
        val changed=gone.isNotEmpty()||steps.isNotEmpty()||saved.ledger.keys!=prints.keys||contactGone||saved.contactPrint!=print||saved.merged==null||saved.fulfillmentSources==null||saved.unattributedMerged==null||saved.archivedUndo==null||saved.unverifiableSources==null
        val next=withAttribution(saved.copy(ledger=prints,lastContact=if(contactGone)lastContact else listOfNotNull(saved.lastContact,lastContact).maxOrNull(),lastReach=listOfNotNull(saved.lastReach,lastReach).maxOrNull(),unverifiableSources=legacySources,
            cursor=if(changed)RelationSnapshotCodec.encode(cursor)else saved.cursor,contactPrint=print),cursor)
        return RelationFeelingResult(cursor,next,changed,gone.size,fresh.size,false)
    }
}
