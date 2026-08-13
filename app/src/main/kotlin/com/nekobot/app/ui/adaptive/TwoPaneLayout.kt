package com.nekobot.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

/**
 * 通用双栏布局容器。
 *
 * - Compact（<600dp）：只显示 list，detail 不渲染
 * - Medium/Expanded（>=600dp）：Row 布局，左列固定 [listWidth]，右列 weight(1f)，中间 1dp 分隔线
 * - 右列无选中项时显示居中占位文本（由 [showDetailPlaceholder] 控制）
 *
 * @param list 左侧列表内容
 * @param detail 右侧详情内容
 * @param listWidth 左列固定宽度，默认 360dp
 * @param emptyDetailPlaceholder 详情为空时的占位文本
 * @param showDetailPlaceholder 是否显示占位文本（true 时显示占位，false 时渲染 detail）
 */
@Composable
fun TwoPaneLayout(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWidth: Dp = 360.dp,
    emptyDetailPlaceholder: String? = null,
    showDetailPlaceholder: Boolean = true
) {
    val useTwoPane = rememberShouldUseTwoPane()

    if (!useTwoPane) {
        // 紧凑模式：仅显示列表，不渲染详情
        Box(modifier = modifier.fillMaxSize()) {
            list()
        }
        return
    }

    Row(modifier = modifier.fillMaxSize()) {
        // 左列：固定宽度的列表
        Box(
            modifier = Modifier
                .width(listWidth)
                .fillMaxHeight()
        ) {
            list()
        }

        // 1dp 垂直分隔线
        HorizontalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
        )

        // 右列：详情区域，占据剩余空间
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (showDetailPlaceholder) {
                // 无选中项时显示居中占位文本
                Text(
                    text = emptyDetailPlaceholder ?: stringResource(R.string.two_pane_select_item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 有选中项时渲染详情内容
                detail()
            }
        }
    }
}
