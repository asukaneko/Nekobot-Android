package app.lianxu.independent

/** Decides which chats need a message re-read, and merges those rows back into the in-memory list. */
internal object MessageReload {
    fun friendsToReload(cached:Map<String,MessageFingerprint>,current:Map<String,MessageFingerprint>):Set<String> =
        current.filter{(id,fp)->cached[id]!=fp}.keys

    fun merge(previous:List<Message>,reloaded:Map<String,List<Message>>,fingerprints:Map<String,MessageFingerprint>):List<Message> {
        val keep=previous.filter{it.friendId !in reloaded && it.friendId in fingerprints}
        return (keep+reloaded.values.flatten()).sortedBy{it.id}
    }
}
