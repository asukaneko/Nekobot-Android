package app.lianxu.independent

/** Process-wide in-memory role avatars; a role is re-read only when missing or after an invalidate. */
internal class RoleAvatarCache {
    private val present=mutableMapOf<String,ByteArray>()
    private val absent=mutableSetOf<String>()
    fun cached(id:String):ByteArray?=present[id]
    fun knownMissing(id:String):Boolean=id in absent
    fun put(id:String,bytes:ByteArray?){
        if(bytes==null){present.remove(id);absent+=id}
        else{present[id]=bytes;absent-=id}
    }
    fun invalidate(id:String){present.remove(id);absent.remove(id)}
    fun retain(ids:Set<String>){present.keys.retainAll(ids);absent.retainAll(ids)}
    fun snapshot():Map<String,ByteArray> = present.toMap()
    fun reset(){present.clear();absent.clear()}
}
