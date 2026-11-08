package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import com.nekobot.app.ui.components.NekoDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.GlassCard
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.repeatCount
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.NekoCheckMark
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoIconAction
import com.nekobot.app.ui.components.NekoLetterAvatar
import com.nekobot.app.ui.components.NekoPrimaryAction
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.NekoSheetNote
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoSectionLabelStyle
import com.nekobot.app.ui.components.nekoWelcomeTitleStyle
import com.nekobot.app.ui.components.nekoTextSize
import com.nekobot.app.ui.components.resolveAvatarUrl
import java.util.Calendar
import kotlin.random.Random

/**
 * 会话页首屏：问候 + 会话 banner + 分组标题 + 分段筛选。
 *
 * 加载态 / 空态 / 列表态共用这一份，保证三种状态的头部长得完全一样。
 */
@Composable
fun SessionHomeHeaderContent(
    characters: List<CharacterPreset>,
    totalCount: Int,
    filter: SessionFilter,
    /** 未过滤、按更新时间倒序的会话行，供 banner 与「固定角色」选择使用。 */
    homeRows: List<SessionListRow>,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenFilter: () -> Unit,
    onSelectFilter: (SessionFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** 用指定角色走「新建角色会话」弹窗。 */
    onCreateSessionForCharacter: (String) -> Unit = {},
    /** 打开首页轻聊面板；服务器模式不传，入口只保留「继续会话」。 */
    onQuickChat: ((HomeQuickChatRequest) -> Unit)? = null,
    /** 问候语更新时同步给轻聊面板（角色 ID + 当前问候）。 */
    onQuickChatGreetingChanged: (String, String) -> Unit = { _, _ -> }
) {
    val prefs = ServiceContainer.prefs
    // prefs 不是可观察状态：这里另存一份局部状态，选择后 banner 立刻刷新
    var bannerMode by remember { mutableStateOf(prefs.homeBannerMode) }
    var bannerSessionId by remember { mutableStateOf(prefs.homeBannerSessionId) }
    var bannerCharacterId by remember { mutableStateOf(prefs.homeBannerCharacterId) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var showSessionPicker by remember { mutableStateOf(false) }
    val pinnedMode = bannerMode == PrefsManager.HOME_BANNER_PINNED
    // 可固定的角色直接来自内容库：即使还没有会话也能固定
    val bannerCharacters = remember(characters, homeRows) {
        buildBannerCharacters(characters, homeRows)
    }
    // 旧版本只存会话 id：角色 id 缺失时从该会话反推，保持已有固定项可用
    val pinnedCharacterId = remember(homeRows, bannerCharacterId, bannerSessionId) {
        bannerCharacterId.takeIf { it.isNotBlank() }
            ?: homeRows.firstOrNull { it.id == bannerSessionId }?.characterId.orEmpty()
    }
    val pinnedCharacter = remember(bannerCharacters, pinnedCharacterId) {
        bannerCharacters.firstOrNull { it.id == pinnedCharacterId }?.character
    }
    val pinnedSessions = remember(homeRows, pinnedCharacterId) {
        if (pinnedCharacterId.isBlank()) {
            emptyList()
        } else {
            homeRows.filter { it.characterId == pinnedCharacterId }
        }
    }
    // 固定角色在 banner 上展示的那一段会话（角色还没有会话时为 null）
    val pinnedRow = remember(homeRows, bannerSessionId, pinnedSessions) {
        pinnedSessions.firstOrNull { it.id == bannerSessionId }
            ?: pinnedSessions.firstOrNull()
            ?: homeRows.firstOrNull { it.id == bannerSessionId }
    }
    val pinnedActive = pinnedMode && (pinnedCharacter != null || pinnedRow != null)
    val latestRow = homeRows.firstOrNull()
    val pinnedCompanionSession = pinnedSessions.firstOrNull {
        it.modeLabel == SessionModeLabel.COMPANION
    }

    // 场景卡：问候区与「继续上次」合并成同一张卡，避免两块内容各说各话。
    // 卡片始终围绕一个角色：固定角色 > 显式指定的角色 > 最近会话的角色 > 应用图标。
    // 关闭聚合后恢复原来的独立 banner：卡片只认手动选择的欢迎角色。
    val aggregateBanner by prefs.homeBannerAggregatedFlow.collectAsStateWithLifecycle()
    // 欢迎形象 / 来源 / 取景都必须可观察：非聚合模式下选完形象没有本地状态变化，
    // 直接读 SharedPreferences 不会触发重组，卡片要等被销毁重建（滑走再滑回）才刷新。
    val manualCharacterId by prefs.homeWelcomeCharacterIdFlow.collectAsStateWithLifecycle()
    val sceneSource by prefs.homeSceneSourceFlow.collectAsStateWithLifecycle()
    val sceneCharacterId = remember(
        aggregateBanner, pinnedActive, pinnedCharacterId, manualCharacterId, sceneSource, latestRow
    ) {
        if (!aggregateBanner) {
            if (sceneSource == PrefsManager.HOME_SCENE_SOURCE_NONE) "" else manualCharacterId
        } else when {
            pinnedActive && pinnedCharacterId.isNotBlank() -> pinnedCharacterId
            sceneSource == PrefsManager.HOME_SCENE_SOURCE_NONE -> ""
            sceneSource == PrefsManager.HOME_SCENE_SOURCE_CHARACTER -> manualCharacterId
            else -> latestRow?.characterId.orEmpty()
        }
    }
    val sceneCharacter = remember(bannerCharacters, sceneCharacterId) {
        bannerCharacters.firstOrNull { it.id == sceneCharacterId }?.character
    }
    val sceneSessions = remember(homeRows, sceneCharacterId) {
        homeRows.filter { it.characterId == sceneCharacterId }
    }
    val shownRow = when {
        pinnedActive -> pinnedRow
        sceneCharacterId.isNotBlank() -> sceneSessions.firstOrNull()
        else -> latestRow
    }
    val scenePinned = pinnedActive && pinnedCharacter != null
    val targetTitle = when {
        scenePinned -> pinnedCharacter?.displayName.orEmpty()
        else -> shownRow?.listTitle.orEmpty()
    }
    val targetSubtitle = when {
        // 固定角色时只展示角色简介：不再把最新会话的消息内容挂在角色名下方
        scenePinned -> pinnedCharacter?.description?.trim()?.takeIf { it.isNotBlank() }
            ?: if (pinnedSessions.isEmpty()) stringResource(R.string.shell_banner_no_session) else null
        else -> shownRow?.lastMessage
    }
    val targetPortraitUrl = when {
        scenePinned -> pinnedCharacter?.let(::characterPortraitUrl) ?: shownRow?.portraitUrl
        else -> shownRow?.portraitUrl
    }
    val hasTarget = shownRow != null || scenePinned

    Column(modifier.fillMaxWidth()) {
        HomeSceneCard(
            characters = characters,
            character = sceneCharacter,
            characterId = sceneCharacterId,
            targetTitle = targetTitle,
            targetSubtitle = targetSubtitle,
            targetPortraitUrl = targetPortraitUrl,
            targetPinned = scenePinned,
            // 非聚合模式下「接着聊」只关心欢迎角色自己有没有会话
            hasTarget = if (aggregateBanner) hasTarget else sceneSessions.isNotEmpty(),
            showTargetRow = aggregateBanner,
            onOpenTarget = {
                when {
                    // 未固定：直接继续该角色最近那段对话
                    !scenePinned -> shownRow?.id?.let(onOpenSession)
                    // 单角色只有一个会话，且不能再新建：直接进入
                    pinnedCompanionSession?.id != null -> onOpenSession(pinnedCompanionSession.id)
                    // 角色还没有会话：直接打开「新建角色会话」弹窗
                    pinnedCharacter != null && pinnedSessions.isEmpty() ->
                        onCreateSessionForCharacter(sceneCharacterId)
                    // 老数据只有会话 id：退回直接进入
                    pinnedCharacter == null -> shownRow?.id?.let(onOpenSession)
                    else -> showSessionPicker = true
                }
            },
            onNewChat = onNewChat,
            onChooseSource = { showSourceSheet = true },
            onQuickChat = onQuickChat,
            onGreetingChanged = onQuickChatGreetingChanged,
            onSceneCharacterChanged = { id, portraitCrop ->
                if (id.isBlank()) {
                    prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_NONE
                    prefs.homeWelcomeCharacterId = ""
                    prefs.homePortraitCrop = ""
                } else {
                    prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_CHARACTER
                    prefs.homeWelcomeCharacterId = id
                    if (portraitCrop != null) prefs.homePortraitCrop = portraitCrop
                }
                // 只有聚合模式才让欢迎形象与「继续入口」指向同一个角色；
                // 关闭聚合时保持原来的语义：选形象不改变独立 banner 的来源。
                if (aggregateBanner) {
                    if (id.isBlank()) {
                        bannerMode = PrefsManager.HOME_BANNER_LATEST
                        bannerCharacterId = ""
                        bannerSessionId = ""
                        prefs.homeBannerMode = PrefsManager.HOME_BANNER_LATEST
                        prefs.homeBannerCharacterId = ""
                        prefs.homeBannerSessionId = ""
                    } else {
                        val latest = homeRows.firstOrNull { it.characterId == id }
                        bannerMode = PrefsManager.HOME_BANNER_PINNED
                        bannerCharacterId = id
                        bannerSessionId = latest?.id.orEmpty()
                        prefs.homeBannerMode = PrefsManager.HOME_BANNER_PINNED
                        prefs.homeBannerCharacterId = id
                        prefs.homeBannerSessionId = latest?.id.orEmpty()
                    }
                }
            }
        )
        if (!aggregateBanner) {
            Spacer(Modifier.height(12.dp))
            if (pinnedActive || latestRow != null) {
                val bannerRow = if (pinnedActive) pinnedRow else latestRow
                HomeSessionBanner(
                    title = if (pinnedActive) {
                        pinnedCharacter?.displayName ?: bannerRow?.listTitle.orEmpty()
                    } else {
                        bannerRow?.listTitle.orEmpty()
                    },
                    subtitle = if (pinnedActive && pinnedCharacter != null) {
                        pinnedCharacter.description?.trim()?.takeIf { it.isNotBlank() }
                            ?: if (pinnedSessions.isEmpty()) stringResource(R.string.shell_banner_no_session) else null
                    } else if (pinnedActive) {
                        bannerRow?.lastMessage ?: stringResource(R.string.shell_banner_no_session)
                    } else {
                        bannerRow?.lastMessage
                    },
                    showEmptySubtitle = !(pinnedActive && pinnedCharacter != null),
                    portraitUrl = if (pinnedActive) {
                        pinnedCharacter?.let(::characterPortraitUrl) ?: bannerRow?.portraitUrl
                    } else {
                        bannerRow?.portraitUrl
                    },
                    modeLabel = bannerRow?.modeLabel ?: SessionModeLabel.CHARACTER,
                    isGroupSession = bannerRow?.isGroupSession == true,
                    pinned = pinnedActive,
                    onOpen = {
                        when {
                            !pinnedActive -> bannerRow?.id?.let(onOpenSession)
                            pinnedCompanionSession?.id != null -> onOpenSession(pinnedCompanionSession.id)
                            pinnedCharacter != null && pinnedSessions.isEmpty() ->
                                onCreateSessionForCharacter(pinnedCharacterId)
                            pinnedCharacter == null -> bannerRow?.id?.let(onOpenSession)
                            else -> showSessionPicker = true
                        }
                    },
                    onChoose = { showSourceSheet = true }
                )
            } else {
                NekoPrimaryAction(
                    text = stringResource(R.string.shell_new_chat),
                    leadingIcon = Icons.Filled.Edit,
                    onClick = onNewChat
                )
            }
        }
        NekoDivider()
        Spacer(Modifier.height(16.dp))
        // 分段控件覆盖三类会话；其它筛选（收藏 / 归档 / 频道 / 按角色）由「筛选」抽屉承担。
        // 右侧胶囊：左段是归档快捷开关，右段打开筛选抽屉，抽屉里选中非分段项时直接显示该筛选项。
        val filterLabel = if (filter in sessionSegmentFilters) {
            stringResource(R.string.shell_filter)
        } else {
            stringResource(filter.labelResId)
        }
        val filterActive = filter !in sessionSegmentFilters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp)
                .heightIn(min = 38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.shell_your_chats),
                style = nekoSectionLabelStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.shell_chat_count, totalCount),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            val capsuleShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .clip(capsuleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, capsuleShape)
                    .heightIn(min = 38.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 归档会话快速筛选：点一下只看归档，再点回到全部
                val archivedActive = filter == SessionFilter.ARCHIVED
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .clickable {
                            onSelectFilter(
                                if (archivedActive) SessionFilter.ALL else SessionFilter.ARCHIVED
                            )
                        }
                        .heightIn(min = 38.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Archive,
                        contentDescription = stringResource(R.string.sessions_filter_archived),
                        tint = if (archivedActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(onClick = onOpenFilter)
                        .heightIn(min = 38.dp)
                        .padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = if (filterActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = filterLabel,
                        style = nekoCaptionStyle(),
                        color = if (filterActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
        NekoSegmentedControl(
            options = listOf(
                stringResource(R.string.shell_seg_all),
                stringResource(R.string.shell_seg_character),
                stringResource(R.string.shell_seg_agent),
                stringResource(R.string.shell_seg_single)
            ),
            selectedIndex = sessionSegmentFilters.indexOf(filter),
            onSelect = { index -> onSelectFilter(sessionSegmentFilters[index]) }
        )
        Spacer(Modifier.size(12.dp))
    }

    if (showSourceSheet) {
        BannerSourceSheet(
            entries = bannerCharacters,
            pinnedMode = pinnedMode,
            pinnedCharacterId = pinnedCharacterId,
            onDismiss = { showSourceSheet = false },
            onSelectLatest = {
                bannerMode = PrefsManager.HOME_BANNER_LATEST
                prefs.homeBannerMode = PrefsManager.HOME_BANNER_LATEST
                // 跟随最近会话：清掉显式角色，场景卡与问候重新跟随最新会话
                prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_AUTO
                prefs.homeWelcomeCharacterId = ""
                prefs.homePortraitCrop = ""
                showSourceSheet = false
            },
            onSelectCharacter = { entry ->
                bannerMode = PrefsManager.HOME_BANNER_PINNED
                bannerCharacterId = entry.id
                bannerSessionId = entry.latestSession?.id.orEmpty()
                prefs.homeBannerMode = PrefsManager.HOME_BANNER_PINNED
                prefs.homeBannerCharacterId = entry.id
                prefs.homeBannerSessionId = entry.latestSession?.id.orEmpty()
                prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_CHARACTER
                prefs.homeWelcomeCharacterId = entry.id
                showSourceSheet = false
            }
        )
    }

    if (showSessionPicker && pinnedSessions.isNotEmpty()) {
        PinnedRoleSheet(
            characterName = pinnedCharacter?.displayName ?: pinnedSessions.first().listTitle,
            sessions = pinnedSessions,
            onDismiss = { showSessionPicker = false },
            onOpenSession = { id ->
                showSessionPicker = false
                onOpenSession(id)
            },
            onCreateSession = {
                showSessionPicker = false
                onCreateSessionForCharacter(pinnedCharacterId)
            }
        )
    }
}

/** 首页 banner 可固定的角色：角色卡 + 该角色最近的一次会话（可能还没有会话）。 */
private data class BannerCharacterEntry(
    val character: CharacterPreset,
    val latestSession: SessionListRow?
) {
    val id: String get() = character.id.orEmpty()
    val title: String get() = character.displayName
    val modeLabel: SessionModeLabel
        get() = latestSession?.modeLabel ?: SessionModeLabel.CHARACTER
    val portraitUrl: String? get() = characterPortraitUrl(character)
    /** 有会话时显示最新消息，还没有会话时显示角色简介。 */
    val subtitle: String? get() = latestSession?.lastMessage
        ?: character.description?.takeIf { it.isNotBlank() }
}

/** 内容库角色 + 每个角色最近的一段会话；有会话的角色排在前面（按最近更新）。 */
private fun buildBannerCharacters(
    characters: List<CharacterPreset>,
    homeRows: List<SessionListRow>
): List<BannerCharacterEntry> {
    val library = characters.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
    val libraryById = library.associateBy { it.id }
    val latestByCharacter = LinkedHashMap<String, SessionListRow>()
    homeRows.forEach { row ->
        val characterId = row.characterId?.takeIf { it.isNotBlank() } ?: return@forEach
        if (!latestByCharacter.containsKey(characterId)) latestByCharacter[characterId] = row
    }
    val chatted = latestByCharacter.keys.mapNotNull { libraryById[it] }
    val never = library.filterNot { latestByCharacter.containsKey(it.id) }
    return (chatted + never).map { character ->
        BannerCharacterEntry(character, latestByCharacter[character.id])
    }
}

/** 角色立绘优先，其次头像。 */
private fun characterPortraitUrl(character: CharacterPreset): String? =
    resolveAvatarUrl(character.portrait?.takeIf { it.isNotBlank() })
        ?: resolveAvatarUrl(character.avatarUrl)

/** 分段筛选各项与筛选枚举的对应关系（下标与控件顺序一致）。 */
val sessionSegmentFilters = listOf(
    SessionFilter.ALL,
    SessionFilter.CHARACTER_SESSIONS,
    SessionFilter.AGENT_SESSIONS,
    SessionFilter.AGENT_CHARACTERS
)

// ---------------------------------------------------------------- 会话 banner

/**
 * 独立会话 banner（关闭聚合时使用，保持原版视觉）。
 *
 * 默认跟随最近聊过的会话；也可以在右侧「切换」里固定某个角色（普通角色或单角色），
 * 固定后 banner 不再随新消息跳动；单角色直接进入会话，还没有会话的角色点击即新建。
 */
@Composable
private fun HomeSessionBanner(
    title: String,
    subtitle: String?,
    portraitUrl: String?,
    modeLabel: SessionModeLabel,
    isGroupSession: Boolean,
    pinned: Boolean,
    onOpen: () -> Unit,
    onChoose: () -> Unit,
    showEmptySubtitle: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape)
            .clickable(onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BannerAvatar(
            portraitUrl = portraitUrl,
            modeLabel = modeLabel,
            fallbackText = title,
            isGroupSession = isGroupSession,
            size = 46.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(if (pinned) R.string.shell_banner_pinned else R.string.shell_banner_continue),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = title,
                    style = nekoRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val visibleSubtitle = subtitle?.takeIf { it.isNotBlank() }
            if (visibleSubtitle != null || showEmptySubtitle) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = visibleSubtitle ?: stringResource(R.string.sessions_no_message),
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        NekoIconAction(
            icon = Icons.Filled.SwapHoriz,
            contentDescription = stringResource(R.string.shell_banner_choose),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onChoose
        )
    }
}

/** banner 头像：与列表行同一套兜底（立绘 → 首字 → 图标）。 */
@Composable
internal fun BannerAvatar(
    portraitUrl: String?,
    modeLabel: SessionModeLabel,
    fallbackText: String,
    isGroupSession: Boolean,
    size: Dp
) {
    val context = LocalContext.current
    val modeColor = modeLabel.color()
    val request = remember(context, portraitUrl) {
        portraitUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .repeatCount(0)
                .build()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(modeColor.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            request != null -> AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            modeLabel == SessionModeLabel.COMPANION -> Text(
                text = fallbackText.takeIf(String::isNotBlank)
                    ?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
                    ?: stringResource(R.string.single_role_c),
                style = nekoRowTitleStyle(),
                color = modeColor
            )
            else -> Icon(
                painter = rememberVectorPainter(
                    if (isGroupSession) Icons.Outlined.Group else Icons.AutoMirrored.Outlined.Chat
                ),
                contentDescription = null,
                tint = modeColor,
                modifier = Modifier.size(size * 0.52f)
            )
        }
    }
}

/** banner 来源选择：最新会话 / 固定一个角色（含还没有会话的角色）。 */
@Composable
private fun BannerSourceSheet(
    entries: List<BannerCharacterEntry>,
    pinnedMode: Boolean,
    pinnedCharacterId: String,
    onDismiss: () -> Unit,
    onSelectLatest: () -> Unit,
    onSelectCharacter: (BannerCharacterEntry) -> Unit
) {
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = stringResource(R.string.shell_banner_choose),
            description = stringResource(R.string.shell_banner_choose_desc),
            onClose = onDismiss
        )
        NekoChoiceRow(
            title = stringResource(R.string.shell_banner_source_latest),
            subtitle = stringResource(R.string.shell_banner_source_latest_desc),
            onClick = onSelectLatest,
            trailing = { NekoCheckMark(selected = !pinnedMode) },
            showChevron = false,
            showDivider = entries.isNotEmpty()
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_banner_no_companion),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(entries, key = { it.id }) { entry ->
                    NekoChoiceRow(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        onClick = { onSelectCharacter(entry) },
                        leading = {
                            BannerAvatar(
                                portraitUrl = entry.portraitUrl,
                                modeLabel = entry.modeLabel,
                                fallbackText = entry.title,
                                isGroupSession = false,
                                size = 38.dp
                            )
                        },
                        trailing = {
                            NekoCheckMark(selected = pinnedMode && entry.id == pinnedCharacterId)
                        },
                        showChevron = false,
                        showDivider = true
                    )
                }
            }
        }
        NekoSheetNote(stringResource(R.string.shell_banner_source_note))
    }
}

/** 固定角色被点击后的选择：进入该角色的某个会话，或新建会话。 */
@Composable
private fun PinnedRoleSheet(
    characterName: String,
    sessions: List<SessionListRow>,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateSession: () -> Unit
) {
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = characterName,
            description = stringResource(R.string.shell_banner_pick_desc),
            onClose = onDismiss
        )
        NekoChoiceRow(
            title = stringResource(R.string.sessions_new),
            subtitle = stringResource(R.string.shell_banner_new_desc),
            onClick = onCreateSession,
            leading = {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            showChevron = false,
            showDivider = sessions.isNotEmpty()
        )
        if (sessions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(sessions, key = { it.key }) { row ->
                    val id = row.id.orEmpty()
                    NekoChoiceRow(
                        title = row.name.ifBlank { row.listTitle },
                        // 同一角色的会话名常常相同，带上日期才好区分
                        subtitle = listOfNotNull(
                            row.listDate,
                            row.lastMessage ?: stringResource(R.string.sessions_no_message)
                        ).joinToString(" · "),
                        onClick = { if (id.isNotEmpty()) onOpenSession(id) },
                        leading = {
                            BannerAvatar(
                                portraitUrl = row.portraitUrl,
                                modeLabel = row.modeLabel,
                                fallbackText = row.listTitle,
                                isGroupSession = row.isGroupSession,
                                size = 38.dp
                            )
                        },
                        showChevron = false,
                        showDivider = true
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 场景卡

/**
 * 场景卡：欢迎立绘 + 场景开场白 +「继续上次」入口。
 *
 * 问候先显示本地候选池（立即可见、避免重复），随后由 AI 按角色卡、记忆、
 * 最近对话与关系状态重写一句；点「接着聊」在首页浮出轻聊面板，
 * 面板与完整会话页共用同一条会话和同一套关系/剧情推进。
 */
@Composable
private fun HomeSceneCard(
    characters: List<CharacterPreset>,
    character: CharacterPreset?,
    characterId: String,
    targetTitle: String,
    targetSubtitle: String?,
    targetPortraitUrl: String?,
    targetPinned: Boolean,
    hasTarget: Boolean,
    /** 聚合模式：卡片内展示「接着上次」；关闭后改由独立 banner 承担。 */
    showTargetRow: Boolean,
    onOpenTarget: () -> Unit,
    onNewChat: () -> Unit,
    onChooseSource: () -> Unit,
    onQuickChat: ((HomeQuickChatRequest) -> Unit)?,
    /** 问候语变化时同步给轻聊面板（角色 ID + 当前问候）。 */
    onGreetingChanged: (String, String) -> Unit,
    onSceneCharacterChanged: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = ServiceContainer.prefs
    val period = remember {
        greetingPeriodFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }
    // 当前语言：切换语言后问候要按新语言重新生成，缓存也按语言区分
    val locales = LocalConfiguration.current.locales
    val localeTag = remember(locales) { locales[0]?.toLanguageTag().orEmpty() }
    val localLine = rememberLocalGreetingLine(period)
    // 用 rememberSaveable：列表滚出再滚回时保留同一句问候，不会重新随机/闪动
    var line by rememberSaveable(period.key, characterId, localeTag) { mutableStateOf(localLine) }
    var showPicker by remember { mutableStateOf(false) }
    var croppingCharacter by remember { mutableStateOf<CharacterPreset?>(null) }
    // 点击阅读完整问候，在展开面板里主动换一句。
    var regenerateKey by remember { mutableIntStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var showFullGreeting by remember { mutableStateOf(false) }
    // 取景参数同样要可观察：重新裁剪后立绘应立即套用新取景
    val portraitCropPref by prefs.homePortraitCropFlow.collectAsStateWithLifecycle()
    val crop = remember(characterId, portraitCropPref) {
        parsePortraitCrop(portraitCropPref, characterId)
    }

    LaunchedEffect(period.key, characterId, character, localeTag, regenerateKey) {
        // 角色列表还没加载完（有 id 但解析不出角色卡）时先不生成：
        // 否则会用默认 Neko 口吻生成并缓存到该角色名下，30 分钟内都不会纠正。
        if (characterId.isNotBlank() && character == null) return@LaunchedEffect
        // regenerateKey > 0 表示用户主动点了问候语：跳过缓存强制重新生成
        val force = regenerateKey > 0
        // 先取角色记忆（单角色会话的融合记忆 + 普通角色会话的记忆）：只作为提示词素材。
        val memoryBrief = if (characterId.isBlank()) "" else runCatching {
            ServiceContainer.unified.homeGreetingMemoryBrief(characterId)
        }.getOrDefault("")
        // 最近对话 + 当前关系：让开场白承接上一次聊天，避免只按长期记忆说空话
        val sceneBrief = if (characterId.isBlank()) "" else runCatching {
            ServiceContainer.unified.homeSceneBrief(characterId)
        }.getOrDefault("")
        // 缓存键只按「角色 + 时段 + 语言」定位：问候一旦生成，就在 TTL 内保持稳定。
        // 记忆与最近对话只作素材，不算进缓存键——否则聊完一轮、或在首页轻聊里退出，
        // 素材变了就会立刻重写问候，看起来像问候语自己莫名换了台词。
        val cacheKey = "$characterId|${period.key}|$localeTag"
        if (!force) {
            val cachedKey = prefs.homeGreetingAiKey
            val cachedLine = prefs.homeGreetingAiLine
            val cacheFresh = cachedKey.startsWith("$cacheKey|") && runCatching {
                System.currentTimeMillis() - cachedKey.substringAfterLast('|').toLong() <
                    GREETING_CACHE_TTL_MS
            }.getOrDefault(false)
            if (cacheFresh && cachedLine.isNotBlank()) {
                line = cachedLine
                return@LaunchedEffect
            }
        }
        generating = true
        val result = runCatching {
            ServiceContainer.unified.generateHomeGreeting(
                character, period.labelForPrompt, memoryBrief, sceneBrief
            )
        }.getOrNull()
        generating = false
        val generated = (result as? Resource.Success<String>)?.data?.trim()
        if (!generated.isNullOrBlank()) {
            line = generated
            prefs.homeGreetingAiLine = generated
            prefs.homeGreetingAiKey = "$cacheKey|${System.currentTimeMillis()}"
        }
    }

    // 问候语可能先本地候选、后 AI 重写：面板若已打开且还没聊，跟着换成最新一句。
    LaunchedEffect(line, characterId) {
        onGreetingChanged(characterId, line)
    }

    val canQuickChat = onQuickChat != null && characterId.isNotBlank()
    val startQuickChat: (Boolean) -> Unit = { preferExisting ->
        onQuickChat?.invoke(
            HomeQuickChatRequest(
                characterId = characterId,
                characterName = character?.displayName ?: targetTitle,
                portraitUrl = character?.let(::characterPortraitUrl) ?: targetPortraitUrl,
                openingLine = line,
                preferExisting = preferExisting
            )
        )
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WelcomePortrait(character = character, crop = crop, onClick = { showPicker = true })
            Spacer(Modifier.width(15.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.shell_greeting_full_title)) {
                        showFullGreeting = true
                    }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(period.titleRes),
                    style = nekoWelcomeTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line,
                        style = nekoRowSubtitleStyle().copy(fontSize = nekoTextSize(13f)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (generating) 0.5f else 1f
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (generating) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (showTargetRow) {
            Spacer(Modifier.height(12.dp))
            // 卡内分隔线：问候与「继续上次」仍是同一张卡，但分成上下两层
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            )
            Spacer(Modifier.height(8.dp))
            HomeSceneCardFooter(
                targetTitle = targetTitle,
                targetSubtitle = targetSubtitle,
                targetPinned = targetPinned,
                hasTarget = hasTarget,
                showTargetRow = showTargetRow,
                canQuickChat = canQuickChat,
                onOpenTarget = onOpenTarget,
                onNewChat = onNewChat,
                onChooseSource = onChooseSource,
                onStartChat = startQuickChat
            )
        }
    }

    if (showFullGreeting) {
        val quickChatLabel = stringResource(
            if (hasTarget) R.string.shell_scene_card_chat else R.string.shell_scene_card_start
        )
        // 非聚合模式：轻聊入口放在这里，卡片本身只保留问候
        val quickChatAvailable = !showTargetRow && canQuickChat
        NekoDialog(
            onDismiss = { showFullGreeting = false },
            title = stringResource(R.string.shell_greeting_full_title),
            message = line,
            confirmText = stringResource(R.string.shell_greeting_regenerate),
            onConfirm = {
                showFullGreeting = false
                regenerateKey++
            },
            extraActionText = quickChatLabel.takeIf { quickChatAvailable },
            onExtraAction = if (quickChatAvailable) {
                {
                    showFullGreeting = false
                    startQuickChat(hasTarget)
                }
            } else null,
            // 「关闭 / 接着聊 / 重新生成」用紧凑按钮排在同一行
            compactActions = quickChatAvailable,
            cancelText = stringResource(R.string.common_close),
            onCancel = { showFullGreeting = false }
        )
    }

    if (showPicker) {
        WelcomePortraitSheet(
            characters = characters,
            selectedCharacterId = characterId,
            onDismiss = { showPicker = false },
            onSelect = { id ->
                showPicker = false
                if (id.isEmpty()) {
                    // 应用图标不需要取景：回到「跟随最近会话」
                    onSceneCharacterChanged("", null)
                } else {
                    characters.firstOrNull { it.id == id }?.let { croppingCharacter = it }
                }
            }
        )
    }

    // 选完角色先进取景框确认方形区域，确认后才写入偏好
    croppingCharacter?.let { target ->
        PortraitCropDialog(
            character = target,
            initial = parsePortraitCrop(prefs.homePortraitCrop, target.id.orEmpty()),
            onDismiss = { croppingCharacter = null },
            onConfirm = { confirmed ->
                onSceneCharacterChanged(
                    target.id.orEmpty(),
                    serialisePortraitCrop(target.id.orEmpty(), confirmed)
                )
                croppingCharacter = null
            }
        )
    }
}

/** 场景卡下半层：聚合模式放「接着上次」，非聚合模式只留轻聊入口。 */
@Composable
private fun HomeSceneCardFooter(
    targetTitle: String,
    targetSubtitle: String?,
    targetPinned: Boolean,
    hasTarget: Boolean,
    showTargetRow: Boolean,
    canQuickChat: Boolean,
    onOpenTarget: () -> Unit,
    onNewChat: () -> Unit,
    onChooseSource: () -> Unit,
    onStartChat: (preferExisting: Boolean) -> Unit
) {
    if (!showTargetRow) return
    when {
        hasTarget -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenTarget)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (targetPinned) R.string.shell_banner_pinned else R.string.shell_banner_continue
                        ),
                        style = nekoCaptionStyle(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    if (targetTitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = targetTitle,
                            style = nekoRowTitleStyle(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val visibleSubtitle = targetSubtitle?.takeIf { it.isNotBlank() }
                    if (visibleSubtitle != null) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = visibleSubtitle,
                            style = nekoRowSubtitleStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (canQuickChat) {
                SceneActionChip(
                    text = stringResource(R.string.shell_scene_card_chat),
                    onClick = { onStartChat(true) }
                )
                Spacer(Modifier.width(2.dp))
            }
            SceneIconButton(
                icon = Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.shell_banner_choose),
                onClick = onChooseSource
            )
        }

        canQuickChat -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shell_scene_card_first_meet),
                    style = nekoRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.shell_banner_no_session),
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SceneActionChip(
                text = stringResource(R.string.shell_scene_card_start),
                onClick = { onStartChat(false) }
            )
            Spacer(Modifier.width(2.dp))
            SceneIconButton(
                icon = Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.shell_banner_choose),
                onClick = onChooseSource
            )
        }

        else -> NekoPrimaryAction(
            text = stringResource(R.string.shell_new_chat),
            leadingIcon = Icons.Filled.Edit,
            onClick = onNewChat
        )
    }
}

/** 场景卡上的小型胶囊动作。 */
@Composable
private fun SceneActionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 场景卡上的紧凑图标动作（比顶栏 48dp 触摸区更窄）。 */
@Composable
private fun SceneIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
    }
}

