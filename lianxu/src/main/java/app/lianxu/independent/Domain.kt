package app.lianxu.independent

data class Friend(val id: String, val name: String, val bio: String, val prompt: String = "", val tone: Int = 0)
data class Message(val id: Long, val friendId: String, val text: String, val mine: Boolean, val created: Long, val displayReasoning:String="", val image:ChatImageRef?=null)

data class GeneratedRole(val friend:Friend,val definition:String,val expression:String,val opening:String)

/** Request identity rejects late results; a failed refresh never destroys the last complete batch. */
class SearchGate {
    var generation=0L; private set
    var searching=false; private set
    var candidates=emptyList<GeneratedRole>(); private set
    fun begin():Long {generation++;searching=true;return generation}
    fun cancel(){generation++;searching=false}
    fun complete(id:Long,roles:List<GeneratedRole>):Boolean {
        if(!searching||id!=generation)return false
        require(roles.size==3&&roles.map{it.friend.id}.distinct().size==3)
        candidates=roles;searching=false;return true
    }
}

data class SourceRef(val characterId: String, val turnId: Long, val version: Long)
interface TokenCounter { fun tokens(text: String): Int }
