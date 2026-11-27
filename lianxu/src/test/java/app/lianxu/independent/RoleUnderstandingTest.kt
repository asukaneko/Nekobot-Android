package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RoleUnderstandingTest {
    @Test fun chatProjectionOmitsOldAppraisals(){
        val entry=RoleEntry(ref(1),flow(1),null)
        val text=RoleContext("a",ref(2),emptyMap(),listOf(entry),emptyList(),emptyList(),emptyList()).prompt()
        assertFalse(text.contains("理解1"));assertFalse(text.contains(entry.flow.appraisal!!.meaning))
    }
    private fun ref(n:Long)=SourceRef("a",n,1)
    private fun flow(n:Long,learning:RoleLearning?=null,eventRelation:String="new_event",certainty:String="reported",used:List<String> = emptyList(),feedback:List<RoleFeedback> = emptyList()):RoleUnderstanding {
        val e=listOf(RoleEvidence(ref(n)))
        val all=(e+learning?.evidence.orEmpty()).distinct()
        return RoleUnderstanding(appraisal=RoleAppraisal("一次有来源的交流","我对共同解题产生兴趣",certainty,"探索共同问题","shared_interaction","puzzle",eventRelation,emptyList(),e),feelings=listOf(RoleFeeling("curiosity","moderate","问题的解法","共同探索的新理解",e)),goal=RoleGoal("探索一个问题","role_interest","active","我的好奇与当前话题有关",e),choices=listOf(RoleChoice("x","explore","愿意理解解法",e)),selected="x",judgment=RoleJudgment("我开始发现这个活动也有值得投入的部分","inference",e),feedback=feedback,learning=learning,usedLearningIds=used,consideredLearningIds=used,future=null,dependencies=all)
    }
    private fun action(n:Long,flow:RoleUnderstanding)=RoleActualAction(ref(n),flow.selectedChoice()!!.action,flow.appraisal!!.situation,n+1,"确实提交的一段合成交流",flow.usedLearningIds,flow.consideredLearningIds,flow.goal!!.origin,null,null)
    private fun entries(kind:String="interest",relation:String="progress",certain:String="reported",withResultEvidence:Boolean=true):List<RoleEntry> {
        val first=flow(1,RoleLearning("candidate",kind,"共同解题","也许这个活动有可取之处","propose",listOf(RoleEvidence(ref(1)))))
        val evidence=listOf(RoleEvidence(ref(3)))+if(withResultEvidence)listOf(RoleEvidence(ref(1),"action",2))else emptyList()
        val second=flow(3,RoleLearning("candidate",kind,"共同解题","在新的共同结果后，我对共同解题更有兴趣","revise",evidence),relation,certain)
        return listOf(RoleEntry(ref(1),first,action(1,first)),RoleEntry(ref(3),second,action(3,second)))
    }
    @Test fun retiredLearningCannotReenterModelMaterialThroughOldEntries(){
        val rows=entries()
        val learning=RoleLearningProjector.project(rows).single()
        val context=RoleContext("a",ref(5),rows.associate{it.source.turnId to it.source},rows,rows.mapNotNull{it.action},listOf(learning),emptyList())
        for(value in listOf(context.copy(learning=listOf(learning.copy(state="faded"))),
            context.copy(learning=emptyList(),learningControls=mapOf(learning.id to LearningControl(deleted=true))),
            context.copy(learning=listOf(learning.copy(state="paused")),learningControls=mapOf(learning.id to LearningControl(paused=true))))){
            val visible=value.visibleLearning()
            assertTrue(visible.learning.isEmpty());assertTrue(visible.entries.all{it.flow.learning==null})
            assertFalse(visible.prompt().contains(learning.content))
        }
    }
    @Test fun groundedFeedbackRaisesHeatOncePerActualAction(){
        val prior=entries(kind="habit")
        val change=prior.last().flow.copy(usedLearningIds=listOf("candidate"),consideredLearningIds=listOf("candidate"))
        val revised=prior.dropLast(1)+RoleEntry(ref(3),change,action(3,change),at=100)
        val feedback=flow(5,feedback=listOf(RoleFeedback(4,"helpful","这次很有帮助")))
        val evidence=revised+RoleEntry(ref(5),feedback,null,at=100)+RoleEntry(ref(7),feedback,null,at=100)
        val item=RoleLearningProjector.project(evidence).single()
        assertEquals(setOf(2L,4L),item.supportingResults);assertEquals(85.0,item.temperature.anchor,0.001)
        assertEquals(56,item.temperature.days)
    }
    @Test fun lightRevisionUsesOnlyTheCurrentUserFactAndCountsEachMessageOnce(){
        val prior=entries().first().copy(at=1000)
        fun revise(previous:List<RoleEntry>,id:Long,evidence:Long=id,nullQuote:Boolean=false):RoleEntry {
            val current=ref(id)
            val known=(previous.map{it.source}+current).associateBy{it.turnId}
            val view=RoleContext("a",current,known,previous,emptyList(),RoleLearningProjector.project(previous),emptyList())
            val wire="""{"learning":{"id":"candidate","kind":"interest","scope":"共同解题","content":"想接着解题","phase":"revise","evidence":[{"source":$evidence,"layer":"fact"}]}}"""
            val payload=com.google.gson.JsonParser.parseString(wire).asJsonObject
            if(nullQuote)payload["learning"].asJsonObject.add("resultQuote",com.google.gson.JsonNull.INSTANCE)
            val flow=RoleUnderstandingContract.parse(payload.toString(),"今天又解开了一题",ExperienceProposal(),view,1000,false,lenient=true,learningOnly=true){_,_->}
            RoleUnderstandingContract.validateAccepted(flow,view,"今天又解开了一题")
            return RoleEntry(current,flow,null,at=1000)
        }
        val second=revise(listOf(prior),3)
        val rows=listOf(prior,second,second)
        val once=RoleLearningProjector.project(rows).single()
        assertEquals(60.0,once.temperature.anchor,0.0);assertEquals(28,once.temperature.days);assertEquals(2,once.version)
        val after=RoleLearningProjector.project(rows+revise(listOf(prior,second),5,nullQuote=true)).single()
        assertEquals(85.0,after.temperature.anchor,0.0);assertEquals(56,after.temperature.days)
        assertNull(revise(listOf(prior),3,evidence=1).flow.learning)
        assertNull(revise(listOf(prior),3,evidence=99).flow.learning)
        assertTrue(RoleLearningProjector.project(listOf(second)).isEmpty())
        assertEquals(35.0,RoleLearningProjector.project(listOf(prior)).single().temperature.anchor,0.0)
    }

    @Test fun lightRevisionStillChecksAnOptionalActionAndExactQuote(){
        val prior=entries().first()
        val source=ref(3)
        val view=RoleContext("a",source,mapOf(1L to ref(1),3L to source),listOf(prior),listOf(prior.action!!),
            RoleLearningProjector.project(listOf(prior)),emptyList())
        val input="这次按你说的试了，真的解开了"
        fun revision(quote:String):RoleUnderstanding {
            val raw="""{"learning":{"id":"candidate","kind":"interest","scope":"共同解题","content":"想继续一起解题","phase":"revise","resultQuote":"$quote","evidence":[{"source":3,"layer":"fact"},{"source":1,"layer":"action","message":2}]}}"""
            return RoleUnderstandingContract.parse(raw,input,ExperienceProposal(),view,200000,false,lenient=true,learningOnly=true){_,_->}
        }
        val revised=revision("真的解开了")
        RoleUnderstandingContract.validateAccepted(revised,view,input)
        assertNull(revised.appraisal);assertEquals("active",RoleLearningProjector.project(listOf(prior,RoleEntry(source,revised,null))).single().state)
        assertNull(revision("原话里没有的结果").learning)
    }
}