/** 欢迎立绘：76dp 圆角方图，右下角铅笔标提示可更换。 */
@Composable
private fun WelcomePortrait(
    character: CharacterPreset?,
    crop: PortraitCrop,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier.size(76.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val avatarUrl = resolveAvatarUrl(character?.avatarUrl)
            when {
                // 用位图 logo 而不是 ic_launcher：后者是自适应图标 XML，
                // Compose 的 painterResource 无法把它转成位图（会崩）。
                character == null -> Image(
                    painter = painterResource(R.mipmap.neko),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                avatarUrl != null -> CroppedPortraitImage(
                    url = avatarUrl,
                    crop = crop,
                    modifier = Modifier.fillMaxSize()
                )
                else -> NekoLetterAvatar(
                    text = character.displayName,
                    size = 76.dp,
                    modifier = Modifier.fillMaxSize().clip(shape)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 欢迎形象选择抽屉：应用图标 + 内容库里的角色立绘。 */
@Composable
private fun WelcomePortraitSheet(
    characters: List<CharacterPreset>,
    selectedCharacterId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = stringResource(R.string.shell_welcome_picker_title),
            description = stringResource(R.string.shell_welcome_picker_desc),
            onClose = onDismiss
        )
        NekoChoiceRow(
            title = stringResource(R.string.shell_welcome_app_icon),
            subtitle = stringResource(R.string.shell_welcome_app_icon_desc),
            onClick = { onSelect("") },
            leading = {
                Image(
                    painter = painterResource(R.mipmap.neko),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                )
            },
            trailing = { NekoCheckMark(selected = selectedCharacterId.isEmpty()) },
            showChevron = false,
            showDivider = characters.isNotEmpty()
        )
        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
            items(characters, key = { it.id ?: it.displayName }) { character ->
                val id = character.id.orEmpty()
                NekoChoiceRow(
                    title = character.displayName,
                    subtitle = stringResource(R.string.shell_welcome_character_desc),
                    onClick = { if (id.isNotEmpty()) onSelect(id) },
                    leading = {
                        val url = resolveAvatarUrl(character.avatarUrl)
                        if (url != null) {
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(11.dp))
                            )
                        } else {
                            NekoLetterAvatar(text = character.displayName, size = 40.dp)
                        }
                    },
                    trailing = { NekoCheckMark(selected = id == selectedCharacterId) },
                    showChevron = false,
                    showDivider = true
                )
            }
        }
        NekoSheetNote(stringResource(R.string.shell_welcome_note))
    }
}

