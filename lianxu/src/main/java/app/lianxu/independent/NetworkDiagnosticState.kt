package app.lianxu.independent

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Shared metadata for one existing network.request ID, never a second logger or retry policy. */
internal data class NetworkTimeoutDiagnostic(val source:String,val primary:Boolean)
internal class ChatRequestCancellation(val origin:String):CancellationException(origin)
internal class NetworkDiagnosticState(val id:String) {
    private val started=System.nanoTime()
    private val stopped=AtomicReference("none")
    private val terminal=AtomicReference<String?>(null)
    private val bytes=AtomicLong()
    private val first=AtomicLong()
    private val last=AtomicLong()
    private val session=Diagnostics.detailSession
    private val written=AtomicLong()
    private val attempted=AtomicLong()
    private val phase=AtomicReference("queued")
    private val origin=AtomicReference<String?>(null)
    private val initialBgCount=DiagnosticEnvironment.backgroundCount()
    private val sawBackground=AtomicBoolean(DiagnosticEnvironment.isBackground())
    internal val enabled get()=session!=null && session===Diagnostics.detailSession
    internal val experiencedBackground get()=sawBackground.get()||DiagnosticEnvironment.isBackground()||DiagnosticEnvironment.backgroundCount()!=initialBgCount

    fun markBackground(){sawBackground.set(true)}
    fun noteVisibility(){if(DiagnosticEnvironment.isBackground()||DiagnosticEnvironment.backgroundCount()!=initialBgCount)sawBackground.set(true)}

    fun phase(value:String,data:Map<String,Any?> = emptyMap()){
        noteVisibility()
        if(!enabled)return
        phase.set(value)
        Diagnostics.detail(session,"http.phase"){fields()+data}
    }
    /** Bytes returned successfully by OutputStream.write. A failing write may have sent a prefix. */
    fun observe(out:OutputStream):OutputStream {
        if(!enabled)return out
        return object:OutputStream(){
            private fun writeObserved(n:Int,write:()->Unit){
                if(!enabled){write();return}
                attempted.addAndGet(n.toLong())
                write()
                if(!enabled)return
                val total=written.addAndGet(n.toLong())
                if(n>0&&(total==n.toLong()||total/1048576!=(total-n)/1048576)){
                    Diagnostics.detail(session,"http.write_progress"){fields()}
                }
            }
            override fun write(b:Int)=writeObserved(1){out.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int)=writeObserved(len){out.write(b,off,len)}
            override fun flush()=out.flush()
            override fun close()=out.close()
        }
    }
    fun finish(status:String)=terminal.compareAndSet(null,status)
    fun cancel(cause:Throwable?){
        if(enabled)origin.compareAndSet(null,generateSequence(cause){it.cause}.take(12).filterIsInstance<ChatRequestCancellation>().firstOrNull()?.origin)
        stop(if(cause is TimeoutCancellationException)"coroutine_timeout"else if(cause is CancellationException)"caller_cancellation"else"cancellation_unknown")
    }
    fun stop(source:String){
        if(source=="total_timeout")stopped.set(source)
        else {
            stopped.compareAndSet("none",source)
            if(source in setOf("caller_cancellation","coroutine_timeout"))stopped.compareAndSet("cancellation_unknown",source)
            if(source=="coroutine_timeout")stopped.compareAndSet("caller_cancellation",source)
        }
    }
    fun timeout(totalBudgetElapsed:Boolean):NetworkTimeoutDiagnostic {
        while(true){
            val before=stopped.get()
            if(before!="none")return NetworkTimeoutDiagnostic(before,false)
            val source=if(totalBudgetElapsed)"total_timeout"else"socket_timeout"
            if(stopped.compareAndSet(before,source))return NetworkTimeoutDiagnostic(source,true)
        }
    }
    fun fields():Map<String,Any?> = mapOf("request_id" to id,"cancel_source" to stopped.get(),"terminal_status" to terminal.get(),"response_bytes" to bytes.get(),
        "first_byte_ms" to first.get().takeIf{it!=0L}?.let{(it-started)/1_000_000},"last_byte_ms" to last.get().takeIf{it!=0L}?.let{(it-started)/1_000_000},"saw_background" to experiencedBackground)+
        if(enabled)mapOf("transport_phase" to phase.get(),"write_attempted_bytes" to attempted.get(),"client_write_completed_bytes" to written.get(),"cancel_origin" to origin.get()) else emptyMap()
    fun stage(name:String,data:Map<String,Any?> = emptyMap()){noteVisibility();Diagnostics.event(name,fields()+data,elapsed=(System.nanoTime()-started)/1_000_000)}
    fun failure(name:String,error:Throwable,timeout:NetworkTimeoutDiagnostic?=null,extra:Map<String,Any?> = emptyMap()){
        noteVisibility()
        val source=timeout?.source?:stopped.get();val decided=terminal.get()
        val cause=if(timeout?.primary==true)"primary_local_timeout"else when(source){"total_timeout","coroutine_timeout","socket_timeout"->"after_local_timeout";"caller_cancellation"->"after_local_cancellation";"cancellation_unknown"->"cancellation_origin_unknown";else->"unattributed"}
        stage(name,mapOf("error_type" to error.javaClass.simpleName,"cancel_source" to source,"terminal_status" to decided,"cause_relation" to cause,"after_terminal_decision" to (decided!=null))+extra+
            if(enabled)mapOf("exception_stack" to Diagnostics.raw(error.stackTraceToString())) else emptyMap())
    }
    fun transferEnd(status:Int?){
        if(last.get()!=0L)stage("http.last_byte")
        stage("http.transfer_end",mapOf("http_status" to status))
    }
    fun count(input:InputStream):InputStream=object:FilterInputStream(input){
        private fun received(n:Int){if(n<=0)return;val now=System.nanoTime();bytes.addAndGet(n.toLong());last.set(now);if(first.compareAndSet(0,now))stage("http.first_byte")}
        override fun read():Int=`in`.read().also{if(it>=0)received(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also{received(it)}
    }
}
