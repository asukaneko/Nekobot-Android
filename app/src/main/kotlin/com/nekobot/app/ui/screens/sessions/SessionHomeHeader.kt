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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.NekoSheetNote
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoWelcomeTitleStyle
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
    /** 未过滤、按更新时间倒序的会话行，供 banner 与「固定会话」选择使用。 */
    homeRows: List<SessionListRow>,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenFilter: () -> Unit,
    onSelectFilter: (SessionFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = ServiceContainer.prefs
    // prefs 不是可观察状态：这里另存一份局部状态，选择后 banner 立刻刷新
    var bannerMode by remember { mutableStateOf(prefs.homeBannerMode) }
    var bannerSessionId by remember { mutableStateOf(prefs.homeBannerSessionId) }
    var showSourceSheet by remember { mutableStateOf(false) }
    val pinnedMode = bannerMode == PrefsManager.HOME_BANNER_PINNED
    val bannerRow = remember(homeRows, pinnedMode, bannerSessionId) {
        if (pinnedMode) {
            homeRows.firstOrNull { it.id == bannerSessionId } ?: homeRows.firstOrNull()
        } else {
            homeRows.firstOrNull()
        }
    }

    Column(modifier.fillMaxWidth()) {
        SessionHomeWelcome(characters = characters)
        // 问候区与 banner 之间用一条细线分隔：避免两块内容糊在一起
        NekoDivider()
        Spacer(Modifier.height(16.dp))
        if (bannerRow != null) {
            HomeSessionBanner(
                row = bannerRow,
                pinned = pinnedMode && bannerRow.id == bannerSessionId,
                onOpen = { bannerRow.id?.let(onOpenSession) },
                onChoose = { showSourceSheet = true }
            )
        } else {
            // 一条会话都还没有：banner 位置仍给一个明确的主要行动
            NekoPrimaryAction(
                text = stringResource(R.string.shell_new_chat),
                leadingIcon = Icons.Filled.Edit,
                onClick = onNewChat
            )
        }
        // 分段控件覆盖三类会话；其它筛选（收藏 / 归档 / 频道 / 按角色）由「筛选」抽屉承担。
        // 抽屉里选中非分段项时，右侧直接显示当前筛选项，避免出现"什么都不像选中"的困惑。
        val filterLabel = if (filter in sessionSegmentFilters) {
            stringResource(R.string.shell_filter)
        } else {
            stringResource(filter.labelResId)
        }
        NekoSectionHeader(
            title = stringResource(R.string.shell_your_chats),
            meta = stringResource(R.string.shell_chat_count, totalCount),
            actionText = filterLabel,
            onAction = onOpenFilter,
            actionLeading = {
                // 归档会话快速筛选：点一下只看归档，再点回到全部
                val archivedActive = filter == SessionFilter.ARCHIVED
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onSelectFilter(
                                if (archivedActive) SessionFilter.ALL else SessionFilter.ARCHIVED
                            )
                        }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 6.dp),
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
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        )
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
            rows = homeRows.filter { it.modeLabel == SessionModeLabel.COMPANION },
            pinnedMode = pinnedMode,
            pinnedSessionId = bannerSessionId,
            onDismiss = { showSourceSheet = false },
            onSelectLatest = {
                bannerMode = PrefsManager.HOME_BANNER_LATEST
                prefs.homeBannerMode = PrefsManager.HOME_BANNER_LATEST
                showSourceSheet = false
            },
            onSelectSession = { id ->
                bannerMode = PrefsManager.HOME_BANNER_PINNED
                bannerSessionId = id
                prefs.homeBannerMode = PrefsManager.HOME_BANNER_PINNED
                prefs.homeBannerSessionId = id
                showSourceSheet = false
            }
        )
    }
}

/** 分段筛选各项与筛选枚举的对应关系（下标与控件顺序一致）。 */
val sessionSegmentFilters = listOf(
    SessionFilter.ALL,
    SessionFilter.CHARACTER_SESSIONS,
    SessionFilter.AGENT_SESSIONS,
    SessionFilter.AGENT_CHARACTERS
)

// ---------------------------------------------------------------- 会话 banner

/**
 * 会话 banner：把"继续上次那段对话"做成页面上最显眼的一行。
 *
 * 默认跟随最近聊过的会话；也可以在右侧「切换」里固定某个单角色会话，
 * 固定后 banner 不再随新消息跳动。
 */