// ---------------------------------------------------------------- 问候语

/** AI 问候语的缓存有效期：半小时内重复进入首页不再请求。 */
private const val GREETING_CACHE_TTL_MS = 30 * 60 * 1000L

/** 一个时段的可选问候语资源。 */
private data class GreetingPeriod(
    val key: String,
    val titleRes: Int,
    /** 给 AI 的时段描述（提示词用，固定中文；输出语言由语言指令控制）。 */
    val labelForPrompt: String,
    val lineRes: List<Int>
)

private fun greetingPeriodFor(hour: Int): GreetingPeriod = when {
    hour in 5..10 -> GreetingPeriod(
        "morning", R.string.shell_greet_morning_title, "早上",
        listOf(R.string.shell_greet_morning_1, R.string.shell_greet_morning_2, R.string.shell_greet_morning_3)
    )
    hour in 11..13 -> GreetingPeriod(
        "midday", R.string.shell_greet_midday_title, "中午",
        listOf(R.string.shell_greet_midday_1, R.string.shell_greet_midday_2, R.string.shell_greet_midday_3)
    )
    hour in 14..17 -> GreetingPeriod(
        "afternoon", R.string.shell_greet_afternoon_title, "下午",
        listOf(R.string.shell_greet_afternoon_1, R.string.shell_greet_afternoon_2, R.string.shell_greet_afternoon_3)
    )
    hour in 18..22 -> GreetingPeriod(
        "evening", R.string.shell_greet_evening_title, "晚上",
        listOf(R.string.shell_greet_evening_1, R.string.shell_greet_evening_2, R.string.shell_greet_evening_3)
    )
    else -> GreetingPeriod(
        "night", R.string.shell_greet_night_title, "深夜",
        listOf(R.string.shell_greet_night_1, R.string.shell_greet_night_2, R.string.shell_greet_night_3)
    )
}

