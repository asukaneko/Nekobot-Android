package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Structural feedback only; source, version, protection and whole-batch checks still run afterwards. */
internal object MemoryOperationFormat {
    fun issues(op:JSONObject,position:Int):List<MemoryRepairFeedback.Issue>{
        val problems=mutableListOf<String>()
        fun string(name:String,required:Boolean=true):String?{
            val value=op.opt(name)
            if(value==null||value==JSONObject.NULL){if(required)problems+="缺少必填字段 $name";return null}
            if(value !is String){problems+="$name 必须是字符串";return null}
            return value
        }
        val id=string("id")
        if(id!=null&&!id.matches(Regex("[A-Za-z0-9_-]{1,64}")))problems+="id 必须为1至64位字母、数字、下划线或连字符；同批重试沿用原值"
        val kind=string("op")
        if(kind!=null&&kind !in setOf("ADD","UPDATE","CANCEL","COMPLETE","RETRACT"))problems+="op 只能是 ADD/UPDATE/CANCEL/COMPLETE/RETRACT"
        string("target")?.let{if(it.isBlank())problems+="target 不能为空；已有记忆用提供的M编号，新增用未占用的N编号"}
        string("category",kind=="ADD")?.let{if(!(kind!="ADD"&&it.isBlank())&&it !in MemoryCategory.entries.map{c->c.name})problems+="category 不是已有分类取值"}
        string("about",kind=="ADD")?.let{if(it !in MemoryAbout.entries.map{a->a.name})problems+="about 只能是 USER/CHARACTER/BOTH"}
        string("text",kind=="ADD")?.let{if(it.length>4000||kind=="ADD"&&it.isBlank())problems+="text 必须为非空正文且最多4000字"}
        if(op.has("core")&&runCatching{op.getBoolean("core")}.isFailure)problems+="core 必须是布尔值 true/false"
        if(op.has("date_type")&&runCatching{op.getString("date_type") in setOf("day","habit","birthday")}.getOrDefault(false).not())problems+="date_type 只能是 day/habit/birthday"
        val evidence=op.optJSONArray("evidence")
        if(evidence==null)problems+="缺少 evidence 数组；用提供的S编号，例如 [\"S1\"]"
        else if(evidence.length() !in 1..20)problems+="evidence 必须有1至20条来源"
        else for(i in 0 until evidence.length()){
            when(val entry=evidence.opt(i)){
                is String->Unit // The numbered-reference adapter checks whether this S actually exists.
                is JSONObject->{
                    val missing=listOf("turn","version","side","quote").filter{!entry.has(it)||entry.isNull(it)}
                    if(missing.isNotEmpty())problems+="evidence[${i+1}] 缺少 ${missing.joinToString("/")}；也可直接用S编号"
                    for(field in listOf("turn","version"))if(field !in missing&&runCatching{entry.getLong(field)}.isFailure)problems+="evidence[${i+1}].$field 必须是有效轮次或版本整数"
                    if("side" !in missing&&runCatching{entry.getString("side") in setOf("user","assistant","image")}.getOrDefault(false).not())problems+="evidence[${i+1}].side 只能是 user/assistant/image"
                    if("quote" !in missing&&runCatching{entry.getString("quote").isNotBlank()}.getOrDefault(false).not())problems+="evidence[${i+1}].quote 必须是非空原话"
                }
                else->problems+="evidence[${i+1}] 必须是S编号或完整来源对象"
            }
        }
        if(op.has("tags")&&(op.opt("tags") !is JSONArray||op.getJSONArray("tags").length()>12))problems+="tags 必须是最多12项的数组"
        else op.optJSONArray("tags")?.let{tags->for(i in 0 until tags.length())if(runCatching{tags.getString(i)}.isFailure)problems+="tags[${i+1}] 必须是字符串"}
        if(problems.isEmpty())return emptyList()
        val name=id?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,64}"))}?:"operations[$position]"
        return listOf(MemoryRepairFeedback.Issue(name,"FORMAT",problems.joinToString("；")))
    }
}
