package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlin.math.pow

internal data class LearningHeat(val anchor:Double=35.0,val at:Long=0,val days:Int=14){
    fun value(now:Long)=if(at==0L)anchor else anchor*.5.pow((now-at).coerceAtLeast(0).toDouble()/RelationshipProjector.DAY/days)
    fun reinforced(now:Long)=LearningHeat((value(now)+25).coerceAtMost(100.0),now,(days*2).coerceAtMost(120))
}
internal data class LearningControl(val pinned:Boolean=false,val paused:Boolean=false,val deleted:Boolean=false,
    val changedAt:Long=0,val heldHeat:Double=0.0,val resumedAt:Long=0) {
    fun change(action:String,state:RoleLearningState,at:Long):LearningControl = when(action){
        "pin" -> copy(pinned=true,paused=false,changedAt=at,heldHeat=maxOf(state.heat,35.0),
            resumedAt=if(state.state=="paused")at else resumedAt)
        "unpin" -> copy(pinned=false,heldHeat=state.heat,changedAt=at)
        "pause" -> copy(paused=true,heldHeat=state.heat,changedAt=at)
        "resume" -> copy(paused=false,resumedAt=at,heldHeat=maxOf(state.heat,35.0),changedAt=at)
        "delete" -> copy(deleted=true)
        else -> error("未知学习操作")
    }

    fun anchor(heat:LearningHeat,now:Long):LearningHeat {
        if(changedAt==0L || changedAt>now)return heat
        val base=if(changedAt>=heat.at)heat.copy(anchor=heldHeat,at=changedAt) else heat
        return if(pinned || paused)base.copy(at=now) else base
    }
}
internal object LearningControls {
    fun key(role:String)="learning-controls-v1:$role"
    fun decode(raw:String):Map<String,LearningControl> = JsonParser.parseString(raw).asJsonObject.entrySet().associate{(id,v)->
        require(id.isNotBlank() && v.isJsonObject)
        val j=v.asJsonObject
        for(k in listOf("pinned","paused","deleted"))require(j[k]?.let{it.isJsonPrimitive && it.asJsonPrimitive.isBoolean}!=false)
        for(k in listOf("changedAt","heldHeat","resumedAt"))require(j[k]?.let{it.isJsonPrimitive && it.asJsonPrimitive.isNumber}!=false)
        val value=Gson().fromJson(j,LearningControl::class.java)
        require(value.changedAt>=0 && value.heldHeat in 0.0..100.0 && value.resumedAt>=0)
        id to value
    }
    fun read(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(role))).use{
        if(it.moveToFirst())decode(it.getString(0)) else emptyMap()
    }
    fun apply(states:List<RoleLearningState>,controls:Map<String,LearningControl>,now:Long)=states.mapNotNull{s->
        val c=controls[s.id]?:LearningControl()
        if(c.deleted)return@mapNotNull null
        val evalAt=if(s.state=="paused" && c.resumedAt<s.pausedAt)s.pausedAt else now
        val heat=c.anchor(s.temperature,evalAt).value(evalAt)
        val state=when{
            c.paused || s.state=="paused" && (c.resumedAt==0L || c.resumedAt<s.pausedAt) -> "paused"
            !c.pinned && heat<15 -> "faded"
            s.state=="paused" -> "developing"
            else -> s.state
        }
        s.copy(state=state,heat=heat,pinned=c.pinned)
    }
}
internal suspend fun CompanionRepository.controlLearning(role:String,id:String,action:String)=ProcessingRepository(store).tx{db->
    val state=roleContext(db,role).learning.singleOrNull{it.id==id}?:return@tx
    val at=clock()
    val controls=LearningControls.read(db,role).toMutableMap()
    val old=controls[id]?:LearningControl()
    controls[id]=old.change(action,state,at)
    db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",LearningControls.key(role));put("value",Gson().toJson(controls))},SQLiteDatabase.CONFLICT_REPLACE)
    CompanionRepository.bump(db,role)
}.also{CompanionRuntime.changed()}
