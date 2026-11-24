package app.lianxu.independent

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

/** Position is replay order; sequence is an insertion ID and is never reused after rollback. */
internal data class RelationSnapshotHeader(val sequence:Long,val position:Int,val event:String,val at:Long,
    val engine:Int,val base:String,val prefix:String,val template:String,val checksum:String)
internal interface RelationSnapshotStore {
    fun headers():List<RelationSnapshotHeader>
    fun complete(headers:List<RelationSnapshotHeader>):Boolean
    fun payload(sequence:Long):String?
    fun truncate(position:Int)
    fun append(header:RelationSnapshotHeader,payload:String)
    fun marker():String?=null
    fun prune(now:Long) {}
}
internal class MemoryRelationSnapshots:RelationSnapshotStore {
    val rows=mutableListOf<Pair<RelationSnapshotHeader,String>>()
    private var sequence=0L
    private var head=""
    override fun headers()=rows.map{it.first}
    override fun payload(sequence:Long)=rows.firstOrNull{it.first.sequence==sequence}?.second
    override fun complete(headers:List<RelationSnapshotHeader>)=head==RelationSnapshotCodec.head(headers)
    override fun truncate(position:Int){rows.removeAll{it.first.position>position};head=RelationSnapshotCodec.head(headers())}
    override fun append(header:RelationSnapshotHeader,payload:String){rows.add(header.copy(sequence=++sequence) to payload);head=RelationSnapshotCodec.head(headers())}
    override fun prune(now:Long){
        val keep=RelationSnapshotRetention.positions(rows.map{it.first.position to it.first.at},now)
        rows.removeAll{it.first.position !in keep};head=RelationSnapshotCodec.head(headers())
    }
}
internal object RelationSnapshotRetention {
    const val RECENT_COUNT=50 // 每轮快照最近 50 份完整保留。
    const val DAILY_DAYS=90L // 本地日期窗口，另留一份窗口之前的起点。
    const val WARN_BYTES=64*1024 // 单份快照诊断阈值，不是截断阈值。
    fun day(at:Long)=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    fun positions(rows:List<Pair<Int,Long>>,now:Long):Set<Int> {
        val recent=rows.takeLast(RECENT_COUNT).map{it.first}.toSet()
        val cutoff=day(now).minusDays(DAILY_DAYS-1)
        val older=rows.filter{it.first !in recent}
        return recent+older.filter{!day(it.second).isBefore(cutoff)}.groupBy{day(it.second)}.values.map{it.last().first}+
            listOfNotNull(older.lastOrNull{day(it.second).isBefore(cutoff)}?.first)
    }
}

