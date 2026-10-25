package com.nekobot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.nekobot.app.R
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.ui.theme.LocalNekoTypeScale
import com.nekobot.app.ui.theme.nekoAccentSoft
import com.nekobot.app.ui.theme.accentWarning
import com.nekobot.app.ui.theme.accentSuccess
import com.nekobot.app.ui.theme.accentSecondary
import com.nekobot.app.ui.theme.nekoFaint
import com.nekobot.app.ui.theme.nekoGreen

// =====================================================================================
// 壳层（外层外观）组件库
//
// 设计基线：内容优先、白底、细线、一处强调色。
//  · 层级只用「背景色差 + 1dp 细线 + 留白」表达，不用渐变、玻璃、阴影堆叠；
//  · 一级页面标题 30sp 大标题 + 右侧 48dp 图标按钮；
//  · 列表统一放进 18dp 圆角的白底分组卡，行高 ≥ 61dp，行内信息最多两级；
//  · 所有彩色只用于"这是哪一类"，同一屏不超过 3 种。
//
// 内部功能页不重新排版，但仍共用这里的配色与圆角，因此视觉是一个整体。
// =====================================================================================

/** 壳层文字尺寸：固定 sp × 用户字号系数（与主题 buildTypography 同源）。 */
@Composable
fun nekoTextSize(value: Float): TextUnit = (value * LocalNekoTypeScale.current).sp

/** 一级页面大标题：30sp / 600 / 收紧字距。 */
@Composable
fun nekoPageTitleStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(30f),
    lineHeight = nekoTextSize(38f),
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.6).sp,
)

/** 二级页面标题：18sp / 600。 */
@Composable
fun nekoSubPageTitleStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(18f),
    lineHeight = nekoTextSize(26f),
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.2).sp,
)

/** 分组小标题 / 说明文字：13sp / 500。 */
@Composable
fun nekoSectionLabelStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(13f),
    lineHeight = nekoTextSize(18f),
    fontWeight = FontWeight.Medium,
)

/** 列表行主标题：15sp / 500。 */
@Composable
fun nekoRowTitleStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(15f),
    lineHeight = nekoTextSize(22f),
    fontWeight = FontWeight.Medium,
)

/** 列表行副标题：12sp / 400。 */
@Composable
fun nekoRowSubtitleStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(12f),
    lineHeight = nekoTextSize(18f),
)

/** 首页问候标题：19sp / 500，比正文重一档但不抢大标题。 */
@Composable
fun nekoWelcomeTitleStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(19f),
    lineHeight = nekoTextSize(26f),
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.2).sp,
)

/** 时间戳 / 脚注：11sp / 400。 */
@Composable
fun nekoCaptionStyle(): TextStyle = TextStyle(
    fontSize = nekoTextSize(11f),
    lineHeight = nekoTextSize(16f),
)

/** 内容区最大宽度：平板下不被拉伸成一条。 */
val NekoContentMaxWidth: Dp = 760.dp

/** 页面左右留白。 */
val NekoPageHorizontalPadding: Dp = 20.dp

/**
 * 图标底座的配色档位。
 *
 * 一屏内轮换使用：既让图标"有颜色"、能区分类别，又不会整页同色显得闷。
 */
enum class NekoTone { Accent, Blue, Green, Amber, Neutral }

/** 档位对应的（底座底色, 图标颜色）。 */
@Composable
fun NekoTone.palette(): Pair<Color, Color> = when (this) {
    NekoTone.Accent -> nekoAccentSoft() to MaterialTheme.colorScheme.primary
    NekoTone.Blue -> MaterialTheme.colorScheme.secondaryContainer to accentSecondary()
    NekoTone.Green -> MaterialTheme.colorScheme.tertiaryContainer to accentSuccess()
    NekoTone.Amber -> accentWarning().copy(alpha = 0.14f) to accentWarning()
    NekoTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) to
        MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------- 顶栏

/**
 * 一级页面顶栏：大标题 + 右侧图标动作。
 *
 * 与底部导航一样不使用毛玻璃：纯页面底色 + 细线，滚动时内容从下方穿过。
 */
@Composable
fun NekoPageTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = NekoPageHorizontalPadding, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = nekoPageTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    badge()
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = nekoCaptionStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 二级页面顶栏：返回箭头 + 18sp 标题 + 右侧动作。 */
@Composable
fun NekoDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.common_back),
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NekoIconAction(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = backContentDescription,
            onClick = onBack,
        )
        Text(
            text = title,
            style = nekoSubPageTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 顶栏图标动作：48dp 触摸区、23dp 图标。 */
@Composable
fun NekoIconAction(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(23.dp))
    }
}

/** 顶栏小型文字动作（如「筛选」「路由记录」）。 */
@Composable
fun NekoTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = nekoCaptionStyle(), color = color, fontWeight = FontWeight.Medium)
    }
}

/** 计数徽标：细描边胶囊，不抢标题。 */
@Composable
fun NekoCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(nekoAccentSoft())
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = count.toString(),
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------- 分组与行

/** 分组标题行：左侧小标题，右侧可放说明、内嵌按钮或文字动作。 */
@Composable
fun NekoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    /** 文字动作左侧的内嵌小按钮（例如归档筛选开关）。 */
    actionLeading: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp)
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = nekoSectionLabelStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (meta != null) {
            Text(meta, style = nekoCaptionStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLeading != null) {
            actionLeading()
        }
        if (actionText != null && onAction != null) {
            NekoTextAction(text = actionText, onClick = onAction, modifier = Modifier.padding(end = 0.dp))
        }
    }
}

