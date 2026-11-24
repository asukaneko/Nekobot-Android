package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class SemanticFact(val ref:String,val memory:StoredMemory,val operation:String?=null,val kind:String="OLD")
internal data class SemanticPair(val id:String,val left:String,val right:String,val kind:String)
internal enum class SemanticVerdict { DUPLICATE, DIFFERENT, SUPPLEMENT, CORRECTION, UNCERTAIN }
internal data class SemanticDecision(val pair:SemanticPair,val verdict:SemanticVerdict,val reason:String)
internal data class SemanticAction(val duplicate:String?=null,val candidate:String?=null,val target:String?=null)

/** Scores only choose questions. Only complete, directly judged pairs can authorize a merge. */
internal object MemorySemanticPlan {
    const val THRESHOLD=.90
    fun sameShape(a:StoredMemory,b:StoredMemory)=MemoryEvidenceRules.sameFact(a.copy(text=""),b.copy(text=""))
    fun pairs(facts:List<SemanticFact>,vectors:Map<String,DoubleArray>,check:()->Unit={}):List<SemanticPair>{
        val parent=facts.associate{it.ref to it.ref}.toMutableMap()
        fun root(id:String):String{var x=id;while(parent.getValue(x)!=x)x=parent.getValue(x);return x}
        fun allowed(a:SemanticFact,b:SemanticFact)=a.memory.friendId==b.memory.friendId&&a.memory.about==b.memory.about&&a.memory.id!=b.memory.id
        val fresh=facts.filter{it.operation!=null}
        for(a in fresh)for(b in facts){
            check()
            if(a.ref==b.ref||!allowed(a,b))continue
            val av=vectors[a.ref]?:continue;val bv=vectors[b.ref]?:continue
            if(av.size==bv.size&&MemoryVectorIndex.cosine(av,bv)>=THRESHOLD)parent[root(a.ref)]=root(b.ref)
        }
        // Ask direct questions inside each suspicious group, including low-score bridge endpoints.
        // A~B and B~C must never silently imply A=C.
        val result=mutableListOf<SemanticPair>()
        for((i,a)in fresh.withIndex())for(b in facts){
            check()
            if(a.ref==b.ref||!allowed(a,b)||root(a.ref)!=root(b.ref))continue
            if(b.operation!=null&&fresh.indexOf(b)>=i)continue
            result+=SemanticPair("P${result.size+1}",a.ref,b.ref,if(b.operation==null)"NEW_OLD"else"NEW_NEW")
        }
        return result
    }
    fun payload(batch:String,pairs:List<SemanticPair>,facts:Map<String,SemanticFact>):JsonObject {
        val refs=pairs.flatMap{listOf(it.left,it.right)}.toSet()
        return JsonObject().apply{
            addProperty("batch_id",batch)
            add("facts",JsonArray().apply{refs.forEach{ref->val f=facts.getValue(ref);add(JsonObject().apply{
                addProperty("ref",ref);addProperty("kind",f.kind);addProperty("text",f.memory.text)
                addProperty("about",f.memory.about?.name);addProperty("category",f.memory.category.name);addProperty("life",f.memory.life.name)
                addProperty("event_date",f.memory.eventDate);addProperty("date_expression",f.memory.dateExpression)
                addProperty("due_date",f.memory.dueDate);addProperty("birthday",f.memory.annualMonthDay)
                add("sources",JsonArray().apply{if(f.operation!=null)f.memory.evidence.forEach{e->add(JsonObject().apply{addProperty("turn",e.source.turnId);addProperty("side",e.side);addProperty("quote",e.quote);addProperty("at",e.sourceAt)})}})
            })}})
            for(kind in listOf("NEW_OLD","NEW_NEW"))add(kind.lowercase(),JsonArray().apply{pairs.filter{it.kind==kind}.forEach{p->add(JsonObject().apply{addProperty("id",p.id);addProperty("left",p.left);addProperty("right",p.right)})}})
        }
    }
    fun parse(batch:String,expected:List<SemanticPair>,facts:Map<String,SemanticFact>,text:String):List<SemanticDecision>{
        require(text.toByteArray().size<=256*1024)
        val root=RoleArchive.parse(text.toByteArray())
        require(root.keySet()==setOf("batch_id","complete","new_old","new_new")&&root["batch_id"].asString==batch&&root["complete"].isJsonPrimitive&&root["complete"].asJsonPrimitive.isBoolean&&root["complete"].asBoolean)
        val byId=expected.associateBy{it.id};val seen=hashSetOf<String>();val result=mutableListOf<SemanticDecision>()
        for(kind in listOf("NEW_OLD","NEW_NEW"))for(value in root[kind.lowercase()].asJsonArray){
            val j=value.asJsonObject;require(j.keySet()==setOf("id","verdict","left_quote","right_quote","reason"))
            require(j.entrySet().all{it.value.isJsonPrimitive&&it.value.asJsonPrimitive.isString})
            val p=byId[j["id"].asString]?:error("Unknown semantic pair");require(p.kind==kind&&seen.add(p.id))
            val left=facts.getValue(p.left).memory;val right=facts.getValue(p.right).memory
            require(j["left_quote"].asString.let{it.isNotBlank()&&it in left.text}&&j["right_quote"].asString.let{it.isNotBlank()&&it in right.text})
            val verdict=SemanticVerdict.valueOf(j["verdict"].asString);val reason=j["reason"].asString;require(reason.length in 4..160)
            if(verdict==SemanticVerdict.DUPLICATE)require(sameShape(left,right))
            result+=SemanticDecision(p,verdict,reason)
        }
        require(seen==byId.keys);return result
    }
    fun actions(facts:List<SemanticFact>,decisions:List<SemanticDecision>):Map<String,SemanticAction>{
        val byRef=facts.associateBy{it.ref};val parent=byRef.keys.associateWith{it}.toMutableMap()
        fun root(id:String):String{var x=id;while(parent.getValue(x)!=x)x=parent.getValue(x);return x}
        // Exact preview dedup already proved these aliases are the same fact.
        facts.groupBy{it.memory.id}.values.forEach{group->group.drop(1).forEach{parent[root(it.ref)]=root(group.first().ref)}}
        decisions.filter{it.verdict==SemanticVerdict.DUPLICATE}.forEach{parent[root(it.pair.left)]=root(it.pair.right)}
        require(decisions.none{it.verdict!=SemanticVerdict.DUPLICATE&&root(it.pair.left)==root(it.pair.right)}){"Contradictory semantic judgments"}
        val result=mutableMapOf<String,SemanticAction>()
        val fresh=facts.filter{it.operation!=null}
        for(f in fresh){
            val old=decisions.filter{it.pair.kind=="NEW_OLD"&&it.pair.left==f.ref&&it.verdict!=SemanticVerdict.DIFFERENT}
            if(old.isEmpty())continue
            val d=old.singleOrNull()
            result[f.ref]=when {
                d==null->SemanticAction(candidate="与多个旧条目的判定需要核对，保留全部来源。")
                f.kind!="ADD"->SemanticAction(candidate="现有条目的修改涉及其他记忆，请核对：${d.reason}")
                d.verdict==SemanticVerdict.DUPLICATE->SemanticAction(duplicate=d.pair.right)
                d.verdict in setOf(SemanticVerdict.SUPPLEMENT,SemanticVerdict.CORRECTION)->SemanticAction(candidate=d.reason,target=d.pair.right)
                else->SemanticAction(candidate=d.reason)
            }
        }
        for(d in decisions.filter{it.pair.kind=="NEW_NEW"&&it.verdict in setOf(SemanticVerdict.SUPPLEMENT,SemanticVerdict.CORRECTION,SemanticVerdict.UNCERTAIN)}){
            if(d.pair.left !in result)result[d.pair.left]=SemanticAction(candidate=d.reason,target=d.pair.right.takeIf{d.verdict!=SemanticVerdict.UNCERTAIN&&byRef.getValue(d.pair.left).kind=="ADD"&&it !in result})
        }
        for(group in fresh.groupBy{root(it.ref)}.values){
            if(group.size<2)continue
            if(group.any{it.ref in result||it.kind!="ADD"}){
                group.filter{it.ref !in result}.forEach{result[it.ref]=SemanticAction(candidate="相关判定涉及已有修改或其他条目，先人工核对，不按关联链合并。")};continue
            }
            val anchor=group.first()
            // Every endpoint pair must be directly judged, even when embedding similarity was lower.
            val judged=decisions.filter{it.verdict==SemanticVerdict.DUPLICATE}.map{setOf(it.pair.left,it.pair.right)}.toSet()
            if(group.any{a->group.any{b->a!=b&&setOf(a.ref,b.ref) !in judged}})continue
            if(MemoryEvidenceRules.merge(emptyList(),group.flatMap{it.memory.evidence}).size>20)continue
            group.drop(1).forEach{result[it.ref]=SemanticAction(duplicate=anchor.ref)}
        }
        return result
    }
}
