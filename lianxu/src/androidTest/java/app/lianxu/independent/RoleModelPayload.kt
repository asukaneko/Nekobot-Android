package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Valid synthetic wire examples for production-path tests; never compiled into the app. */
internal object RoleModelPayload {
    fun json(source:Long,future:Reconsideration?=null,action:String="listen"):String {
        fun evidence()=JSONArray().put(JSONObject().put("source",source).put("layer","fact"))
        fun based(vararg pairs:Pair<String,Any>)=JSONObject().apply{pairs.forEach{put(it.first,it.second)};put("evidence",evidence())}
        return JSONObject().put("schemaVersion",1)
            .put("appraisal",based("event" to "合成真实交流","meaning" to "重视当前交流而不冒充用户情绪","certainty" to "reported","relevance" to "回应当前表达","attribution" to "shared_interaction","situation" to "synthetic_chat","eventRelation" to "new_event","uncertainty" to JSONArray()))
            .put("feelings",JSONArray().put(based("kind" to "care","degree" to "mild","object" to "当前交流","reason" to "愿意认真听当前表达")))
            .put("goal",based("description" to "认真回应当前表达","origin" to "current_request","state" to "active","reason" to "当前消息值得认真回应"))
            .put("choices",JSONArray().put(based("id" to "choice","action" to action,"reason" to "根据当前目标选择")))
            .put("selected","choice").put("judgment",based("position" to "保留自己的判断且忠于原话","kind" to "inference"))
            .put("feedback",JSONArray()).put("learning",JSONObject.NULL).put("usedLearningIds",JSONArray()).put("consideredLearningIds",JSONArray())
            .put("future",future?.let{based("kind" to "follow_up","reason" to it.reason,"notBefore" to it.notBefore,"expiresAt" to it.expiresAt)}?:JSONObject.NULL).toString()
    }
}
