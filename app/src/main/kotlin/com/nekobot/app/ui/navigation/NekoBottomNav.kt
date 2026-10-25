package com.nekobot.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.components.nekoTextSize
import com.nekobot.app.ui.theme.nekoAccentSoft

/**
 * 扁平底部导航栏。
 *
 * 设计取向：导航是"位置指示器"而不是装饰品——纯页面底色、一条 1dp 顶线、
 * 图标 + 10sp 标签，选中项只用强调色 + 顶部一小段圆角短线，没有胶囊、没有模糊、
 * 没有滑动光斑，把注意力留给内容。
 *
 * 三种窗口宽度共用同一套语义，几何规格来自 [BottomBarLayout]：
 * 手机为「图标在上、文字在下」，平板改为并排并放大，且内容限宽居中。
 */
@Composable
fun NekoBottomNav(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val layout = bottomBarLayout()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = 6.dp,
                    bottom = 6.dp
                )
                .then(
                    if (layout.pillMaxWidth != null) {
                        Modifier.widthIn(max = layout.pillMaxWidth).align(Alignment.CenterHorizontally)
                    } else Modifier
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                BottomNavItem(
                    item = item,
                    selected = index == selectedIndex,
                    iconSize = layout.iconSize,
                    labelBesideIcon = layout.labelBesideIcon,
                    iconTextGap = layout.iconTextGap,
                    modifier = Modifier.weight(1f),
                    onClick = { onItemSelected(item) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    item: BottomItem,
    selected: Boolean,
    iconSize: androidx.compose.ui.unit.Dp,
    labelBesideIcon: Boolean,
    iconTextGap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 切换标签时给一点点触感反馈，确认点击已生效
    val haptic = LocalHapticFeedback.current
    val labelStyle = androidx.compose.ui.text.TextStyle(
        fontSize = nekoTextSize(if (labelBesideIcon) 12f else 10f),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
    Column(
        modifier = modifier
            .heightIn(min = if (labelBesideIcon) 52.dp else 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!labelBesideIcon) {
            // 顶部一小段圆角短线：比整块高亮更安静，又能一眼看出当前在哪
            Box(
                Modifier
                    .size(width = 16.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
            Spacer(Modifier.size(7.dp))
        }
        if (labelBesideIcon) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
                Spacer(Modifier.width(iconTextGap))
                Text(item.label, style = labelStyle, color = contentColor, maxLines = 1)
            }
        } else {
            Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
            Spacer(Modifier.size(3.dp))
            Text(
                item.label,
                style = labelStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 平板形态的侧边导航栏。
 *
 * 与底栏同源的扁平样式：白底 + 1dp 细线 + 圆角面板，选中项用强调色浅底。
 * 面板贴屏幕左侧垂直居中，主界面内容整体向右避让 [NavRailLayout.clearance]。
 */
@Composable
fun NekoNavRail(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val layout = navRailLayout()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val shape = RoundedCornerShape(layout.corner)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(layout.clearance)
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .width(layout.railWidth)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(vertical = 6.dp)
        ) {
            items.forEachIndexed { index, item ->
                RailNavItem(
                    item = item,
                    selected = index == selectedIndex,
                    layout = layout,
                    onClick = { onItemSelected(item) }
                )
            }
        }
    }
}

@Composable
private fun RailNavItem(
    item: BottomItem,
    selected: Boolean,
    layout: NavRailLayout,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = LocalHapticFeedback.current
    val itemShape = RoundedCornerShape(layout.indicatorCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .heightIn(min = layout.itemHeight - 4.dp)
            .clip(itemShape)
            .background(if (selected) nekoAccentSoft() else Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(layout.iconSize))
            Spacer(Modifier.size(layout.iconTextGap))
            Text(
                item.label,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = nekoTextSize(if (layout.largerLabel) 12f else 11f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
