package app.lianxu.independent

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

/** Archive path/version rules adapted from Jiang-XX's PortableArchiveValidator, ccf224dc.
 * See docs/REUSE-PROVENANCE.md. No extraction, SQL, model calls or executable payloads. */
internal object RoleArchive {
    const val FORMAT = "lianxu-role"
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_ROWS = 50000
    const val EXTENSION = "lianxu.zip"
    val tables = linkedMapOf(
        "messages" to "id friend_id body mine created",
        "chat_turns" to "id friend_id user_message_id assistant_message_id state attempt config_id error_code owner_session source_revision source_zone",
        "message_state" to "message_id revision deleted",
        "message_history" to "message_id revision body deleted",
        "memory_current" to "id friend_id json version valid",
        "memory_history" to "memory_id version json",
        "memory_candidates" to "id friend_id target_id json resolved created",
        "memory_retired" to "friend_id source_user_id source_version batch_id",
        "memory_progress" to "friend_id cursor"
    ).mapValues { it.value.split(' ').toSet() }
    data class Package(val raw: String, val digest: String, val name: String, val scope: String, val counts: Map<String, Int>) {
        fun root(): JsonObject = JsonParser.parseString(raw).asJsonObject
        val summary get() = if(scope=="role") "仅角色设定和表达提示；不含聊天和记忆记录" else
            "聊天 ${counts["messages"] ?: 0} 条 · 记忆 ${counts["memory_current"] ?: 0} 条 · 待确认 ${counts["memory_candidates"] ?: 0} 条"
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun entryName(raw: String): String {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\') &&
            !Regex("^[A-Za-z]:").containsMatchIn(raw) && !raw.contains('\u0000')) { "归档路径无效" }
        return raw.replace('\\','/').also { require(it.split('/').none { p -> p.isBlank() || p=="." || p==".." }) { "归档路径无效" } }
    }
    private fun bounded(input: InputStream, max: Int): ByteArray {
        val out=ByteArrayOutputStream(); val b=ByteArray(8192); var total=0
        while(true) { val n=input.read(b); if(n<0)break; total+=n; require(total<=max){"文件过大，最多32 MiB"};out.write(b,0,n) }
        return out.toByteArray()
    }
    fun read(input: InputStream): Package {
        val bytes=bounded(input,MAX_BYTES)
        val entries=linkedMapOf<String,ByteArray>(); var total=0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while(true){val e=zip.nextEntry?:break;val name=entryName(e.name)
                require(!e.isDirectory && name in setOf("manifest.json","role.json") && name !in entries){"归档含未知或重复文件"}
                val content=bounded(zip,if(name=="manifest.json")8192 else MAX_BYTES)
                total+=content.size;require(total<=MAX_BYTES){"归档解压后过大"};entries[name]=content;zip.closeEntry()
            }
        }
        require(entries.keys==setOf("manifest.json","role.json")){"归档缺少清单或角色资料"}
        val manifest=parse(entries.getValue("manifest.json"))
        require(manifest.keySet()==setOf("format","version","scope","size","sha256")){"清单字段不支持"}
        require(manifest["format"].asString==FORMAT){"仅支持恋叙角色包；不能迁入星伴或其他旧包"}
        require(integer(manifest["version"])==1L){"不支持的角色包版本，请核对文件"}
        val raw=entries.getValue("role.json")
        require(integer(manifest["size"])==raw.size.toLong() && manifest["sha256"].asString==hash(raw)){"角色资料校验失败"}
        return validate(raw).also { require(it.scope==manifest["scope"].asString){"导出范围不一致"} }
    }
    fun write(root: JsonObject, output: OutputStream) {
        val raw=root.toString().toByteArray(Charsets.UTF_8);val checked=validate(raw)
        val manifest=JsonObject().apply {addProperty("format",FORMAT);addProperty("version",1);addProperty("scope",checked.scope);addProperty("size",raw.size);addProperty("sha256",hash(raw))}
        ZipOutputStream(output).use { z -> listOf("manifest.json" to manifest.toString().toByteArray(),"role.json" to raw).forEach{(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()} }
    }
    /** Strict, depth/budget bounded JSON; duplicate names and trailing content are rejected. */
    fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size<=MAX_BYTES){"资料过大"}
        val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        var nodes=0
        JsonReader(StringReader(text)).use { r ->
            r.strictness=Strictness.STRICT
            fun scan(depth:Int){require(depth<=32 && ++nodes<=1000000){"资料结构过深或过多"}
                when(r.peek()){
                    JsonToken.BEGIN_OBJECT->{val keys=hashSetOf<String>();r.beginObject();while(r.hasNext()){require(keys.add(r.nextName())){"JSON字段重复"};scan(depth+1)};r.endObject()}
                    JsonToken.BEGIN_ARRAY->{r.beginArray();while(r.hasNext())scan(depth+1);r.endArray()}
                    JsonToken.STRING,JsonToken.NUMBER->{require(r.nextString().length<=2*1024*1024){"单项内容过长"}}
                    JsonToken.BOOLEAN->r.skipValue();JsonToken.NULL->r.nextNull();else->error("JSON格式无效")
                }
            }
            scan(0);require(r.peek()==JsonToken.END_DOCUMENT){"JSON结尾无效"}
        }
        return JsonParser.parseString(text).asJsonObject
    }
    fun integer(v:JsonElement):Long {require(v.isJsonPrimitive&&v.asJsonPrimitive.isNumber&&v.asString.matches(Regex("-?[0-9]+"))){"数值必须为整数"};return v.asString.toLong()}
    fun text(o:JsonObject,key:String,max:Int,blank:Boolean=false):String {val v=o[key];require(v!=null&&v.isJsonPrimitive&&v.asJsonPrimitive.isString){"缺少文字字段：$key"};return v.asString.also{require(it.length<=max&&(blank||it.isNotBlank())){"文字字段长度无效：$key"}}}
    fun validate(raw:ByteArray):Package {
        val root=parse(raw);RetiredRelationshipData.stripRole(root);require(root.keySet()==setOf("scope","character","prompts","records")){"角色包字段不支持"}
        val scope=text(root,"scope",10);require(scope in setOf("role","records")){"角色包范围不支持"}
        val f=root["character"].asJsonObject;require(f.keySet()==setOf("id","name","bio","prompt","tone")){"角色字段不支持"}
        text(f,"id",200);val name=text(f,"name",30);text(f,"bio",500);text(f,"prompt",4000,true);require(integer(f["tone"]) in 0..10)
        val p=root["prompts"].asJsonObject
        val fields=setOf("expression","extra","examples","examplesEnabled","examplesClosed","initialRelationship","characterDefinition","opening","relationTemplate","relationStart","thinkingPrompt")
        // 已删掉的「内部判断提示」字段：旧包里有就忽略，不报错也不使用。
        val retired=setOf("internalPrompt","internalEnabled","thinkingNone")
        require(p.keySet().all{it in fields||it in retired}){"提示设置含未知字段"}
        listOf("expression","extra","initialRelationship","characterDefinition","opening","relationTemplate","relationStart","thinkingPrompt").forEach{if(p.has(it))text(p,it,if(it=="initialRelationship")4000 else 8000,true)}
        listOf("examplesEnabled","examplesClosed").forEach{if(p.has(it))require(p[it].isJsonPrimitive&&p[it].asJsonPrimitive.isBoolean)}
        if(p.has("relationTemplate"))require(text(p,"relationTemplate",40,true).let{it.isEmpty()||RelationTemplate.named(it)!=null})
        if(p.has("relationStart"))require(text(p,"relationStart",20) in RelationStart.entries.map{it.name})
        p["examples"]?.asJsonArray?.let{require(it.size()<=10);it.forEach{v->val e=v.asJsonObject;require(e.keySet()==setOf("user","assistant"));text(e,"user",8000);text(e,"assistant",16000)}}
        val records=root["records"].asJsonObject
        require(records.keySet()==if(scope=="role")emptySet<String>() else tables.keys){"记录类别缺失或不支持"}
        var total=0;val counts=linkedMapOf<String,Int>()
        records.entrySet().forEach{(table,value)->val rows=value.asJsonArray;total+=rows.size();require(total<=MAX_ROWS){"记录过多，最多50000行"};counts[table]=rows.size()
            rows.forEach{r->val o=r.asJsonObject;require(o.keySet()==tables.getValue(table)){"记录字段不符：$table"};o.entrySet().forEach{(_,v)->require(v.isJsonNull||v.isJsonPrimitive){"记录字段必须为基础值"}}
                if(o.has("friend_id"))require(o["friend_id"]==f["id"]){"记录属于其他角色"}
            }
        }
        val normalized=root.toString();return Package(normalized,hash(normalized.toByteArray(Charsets.UTF_8)),name,scope,counts)
    }
}
