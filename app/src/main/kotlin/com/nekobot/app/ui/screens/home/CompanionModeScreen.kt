package com.nekobot.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.NekoDetailTopBar
import com.nekobot.app.ui.components.NekoEmpty
import com.nekobot.app.ui.components.NekoPrimaryAction
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoSectionLabelStyle
import com.nekobot.app.ui.screens.sessions.SessionListRow

/**
 * 单角色模式首页：只有一条关系的会话，外加新建入口。
 */
@Composable
internal fun CompanionModeScreen(
    sessions: List<SessionListRow>,
    /** 单角色会话只在本地模式可新建；服务器模式隐藏入口。 */
    canCreate: Boolean,
    error: String?,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCreateCompanion: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NekoDetailTopBar(
                title = stringResource(R.string.home_companion_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "intro") {
                    Column {
                        Text(
                            text = stringResource(R.string.home_companion_hint),
                            style = nekoCaptionStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        if (canCreate) {
                            NekoPrimaryAction(
                                text = stringResource(R.string.home_companion_new),
                                leadingIcon = Icons.Filled.Add,
                                trailingChevron = false,
                                onClick = onCreateCompanion
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
                if (sessions.isEmpty()) {
                    item(key = "empty") {
                        NekoEmpty(
                            title = stringResource(R.string.home_companion_empty),
                            hint = stringResource(R.string.home_companion_empty_hint),
                            icon = Icons.Outlined.PersonOutline
                        )
                    }
                } else {
                    item(key = "label") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.home_companion_yours),
                                style = nekoSectionLabelStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.home_mode_card_count, sessions.size),
                                style = nekoCaptionStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(sessions, key = { it.key }) { row ->
                        val id = row.id ?: return@items
                        ModeSessionRow(
                            row = row,
                            tint = MaterialTheme.colorScheme.secondary,
                            onClick = { onOpenChat(id) }
                        )
                    }
                }
            }
            ErrorToast(message = error, onShown = onErrorShown)
        }
    }
}
