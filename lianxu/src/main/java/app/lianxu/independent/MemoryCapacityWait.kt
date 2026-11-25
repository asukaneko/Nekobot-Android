package app.lianxu.independent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** The one reason a chat turn waits for a summary: the context truly overflows by tokens and a summary can shrink it. */
internal fun PromptBuild.waitsForSummary()=this is PromptBuild.Blocked&&reason==ChatFailure.CONTEXT_NEEDS_MEMORY

/** Chat can wait for background ownership; duplicate manual starts still return BUSY. */
internal suspend fun awaitMemoryCapacity(friendId:String,guard:MemorySummaryGuard,timeoutMillis:Long=300000,ensureRoom:suspend()->ProcessingRun):ProcessingRun {
    val deadline=System.nanoTime()/1_000_000+timeoutMillis
    while(true){
        val remaining=deadline-System.nanoTime()/1_000_000
        if(remaining<=0)return ProcessingRun(false,"SUMMARY_TIMEOUT")
        val idle=withTimeoutOrNull(remaining){guard.roles.first{friendId !in it};true}
        if(idle!=true)return ProcessingRun(false,"SUMMARY_TIMEOUT")
        // An admitted summary owns its own five-minute limit; waiting must not shorten it.
        val result=ensureRoom()
        if(result.code!="BUSY")return result
        // Another task may acquire/release ownership between observing the flow and starting.
        yield()
    }
}
