package app.lianxu.independent

import java.time.LocalDate

/** Content, subject, topics and workflow state are orthogonal. No category inference for legacy rows. */
object MemoryFacets {
    fun tags(values:List<String>):List<String>{
        val result=values.map{it.trim().replace(Regex("\\s+")," ")}.filter{it.isNotBlank()}.distinctBy{it.lowercase()}
        require(result.size<=12&&result.all{it.length<=24}){"标签最多12个，每个最多24字"};return result
    }
    fun parseTags(text:String)=tags(text.split(',', '，', ';', '；', '\n').map{it.trim().removePrefix("#")})
    fun dueDate(raw:String):String?=raw.trim().takeIf{it.isNotEmpty()}?.let{LocalDate.parse(it).toString()}
    fun categoryLabel(m:StoredMemory)=if(m.category.modern)m.category.label+" · "+(m.about?.label?:"未注明") else "未归类 · 原分类："+m.category.label.removePrefix("旧版：")
    fun matches(m:StoredMemory,category:MemoryCategory?=null,about:MemoryAbout?=null,tag:String?=null,query:String=""):Boolean {
        return (category==null||m.category==category)&&(about==null||m.about==about)&&(tag==null||m.tags.any{it.equals(tag,true)})&&(query.isBlank()||query.trim().lowercase() in (m.text+" "+m.tags.joinToString(" ")+" "+categoryLabel(m)).lowercase())
    }
}
