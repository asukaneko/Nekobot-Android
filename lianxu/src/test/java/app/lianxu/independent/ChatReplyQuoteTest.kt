package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ChatReplyQuoteTest {
    @Test fun compactUnicodeSnippetRoundTripsAndRemapsBothReferences() {
        val quote=ChatReplyQuote(31,true,"  山\n 风 "+"🌙".repeat(220),"voice")
        val decoded=requireNotNull(ChatReplyQuote.decode(quote.encode()))
        assertEquals(200,decoded.snippet.codePointCount(0,decoded.snippet.length))
        assertTrue(decoded.label().startsWith("[语音] 山 风 "))
        assertTrue(decoded.snippet.endsWith("🌙"))
        assertNull(ChatReplyQuote.decode(quote.copy(mediaKind="other").encode()))
        val map=BackupRemapper("a","b",mapOf("old" to "new"),mapOf(31L to 82L))
        assertEquals(ChatReplyQuote.PREFIX+"new",map.preferenceKey(ChatReplyQuote.PREFIX+"old"))
        assertEquals(decoded.copy(messageId=82),ChatReplyQuote.decode(map.preferenceValue(quote.encode())))
    }
    @Test fun quotedHistoryAndImageOnlyInputKeepTheirVerbatimEvidence() {
        val quote=ChatReplyQuote(3,false,"晚饭记得趁热吃。","voice")
        val history=Message(5,"a","我的\n原话",true,100)
        val image=ChatImageRef("a",8,"test","image/png","x",12,12)
        val messages=(CompanionPrompt.build(Friend("a","林间","合成"),RolePrompts(),listOf(history),"",
            currentMessageId=8,inputImage=image,quote=quote.copy(mediaKind="image",snippet=""),
            recentQuotes=mapOf(5L to quote)) as PromptBuild.Ready).request.messages
        fun checkSource(id:Long,body:String,label:String) {
            val wire=messages.single { it.sourceMessageId==id.toString() }
            assertTrue(wire.content.take(wire.sourcePrefix).contains(label))
            assertEquals(body,wire.content.substring(wire.sourcePrefix))
            val proof=MemoryEvidence(SourceRef("a",id,1),"user",body,body,100,"UTC")
            assertTrue(ImmediateMemoryEvidence.visible(proof,messages,current=id==8L))
        }
        checkSource(5,history.text,"[语音] 晚饭记得趁热吃。")
        checkSource(8,"","[图片]")
        assertEquals(listOf(image),messages.single { it.sourceMessageId=="8" }.imageRefs)
    }
}
