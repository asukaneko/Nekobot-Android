package app.lianxu.independent

/**
 * 她连发几条：同一轮回复按空行拆出的气泡一条条冒出来。第一条马上出，之后每条按这条的字数等 0.5～2 秒。
 * 只管显示节奏；存储、记忆、关系、重试、删除、复制仍按一整轮回复处理。
 */
internal object ReplyPacing {
    fun gapMillis(segment:String)=(300L+segment.length*50L).coerceIn(500L,2000L)
    /** Wait before bubble [index] appears; the first one appears at once. */
    fun waitBefore(index:Int,segments:List<String>)=if(index<=0)0L else gapMillis(segments.getOrElse(index){""})
    /** How many bubbles may be shown now: closed ones while streaming, all once the reply is stored. */
    fun target(streamingText:String?,storedText:String?):Int=when{
        storedText!=null->ChatBubbleSegments.forMessage(storedText,false).size
        streamingText!=null->ChatBubbleSegments.closedWhileStreaming(streamingText)
        else->0
    }
}
