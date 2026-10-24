package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity

/** 消费内容区分页到边缘后的横向余量，防止一次拖动顺带切换外层底栏页面。 */
@Composable
internal fun rememberSessionPageSwipeBoundary(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            Offset(available.x, 0f)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            Velocity(available.x, 0f)
    }
}

/** 三个等宽文字页签；不叠加计数卡、图标或常驻搜索条。 */
@Composable
internal fun SessionPageTabs(selected: SessionPage, onSelect: (SessionPage) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().padding(horizontal = 16.dp)) {
            SessionPage.entries.forEach { page ->
                val active = page == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 56.dp)
                        .testTag("sessions-page-${page.name.lowercase()}")
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(page) })
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(page.titleResId),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        Modifier.padding(top = 7.dp).width(22.dp).height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
