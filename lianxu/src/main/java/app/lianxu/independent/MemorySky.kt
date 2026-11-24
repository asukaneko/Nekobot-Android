package app.lianxu.independent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Path

/** A bounded perspective view of persisted memories. No invented association edges. */
@Composable internal fun MemorySky(memories:List<StoredMemory>,edges:List<MemoryRecall.Edge>,focusedCategory:MemoryCategory?=null,open:(String)->Unit){
    var rotation by rememberSaveable{mutableFloatStateOf(0f)}
    val focus by animateFloatAsState(if(focusedCategory==null)1f else 1.16f,label="靠近所选记忆分类")
    val nodes=remember(memories){memories.filter{it.category.modern&&it.valid&&it.life!=MemoryLife.RETRACTED}.sortedBy{it.id}.take(60)}
    val categories=MemoryCategory.entries.filter{it.modern}
    fun point(m:StoredMemory,w:Float,h:Float):Offset{
        val group=categories.indexOf(m.category)
        val hash=m.id.hashCode().toUInt().toLong()
        val a=group*PI/2+(hash%1000)/1000.0*.75-.375
        val radius=.24+(hash%211)/211.0*.16
        val x=cos(a)*radius;val z=sin(a)*radius
        val turn=rotation*PI;val depth=x*sin(turn)+z*cos(turn)
        val scale=focus/(1+depth*.65)
        return Offset((w*(.5+(x*cos(turn)-z*sin(turn))*scale).coerceIn(.06,.94)).toFloat(),(h*(.5+((hash%997)/997.0-.5)*.68*scale).coerceIn(.08,.92)).toFloat())
    }
    Column{
        Text("记忆星空",style=MaterialTheme.typography.titleMedium)
        Text("星点对应已保存的记忆，细线只表示已验证的关联。点星点查看正文；全部内容仍可在下方列表操作。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Canvas(Modifier.fillMaxWidth().height(230.dp).background(Color(0xFF25231E)).semantics{contentDescription="记忆星空，${nodes.size} 个可见星点；下方列表提供无障碍阅读与编辑"}.pointerInput(nodes,rotation){detectTapGestures{tap->nodes.minByOrNull{(point(it,size.width.toFloat(),size.height.toFloat())-tap).getDistance()}?.let{m->if((point(m,size.width.toFloat(),size.height.toFloat())-tap).getDistance()<28.dp.toPx())open(m.id)}}}){
            val positions=nodes.associate{it.id to point(it,size.width,size.height)}
            visibleSkyEdges(nodes,edges).forEach{edge->val a=positions[edge.sourceId];val b=positions[edge.targetId];if(a!=null&&b!=null)drawLine(Color(0xFFAC9460).copy(alpha=.45f),a,b,1.dp.toPx())}
            nodes.forEach{m->
                val p=positions.getValue(m.id);val r=if(m.core)7.dp.toPx() else 5.dp.toPx()
                val rays=listOf(4,5,6,8)[categories.indexOf(m.category)]
                val shape=Path().apply{repeat(rays*2){i->val a=i*PI/rays-PI/2;val radius=if(i%2==0)r else r*.35f;val x=p.x+(cos(a)*radius).toFloat();val y=p.y+(sin(a)*radius).toFloat();if(i==0)moveTo(x,y)else lineTo(x,y)};close()}
                drawPath(shape,Color(0xFFE4BF65))
            }
        }
        Slider(rotation,{rotation=it},valueRange=-1f..1f,modifier=Modifier.semantics{contentDescription="转动记忆星空"})
        if(memories.size>60)Text("星空最多展示 60 条，完整内容见列表。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
