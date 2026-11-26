package app.lianxu.independent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun MemoryTimeline(shown:List<StoredMemory>,all:List<StoredMemory>,edges:List<MemoryRecall.Edge>,
    clarity:Map<String,Double>,list:LazyListState,manual:Boolean,editable:Boolean,add:()->Unit,open:(String)->Unit) {
    val gold=Color(0xFFC49352)
    val byId=remember(all){all.associateBy{it.id}}
    val links=remember(edges){edges.flatMap{listOf(it.sourceId to it.targetId,it.targetId to it.sourceId)}.groupBy({it.first},{it.second})}
    Box(Modifier.fillMaxSize().testTag("memory-timeline")) {
        LazyColumn(Modifier.fillMaxSize().testTag("memory-list"),state=list){
            if(manual)item{TextButton(add,enabled=editable,modifier=Modifier.testTag("memory-add")){Text("＋ 写一条想让她记住的事")}}
            if(shown.isEmpty())item{Text(if(manual)"写下希望她记住的小事。" else "还没有记下什么，多聊聊吧",Modifier.padding(vertical=32.dp),color=Muted)}
            items(shown,key={it.id}){m->
                val strength=((clarity[m.id]?:100.0)/100).toFloat().coerceIn(0f,1f)
                val ink=lerp(Muted,Ink,if(m.core)1f else strength)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)){
                    Canvas(Modifier.width(42.dp).fillMaxHeight()){
                        val center=Offset(21.dp.toPx(),22.dp.toPx())
                        drawLine(gold.copy(alpha=.53f),Offset(center.x,0f),Offset(center.x,size.height),1.dp.toPx())
                        if(m.core)drawCircle(Brush.radialGradient(listOf(gold.copy(alpha=.55f),gold.copy(alpha=0f)),center,14.dp.toPx()),14.dp.toPx(),center)
                        drawCircle(if(m.core)gold else ink,(if(m.core)5f else 2f+strength*2f).dp.toPx(),center)
                    }
                    Column(Modifier.weight(1f).padding(vertical=10.dp)){
                        Column(Modifier.fillMaxWidth().clickable{open(m.id)}.testTag("memory-${m.id}")){
                            Text(memoryUiDate(m.createdAt)+listOfNotNull("核心".takeIf{m.core},"锁定".takeIf{m.locked}).joinToString(""){" · $it"},color=Muted,style=MaterialTheme.typography.labelSmall)
                            Text(m.text,Modifier.padding(vertical=5.dp),color=ink,fontFamily=FontFamily.Serif,style=MaterialTheme.typography.bodyLarge,maxLines=4,overflow=TextOverflow.Ellipsis)
                        }
                        links[m.id].orEmpty().distinct().mapNotNull(byId::get).forEach{other->
                            Text("↪ 牵连："+other.text,Modifier.clickable{open(other.id)}.testTag("memory-link-${m.id}-${other.id}").padding(vertical=6.dp),
                                color=GreenText,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Canvas(Modifier.width(42.dp).fillMaxHeight()){
            val positions=list.layoutInfo.visibleItemsInfo.associate{it.key to (it.offset+22.dp.toPx())}
            val x=21.dp.toPx()
            edges.forEach{edge->
                val a=positions[edge.sourceId]?:return@forEach
                val b=positions[edge.targetId]?:return@forEach
                drawPath(Path().apply{moveTo(x,a);cubicTo(0f,a,0f,b,x,b)},gold.copy(alpha=.64f),style=Stroke(1.dp.toPx()))
            }
        }
    }
}
