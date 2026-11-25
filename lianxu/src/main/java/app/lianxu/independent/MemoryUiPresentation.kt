package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun memoryUiGroup(at:Long,now:Long,zone:ZoneId=ZoneId.systemDefault()):String {
    val date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if(date==today) "今天" else if(date>=today.minusDays(6)&&date<today) "这周" else "更早"
}
internal fun memoryUiLevel(clarity:Double)=when {clarity>=80->0;clarity>=50->1;clarity>=20->2;else->3}
internal fun memoryUiTime(at:Long,now:Long):String=DateTimeFormatter.ofPattern(when(memoryUiGroup(at,now)){"今天"->"HH:mm";"这周"->"EEE";else->"M月d日"},Locale.CHINA).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
internal suspend fun ProcessingRepository.memoryUiClarity(memories:List<StoredMemory>,now:Long=System.currentTimeMillis()):Map<String,Double> = read{db->memories.associate{it.id to MemoryForgetting.state(db,it,now).clarity(now)}}

internal fun memoryUiDate(at:Long)=DateTimeFormatter.ofPattern("M月d日 HH:mm",Locale.CHINA).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
internal fun memoryUiSourceDate(at:Long):String {
 val date=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
 return DateTimeFormatter.ofPattern("M月d日",Locale.CHINA).format(date)+when(date.hour){in 0..11->"上午";in 12..17->"下午";else->"晚上"}
}
internal fun memoryUiClarityLabel(value:Double)=listOf("记得很牢","记得挺清楚","有点模糊","快忘了")[memoryUiLevel(value)]
