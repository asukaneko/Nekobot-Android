package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.Image
import com.nekobot.app.ui.components.NekoDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.GlassCard
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.nekobot.app.ui.components.FramedPortraitImage
import com.nekobot.app.ui.components.PortraitFramingDialog
import com.nekobot.app.ui.components.rememberPortraitFraming
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
    onQuickChatGreetingChanged: (String, String) -> Unit = { _, _ -> },
    /** false = 不展示欢迎区（独立会话列表页）。 */
    showWelcome: Boolean = true,
    /** false = 首页导航形态：只保留欢迎卡，不要筛选胶囊与分段控件。 */
    showListControls: Boolean = true
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
        if (showWelcome) {
        HomeSceneCard(
            characters = characters,
            character = sceneCharacter,
            characterId = sceneCharacterId,
            targetTitle = targetTitle,
            targetSubtitle = targetSubtitle,
            targetPortraitUrl = targetPortraitUrl,
            // 非聚合模式下「接着聊」只关心欢迎角色自己有没有会话
            hasTarget = if (aggregateBanner) hasTarget else sceneSessions.isNotEmpty(),
            // 固定角色还没有会话时，卡片右下的胶囊要说「开始聊」而不是「接着聊」
            targetHasSession = shownRow != null,
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
            // 聚合模式下「改角色」统一收在立绘右下角的编辑按钮里：
            // 跟随最近会话 / 应用图标 / 指定角色 三选一，不再单独放「切换」图标。
            followLatestSelected = !pinnedMode &&
                sceneSource == PrefsManager.HOME_SCENE_SOURCE_AUTO,
            onFollowLatest = {
                bannerMode = PrefsManager.HOME_BANNER_LATEST
                bannerCharacterId = ""
                bannerSessionId = ""
                prefs.homeBannerMode = PrefsManager.HOME_BANNER_LATEST
                prefs.homeBannerCharacterId = ""
                prefs.homeBannerSessionId = ""
                prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_AUTO
                prefs.homeWelcomeCharacterId = ""
            },
            onQuickChat = onQuickChat,
            onGreetingChanged = onQuickChatGreetingChanged,
            onSceneCharacterChanged = { id ->
                if (id.isBlank()) {
                    prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_NONE
                    prefs.homeWelcomeCharacterId = ""
                } else {
                    prefs.homeSceneSource = PrefsManager.HOME_SCENE_SOURCE_CHARACTER
                    prefs.homeWelcomeCharacterId = id
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
                    // 立绘取景按角色取，固定角色与最近会话角色都能对上同一份
                    characterId = if (pinnedActive) {
                        pinnedCharacter?.id ?: bannerRow?.characterId
                    } else {
                        bannerRow?.characterId
                    },
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
        }
        if (showListControls) {
        // 欢迎卡与列表之间先留一段空白再落线，卡片不会像被压在分隔线上；
        // 不展示欢迎区时（独立会话列表）直接从分段筛选开始，不要这条线
        if (showWelcome) {
            Spacer(Modifier.height(14.dp))
            NekoDivider()
        }
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
                .padding(top = 20.dp, bottom = 8.dp)
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
    /** 会话所属角色：套用该角色的立绘取景，与首页立绘保持一致。 */
    characterId: String? = null,
    showEmptySubtitle: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    // 与场景卡里的「继续」白卡同一套语言：白底 + 1dp 细线，暖色只留给上面的问候卡
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
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
            size = 46.dp,
            characterId = characterId
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
    size: Dp,
    /** 有角色 ID 时套用该角色的立绘取景，与首页立绘 / 模式卡取景一致。 */
    characterId: String? = null
) {
    val context = LocalContext.current
    val modeColor = modeLabel.color()
    // 与首页欢迎立绘、角色聊天模式卡共用同一份"按角色"的取景
    val framing = rememberPortraitFraming(characterId)
    val framedUrl = portraitUrl?.takeIf { characterId != null }
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
            framedUrl != null -> FramedPortraitImage(
                url = framedUrl,
                framing = framing,
                modifier = Modifier.fillMaxSize()
            )
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
                                size = 38.dp,
                                characterId = entry.id
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
                                size = 38.dp,
                                characterId = row.characterId
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
    hasTarget: Boolean,
    /** 目标角色是否已有会话：决定右下角胶囊说「接着聊」还是「开始聊」。 */
    targetHasSession: Boolean,
    /** 聚合模式：卡片内展示「接着上次」；关闭后改由独立 banner 承担。 */
    showTargetRow: Boolean,
    onOpenTarget: () -> Unit,
    onNewChat: () -> Unit,
    /** 当前是否跟随最近会话（聚合模式编辑按钮里的勾选状态）。 */
    followLatestSelected: Boolean,
    onFollowLatest: () -> Unit,
    onQuickChat: ((HomeQuickChatRequest) -> Unit)?,
    /** 问候语变化时同步给轻聊面板（角色 ID + 当前问候）。 */
    onGreetingChanged: (String, String) -> Unit,
    /** 换欢迎形象；取景参数由取景对话框按角色单独写入偏好。 */
    onSceneCharacterChanged: (String) -> Unit,
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
        // 场景卡是首屏唯一的暖色面：主色淡化到 40% 作底，再用 1dp 细线收边，
        // 与下方的白底列表拉开一层；下半层再用纯白内嵌卡压回来，形成「暖底 + 白块」的层级。
        containerColor = lerp(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primaryContainer,
            0.40f
        ),
        borderWidth = 1,
        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WelcomePortrait(
                character = character,
                characterId = characterId,
                onClick = { showPicker = true }
            )
            Spacer(Modifier.width(14.dp))
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
                Spacer(Modifier.size(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line,
                        style = nekoRowSubtitleStyle().copy(
                            fontSize = nekoTextSize(13f),
                            lineHeight = nekoTextSize(19f)
                        ),
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
            // 不用贯穿整卡的细线分隔：下半层做成一张内嵌白卡，
            // 「问候」与「哪段对话」各自成块，谁是谁一眼可辨。
            HomeSceneCardFooter(
                targetTitle = targetTitle,
                targetSubtitle = targetSubtitle,
                hasTarget = hasTarget,
                targetHasSession = targetHasSession,
                showTargetRow = showTargetRow,
                canQuickChat = canQuickChat,
                onOpenTarget = onOpenTarget,
                onNewChat = onNewChat,
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
            // 聚合模式：这张抽屉同时承担「继续哪段对话」的来源选择
            showFollowLatest = showTargetRow,
            followLatestSelected = followLatestSelected,
            onFollowLatest = {
                showPicker = false
                onFollowLatest()
            },
            onDismiss = { showPicker = false },
            onSelect = { id ->
                showPicker = false
                if (id.isEmpty()) {
                    // 应用图标不需要取景：直接把来源切成「应用图标」
                    onSceneCharacterChanged("")
                } else {
                    characters.firstOrNull { it.id == id }?.let { croppingCharacter = it }
                }
            }
        )
    }

    // 选完角色先进取景框确认，确认后才写入偏好（与模式卡共用同一份取景）
    croppingCharacter?.let { target ->
        val id = target.id.orEmpty()
        PortraitFramingDialog(
            imageUrl = characterPortraitUrl(target),
            characterName = target.displayName,
            // 首页 banner 的立绘是方形，预览也按方形，所见即所得
            previewAspect = 1f,
            initial = rememberPortraitFraming(id),
            onDismiss = { croppingCharacter = null },
            onConfirm = { confirmed ->
                prefs.setPortraitFraming(id, confirmed.serialise())
                onSceneCharacterChanged(id)
                croppingCharacter = null
            }
        )
    }
}

/** 场景卡下半层：把「继续哪段对话」收成一张内嵌白卡，左侧内容、右侧动作。 */
@Composable
private fun HomeSceneCardFooter(
    targetTitle: String,
    targetSubtitle: String?,
    hasTarget: Boolean,
    targetHasSession: Boolean,
    showTargetRow: Boolean,
    canQuickChat: Boolean,
    onOpenTarget: () -> Unit,
    onNewChat: () -> Unit,
    onStartChat: (preferExisting: Boolean) -> Unit
) {
    if (!showTargetRow) return
    when {
        hasTarget -> SceneResumeRow(
            title = targetTitle,
            subtitle = targetSubtitle,
            actionText = stringResource(
                if (targetHasSession) R.string.shell_scene_card_chat else R.string.shell_scene_card_start
            ),
            onOpen = onOpenTarget,
            onAction = if (canQuickChat) {
                { onStartChat(true) }
            } else {
                null
            }
        )

        canQuickChat -> SceneResumeRow(
            title = stringResource(R.string.shell_scene_card_first_meet),
            subtitle = stringResource(R.string.shell_banner_no_session),
            actionText = stringResource(R.string.shell_scene_card_start),
            // 还没有会话：整行没有可进入的目标，只保留「开始聊」
            onOpen = null,
            onAction = { onStartChat(false) }
        )

        else -> NekoPrimaryAction(
            text = stringResource(R.string.shell_new_chat),
            leadingIcon = Icons.Filled.Edit,
            onClick = onNewChat
        )
    }
}

/**
 * 内嵌的「继续上次」白卡：角色名 + 一行摘要，
 * 右侧是主行动胶囊与换角色的图标。
 *
 * 没有会话时 [onOpen] 传 null，整张卡不再可点，避免点了没反应。
 */
@Composable
private fun SceneResumeRow(
    title: String,
    subtitle: String?,
    actionText: String,
    onOpen: (() -> Unit)?,
    onAction: (() -> Unit)?
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val visibleSubtitle = subtitle?.takeIf { it.isNotBlank() }
            if (visibleSubtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = visibleSubtitle,
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onAction != null) {
            Spacer(Modifier.width(8.dp))
            SceneActionChip(text = actionText, onClick = onAction)
        }
    }
}

/** 场景卡上的主行动胶囊：实心主色，和摘要区分开，一眼能看出「点这里继续」。 */
@Composable
private fun SceneActionChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = nekoCaptionStyle().copy(fontSize = nekoTextSize(12f)),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 欢迎立绘：76dp 圆角方图，右下角铅笔标提示可更换。 */
@Composable
private fun WelcomePortrait(
    character: CharacterPreset?,
    characterId: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    // 与角色聊天模式卡共用同一份取景：卡片上怎么调，banner 就怎么显示
    val framing = rememberPortraitFraming(characterId)
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
            // 优先立绘（与模式卡封面同源），没有立绘时才退回列表头像
            val portraitUrl = character?.let(::characterPortraitUrl)
            when {
                // 用位图 logo 而不是 ic_launcher：后者是自适应图标 XML，
                // Compose 的 painterResource 无法把它转成位图（会崩）。
                character == null -> Image(
                    painter = painterResource(R.mipmap.neko),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                portraitUrl != null -> FramedPortraitImage(
                    url = portraitUrl,
                    framing = framing,
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

/**
 * 欢迎形象选择抽屉：应用图标 + 内容库里的角色立绘。
 *
 * 聚合模式下这张抽屉同时承担「继续哪段对话」的来源：多一行「最新会话」，
 * 选角色也会把首页 banner 固定到该角色（与立绘同步）。
 */
@Composable
private fun WelcomePortraitSheet(
    characters: List<CharacterPreset>,
    selectedCharacterId: String,
    showFollowLatest: Boolean,
    followLatestSelected: Boolean,
    onFollowLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = stringResource(R.string.shell_welcome_picker_title),
            description = stringResource(
                if (showFollowLatest) {
                    R.string.shell_welcome_picker_desc_aggregate
                } else {
                    R.string.shell_welcome_picker_desc
                }
            ),
            onClose = onDismiss
        )
        if (showFollowLatest) {
            NekoChoiceRow(
                title = stringResource(R.string.shell_banner_source_latest),
                subtitle = stringResource(R.string.shell_banner_source_latest_desc),
                onClick = onFollowLatest,
                leading = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                },
                trailing = { NekoCheckMark(selected = followLatestSelected) },
                showChevron = false,
                showDivider = true
            )
        }
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
// 取景参数与渲染统一收敛到 ui/components/PortraitFraming.kt：
// 首页 banner 与角色聊天模式卡共用同一份"按角色"的取景，逻辑保持一致。
