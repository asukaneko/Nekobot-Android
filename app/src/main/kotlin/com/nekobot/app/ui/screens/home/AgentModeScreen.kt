package com.nekobot.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.R
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.NekoDetailTopBar
import com.nekobot.app.ui.components.NekoIconAction
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoSectionLabelStyle
import com.nekobot.app.ui.components.nekoTextSize
import com.nekobot.app.ui.screens.sessions.SessionListRow
import kotlinx.coroutines.launch

private val AgentConsole = Color(0xFF1B2329)
private val AgentMint = Color(0xFF8FD3B6)

/**
 * Agent 模式首页：一个还没建会话的欢迎页。
 *
 * 页面本身只在内存里维护草稿，只有点发送时才真正创建会话，
 * 然后把这句话交给会话页作为第一条消息（见 ChatPendingPrompt）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModeScreen(
    sessions: List<SessionListRow>,
    creating: Boolean,
    error: String?,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenChat: (String) -> Unit,
    /** 新建会话后进入聊天：这一页从返回栈退掉，返回时直接回首页。 */
    onChatCreated: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onSwitchMode: (HomeMode) -> Unit,
    onCreateSession: (String, (String) -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val canSend = input.isNotBlank() && !creating

    val submit: () -> Unit = {
        val text = input.trim()
        if (text.isNotEmpty() && !creating) {
            onCreateSession(text) { sessionId ->
                input = ""
                onChatCreated(sessionId)
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f),
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            ) {
                AgentDrawerContent(
                    sessions = sessions,
                    onClose = { scope.launch { drawerState.close() } },
                    onHome = onHome,
                    onOpenSessions = onOpenSessions,
                    onSwitchMode = onSwitchMode,
                    onOpenChat = { id ->
                        scope.launch { drawerState.close() }
                        onOpenChat(id)
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                NekoDetailTopBar(
                    title = stringResource(R.string.home_agent_bar_title),
                    onBack = onBack,
                    backContentDescription = stringResource(R.string.common_back),
                    actions = {
                        NekoIconAction(
                            icon = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.home_agent_menu),
                            onClick = { scope.launch { drawerState.open() } }
                        )
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(AgentMint, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AGENT",
                            style = nekoCaptionStyle().copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_agent_title),
                        style = nekoRowTitleStyle().copy(
                            fontSize = nekoTextSize(28f),
                            lineHeight = nekoTextSize(36f),
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.4).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_agent_hint),
                        style = nekoRowSubtitleStyle().copy(
                            fontSize = nekoTextSize(13f),
                            lineHeight = nekoTextSize(20f)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    AgentConsolePanel(input = input, creating = creating)
                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = stringResource(R.string.home_agent_try),
                        style = nekoSectionLabelStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
                    )
                    listOf(
                        R.string.home_agent_try_1,
                        R.string.home_agent_try_2,
                        R.string.home_agent_try_3
                    ).forEach { res ->
                        val text = stringResource(res)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                .clickable { input = text }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = text,
                                style = nekoRowSubtitleStyle().copy(fontSize = nekoTextSize(13f)),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                AgentComposer(
                    input = input,
                    onInputChange = { input = it },
                    canSend = canSend,
                    creating = creating,
                    onSubmit = submit
                )
            }
        }
    }

    ErrorToast(message = error, onShown = onErrorShown)
}

/** 快照式终端面板：把「还没发出的那句话」和两条状态回显出来。 */
@Composable
private fun AgentConsolePanel(input: String, creating: Boolean) {
    val placeholder = stringResource(R.string.home_agent_console_placeholder)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AgentConsole)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        ConsoleLine(
            marker = "›",
            text = input.ifBlank { placeholder },
            highlighted = input.isNotBlank(),
            dim = input.isBlank()
        )
        ConsoleLine(
            marker = "·",
            text = stringResource(
                if (creating) R.string.home_agent_console_creating else R.string.home_agent_console_ready
            ),
            highlighted = creating,
            dim = !creating
        )
        ConsoleLine(
            marker = "→",
            text = stringResource(R.string.home_agent_console_next),
            highlighted = false,
            dim = true
        )
    }
}

@Composable
private fun ConsoleLine(marker: String, text: String, highlighted: Boolean, dim: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = marker,
            style = nekoCaptionStyle().copy(fontFamily = FontFamily.Monospace),
            color = if (highlighted) AgentMint else Color(0xFF8FD3B6).copy(alpha = 0.45f),
            modifier = Modifier.width(16.dp)
        )
        Text(
            text = text,
            style = nekoRowSubtitleStyle().copy(
                fontSize = nekoTextSize(12.5f),
                lineHeight = nekoTextSize(19f),
                fontFamily = FontFamily.Monospace
            ),
            color = Color(0xFFE8EFEC).copy(alpha = if (dim) 0.55f else 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 底部输入区：细边框输入框 + 圆形发送键。 */
@Composable
private fun AgentComposer(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    creating: Boolean,
    onSubmit: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    // 与聊天页输入胶囊同一套：暖白渐变底 + 细描边 + 内嵌圆形发送键
    val fill = Brush.linearGradient(
        colorStops = arrayOf(
            0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            0.55f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            1f to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
    )
    val borderBrush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    )
    val sendBrush = if (canSend) {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            )
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.09f)
                )
                .clip(shape)
                .background(brush = fill, shape = shape)
                .border(width = 1.dp, brush = borderBrush, shape = shape)
                .padding(6.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 120.dp)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_agent_placeholder),
                            style = nekoRowSubtitleStyle().copy(fontSize = nekoTextSize(13.5f)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        enabled = !creating,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = nekoTextSize(14f),
                            lineHeight = nekoTextSize(21f)
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(brush = sendBrush, shape = CircleShape)
                        .clickable(enabled = canSend, onClick = onSubmit),
                    contentAlignment = Alignment.Center
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            // 创建中按钮是浅色底，转圈用主色才看得见
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.home_agent_send),
                            tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 左侧抽屉：返回主页 + 快速切模式 + 会话列表。 */
@Composable
private fun AgentDrawerContent(
    sessions: List<SessionListRow>,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onOpenSessions: () -> Unit,
    onSwitchMode: (HomeMode) -> Unit,
    onOpenChat: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_agent_drawer_title),
                style = nekoRowTitleStyle().copy(
                    fontSize = nekoTextSize(18f),
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            NekoIconAction(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onClose
            )
        }
        // 返回主页
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onHome)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.home_agent_drawer_home),
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        ModeThinDivider(Modifier.padding(vertical = 6.dp))
        ModeSectionLabel(stringResource(R.string.home_agent_drawer_modes))
        NekoSegmentedControl(
            options = listOf(
                stringResource(R.string.shell_seg_character),
                stringResource(R.string.shell_seg_agent),
                stringResource(R.string.shell_seg_single)
            ),
            selectedIndex = HomeMode.AGENT.ordinal,
            onSelect = { index ->
                val target = HomeMode.entries.getOrNull(index) ?: return@NekoSegmentedControl
                if (target != HomeMode.AGENT) {
                    onClose()
                    onSwitchMode(target)
                }
            }
        )
        ModeThinDivider(Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_agent_drawer_sessions),
                style = nekoSectionLabelStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.home_mode_all_sessions),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = {
                        onClose()
                        onOpenSessions()
                    })
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.home_agent_drawer_empty),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 18.dp, horizontal = 4.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sessions, key = { it.key }) { row ->
                    val id = row.id ?: return@items
                    ModeSessionRow(
                        row = row,
                        tint = MaterialTheme.colorScheme.tertiary,
                        onClick = { onOpenChat(id) }
                    )
                }
            }
        }
    }
}