/**
 * 本地候选池里挑一句（立即可见）。
 *
 * 最近 8 条记录过的组合不再重复出现；候选全部用完时清空历史重新开始。
 */
@Composable
private fun rememberLocalGreetingLine(period: GreetingPeriod): String {
    val lineRes = rememberSaveable(period) {
        val history = ServiceContainer.prefs.homeGreetingHistory
            .split('|')
            .filter { it.isNotBlank() }
        val allIds = period.lineRes.indices.map { "${period.key}:$it" }
        val fresh = allIds.filterNot { it in history }
        val pool = if (fresh.isEmpty()) allIds else fresh
        val picked = pool[Random.nextInt(pool.size)]
        val index = picked.substringAfter(':').toIntOrNull() ?: 0
        val nextHistory = (if (fresh.isEmpty()) listOf(picked) else history + picked).takeLast(8)
        runCatching { ServiceContainer.prefs.homeGreetingHistory = nextHistory.joinToString("|") }
        period.lineRes[index]
    }
    return stringResource(lineRes)
}

// ---------------------------------------------------------------- 立绘取景

/**
 * 方形取景参数（相对整张图片的比例，与显示尺寸无关）。
 *
 * - [frame]：方形取景框边长 ÷ 图片宽度。0 表示"自动"＝能放下的最大方形（居中）。
 * - [centerX]/[centerY]：取景框中心在图片里的位置（0..1）。
 *
 * 用比例而不是像素保存，76dp 头像与取景预览框才会显示同一块画面。
 */
