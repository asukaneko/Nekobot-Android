package com.nekobot.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.R
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
        // 宽屏时限宽居中：widthIn 必须在 fillMaxWidth 之前，否则传入约束已被铺满，最大宽度不生效
        val pillMaxWidth = layout.pillMaxWidth
        Row(
            modifier = Modifier
                .then(
                    if (pillMaxWidth != null) {
                        Modifier.widthIn(max = pillMaxWidth).align(Alignment.CenterHorizontally)
                    } else Modifier
                )
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = 6.dp,
                    bottom = 6.dp
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
 * 平板形态的左侧抽屉导航。
 *
 * 与底栏同源的扁平样式：纯页面底色 + 右侧一条 1dp 细线，面板贴着屏幕左边缘、
 * 从上到下贯穿整屏（不再是一块悬浮的小岛）。顶部一行标题，下面是「图标 + 文字」
 * 的导航项，选中项用强调色浅底圆角块标记。
 *
 * [expanded] 为 false 时收起成只留图标的窄轨，为内容让出宽度；
 * 主界面内容整体按 [NavDrawerLayout.widthFor] 向右避让。
 */
@Composable
fun NekoNavDrawer(
    items: List<BottomItem>,
    selectedRoute: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val layout = navDrawerLayout()
    val width by animateDpAsState(
        targetValue = layout.widthFor(expanded),
        animationSpec = tween(NAV_DRAWER_ANIM_MS, easing = FastOutSlowInEasing),
        label = "navDrawerWidth"
    )
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Vertical
                    )
                )
        ) {
            DrawerHeader(
                expanded = expanded,
                layout = layout,
                onToggleExpanded = onToggleExpanded
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (expanded) layout.drawerPadding else 6.dp,
                        end = if (expanded) layout.drawerPadding else 6.dp,
                        top = 4.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(layout.itemSpacing)
            ) {
                items.forEachIndexed { index, item ->
                    DrawerNavItem(
                        item = item,
                        selected = index == selectedIndex,
                        expanded = expanded,
                        layout = layout,
                        onClick = { onItemSelected(item) }
                    )
                }
            }
        }
        // 右侧 1dp 细线：与页面内容之间只用一条线分隔，不加阴影
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/** 抽屉动画时长：展开 / 收起共用。 */
private const val NAV_DRAWER_ANIM_MS = 220

/** 抽屉顶部标题行：展开态显示应用名与收起按钮，收起态只留一个展开按钮。 */
@Composable
private fun DrawerHeader(
    expanded: Boolean,
    layout: NavDrawerLayout,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.headerHeight)
            .padding(
                start = if (expanded) layout.drawerPadding + 8.dp else 0.dp,
                end = if (expanded) 6.dp else 0.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        if (expanded) {
            Text(
                text = stringResource(R.string.app_name),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = nekoTextSize(if (layout.largerLabel) 19f else 17f),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            DrawerToggleButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                description = stringResource(R.string.nav_drawer_collapse),
                onClick = onToggleExpanded
            )
        } else {
            DrawerToggleButton(
                icon = Icons.Outlined.Menu,
                description = stringResource(R.string.nav_drawer_expand),
                onClick = onToggleExpanded
            )
        }
    }
}

/** 展开 / 收起按钮：40dp 触摸区、20dp 图标，比常规 IconButton 更轻。 */
@Composable
private fun DrawerToggleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 单个导航项：展开态「图标 + 文字」横排，收起态图标在上、文字在下。 */
@Composable
private fun DrawerNavItem(
    item: BottomItem,
    selected: Boolean,
    expanded: Boolean,
    layout: NavDrawerLayout,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(layout.itemCorner)
    val labelStyle = androidx.compose.ui.text.TextStyle(
        fontSize = nekoTextSize(
            when {
                expanded && layout.largerLabel -> 15f
                expanded -> 14f
                layout.largerLabel -> 11f
                else -> 10f
            }
        ),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
    val interaction = Modifier
        .fillMaxWidth()
        .height(layout.itemHeight)
        .clip(shape)
        .background(if (selected) nekoAccentSoft() else Color.Transparent)
        .selectable(
            selected = selected,
            role = Role.Tab,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
        )

    if (expanded) {
        Row(
            modifier = interaction.padding(horizontal = layout.itemPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(layout.iconSize))
            Spacer(Modifier.width(layout.labelGap))
            Text(
                item.label,
                style = labelStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Column(
            modifier = interaction,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(layout.iconSize))
            Spacer(Modifier.size(layout.railLabelGap))
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
