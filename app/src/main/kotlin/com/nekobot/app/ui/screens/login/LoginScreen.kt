package com.nekobot.app.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.nekobot.app.R
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LoginRecord
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录页：服务器地址 / 用户名 / 密码表单，登录成功回调 [onLoggedIn]。
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val viewModel: LoginViewModel = viewModel()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val loginRecords by viewModel.loginRecords.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo + 标题
            Image(
                painter = painterResource(id = R.drawable.neko),
                contentDescription = "Nekobot Logo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Nekobot",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "登录以继续",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24) {
                // 服务器地址
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.x:5000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                // 用户名
                OutlinedTextField(
                    value = username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                // 密码
                OutlinedTextField(
                    value = password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (!loading) viewModel.login(onLoggedIn)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))

                // 登录按钮
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.login(onLoggedIn)
                    },
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text("登录", color = androidx.compose.ui.graphics.Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 本地模式入口：跳过登录直连 AI API
                Text(
                    text = "使用本地模式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            // 调用 switchAppMode 同步 prefs / appModeFlow / loginStateFlow
                            ServiceContainer.switchAppMode(com.nekobot.app.data.local.AppMode.LOCAL)
                            onLoggedIn()
                        }
                        .padding(8.dp)
                )

                // 历史登录记录：点击直接用 token 快速登录（多条记录可滚动）
                if (loginRecords.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "历史账号",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        loginRecords.forEach { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable {
                                        if (!loading) viewModel.quickLogin(record, onLoggedIn)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 用户名首字符圆形徽标
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = record.username.firstOrNull()?.uppercase() ?: "?",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.size(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = record.username,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = record.serverUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                // 删除按钮
                                IconButton(
                                    onClick = { viewModel.removeRecord(record.serverUrl, record.username) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "删除记录",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // 错误提示
            val errorMsg = error
            if (!errorMsg.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(
                    message = errorMsg,
                    onRetry = { viewModel.clearError() }
                )
            }
        }
    }
}

/**
 * 登录 ViewModel：持有表单字段，登录前写入服务器地址并重建网络。
 */
class LoginViewModel : BaseViewModel() {

    private val prefs get() = ServiceContainer.prefs

    private val _serverUrl = MutableStateFlow(prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(prefs.username)
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    /** 已保存的登录记录列表（按最近使用排序） */
    private val _loginRecords = MutableStateFlow(prefs.listLoginRecords())
    val loginRecords: StateFlow<List<LoginRecord>> = _loginRecords.asStateFlow()

    fun onServerUrlChange(v: String) { _serverUrl.value = v }
    fun onUsernameChange(v: String) { _username.value = v }
    fun onPasswordChange(v: String) { _password.value = v }

    /** 刷新登录记录列表 */
    fun refreshRecords() { _loginRecords.value = prefs.listLoginRecords() }

    /** 删除一条登录记录 */
    fun removeRecord(server: String, user: String) {
        prefs.removeLoginRecord(server, user)
        refreshRecords()
    }

    /**
     * 快速登录：使用已保存的 token 直接恢复登录态，无需输入密码。
     * 适用场景：token 仍在有效期内，直接写入 prefs 并重建网络。
     * 若 token 已失效，后端请求会返回 401，用户需改用普通登录。
     */
    fun quickLogin(record: LoginRecord, onSuccess: () -> Unit) {
        // 先写入地址并重建网络，再验证 token
        prefs.serverUrl = record.serverUrl
        prefs.username = record.username
        prefs.token = record.token
        ServiceContainer.rebuildNetwork()
        // 更新表单为该记录的值，便于失效后手动登录
        _serverUrl.value = record.serverUrl
        _username.value = record.username
        // 验证 token：调用 listSessions，成功则跳转，失败则提示重新输入密码
        launchResult(
            block = { repo.listSessions() },
            onSuccess = { onSuccess() },
            onError = {
                // token 已失效，清除并提示
                prefs.clearAuth()
                showError("登录已过期，请重新输入密码")
            }
        )
    }

    /**
     * 登录流程：
     * 1. 写入服务器地址到 prefs
     * 2. 重建网络客户端
     * 3. 调用 repo.login
     * 4. 成功后保存登录记录（复用 token）
     */
    fun login(onSuccess: () -> Unit) {
        val server = _serverUrl.value.trim()
        val user = _username.value.trim()
        val pwd = _password.value
        if (server.isBlank() || user.isBlank() || pwd.isBlank()) {
            showError("请填写完整信息")
            return
        }
        // 先写入地址并重建网络
        prefs.serverUrl = server
        ServiceContainer.rebuildNetwork()
        launchResult(
            block = { repo.login(user, pwd) },
            onSuccess = {
                // 保存登录记录（token 来自 NekobotRepository.login 写入的 prefs.token）
                prefs.token?.let { prefs.saveLoginRecord(server, user, it) }
                refreshRecords()
                onSuccess()
            }
        )
    }
}
