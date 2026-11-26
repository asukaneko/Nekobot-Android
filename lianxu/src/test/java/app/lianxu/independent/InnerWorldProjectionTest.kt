package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class InnerWorldProjectionTest {
    private val start=1000L
    private val day=RelationshipProjector.DAY
    private val ref=SourceRef("a",7,1)
    private val state=RoleLearningState("l","habit","读书时","先读后聊","candidate",ref,temperature=LearningHeat(at=start))
    private fun project(at:Long,control:LearningControl=LearningControl())=
        LearningControls.apply(listOf(state),mapOf(state.id to control),at)
    private fun prompt(values:List<RoleLearningState>)=RoleContext("a",ref,mapOf(7L to ref),emptyList(),emptyList(),values,emptyList()).prompt()
    @Test fun heatDecaysAndEachIndependentConfirmationHasACap(){
        val base=state.temperature
        assertEquals(17.5,base.value(start+14*day),1e-9)
        val second=base.reinforced(start+14*day)
        assertEquals(42.5,second.anchor,1e-9);assertEquals(28,second.days)
        assertEquals("刚了解",project(start).single().familiarity)
        assertEquals("挺上心",state.copy(heat=42.5).familiarity)
        var hot=second
        repeat(6){hot=hot.reinforced(second.at)}
        assertEquals(120,hot.days);assertEquals(100.0,hot.anchor,1e-9)
        val faded=project(start+28*day)
        assertEquals("faded",faded.single().state);assertFalse(prompt(faded).contains(state.content))
    }
    @Test fun controlsSurviveBackupAndNeverRestoreWithdrawnSources(){
        val at=start+28*day
        val pin=LearningControl(pinned=true,heldHeat=8.75,changedAt=at)
        val saved=LearningControls.decode(Gson().toJson(mapOf("l" to pin)))
        assertEquals(mapOf("l" to pin),saved)
        val active=project(at+200*day,saved.getValue("l"))
        assertEquals(3,active.single().dots);assertTrue(prompt(active).contains("已记牢"))
        assertEquals(8.75,active.single().heat,1e-9)
        assertTrue(LearningControls.apply(emptyList(),saved,at).isEmpty())
        assertFalse(prompt(project(at,pin.copy(paused=true))).contains(state.content))
        assertTrue(project(at,pin.copy(deleted=true)).isEmpty())
        assertEquals("faded",project(at,pin.copy(pinned=false)).single().state)
        assertTrue(runCatching{LearningControls.decode("""{"l":{"pinned":"true"}}""")}.isFailure)
    }
    @Test fun oldChatAndNotesRetainDefaultsAndDisputeSurvivesRoundTrip(){
        val gson=Gson()
        val legacy=gson.fromJson("""{"id":"l","kind":"habit","scope":"读书","content":"聊天","phase":"propose","evidence":[]}""",RoleLearning::class.java)
        assertEquals("chat",legacy.origin)
        val old=gson.fromJson("""{"text":"想分享今天的书","at":1000}""",ReviewInnerNote::class.java)
        assertTrue(old.visible(start,ref){true})
        val archived=BackupRemapper("a","b",emptyMap(),mapOf(7L to 10L)).json(JsonParser.parseString(gson.toJson(old.copy(disputed=true))))
        val restored=gson.fromJson(archived,ReviewInnerNote::class.java)
        assertTrue(restored.disputed);assertFalse(restored.visible(start,ref){true})
        assertFalse(old.visible(start+day,ref){true})
    }
    @Test fun sixSkiesTrackTheStrongestCurrentFeeling(){
        val calm=RelationState(.3,.4,.0,.0,.0,.0,.0,emptyMap(),null)
        assertEquals(MoodSky.CALM,moodSky(calm))
        assertEquals(MoodSky.HAPPY,moodSky(calm.copy(happy=.7)))
        assertEquals(MoodSky.MISSING,moodSky(calm.copy(missing=.6,tension=.3)))
        assertEquals(MoodSky.HURT,moodSky(calm.copy(hurt=.6)))
        assertEquals(MoodSky.HURT,moodSky(calm.copy(low=.5)))
        assertEquals(MoodSky.ANGRY,moodSky(calm.copy(unhappy=.5)))
        assertEquals(MoodSky.KNOT,moodSky(calm.copy(tension=.3)))
    }
}
