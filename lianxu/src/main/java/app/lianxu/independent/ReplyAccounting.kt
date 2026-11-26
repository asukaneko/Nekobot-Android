package app.lianxu.independent

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.util.concurrent.TimeUnit

/** A wake-up only; unfinished work is in the database, not in the chat coroutine. */
internal object ReplyAccountingScheduler {
    fun enqueue(store:Store,role:String){
        // Isolated test/import stores must never enqueue work against the live database.
        if(store.databaseName!=AppServices.databaseName)return
        val request=OneTimeWorkRequestBuilder<ReplyAccountingWorker>().setInputData(workDataOf("role" to role))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("lianxu_reply_accounting:$role").build()
        // APPEND_OR_REPLACE closes the enqueue/worker-exit gap without replacing active accounting.
        WorkManager.getInstance(store.applicationContext).enqueueUniqueWork("lianxu_reply_accounting:$role",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    suspend fun recover(store:Store){ProcessingRepository(store).read{ReplyAccountingQueue.roles(it)}.forEach{enqueue(store,it)}}
}

class ReplyAccountingWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        val store=Store(applicationContext,AppServices.databaseName)
        try{
            val vault = ModelVault(applicationContext, AppServices.vaultNamespace, store)
            HostModelSync.refresh(store, vault)
            ChatLedger(store).recoverInterrupted()
            val role=inputData.getString("role")?:return Result.failure()
            val processor=ReplyAccounting(store,ModelAccess(vault),LoggedTextChatClient(AppServices.createTextClient()))
            val complete=processor.drain(role)
            if(complete)CompanionScheduler.enqueue(applicationContext,CompanionRepository(store,AppServices.companionClock))
            // One delayed retry per wake-up. Persistent failure stays queued for next chat/reopen.
            return if(!complete&&runAttemptCount<1)Result.retry()else Result.success()
        }finally{store.close()}
    }
}

internal class ReplyAccounting(private val store:Store,private val access:ModelAccess,private val client:TextChatClient,
    private val embeddingClient:MemoryEmbeddingClient=HttpsMemoryEmbeddingClient()){
    private val repo=ProcessingRepository(store)
    private val companion=CompanionRepository(store,AppServices.companionClock)
    suspend fun drain(role:String):Boolean=AccountingDrain.run(role,
        next={repo.tx{db->ReplyAccountingQueue.claim(db,role,repo.memoryRules(db).first.reviewRounds)?.id}},
        record={id->try{record(role,id)}catch(e:CancellationException){throw e}catch(e:Exception){Diagnostics.failure("reply.accounting.failed",e,mapOf("batch" to id));false}})

    private suspend fun record(role:String,id:String):Boolean {
        val epoch=AppMutationGate.epoch()
        val (batch,items)=repo.read{db->val batch=ReplyAccountingQueue.batch(db,role)?:return@read null
            if(batch.id!=id)return@read null
            batch to ReplyAccountingQueue.frozen(db,role,batch)
        }?:return true
        val valid=repo.read{db->items.filter{ReplyAccountingQueue.valid(db,it)}}
        if(companion.relationship(role).attributionIncomplete){
            Diagnostics.event("reply.accounting.blocked",mapOf("batch" to id,"reason" to "RELATIONSHIP_ATTRIBUTION_UNVERIFIED"))
            CompanionRuntime.changed()
            return false // Keep frozen raw rounds pending; do not spend a model call or mark them reviewed.
        }
        val snapshot=companion.snapshot(role).let{it.copy(sources=it.sources.filter{r->r.turnId<=(items.lastOrNull()?.source?.turnId?:0)})}
        val job=currentCoroutineContext().job
        fun unchanged()=job.isActive&&epoch==AppMutationGate.epoch()&&companion.accountingCurrent(store.readableDatabase,snapshot)&&valid.all{ReplyAccountingQueue.valid(store.readableDatabase,it)}
        if(valid.isEmpty())return companion.finishReviewBatch(batch,items,emptyMap(),snapshot,AppServices.companionClock(),::unchanged)
        val credential=access.reviewCredential()?:return false
        fun active()=unchanged()&&access.reviewCurrent(credential.stamp)
        val first=valid.first().source
        val before=companion.roleContext(role,first).beforeCurrent()
        val extra=repo.read{db->val seen=valid.flatMap{it.readSources}.toSet()
            val resolve=RelationshipRecall.resolver(repo,db,role,AppServices.companionClock())
            before.relationInputs.filter{it.source in seen&&companion.refCurrent(db,it.source)&&CompanionRepository.aiAllowed(db,role,it.source.turnId)}.mapNotNull{row->
                before.relationState?.let{state->resolve(row.source).takeIf{it.isNotBlank()}?.let{RelationshipRecall.event(row,state,it)}}
            }
        }
        val view=before.copy(knownSources=before.knownSources.filterKeys{it<=first.turnId},facts=before.facts.filter{it.source.turnId<first.turnId},relationRecent=(before.relationRecent+extra).distinctBy{it["消息"]})
        val now=AppServices.companionClock()
        val records=try{ReviewBatchModel(client).review(credential.config,credential.key,valid,view,snapshot.roleProfile,now,allowInnerNote=batch.remaining.isEmpty(),active=::active)}
        catch(e:CompanionModelFailure){
            if(e.failure==ChatFailure.CONTEXT_CAPACITY){
                val split=valid.size>1&&repo.tx{db->active()&&ReplyAccountingQueue.splitForCapacity(db,role,batch)}
                Diagnostics.event("reply.accounting.capacity",mapOf("items" to valid.size,"action" to if(split)"split"else"blocked_single_or_changed"))
                if(split)return true // Reclaim the smaller frozen prefix; no record was committed yet.
            }
            throw e
        }
        val saved=companion.finishReviewBatch(batch,items,records,snapshot,now,::active)
        if(saved){
            CompanionRuntime.changed()
            // Chat completion can precede this commit. Index the newly published experiences here,
            // otherwise the next query only sees the cache from before this private review.
            // The index is rebuildable: its transport failure must not undo committed accounting.
            CompanionVectorIndex(companion,access,embeddingClient).index(role)
        }
        return saved
    }
}
