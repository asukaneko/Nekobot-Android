package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.data.local.AppMode

/** Settings navigation and preferences only. Connection details stay on the next page. */
@Composable
internal fun SettingsHomeContent(
    scrollState: ScrollState,
    appMode: AppMode,
    language: String,
    version: String,
    openLatestSessionOnLaunch: Boolean,
    recentSessionsIncludeArchived: Boolean,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    onOpenLatestSessionChange: (Boolean) -> Unit,
    onIncludeArchivedChange: (Boolean) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onLanguageClick: () -> Unit,
    onConnectionClick: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxSize()
            .testTag("settings-home-scroll")
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        errorContent()
        SettingsHomeGroup(stringResource(R.string.settings_home_common)) {
            SettingsHomeNavRow(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.settings_language),
                subtitle = language,
                tag = "settings-home-language",
                onClick = onLanguageClick
            )
            SettingsHomeDivider()
            SettingsHomeSwitchRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.settings_home_open_latest),
                checked = openLatestSessionOnLaunch,
                tag = "settings-home-open-latest",
                onCheckedChange = onOpenLatestSessionChange
            )
            SettingsHomeDivider()
            SettingsHomeSwitchRow(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.settings_home_include_archived),
                checked = recentSessionsIncludeArchived,
                tag = "settings-home-include-archived",
                onCheckedChange = onIncludeArchivedChange
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_privacy_experience)) {
            SettingsHomeSwitchRow(
                icon = Icons.Outlined.Fingerprint,
                title = stringResource(R.string.settings_app_lock),
                subtitle = stringResource(R.string.settings_home_lock_summary),
                checked = appLockEnabled,
                enabled = appLockAvailable,
                tag = "settings-home-app-lock",
                onCheckedChange = onAppLockChange
            )
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.more_system_operations),
                subtitle = stringResource(R.string.more_permissions_summary),
                tag = "settings-home-system_operations",
                onClick = { onNavigate(Routes.SYSTEM_OPERATIONS) }
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_advanced)) {
            if (appMode != AppMode.LOCAL) {
                SettingsHomeNavRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_feature_switches),
                    subtitle = stringResource(R.string.settings_feature_switches_desc),
                    tag = "settings-home-feature_switches",
                    onClick = { onNavigate("feature_switches") }
                )
                SettingsHomeDivider()
            }
            SettingsHomeNavRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_home_connection),
                subtitle = stringResource(
                    if (appMode == AppMode.LOCAL) R.string.settings_local_mode
                    else R.string.settings_server_mode
                ),
                tag = "settings-home-connection",
                onClick = onConnectionClick
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_support_about)) {
            SettingsHomeNavRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_about),
                subtitle = "v$version",
                tag = "settings-home-about",
                onClick = { onNavigate("about") }
            )
        }
    }
}

@Composable
internal fun SettingsHomeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsHomeDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
internal fun SettingsHomeNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsHomeRowLabel(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SettingsHomeSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsHomeRowLabel(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // The whole row is a single accessible switch and has one event handler.
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun SettingsHomeRowLabel(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
