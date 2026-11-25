package app.lianxu.independent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One foreground attempt only. Caches query vectors, never document validity or credentials. */
internal class QueryEmbeddingCache {
    private data class Key(val stamp:String,val text:String)
    private val values=mutableMapOf<Key,DoubleArray>()
    private val mutex=Mutex()
    suspend fun vector(credential:ModelCredential,text:String,timeout:Int,client:MemoryEmbeddingClient):DoubleArray=mutex.withLock {
        currentCoroutineContext().ensureActive()
        val key=Key(credential.stamp,text)
        values[key]?.let{return@withLock it.copyOf()}
        val value=client.embed(credential,listOf(text),timeout).single()
        currentCoroutineContext().ensureActive()
        require(MemoryVectorIndex.valid(value))
        values[key]=value.copyOf()
        value.copyOf()
    }
}
