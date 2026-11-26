package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 她连发几条：第一条马上出，之后每条按字数等 0.5～2 秒；流式时只放已经写完的气泡，存好后全部放出。 */
class ReplyPacingTest {
    @Test fun gapFollowsLengthWithinHalfToTwoSeconds(){
        assertEquals(500L,ReplyPacing.gapMillis("嗯"))
        assertEquals(2000L,ReplyPacing.gapMillis("长".repeat(200)))
        assertTrue(ReplyPacing.gapMillis("今天有点累，先去洗个澡")>ReplyPacing.gapMillis("好"))
    }
    @Test fun theFirstBubbleAppearsAtOnce(){
        val segments=listOf("在呢","刚下班，今天好累")
        assertEquals(0L,ReplyPacing.waitBefore(0,segments))
        assertEquals(ReplyPacing.gapMillis("刚下班，今天好累"),ReplyPacing.waitBefore(1,segments))
    }
    @Test fun targetIsClosedBubblesWhileStreamingAndAllOnceStored(){
        assertEquals(1,ReplyPacing.target("在呢\n\n刚下",null))
        assertEquals(2,ReplyPacing.target("在呢\n\n刚下",storedText="在呢\n\n刚下班"))
        assertEquals(5,ReplyPacing.target(null,"一\n\n二\n\n三\n\n四\n\n五"))
        assertEquals(0,ReplyPacing.target(null,null))
    }
}