internal object RelationSnapshotCodec {
    const val CODEC=5
    const val MAX_BYTES=256*1024
    private val gson=GsonBuilder().enableComplexMapKeySerialization().serializeNulls().create()
    fun hash(text:String):String {
        val hex="0123456789abcdef";val bytes=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(64){bytes.forEach{val n=it.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15])}}
    }
    fun fingerprint(value:Any)=hash(gson.toJson(value))
    fun head(headers:List<RelationSnapshotHeader>)=headers.lastOrNull()?.let{"${headers.size}:${it.sequence}:${it.prefix}"}?:""
    fun checksum(header:RelationSnapshotHeader,raw:String)=hash(raw+gson.toJson(header.copy(sequence=0,checksum="")))
    // Parameters are restored from authoritative template history. In particular no free-text tone
    // or source input/body is duplicated in a snapshot. Everything else in the cursor is required.
    // Keep this DTO and its SerializedName annotations when enabling R8/minify.
    private data class Payload(
        @SerializedName("role") val role:String,
        @SerializedName("time") val time:List<Long?>,
        @SerializedName("values") val values:List<Double>,
        @SerializedName("counts") val counts:List<Int>,
        @SerializedName("impacts") val impacts:Map<String,List<Number>>,
        @SerializedName("pending") val pending:Map<String,List<Number>>,
        @SerializedName("causes") val causes:Map<String,List<List<String>>>,
        @SerializedName("promises") val promises:Map<String,List<String>>,
        @SerializedName("explanations") val explanations:Map<String,List<String>>,
        @SerializedName("excuses") val excuses:Map<String,Int>,
        @SerializedName("positives") val positives:Map<Long,Int>,
        @SerializedName("apologized") val apologized:List<String>,
        @SerializedName("cares") val cares:List<Long>,
        @SerializedName("bigRecent") val bigRecent:Map<String,List<Long>>,
        @SerializedName("fulfilledAt") val fulfilledAt:Map<String,Long>,
        @SerializedName("repairPairs") val repairPairs:List<List<String>>,
        @SerializedName("undo") val undo:Map<String,RelationUndo>,
        @SerializedName("moments") val moments:List<RelationMoment>)
    private val kinds=listOf(null,"V","B","ignored","gap")
    private fun key(ref:SourceRef)="${ref.turnId}:${ref.version}"
    private fun payload(c:RelationCursor):Payload {
        val role=(c.impacts.keys+c.pending.keys+c.causes.values.map{it.source}+c.promises.keys+c.explanations.keys+c.undo.keys+c.moments.map{it.source}).firstOrNull()?.characterId.orEmpty()
        return Payload(role,listOf(c.at,c.contact,c.nextCooling),listOf(c.baseTrust,c.baseCloseness,c.peak,c.missing),listOf(c.fulfillments,c.applied),
            c.impacts.mapKeys{key(it.key)}.mapValues{(_,v)->listOf<Number>(v.loss,v.tension,v.disappointment,v.happy,v.unhappy,v.hurt,v.low,v.at,kinds.indexOf(v.kind),if(v.big)1 else 0,v.grudgeSlow,v.grudgeFloor,v.trust,v.closeness,if(v.lasting)1 else 0)},
            c.pending.mapKeys{key(it.key)}.mapValues{(_,v)->listOf<Number>(v.loss,if(v.big)1 else 0)},
            c.causeHistory.mapValues{(_,list)->list.map{v->listOf(key(v.source),v.at.toString(),v.direction)}},
            c.promises.mapKeys{key(it.key)}.mapValues{(_,v)->listOf(v.at.toString(),v.fulfilledBy?.let(::key).orEmpty())},
            c.explanations.mapKeys{key(it.key)}.mapValues{(_,v)->listOf(key(v.source),v.category,v.ratio.toString(),v.at.toString())},
            c.excuses,c.positives,c.apologized.map(::key),c.cares,c.bigRecent,c.fulfilledAt.mapKeys{key(it.key)},c.repairPairs.map{listOf(key(it.basis),key(it.target))},c.undo.mapKeys{key(it.key)},c.moments.toList())
    }
    fun encode(cursor:RelationCursor):String=gson.toJson(payload(cursor))
    fun decode(raw:String,start:RelationStart,parameters:RelationParameters):RelationCursor?=try {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        val tree=JsonParser.parseString(raw).asJsonObject
        val p=gson.fromJson(tree,Payload::class.java)
        fun ref(s:String):SourceRef {val parts=s.split(':');require(parts.size==2);return SourceRef(p.role,parts[0].toLong(),parts[1].toLong())}
        val cursor=RelationCursor(start,parameters).also{c->
            require(p.time.size==3&&p.values.size==4&&p.counts.size==2)
            c.at=p.time[0];c.contact=p.time[1];c.nextCooling=p.time[2]
            c.baseTrust=p.values[0];c.baseCloseness=p.values[1];c.peak=p.values[2];c.missing=p.values[3];c.fulfillments=p.counts[0];c.applied=p.counts[1]
            p.impacts.forEach{(k,v)->require(v.size==15&&v.all{it.toDouble().isFinite()}&&v.take(7).all{it.toDouble() in 0.0..1.0}&&v.subList(12,14).all{it.toDouble() in -10.0..10.0});c.impacts[ref(k)]=RelationImpact(v[0].toDouble(),v[1].toDouble(),v[2].toDouble(),v[3].toDouble(),v[4].toDouble(),v[5].toDouble(),v[6].toDouble(),v[7].toLong(),kinds[v[8].toInt()],v[9].toInt()==1,v[10].toDouble(),v[11].toDouble(),v[12].toDouble(),v[13].toDouble(),v[14].toInt()==1)}
            p.pending.forEach{(k,v)->require(v.size==2&&v[0].toDouble().isFinite()&&v[0].toDouble() in 0.0..1.0&&v[1].toDouble() in listOf(0.0,1.0));c.pending[ref(k)]=RelationPending(v[0].toDouble(),v[1].toInt()==1)}
            p.causes.forEach{(k,list)->require(list.isNotEmpty()&&list.size<=RelationCursor.CAUSE_HISTORY);c.causeHistory[k]=list.map{v->require(v.size==3);RelationCause(ref(v[0]),v[1].toLong(),v[2])}.toMutableList();c.causes[k]=c.causeHistory.getValue(k).last()}
            p.promises.forEach{(k,v)->require(v.size==2);val r=ref(k);c.promises[r]=RelationPromise(r,v[0].toLong(),v[1].takeIf{it.isNotEmpty()}?.let(::ref))}
            p.explanations.forEach{(k,v)->require(v.size==4);c.explanations[ref(k)]=RelationExplanation(ref(v[0]),ref(k),v[1],v[2].toDouble(),v[3].toLong())}
            p.repairPairs.forEach{require(it.size==2);val basis=ref(it[0]);val target=ref(it[1]);require(basis.turnId>0&&basis.version>0&&target.turnId>0&&target.version>0);c.repairPairs.add(RelationRepairPair(basis,target))}
            c.excuses.putAll(p.excuses);c.positives.putAll(p.positives);c.apologized.addAll(p.apologized.map(::ref));c.cares.addAll(p.cares)
            p.bigRecent.forEach{(k,v)->c.bigRecent[k]=v.toMutableList()};p.fulfilledAt.forEach{(k,v)->c.fulfilledAt[ref(k)]=v}
            p.undo.forEach{(k,v)->c.undo[ref(k)]=v};c.moments.addAll(p.moments.map{it.copy(source=SourceRef(p.role,it.source.turnId,it.source.version))})
        }
        // Reject missing/unknown fields instead of accepting Gson's zero/null defaults.
        require(JsonParser.parseString(encode(cursor))==tree)
        require(listOf(cursor.peak,cursor.missing).all{it.isFinite()&&it in 0.0..1.0}&&listOf(cursor.baseTrust,cursor.baseCloseness).all{it.isFinite()})
        cursor.copy().state() // Also validates the required collections before use.
        cursor
    }catch(e:Exception){if(e is java.util.concurrent.CancellationException)throw e;null}
}