/** 白底 + 1dp 细线的分组容器（列表统一住在里面）。 */
@Composable
fun NekoGroup(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(contentPadding),
        content = content
    )
}

/** 分组内的分隔线，左侧留出与文字起始位置对齐的缩进。 */
@Composable
fun NekoDivider(startInset: Dp = 0.dp, endInset: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 标准设置行：图标底座 + 标题（+ 副标题）+ 右侧值 / 开关 / 箭头。
 */
@Composable
fun NekoSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    /** 图标底座配色档位；[iconTint] / [iconBackground] 传值时优先生效。 */
    tone: NekoTone = NekoTone.Accent,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    value: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            val (toneBackground, toneTint) = tone.palette()
            Box(
                modifier = Modifier
                    .size(33.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBackground ?: toneBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint ?: toneTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
            )
        }
    }
}

/** 行尾箭头（单独使用于自定义行布局）。 */
@Composable
fun NekoChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp)
    )
}

/** 行尾选中勾（单选列表）。 */
@Composable
fun NekoCheckMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
        }
    }
}

// ---------------------------------------------------------------- 分段控件

/**
 * 分段控件：替代一排彩色筛选卡。
 *
 * @param options 选项文案
 * @param selectedIndex 选中下标；传入 -1 表示"不选任何一项"
 */
@Composable
fun NekoSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = nekoRowSubtitleStyle(),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 主要行动

/** 页面唯一的主要行动：整宽实心按钮 + 右侧箭头。 */
@Composable
fun NekoPrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingChevron: Boolean = true,
    enabled: Boolean = true
) {
    val foreground = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = nekoRowTitleStyle(),
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (trailingChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 次要按钮：描边胶囊。 */
@Composable
fun NekoSecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = nekoRowTitleStyle(), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- 输入

/** 搜索输入框：46dp、14dp 圆角、细描边，聚焦时描边转为强调色。 */
@Composable
fun NekoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = nekoRowSubtitleStyle(),
                    color = nekoFaint(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = nekoRowSubtitleStyle().copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChangedCompat { focused = it }
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 兼容不同 Compose 版本：集中一处的聚焦回调修饰符。 */
private fun Modifier.onFocusChangedCompat(onChanged: (Boolean) -> Unit): Modifier = composed {
    var previous = false
    this.onFocusChanged { state ->
        if (state.isFocused != previous) {
            previous = state.isFocused
            onChanged(state.isFocused)
        }
    }
}

// ---------------------------------------------------------------- 小件

/** 彩色标签（AI 伙伴 / 角色 / 次数等）。 */
@Composable
fun NekoTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(nekoAccentSoft())
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, style = nekoCaptionStyle(), color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** 状态点 + 文案（如「本地模式 · 数据仅在此设备」）。 */
@Composable
fun NekoStatusLine(text: String, modifier: Modifier = Modifier, dotColor: Color = nekoGreen()) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(5.dp))
        Text(text, style = nekoCaptionStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 圆形字母头像：无立绘时的兜底，底色在三种柔和色调间轮换。 */
@Composable
fun NekoLetterAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    toneIndex: Int = 0
) {
    val palette = listOf(
        nekoAccentSoft() to MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary,
    )
    val (bg, fg) = palette[((toneIndex % palette.size) + palette.size) % palette.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.firstCodePointText(),
            style = nekoRowTitleStyle(),
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 取首个完整码点：emoji / 补充平面汉字不会被截成半个代理对。 */
private fun String.firstCodePointText(): String =
    if (isEmpty()) this else substring(0, offsetByCodePoints(0, 1))

/** 图标底座（33dp / 彩色浅底），用于设置行与抽屉选项。 */
@Composable
fun NekoIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 33.dp,
    tone: NekoTone = NekoTone.Accent,
    tint: Color? = null,
    background: Color? = null
) {
    val tileShape = RoundedCornerShape(if (size >= 40.dp) 13.dp else 10.dp)
    val (toneBackground, toneTint) = tone.palette()
    Box(
        modifier = modifier
            .size(size)
            .clip(tileShape)
            .background(background ?: toneBackground),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: toneTint,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** 空状态：一个淡图标 + 一句标题 + 一句提示。 */
@Composable
fun NekoEmpty(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = nekoFaint(),
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = title,
            style = nekoRowTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 设计稿配色的开关。 */
@Composable
fun NekoToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        )
    )
}

// ---------------------------------------------------------------- 抽屉

/**
 * 底部抽屉：26dp 顶圆角 + 抓手 + 标题 + 关闭。
 *
 * 与设计稿一致地把"选择类"操作放进抽屉，而不是塞满主页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 14.dp)
                    .size(width = 37.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NekoPageHorizontalPadding)
                    .padding(bottom = 28.dp),
                content = content
            )
        }
    }
}

/** 抽屉头部：标题 + 关闭按钮。 */
@Composable
fun NekoSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = nekoSubPageTitleStyle().copy(fontSize = nekoTextSize(20f)), color = MaterialTheme.colorScheme.onSurface)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = nekoRowSubtitleStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NekoIconAction(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.common_close),
            onClick = onClose,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 抽屉里的一行可选项：图标 + 标题 + 说明 + 尾部（勾 / 箭头）。 */
@Composable
fun NekoChoiceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = true,
    showDivider: Boolean = false
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(13.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = nekoRowTitleStyle(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = nekoRowSubtitleStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            } else if (showChevron) {
                Spacer(Modifier.width(8.dp))
                NekoChevron()
            }
        }
        if (showDivider) NekoDivider()
    }
}

/** 抽屉底部说明文字。 */
@Composable
fun NekoSheetNote(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = nekoCaptionStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}
