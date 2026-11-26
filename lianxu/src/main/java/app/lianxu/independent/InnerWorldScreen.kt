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

@Composable internal fun InnerWorldScreen(vm: AppState) {
    val friend = vm.friends.firstOrNull { it.id == vm.activeId } ?: return
    val initial = when (vm.page) { "role-relationship" -> 0; "role-learning" -> 1; else -> 2 }
    val tabs = rememberPageTabs(3, initial)
    val detailOpen = remember { mutableStateMapOf<Int, Boolean>() }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().background(Chrome).selectableGroup()) {
            listOf("心情", "学习", "记忆").forEachIndexed { index, label ->
                val active = tabs.selected == index
                Column(Modifier.weight(1f).selectable(active, role = Role.Tab) { tabs.select(index) }
                    .testTag("inner-tab-$index").heightIn(min=48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(label, color = if (active) Ink else Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(5.dp))
                    Spacer(Modifier.size(20.dp, 1.dp).background(if (active) GreenText else Chrome))
                }
            }
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
