package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Keeps legacy example text for round-trip backups; prompt assembly never reads it. */
object RolePromptStore {
    fun key(friendId:String)="role-prompts:$friendId"
    fun decode(raw:String):RolePrompts {
        if(raw.isBlank())return RolePrompts()
        val j=JSONObject(raw);val examples=j.optJSONArray("examples")?:JSONArray()
        // 已删掉的「内部判断提示」（internalPrompt/internalEnabled/thinkingNone）不再读，旧资料里有也忽略，下次保存时自然去掉。
        return RolePrompts(j.optString("expression"),j.optString("extra"),(0 until examples.length()).mapNotNull{i->examples.optJSONObject(i)?.let{e->val u=e.optString("user");val a=e.optString("assistant");if(u.isBlank()||a.isBlank())null else DialogueExample(u,a)}},j.optBoolean("examplesEnabled"),j.optBoolean("examplesClosed"),j.optString("initialRelationship"),j.optString("characterDefinition"),j.optString("opening"),j.optString("relationTemplate"),j.optString("relationStart","NEW"),j.optString("thinkingPrompt").ifBlank{""})
    }
    fun encode(p:RolePrompts):String {
        val examples=JSONArray();p.examples.forEach{examples.put(JSONObject().put("user",it.user).put("assistant",it.assistant))}
        return JSONObject().put("relationTemplate",p.relationTemplate).put("relationStart",p.relationStart).put("expression",p.expression).put("extra",p.extra).put("examples",examples).put("examplesEnabled",p.examplesEnabled).put("examplesClosed",p.examplesClosed).put("initialRelationship",p.initialRelationship).apply{if(p.characterDefinition.isNotBlank())put("characterDefinition",p.characterDefinition);if(p.opening.isNotBlank())put("opening",p.opening);if(p.thinkingPrompt.isNotBlank())put("thinkingPrompt",p.thinkingPrompt)}.toString()
    }
}
