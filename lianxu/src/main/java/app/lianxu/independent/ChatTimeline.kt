package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId

internal data class MessageCluster(val id:Long,val mine:Boolean,val friendId:String,val day:Long,val minute:Long,val messages:List<Message>)
internal sealed interface ChatRow {
    val key:String
    data class Date(val day:Long,val firstId:Long):ChatRow{override val key="date-$day-$firstId"}
    data class Group(val value:MessageCluster,val showTime:Boolean):ChatRow{override val key="group-${value.id}"}
    /** A committed memory batch, kept in history like any other chat state. */
    data class Pill(val value:MemoryPill):ChatRow{override val key="pill-${value.batchId}"}
}
internal fun clusterMessages(messages:List<Message>,zone:ZoneId=ZoneId.systemDefault()):List<MessageCluster> = buildList {
    messages.forEach {message->
        val minute=message.created/60000;val day=Instant.ofEpochMilli(message.created).atZone(zone).toLocalDate().toEpochDay()
        val old=lastOrNull()
        if(old!=null&&old.friendId==message.friendId&&old.mine==message.mine&&old.minute==minute&&old.day==day){removeAt(lastIndex);add(old.copy(messages=old.messages+message))}
        else add(MessageCluster(message.id,message.mine,message.friendId,day,minute,listOf(message)))
    }
}
internal fun chatTimeline(messages:List<Message>,pills:List<MemoryPill> = emptyList()):List<ChatRow> = buildList {
    val groups=clusterMessages(messages)
    // Defensive: a batch id is unique in storage, and a duplicated input may never repeat a list key.
    val remaining=pills.distinctBy{it.batchId}.toMutableList()
    groups.forEachIndexed {index,group->
        val previous=groups.getOrNull(index-1)
        if(previous?.day!=group.day)add(ChatRow.Date(group.day,group.id))
        val showTime=previous==null||previous.minute!=group.minute
        val anchored=remaining.filter{p->group.messages.any{it.id==p.afterMessageId}}
        if(anchored.isEmpty()){add(ChatRow.Group(group,showTime));return@forEachIndexed}
        // A pill is a boundary inside the merged group: later same-side messages must stay after it.
        var start=0;var firstPart=true
        anchored.forEach{p->
            val at=group.messages.indexOfFirst{it.id==p.afterMessageId}
            if(at>=start){
                add(ChatRow.Group(group.copy(id=group.messages[start].id,messages=group.messages.subList(start,at+1).toList()),showTime&&firstPart))
                firstPart=false
            }
            add(ChatRow.Pill(p));remaining.remove(p);start=at+1
        }
        if(start<group.messages.size)add(ChatRow.Group(group.copy(id=group.messages[start].id,messages=group.messages.subList(start,group.messages.size).toList()),false))
    }
    // A withdrawn anchor must not hide a committed batch: keep the pill, at the end.
    remaining.forEach{add(ChatRow.Pill(it))}
}
