package app.lianxu.independent

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Own every maintenance/index job so cancelling a role never cancels another role's work. */
internal class RoleBackgroundJobs(private val scope:CoroutineScope) {
    private val lock=Any()
    private val jobs=mutableMapOf<String,MutableSet<Job>>()
    fun launch(id:String,context:CoroutineContext=EmptyCoroutineContext,block:suspend()->Unit):Job {
        val job=scope.launch(context,start=CoroutineStart.LAZY){block()}
        synchronized(lock){jobs.getOrPut(id){mutableSetOf()}.add(job)}
        job.invokeOnCompletion{synchronized(lock){jobs[id]?.let{owned->owned.remove(job);if(owned.isEmpty())jobs.remove(id)}}}
        job.start()
        return job
    }
    fun cancel(id:String){
        val owned=synchronized(lock){jobs.remove(id)?.toList().orEmpty()}
        owned.forEach{it.cancel()}
    }
}
