package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SourceDialogue(val requestId:String,val source:SourceRef,val user:Message,val assistant:Message?,val zone:String,val imageObservations:List<ImageObservation> = emptyList()) {
    val messages get()=listOfNotNull(user,assistant)
    val tokens get()=messages.sumOf{ConservativeTokens.tokens(it.text)}+imageObservations.sumOf{ConservativeTokens.tokens(it.text)}
    fun json()=JSONObject().put("request",requestId).put("character",source.characterId).put("turn",source.turnId).put("version",source.version).put("user_id",user.id).put("assistant_id",assistant?.id?:JSONObject.NULL).put("user",user.text).put("assistant",assistant?.text?:JSONObject.NULL).put("user_at",user.created).put("assistant_at",assistant?.created?:JSONObject.NULL).put("zone",zone).put("image_origin",user.image?.let{JSONObject(com.google.gson.Gson().toJson(it))}?:JSONObject.NULL).put("image_observations",JSONArray().apply{imageObservations.forEach{put(it.json())}})
    companion object {fun from(j:JSONObject):SourceDialogue {
        val friend=j.getString("character");val ref=SourceRef(friend,j.getLong("turn"),j.getLong("version"))
        val image=j.optJSONObject("image_origin")?.let{raw->
            val kind=raw.optString("storageKind","legacy-normalized");require(kind in setOf("original","legacy-normalized","chunked-original"))
            com.google.gson.Gson().fromJson(raw.toString(),ChatImageRef::class.java).copy(storageKind=kind)
        }
        return SourceDialogue(j.getString("request"),ref,Message(j.getLong("user_id"),friend,j.getString("user"),true,j.getLong("user_at"),image=image),if(j.isNull("assistant_id"))null else Message(j.getLong("assistant_id"),friend,j.getString("assistant"),false,j.getLong("assistant_at")),j.getString("zone"),(j.optJSONArray("image_observations")?:JSONArray()).let{a->(0 until a.length()).map{ImageObservation.from(a.getJSONObject(it))}})
    }}
}
// Live history/batch/recall counts belong to MemoryRules. Old serialized no-op budgets are ignored on read.
data class ProcessingPolicy(val coreGoal:Int=1000) {
    fun valid()=coreGoal in 100..10000
    fun json()=JSONObject().put("core",coreGoal)
    companion object {fun from(j:JSONObject)=ProcessingPolicy(j.optInt("core",1000))}
}
enum class MemoryCategory(val label:String,val modern:Boolean=false) {
    STATE_FACT("现状与事实",true),PREFERENCE_BOUNDARY("偏好与边界",true),EXPERIENCE_MEMORY("经历与回忆",true),COMMITMENT_OPEN("约定与未竟事项",true),
    USER_PROFILE("旧版：用户画像"),CHARACTER_PROFILE("旧版：角色画像"),RELATIONSHIP_FACT("旧版：双方关系事实"),EXPERIENCE("旧版：经历"),PROMISE("旧版：约定"),UNRESOLVED("旧版：未解决事项")
}
enum class MemoryAbout(val label:String){ USER("用户"),CHARACTER("角色"),BOTH("双方") }
enum class MemoryLife { ACTIVE,CANCELLED,COMPLETED,RETRACTED }
data class MemoryEvidence(val source:SourceRef,val side:String,val quote:String,val snapshot:String,val sourceAt:Long,val zone:String,val image:ImageEvidence?=null) {
    fun json()=JSONObject().put("character",source.characterId).put("turn",source.turnId).put("version",source.version).put("side",side).put("quote",quote).put("snapshot",snapshot).put("at",sourceAt).put("zone",zone).put("image",image?.json()?:JSONObject.NULL)
    companion object{fun from(j:JSONObject)=MemoryEvidence(SourceRef(j.getString("character"),j.getLong("turn"),j.getLong("version")),j.getString("side"),j.getString("quote"),j.getString("snapshot"),j.getLong("at"),j.getString("zone"),j.optJSONObject("image")?.let(ImageEvidence::from))}
}
data class StoredMemory(val id:String,val friendId:String,val category:MemoryCategory,val text:String,val life:MemoryLife=MemoryLife.ACTIVE,val core:Boolean=false,val locked:Boolean=false,val manual:Boolean=false,val version:Int=1,val valid:Boolean=true,val dateExpression:String?=null,val eventDate:String?=null,val evidence:List<MemoryEvidence> = emptyList(),val about:MemoryAbout?=null,val tags:List<String> = emptyList(),val dueExpression:String?=null,val dueDate:String?=null,val expiresAt:Long?=null,val dateType:String?=null,val annualMonthDay:String?=null,val createdAt:Long=evidence.minOfOrNull{it.sourceAt}?:AppServices.companionClock(),val importance:MemoryImportance?=null,val gist:String?=null,val trace:String?=null,val userDeleted:Boolean=false,val deletedAt:Long?=null) {
    var createdAtStored:Boolean=true
    // The current app has one fixed conversation per character; BOTH never widens this scope.
    val conversationId get()=friendId
    fun json()=JSONObject().put("id",id).put("character",friendId).put("category",category.name).put("text",text).put("life",life.name).put("core",core).put("locked",locked).put("manual",manual).put("version",version).put("valid",valid).put("dateExpression",dateExpression?:JSONObject.NULL).put("eventDate",eventDate?:JSONObject.NULL).put("dateType",dateType?:JSONObject.NULL).put("annualMonthDay",annualMonthDay?:JSONObject.NULL).put("expiresAt",expiresAt?:JSONObject.NULL).put("evidence",JSONArray().apply{evidence.forEach{put(it.json())}}).apply{
        put("userDeleted",userDeleted);put("deletedAt",deletedAt?:JSONObject.NULL);put("createdAt",createdAt);put("importance",importance?.name?:JSONObject.NULL);put("gist",gist?:JSONObject.NULL);put("trace",trace?:JSONObject.NULL);
        if(category.modern){put("schema",2);put("conversation",conversationId);put("about",about?.name?:JSONObject.NULL);put("tags",JSONArray(tags));put("dueExpression",dueExpression?:JSONObject.NULL);put("dueDate",dueDate?:JSONObject.NULL)}
    }
    companion object{fun from(j:JSONObject):StoredMemory {
        val a=j.optJSONArray("evidence")?:JSONArray()
        val category=MemoryCategory.valueOf(j.getString("category"));val tags=j.optJSONArray("tags")?:JSONArray()
        if(category.modern)require(j.optInt("schema",2)==2&&j.optString("conversation",j.getString("character"))==j.getString("character"))
        return StoredMemory(j.getString("id"),j.getString("character"),category,j.getString("text"),MemoryLife.valueOf(j.optString("life","ACTIVE")),j.optBoolean("core"),j.optBoolean("locked"),j.optBoolean("manual"),j.getInt("version"),j.optBoolean("valid",true),if(j.isNull("dateExpression"))null else j.getString("dateExpression"),if(j.isNull("eventDate"))null else j.getString("eventDate"),(0 until a.length()).map{MemoryEvidence.from(a.getJSONObject(it))},if(category.modern)MemoryAbout.valueOf(j.getString("about")) else null,if(category.modern)(0 until tags.length()).map{tags.getString(it)} else emptyList(),if(category.modern&&!j.isNull("dueExpression"))j.getString("dueExpression") else null,if(category.modern&&!j.isNull("dueDate"))j.getString("dueDate") else null,if(j.has("expiresAt")&&!j.isNull("expiresAt"))j.getLong("expiresAt") else null,if(j.isNull("dateType"))null else j.getString("dateType"),if(j.isNull("annualMonthDay"))null else MemoryDates.monthDay(j.getString("annualMonthDay")),createdAt=if(j.has("createdAt"))j.getLong("createdAt")else (0 until a.length()).map{a.getJSONObject(it).getLong("at")}.minOrNull()?:AppServices.companionClock(),importance=runCatching{MemoryImportance.valueOf(j.optString("importance"))}.getOrNull(),gist=j.optString("gist").takeIf{it.isNotBlank()&&it!="null"}?.take(40),trace=j.optString("trace").takeIf{it.isNotBlank()&&it!="null"}?.take(8),userDeleted=j.optBoolean("userDeleted",false),deletedAt=if(j.isNull("deletedAt"))null else j.getLong("deletedAt")).also{it.createdAtStored=j.has("createdAt")}
    }}
}
internal fun stableDigest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
/** Program-resolved single days only; ambiguous expressions never fall back to invented model dates. */
object MemoryDates {
    fun monthDay(value:String):String {
        require(value.matches(Regex("[0-9]{2}-[0-9]{2}")))
        java.time.MonthDay.parse("--"+value)
        return value
    }
    /** Birthday is opt-in metadata. A missing birth year never uses the source year. */
    fun birthday(expression:String?):Pair<String?,String?> {
        if(expression.isNullOrBlank())return null to null
        val full=Regex("(?<![0-9])([0-9]{4})[年/-]([0-9]{1,2})[月/-]([0-9]{1,2})(?:日|号)?(?![0-9])").find(expression)
        if(full!=null){val (y,m,d)=full.destructured;val date=runCatching{LocalDate.of(y.toInt(),m.toInt(),d.toInt())}.getOrNull()?:return null to null;return date.toString() to date.toString().takeLast(5)}
        val match=Regex("(?<![0-9])([0-9]{1,2})[月/-]([0-9]{1,2})(?:日|号)?(?![0-9])").find(expression)?:return null to null
        val value="%02d-%02d".format(java.util.Locale.ROOT,match.groupValues[1].toInt(),match.groupValues[2].toInt())
        return null to runCatching{monthDay(value)}.getOrNull()
    }

