package com.nekobot.app.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoDetailTopBar
import com.nekobot.app.ui.components.NekoEmpty
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoSectionLabelStyle
import com.nekobot.app.ui.components.nekoTextSize
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.screens.sessions.SessionListRow

/**
 * 角色聊天模式首页：先看角色，再看这个角色有哪几段对话。
 *
 * 点角色卡 → 抽屉里列该角色的会话 + 新建；没有会话的角色点开就是新建入口。
 */
@Composable
internal fun CharacterModeScreen(
    sessions: List<SessionListRow>,
    characters: List<CharacterPreset>,
    creating: Boolean,
    error: String?,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCreateSession: (String, String, (String) -> Unit) -> Unit
) {
    var sheetCharacter by remember { mutableStateOf<CharacterPreset?>(null) }

    val sessionsByCharacter = remember(sessions) {
        sessions
            .filter { !it.characterId.isNullOrBlank() }
            .groupBy { it.characterId.orEmpty() }
    }
    val ordered = remember(characters, sessionsByCharacter) {
        characters
            .filter { !it.id.isNullOrBlank() }
            .distinctBy { it.id }
            .sortedByDescending { character ->
                sessionsByCharacter[character.id]?.firstOrNull()?.updatedAt.orEmpty()
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NekoDetailTopBar(
                title = stringResource(R.string.home_character_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ordered.isEmpty()) {
                NekoEmpty(
                    title = stringResource(R.string.home_character_empty),
                    hint = stringResource(R.string.home_character_empty_hint),
                    icon = Icons.Outlined.PersonOutline
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.home_character_pick),
                                style = nekoSectionLabelStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.home_character_count, ordered.size),
                                style = nekoCaptionStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(ordered, key = { it.id.orEmpty() }) { character ->
                        val characterSessions = sessionsByCharacter[character.id].orEmpty()
                        CharacterGridCard(
                            character = character,
                            sessionCount = characterSessions.size,
                            lastMessage = characterSessions.firstOrNull()?.lastMessage,
                            onClick = { sheetCharacter = character }
                        )
                    }
                }
            }
            ErrorToast(message = error, onShown = onErrorShown)
        }
    }

    val selected = sheetCharacter
    if (selected != null) {
        val characterSessions = sessionsByCharacter[selected.id].orEmpty()
        NekoSheet(onDismissRequest = { sheetCharacter = null }) {
            NekoSheetHeader(
                title = selected.displayName,
                description = stringResource(R.string.home_character_sheet_desc),
                onClose = { sheetCharacter = null }
            )
            NekoChoiceRow(
                title = stringResource(R.string.home_character_new),
                subtitle = stringResource(
                    if (creating) R.string.home_character_new_creating else R.string.home_character_new_desc
                ),
                onClick = {
                    val id = selected.id.orEmpty()
                    if (id.isBlank() || creating) return@NekoChoiceRow
                    onCreateSession(id, selected.displayName) { sessionId ->
                        sheetCharacter = null
                        onOpenChat(sessionId)
                    }
                },
                leading = {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                showChevron = false,
                showDivider = characterSessions.isNotEmpty()
            )
            if (characterSessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_character_no_session),
                    style = nekoRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 2.dp)
                )
            } else {
                ModeSectionLabel(stringResource(R.string.home_character_sessions))
                characterSessions.forEach { row ->
                    val id = row.id ?: return@forEach
                    ModeSessionRow(row = row, onClick = {
                        sheetCharacter = null
                        onOpenChat(id)
                    })
                }
            }
        }
    }
}

/** 网格里的角色卡：立绘铺满上方，名字压在图上。 */
@Composable
private fun CharacterGridCard(
    character: CharacterPreset,
    sessionCount: Int,
    lastMessage: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val portrait = remember(character.portrait, character.avatar) {
        resolveAvatarUrl(character.portrait ?: character.avatar)?.let { url ->
            ImageRequest.Builder(context).data(url).crossfade(true).build()
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
        ) {
            if (portrait != null) {
                AsyncImage(
                    model = portrait,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f)
                            )
                        )
                )
                Text(
                    text = character.displayName,
                    style = nekoRowTitleStyle().copy(
                        fontSize = nekoTextSize(14f),
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else {
                Text(
                    text = character.displayName,
                    style = nekoRowTitleStyle().copy(
                        fontSize = nekoTextSize(16f),
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 14.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = if (sessionCount > 0) {
                stringResource(R.string.home_character_session_count, sessionCount)
            } else {
                stringResource(R.string.home_character_no_session)
            },
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        val preview = lastMessage?.takeIf { it.isNotBlank() }
        if (preview != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = preview,
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
