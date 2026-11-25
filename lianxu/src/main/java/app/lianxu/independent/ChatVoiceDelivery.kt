package app.lianxu.independent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.TimeUnit
import java.io.IOException

internal object ChatVoiceDelivery {
    val pending = MutableStateFlow(emptySet<String>())
    val revision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val tails = mutableMapOf<String, Job>()
    val definition = OrderedChatReply.definition
    fun key(role:String,turn:String,attempt:Int,text:String) = stableDigest(listOf(role,turn,attempt.toString(),text).joinToString("\u0000"))
    fun file(context:Context,id:String) = File(File(context.cacheDir,"lianxu-reply-audio"),"$id.mp3")

    private fun safeReason(stage: VoiceStage, e: Exception): VoiceReason = when {
        e is BreezeBlueSpeechException -> when (e.code) {
            401 -> VoiceReason.HTTP_401
            403 -> VoiceReason.HTTP_403
            404 -> VoiceReason.HTTP_404
            429 -> VoiceReason.HTTP_429
            in 500..599 -> VoiceReason.HTTP_5XX
            else -> VoiceReason.entries.firstOrNull { it.name == e.reason.name } ?: VoiceReason.UNKNOWN
        }
        e is java.net.SocketTimeoutException -> VoiceReason.TIMEOUT
        e is IOException -> VoiceReason.IO
        stage == VoiceStage.DECODE -> VoiceReason.DECODER_ERROR
        else -> VoiceReason.UNKNOWN
    }

    fun submit(context:Context, role:String, id:String, valid:()->Boolean, trace:VoiceTrace?=null,
        visibility:(()->VoiceStatus)?=null, synthesize:suspend()->ByteArray) {
        submitFile(file(context,id), role,id,valid,trace,visibility?.let { read ->
            suspend { withContext(Dispatchers.Main.immediate) { read() } }
        },decode={VoiceAudio.decode(it)},synthesize=synthesize)
    }

    @Synchronized internal fun submitFile(file:File, role:String, id:String, valid:()->Boolean,
        trace:VoiceTrace?, visibility:(suspend ()->VoiceStatus)?, decode:(File)->Unit, synthesize:suspend()->ByteArray) {
        if(id in pending.value) return
        fun outcome(status:VoiceStatus, reason:VoiceReason?=null) {
            if (trace == null) return
            ChatVoiceDiagnostics.outcome(trace,status,reason)
            if (visibility != null) scope.launch {
                val surface=try { visibility() } catch(e:CancellationException){throw e} catch(_:Exception){VoiceStatus.VISIBILITY_UNKNOWN}
                trace.record(VoiceStage.PRESENTATION,surface)
            }
        }
        if(file.isFile) {
            trace?.record(VoiceStage.CACHE,VoiceStatus.CACHE_HIT)
            outcome(VoiceStatus.AUDIO_READY)
            return
        }
        val before=tails[role]
        trace?.record(VoiceStage.QUEUE, VoiceStatus.STARTED)
        pending.update { it+id }
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var temp: File?=null
            var stage=VoiceStage.QUEUE
            var started=System.nanoTime()
            fun begin(next:VoiceStage) {
                stage=next; started=System.nanoTime()
                trace?.record(stage,VoiceStatus.STARTED)
            }
            fun end(status:VoiceStatus,reason:VoiceReason?=null) {
                trace?.record(stage,status,reason,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started))
            }
            fun stillValid():Boolean {
                stage=VoiceStage.VALIDATE; started=System.nanoTime()
                if(valid()) return true
                end(VoiceStatus.CANCELLED,VoiceReason.STALE_TARGET_OR_MESSAGE)
                outcome(VoiceStatus.TEXT_FALLBACK,VoiceReason.STALE_TARGET_OR_MESSAGE)
                return false
            }
            try {
                before?.join()
                if(!stillValid()) return@launch
                begin(VoiceStage.SYNTHESIS)
                val bytes=synthesize()
                end(VoiceStatus.SUCCEEDED)
                if(!stillValid()) return@launch
                begin(VoiceStage.TEMP_WRITE)
                val dir=requireNotNull(file.parentFile);dir.mkdirs()
                val f=File.createTempFile("reply-", ".tmp",dir);temp=f;f.writeBytes(bytes)
                end(VoiceStatus.SUCCEEDED)
                begin(VoiceStage.DECODE)
                decode(f)
                end(VoiceStatus.SUCCEEDED)
                if(stillValid()) {
                    begin(VoiceStage.CACHE)
                    check(f.renameTo(file))
                    end(VoiceStatus.SUCCEEDED)
                    outcome(VoiceStatus.AUDIO_READY)
                }
            } catch(e:CancellationException){
                end(VoiceStatus.CANCELLED)
                outcome(VoiceStatus.TEXT_FALLBACK)
                throw e
            } catch(e:Exception){
                val reason=if(stage==VoiceStage.CACHE && e is IllegalStateException) VoiceReason.RENAME_FAILED else safeReason(stage,e)
                end(VoiceStatus.FAILED,reason)
                outcome(VoiceStatus.TEXT_FALLBACK,reason)
            } finally {temp?.delete();pending.update{it-id};revision.update{it+1}}
        }
        tails[role]=job
        job.invokeOnCompletion { synchronized(this){if(tails[role]===job) tails.remove(role)} }
        job.start()
    }
}
