package app.lianxu.independent
import org.junit.Assert.assertEquals
import org.junit.Test
class ChatBubbleSegmentsTest {
    @Test fun paragraphs() { assertEquals(listOf("一", "二"), ChatBubbleSegments.split("一\n\n二")) }
    @Test fun whitespace() { assertEquals(listOf("一", "二"), ChatBubbleSegments.split("\n \n一\n \n\n二\n\n")) }
    @Test fun single() { assertEquals(listOf("一\n二"), ChatBubbleSegments.split("一\n二")) }
    @Test fun empty() { assertEquals(emptyList<String>(), ChatBubbleSegments.split(" \n\n")) }
    @Test fun streaming() {
        assertEquals(listOf("第一段", "第二"), ChatBubbleSegments.split("第一段\n\n第二"))
        assertEquals(listOf("第一段"), ChatBubbleSegments.split("第一段\n"))
    }
    @Test fun fences() {
        val code="说明\n```kotlin\na\n\nb\n```\n结束"
        assertEquals(listOf(code,"下一段"),ChatBubbleSegments.split(code+"\n\n下一段"))
        assertEquals(listOf("```\na\n\nb"),ChatBubbleSegments.split("```\na\n\nb"))
    }
    @Test fun allParagraphsKeepTheirOwnBubble() {
        val parts=(1..10).map(Int::toString)
        assertEquals(parts,ChatBubbleSegments.forMessage(parts.joinToString("\n\n"),false))
        assertEquals(listOf("一","二","三","四"),ChatBubbleSegments.forMessage("一\n\n二\n\n三\n\n四",false))
    }
    @Test fun whileStreamingOnlyClosedBubblesCount() {
        assertEquals(0,ChatBubbleSegments.closedWhileStreaming("第一段还在写"))
        assertEquals(1,ChatBubbleSegments.closedWhileStreaming("第一段\n\n第二"))
        assertEquals(2,ChatBubbleSegments.closedWhileStreaming("第一段\n\n第二段\n\n"))
        assertEquals("every closed paragraph becomes visible",10,ChatBubbleSegments.closedWhileStreaming((1..10).joinToString("\n\n",postfix="\n\n")))
        assertEquals("a blank line inside a code fence does not close a bubble",0,ChatBubbleSegments.closedWhileStreaming("```\na\n\n"))
    }
    @Test fun mine() { assertEquals(listOf("一\n\n二"),ChatBubbleSegments.forMessage("一\n\n二",true)) }
}
