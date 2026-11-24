package app.lianxu.independent

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Non-sensitive snapshot of OS power, background and notification restrictions for this app. */
internal data class BackgroundAccessState(
    val ignoringBatteryOptimizations:Boolean? = null,
    val powerSaveMode:Boolean? = null,
    val backgroundRestricted:Boolean? = null,
    val restrictBackgroundStatus:String = "unknown",
    val notificationsEnabled:Boolean? = null,
    val chatChannelBlocked:Boolean? = null,
) {
    val needsBackgroundSettings:Boolean get()=ignoringBatteryOptimizations==false||backgroundRestricted==true
    val needsNotificationSettings:Boolean get()=notificationsEnabled==false||chatChannelBlocked==true
    fun toDiagnosticMap():Map<String,Any?> = mapOf(
        "ignoring_battery_optimizations" to ignoringBatteryOptimizations,
        "power_save_mode" to powerSaveMode,
        "background_restricted" to backgroundRestricted,
        "restrict_background_status" to restrictBackgroundStatus,
        "notifications_enabled" to notificationsEnabled,
        "chat_channel_blocked" to chatChannelBlocked,
    )
}

/** Detailed-mode observations of the app's activity and the system default network. */
internal object DiagnosticEnvironment {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val disk by lazy { Executors.newSingleThreadExecutor() }
    private val activities=WeakHashMap<LianxuActivity,LifecycleEventObserver?>()
    private var current:Any?=null
    private var registration:Pair<ConnectivityManager,ConnectivityManager.NetworkCallback>?=null
    @Volatile private var appContext:Context?=null
    @Volatile private var fields=emptyMap<String,Any?>()
    @Volatile private var apkHash:String?=null
    private val bgTransitions=AtomicLong(0)

    fun isBackground():Boolean = fields["activity_visibility"]=="background"
    fun backgroundCount():Long = bgTransitions.get()
    /** Observed system network blocked status: true=blocked, false=allowed, null=not yet observed. */
    fun networkBlockedState():Boolean? = fields["network_blocked"] as? Boolean

    fun backgroundState(context:Context):BackgroundAccessState {
        val power=runCatching{context.getSystemService(PowerManager::class.java)}.getOrNull()
        val activity=runCatching{context.getSystemService(ActivityManager::class.java)}.getOrNull()
        val connectivity=runCatching{context.getSystemService(ConnectivityManager::class.java)}.getOrNull()
        val notifications=runCatching{context.getSystemService(NotificationManager::class.java)}.getOrNull()
        val restrict=runCatching{
            when(connectivity?.restrictBackgroundStatus){
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED->"disabled"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED->"whitelisted"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED->"enabled"
                else->"unknown"
            }
        }.getOrDefault("unknown")
        val postGranted=if(Build.VERSION.SDK_INT>=33)runCatching{context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED}.getOrNull() else true
        val appNotificationsEnabled=runCatching{notifications?.areNotificationsEnabled()}.getOrNull()?.let{enabled->
            if(postGranted==false) false else enabled
        }?:postGranted
        val channelBlocked=runCatching{
            val channel=notifications?.getNotificationChannel(ChatRequestService.CHANNEL_ID)
            if(channel==null)null else channel.importance==NotificationManager.IMPORTANCE_NONE
        }.getOrNull()
        return BackgroundAccessState(
            ignoringBatteryOptimizations=runCatching{power?.isIgnoringBatteryOptimizations(context.packageName)}.getOrNull(),
            powerSaveMode=runCatching{power?.isPowerSaveMode}.getOrNull(),
            backgroundRestricted=if(Build.VERSION.SDK_INT>=28)runCatching{activity?.isBackgroundRestricted}.getOrNull() else null,
            restrictBackgroundStatus=restrict,
            notificationsEnabled=appNotificationsEnabled,
            chatChannelBlocked=channelBlocked,
        )
    }

    fun backgroundFields(context:Context):Map<String,Any?> = backgroundState(context).toDiagnosticMap()

