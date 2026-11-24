package app.lianxu.independent

internal const val CHAT_CAPSULE_TRANSITION_MS=1500
internal const val CHAT_CAPSULE_REVEAL_MS=350
internal fun capsuleInitialReveal(event:ChatCapsuleMotionEvent?,ready:Boolean,now:Long)=if(event==null)1f else if(!ready)0f else event.progress(now)
internal fun toolCapsuleKey(item:ChatToolActivity,owner:String="")="tool:$owner:${item.attempt}:${item.id}"
internal fun memoryCapsuleKey(pill:MemoryPill)="batch:${pill.friendId}:${pill.batchId}"

/** All actual chat capsules, using existing projections and labels without changing the journal. */
internal fun chatCapsuleFrames(raw:List<ChatToolActivity>,pills:List<MemoryPill>,owner:String=""):Map<String,String> {
    val items=raw.associateBy{it.id}.values.toList().filter{it.state!=ChatToolActivity.SUPERSEDED}
    return buildMap{
        items.forEach{item->
            val shown=if(item.tool in ChatToolActivity.RECALL_TOOLS)item.recallOutcome().label else item.label()
            put(toolCapsuleKey(item,owner),(if(item.state in setOf("running","staged"))"pending|"else"result|")+"${item.state}:$shown")
        }
        pills.forEach{put(memoryCapsuleKey(it),it.text)}
    }
}

internal data class ChatCapsuleMotionEvent(val serial:Long,val startedAt:Long,val scale:Float=1f,val ready:Boolean=true,val resultAt:Long=startedAt) {
    private val hold=(CHAT_CAPSULE_TRANSITION_MS*scale).toLong().coerceAtLeast(0)
    private val reveal=(CHAT_CAPSULE_REVEAL_MS*scale).toLong().coerceAtLeast(0)
    private val revealAt=maxOf(startedAt+hold,resultAt)
    fun waitBeforeReveal(now:Long)=if(!ready)0L else (revealAt-now).coerceAtLeast(0)
    fun loading(now:Long)=!ready||waitBeforeReveal(now)>0
    fun remaining(now:Long)=if(!ready)0 else (revealAt+reveal-now).coerceAtLeast(0).toInt()
    fun progress(now:Long)=when{scale<=0f->1f;loading(now)->0f;reveal==0L->1f;else->((now-revealAt).toFloat()/reveal).coerceIn(0f,1f)}
}

/** Page-local display bookkeeping only. Existing history is the baseline, never an arrival. */
internal data class ChatCapsuleMotionUpdate(val seen:Map<String,String>,val events:Map<String,ChatCapsuleMotionEvent>,val serial:Long,val scale:Float)
internal class ChatCapsuleMotionTracker(initial:Map<String,String>) {
    private var seen=initial
    private var serial=0L
    private var scale=1f
    private var events=emptyMap<String,ChatCapsuleMotionEvent>()
    fun prepare(frames:Map<String,String>,now:Long,durationScale:Float=1f):ChatCapsuleMotionUpdate {
        val next=events.filter{(id,event)->scale==durationScale&&id in frames&&(!event.ready||event.remaining(now)>0)}.toMutableMap()
        val nextSeen=seen.toMutableMap();var nextSerial=serial
        frames.forEach{(id,state)->
            if(seen[id]!=state){
                nextSeen[id]=state
                if(durationScale>0f){
                    val previous=next[id]
                    val start=if(previous!=null&&previous.loading(now))previous.startedAt else now
                    next[id]=ChatCapsuleMotionEvent(++nextSerial,start,durationScale,!state.startsWith("pending|"),now)
                }
            }
        }
        // A lazy row or live→saved handoff returning in the same state is not a new result.
        return ChatCapsuleMotionUpdate(nextSeen.toMap(),next.toMap(),nextSerial,durationScale)
    }
    fun commit(update:ChatCapsuleMotionUpdate){
        seen=update.seen;events=update.events;serial=update.serial;scale=update.scale
    }
}
