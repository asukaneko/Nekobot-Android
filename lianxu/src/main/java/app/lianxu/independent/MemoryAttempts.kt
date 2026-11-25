package app.lianxu.independent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local identity of every memory summary request. Each attempt records the source versions
 * and the window position it was built from. A result may publish only while its attempt is still
 * the newest one for the role, has not been voided by a timeout or stop, and its sources and window
 * are unchanged; anything else is a late result and the whole batch is rejected.
 */
internal class MemoryAttempts {
    class Attempt internal constructor(val id:String,val friendId:String,val batchId:String,val number:Int,val sources:Map<Long,Long>,val window:Long) {
        @Volatile var voided=false;internal set
        internal val lateLogged=AtomicBoolean(false)
    }
    private val latest=ConcurrentHashMap<String,Attempt>()
    fun begin(friendId:String,batchId:String,number:Int,sources:Map<Long,Long>,window:Long):Attempt {
        val next=Attempt(UUID.randomUUID().toString(),friendId,batchId,number,sources,window)
        latest.put(friendId,next)?.voided=true
        return next
    }
    fun void(attempt:Attempt?){attempt?.voided=true}
    fun live(attempt:Attempt)=!attempt.voided&&latest[attempt.friendId]===attempt
    fun committable(attempt:Attempt,sources:Map<Long,Long>,window:Long)=live(attempt)&&sources==attempt.sources&&window==attempt.window
    /** True only once per attempt, so a discarded late result writes exactly one log line. */
    fun markLate(attempt:Attempt)=attempt.lateLogged.compareAndSet(false,true)
}
