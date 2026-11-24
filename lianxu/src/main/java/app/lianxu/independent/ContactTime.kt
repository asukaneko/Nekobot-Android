package app.lianxu.independent

import com.google.gson.JsonObject
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** One conversion at the request's fixed clock; persisted plans remain epoch milliseconds. */
internal object ContactTime {
    const val GUIDE="时间用明确自然表达，如‘半小时后’、‘三天后’、‘明天 18:30’或‘2027-01-02 18:30’；不必计算毫秒。不要用‘晚点’等模糊时间。"
    fun clock(at:Long,zone:ZoneId=ZoneId.systemDefault())=Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss VV"))
    fun field(root:JsonObject,name:String,legacy:String,now:Long,zone:ZoneId=ZoneId.systemDefault()):Long {
        require(!(root.has(name)&&root.has(legacy))){"AMBIGUOUS_CONTACT_TIME"}
        val value=root.get(name)?:root.get(legacy)?:error("MISSING_CONTACT_TIME")
        // Numeric input is retained for old stored/test wire records, never requested by the guide.
        val at=if(value.isJsonPrimitive&&value.asJsonPrimitive.isNumber&&!root.has(name))value.asBigDecimal.longValueExact()
            else { require(value.isJsonPrimitive&&value.asJsonPrimitive.isString){"INVALID_CONTACT_TIME"};parse(value.asString,now,zone) }
        require(at>=now){"CONTACT_TIME_IN_PAST"};return at
    }
    fun parse(raw:String,now:Long,zone:ZoneId=ZoneId.systemDefault()):Long {
        val text=raw.trim();require(text.length in 1..80){"INVALID_CONTACT_TIME"}
        if(text=="现在")return now
        val relative=Regex("(半|[零一二两三四五六七八九十百]+|[0-9]+(?:\\.[0-9]+)?)(秒|分钟|小时|天|周)后").matchEntire(text)
        if(relative!=null){
            val number=relative.groupValues[1]
            val amount=when{number=="半"->BigDecimal("0.5");number.first().isDigit()->BigDecimal(number);else->BigDecimal(chinese(number))}
            require(amount.signum()>0){"INVALID_CONTACT_DURATION"}
            val unit=when(relative.groupValues[2]){"秒"->1000L;"分钟"->60000L;"小时"->3600000L;"天"->86400000L;else->604800000L}
            return Math.addExact(now,amount.multiply(BigDecimal(unit)).longValueExact())
        }
        val localNow=Instant.ofEpochMilli(now).atZone(zone)
        val day=Regex("(今天|明天|后天)\\s+([0-9]{1,2}:[0-9]{2})").matchEntire(text)
        val local=if(day!=null){
            val date=localNow.toLocalDate().plusDays(when(day.groupValues[1]){"今天"->0L;"明天"->1L;else->2L})
            LocalDateTime.of(date,LocalTime.parse(day.groupValues[2],DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT)))
        }else LocalDateTime.parse(text,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT))
        val offsets=zone.rules.getValidOffsets(local)
        require(offsets.size==1){"AMBIGUOUS_OR_MISSING_LOCAL_TIME"}
        return local.toInstant(offsets.single()).toEpochMilli().also{require(it>=now){"CONTACT_TIME_IN_PAST"}}
    }
    private fun chinese(value:String):Int {
        val digits=mapOf('零' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        if(value.length==1)return digits[value.single()]?:if(value=="十")10 else error("INVALID_CONTACT_NUMBER")
        // Deliberately bounded grammar; unsupported expressions fail instead of being guessed.
        require(Regex("[一二三四五六七八九]?十[一二三四五六七八九]?").matches(value)){"INVALID_CONTACT_NUMBER"}
        return (if(value.first()=='十')1 else digits.getValue(value.first()))*10+(if(value.last()=='十')0 else digits.getValue(value.last()))
    }
}