    fun start(app:Context){
        appContext=app.applicationContext
        Diagnostics.modeChanged={if(Looper.myLooper()==Looper.getMainLooper())refresh(app) else main.post{refresh(app)}}
        refresh(app)
    }
    fun attach(activity:LianxuActivity){
        appContext=activity.applicationContext
        activities[activity]=null
        refresh(activity.applicationContext)
    }
    fun detach(activity:LianxuActivity){
        activities.remove(activity)?.let{activity.lifecycle.removeObserver(it)}
        publishVisibility()
    }
    private fun refresh(app:Context){
        val session=Diagnostics.detailSession
        if(current!==session){
            current=session
            Diagnostics.environment=null
            registration?.let{(manager,callback)->runCatching{manager.unregisterNetworkCallback(callback)}}
            registration=null
            activities.forEach{(activity,observer)->observer?.let{activity.lifecycle.removeObserver(it)}}
            activities.keys.toList().forEach{activities[it]=null}
            fields=emptyMap()
            if(session!=null){
                val build=runCatching{InstalledBuild.read(app)}.getOrNull()
                fields=mapOf("package_name" to app.packageName,"version_name" to build?.versionName,"version_code" to build?.versionCode,
                    "build_type" to BuildConfig.BUILD_TYPE,"android_api" to Build.VERSION.SDK_INT,"apk_sha256" to apkHash,
                    "default_network" to "awaiting_callback")+backgroundFields(app)
                Diagnostics.environment={if(Diagnostics.detailSession===session)fields+("elapsed_realtime_ns" to SystemClock.elapsedRealtimeNanos()) else emptyMap()}
                startNetwork(app,session)
                disk.execute{
                    if(Diagnostics.detailSession!==session)return@execute
                    val hash=apkHash?:runCatching{
                        val digest=MessageDigest.getInstance("SHA-256")
                        File(app.applicationInfo.sourceDir).inputStream().use{input->
                            val buffer=ByteArray(65536)
                            while(true){
                                if(Diagnostics.detailSession!==session)return@execute
                                val n=input.read(buffer);if(n<0)break
                                digest.update(buffer,0,n)
                            }
                        }
                        digest.digest().joinToString(""){"%02x".format(it)}
                    }.getOrNull()
                    main.post{if(current===session&&Diagnostics.detailSession===session){
                        apkHash=hash;fields=fields+("apk_sha256" to hash)
                        Diagnostics.detail(session,"diagnostic.build"){mapOf("fingerprint_scope" to "installed_base_apk","hash_available" to (hash!=null))}
                    }}
                }
            }
        }
        if(session!=null){
            activities.keys.toList().forEach{activity->
                if(activities[activity]==null){
                    val observer=LifecycleEventObserver{_,event->
                        if(event==Lifecycle.Event.ON_START||event==Lifecycle.Event.ON_STOP)publishVisibility()
                    }
                    activities[activity]=observer
                    activity.lifecycle.addObserver(observer)
                }
            }
            publishVisibility()
        }
    }
    private fun publishVisibility(){
        val session=current?:return
        val visibility=if(activities.keys.any{it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)})"foreground" else "background"
        val bg=appContext?.let(::backgroundFields).orEmpty()
        if(fields["activity_visibility"]!=visibility){
            if(visibility=="background")bgTransitions.incrementAndGet()
            fields=fields+("activity_visibility" to visibility)+bg
            Diagnostics.detail(session,"diagnostic.lifecycle")
        } else if(bg.isNotEmpty()) {
            fields=fields+bg
        }
    }
    internal fun networkObserver(session:Any):ConnectivityManager.NetworkCallback {
        var active:Network?=null
        return object:ConnectivityManager.NetworkCallback(){
            private fun update(event:String,value:String,extra:Map<String,Any?> = emptyMap(),clearBlocked:Boolean=false){
                if(current!==session||Diagnostics.detailSession!==session)return
                val base=if(clearBlocked) fields-"network_blocked" else fields
                fields=base+("default_network" to value)+extra
                Diagnostics.detail(session,"diagnostic.network"){mapOf("network_event" to event)+extra}
            }
            override fun onAvailable(network:Network){active=network;update("available","available",clearBlocked=true)}
            override fun onCapabilitiesChanged(network:Network,c:NetworkCapabilities){
                if(network!=active)return
                val transports=listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi",NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",NetworkCapabilities.TRANSPORT_VPN to "vpn")
                    .filter{c.hasTransport(it.first)}.joinToString("+"){it.second}.ifEmpty{"other"}
                update("capabilities","$transports/validated=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}/metered=${!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
            }
            override fun onBlockedStatusChanged(network:Network,blocked:Boolean){
                if(network!=active||current!==session||Diagnostics.detailSession!==session)return
                fields=fields+("network_blocked" to blocked)
                Diagnostics.detail(session,"diagnostic.network"){mapOf("network_event" to "blocked_changed","network_blocked" to blocked)}
            }
            override fun onLost(network:Network){if(active==network){active=null;update("lost","none",clearBlocked=true)}}
        }
    }
    private fun startNetwork(app:Context,session:Any){
        val manager=app.getSystemService(ConnectivityManager::class.java)?:return
        val callback=networkObserver(session)
        runCatching{
            manager.registerDefaultNetworkCallback(callback,main)
            registration=manager to callback
            if(manager.activeNetwork==null){fields=fields+("default_network" to "none");Diagnostics.detail(session,"diagnostic.network"){mapOf("network_event" to "snapshot")}}
        }.onFailure{
            fields=fields+("default_network" to "unavailable")
            Diagnostics.detail(session,"diagnostic.network"){mapOf("error_type" to it.javaClass.name)}
        }
    }
}
