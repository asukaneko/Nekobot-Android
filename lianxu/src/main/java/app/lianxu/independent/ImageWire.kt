package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.io.OutputStream

internal object ImageWire {
    fun content(message:WireMessage,diagnostic:Boolean=false):Any {
        require(message.imageRefs.isEmpty()){"图片尚未建立发送快照"}
        if(message.images.isEmpty()&&message.videos.isEmpty())return message.content
        return JSONArray().apply{
            if(message.content.isNotBlank())put(JSONObject().put("type","text").put("text",message.content))
            message.images.forEach{image->
                if(image.description.isNotBlank())put(JSONObject().put("type","text").put("text",image.description))
                put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",if(diagnostic)"[${image.mime}; ${image.size} bytes; 图片数据已省略]"else "data:${image.mime};base64,"+Base64.getEncoder().encodeToString(image.bytes))))
            }
            message.videos.forEach{video->
                require(diagnostic){"视频原件必须流式发送"}
                if(video.description.isNotBlank())put(JSONObject().put("type","text").put("text",video.description))
                put(JSONObject().put("type","video_url").put("video_url",JSONObject().put("url","[${video.mime}; ${video.size} bytes; 视频数据已省略]")))
            }
        }
    }
    /** Metadata only: no base64 strings or copies of pixels in JSON/diagnostics. */
    fun body(config:ModelConfig,request:TextChatRequest,stream:Boolean):JSONObject {
        val messages=JSONArray();request.messages.forEach{m->messages.put(JSONObject().put("role",m.role).put("content",content(m,true)).apply{
            if(m.toolCalls.isNotEmpty())put("tool_calls",JSONArray().apply{m.toolCalls.forEach{call->put(JSONObject().put("id",call.id).put("type","function").put("function",JSONObject().put("name",call.name).put("arguments",call.arguments)))}})
            if(m.role=="assistant"&&m.toolCalls.isNotEmpty()&&StructuredResponsePolicy.supportsReasoningContinuation(config))m.continuation?.let{put("reasoning_content",it.wireValue())}
            if(m.role=="tool"){put("tool_call_id",m.toolCallId);put("name",m.name)}
        })}
        return JSONObject().put("model",config.modelId).put("messages",messages).put("stream",stream).apply{
            if(StructuredResponsePolicy.jsonMode(config,request))put("response_format",JSONObject().put("type","json_object"))
            if(request.tools.isNotEmpty())put("tools",JSONArray(request.tools)).put("tool_choice","auto")
            ThinkingFields.apply(this,config)
            put(if(config.providerId in setOf("openai","mimo"))"max_completion_tokens" else "max_tokens",request.outputLimit)
        }
    }
    private class Counter:OutputStream(){var size=0L;override fun write(b:Int){size++};override fun write(b:ByteArray,off:Int,len:Int){size+=len}}
    /** Exact UTF-8 JSON size, including base64 padding, labels, escaped text, tools and model fields. */
    fun size(body:JSONObject,request:TextChatRequest):Long=Counter().also{write(body,request,it)}.size
    fun fits(request:TextChatRequest):Boolean {
        val images=request.messages.flatMap{it.images}
        val videos=request.messages.flatMap{it.videos}
        if(images.isEmpty()&&videos.isEmpty())return true
        if(images.count{!it.videoFrame}>ImagePolicy.MAX_IMAGES||images.any{it.size !in 1..ImagePolicy.ORIGINAL_LIMIT.toLong()||it.mime !in ImagePolicy.mimes})return false
        if(videos.any{it.size !in 1..VideoPolicy.ORIGINAL_LIMIT||!VideoPolicy.isVideo(it.mime)||!it.mime.matches(Regex("video/[a-zA-Z0-9.+-]+"))})return false
        if(hasVideo(request))return true
        val stub=JSONObject().put("messages",JSONArray(request.messages.map{JSONObject().put("role",it.role).put("content",content(it,true))})).put("tools",JSONArray(request.tools))
        return size(stub,request)+4096<=ImagePolicy.REQUEST_LIMIT
    }
    fun hasVideo(request:TextChatRequest)=request.messages.any{it.videos.isNotEmpty()||it.images.any{image->image.videoFrame}}
    fun requestLimit(request:TextChatRequest):Long=if(request.messages.any{it.videos.isNotEmpty()})Long.MAX_VALUE else if(request.messages.any{it.images.isNotEmpty()})ImagePolicy.REQUEST_LIMIT.toLong() else 2L*1024*1024
    fun write(body:JSONObject,request:TextChatRequest,out:OutputStream){
        fun text(value:String){out.write(value.toByteArray(Charsets.UTF_8))}
        fun quoted(value:String)=text(JSONObject.quote(value))
        fun json(value:Any):String=JSONArray().put(value).toString().let{it.substring(1,it.length-1)}
        fun encoded(size:Long,input:()->java.io.InputStream){
            if(out is Counter){out.size+=ImagePolicy.encodedLength(size);return}
            input().use{source->val buffer=ByteArray(12288);var total=0L
                while(true){var n=0;while(n<buffer.size){val got=source.read(buffer,n,buffer.size-n);if(got<0)break;if(got==0)continue;n+=got};if(n==0)break
                    out.write(Base64.getEncoder().encode(if(n==buffer.size)buffer else buffer.copyOf(n)));total+=n
                };check(total==size){"附件原文件读取不完整"}
            }
        }
        fun content(m:WireMessage){
            if(m.images.isEmpty()&&m.videos.isEmpty()){quoted(m.content);return}
            text("[");var comma=false
            fun caption(value:String){if(comma)text(",");text("{\"type\":\"text\",\"text\":");quoted(value);text("}");comma=true}
            if(m.content.isNotBlank())caption(m.content)
            for(image in m.images){
                if(image.description.isNotBlank())caption(image.description)
                if(comma)text(",");text("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:${image.mime};base64,")
                encoded(image.size,image::input)
                text("\"}}");comma=true
            }
            for(video in m.videos){
                if(video.description.isNotBlank())caption(video.description)
                if(comma)text(",");text("{\"type\":\"video_url\",\"video_url\":{\"url\":\"data:${video.mime};base64,")
                encoded(video.size,video::input);text("\"}}");comma=true
            };text("]")
        }
        text("{");var first=true
        val keys=body.keys();while(keys.hasNext()){val key=keys.next();if(!first)text(",");first=false;quoted(key);text(":")
            if(key!="messages")text(json(body.get(key)))
            else{val rows=body.getJSONArray(key);text("[");for(i in 0 until rows.length()){
                if(i>0)text(",");text("{");val row=rows.getJSONObject(i);val fields=row.keys();var firstField=true
                while(fields.hasNext()){val field=fields.next();if(!firstField)text(",");firstField=false;quoted(field);text(":");if(field=="content")content(request.messages[i])else text(json(row.get(field)))}
                text("}")
            };text("]")}
        };text("}")
    }
}
