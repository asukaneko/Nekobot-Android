package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrderedChatReplyTest {
    @Test fun receiptFromPublicReleaseRequiresUnchangedEffectiveSettings() {
        val config = ModelConfig("c","配置","custom","https://example.invalid/v1","m",16000,2048)
        val old = stableDigest("ModelConfig(id=c, name=配置, providerId=custom, baseUrl=https://example.invalid/v1, modelId=m, contextTokens=16000, outputTokens=2048, memoryOutputTokens=0)")
        assertTrue(config.matchesReceivedStamp(old))
        assertFalse(config.copy(thinkingMode="off").matchesReceivedStamp(old))
        assertFalse(config.copy(thinkingCustom="{}").matchesReceivedStamp(old))
        assertFalse(config.copy(streamReplies=false).matchesReceivedStamp(old))
        assertFalse(config.copy(modelId="other").matchesReceivedStamp(old))
    }
    private fun text(s:String)=ReplyPart(s,false)
    private fun voice(s:String)=ReplyPart(s,true)
    private fun arguments(parts:List<ReplyPart>)=JSONObject().put("segments",JSONArray().apply {
        parts.forEach { put(JSONObject().put("type",if(it.voice)"voice" else "text").put("text",it.text)) }
    }).toString()

    @Test fun exactOrderAndOriginalsSurviveStorage() {
        val cases=listOf(listOf(text("一句")),listOf(voice("一句")),listOf(text("甲"),text("乙")),
            listOf(voice("重复"),voice("重复")),listOf(text("一\n\n仍在同一段"),voice("二"),text("三"),voice("四"),voice("五")))
        for(parts in cases){
            assertEquals(parts,OrderedChatReply.parse(arguments(parts)))
            val body=OrderedChatReply.body(parts)
            val layout=OrderedChatReply.encode(parts)
            assertEquals(parts,OrderedChatReply.decode(body,layout))
            assertNull(OrderedChatReply.decode(body+"改动",layout))
            val delivery=ReplyDelivery()
            assertEquals(parts,delivery.finishOrdered(parts))
            assertEquals(parts.map{it.text},delivery.bubbles)
            val restored=ReplyDelivery().also{it.restore("");it.restoreFinal(delivery.bubbles)}
            restored.acknowledge(parts.size)
            assertEquals(body,restored.spoken)
        }
    }
    @Test fun legacyAndMalformedInputs() {
        assertEquals(listOf(voice("原句")),OrderedChatReply.parse("""{"text":"原句"}"""))
        for(raw in listOf("{}", """{"segments":[]}""","""{"segments":[{"type":"html","text":"a"}]}""",
            """{"segments":[{"type":"voice","text":42}]}""",arguments(listOf(voice("长".repeat(301)))),
            """{"text":"重复","segments":[{"type":"text","text":"重复"}]}""")){
            assertNull(OrderedChatReply.parse(raw))
        }
    }
    @Test fun toolBodyIsSoleReplyAndAcknowledgedContentIsNeverDuplicated() {
        val parts=listOf(text("好的"),voice("下班啦"))
        val call=ChatToolCall("reply","send_short_voice",arguments(parts))
        val target=VoiceTarget("owner","voice",1)
        val result=finishVoiceTool(TextChatResult.Success("重复的正文",listOf(call)),"",target,{target},
            VoiceTrace("unit",1),onParts={assertEquals(parts,it)}) { assertEquals(target,it.target) }
        assertEquals("好的\n\n下班啦",(result as TextChatResult.Success).text)
        val delivery=ReplyDelivery()
        delivery.update("好的\n\n尚未发表");delivery.acknowledge(1)
        assertEquals(parts,delivery.finishOrdered(parts))
        delivery.acknowledge(2)
        assertEquals(result.text,delivery.spoken)
        val fromPreviousRound=ReplyDelivery()
        fromPreviousRound.update("好的\n\n草稿");fromPreviousRound.acknowledge(1)
        fromPreviousRound.nextResponse()
        assertEquals(parts,fromPreviousRound.finishOrdered(parts))
        fromPreviousRound.acknowledge(2)
        assertEquals(result.text,fromPreviousRound.spoken)
        val conflicted=ReplyDelivery()
        conflicted.update("已经发表\n\n待定");conflicted.acknowledge(1)
        assertNull(conflicted.finishOrdered(parts));assertEquals("已经发表",conflicted.stop())
    }
    @Test fun repeatedTextHasIndependentAudioAndPlainReplyStillSplitsNormally(){
        val turn=ChatTurn("t","r",1,2,TurnState.COMPLETED,1,"m",null)
        assertNotEquals(OrderedChatReply.audioKey(turn,"原句\n\n原句",0),OrderedChatReply.audioKey(turn,"原句\n\n原句",1))
        assertNull(OrderedChatReply.decode("旧文字",null))
        val segments=(1..12).map(Int::toString)
        val raw=segments.joinToString("\n\n")
        val delivery=ReplyDelivery().also{it.update(raw,final=true)}
        assertEquals(ChatBubbleSegments.forMessage(raw,false),delivery.bubbles)
        assertEquals(segments,delivery.bubbles)
    }
}
