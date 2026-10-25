package com.nekobot.app.ui.screens.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.NekoLetterAvatar
import com.nekobot.app.ui.components.NekoIconTile
import com.nekobot.app.ui.components.NekoChevron
import com.nekobot.app.data.model.userFacingTitle
import com.nekobot.app.data.model.Session
import coil.request.repeatCount
import coil.request.ImageRequest
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import com.nekobot.app.ui.navigation.Routes

@Composable
internal fun GlobalSearchInput(
    query: String, onQueryChange: (String) -> Unit, searching: Boolean,
    focusRequester: FocusRequester, onSearch: () -> Unit, modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    TextField(value = query, onValueChange = onQueryChange,
        modifier = modifier.focusRequester(focusRequester).onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused) colors.primary else colors.outline, shape),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true, shape = shape,
        placeholder = { Text(stringResource(R.string.global_search_placeholder),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searching) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(20.dp))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); onSearch() }))
}

@Composable
internal fun GlobalSearchCategories(selected: GlobalSearchKind?, onSelect: (GlobalSearchKind?) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(listOf<GlobalSearchKind?>(null) + GlobalSearchKind.entries, key = { it?.name ?: "all" }) { kind ->
            val active = kind == selected
            Surface(shape = RoundedCornerShape(12.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent) {
                Box(Modifier.selectable(selected = active, role = Role.Tab, onClick = { onSelect(kind) })
                    .heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(kind?.labelRes ?: R.string.search_filter_all),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}


/** 搜索结果行：放在分组卡里的一行（图标底座 + 标题 + 副标题 + 箭头）。 */
@Composable
internal fun GlobalSearchRow(
    result: GlobalSearchResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NekoIconTile(icon = result.searchIcon(), size = 33.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                highlightedSearchText(result.title, query, SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)),
                style = nekoRowTitleStyle(), color = colors.onSurface,
                maxLines = if (result.kind == GlobalSearchKind.MESSAGE) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    highlightedSearchText(result.subtitle, query, SpanStyle(color = colors.primary)),
                    style = nekoRowSubtitleStyle(), color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        NekoChevron()
    }
}

/** 最近会话行：头像 + 会话名 + 末条消息 + 更新时间。 */
@Composable
internal fun GlobalSearchSessionRow(
    session: Session,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val title = session.displayName
    val subtitle = listOfNotNull(
        if (session.companionMode == true) session.userFacingTitle() else session.characterName,
        session.lastMessage?.replace(Regex("\\s+"), " ")?.trim()
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val timeLabel = session.updatedAt?.let { if (it.length >= 10) it.substring(5, 10) else it }
    val portrait = remember(session.portraitUrl) {
        resolveAvatarUrl(session.portraitUrl)?.let {
            ImageRequest.Builder(context).data(it).crossfade(false).repeatCount(0).build()
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (portrait != null) {
            AsyncImage(
                model = portrait,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
            )
        } else {
            NekoLetterAvatar(text = title, size = 38.dp)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = nekoRowTitleStyle(),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = nekoRowSubtitleStyle(),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (timeLabel != null) {
            Spacer(Modifier.width(8.dp))
            Text(timeLabel, style = nekoCaptionStyle(), color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun GlobalSearchEmpty(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Text(stringResource(R.string.global_search_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun GlobalSearchResult.searchIcon() = if (kind == GlobalSearchKind.COMMAND) when (route) {
    Routes.SESSIONS -> Icons.Filled.Chat
    Routes.CHARACTERS -> Icons.Filled.Person
    Routes.WORLD_BOOKS -> Icons.Filled.MenuBook
    Routes.MEMORY -> Icons.Filled.Memory
    Routes.AI_CONFIG_CENTER -> Icons.Filled.Tune
    Routes.SYSTEM_OPERATIONS -> Icons.Filled.SettingsApplications
    Routes.DATA_PORTABILITY -> Icons.Filled.ImportExport
    Routes.WEBDAV_BACKUP -> Icons.Filled.CloudSync
    Routes.SKILLS -> Icons.Filled.Extension
    else -> Icons.Filled.Settings
} else when (kind) {
    GlobalSearchKind.SESSION, GlobalSearchKind.MESSAGE -> Icons.Filled.Chat
    GlobalSearchKind.CHARACTER -> Icons.Filled.Person
    GlobalSearchKind.WORLD_BOOK -> Icons.Filled.MenuBook
    GlobalSearchKind.MEMORY -> Icons.Filled.Memory
    GlobalSearchKind.WORKSPACE -> Icons.Filled.Folder
    GlobalSearchKind.COMMAND -> Icons.Filled.Settings
}
