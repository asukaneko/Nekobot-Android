package com.nekobot.app.ui.screens.more

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.settings.SettingsHomeSwitchRow

internal enum class MoreCategory(
    val route: String,
    @get:StringRes val title: Int,
    @get:StringRes val summary: Int,
    val icon: ImageVector
) {
    MEMORY(Routes.MORE_MEMORY, R.string.more_category_memory, R.string.more_category_memory_summary, Icons.Outlined.Psychology),
    APPEARANCE(Routes.MORE_APPEARANCE, R.string.more_category_appearance, R.string.more_category_appearance_summary, Icons.Outlined.Palette),
    AI(Routes.MORE_AI, R.string.more_category_ai, R.string.more_category_ai_summary, Icons.Outlined.AutoAwesome),
    APP(Routes.SETTINGS, R.string.more_category_app, R.string.more_category_app_summary, Icons.Outlined.Settings),
    MAINTENANCE(Routes.MORE_MAINTENANCE, R.string.more_category_maintenance, R.string.more_category_maintenance_summary, Icons.Outlined.Build)
}

@Composable
internal fun MoreCategoryScreen(category: MoreCategory, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val mode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    var optimize by remember { mutableStateOf(ServiceContainer.prefs.messageImagePromptOptimizeEnabled) }
    MoreCategoryContent(
        category = category,
        appMode = mode,
        onNavigate = rememberMoreNavigateOnce(onNavigate),
        onBack = onBack,
        imagePromptOptimizeEnabled = optimize,
        onImagePromptOptimizeChange = {
            optimize = it
            ServiceContainer.prefs.messageImagePromptOptimizeEnabled = it
        }
    )
}

/** A directory only: existing destinations retain their state, confirmations and mode checks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreCategoryContent(
    category: MoreCategory,
    appMode: AppMode,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    imagePromptOptimizeEnabled: Boolean = false,
    onImagePromptOptimizeChange: (Boolean) -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("more-category-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).testTag("more-category-scroll")
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when (category) {
                MoreCategory.MEMORY -> MoreHomeGroup {
                    MoreHomeRow(Icons.Outlined.Psychology, stringResource(R.string.more_role_memory), Routes.MEMORY, onNavigate,
                        stringResource(R.string.more_home_role_memory_scope))
                    if (appMode == AppMode.LOCAL) {
                        MoreHomeDivider()
                        MoreHomeRow(Icons.Outlined.Book, stringResource(R.string.agent_character_memory_title), Routes.AGENT_CHARACTER_MEMORY, onNavigate,
                            stringResource(R.string.more_home_session_memory_scope))
                    }
                    MoreHomeDivider()
                    MoreHomeRow(Icons.Outlined.Bookmarks, stringResource(R.string.more_global_agent_memory), Routes.AGENT_MEMORY, onNavigate,
                        stringResource(R.string.more_home_global_memory_scope))
                    MoreHomeDivider()
                    MoreHomeRow(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.more_state_history), Routes.STATE_HISTORY, onNavigate)
                }
                MoreCategory.APPEARANCE -> MoreHomeGroup {
                    MoreHomeRow(Icons.Outlined.Palette, stringResource(R.string.more_style_settings), Routes.STYLE_SETTINGS, onNavigate)
                    MoreHomeDivider()
                    MoreHomeRow(Icons.Outlined.Landscape, stringResource(R.string.single_role_relationship_page_skins), Routes.RELATIONSHIP_BACKGROUND, onNavigate)
                    MoreHomeDivider()
                    MoreHomeRow(Icons.Outlined.EmojiEmotions, stringResource(R.string.more_stickers), Routes.STICKERS, onNavigate)
                }
                MoreCategory.AI -> {
                    MoreHomeGroup(stringResource(R.string.more_ai_configuration)) {
                        MoreHomeRow(Icons.Outlined.AutoAwesome, stringResource(R.string.more_ai_config_center), Routes.AI_CONFIG_CENTER, onNavigate,
                            stringResource(if (appMode == AppMode.LOCAL) R.string.more_ai_config_desc_local else R.string.more_ai_config_desc_server))
                        MoreHomeDivider()
                        MoreHomeRow(Icons.Outlined.Psychology, stringResource(R.string.agent_settings_title), Routes.AGENT_SETTINGS, onNavigate,
                            stringResource(R.string.settings_home_agent_summary))
                        if (appMode == AppMode.LOCAL) {
                            MoreHomeDivider()
                            SettingsHomeSwitchRow(
                                icon = Icons.Outlined.AutoAwesome,
                                title = stringResource(R.string.settings_message_image_prompt_optimize),
                                subtitle = stringResource(R.string.settings_home_image_prompt_summary),
                                checked = imagePromptOptimizeEnabled,
                                tag = "more-ai-image-prompt",
                                onCheckedChange = onImagePromptOptimizeChange
                            )
                        }
                    }
                    MoreHomeGroup {
                        MoreHomeRow(Icons.Outlined.Extension, stringResource(R.string.more_extensions), Routes.EXTENSIONS, onNavigate,
                            stringResource(R.string.more_extensions_summary))
                    }
                }
                MoreCategory.MAINTENANCE -> {
                    MoreHomeGroup(stringResource(R.string.settings_home_data_backup)) {
                        MoreHomeRow(Icons.Outlined.ImportExport, stringResource(R.string.settings_data_portability), Routes.DATA_PORTABILITY, onNavigate,
                            stringResource(R.string.settings_home_portability_summary))
                        MoreHomeDivider()
                        MoreHomeRow(Icons.Outlined.CloudUpload, stringResource(R.string.settings_webdav_backup), Routes.WEBDAV_BACKUP, onNavigate,
                            stringResource(R.string.settings_webdav_backup_desc))
                        if (appMode != AppMode.LOCAL) {
                            MoreHomeDivider()
                            MoreHomeRow(Icons.Outlined.ImportExport, stringResource(R.string.settings_config_transfer), Routes.CONFIG_TRANSFER, onNavigate,
                                stringResource(R.string.settings_config_transfer_desc))
                        }
                    }
                    MoreHomeGroup(stringResource(R.string.more_maintenance_diagnostics)) {
                        MoreHomeRow(Icons.Outlined.Description,
                            stringResource(if (appMode == AppMode.LOCAL) R.string.settings_local_logs else R.string.settings_logs_view), Routes.LOGS, onNavigate)
                        MoreHomeDivider()
                        MoreHomeRow(Icons.Outlined.BugReport, stringResource(R.string.diagnostic_center_title), Routes.DIAGNOSTIC_CENTER, onNavigate,
                            stringResource(R.string.settings_home_diagnostics_summary))
                    }
                    MoreHomeGroup(stringResource(R.string.more_maintenance_advanced)) {
                        MoreHomeRow(Icons.Outlined.Build, stringResource(R.string.settings_data_maintenance), Routes.DATA_MAINTENANCE, onNavigate,
                            stringResource(R.string.settings_data_maintenance_desc))
                        if (appMode == AppMode.LOCAL) {
                            MoreHomeDivider()
                            MoreHomeRow(Icons.Outlined.Storage, stringResource(R.string.more_db_management), Routes.DB_PROFILE, onNavigate,
                                stringResource(R.string.more_database_summary))
                        } else {
                            MoreHomeDivider()
                            MoreHomeRow(Icons.Outlined.Settings, stringResource(R.string.settings_system_settings), Routes.SYSTEM_SETTINGS, onNavigate,
                                stringResource(R.string.more_system_json_summary))
                        }
                    }
                }
                // Application preferences use SettingsScreen directly, with no extra index in between.
                MoreCategory.APP -> Unit
            }
        }
    }
}
