package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class VideoWireTest {
    @Test fun originalStreamingSizeJsonAndDiagnosticsPreserveOnlyWireBytes(){
        val file=File.createTempFile("video-wire-",".mp4")
        try{val bytes=byteArrayOf(0,1,2,3,4,5,6);file.writeBytes(bytes)
            val request=TextChatRequest(listOf(WireMessage("user","视频附文",videos=listOf(WireVideo("video/mp4",file,"原件含原音轨")))))
            val config=ModelConfig("test","test","custom","https://example.invalid/v1","test")
            val body=ImageWire.body(config,request,false);val output=ByteArrayOutputStream();ImageWire.write(body,request,output)
            assertEquals(output.size().toLong(),ImageWire.size(body,request));assertTrue(ImageWire.fits(request))
            val blocks=JSONObject(output.toString("UTF-8")).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            val url=blocks.getJSONObject(2).getJSONObject("video_url").getString("url");assertArrayEquals(bytes,Base64.getDecoder().decode(url.substringAfter(',')))
            assertFalse(body.toString().contains("data:video/"));assertTrue(body.toString().contains("视频数据已省略"))
            assertFalse(ChatImages.redact(url).contains("base64"));assertEquals("[视频数据已省略]",ChatImages.redact(url))
        }finally{file.delete()}
    }
    @Test fun jpegRequestBudgetIncludesBase64AndMetadata(){
        val file=File.createTempFile("frame-budget-",".jpg")
        try{
            java.io.RandomAccessFile(file,"rw").use{it.setLength(13L*1024*1024)}
            val frames=listOf(WireImage.videoFrame(file,"0 ms"),WireImage.videoFrame(file,"1000 ms"))
            val request=TextChatRequest(listOf(WireMessage("user","",images=frames)))
            val size=ImageWire.size(ImageWire.body(ModelConfig("id","test","custom","https://example.invalid","test"),request,false),request)
            assertEquals(ImagePolicy.REQUEST_LIMIT.toLong(),ImageWire.requestLimit(request))
            assertTrue(size>ImageWire.requestLimit(request))
        }finally{file.delete()}
    }
    @Test fun frameCountDoesNotInheritOrdinaryImageQuotaAndRolesStillApply(){
        val file=File.createTempFile("frame-wire-",".png");try{file.writeBytes(byteArrayOf(1,2,3))
            val frames=VideoPolicy.frameTimes(180_000).map{WireImage.videoFrame(file,VideoPolicy.formatTime(it))}.toList()
            assertEquals(24,frames.size)
            assertTrue(ImageWire.fits(TextChatRequest(listOf(WireMessage("user","",images=frames + frames + frames)))))
            val ordinary=(0..64).map{WireImage("image/png",byteArrayOf(1,2,3))}
            assertFalse(ImageWire.fits(TextChatRequest(listOf(WireMessage("user","",images=ordinary)))))
            assertTrue(runCatching{WireMessage("assistant","文字",videos=listOf(WireVideo("video/mp4",file,"")))}.isFailure)
        }finally{file.delete()}
    }
}