@Composable
private fun HomeSessionBanner(
    row: SessionListRow,
    pinned: Boolean,
    onOpen: () -> Unit,
    onChoose: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
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
        BannerAvatar(row = row, size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
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
                    text = row.listTitle,
                    style = nekoRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.lastMessage ?: stringResource(R.string.shell_banner_continue),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
private fun BannerAvatar(row: SessionListRow, size: Dp) {
    val context = LocalContext.current
    val modeColor = row.modeLabel.color()
    val request = remember(context, row.portraitUrl) {
        row.portraitUrl?.let {
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
            row.modeLabel == SessionModeLabel.COMPANION -> Text(
                text = row.listTitle.takeIf(String::isNotBlank)
                    ?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
                    ?: stringResource(R.string.single_role_c),
                style = nekoRowTitleStyle(),
                color = modeColor
            )
            else -> Icon(
                painter = rememberVectorPainter(
                    if (row.isGroupSession) Icons.Outlined.Group else Icons.AutoMirrored.Outlined.Chat
                ),
                contentDescription = null,
                tint = modeColor,
                modifier = Modifier.size(size * 0.52f)
            )
        }
    }
}

/** banner 来源选择：最新会话 / 固定一个单角色会话。 */
@Composable
private fun BannerSourceSheet(
    rows: List<SessionListRow>,
    pinnedMode: Boolean,
    pinnedSessionId: String,
    onDismiss: () -> Unit,
    onSelectLatest: () -> Unit,
    onSelectSession: (String) -> Unit
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
            showDivider = rows.isNotEmpty()
        )
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_banner_no_companion),
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(rows, key = { it.key }) { row ->
                    val id = row.id.orEmpty()
                    NekoChoiceRow(
                        title = row.listTitle,
                        subtitle = row.lastMessage,
                        onClick = { if (id.isNotEmpty()) onSelectSession(id) },
                        leading = { BannerAvatar(row = row, size = 38.dp) },
                        trailing = {
                            NekoCheckMark(selected = pinnedMode && id == pinnedSessionId)
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

// ---------------------------------------------------------------- 问候区

/**
 * 问候区：大一些的欢迎立绘 + 一句问候。
 *
 * 问候文案先显示本地候选池（立即可见、避免重复），随后由 AI 按当前欢迎角色的
 * 角色卡口吻重写一句；结果按「角色 + 时段」缓存半小时，失败则保留本地文案。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionHomeWelcome(characters: List<CharacterPreset>) {
    val prefs = ServiceContainer.prefs
    val characterId = prefs.homeWelcomeCharacterId
    val character = remember(characterId, characters) {
        characters.firstOrNull { it.id == characterId }
    }
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
    // 点击问候语：重新生成一次；长按：看完整内容
    var regenerateKey by remember { mutableIntStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var showFullGreeting by remember { mutableStateOf(false) }
    val crop = remember(characterId, prefs.homePortraitCrop) {
        parsePortraitCrop(prefs.homePortraitCrop, characterId)
    }

    LaunchedEffect(period.key, characterId, character, localeTag, regenerateKey) {
        // 角色列表还没加载完（有 id 但解析不出角色卡）时先不生成：
        // 否则会用默认 Neko 口吻生成并缓存到该角色名下，30 分钟内都不会纠正。
        if (characterId.isNotBlank() && character == null) return@LaunchedEffect
        // regenerateKey > 0 表示用户主动点了问候语：跳过缓存强制重新生成
        val force = regenerateKey > 0
        // 先取角色记忆（单角色会话的融合记忆 + 普通角色会话的记忆）：
        // 既作为提示词素材，也参与缓存键，记忆更新后问候会自动重写。
        val memoryBrief = if (characterId.isBlank()) "" else runCatching {
            ServiceContainer.unified.homeGreetingMemoryBrief(characterId)
        }.getOrDefault("")
        val cacheKey = "$characterId|${period.key}|$localeTag|${memoryBrief.hashCode()}"
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
            ServiceContainer.unified.generateHomeGreeting(character, period.labelForPrompt, memoryBrief)
        }.getOrNull()
        generating = false
        val generated = (result as? Resource.Success<String>)?.data?.trim()
        if (!generated.isNullOrBlank()) {
            line = generated
            prefs.homeGreetingAiLine = generated
            prefs.homeGreetingAiKey = "$cacheKey|${System.currentTimeMillis()}"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WelcomePortrait(character = character, crop = crop, onClick = { showPicker = true })
        Spacer(Modifier.width(15.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { regenerateKey++ },
                    onLongClick = { showFullGreeting = true }
                )
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
                    style = nekoCaptionStyle(),
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

    if (showFullGreeting) {
        NekoDialog(
            onDismiss = { showFullGreeting = false },
            title = stringResource(R.string.shell_greeting_full_title),
            message = line,
            confirmText = stringResource(R.string.shell_greeting_regenerate),
            onConfirm = {
                showFullGreeting = false
                regenerateKey++
            },
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
                    // 应用图标不需要取景
                    prefs.homeWelcomeCharacterId = ""
                    prefs.homePortraitCrop = ""
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
                prefs.homeWelcomeCharacterId = target.id.orEmpty()
                prefs.homePortraitCrop = serialisePortraitCrop(target.id.orEmpty(), confirmed)
                croppingCharacter = null
            }
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
