package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class RelationRestoreReport(val skipped:Int){val message:String? get()=if(skipped==0)null else "有 $skipped 条关系记录对不上，已跳过"}
internal object RelationshipBackup {
    /** Mutates only relation fields of a private preview copy; unrelated archive validation stays strict. */
    fun sanitize(root:JsonObject):RelationRestoreReport {
        var skipped=0
        for(value in root["roles"].asJsonArray){
            val role=value.asJsonObject;val id=role["character"].asJsonObject["id"].asString;val tables=role["tables"].asJsonObject
            val messages=tables["messages"].asJsonArray.map{it.asJsonObject}.associateBy{it["id"].asLong}
            val revisions=tables["chat_turns"].asJsonArray.map{it.asJsonObject}.associate{it["user_message_id"].asLong to it["source_revision"].asLong}
            val deleted=tables["message_state"].asJsonArray.map{it.asJsonObject}.filter{it["deleted"].asInt==1}.map{it["message_id"].asLong}.toSet()
            val blocked=tables["companion_blocks"]?.asJsonArray?.map{it.asJsonObject["source_id"].asLong}?.toSet().orEmpty()
            fun current(ref:SourceRef)=ref.characterId==id&&ref.turnId in messages&&ref.turnId !in deleted&&ref.turnId !in blocked&&revisions[ref.turnId]==ref.version
            for(raw in tables["companion_sources"]?.asJsonArray?:continue){
                val row=raw.asJsonObject;val proposal=JsonParser.parseString(row["proposal"].asString).asJsonObject
                val relation=proposal["relationInput"]?.takeUnless{it.isJsonNull}?:continue
                val valid=runCatching{
                    val obj=relation.asJsonObject;require(obj.keySet().containsAll(setOf("source","at","day","category")))
                    if(obj.has("formatVersion"))require(obj["formatVersion"].asInt in 1..2)
                    val original=Gson().fromJson(obj,RelationInput::class.java)
                    val input=if(original.repairs?.let{!current(it)}==true||original.repairBasis?.let{!current(it)}==true)original.copy(repairs=null,repairCompleted=false,repairBasis=null)else original
                    require(RelationInputContract.validStored(input)&&input.source==SourceRef(id,row["source_id"].asLong,row["revision"].asLong)&&current(input.source))
                    require(listOfNotNull(input.previous,input.fulfills,input.explains,input.repairs).all(::current))
                    val body=messages.getValue(input.source.turnId)["body"].asString
                    if(input.sourceHash!=null)require(input.sourceHash==RelationshipBackup.hash(body))
                    else proposal["quote"]?.takeIf{it.isJsonPrimitive}?.asString?.let{require(it.isEmpty()||it in body)}
                    if(input!=original){proposal.add("relationInput",Gson().toJsonTree(input));row.addProperty("proposal",proposal.toString())}
                }.isSuccess
                if(!valid){proposal.remove("relationInput");row.addProperty("proposal",proposal.toString());skipped++}
            }
            val preferences=role["preferences"].asJsonObject
            preferences.keySet().filter{it.startsWith("relationship-cache:")}.forEach{preferences.remove(it)}
            preferences[RelationStorage.key(id)]?.let{raw->
                val history=runCatching{Gson().fromJson(raw.asString,RelationHistory::class.java).also{require(it.version==1)}}.getOrNull()
                if(history==null){preferences.remove(RelationStorage.key(id));skipped++}else{
                    val changes=history.changes.orEmpty().mapNotNull{change->runCatching{require(change.afterMessage==0L||change.afterMessage in messages);change.copy(old=RelationParameterRules.clamp(change.old),new=RelationParameterRules.clamp(change.new))}.getOrNull().also{if(it==null)skipped++}}
                    val program=history.programInputs.orEmpty().filter{input->runCatching{RelationInputContract.validStored(input)&&input.ignored!=null&&input.source.characterId==id&&input.source.turnId in messages&&input.source.turnId !in deleted}.getOrDefault(false).also{if(!it)skipped++}}
                    val corrections=history.corrections.orEmpty().filter{it.source.characterId==id&&it.source.turnId in messages}.also{skipped+=history.corrections.orEmpty().size-it.size}
                    preferences.addProperty(RelationStorage.key(id),Gson().toJson(history.copy(changes=changes,programInputs=program,corrections=corrections,acceptedProposals=history.acceptedProposals.orEmpty().filter(::current))))
                }
            }
        }
        return RelationRestoreReport(skipped)
    }
    fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}
