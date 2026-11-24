package app.lianxu.independent

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal class ImportedVoiceRecording private constructor(val file: File, val mime: String, val durationMs: Long) : AutoCloseable {
    override fun close() { file.delete() }
    companion object {
        suspend fun open(context:Context,uri:Uri):ImportedVoiceRecording {
            var owned: File? = null
            try { return withContext(Dispatchers.IO) {
            require(uri.scheme=="content")
            val mime=context.contentResolver.getType(uri).orEmpty()
            val ext=when(mime.lowercase()){
                "audio/mpeg","audio/mp3"->"mp3"; "audio/wav","audio/x-wav","audio/wave"->"wav"
                "audio/mp4","audio/x-m4a"->"m4a"; "audio/flac","audio/x-flac"->"flac"; "audio/aac"->"aac"
                "audio/ogg","application/ogg"->"ogg"; "audio/opus"->"opus"; "audio/webm"->"webm"
                "audio/x-ms-wma"->"wma"; "audio/aiff","audio/x-aiff"->"aiff"; else->error("不支持此录音格式")
            }
            val file=File.createTempFile("lianxu-voice-import-",".${ext}",context.cacheDir)
            owned=file
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> file.writeBytes(input.readVoiceBytes(5_000_000)) } ?: error("无法读取录音")
                val metadata=MediaMetadataRetriever()
                val duration=try {
                    metadata.setDataSource(file.absolutePath)
                    require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)!="yes")
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
                } finally {metadata.release()}
                require(duration>=3000){"录音至少需要 3 秒"}
                ImportedVoiceRecording(file,mime,duration)
            } catch(t:Throwable){file.delete();throw t}
            } } catch(t:Throwable){owned?.delete();throw t}
        }
    }
}

private fun InputStream.readVoiceBytes(limit:Int):ByteArray {
    val out=ByteArrayOutputStream()
    val buf=ByteArray(8192)
    while(true) {
        val size=read(buf)
        if(size<0) break
        if(size>limit-out.size()) throw IOException("文件超过大小限制")
        out.write(buf,0,size)
    }
    return out.toByteArray()
}

internal data class VoiceCandidate(val account:String,val id:String,val language:String,val needsVerification:Boolean=false)
internal class BreezeVoiceStudio(private val account:BreezeVoiceAccount) {
    private fun segment(id:String):String {
        require(id.isNotBlank() && id !in listOf(".","..") && id.none{it=='/' || it=='\\' || it.isISOControl()})
        return URLEncoder.encode(id,"UTF-8").replace("+","%20")
    }
    private suspend fun request(owner:String,path:String,body:ByteArray?=null,type:String="application/json",audio:Boolean=false):ByteArray =
        withContext(Dispatchers.IO) {
            val key=account.keyFor(owner)?:error("账户已变化，请重新选择")
            val c=AppServices.voiceConnections.open(URL("https://api.breeze.blue/v1/$path"))
            try {
                c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=8000;c.readTimeout=30000
                c.setRequestProperty("xi-api-key",key)
                c.setRequestProperty("Accept",if(audio)"audio/mpeg" else "application/json")
                if(body!=null) {
                    c.requestMethod="POST";c.doOutput=true;c.setFixedLengthStreamingMode(body.size);c.setRequestProperty("Content-Type",type)
                    ensureActive();c.outputStream.use{it.write(body)}
                }
                val status=c.responseCode
                if(status !in 200..299) throw StudioFailure(status)
                if(audio && !c.contentType.orEmpty().substringBefore(';').trim().equals("audio/mpeg",true))throw IOException()
                val result=c.inputStream.use{it.readVoiceBytes(if(audio) 5_000_000 else 8_000_000)}
                ensureActive();check(account.accountId()==owner)
                result
            } catch(e:CancellationException){throw e}
            catch(e:StudioFailure){throw e}
            catch(_:Exception){throw IOException("音色请求未完成")}
            finally {c.disconnect()}
        }