private data class PortraitCrop(
    val frame: Float = 0f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f
)

private fun parsePortraitCrop(raw: String, characterId: String): PortraitCrop {
    if (characterId.isBlank()) return PortraitCrop()
    val parts = raw.split('|')
    if (parts.size != 4 || parts[0] != characterId) return PortraitCrop()
    return PortraitCrop(
        frame = parts[1].toFloatOrNull()?.coerceIn(0.05f, 1f) ?: 0f,
        centerX = parts[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f,
        centerY = parts[3].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
    )
}

private fun serialisePortraitCrop(characterId: String, crop: PortraitCrop): String =
    "$characterId|${crop.frame}|${crop.centerX}|${crop.centerY}"

/** 图片长宽比下，方形取景框边长最多能占图片宽度的比例。 */
private fun maxFrameFraction(imageAspect: Float): Float =
    if (imageAspect >= 1f) 1f / imageAspect else 1f

/**
 * 按取景参数绘制头像。
 *
 * 直接从位图里取**源矩形**（正方形）再铺满方框：不依赖 ContentScale 的裁切规则，
 * 也不用自己推算缩放/位移，因此既不会露白边，也不会多裁或少裁。
 */
@Composable
private fun CroppedPortraitImage(
    url: String,
    crop: PortraitCrop,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val loaded = bitmap
    if (loaded == null) {
        // 位图还没拿到：先按 Crop 铺一张，拿到后立刻切成精确的源矩形
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state -> bitmap = state.result.drawable.toImageBitmapOrNull() },
            modifier = modifier
        )
        return
    }
    val imageWidth = loaded.width
    val imageHeight = loaded.height
    if (imageWidth <= 0 || imageHeight <= 0) {
        Image(painter = BitmapPainter(loaded), contentDescription = null, modifier = modifier)
        return
    }
    val aspect = imageWidth.toFloat() / imageHeight.toFloat()
    val maxFrame = maxFrameFraction(aspect)
    val frame = if (crop.frame <= 0f) maxFrame else crop.frame.coerceIn(0.05f, maxFrame)
    val sourceSide = (frame * imageWidth).roundToInt()
        .coerceIn(1, minOf(imageWidth, imageHeight))
    val half = sourceSide / 2f
    val centerX = (crop.centerX * imageWidth).coerceIn(half, imageWidth - half)
    val centerY = (crop.centerY * imageHeight).coerceIn(half, imageHeight - half)
    val left = (centerX - half).roundToInt().coerceIn(0, imageWidth - sourceSide)
    val top = (centerY - half).roundToInt().coerceIn(0, imageHeight - sourceSide)
    val painter = remember(loaded, left, top, sourceSide) {
        BitmapPainter(
            image = loaded,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(sourceSide, sourceSide)
        )
    }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
    )
}
/**
 * 立绘取景：**先看到完整图片**，再拖动方形取景框选区域，双指缩放改变方框大小。
 */
