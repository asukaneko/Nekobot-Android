package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 主动联系（含到期回访和关系上的「想联系」）交给模型的原始材料：最近几轮聊天（带时间）、她答应回访的原话、
 * 上次聊天时间和隔了多久、现在的时间。程序不替她写理由或结论，为什么联系、说什么、发不发都由她定。
 */
internal object ProactiveMaterial {
    const val ROUNDS=6
    private const val CLIP=400
    private const val DELETED="【已撤回】"
    /** Old installs may still hold the program-written relationship reason; it is not her words. */
    const val LEGACY_RELATION_REASON="她有点想你了，考虑自然联系。"
    private val FORMAT=DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm",Locale.CHINA)
    /** Same local clock wording for proactive material and the ordinary chat footer. */
    fun currentTime(now:Instant,zone:ZoneId)=now.atZone(zone).format(FORMAT)
    fun relationship(o:CompanionOpportunity)=o.id.startsWith("relationship:")
    /** Only words she wrote herself (a promised follow-up, or her own reason when she put it off). */
    fun ownReason(o:CompanionOpportunity)=o.reason.takeUnless{relationship(o)&&it.startsWith(LEGACY_RELATION_REASON)}?.trim().orEmpty()
    fun recent(messages:List<Message>,rounds:Int=ROUNDS)=messages.filter{it.text!=DELETED&&it.text.isNotBlank()}.takeLast(rounds*2)
    fun ago(ms:Long):String {val m=ms.coerceAtLeast(0);return when{m<60_000->"不到 1 分钟";m<3_600_000->"${m/60_000} 分钟";m<86_400_000->"${m/3_600_000} 小时";else->"${m/86_400_000} 天"}}
    private fun clip(s:String)=if(s.length<=CLIP)s else s.take(CLIP)+"…"
    fun render(o:CompanionOpportunity,recent:List<Message>,source:Message?,now:Long,zone:ZoneId,addressee:String):String=buildString{
        val at=Instant.ofEpochMilli(now)
        append("[现在] ").append(currentTime(at,zone)).append('\n')
        val last=recent.lastOrNull()
        if(last==null)append("[上次聊天] 没有找到可用的聊天记录。\n")
        else append("[上次聊天] ").append(ChatTimeLabels.label(last.created,at,zone)).append("，距现在约 ").append(ago(now-last.created)).append('\n')
        if(recent.isNotEmpty()){
            append("[最近的聊天，时间是程序加的]\n")
            recent.indices.forEach{i->val m=recent[i];append(ChatTimeLabels.prefix(recent,i,at,zone)).append(if(m.mine)"$addressee（消息 ${m.id}）"else"你（回复 ${m.id}）").append("：").append(clip(m.text)).append('\n')}
        }
        val own=ownReason(o)
        if(!relationship(o)){
            append("[你答应过的回访]\n")
            if(own.isNotBlank())append("你当时留下的原话：").append(own).append('\n')
            source?.let{append("当时${addressee}那条消息（").append(ChatTimeLabels.label(it.created,at,zone).removeSurrounding("[","]")).append("，距现在约 ").append(ago(now-it.created)).append("）：").append(clip(it.text)).append('\n')}
        }else if(own.isNotBlank())append("[你上次推迟时留下的话]\n").append(own).append('\n')
        else append("[这次的机会]\n没有约好的回访，只是一次可以主动联系的机会。\n")
    }
}
