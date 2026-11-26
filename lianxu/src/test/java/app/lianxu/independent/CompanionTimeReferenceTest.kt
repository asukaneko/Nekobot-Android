package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CompanionTimeReferenceTest {
    private val friend=Friend("a","合成角色","纯虚构设定")
    private val now=Instant.parse("2026-11-14T10:00:00Z")
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun ready(history:List<Message>,input:String="今天过得怎样？",adaptive:Boolean=false)=CompanionPrompt.build(friend,RolePrompts(),history,input,now=now,zone=zone,adaptive=adaptive,capacity=64000) as PromptBuild.Ready
    @Test fun fortyRoundsKeepEveryOriginalBodyRoleIdAndOrderInBothModes(){
        val history=(1..40).flatMap{n->listOf(Message(n*2L,"a","合成用户第$n 轮",true,now.minusSeconds((81-n*2)*60L).toEpochMilli()),Message(n*2L+1,"a","合成回复第$n 轮",false,now.minusSeconds((80-n*2)*60L).toEpochMilli()))}
        for(adaptive in listOf(false,true)){
            val result=ready(history,adaptive=adaptive);val sent=CompanionPrompt.forSend(result.request,emptyList())
            val wire=sent.messages.filter{it.sourceMessageId.isNotBlank()}
            assertEquals(80,wire.size);assertEquals(history.map{it.text},wire.map{it.content});assertEquals(history.map{it.id.toString()},wire.map{it.sourceMessageId})
            assertEquals(history.map{if(it.mine)"user"else"assistant"},wire.map{it.role});assertTrue(wire.all{it.sourcePrefix==0})
            val reference=sent.messages.single{it.turnContext};assertEquals("system",reference.role);assertEquals(reference,sent.messages[sent.messages.size-2])
            assertTrue(sent.messages.last().thinkingContext)
            assertTrue(reference.content.contains("第80条（我）：今天 18:00"));assertEquals(80,Regex("第[0-9]+条（").findAll(reference.content).count())
            assertTrue(result.boundaryReached)
        }
    }
    @Test fun userRequestedBracketsAndAuthoredTimesAreNeverFiltered(){
        val history=listOf(Message(1,"a","[17:29] 请保留这段引用，明天下午三点见。",true,now.minusSeconds(120).toEpochMilli()),Message(2,"a","[17:29] [引用] 明天下午三点，我会把茶准备好。",false,now.minusSeconds(60).toEpochMilli()))
        val input="这一条请原样写出 [17:29]，不要删除方括号；再说周五下午三点。"
        val sent=CompanionPrompt.forSend(ready(history,input).request,emptyList())
        assertEquals(history.map{it.text},sent.messages.filter{it.sourceMessageId.isNotBlank()}.map{it.content})
        assertEquals(input,sent.messages.single{it.role=="user"&&it.sourceMessageId.isBlank()}.content)
        val reference=sent.messages.single{it.turnContext}.content;assertTrue(reference.contains("对方明确要求的时间"));assertTrue(reference.contains("自然的时间表达照常保留"))
        assertEquals("[17:29] [引用] 明天下午三点，我会把茶准备好。",history[1].text)
    }
    @Test fun futureClockWarningAndHistoricalTimeStaySeparateFromBody(){
        val history=listOf(Message(1,"a","原话里写着昨天晚上九点。",true,now.plusSeconds(60).toEpochMilli()))
        val sent=ready(history).request.messages;assertEquals(history.single().text,sent.single{it.sourceMessageId=="1"}.content)
        val reference=sent.single{it.turnContext}.content;assertTrue(reference.contains("历史消息时间晚于当前设备时间"));assertTrue(reference.contains("第1条（对方）：今天 18:01"));assertFalse(reference.contains(history.single().text))
    }
    @Test fun toolContinuationKeepsOneReferenceAndRawHistory(){
        val history=listOf(Message(1,"a","在吗",true,now.minusSeconds(120).toEpochMilli()),Message(2,"a","在，刚泡好茶。",false,now.minusSeconds(60).toEpochMilli()))
        val first=CompanionPrompt.forSend(ready(history).request,emptyList());val reference=first.messages.single{it.turnContext};val thinking=first.messages.single{it.thinkingContext}
        val call=ChatToolCall("synthetic-call","agent_history_search","{}")
        val continued=CompanionPrompt.forSend(first.copy(messages=first.messages+WireMessage("assistant","",toolCalls=listOf(call))+WireMessage("tool","纯虚构工具结果",toolCallId=call.id,name=call.name)),emptyList())
        assertEquals(first.messages.filter{it.sourceMessageId.isNotBlank()},continued.messages.filter{it.sourceMessageId.isNotBlank()})
        assertEquals(listOf(reference),continued.messages.filter{it.turnContext});assertEquals(listOf(thinking),continued.messages.filter{it.thinkingContext})
        assertEquals(listOf(reference,thinking),continued.messages.takeLast(2))
        assertEquals("纯虚构工具结果",continued.messages.single{it.role=="tool"}.content)
        assertEquals(continued.messages.indexOfFirst{it.role=="assistant"&&it.toolCalls.isNotEmpty()}+1,continued.messages.indexOfFirst{it.role=="tool"})
        assertEquals(first.messages.last{it.role=="user"},continued.messages.last{it.role=="user"})
        assertFalse(continued.messages.last{it.role=="user"}.content.endsWith("\n\n"+CompanionPrompt.THINKING_REMINDER))
        assertEquals(1,continued.messages.sumOf{Regex.fromLiteral("[思考语言]").findAll(it.content).count()})
        assertFalse(continued.messages.first().content.contains("[思考语言]"))
        assertTrue(thinking.content.endsWith(CompanionPrompt.DEFAULT_THINKING_BODY))
    }
    @Test fun blankRowsRemainUnmodifiedWithoutInventingSpokenTextOrBreakingNumbering(){
        val history=listOf(Message(1,"a","早",true,now.minusSeconds(120).toEpochMilli()),Message(2,"a"," \n ",false,now.minusSeconds(90).toEpochMilli()),Message(3,"a","再聊一句",true,now.minusSeconds(60).toEpochMilli()))
        val sent=ready(history).request.messages;val wire=sent.filter{it.sourceMessageId.isNotBlank()}
        assertEquals(listOf("1","3"),wire.map{it.sourceMessageId});assertEquals(listOf("早","再聊一句"),wire.map{it.content});assertEquals(" \n ",history[1].text)
        val reference=sent.single{it.turnContext}.content;assertTrue(reference.contains("第2条（对方）：今天 17:59"));assertFalse(reference.contains("第3条（"))
    }
    @Test fun adaptiveSummaryCanReleaseTheTimeRowsTogetherWithOldRounds(){
        fun build(history:List<Message>)=CompanionPrompt.build(friend,RolePrompts(),history,"继续聊",now=now,zone=zone,adaptive=true,capacity=4096,outputLimit=1024)
        assertTrue(build(emptyList()) is PromptBuild.Ready)
        val history=(1..300).flatMap{n->listOf(Message(n*2L,"a","甲",true,now.minusSeconds((601-n*2)*60L).toEpochMilli()),Message(n*2L+1,"a","乙",false,now.minusSeconds((600-n*2)*60L).toEpochMilli()))}
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(build(history) as PromptBuild.Blocked).reason)
    }
    @Test fun explicitClockQuestionStillHasCurrentTimeAndNoUserSuffix(){
        val input="现在几点？请自然告诉我时分。";val sent=CompanionPrompt.forSend(ready(emptyList(),input).request,emptyList())
        assertEquals(input,sent.messages.single{it.role=="user"}.content)
        assertTrue(sent.messages.single{it.turnContext}.content.contains("当前设备时间：2026年11月14日 星期六 18:00"))
        assertEquals(CompanionPrompt.thinkingLanguage(friend.name),sent.messages.last().content)
        assertEquals(1,sent.messages.count{it.turnContext});assertEquals(1,sent.messages.count{it.thinkingContext})
    }
}
