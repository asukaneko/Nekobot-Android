package app.lianxu.independent

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

fun interface HttpsConnectionFactory { fun open(url:URL):HttpsURLConnection }
class HttpsModelClient(
    private val factory:HttpsConnectionFactory=HttpsConnectionFactory {it.openConnection() as HttpsURLConnection},
    private val timeoutMillis:Int=10000
):ModelDirectoryClient {
    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult = withContext(Dispatchers.IO) {
        Diagnostics.registerSecret(key)
        if(ConfigRules.urlError(config.baseUrl)!=null || key.isBlank() || key.any {it.isWhitespace() || it.code<32}) return@withContext ConfigResult.Failure(ConfigError.INVALID)
        val provider=ProviderPresets.get(config.providerId)
        val url=ModelDirectoryRules.url(config,provider,fetchModels) ?: return@withContext ConfigResult.Failure(ConfigError.UNSUPPORTED)
        if(ConfigRules.urlError(url)!=null) return@withContext ConfigResult.Failure(ConfigError.INVALID)
        Diagnostics.event("model.http.request",mapOf("endpoint" to url,"provider" to config.providerId,"fetch_models" to fetchModels,"timeout_ms" to timeoutMillis),secrets=listOf(key))
        val path=if(fetchModels) null else provider.testPath
        var connection:HttpsURLConnection?=null
        val requestDiagnostics=Diagnostics.requestState();val ownerJob=currentCoroutineContext()[Job]
        var cancellationWatcher:Job?=null
        val deadline=System.nanoTime()+timeoutMillis*3L*1_000_000L
        try {
            ensureActive()
            requestDiagnostics?.phase("open_connection_object",mapOf("method" to "GET"))
            connection=factory.open(URL(url))
            val opened=connection
            cancellationWatcher=launch(start=CoroutineStart.UNDISPATCHED){
                try{awaitCancellation()}finally{
                    if(ownerJob?.isCancelled==true){requestDiagnostics?.stop("cancellation_unknown");requestDiagnostics?.stage("model.http.abort_requested")}
                    try{opened.disconnect()}catch(e:Exception){requestDiagnostics?.failure("model.http.disconnect_failed",e)}
                }
            }
            connection.instanceFollowRedirects=false
            connection.connectTimeout=timeoutMillis; connection.readTimeout=timeoutMillis;connection.requestMethod="GET"
            connection.setRequestProperty(provider.authHeader,if(provider.authHeader=="Authorization") "Bearer $key" else key)
            connection.setRequestProperty("Accept","application/json")
            requestDiagnostics?.phase("await_response_headers")
            val status=connection.responseCode
            requestDiagnostics?.phase("read_response_body",mapOf("http_status" to status))
            Diagnostics.event("model.http.status",mapOf("status" to status),secrets=listOf(key))
            if(status !in 200..299){DiagnosticHttp.errorBody(connection,deadline,key);return@withContext ConfigResult.Failure(when(status) {401->ConfigError.UNAUTHORIZED;403->ConfigError.FORBIDDEN;429->ConfigError.RATE_LIMIT;in 300..399->ConfigError.REDIRECT;else->ConfigError.HTTP},status)}
            // Bound untrusted directory responses; never show raw server errors or exception text.
            val bytes=(requestDiagnostics?.count(connection.inputStream)?:connection.inputStream).use {input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(output.size()<=1024*1024) {
                    ensureActive()
                    val remaining=((deadline-System.nanoTime())/1_000_000L).toInt()
                    if(remaining<=0)throw SocketTimeoutException()
                    connection.readTimeout=minOf(timeoutMillis,remaining)
                    val count=input.read(buffer,0,minOf(buffer.size,1024*1024+1-output.size()));if(count<0)break;output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            if(bytes.size>1024*1024) return@withContext ConfigResult.Failure(ConfigError.FORMAT)
            val body=JSONObject(String(bytes,Charsets.UTF_8))
            Diagnostics.event("model.http.response",mapOf("response_bytes" to bytes.size,"response" to Diagnostics.raw(body.toString())),secrets=listOf(key))
            if(!fetchModels) {
                if((path=="key" && body.optJSONObject("data")==null) || (path!="key" && body.optJSONArray("data")==null)) ConfigResult.Failure(ConfigError.FORMAT) else ConfigResult.Success()
            } else {
                val data=body.optJSONArray("data") ?: return@withContext ConfigResult.Failure(ConfigError.FORMAT)
                val entries=(0 until data.length()).mapNotNull{data.optJSONObject(it)?.let{obj->ModelDirectoryEntry(obj.optString("id"),obj.optJSONObject("api")?.optJSONObject("chat-completions")!=null)}}
                ConfigResult.Success(ModelDirectoryParser.ids(entries,provider.openAiChatModelsOnly).filter{!it.contains(key)})
            }
        } catch(e:CancellationException) {requestDiagnostics?.cancel(e);throw e}
        catch(e:SocketTimeoutException) {val diagnostic=requestDiagnostics?.timeout(System.nanoTime()>=deadline);requestDiagnostics?.failure("model.http.timeout",e,diagnostic);ensureActive();ConfigResult.Failure(ConfigError.TIMEOUT)}
        catch(e:SSLException) {requestDiagnostics?.failure("model.http.tls_failed",e);ensureActive();ConfigResult.Failure(ConfigError.TLS)}
        catch(e:org.json.JSONException) {requestDiagnostics?.failure("model.http.format_failed",e);ensureActive();ConfigResult.Failure(ConfigError.FORMAT)}
        catch(e:Exception) {requestDiagnostics?.failure("model.http.failed",e);ensureActive();ConfigResult.Failure(ConfigError.NETWORK)}
        finally {cancellationWatcher?.cancel();requestDiagnostics?.transferEnd(null);try{connection?.disconnect()}catch(e:Exception){requestDiagnostics?.failure("model.http.disconnect_failed",e)}}
    }
}

/** Absolute catalog URL for presets whose model directory lives on a different path than the chat base. */
internal object ModelDirectoryRules {
    fun url(config:ModelConfig,preset:ProviderPreset,fetchModels:Boolean):String? {
        if(fetchModels&&preset.modelsUrl!=null){
            // An edited chat base may use credentials scoped to a different service or tenant.
            if(config.baseUrl.trimEnd('/')!=preset.base.trimEnd('/'))return null
            return preset.modelsUrl
        }
        return (if(fetchModels)preset.modelsPath else preset.testPath)?.let{config.baseUrl.trimEnd('/')+"/"+it}
    }
}
internal data class ModelDirectoryEntry(val id:String,val openAiChat:Boolean)
internal object ModelDirectoryParser {
    private val idPattern=Regex("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}")
    /** Keeps only sanitized, de-duplicated IDs; openAiChatOnly drops models without an OpenAI-compatible chat route. */
    fun ids(entries:List<ModelDirectoryEntry>,openAiChatOnly:Boolean):List<String> =
        entries.filter{!openAiChatOnly||it.openAiChat}.mapNotNull{it.id.takeIf{id->idPattern.matches(id)}}.distinct().take(500)
}
