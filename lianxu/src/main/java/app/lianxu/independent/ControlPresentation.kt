package app.lianxu.independent

/** System reference only. The tool protocol and its success/failure receipts stay unchanged. */
internal object ControlPresentation {
    const val LABEL="本轮操作结果"
    const val NOT_COMPLETED="目前还没有已完成的忘记、更正或停提醒操作。若尚未找到对应记录，就说明尚未定位；若工具报告失败，就按回执说明这次未完成，不把失败说成成功，也不猜原因。"
    fun reference(applied:ExperienceProposal?):PromptReference? {
        if(applied==null)return null
        val result=applied.takeIf{it.controlChecked&&it.targets.isNotEmpty()}?.let{p->when(p.control){
            "forget"->"已停止使用指定经历及相关判断，相关回访也已取消。原聊天仍保留供对方查看，不再引用已停用内容。"
            "correct"->"已停用指定的旧来源及相关判断，相关回访也已取消。原聊天仍保留；更正后的新内容是否记入记忆，以实际写入结果为准，不能仅凭这次操作声称已经记住。"
            "stop_reminding"->"已取消尚未发送的相关回访；已有经历记录和原聊天仍保留，这次没有执行遗忘。"
            else->null
        }}
        val text=if(result==null)NOT_COMPLETED else result+"这项操作已经完成，不要重复执行；结合用户这次原话自然回应。\n"+
            "操作涉及的原消息编号（仅核对结果，不代表可重新使用已停用内容）："+applied.targets.distinct().joinToString("、")+"。"
        return PromptReference(LABEL,text,true)
    }
}
