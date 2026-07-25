package com.nekobot.app.ui.screens.aiconfig

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalOAuthAccountEntity
import com.nekobot.app.data.local.oauth.LocalOAuthProviders
import com.nekobot.app.data.local.oauth.OAuthLoginMode
import com.nekobot.app.data.local.oauth.OAuthLoginSession
import com.nekobot.app.data.local.oauth.OAuthPollResult
import com.nekobot.app.data.local.oauth.OAuthProviderSpec
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.ProviderLogo
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OAuthModelPickerState(
    val account: LocalOAuthAccountEntity,
    val models: List<String>,
    val selected: Set<String>
)

class OAuthAccountsViewModel : BaseViewModel() {
    private val manager = ServiceContainer.localRepository.oauthManager

    private val _accounts = MutableStateFlow<List<LocalOAuthAccountEntity>>(emptyList())
    val accounts: StateFlow<List<LocalOAuthAccountEntity>> = _accounts.asStateFlow()

    private val _loginSession = MutableStateFlow<OAuthLoginSession?>(null)
    val loginSession: StateFlow<OAuthLoginSession?> = _loginSession.asStateFlow()

    private val _modelPicker = MutableStateFlow<OAuthModelPickerState?>(null)
    val modelPicker: StateFlow<OAuthModelPickerState?> = _modelPicker.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            manager.observeAccounts().collect { _accounts.value = it }
        }
    }

    fun startLogin(provider: OAuthProviderSpec) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val session = manager.startLogin(provider.id)
                _loginSession.value = session
                if (provider.loginMode == OAuthLoginMode.DEVICE_CODE) {
                    startPolling(session)
                }
            } catch (error: Exception) {
                showError(error.message ?: "OAuth 登录启动失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun importApiKey(provider: OAuthProviderSpec, apiKey: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                handleResult(manager.importApiKey(provider.id, apiKey))
            } finally {
                setLoading(false)
            }
        }
    }

    fun importQwen(rawJson: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                handleResult(manager.importQwenCredentials(rawJson))
            } finally {
                setLoading(false)
            }
        }
    }

    fun submitAnthropicCode(code: String) {
        val session = _loginSession.value ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                handleResult(manager.submitAnthropicCode(session, code))
            } finally {
                setLoading(false)
            }
        }
    }

    fun editModels(account: LocalOAuthAccountEntity, refresh: Boolean = false) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val models = manager.availableModels(account.id, refresh)
                val selected = manager.selectedModels(account.id).toSet()
                _modelPicker.value = OAuthModelPickerState(account, models, selected)
            } catch (error: Exception) {
                showError(error.message ?: "读取模型列表失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun saveModels(selected: Set<String>) {
        val state = _modelPicker.value ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                manager.syncSelectedModels(state.account.id, selected)
                _modelPicker.value = null
                showToast("已同步 ${selected.size} 个模型到本地 AI 模型")
            } catch (error: Exception) {
                showError(error.message ?: "保存模型失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun deleteAccount(account: LocalOAuthAccountEntity) {
        viewModelScope.launch {
            setLoading(true)
            try {
                manager.deleteAccount(account.id)
                showToast("已删除 AI 账号及其模型")
            } catch (error: Exception) {
                showError(error.message ?: "删除账号失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun dismissLogin() {
        pollingJob?.cancel()
        pollingJob = null
        _loginSession.value = null
    }

    fun dismissModelPicker() {
        _modelPicker.value = null
    }

    private fun startPolling(session: OAuthLoginSession) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && _loginSession.value?.id == session.id) {
                delay(session.pollIntervalSeconds * 1000L)
                when (val result = manager.pollLogin(session)) {
                    OAuthPollResult.Pending -> Unit
                    else -> {
                        handleResult(result)
                        break
                    }
                }
            }
        }
    }

    private fun handleResult(result: OAuthPollResult) {
        when (result) {
            OAuthPollResult.Pending -> Unit
            is OAuthPollResult.Failed -> {
                pollingJob?.cancel()
                pollingJob = null
                _loginSession.value = null
                showError(result.message)
            }
            is OAuthPollResult.Connected -> {
                pollingJob?.cancel()
                pollingJob = null
                _loginSession.value = null
                _modelPicker.value = OAuthModelPickerState(
                    account = result.account,
                    models = result.models,
                    selected = emptySet()
                )
                showToast("账号已连接，请选择要使用的模型")
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuthAccountsScreen(onBack: () -> Unit) {
    val vm: OAuthAccountsViewModel = viewModel()
    val accounts by vm.accounts.collectAsState()
    val loginSession by vm.loginSession.collectAsState()
    val modelPicker by vm.modelPicker.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<LocalOAuthAccountEntity?>(null) }
    var openedSessionId by remember { mutableStateOf<String?>(null) }
    var apiKeyProvider by remember { mutableStateOf<OAuthProviderSpec?>(null) }

    val qwenFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("无法读取所选文件")
            }.onSuccess(vm::importQwen)
                .onFailure { vm.showError(it.message ?: "读取 Qwen 凭据失败") }
        }
    }

    LaunchedEffect(loginSession?.id) {
        val session = loginSession ?: return@LaunchedEffect
        if (openedSessionId != session.id) {
            openedSessionId = session.id
            openBrowser(context, session.verificationUrl)
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aiconfig_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(title = "登录提供商")
                Text(
                    text = "通过 OAuth 或 API Key 连接账号，随后选择要自动加入“本地 AI 模型”的模型，之后可随时修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(LocalOAuthProviders.all, key = OAuthProviderSpec::id) { provider ->
                val connectedCount = accounts.count { it.provider == provider.id }
                ProviderCard(
                    provider = provider,
                    connectedCount = connectedCount,
                    onLogin = {
                        when (provider.loginMode) {
                            OAuthLoginMode.QWEN_CREDENTIAL_IMPORT ->
                                qwenFilePicker.launch(arrayOf("application/json", "text/plain"))
                            OAuthLoginMode.API_KEY -> apiKeyProvider = provider
                            else -> vm.startLogin(provider)
                        }
                    }
                )
            }
            if (accounts.isNotEmpty()) {
                item { SectionHeader(title = "已登录账号") }
                items(accounts, key = LocalOAuthAccountEntity::id) { account ->
                    ConnectedAccountCard(
                        account = account,
                        onEditModels = { vm.editModels(account) },
                        onDelete = { deleteTarget = account }
                    )
                }
            }
            item { Spacer(Modifier.padding(bottom = 12.dp)) }
        }
        LoadingOverlay(visible = loading)
    }

    loginSession?.let { session ->
        LoginDialog(
            session = session,
            onDismiss = vm::dismissLogin,
            onSubmitAnthropic = vm::submitAnthropicCode,
            onOpenBrowser = { openBrowser(context, session.verificationUrl) },
            onCopyCode = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OAuth code", session.userCode))
                Toast.makeText(context, "设备码已复制", Toast.LENGTH_SHORT).show()
            }
        )
    }

    apiKeyProvider?.let { provider ->
        ApiKeyDialog(
            provider = provider,
            onDismiss = { apiKeyProvider = null },
            onOpenConsole = { openBrowser(context, "https://opencode.ai/zen") },
            onSubmit = { apiKey ->
                apiKeyProvider = null
                vm.importApiKey(provider, apiKey)
            }
        )
    }

    modelPicker?.let { state ->
        ModelPickerDialog(
            state = state,
            onDismiss = vm::dismissModelPicker,
            onSave = vm::saveModels,
            onRefresh = { vm.editModels(state.account, refresh = true) }
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("操作失败") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("确定") } }
        )
    }

    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 AI 账号？") },
            text = { Text("将同时删除由“${account.label}”添加的本地 AI 模型。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccount(account)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: OAuthProviderSpec,
    connectedCount: Int,
    onLogin: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(
                provider = provider.id,
                baseUrl = provider.baseUrl,
                model = provider.models.firstOrNull(),
                size = 42.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (connectedCount > 0) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                        )
                    }
                }
                Text(
                    provider.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onLogin) {
                Text(
                    when (provider.loginMode) {
                        OAuthLoginMode.QWEN_CREDENTIAL_IMPORT -> "导入"
                        OAuthLoginMode.API_KEY -> "输入 Key"
                        else -> "登录"
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectedAccountCard(
    account: LocalOAuthAccountEntity,
    onEditModels: () -> Unit,
    onDelete: () -> Unit
) {
    val provider = LocalOAuthProviders.get(account.provider)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(
                provider = account.provider,
                baseUrl = provider?.baseUrl,
                model = provider?.models?.firstOrNull(),
                size = 38.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (account.status == "connected") "已连接" else "需要重新登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account.status == "connected") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            IconButton(onClick = onEditModels) {
                Icon(Icons.Filled.Edit, contentDescription = "修改模型")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除账号",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(
    provider: OAuthProviderSpec,
    onDismiss: () -> Unit,
    onOpenConsole: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var apiKey by remember(provider.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = { Text("连接 ${provider.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在 OpenCode 控制台创建对应方案的 API Key，然后粘贴到下方。Key 会加密保存在本机。")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("${provider.title} API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onOpenConsole) { Text("打开 OpenCode Zen") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(apiKey.trim()) },
                enabled = apiKey.isNotBlank()
            ) { Text("连接并读取模型") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun LoginDialog(
    session: OAuthLoginSession,
    onDismiss: () -> Unit,
    onSubmitAnthropic: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onCopyCode: () -> Unit
) {
    var code by remember(session.id) { mutableStateOf("") }
    val isAnthropic = session.provider == LocalOAuthProviders.ANTHROPIC
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = { Text(if (isAnthropic) "完成 Anthropic 授权" else "在浏览器中完成登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isAnthropic) {
                    Text("浏览器授权后，将页面显示的授权码粘贴到下方。")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("授权码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("请在已打开的浏览器页面输入设备码，应用会自动等待登录结果。")
                    if (session.userCode.isNotBlank()) {
                        Text(
                            session.userCode,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onCopyCode)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("等待授权…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onOpenBrowser) { Text("重新打开浏览器") }
            }
        },
        confirmButton = {
            if (isAnthropic) {
                TextButton(
                    onClick = { onSubmitAnthropic(code) },
                    enabled = code.isNotBlank()
                ) { Text("提交") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ModelPickerDialog(
    state: OAuthModelPickerState,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
    onRefresh: () -> Unit
) {
    var selected by remember(state.account.id, state.models, state.selected) {
        mutableStateOf(state.selected)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要添加的模型") },
        text = {
            Column {
                Text(
                    state.account.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(state.models, key = { it }) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (model in selected) selected - model else selected + model
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = model in selected,
                                onCheckedChange = {
                                    selected = if (it) selected + model else selected - model
                                }
                            )
                            Text(
                                model,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
                TextButton(onClick = onRefresh) { Text("刷新模型列表") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) {
                Text("保存（${selected.size}）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后设置") }
        }
    )
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
