package app.lianxu.independent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock

internal object ContactWake {
    const val RECONCILE_WORK = "lianxu_contact_wake_reconcile"
    fun roleTag(role:String) = "lianxu_companion:$role"
    fun agendaName(w:PlanWake,alarm:Boolean) = "lianxu_agenda:${w.role}:${w.version}:${w.due}:${w.lastUser}:${alarm}"

    fun alarm(context:Context,next:Long?){
        val manager=context.getSystemService(AlarmManager::class.java)
        val intent=PendingIntent.getBroadcast(context,0,Intent(context,ContactWakeReceiver::class.java).setAction("app.lianxu.CONTACT_DUE"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if(next==null)manager.cancel(intent)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,intent)
    }
    fun plan(context:Context,w:PlanWake,delay:Long,alarm:Boolean):Operation{
        val request=OneTimeWorkRequestBuilder<DailyAgendaWorker>().setInputData(workDataOf("role" to w.role))
            .setInitialDelay(delay,TimeUnit.MILLISECONDS).addTag(roleTag(w.role))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        return WorkManager.getInstance(context).enqueueUniqueWork(agendaName(w,alarm),ExistingWorkPolicy.KEEP,request)
    }
}
class ContactWakeReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,"app.lianxu.CONTACT_DUE"))return
        val pending=goAsync()
        try{
            val op=WorkManager.getInstance(context).enqueueUniqueWork(ContactWake.RECONCILE_WORK,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ContactWakeWorker>().build())
            op.result.addListener({pending.finish()},java.util.concurrent.Executor{it.run()})
        }catch(e:RuntimeException){pending.finish();throw e}
    }
}
class ContactWakeWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=Store(applicationContext,AppServices.databaseName).use{store->
        CompanionScheduler.enqueue(applicationContext,CompanionRepository(store,AppServices.companionClock),true)
        Result.success()
    }
}
class DailyAgendaWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=Store(applicationContext,AppServices.databaseName).use{store->
        val role=inputData.getString("role")?:return Result.failure()
        val core=CompanionRepository(store,AppServices.companionClock)
        if(core.contactPolicies()[role]!="enabled")return Result.success()
        if(CompanionRuntime.busy(role)||core.inputPending(role))return Result.retry()
        val planner=DailyPlanner(core)
        try{coroutineScope {
            val job=currentCoroutineContext().job
            CompanionRuntime.register(role,job)
            try{CompanionRuntime.mutex(role).withLock{
                currentCoroutineContext().ensureActive()
                val vault = ModelVault(applicationContext, AppServices.vaultNamespace, store)
                HostModelSync.refresh(store, vault)
                planner.run(role,ModelAccess(vault),AppServices.createTextClient())
            }}finally{CompanionRuntime.unregister(role,job)}
        }}catch(e:CancellationException){currentCoroutineContext().ensureActive();return Result.retry()}
        CompanionScheduler.enqueue(applicationContext,core)
        if(planner.wakes().any{it.role==role && it.due<=core.clock()})Result.retry() else Result.success()
    }
}
