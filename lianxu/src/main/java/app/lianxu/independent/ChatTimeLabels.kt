package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 发给模型的近期聊天记录里，程序在每条前面加的简短时间：「[今天 08:05]」「[昨天 22:10]」，更早用
 * 「[11月8日 22:10]」（跨年再带年份）；两条相隔超过半天，在后一条前面加一行「（隔了 3 天）」/「（隔了 14 小时）」。
 * 只进模型输入，不存库、不改界面。
 */
internal object ChatTimeLabels {
    private const val HOUR=3_600_000L
    private val clock=DateTimeFormatter.ofPattern("HH:mm")
    fun label(created:Long,now:Instant,zone:ZoneId):String {
        val at=Instant.ofEpochMilli(created).atZone(zone);val today=now.atZone(zone).toLocalDate();val day=at.toLocalDate()
        val date=when{
            day==today->"今天"
            day==today.minusDays(1)->"昨天"
            day.year==today.year->"${day.monthValue}月${day.dayOfMonth}日"
            else->"${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }
        return "[$date ${at.format(clock)}]"
    }
    /** Only gaps over half a day are worth saying. */
    fun gap(previous:Long,next:Long):String? {
        val diff=next-previous
        if(diff<=12*HOUR)return null
        return if(diff>=24*HOUR)"（隔了 ${diff/(24*HOUR)} 天）" else "（隔了 ${diff/HOUR} 小时）"
    }
    /** The program-added head for message [index] of [recent]; the original text follows it unchanged. */
    fun prefix(recent:List<Message>,index:Int,now:Instant,zone:ZoneId):String {
        val m=recent[index]
        val gap=recent.getOrNull(index-1)?.let{gap(it.created,m.created)}
        return (gap?.let{"$it\n"}.orEmpty())+label(m.created,now,zone)+" "
    }
}
