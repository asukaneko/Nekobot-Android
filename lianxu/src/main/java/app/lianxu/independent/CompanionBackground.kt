package app.lianxu.independent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal object CompanionScheduler {
    suspend fun enqueue(context:Context,repo:CompanionRepository){
        repo.refreshRelationshipContacts()
        repo.queued().forEach{o->
            val backoff=o.attempt>0||CompanionRuntime.busy(o.role)||repo.inputPending(o.role)
            val request=OneTimeWorkRequestBuilder<CompanionWorker>()
                .setInputData(workDataOf("opportunity" to o.id))
                .setInitialDelay(ContactRetry.delay(o.notBefore,AppServices.companionClock(),backoff),TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("lianxu_companion:${o.role}").build()
            // One generation per persisted not-before/attempt. A deferred generation has a new
            // work name, avoiding self-replacement and KEEP silently dropping its successor.
            WorkManager.getInstance(context).enqueueUniqueWork("lianxu_companion:${o.id}:${o.attempt}:${o.notBefore}",ExistingWorkPolicy.KEEP,request)
        }
    }
    fun cancel(context:Context,role:String){WorkManager.getInstance(context).cancelAllWorkByTag("lianxu_companion:$role")}
}

class CompanionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        val store=Store(applicationContext,AppServices.databaseName)
        try{
            val repo=CompanionRepository(store,AppServices.companionClock)
            ChatLedger(store).recoverInterrupted()
            repo.refreshRelationshipContacts()
            repo.checkContinuity(CompanionRuntime.owner)
            val id=inputData.getString("opportunity")?:return Result.failure()
            val candidate=repo.queued().firstOrNull{it.id==id}?:return Result.success()
            if(candidate.notBefore>AppServices.companionClock())return Result.success()
            if(CompanionRuntime.busy(candidate.role))return Result.retry()
            val job=currentCoroutineContext().job
            CompanionRuntime.register(candidate.role,job)
            var busy=false
            try{CompanionRuntime.mutex(candidate.role).withLock{
                // Receive the durable claim even if cancellation arrives at the IO handoff.
                val o=withContext(NonCancellable){repo.claim(id,CompanionRuntime.owner)}?:run{busy=repo.queued().any{it.id==id};return@withLock}
                var recover=false
                var failure:ContactFailureKind?=null
                var failureDetail="";var secret:String?=null
                try{
                    currentCoroutineContext().ensureActive()
                    val access=ModelAccess(ModelVault(applicationContext,AppServices.vaultNamespace,store))
                    val credential=access.credential(ModelPurpose.CHAT)?:throw CompanionModelFailure(ChatFailure.MISSING_CONFIG)
                    secret=credential.key
                    val history=repo.contactHistory(o.role);val recent=ProactiveMaterial.recent(history)
                    val query=ProactiveMaterial.ownReason(o).ifBlank{recent.takeLast(4).joinToString("\n"){it.text}}
                    val semantic=CompanionVectorIndex(repo,access).scores(o.role,query)
                    val snapshot=repo.snapshot(o.role,query,semantic,(credential.config.contextTokens-credential.config.outputTokens).coerceAtLeast(0))
                    if(!access.current(ModelPurpose.CHAT,credential.stamp))return@withLock
                    if(!repo.current(o,CompanionRuntime.owner)){recover=true;return@withLock}
                    val factsNow=AppServices.companionClock()
                    val facts=repo.contactFacts(o.role,factsNow)
                    val sourceAt=store.readableDatabase.rawQuery("SELECT created FROM companion_sources WHERE role=? AND source_id=? ORDER BY revision DESC LIMIT 1",arrayOf(o.role,o.source.toString())).use{if(it.moveToFirst())it.getLong(0)else null}
                    val decisionAt=AppServices.companionClock()
                    val decision=CompanionModel(AppServices.createTextClient()).reconsider(credential.config,credential.key,o,snapshot,snapshot.roleProfile,decisionAt,facts,sourceAt,recent,history.firstOrNull{it.id==o.source}){access.current(ModelPurpose.CHAT,credential.stamp)&&repo.current(store.readableDatabase,snapshot)&&!isStopped&&!CompanionRuntime.busy(o.role)}
                    currentCoroutineContext().ensureActive()
                    if(isStopped||CompanionRuntime.busy(o.role)){recover=true;return@withLock}
                    val message=repo.conclude(o,CompanionRuntime.owner,decision,snapshot,executionCurrent={job.isActive&&!isStopped&&!CompanionRuntime.busy(o.role)},decisionAt=decisionAt){access.current(ModelPurpose.CHAT,credential.stamp)}
                    if(message!=null){CompanionRuntime.changed();CompanionNotifications(applicationContext,store,repo).post(message)}
                }catch(e:CancellationException){recover=true;throw e}
                catch(e:Exception){ failure=ContactFailures.classify(e);failureDetail=ContactFailures.detail(e,listOfNotNull(secret)) }
                finally{withContext(NonCancellable){repo.release(o,CompanionRuntime.owner,recover,failure,failureDetail)}}
            }}finally{CompanionRuntime.unregister(candidate.role,job)}
            return if(busy)Result.retry() else Result.success()
        }finally{
            try{withContext(NonCancellable){CompanionScheduler.enqueue(applicationContext,CompanionRepository(store,AppServices.companionClock))}}
            finally{store.close()}
        }
    }
}

internal class CompanionNotifications(private val context:Context,private val store:Store,private val repo:CompanionRepository){
    private val manager=context.getSystemService(NotificationManager::class.java)
    companion object{const val CHANNEL="lianxu_companion_messages";const val ROLE="companion_role";const val MESSAGE="companion_message";const val TAG="lianxu-companion"}
    fun allowed()=(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&manager.areNotificationsEnabled()
    suspend fun post(id:Long){
        if(!repo.claimNotification(id))return
        if(!allowed()){repo.delivery(id,"permission_missing");return}
        val message=store.messages().firstOrNull{it.id==id}?:run{repo.delivery(id,"unknown");return}
        val friend=store.friends().firstOrNull{it.id==message.friendId}?:run{repo.delivery(id,"unknown");return}
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"角色消息",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=LianxuActivity.createIntent(context).apply{
            action=Intent.ACTION_MAIN;addCategory(Intent.CATEGORY_LAUNCHER)
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ROLE,friend.id);putExtra(MESSAGE,id)
        }
        val pending=PendingIntent.getActivity(context,id.toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(friend.name).setContentText(message.text).setStyle(Notification.BigTextStyle().bigText(message.text))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        try{
            if(!repo.publishIfCurrent(id){manager.notify(TAG,id.toInt(),notification);true}){repo.delivery(id,"cancelled");return}
            if(!repo.notificationCurrent(id)){manager.cancel(TAG,id.toInt());repo.delivery(id,"cancelled");return}
            val observed=manager.activeNotifications.any{it.tag==TAG&&it.id==id.toInt()}
            repo.delivery(id,"requested",observed)
        }catch(_:Exception){repo.delivery(id,"unknown")}
    }
    suspend fun reconcile(){
        manager.activeNotifications.filter{it.tag==TAG}.forEach{if(!repo.notificationCurrent(it.id.toLong()))manager.cancel(TAG,it.id)}
    }
}
