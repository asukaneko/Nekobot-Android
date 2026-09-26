package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nekobot.app.R
import kotlin.math.roundToInt

/**
 * 聊天快捷工具栏的按钮目录。
 *
 * 工具栏位于输入框下方：默认放入表情包等常用操作，用户可在编辑弹窗里增删、调整顺序，
 * 并可用折叠按钮整排收起；配置持久化在 [com.nekobot.app.data.local.PrefsManager]。
 */
internal object ChatQuickAction {
    const val STICKER = "sticker"
    const val IMAGE = "image"
    const val FILE = "file"
    const val UPLOAD = "upload"
    const val WORKSPACE = "workspace"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val MY_MESSAGES = "my_messages"
    const val CONTEXT = "context"
    const val COMPRESS = "compress"
    const val CLEAR = "clear"
    const val LATEST = "latest"

    /** 全部可用按钮（顺序即编辑弹窗「可添加」列表顺序）。 */
    val allIds: List<String> = listOf(
        STICKER, IMAGE, FILE, UPLOAD, WORKSPACE,
        SEARCH, FAVORITES, MY_MESSAGES, CONTEXT, COMPRESS, CLEAR, LATEST
    )

    /** 默认工具栏：表情包 + 常用操作。 */
    val defaultIds: List<String> = listOf(STICKER, IMAGE, FILE, WORKSPACE, SEARCH, FAVORITES)

    /** 过滤未知 id 并去重，保持顺序；用于读取历史配置与编辑结果。 */
    fun normalize(ids: List<String>): List<String> = ids.filter { it in allIds }.distinct()

    /** 把 [from] 位置的按钮移动到 [to] 位置（越界自动收敛），供拖拽排序与编辑使用。 */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices) return ids
        val target = to.coerceIn(0, ids.lastIndex)
        if (target == from) return ids
        return ids.toMutableList().apply { add(target, removeAt(from)) }
    }

    fun iconOf(id: String): ImageVector = when (id) {
        STICKER -> Icons.Filled.EmojiEmotions
        IMAGE -> Icons.Filled.Image
        FILE -> Icons.Filled.AttachFile
        UPLOAD -> Icons.Filled.CloudUpload
        WORKSPACE -> Icons.Filled.Folder
        SEARCH -> Icons.Filled.Search
        FAVORITES -> Icons.Filled.Star
        MY_MESSAGES -> Icons.Filled.AccountCircle
        CONTEXT -> Icons.Filled.AutoAwesome
        COMPRESS -> Icons.Filled.Compress
        CLEAR -> Icons.Filled.CleaningServices
        LATEST -> Icons.Filled.KeyboardDoubleArrowDown
        else -> Icons.Filled.Add
    }

    fun labelResOf(id: String): Int = when (id) {
        STICKER -> R.string.sticker_picker_title
        IMAGE -> R.string.chat_quick_action_image
        FILE -> R.string.chat_send_file
        UPLOAD -> R.string.chat_quick_action_upload
        WORKSPACE -> R.string.chat_workspace
        SEARCH -> R.string.chat_quick_action_search
        FAVORITES -> R.string.chat_favorites
        MY_MESSAGES -> R.string.chat_my_messages
        CONTEXT -> R.string.chat_quick_action_context
        COMPRESS -> R.string.chat_quick_action_compress
        CLEAR -> R.string.chat_quick_action_clear
        LATEST -> R.string.chat_quick_action_latest
        else -> R.string.chat_quick_action_image
    }
}

/**
 * 输入框下方的快捷工具栏（展开态）。
 *
 * 最左为折叠按钮，中间是横向滚动的操作按钮，最右为编辑按钮。
 * 折叠后整条工具栏不再占位（输入框随之下移），由输入框操作行里的展开按钮重新打开。
 */
@Composable
internal fun ChatQuickActionBar(
    actions: List<String>,
    onCollapse: () -> Unit,
    onEdit: () -> Unit,
    /**
     * 各操作当前是否可用；不可用时渲染为半透明且点击无效
     * （如 AI 持续 loop 期间禁用「压缩」）。
     */
    isActionEnabled: (String) -> Boolean = { true },
    onAction: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_quick_bar_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (actions.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_quick_bar_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                actions.forEach { id ->
                    ChatQuickActionChip(
                        id = id,
                        enabled = isActionEnabled(id),
                        onClick = { onAction(id) }
                    )
                }
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(R.string.chat_quick_bar_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * 折叠态的展开按钮：挂在输入框操作行里，避免工具栏收起后没有恢复入口。
 * 只显示一个图标，不加底色/边框，也不占额外纵向空间。
 */
@Composable
internal fun ChatQuickActionExpandButton(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onExpand,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(R.string.chat_quick_bar_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 单个快捷操作：图标 + 名称的胶囊按钮；[enabled] 为 false 时半透明且不可点击。 */
@Composable
private fun ChatQuickActionChip(
    id: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = ChatQuickAction.iconOf(id),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(ChatQuickAction.labelResOf(id)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 快捷工具栏编辑弹窗：调整已显示按钮的顺序 / 移除，或从剩余按钮中添加。
 *
 * [onActionsChange] 直接回写完整的新顺序，由调用方负责持久化。
 */
@Composable
internal fun ChatQuickActionEditorDialog(
    actions: List<String>,
    onActionsChange: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val available = ChatQuickAction.allIds.filterNot { it in actions }
    // 拖拽排序状态：行高固定，拖动时按行高换算目标位置并即时重排
    val rowHeight = 46.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val latestActions by rememberUpdatedState(actions)
    val latestOnActionsChange by rememberUpdatedState(onActionsChange)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quick_bar_editor_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.chat_quick_bar_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .padding(top = 8.dp)
                ) {
                    item { QuickActionSectionTitle(stringResource(R.string.chat_quick_bar_section_shown)) }
                    if (actions.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.chat_quick_bar_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                    itemsIndexed(actions, key = { _, id -> "shown_$id" }) { _, id ->
                        val isDragging = draggingId == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset {
                                    if (isDragging) IntOffset(0, dragOffset.roundToInt()) else IntOffset.Zero
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isDragging) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingId = id
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.y
                                                val steps = (dragOffset / rowHeightPx).roundToInt()
                                                if (steps != 0) {
                                                    val list = latestActions
                                                    val from = list.indexOf(id)
                                                    if (from >= 0) {
                                                        val to = (from + steps)
                                                            .coerceIn(0, list.lastIndex)
                                                        if (to != from) {
                                                            latestOnActionsChange(
                                                                ChatQuickAction.move(list, from, to)
                                                            )
                                                            dragOffset -= (to - from) * rowHeightPx
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = stringResource(R.string.chat_quick_bar_drag),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = ChatQuickAction.iconOf(id),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(ChatQuickAction.labelResOf(id)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onActionsChange(actions - id) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.chat_quick_bar_remove),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    item {
                        QuickActionSectionTitle(
                            stringResource(R.string.chat_quick_bar_section_available)
                        )
                    }
                    if (available.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.chat_quick_bar_all_added),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                    items(available, key = { "available_$it" }) { id ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onActionsChange(actions + id) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = ChatQuickAction.iconOf(id),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(ChatQuickAction.labelResOf(id)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = {
            TextButton(onClick = { onActionsChange(ChatQuickAction.defaultIds) }) {
                Text(stringResource(R.string.chat_quick_bar_reset_default))
            }
        }
    )
}

/** 编辑弹窗的小节标题。 */
@Composable
private fun QuickActionSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}
