package app.lianxu.independent

/** Attempt-owned display; candidate text is not dialogue. Only acknowledged bubbles are persisted. */
internal enum class ChatReplyPhase { REQUESTING, THINKING, REPLYING }
/** [at] is a character offset in [ChatReplyPreview.reasoning] where a tool receipt interrupted the thinking. */
internal data class ChatThinkingBreak(val at:Int,val toolId:String)
internal data class ChatReplyPreview(val turnId:String,val attempt:Int,val text:String="",val reasoning:String="",val phase:ChatReplyPhase=ChatReplyPhase.REQUESTING,val tools:List<ChatToolActivity> = emptyList(),val thinkingBreaks:List<ChatThinkingBreak> = emptyList(),val bubbles:List<String> = emptyList(),val visible:Int=0)
