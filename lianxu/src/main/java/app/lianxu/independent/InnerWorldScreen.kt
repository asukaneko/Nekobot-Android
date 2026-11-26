package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable internal fun InnerWorldScreen(vm: AppState, entryPage: String = vm.page) {
    val friend = vm.friends.firstOrNull { it.id == vm.activeId } ?: return
    val initial = when (entryPage) { "role-relationship" -> 0; "role-learning" -> 1; else -> 2 }
    val tabs = rememberPageTabs(3, initial)
    val detailOpen = remember { mutableStateMapOf<Int, Boolean>() }
    val header: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Chrome)) {
          val tabWidth=maxWidth/3
          val position=tabs.pager.currentPage+tabs.pager.currentPageOffsetFraction
          Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf("心情", "学习", "记忆").forEachIndexed { index, label ->
                val active = tabs.selected == index
                Column(Modifier.weight(1f).selectable(active, role = Role.Tab) { tabs.select(index) }
                    .testTag("inner-tab-$index").heightIn(min=48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(label, color = softColor(if (active) Ink else Muted), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(5.dp))
                    Spacer(Modifier.height(1.dp))
                }
            }
          }
          Spacer(Modifier.align(Alignment.BottomStart).offset(x=tabWidth*(position+.5f)-10.dp,y=(-9).dp).size(20.dp,1.dp).background(GreenText))
        }
    }
    PageTabContent(tabs, enabled = detailOpen[tabs.selected] != true) { page ->
        when (page) {
            0 -> RelationshipScreen(vm, header)
            1 -> LearningRecordsScreen(vm, header) { detailOpen[1] = it }
            2 -> MemoryExperience(vm, friend, header) { detailOpen[2] = it }
        }
    }
}
