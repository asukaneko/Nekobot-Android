package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/**
 * 回话主提示词不再带固定分析步骤（事件→意义→判断→感受→选择），但同一句里有效的边界要留着：
 * 不跟着对方情绪走、不必每次都有感受、先顾好当前请求与界限、可以温和不同意（改成人话，不再出现「用户」）；
 * 还要有「说到事实只按原话和有效资料、工具结果不能编」这句。
 */
class ReplyContractWordingTest {
    private val source=SourceRef("a",2,1)
    private val context=RoleContext("a",source,mapOf(2L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)

    private fun replySystem(persona:UserPersona=UserPersona()):String{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Prompt preparation must not call a model")}
        val base=CompanionPrompt.build(Friend("a","合成角色","成年"),RolePrompts(),emptyList(),"问候",emptyList(),capacity=64000,outputLimit=2048,persona=persona) as PromptBuild.Ready
        val ready=RolePlanner(client).prepareReply(base,config,"问候",ExperienceProposal(),context,0,false,emptyMap(),0,persona=persona) as PromptBuild.Ready
        return ready.request.messages.first().content.let{assertTrue("回话规矩要和人设放在开头",it.contains("[companion.reply.v1]"));"[companion.reply.v1]"+it.substringAfter("[companion.reply.v1]")}
    }

    @Test fun addressedSentences() {
        for((persona,name) in listOf(UserPersona(name="名字",address="小星") to "小星",UserPersona(name="名字") to "名字",UserPersona() to "对方")) {
            assertTrue(replySystem(persona).contains("只输出给${name}看的正文"))
            val base=CompanionPrompt.build(Friend("a","角色","{{user}}"),RolePrompts(),emptyList(),"你好",persona=persona) as PromptBuild.Ready
            val text=base.request.messages.first().content
            assertTrue(text.contains("你就是角色，正在和${name}一对一聊天。"))
            assertTrue(text.contains("输出面向${name}的正文"))
            assertTrue(base.request.messages.last().content.contains("想到对方时就用平时的称呼"))
            assertFalse(text.contains("[思考语言]"))
            for(system in listOf(text,replySystem(persona).substringBefore("{\""))) { assertFalse(system.contains("{{"));assertFalse(system.contains("}}")) }
        }
    }

    @Test fun fixedAnalysisStepsAreGone(){
        val system=replySystem()
        assertFalse("固定流程「事件→…→选择」必须移除","事件→" in system)
        assertFalse("不能留逐项分析的说法","对自身目标的意义" in system)
        assertFalse("不能留连锁步骤","判断→感受" in system)
    }

    @Test fun boundariesSurviveWithoutTheSteps(){
        val system=replySystem()
        for(hint in listOf("不用强行附和或跟着对方的情绪走","也不用每次都说自己有什么感受","先顾好对方这会儿要你做的事、说过的界限和你能做的范围","有不同意见或小脾气时，按你自己的性子直接说出来就好")){
            assertTrue("边界句应保留：$hint",hint in system)
        }
        assertFalse("不再强行要求温和客服腔","温和地说出来" in system)
        val mood=system.lines().first{"的心情是" in it}
        assertFalse("这一行用称呼，不用「用户」","用户" in mood)
        assertFalse("不再是说明书腔","不复制用户情绪" in system)
    }
    @Test fun sheDecidesWhetherToSendSeveralShortMessages(){
        assertTrue(replySystem().contains("想分开说就用空行隔开，会像连发几条消息一样一条条发出去；不想分就写成一段。分不分、发几条全由你决定。"))
    }
    @Test fun historyTimesAreForReferenceOnly(){
        assertTrue(replySystem().contains("聊天记录每条前面的时间（比如「[昨天 22:10]」）和「（隔了 3 天）」是程序加的，只给你参考，回话别照这种格式念出来。"))
    }
    @Test fun factsComeOnlyFromTheseWordsAndValidSources(){
        for((persona,name) in listOf(UserPersona(name="名字",address="小星") to "小星",UserPersona() to "对方")){
            assertTrue(replySystem(persona).contains("说到事实，只按${name}这次的原话和还有效的资料来，工具结果也不能编。"))
        }
    }

    @Test fun noNewShortcutsOrEnvelope(){
        val system=replySystem()
        assertFalse("不写禁止想工具","禁止想工具" in system)
        assertFalse("不强制第一人称意识流","第一人称" in system)
        assertFalse("不出现最终JSON式矛盾措辞","最终JSON" in system)
        assertFalse("回话阶段不出现思考外观开关","思考外观" in system)
    }

    @Test fun recordEnabledToggleStillWorks(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Prompt preparation must not call a model")}
        val base=CompanionPrompt.build(Friend("a","合成角色","成年"),RolePrompts(),emptyList(),"问候",emptyList(),capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val off=RolePlanner(client).prepareReply(base,config,"问候",ExperienceProposal(),context,0,false,emptyMap(),0,false) as PromptBuild.Ready
        val system=off.request.messages.first().content.let{assertTrue("回话规矩要和人设放在开头",it.contains("[companion.reply.v1]"));"[companion.reply.v1]"+it.substringAfter("[companion.reply.v1]")}
        assertFalse("关掉记账时不再提示记下来","顺手记下来" in system)
        assertTrue("关掉记账时明确说本轮不提供","本轮不提供记账工具" in system)
    }
}
