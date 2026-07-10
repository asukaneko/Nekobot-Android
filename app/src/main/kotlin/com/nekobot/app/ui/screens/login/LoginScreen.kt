package com.nekobot.app.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.nekobot.app.R
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "登录以继续",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant
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
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
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

    fun onServerUrlChange(v: String) { _serverUrl.value = v }
    fun onUsernameChange(v: String) { _username.value = v }
    fun onPasswordChange(v: String) { _password.value = v }

    /**
     * 登录流程：
     * 1. 写入服务器地址到 prefs
     * 2. 重建网络客户端
     * 3. 调用 repo.login
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
            onSuccess = { onSuccess() }
        )
    }
}
