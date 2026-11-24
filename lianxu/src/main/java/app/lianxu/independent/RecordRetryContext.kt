package app.lianxu.independent

/** Only indices of failed receipts appended by this runLoop are eligible. */
internal object RecordRetryContext {
    const val resolved="""{"status":"superseded","note":"这次参数错误已在后面的重试里改正，这轮记账已经记下；不用再处理，也不要再记一次。"}"""
    fun resolve(messages:List<WireMessage>,failed:Set<Int>,success:Boolean):List<WireMessage> =
        if(!success)messages else messages.mapIndexed { index,message ->
            if(index in failed&&message.role=="tool"&&message.name==CompanionRecord.NAME)
                message.copy(content=resolved) else message
        }
}
