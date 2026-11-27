package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class LearningRecordsAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var fixture:LearningRecordsFixture
    private lateinit var database:String

    @Before fun setup():Unit=runBlocking {
        database="learning-records-${System.nanoTime()}.db"
        store=Store(context,database)
        store.add(listOf(Friend("a","合成甲","测试"),Friend("b","合成乙","测试")),false)
        fixture=LearningRecordsFixture(store)
    }
    @After fun close(){store.close();context.deleteDatabase(database)}

    @Test fun futureSourceAndKindRemainReadableWithVerifiedOriginalEvidence():Unit=runBlocking{
        val first=fixture.proposeHabit()
        val previous=requireNotNull(fixture.core.cached(first))
        val flow=requireNotNull(previous.understanding)
        val future=requireNotNull(flow.learning).copy(kind="future-kind",origin="future-origin")
        store.writableDatabase.execSQL("UPDATE companion_sources SET proposal=? WHERE role=? AND source_id=?",
            arrayOf<Any>(Gson().toJson(previous.copy(understanding=flow.copy(learning=future))),"a",first.userMessageId))
        val restored=fixture.core.learningRecords("a").single()
        assertEquals("future-origin",restored.origin);assertEquals("future-kind",restored.state.kind)
        assertEquals(first.userMessageId,restored.evidence.single().messageId)
        fixture.core.forget("a",first.userMessageId);assertTrue(fixture.core.learningRecords("a").isEmpty())
    }
}
