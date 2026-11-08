package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.R
import com.nekobot.app.data.local.HomePlotChoice
import com.nekobot.app.data.local.splitHomeQuickChatBubbles
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle

/**
 * 首页场景轻聊面板。
 *
 * 只是完整会话的一个轻量入口：消息、关系与剧情都落在同一条会话上，
 * 右上角「展开」进入完整会话页后可以继续同一段对话。
 */
@Composable
internal fun HomeQuickChatPanel(
    state: HomeQuickChatUiState,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onExpand: () -> Unit,
    onNewScene: () -> Unit,
    onPlotChoice: (HomePlotChoice) -> Unit,
    onDismissHint: () -> Unit
) {
    if (!state.visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f),
                cornerRadius = 22,
                containerColor = MaterialTheme.colorScheme.surface,
                borderWidth = 1,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
            ) {
                QuickChatHeader(
                    state = state,
                    onDismiss = onDismiss,
                    onExpand = onExpand,
                    onNewScene = onNewScene
                )
                Spacer(Modifier.height(8.dp))
                if (state.loading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    QuickChatMessages(state = state, modifier = Modifier.weight(1f))
                }
                if (state.relationDelta != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.relationDelta.orEmpty(),
                        style = nekoCaptionStyle(),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.plotChoices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.plotChoices.forEach { choice ->
                            PlotChoiceChip(choice.text) { onPlotChoice(choice) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                QuickChatInput(
                    state = state,
                    onSend = onSend
                )
                if (state.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.error,
                        style = nekoCaptionStyle(),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.hintVisible) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.shell_quick_chat_hint),
                            style = nekoCaptionStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onDismissHint)
                                .heightIn(min = 30.dp)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.shell_quick_chat_hint_dismiss),
                                style = nekoCaptionStyle(),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChatHeader(
    state: HomeQuickChatUiState,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onNewScene: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BannerAvatar(
            portraitUrl = state.portraitUrl,
            modeLabel = SessionModeLabel.CHARACTER,
            fallbackText = state.characterName,
            isGroupSession = false,
            size = 34.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.characterName.ifBlank { stringResource(R.string.shell_quick_chat_title) },
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    if (state.origin == HomeQuickChatOrigin.NEW_SCENE) {
                        R.string.shell_quick_chat_subtitle_new
                    } else {
                        R.string.shell_quick_chat_subtitle_continue
                    }
                ),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.canStartNewScene) {
            QuickChatIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.shell_quick_chat_new_scene),
                onClick = onNewScene
            )
        }
        QuickChatIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.shell_quick_chat_expand),
            onClick = onExpand
        )
        QuickChatIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.shell_quick_chat_close),
            onClick = onDismiss
        )
    }
}

@Composable
private fun QuickChatIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun QuickChatMessages(state: HomeQuickChatUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + if (state.sending) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        if (state.messages.isEmpty() && !state.sending) {
            item(key = "empty") {
                Text(
                    text = state.openingLine.ifBlank {
                        stringResource(R.string.shell_quick_chat_empty)
                    },
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
            }
        }
        items(
            items = state.messages,
            key = { it.id ?: it.hashCode().toString() }
        ) { message ->
            QuickChatBubble(
                text = message.displayContent,
                isUser = message.isUser
            )
        }
        if (state.sending) {
            item(key = "streaming") {
                val streaming = state.streamingText
                if (streaming.isBlank()) {
                    Text(
                        text = stringResource(R.string.shell_quick_chat_typing),
                        style = nekoCaptionStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    QuickChatBubble(text = streaming, isUser = false, isStreaming = true)
                }
            }
        }
    }
}

@Composable
private fun QuickChatBubble(text: String, isUser: Boolean, isStreaming: Boolean = false) {
    // 与完整会话一致：<||> 分隔的多段各自渲染成一个气泡
    val segments = remember(text) { splitHomeQuickChatBubbles(text) }
    if (segments.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        }
                    )
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                MarkdownText(
                    text = segment,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    chatMode = true,
                    processParens = !isUser,
                    isStreaming = isStreaming
                )
            }
        }
    }
}

@Composable
private fun PlotChoiceChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickChatInput(
    state: HomeQuickChatUiState,
    onSend: (String) -> Unit
) {
    var text by rememberSaveable(state.sessionId) { mutableStateOf("") }
    val enabled = !state.sending && !state.busyElsewhere && !state.loading
    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = {
                Text(
                    text = stringResource(
                        if (state.busyElsewhere) R.string.shell_quick_chat_busy
                        else R.string.shell_quick_chat_input_hint
                    ),
                    style = nekoCaptionStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (enabled && text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                )
                .clickable(enabled = enabled && text.isNotBlank()) {
                    val content = text.trim()
                    if (content.isNotEmpty()) {
                        text = ""
                        onSend(content)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.shell_quick_chat_send),
                tint = if (enabled && text.isNotBlank()) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/** 面板只展示用户/角色的纯文本正文，附件与工具结果仍留在完整会话页。 */
