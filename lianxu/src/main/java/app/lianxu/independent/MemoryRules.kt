package app.lianxu.independent

import org.json.JSONObject

/** Global user-chosen counts. Context capacity is an independent hard constraint. */
data class MemoryRules(val recentRounds:Int=40,val summaryRounds:Int=20,val relatedCount:Int=15,val proactiveRecall:Boolean=true,val immediateWrite:Boolean=false,val recallRounds:Int=3,val recallTokens:Int=4000,val recallPerCall:Int=1500,val adaptiveContext:Boolean=false,val reviewRounds:Int=5){
    fun valid()=reviewRounds>0&&recentRounds>0&&summaryRounds>0&&summaryRounds<=recentRounds&&relatedCount>0&&recallRounds in 1..100&&recallTokens in 256..64000&&recallPerCall in 128..recallTokens
    fun json()=JSONObject().put("recentRounds",recentRounds).put("summaryRounds",summaryRounds).put("relatedCount",relatedCount).put("proactiveRecall",proactiveRecall).put("immediateWrite",immediateWrite).put("recallRounds",recallRounds).put("recallTokens",recallTokens).put("recallPerCall",recallPerCall).put("adaptiveContext",adaptiveContext).put("reviewRounds",reviewRounds)
    companion object{
        const val KEY="memory-rules-counts-v1"
        fun from(j:JSONObject)=MemoryRules(j.getInt("recentRounds"),j.getInt("summaryRounds"),j.getInt("relatedCount"),j.optBoolean("proactiveRecall",true),j.optBoolean("immediateWrite"),j.optInt("recallRounds",3),j.optInt("recallTokens",4000),j.optInt("recallPerCall",1500),j.optBoolean("adaptiveContext",false),j.optInt("reviewRounds",5)).also{require(it.valid())}
        /** Consecutive user messages plus their first nonblank assistant reply form one round. */
        fun completeRounds(messages:List<Message>):Int{var waiting=false;var count=0;messages.forEach{m->if(m.text.isNotBlank()){if(m.mine)waiting=true else if(waiting){count++;waiting=false}}};return count}
    }
}
