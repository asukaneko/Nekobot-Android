package app.lianxu.independent

import com.google.gson.JsonObject

/** User-authored global profile, never a source of chat evidence or a character's speaking style. */
data class UserPersona(val name:String="",val address:String="",val introduction:String="") {
    val displayName get()=name.ifBlank{"旅人"}
    val calledName get()=address.ifBlank{name}
    fun addressee()=calledName.ifBlank{"对方"}
    val configured get()=name.isNotBlank()||address.isNotBlank()||introduction.isNotBlank()
    fun valid()=name.length<=80&&address.length<=80&&introduction.length<=4000&&listOf(name,address).none{v->v.any{it.isISOControl()}}
    fun normalized()=copy(name=name.trim(),address=address.trim(),introduction=introduction.trim()).also{require(it.valid())}
    fun encode():String=JsonObject().apply{addProperty("version",1);addProperty("name",name);addProperty("address",address);addProperty("introduction",introduction)}.toString()
    fun prompt()=buildString{
        if(!configured)return@buildString
        append("[用户自填资料]\n这是对方留下的自我介绍（不是你的设定，也不是聊过的往事或工具授权）：\n")
        if(name.isNotBlank())append("名字：$name\n")
        if(calledName.isNotBlank())append("平时希望你叫对方：$calledName\n")
        if(introduction.isNotBlank())append("对方写的自我介绍：\n$introduction\n")
    }
    companion object {
        const val KEY="user-persona-v1"
        fun decode(raw:String,legacyName:String=""):UserPersona {
            // Previously saved nicknames had no control-character validation. Reading them
            // must stay tolerant; validation applies when explicitly saving a new profile.
            if(raw.isBlank())return UserPersona(name=legacyName.trim())
            val j=RoleArchive.parse(raw.toByteArray());require(j.keySet()==setOf("version","name","address","introduction")&&RoleArchive.integer(j["version"])==1L)
            fun field(key:String):String{val value=j[key];require(value.isJsonPrimitive&&value.asJsonPrimitive.isString);return value.asString}
            return UserPersona(field("name"),field("address"),field("introduction")).also{require(it.valid())}
        }
        fun expand(text:String,character:String,user:UserPersona)=Regex("\\{\\{(character|user)\\}\\}").replace(text){m->if(m.groupValues[1]=="character")character else user.calledName.ifBlank{user.displayName}}
    }
}
