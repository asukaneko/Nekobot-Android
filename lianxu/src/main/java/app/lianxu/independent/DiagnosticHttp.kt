package app.lianxu.independent

import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection

/** Reads at most 16 KiB of the same failed response; never retries or follows a redirect. */
internal object DiagnosticHttp {
    fun errorBody(connection:HttpsURLConnection,deadline:Long,key:String,trace:String=Diagnostics.traceId()){
        var complete=false
        val bytes=runCatching{connection.errorStream?.let{Diagnostics.requestState()?.count(it)?:it}?.use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(2048)
            while(out.size()<16384){val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)break;connection.readTimeout=minOf(2000,remaining);val n=input.read(buffer,0,minOf(buffer.size,16384-out.size()));if(n<0){complete=true;break};out.write(buffer,0,n)}
            out.toByteArray()
        }?:ByteArray(0)}.getOrElse{Diagnostics.requestState()?.failure("http.error_body.unavailable",it)?:Diagnostics.event("http.error_body.unavailable",mapOf("error_type" to it.javaClass.simpleName),trace=trace);ByteArray(0)}
        Diagnostics.event("http.error_body",mapOf("body" to if(!complete)"[错误响应未完整读取或达到限额，正文已省略]" else Diagnostics.raw(String(bytes,Charsets.UTF_8)),"limited" to !complete),trace=trace,secrets=listOf(key))
    }
}
