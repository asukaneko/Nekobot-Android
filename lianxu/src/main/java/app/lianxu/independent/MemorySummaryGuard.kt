package app.lianxu.independent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local ownership. Starts, manual transactions and revocation share AppMutationGate. */
internal class MemorySummaryGuard(private val now:()->Long={System.nanoTime()/1_000_000}) {
    class Ticket internal constructor(val friendId:String,val lease:ChatAttemptLease,val deadline:Long,val cancelled:()->Unit) {
        var timedOut=false;internal set
    }
    private val owners=mutableMapOf<String,Ticket>()
    private val state=MutableStateFlow<Set<String>>(emptySet())
    val roles=state.asStateFlow()
    private fun release(ticket:Ticket,cancel:Boolean,timeout:Boolean=false){
        if(owners[ticket.friendId]!==ticket)return
        ticket.timedOut=timeout;ticket.lease.cancel();owners.remove(ticket.friendId);state.value=owners.keys.toSet()
        if(cancel)ticket.cancelled()
    }
    private fun expire(){owners.values.toList().filter{now()>=it.deadline}.forEach{release(it,true,true)}}
    fun begin(friendId:String,lease:ChatAttemptLease,timeout:Long,cancelled:()->Unit):Ticket?=AppMutationGate.read {
        expire();if(friendId in owners)null else Ticket(friendId,lease,now()+timeout,cancelled).also{owners[friendId]=it;state.value=owners.keys.toSet()}
    }
    fun current(ticket:Ticket):Boolean=AppMutationGate.read{expire();owners[ticket.friendId]===ticket&&ticket.lease.isActive()}
    fun requireEditable(friendId:String)=AppMutationGate.read{expire();rejectUnless(friendId !in owners,"MEMORY_SUMMARIZING")}
    fun requireAllEditable()=AppMutationGate.read{expire();rejectUnless(owners.isEmpty(),"MEMORY_SUMMARIZING")}
    fun stop(friendId:String)=AppMutationGate.read{owners[friendId]?.let{release(it,true)}}
    fun timeout(ticket:Ticket)=AppMutationGate.read{release(ticket,true,true)}
    fun finish(ticket:Ticket)=AppMutationGate.read{release(ticket,false)}
}
