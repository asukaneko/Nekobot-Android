package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Explicit model-derived evidence. The source is the question turn; imageSource is the original image. */
data class ImageEvidence(val imageSource:SourceRef,val hash:String,val observationId:String){
    fun json()=JSONObject().put("character",imageSource.characterId).put("turn",imageSource.turnId).put("version",imageSource.version).put("hash",hash).put("observation_id",observationId)
    companion object {fun from(j:JSONObject)=ImageEvidence(SourceRef(j.getString("character"),j.getLong("turn"),j.getLong("version")),j.getString("hash"),j.getString("observation_id"))}
}
data class ImageObservation(val evidence:ImageEvidence,val text:String){
    fun json()=JSONObject().put("origin","model_observation_not_user_words").put("image",evidence.json()).put("text",text)
    companion object{fun from(j:JSONObject)=ImageObservation(ImageEvidence.from(j.getJSONObject("image")),j.getString("text"))}
}
internal object ImageMemory {
    fun observations(db:SQLiteDatabase,source:SourceRef):List<ImageObservation> =db.rawQuery("SELECT o.id,o.message_id,o.source_revision,o.image_hash,o.body FROM image_observations o WHERE o.friend_id=? AND o.question_message_id=? AND o.question_revision=? ORDER BY o.created,o.id",arrayOf(source.characterId,source.turnId.toString(),source.version.toString())).use{c->buildList{while(c.moveToNext()){
        val e=ImageEvidence(SourceRef(source.characterId,c.getLong(1),c.getLong(2)),c.getString(3),c.getString(0))
        if(validImage(db,e))add(ImageObservation(e,c.getString(4)))
    }}}
    private fun validImage(db:SQLiteDatabase,e:ImageEvidence):Boolean{
        val ref=ChatImages.ref(db,e.imageSource.turnId)?:return false
        return ref.friendId==e.imageSource.characterId&&ref.hash==e.hash&&ProcessingRepository.validSourceOnly(db,e.imageSource)
    }
    fun valid(db:SQLiteDatabase,e:MemoryEvidence):Boolean {
        if(e.side!="image")return e.image==null
        val image=e.image?:return false
        if(!validImage(db,image)||image.imageSource.characterId!=e.source.characterId)return false
        return db.rawQuery("SELECT body FROM image_observations WHERE id=? AND friend_id=? AND message_id=? AND source_revision=? AND image_hash=? AND question_message_id=? AND question_revision=?",arrayOf(image.observationId,e.source.characterId,image.imageSource.turnId.toString(),image.imageSource.version.toString(),image.hash,e.source.turnId.toString(),e.source.version.toString())).use{it.moveToFirst()&&it.getString(0)==e.snapshot}
    }
}
