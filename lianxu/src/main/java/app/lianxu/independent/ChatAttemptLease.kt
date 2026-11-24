package app.lianxu.independent

/** Cancel and local commit have one order, including clients that ignore cancellation. */
class ChatAttemptLease {
    private var active=true
    private var committed=false
    @Synchronized fun isActive()=active
    @Synchronized fun wasCommitted()=committed
    @Synchronized fun cancel(){active=false}
    @Synchronized fun guard(action:()->Boolean):Boolean=if(active)action() else false
    @Synchronized fun commit(action:()->Boolean):Boolean {
        if(!active)return false
        return action().also{if(it){active=false;committed=true}}
    }
}
