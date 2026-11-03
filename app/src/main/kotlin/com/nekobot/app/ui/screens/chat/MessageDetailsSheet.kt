package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import androidx.compose.ui.res.stringResource
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoGroup
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.nekoRowTitleStyle

/**
 * 正文最多占用的高度：超出后只在卡片内部滚动。
 *
 * 高度必须留有余量（不能顶满屏幕），否则内容一旦接近抽屉可用高度，
 * 滚动与抽屉自身的拖拽手势就会互相抢事件，出现上下滑动后回弹跳动。
 */
private val DetailsScrollMaxHeight = 420.dp

/** 与其它界面一致的底部抽屉，只列消息的时间、模型、Token 与生成耗时。 */
@Composable
internal fun MessageDetailsSheet(details: MessageDetails, subtitle: String, onDismiss: () -> Unit) {
    NekoSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("message-details-sheet")
    ) {
        NekoSheetHeader(
            title = stringResource(R.string.single_role_message_details),
            description = subtitle,
            onClose = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = DetailsScrollMaxHeight)
                .verticalScroll(rememberScrollState())
        ) {
            NekoGroup {
                DetailRow(stringResource(R.string.single_role_sent_at), details.sentAt)
                NekoDivider(startInset = 15.dp)
                DetailRow(stringResource(R.string.aimodel_editor_field_model), details.model)
            }
            NekoSectionHeader(title = stringResource(R.string.single_role_token_usage))
            NekoGroup {
                DetailRow(stringResource(R.string.stats_token_ratio_input), details.inputTokens)
                NekoDivider(startInset = 15.dp)
                DetailRow(stringResource(R.string.stats_token_ratio_output), details.outputTokens)
                NekoDivider(startInset = 15.dp)
                DetailRow(stringResource(R.string.tokens_total), details.totalTokens, emphasize = true)
            }
            Spacer(Modifier.height(12.dp))
            NekoGroup {
                DetailRow(stringResource(R.string.single_role_generation_speed), details.speed)
                NekoDivider(startInset = 15.dp)
                DetailRow(stringResource(R.string.single_role_generation_time), details.duration)
            }
        }
    }
}

/** 详情行：左侧名称、右侧可复制的值，不额外附加说明小字。 */
@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = nekoRowTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.38f)
        )
        SelectionContainer(Modifier.weight(0.62f)) {
            Text(
                text = value,
                style = nekoRowTitleStyle(),
                color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
