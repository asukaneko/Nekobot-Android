package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ReplyDeliveryTest {
    @Test fun restoredReceiptShowsOnlyTheRemainingBubbles(){
        val d=ReplyDelivery();d.restore("一");d.restoreFinal(listOf("一","二","三"))
        assertEquals(1,d.visible);assertFalse(d.completed.isCompleted)
        d.acknowledge(2);assertEquals("一\n\n二",d.stop())
        d.acknowledge(3);assertEquals(2,d.visible);assertFalse(d.completed.isCompleted)
    }
    @Test fun retryRestoresPublishedPrefixAndOnlyAddsNewBubbles() {
        val d=ReplyDelivery();d.restore("已经说过")
        assertEquals(1,d.visible);d.update("接着说的",true)
        assertEquals("已经说过",d.spoken);d.acknowledge(2)
        assertEquals("已经说过\n\n接着说的",d.spoken)
    }
    @Test fun regeneratedPrefixIsNotPublishedTwice() {
        val d=ReplyDelivery();d.restore("第一段\n\n第二段")
        d.update("第一段\n\n第二段\n\n新内容",true)
        assertEquals(listOf("第一段","第二段","新内容"),d.bubbles)
        d.acknowledge(3);assertEquals("第一段\n\n第二段\n\n新内容",d.spoken)
    }
    @Test fun anIdenticalRetryAnswerCompletesWithoutAnExtraBubble() {
        val d=ReplyDelivery();d.restore("已说的话");d.update("已说的话",true)
        assertTrue(d.completed.isCompleted);assertEquals(1,d.bubbles.size)
        assertEquals("已说的话",d.spoken)
    }
    @Test fun receivedTailIsNotSpoken() {
        val d=ReplyDelivery();d.update("第一句\n\n第二句\n\n未写完")
        assertEquals(listOf("第一句","第二句"),d.bubbles)
        assertEquals("",d.spoken)
        d.acknowledge(1)
        assertEquals("第一句",d.stop())
    }
    @Test fun finalResponseWaitsForTheLastBubbleToActuallyAppear() {
        val d=ReplyDelivery();d.update("一\n\n二",true)
        assertFalse(d.completed.isCompleted)
        d.acknowledge(1);assertFalse(d.completed.isCompleted)
        d.acknowledge(2);assertTrue(d.completed.isCompleted)
        assertEquals("一\n\n二",d.spoken)
    }
    @Test fun lateContentAndLayoutCannotReviveStoppedReply() {
        val d=ReplyDelivery();d.update("显示\n\n丢弃",true);d.acknowledge(1);d.stop()
        d.update("晚到答案",true);d.acknowledge(99);d.nextResponse()
        assertEquals("显示",d.spoken);assertFalse(d.completed.isCompleted)
    }
    @Test fun stopBeforeFirstBubbleDoesNotInventReply() {
        val d=ReplyDelivery();d.update("已经生成，但没显示",true)
        assertEquals("",d.stop());assertFalse(d.completed.isCompleted)
    }
    @Test fun switchingAwayAndBackKeepsTheAcknowledgedCount() {
        val d=ReplyDelivery();d.update("一\n\n二\n\n三",true);d.acknowledge(1)
        assertEquals(1,d.visible);assertEquals(3,d.bubbles.size)
        d.acknowledge(0);assertEquals(1,d.visible)
        d.acknowledge(2);assertEquals("一\n\n二",d.stop())
    }
    @Test fun rolesAndAttemptsNeverShareDelivery() {
        val a=ReplyDelivery();val b=ReplyDelivery()
        a.update("甲",true);b.update("乙",true);a.acknowledge(1)
        assertEquals("",b.spoken);a.stop();b.acknowledge(1)
        assertEquals("乙",b.spoken)
    }
    @Test fun toolContinuationPreservesOnlyPreviouslyShownText() {
        val d=ReplyDelivery();d.update("先说过的话\n\n未说完");d.acknowledge(1)
        d.nextResponse();d.update("查完再说的话",true)
        assertEquals(listOf("先说过的话","查完再说的话"),d.bubbles)
        assertEquals("先说过的话",d.spoken)
        d.acknowledge(2);assertEquals("先说过的话\n\n查完再说的话",d.spoken)
    }
    @Test fun streamingAppendDoesNotDuplicateTheFirstBubble() {
        val d=ReplyDelivery();d.update("甲\n\n乙");d.acknowledge(1)
        d.update("甲\n\n乙完成",true);d.acknowledge(2)
        assertEquals("甲\n\n乙完成",d.spoken)
    }
    @Test fun fifthParagraphIsRevealedAndAcknowledgedSeparately() {
        val d=ReplyDelivery();val text="一\n\n二\n\n三\n\n四\n\n五"
        d.update(text);d.acknowledge(3);assertEquals(3,d.visible)
        d.update(text,true);assertEquals(5,d.bubbles.size)
        d.acknowledge(4);assertFalse(d.spoken.endsWith("五"))
        d.acknowledge(5);assertEquals(text,d.spoken)
    }
}
