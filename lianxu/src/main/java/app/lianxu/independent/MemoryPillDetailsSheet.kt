package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun ChatMemoryPillDetails(vm:AppState,friend:String,batch:String,onDismiss:()->Unit) {
    // A new revision gets a new loading state immediately; never keep a deleted row on screen.
    val details=key(friend,batch,vm.processingRevision){
        produceState<MemoryPillDetails?>(null){
            value=try{vm.processing.memoryPillDetails(friend,batch)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){MemoryPillDetails.Unavailable}
        }
    }.value
    MemoryPillDetailsSheet(details,onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MemoryPillDetailsSheet(details:MemoryPillDetails?,onDismiss:()->Unit) {
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val maxHeight=(LocalConfiguration.current.screenHeightDp*.8f).dp
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=state,containerColor=Paper,modifier=Modifier.testTag("memory-batch-sheet")){
        Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).padding(horizontal=UiSpace.page).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("这次整理的记忆",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge,color=Ink,textAlign=TextAlign.Center)
            when(details){
                null->Box(Modifier.fillMaxWidth().padding(vertical=24.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),color=GreenText,strokeWidth=2.dp)}
                MemoryPillDetails.Unavailable->Text("详情不可用",Modifier.fillMaxWidth().padding(vertical=24.dp).testTag("memory-batch-unavailable"),style=MaterialTheme.typography.bodyMedium,color=Muted,textAlign=TextAlign.Center)
                is MemoryPillDetails.Available->{
                    if(details.items.isEmpty())Text("本次没有新增或更新的记忆",Modifier.fillMaxWidth().padding(vertical=24.dp).testTag("memory-batch-empty"),style=MaterialTheme.typography.bodyMedium,color=Muted,textAlign=TextAlign.Center)
                    else LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("memory-batch-detail-list")){
                        itemsIndexed(details.items,key={_,item->item.id}){index,item->
                            Row(Modifier.fillMaxWidth().padding(vertical=14.dp).testTag("memory-batch-item-${item.id}"),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top){
                                Text("${index+1}.",Modifier.widthIn(min=24.dp),style=MaterialTheme.typography.bodyMedium,color=Muted)
                                Text(item.text?:"已删除",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(item.deleted)Muted else Ink)
                                if(item.updated)Text("更新",Modifier.clip(RoundedCornerShape(4.dp)).background(Accent).padding(horizontal=6.dp,vertical=2.dp).testTag("memory-batch-updated-${item.id}"),style=MaterialTheme.typography.labelSmall,color=GreenText)
                            }
                            if(index<details.items.lastIndex)HorizontalDivider(color=Line,thickness=.5.dp)
                        }
                    }
                }
            }
        }
    }
}