    private suspend fun json(owner:String,path:String,obj:JSONObject?=null):JSONObject {
        val bytes=request(owner,path,obj?.toString()?.toByteArray())
        return try {JSONObject(bytes.toString(Charsets.UTF_8))}catch(_:Exception){throw IOException("音色服务响应无效")}
    }
    companion object { const val DEFAULT_LINE = "你好，这是我的声音。" }
    suspend fun audition(owner:String,voice:SavedVoice,line:String=DEFAULT_LINE):ByteArray {
        require(account.accountId()==owner && voice in account.voices())
        return BreezeBlueSpeechProvider({account.keyFor(owner)},AppServices.voiceConnections)
            .synthesize(line,voice.id).also{check(account.accountId()==owner)}
    }
    suspend fun design(owner:String,description:String,language:String="zh"):VoiceCandidate {
        require(description.length in 3..500)
        val row=json(owner,"voice-previews/design",JSONObject().put("voice_description",description)
            .put("language_code",language).put("preview_count",1)).getJSONArray("previews").getJSONObject(0)
        return VoiceCandidate(owner,row.getString("generated_voice_id"),language)
    }
    suspend fun clone(owner:String,source:ImportedVoiceRecording,confirmedUploadAndRights:Boolean,language:String="zh"):VoiceCandidate {
        require(confirmedUploadAndRights){"请先确认有权使用并同意上传给 BreezeBlue"}
        val boundary="lianxu-"+UUID.randomUUID()
        val payload=withContext(Dispatchers.IO) {
            val out=ByteArrayOutputStream()
            fun line(s:String){out.write((s+"\r\n").toByteArray())}
            if(language=="zh"){line("--$boundary");line("Content-Disposition: form-data; name=\"reference_language_code\"");line("");line("zh")}
            line("--$boundary");line("Content-Disposition: form-data; name=\"files\"; filename=\"reference.${source.file.extension}\"")
            line("Content-Type: ${source.mime}");line("");source.file.inputStream().use{out.write(it.readVoiceBytes(5_000_000))}
            line("");line("--$boundary--");out.toByteArray()
        }
        val response=request(owner,"voice-previews/clone",payload,"multipart/form-data; boundary=$boundary")
        val row=JSONObject(response.toString(Charsets.UTF_8))
        return VoiceCandidate(owner,row.getString("generated_voice_id"),language,row.optBoolean("requires_verification"))
    }
    suspend fun remix(owner:String,source:SavedVoice,prompt:String):VoiceCandidate {
        require(source in account.voices() && prompt.length in 3..1000)
        val id=json(owner,"voice-previews/remix",JSONObject().put("voice_id",source.id).put("prompt",prompt)).getString("generation_job_id")
        return withTimeoutOrNull(120_000) {
            var candidate:VoiceCandidate?=null
            while(candidate==null) {
                delay(1500)
                val job=json(owner,"voice-previews/remix/"+segment(id))
                if(!job.isNull("error") || job.optString("status") in listOf("failed","cancelled","canceled"))throw IOException("音色重塑未完成")
                if(!job.isNull("generated_voice_id"))candidate=VoiceCandidate(owner,job.getString("generated_voice_id"),job.optString("language_code","zh"))
            }
            candidate
        } ?: throw IOException("等待重塑预览超时")
    }
    suspend fun preview(candidate:VoiceCandidate):ByteArray = request(candidate.account,"voice-previews/"+segment(candidate.id)+"/stream?output_format=mp3",audio=true)
    suspend fun saveAs(candidate:VoiceCandidate,name:String):String {
        require(!candidate.needsVerification && name.isNotBlank() && name.length<=80)
        return json(candidate.account,"voice-previews/"+segment(candidate.id)+"/save",
            JSONObject().put("voice_name",name).put("language_code",candidate.language)).getString("voice_id")
    }
    private class StudioFailure(code:Int):IOException("BreezeBlue 返回 HTTP $code")
}
