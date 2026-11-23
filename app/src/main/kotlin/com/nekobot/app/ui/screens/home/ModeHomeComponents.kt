package com.nekobot.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.screens.sessions.SessionListRow

/**
 * 模式页里的一条会话：头像 + 标题 + 最后一句 + 日期。
 *
 * 只用列表页已有的展示模型 [SessionListRow]，不在这里重新解析会话数据。
 */
@Composable
internal fun ModeSessionRow(
    row: SessionListRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeSessionAvatar(
            url = row.portraitUrl,
            fallback = row.listTitle,
            tint = tint,
            size = 42.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.listTitle,
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.lastMessage ?: stringResource(R.string.sessions_no_message),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val date = row.listDate
        if (!date.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = date,
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

/** 会话还没有消息时的占位文案。 *//** 圆形头像：有图用图，没图用首字兜底。 */
@Composable
internal fun ModeSessionAvatar(
    url: String?,
    fallback: String,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(context, url) {
        url?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context).data(it).crossfade(true).build()
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = fallback.takeIf { it.isNotBlank() }?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }.orEmpty(),
                style = nekoRowTitleStyle(),
                color = tint,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 抽屉 / 页面里的小节标题。 */
@Composable
internal fun ModeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = nekoCaptionStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 6.dp)
    )
}

/** 细线分隔，用在抽屉里的分组之间。 */
@Composable
internal fun ModeThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    )
}
