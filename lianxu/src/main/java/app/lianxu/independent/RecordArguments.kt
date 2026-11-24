package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Lenient front door for companion_record arguments, the same for every model. Some models send
 * every value as a string ("1", "null", "", "true"), a nested object as a JSON string or an array as {"item":…}; some put
 * 关系输入 under a top-level name of their own (relationshipInput, relationship_input, relationshipData…). That block is
 * recognised by its content (消息 + 信息类别), whatever the key is called. Only those shapes are repaired, by field name, before the
 * unchanged strict validation runs: free text is never touched and nothing is invented.
 */
internal object RecordArguments {
    /** Values that must be an object or array; a JSON string holding one is unpacked. */
    private val containers=setOf("experience","appraisal","feelings","goal","choices","judgment","feedback","learning","future",
        "usedLearningIds","consideredLearningIds","targets","uncertainty","evidence","关系输入","改主动参数","没达到预期","指向之前","解释","参数")
    /** Arrays: "" / "null" / null mean empty. */
    private val arrays=setOf("targets","uncertainty","evidence","feelings","feedback","choices","usedLearningIds","consideredLearningIds")
    /** Fields where null is a legal value: "" / "null" mean null. */
    private val nullable=setOf("reconsider","learning","future")
    /** Integer fields: "1" / "-1" / "0" become numbers. */
    private val integers=setOf("valence","source","message","actionMessage","notBefore","expiresAt")
    /** Boolean fields: "true" / "false" become booleans. */
    private val booleans=setOf("约定","弥补已完成")
    /** Names seen in the wild; used only as a last resort when no key carries a block with 消息 and 信息类别. */
    val relationAliases=listOf("relationship","relationInput","relation_input")
    private val integer=Regex("-?\\d{1,15}")

    /** Returns the repaired JSON text, or [raw] unchanged when it is not a JSON object. */
    fun normalize(raw:String):String {
        val root=runCatching{JsonParser.parseString(raw)}.getOrNull()?.takeIf{it.isJsonObject}?.asJsonObject?:return raw
        val fixed=fix(root) as JsonObject
        if(!relationBlock(fixed["关系输入"])){
            // First top-level key, in the model's order, whose value is a relation block by content; then the legacy names.
            val key=fixed.keySet().firstOrNull{it!="关系输入"&&relationBlock(fixed[it])}
            val candidate=key?.let{relationCandidate(fixed[it])}
            if(key!=null&&candidate!=null){fixed.remove(key);fixed.add("关系输入",fix(candidate,"关系输入"))}
            else if(absent(fixed["关系输入"]))for(alias in relationAliases){
                val legacy=relationCandidate(fixed[alias])?:continue
                fixed.remove(alias);fixed.add("关系输入",fix(legacy,"关系输入"));break
            }
        }
        return fixed.toString()
    }
    private fun absent(value:JsonElement?)=value==null||value.isJsonNull||value.isJsonPrimitive&&value.asString.isBlank()
    /** An object (or JSON string of one) that carries both 消息 and 信息类别: the shape of 关系输入, under any name. */
    private fun relationBlock(value:JsonElement?)=relationCandidate(value)?.let{it.has("消息")&&it.has("信息类别")}==true
    /** An object, or a JSON string that parses to an object carrying 消息 and 信息类别; an enum string like "none" is not one. */
    private fun relationCandidate(value:JsonElement?):JsonObject? = when {
        value==null||value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject
        value.isJsonPrimitive&&value.asJsonPrimitive.isString -> parsed(value.asString)?.takeIf{it.isJsonObject}?.asJsonObject?.takeIf{it.has("消息")&&it.has("信息类别")}
        else -> null
    }
    private fun parsed(text:String):JsonElement? {
        val t=text.trim()
        if(!(t.startsWith("{")&&t.endsWith("}")||t.startsWith("[")&&t.endsWith("]")))return null
        return runCatching{JsonParser.parseString(t)}.getOrNull()?.takeIf{it.isJsonObject||it.isJsonArray}
    }
    private fun fix(value:JsonElement,key:String?=null):JsonElement {
        var v=value
        if(key!=null&&v.isJsonPrimitive&&v.asJsonPrimitive.isString){
            val s=v.asString.trim()
            if(key in containers)parsed(s)?.let{v=it}
            if(v.isJsonPrimitive){
                when {
                    key in arrays&&(s.isEmpty()||s=="null") -> v=JsonArray()
                    key in nullable&&(s.isEmpty()||s=="null") -> v=JsonNull.INSTANCE
                    key in integers&&integer.matches(s) -> v=JsonPrimitive(s.toLong())
                    key in booleans&&(s=="true"||s=="false") -> v=JsonPrimitive(s=="true")
                }
            }
        } else if(key!=null&&key in arrays&&v.isJsonNull)v=JsonArray()
        // An array sent as {"item":…} (one item, or the list under "item") is the same array.
        if(key!=null&&key in arrays&&v.isJsonObject&&v.asJsonObject.keySet()==setOf("item"))v=v.asJsonObject["item"].let{if(it.isJsonArray)it else JsonArray().apply{add(it)}}
        return when {
            v.isJsonObject -> JsonObject().also{o->v.asJsonObject.entrySet().forEach{(k,child)->o.add(k,fix(child,k))}}
            v.isJsonArray -> JsonArray().also{a->v.asJsonArray.forEach{child->a.add(fix(child,key?.takeIf{it in arrays}?.let{ITEM}))}}
            else -> v
        }
    }
    /** Array items carry no field name of their own; only containers inside them are repaired. */
    private const val ITEM="\u0000item"
}