    fun resolve(expression:String?,sourceAt:Long,zone:String):String? {
        if(expression.isNullOrBlank())return null
        return runCatching {
            val day=Instant.ofEpochMilli(sourceAt).atZone(ZoneId.of(zone)).toLocalDate()
            (app.lianxu.independent.time.TimeWordEngine.resolve(expression,day) as? app.lianxu.independent.time.TimeWordEngine.Result.Day)?.date?.toString()
        }.getOrNull()
    }
    // Reused from our CompanionPromptLanguage.memory; dates remain separate from the saved body.
    /** Rough recency word for a saved exchange. Only says how long ago it was, never invents a date. */
    fun relative(at:Long,now:Long,zone:String=ZoneId.systemDefault().id):String {
        val day=runCatching{Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate()}.getOrNull()?:return "时间未知"
        val today=Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate()
        val days=java.time.temporal.ChronoUnit.DAYS.between(day,today)
        return when {
            days<=0L->"今天"; days==1L->"昨天"; days==2L->"前天"; days<=7L->"${days}天前"
            days<=31L->"${days}天前"; else->"${day.monthValue}月${day.dayOfMonth}日"
        }
    }
    fun display(content:String,eventDate:String?,today:LocalDate=LocalDate.now()):String {
        val date=eventDate?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        val prefix=date?.let { when(it) {
            today->"今天";today.minusDays(1)->"昨天";today.minusDays(2)->"前天"
            else->(if(it.year==today.year)"" else "${it.year}年")+"${it.monthValue}月${it.dayOfMonth}日"
        }+"：" }.orEmpty()
        return prefix+content.replace(Regex("[\\r\\n]+")," ").trim()
    }
}
