package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField

/** The existing mode/server controls, one level below the settings index. */
@Composable
internal fun SettingsConnectionContent(
    scrollState: ScrollState,
    appMode: AppMode,
    serverUrlInput: String,
    username: String,
    loading: Boolean,
    onModeChange: (AppMode) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onSaveServerUrl: () -> Unit,
    onReloadConfig: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    errorContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxSize()
            .testTag("settings-connection-scroll")
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        errorContent()
        SettingsHomeGroup(stringResource(R.string.settings_run_mode)) {
            SettingsHomeSwitchRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_local_mode),
                subtitle = stringResource(
                    if (appMode == AppMode.LOCAL) R.string.settings_local_mode_desc
                    else R.string.settings_server_mode_desc
                ),
                checked = appMode == AppMode.LOCAL,
                enabled = !loading,
                tag = "settings-connection-mode",
                onCheckedChange = { onModeChange(if (it) AppMode.LOCAL else AppMode.SERVER) }
            )
        }

        if (appMode != AppMode.LOCAL) {
            SettingsHomeGroup(stringResource(R.string.settings_server_config)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = onServerUrlChange,
                        label = { Text(stringResource(R.string.settings_server_address)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings-connection-server-url")
                    )
                    Text(
                        text = stringResource(R.string.server_https_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (serverUrlInput.trimStart().startsWith("http://", ignoreCase = true)) {
                        Text(
                            text = stringResource(R.string.server_http_insecure_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = onSaveServerUrl,
                        enabled = !loading,
                        modifier = Modifier.testTag("settings-connection-save-url")
                    ) {
                        Text(stringResource(R.string.settings_save_address))
                    }
                }
            }

            SettingsHomeGroup(stringResource(R.string.settings_system_actions)) {
                TextButton(
                    onClick = onReloadConfig,
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("settings-connection-reload")
                ) {
                    Icon(Icons.Outlined.CloudSync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_reload_config))
                }
            }

            SettingsHomeGroup(stringResource(R.string.settings_account)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_current_user,
                            username.ifBlank { stringResource(R.string.common_not_logged_in) }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onLogout,
                        enabled = !loading,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("settings-connection-logout")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_logout))
                    }
                }
            }
        }
    }
}
