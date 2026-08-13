package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.LoginToken
import com.nekobot.app.data.model.LoginTokenRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录令牌 ViewModel：管理令牌列表的加载、创建与撤销。
 */
class LoginTokensViewModel : BaseViewModel() {

    private val _tokens = MutableStateFlow<List<LoginToken>>(emptyList())
    val tokens: StateFlow<List<LoginToken>> = _tokens.asStateFlow()

    /** 创建后返回的明文令牌，仅显示一次。 */
    private val _createdToken = MutableStateFlow<String?>(null)
    val createdToken: StateFlow<String?> = _createdToken.asStateFlow()

    init {
        load()
    }

    /** 加载令牌列表 */
    fun load() {
        launchResult(
            block = { unified.listLoginTokens() },
            onSuccess = { _tokens.value = it ?: emptyList() }
        )
    }

    /** 创建令牌，成功后保存明文令牌（仅显示一次） */
    fun create(username: String, expiresDays: Int) {
        val req = LoginTokenRequest(username = username, expiresDays = expiresDays)
        launchResult(
            block = { unified.createLoginToken(req) },
            onSuccess = { res ->
                _createdToken.value = res.token
                load()
            }
        )
    }

    /** 删除指定令牌 */
    fun delete(tokenHash: String) {
        launchResult(
            block = { unified.deleteLoginToken(tokenHash) },
            onSuccess = { load() }
        )
    }

    /** 撤销全部令牌 */
    fun deleteAll() {
        launchResult(
            block = { unified.deleteAllLoginTokens() },
            onSuccess = {
                showToast(string(R.string.logintokens_revoked_all))
                load()
            }
        )
    }

    /** 清除已显示的明文令牌 */
    fun clearCreatedToken() {
        _createdToken.value = null
    }
}

/**
 * 登录令牌页：列出所有访问令牌，支持新建、单条删除与撤销全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginTokensScreen(onBack: () -> Unit) {
    val vm: LoginTokensViewModel = viewModel()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val createdToken by vm.createdToken.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteAllConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LoginToken?>(null) }

    // 模式切换时自动刷新
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(appMode) { vm.load() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logintokens_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.logintokens_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { deleteAllConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.logintokens_revoke_all), tint = ErrorRed)
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.logintokens_new_token), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tokens.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(R.string.logintokens_empty_title),
                    hint = stringResource(R.string.logintokens_empty_hint)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                vm.clearError()
                                vm.load()
                            })
                        }
                    }
                    items(tokens, key = { it.tokenHash }) { token ->
                        LoginTokenItem(
                            token = token,
                            onDelete = { deleteTarget = token }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading && tokens.isEmpty())
        }
    }

    // 新建令牌弹窗
    if (showCreateDialog) {
        CreateLoginTokenDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { username, days ->
                vm.create(username, days)
                showCreateDialog = false
            }
        )
    }

    // 撤销全部确认弹窗
    if (deleteAllConfirm) {
        NekoDialog(
            onDismiss = { deleteAllConfirm = false },
            title = stringResource(R.string.logintokens_revoke_all_title),
            message = stringResource(R.string.logintokens_revoke_all_confirm_msg),
            confirmText = stringResource(R.string.logintokens_revoke_all),
            onConfirm = {
                vm.deleteAll()
                deleteAllConfirm = false
            }
        )
    }

    // 删除单个确认弹窗
    deleteTarget?.let { token ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.logintokens_confirm_delete_title),
            message = stringResource(R.string.logintokens_delete_confirm_msg, token.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                vm.delete(token.tokenHash)
                deleteTarget = null
            }
        )
    }

    // 创建成功后显示明文令牌（仅显示一次）
    createdToken?.let { token ->
        NekoDialog(
            onDismiss = { vm.clearCreatedToken() },
            title = stringResource(R.string.logintokens_token_created_title),
            confirmText = stringResource(R.string.logintokens_saved),
            cancelText = null,
            onCancel = null,
            onConfirm = { vm.clearCreatedToken() },
            content = {
                Text(
                    stringResource(R.string.logintokens_warning_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarningAmber
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = token,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(token))
                            Toast.makeText(context, context.getString(R.string.logintokens_token_copied), Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.logintokens_copy_token), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        )
    }
}

/** 单个令牌卡片：用户名 + 前缀 + 创建/过期时间 + IP + 删除按钮 */
@Composable
private fun LoginTokenItem(token: LoginToken, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.username,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!token.tokenPrefix.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.logintokens_prefix_label, token.tokenPrefix),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 后端返回 ISO 时间戳（如 "2026-08-11T10:30:00.123456"），过长会导致 Row 内
                // 第二个 Text 被竖排（一字一行）。这里先截到 yyyy-MM-dd HH:mm 缩短字符串，
                // 并用 maxLines=1 + Ellipsis 兜底防止溢出。
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!token.createdAt.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.logintokens_created_label, formatIsoDateTime(token.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!token.expiresAt.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.logintokens_expires_label, formatIsoDateTime(token.expiresAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (!token.ipAddress.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.logintokens_ip_label, token.ipAddress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = ErrorRed)
            }
        }
    }
}

/**
 * 把后端返回的 ISO 时间字符串（如 "2026-08-11T10:30:00.123456"）截短为
 * "yyyy-MM-dd HH:mm"。解析失败时回退到原字符串前 16 个字符，再加 "…" 防溢出。
 */
private fun formatIsoDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val raw = iso.trim()
    // 匹配 "yyyy-MM-ddTHH:mm" 形式（前 16 个字符）
    return if (raw.length >= 16 && raw[4] == '-' && raw[7] == '-' && (raw[10] == 'T' || raw[10] == ' ')) {
        raw.substring(0, 16)
    } else if (raw.length > 16) {
        raw.substring(0, 16) + "…"
    } else {
        raw
    }
}

/** 新建令牌弹窗：用户名 + 有效期下拉（1/7/30/90/365 天） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateLoginTokenDialog(
    onDismiss: () -> Unit,
    onConfirm: (username: String, expiresDays: Int) -> Unit
) {
    var username by remember { mutableStateOf("") }
    val dayOptions = listOf(1, 7, 30, 90, 365)
    var selectedDay by remember { mutableStateOf(30) }
    var dayExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.logintokens_new_token_title),
        confirmText = stringResource(R.string.common_create),
        onConfirm = {
            if (username.isBlank()) return@NekoDialog
            onConfirm(username.trim(), selectedDay)
        }
    ) {
        Text(stringResource(R.string.logintokens_username), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.logintokens_username_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.logintokens_validity), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = dayExpanded,
            onExpandedChange = { dayExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            @Suppress("DEPRECATION")
            OutlinedTextField(
                value = stringResource(R.string.logintokens_days_count, selectedDay),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dayExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = dayExpanded,
                onDismissRequest = { dayExpanded = false }
            ) {
                dayOptions.forEach { days ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logintokens_days_count, days)) },
                        onClick = {
                            selectedDay = days
                            dayExpanded = false
                        }
                    )
                }
            }
        }
    }
}