@Composable
private fun PortraitCropDialog(
    character: CharacterPreset,
    initial: PortraitCrop,
    onDismiss: () -> Unit,
    onConfirm: (PortraitCrop) -> Unit
) {
    var aspect by remember { mutableFloatStateOf(1f) }
    var frameFraction by remember { mutableFloatStateOf(1f) }
    var centerX by remember { mutableFloatStateOf(initial.centerX) }
    var centerY by remember { mutableFloatStateOf(initial.centerY) }
    val avatarUrl = resolveAvatarUrl(character.avatarUrl)

    // 已有取景：把"边长占图片宽度比例"换回"占显示短边比例"
    LaunchedEffect(aspect) {
        if (initial.frame > 0f) {
            frameFraction = (initial.frame / maxFrameFraction(aspect)).coerceIn(0.25f, 1f)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                cornerRadius = 20,
                containerColor = MaterialTheme.colorScheme.surface,
                borderWidth = 1
            ) {
                Text(
                    text = stringResource(R.string.shell_portrait_crop_title),
                    style = nekoRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                // 预览框：在可用宽度与最大高度内按图片比例 contain，避免长图把按钮顶出屏幕
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentAlignment = Alignment.Center
                ) {
                val previewAspect = if (aspect > 0f) aspect else 1f
                val previewWidth = if (maxWidth / previewAspect <= maxHeight) maxWidth else maxHeight * previewAspect
                val previewHeight = previewWidth / previewAspect
                Box(
                    modifier = Modifier
                        .size(previewWidth, previewHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .pointerInput(previewAspect) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val boxWidth = size.width.toFloat()
                                val boxHeight = size.height.toFloat()
                                val minSide = minOf(boxWidth, boxHeight)
                                if (minSide <= 0f) return@detectTransformGestures
                                val nextFrame = (frameFraction * gestureZoom).coerceIn(0.25f, 1f)
                                val half = nextFrame * minSide / 2f
                                frameFraction = nextFrame
                                val nextX = (centerX * boxWidth + pan.x)
                                    .coerceIn(half, (boxWidth - half).coerceAtLeast(half))
                                val nextY = (centerY * boxHeight + pan.y)
                                    .coerceIn(half, (boxHeight - half).coerceAtLeast(half))
                                centerX = if (boxWidth > 0f) nextX / boxWidth else 0.5f
                                centerY = if (boxHeight > 0f) nextY / boxHeight else 0.5f
                            }
                        }
                ) {
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            onSuccess = { state ->
                                val drawable = state.result.drawable
                                val width = drawable.intrinsicWidth
                                val height = drawable.intrinsicHeight
                                if (width > 0 && height > 0) {
                                    aspect = width.toFloat() / height.toFloat()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        NekoLetterAvatar(
                            text = character.displayName,
                            size = 96.dp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    // 取景框：框外压暗，框线用强调色，一眼能看出会截哪一块
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val boxWidth = size.width
                        val boxHeight = size.height
                        val minSide = minOf(boxWidth, boxHeight)
                        val side = frameFraction * minSide
                        val half = side / 2f
                        val cx = (centerX * boxWidth).coerceIn(half, (boxWidth - half).coerceAtLeast(half))
                        val cy = (centerY * boxHeight).coerceIn(half, (boxHeight - half).coerceAtLeast(half))
                        val left = cx - half
                        val top = cy - half
                        val dim = Color.Black.copy(alpha = 0.45f)
                        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(boxWidth, top))
                        drawRect(dim, topLeft = Offset(0f, top + side), size = Size(boxWidth, boxHeight - top - side))
                        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, side))
                        drawRect(dim, topLeft = Offset(left + side, top), size = Size(boxWidth - left - side, side))
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(left, top),
                            size = Size(side, side),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.shell_portrait_crop_hint),
                    style = nekoCaptionStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        frameFraction = 1f
                        centerX = 0.5f
                        centerY = 0.5f
                    }) {
                        Text(
                            stringResource(R.string.shell_portrait_crop_reset),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                PortraitCrop(
                                    frame = (frameFraction * maxFrameFraction(aspect))
                                        .coerceIn(0.05f, 1f),
                                    centerX = centerX.coerceIn(0f, 1f),
                                    centerY = centerY.coerceIn(0f, 1f)
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            }
        }
    }
}

/** 把 Coil 的 drawable 转成 ImageBitmap（兼容不同 Coil 版本；失败返回 null）。 */
private fun android.graphics.drawable.Drawable?.toImageBitmapOrNull(): ImageBitmap? {
    if (this == null) return null
    val bitmap = runCatching {
        if (this is android.graphics.drawable.BitmapDrawable) {
            this.bitmap
        } else {
            this.toBitmap()
        }
    }.getOrNull() ?: return null
    return if (bitmap.width > 0 && bitmap.height > 0) bitmap.asImageBitmap() else null
}
