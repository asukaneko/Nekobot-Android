package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId

/** Model-facing wording only; identifiers, clocks and receipt semantics stay unchanged. */
internal object ExperiencePresentation {
    const val REFS="[工具引用表，只在调用工具时使用]"
    const val MESSAGE_STATE_LABEL="现有消息状态"
    const val READ_STATE_RULE="已读/未读按聊天页显示记录；未回复不等于未读。"
    fun at(time:Long,now:Long,zone:ZoneId=ZoneId.systemDefault()):String =
        ChatTimeLabels.label(time,Instant.ofEpochMilli(now),zone).removeSurrounding("[","]")+
            if(time>now)"（记录时间晚于现在，需核对）"else ""
    fun reference(source:SourceRef)="消息 ${source.turnId}，版本 ${source.version}"
    fun experience(source:SourceRef,created:Long,text:String,now:Long,zone:ZoneId=ZoneId.systemDefault())=
        "${at(created,now,zone)}记下的经历：\n$text\n$REFS\n这段经历：${reference(source)}"
    fun visibility(readAt:Long?,now:Long,zone:ZoneId=ZoneId.systemDefault())=if(readAt==null)
        "未读"
        else "已读（${at(readAt,now,zone)}）"
    fun receipt(messageId:Long,readAt:Long?,now:Long,zone:ZoneId=ZoneId.systemDefault(),createdAt:Long?=null)=
        "回复 $messageId：${visibility(readAt,now,zone)}"+
            createdAt?.let{"；发送于${at(it,now,zone)}"+(if(it<=now)"，距现在约 ${ProactiveMaterial.ago(now-it)}"else "")}.orEmpty()
    fun permission(policy:String)=when(policy){
        "enabled"->"对方允许主动联系"
        "paused_by_user"->"对方已暂停主动联系"
        "awaiting_user_resume"->"主动联系暂停中，等待对方重新开启"
        "disabled"->"对方尚未开启主动联系"
        else->"主动联系权限尚不明确，不能当作已获许可"
    }
    fun emotion(value:String)=when(value){"calm"->"平静";"happy","joy"->"开心";"sad"->"难过";"anxious"->"焦虑";"angry"->"生气";"curiosity"->"好奇";"care"->"关心";"relief"->"松了一口气";"disappointment"->"失望";"concern"->"担心";else->"尚不明确"}
}
