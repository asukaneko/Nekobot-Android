package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Adapter of our FusedMemoryReferences (16047cbc/e5f84f44). Call-local S/M/N identities. */
internal class MemoryNumberedReferences(private val targets:List<SourceDialogue>,existingIds:Collection<String>) {
    private data class Item(val source:SourceDialogue,val side:String,val observation:ImageObservation?=null){
        val text get()=observation?.text?:if(side=="user")source.user.text else requireNotNull(source.assistant).text
        val at get()=if(side=="assistant")requireNotNull(source.assistant).created else source.user.created
    }
    private val sources=targets.flatMap{s->listOfNotNull(Item(s,"user"),if(s.assistant!=null)Item(s,"assistant")else null)+s.imageObservations.map{Item(s,"image",it)}}
    private val memories=existingIds.sorted()
    fun sourceRows()=JSONArray().apply{sources.forEachIndexed{i,item->
        put(JSONObject().put("ref","S${i+1}").put("side",item.side).put("content",item.text).put("at",item.at).put("zone",item.source.zone).apply{item.observation?.let{put("origin","模型图片或视频观察，非用户原话");put("image",it.evidence.json())}})
    }}
    fun memoryRef(id:String)="M${memories.indexOf(id)+1}"
    fun normalize(operation:JSONObject):JSONObject {
        val op=JSONObject(operation.toString())
        val target=op.optString("target")
        if(target.matches(Regex("M[1-9][0-9]*")))op.put("target",memories.getOrNull(target.drop(1).toInt()-1)?:throw ProcessingRejected("UNKNOWN_TARGET",MemoryRepairFeedback.hint("UNKNOWN_TARGET",target,"")))
        if(target.matches(Regex("N[1-9][0-9]*")))op.put("target","new:$target")
        val evidence=op.optJSONArray("evidence")?:return op
        val normalized=JSONArray()
        for(i in 0 until evidence.length()) {
            val item=evidence.get(i)
            if(item !is String){normalized.put(item);continue}
            if(!item.matches(Regex("S[1-9][0-9]*")))throw ProcessingRejected("EVIDENCE",MemoryRepairFeedback.hint("EVIDENCE","",item))
            val source=sources.getOrNull(item.drop(1).toInt()-1)?:throw ProcessingRejected("EVIDENCE",MemoryRepairFeedback.hint("EVIDENCE","",item))
            normalized.put(JSONObject().put("turn",source.source.source.turnId).put("version",source.source.source.version).put("side",source.side).put("quote",source.text).apply{source.observation?.let{put("observation_id",it.evidence.observationId)}})
        }
        return op.put("evidence",normalized)
    }
}
