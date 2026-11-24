package app.lianxu.independent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Main-thread request registry. Intents carry an opaque id, never input, access keys or restart instructions. */
internal object ChatRequestOwner {
    class Attempt(val id:String,val notificationId:Int,val stop:(String)->Unit){
        val job=kotlinx.coroutines.SupervisorJob()
        val started=CompletableDeferred<Boolean>()
    }
    val active=linkedMapOf<String,Attempt>()
    var service:ChatRequestService?=null
    private var nextId=32000
    fun begin(context:Context,stop:(String)->Unit):Attempt {
        val a=Attempt(UUID.randomUUID().toString(),nextId++,stop)
        active[a.id]=a
        Diagnostics.event("service.request.begin",mapOf("active_requests" to active.size)+DiagnosticEnvironment.backgroundFields(context))
        try{
            context.startForegroundService(Intent(context,ChatRequestService::class.java).putExtra("request",a.id))
        }catch(e:RuntimeException){
            Diagnostics.event("service.foreground.failed",mapOf("error_type" to e.javaClass.simpleName,"active_requests" to active.size)+DiagnosticEnvironment.backgroundFields(context))
            active.remove(a.id);a.started.complete(false)
        }
        return a
    }
    fun release(a:Attempt?){
        a?.job?.complete()
        if(a==null||active.remove(a.id)!==a)return
        a.started.complete(false)
        service?.refresh()
    }
}

/** Only active transfers own this service. Recreating the process never reconstructs an outbound request. */
class ChatRequestService:Service(){
    internal companion object { const val CHANNEL_ID = "lianxu_chat_active_requests" }
    private val shown=mutableSetOf<Int>()
    private val manager get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        ChatRequestOwner.service=this
        Diagnostics.event("service.created",mapOf("active_requests" to ChatRequestOwner.active.size)+DiagnosticEnvironment.backgroundFields(this))
    }
    private fun notification(a:ChatRequestOwner.Attempt):Notification{
        val stopIntent=Intent(this,ChatRequestService::class.java).setAction("stop").putExtra("request",a.id)
        val action=PendingIntent.getService(this,a.notificationId,stopIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,0,LianxuActivity.createIntent(this).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在等待回复").setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null,"停止",action).build()).apply{
                if(Build.VERSION.SDK_INT>=31)setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val a=ChatRequestOwner.active[intent?.getStringExtra("request")]
        if(intent?.action=="stop"){
            Diagnostics.event("service.stop_requested",mapOf("active_requests" to ChatRequestOwner.active.size))
            a?.stop?.invoke("notification_stop")
        }
        refresh()
        return START_NOT_STICKY
    }
    internal fun refresh(){
        val tasks=ChatRequestOwner.active.values.toList()
        if(tasks.isEmpty()){
            Diagnostics.event("service.stopped",mapOf("active_requests" to 0))
            stopForeground(STOP_FOREGROUND_REMOVE)
            shown.forEach{manager.cancel(it)};shown.clear()
            stopSelf()
            return
        }
        try{
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"正在处理的聊天",NotificationManager.IMPORTANCE_LOW))
            val first=tasks.first()
            val n=notification(first)
            if(Build.VERSION.SDK_INT>=29)startForeground(first.notificationId,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(first.notificationId,n)
            tasks.forEachIndexed{i,a->
                if(i>0)manager.notify(a.notificationId,notification(a))
                shown+=a.notificationId
            }
            val obsolete=shown-tasks.map{it.notificationId}.toSet()
            obsolete.forEach{manager.cancel(it)};shown.removeAll(obsolete)
            val newlyPromoted=tasks.count{it.started.complete(true)}
            if(newlyPromoted>0)Diagnostics.event("service.foreground.active",mapOf("active_requests" to tasks.size)+DiagnosticEnvironment.backgroundFields(this))
        }catch(e:RuntimeException){
            Diagnostics.event("service.foreground.failed",mapOf("error_type" to e.javaClass.simpleName,"active_requests" to tasks.size)+DiagnosticEnvironment.backgroundFields(this))
            endTransfers("service_rejected")
        }
    }
    private fun endTransfers(reason:String){
        val tasks=ChatRequestOwner.active.values.toList()
        ChatRequestOwner.active.clear()
        tasks.forEach{if(!it.started.complete(false))it.stop(reason)}
        stopForeground(STOP_FOREGROUND_REMOVE)
        shown.forEach{manager.cancel(it)};shown.clear()
        stopSelf()
    }
    override fun onTimeout(startId:Int,fgsType:Int){
        Diagnostics.event("service.timeout",mapOf("active_requests" to ChatRequestOwner.active.size)+DiagnosticEnvironment.backgroundFields(this))
        endTransfers("service_timeout")
    }
    override fun onDestroy(){
        var aborted=0
        if(ChatRequestOwner.service===this){
            ChatRequestOwner.service=null
            // A new start may be queued behind an already requested stop. Only abort promoted transfers.
            val promoted=ChatRequestOwner.active.values.filter{it.started.isCompleted}
            aborted=promoted.size
            promoted.forEach{ChatRequestOwner.active.remove(it.id);it.stop("service_destroyed")}
        }
        Diagnostics.event("service.destroyed",mapOf("promoted_aborted" to aborted,"active_requests" to ChatRequestOwner.active.size))
        shown.forEach{manager.cancel(it)};shown.clear()
        super.onDestroy()
    }
}
