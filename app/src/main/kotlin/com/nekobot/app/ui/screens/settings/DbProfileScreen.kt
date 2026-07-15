package com.nekobot.app.ui.screens.settings

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LoginRecord
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地 DB Profile 管理 ViewModel：维护 profile 列表、激活态、远程导入与切换。
 *
 * - profiles：本地已记录的 db profile 列表（来自 PrefsManager.listDbProfiles）
 * - activeName：当前激活的 db 名
 * - loginRecords：已保存的远程服务器登录记录，供导入下拉选择
 */
class DbProfileViewModel : ViewModel() {

    private val prefs: PrefsManager get() = ServiceContainer.prefs

    private val _profiles = MutableStateFlow<List<PrefsManager.DbProfile>>(emptyList())
    val profiles: StateFlow<List<PrefsManager.DbProfile>> = _profiles.asStateFlow()

    private val _activeName = MutableStateFlow(PrefsManager.DEFAULT_DB_NAME)
    val activeName: StateFlow<String> = _activeName.asStateFlow()

    private val _loginRecords = MutableStateFlow<List<LoginRecord>>(emptyList())
    val loginRecords: StateFlow<List<LoginRecord>> = _loginRecords.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { reload() }

    fun reload() {
        _profiles.value = prefs.listDbProfiles()
        _activeName.value = prefs.activeDbName
        _loginRecords.value = prefs.listLoginRecords()
    }

    fun clearToast() { _toast.value = null }

    /** 切换激活 db profile。 */
    fun switchTo(profileName: String) {
        if (profileName == _activeName.value) return
        prefs.activeDbName = profileName
        ServiceContainer.switchLocalDb(profileName)
        _activeName.value = profileName
        _toast.value = "已切换到「${displayName(profileName)}」"
    }

    /** 删除指定 profile（默认 db 不可删除）。 */
    fun delete(profileName: String) {
        if (profileName == PrefsManager.DEFAULT_DB_NAME) {
            _toast.value = "默认数据库不可删除"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = ServiceContainer.appContext
            if (ctx != null) {
                NekobotDatabase.deleteProfileFile(ctx, profileName)
                // 清理导入的立绘目录
                runCatching {
                    java.io.File(ctx.cacheDir, "portraits/$profileName").deleteRecursively()
                }
            }
            prefs.removeDbProfile(profileName)
            withContext(Dispatchers.Main) {
                if (_activeName.value == profileName) {
                    _activeName.value = PrefsManager.DEFAULT_DB_NAME
                    ServiceContainer.switchLocalDb(PrefsManager.DEFAULT_DB_NAME)
                }
                reload()
                _toast.value = "已删除 db「$profileName」"
            }
        }
    }

    /**
     * 从远程服务器下载 nbotcfg 并导入为新 db profile。
     *
     * 密码由本地自动随机生成，传给服务端加密配置包，本地用同一密码解密。
     * 用户无需输入密码，整个过程对用户透明。
     */
    fun importFromRemote(
        record: LoginRecord,
        displayName: String
    ) {
        if (displayName.isBlank()) {
            _toast.value = "请输入显示名"
            return
        }
        val profileName = sanitizeProfileName(displayName)
        // 随机密码：仅用于本次 HTTP 传输的加解密，用户无感
        val password = generateRandomPassword()
        _importing.value = true
        viewModelScope.launch {
            val result = ServiceContainer.unified.importNbotConfigFromRemote(
                url = record.serverUrl,
                token = record.token,
                password = password,
                profileName = profileName,
                displayName = displayName
            )
            _importing.value = false
            if (result.success) {
                prefs.saveDbProfile(
                    PrefsManager.DbProfile(
                        name = profileName,
                        displayName = displayName,
                        source = "imported",
                        createdAt = System.currentTimeMillis()
                    )
                )
                reload()
                // 自动切换到新导入的 db
                prefs.activeDbName = profileName
                ServiceContainer.switchLocalDb(profileName)
                _activeName.value = profileName
                _toast.value = result.message
            } else {
                _toast.value = result.message
            }
        }
    }

    /** 生成 16 字节随机密码（Base64 编码），用于本次导出/导入的加解密。 */
    private fun generateRandomPassword(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun displayName(name: String): String =
        _profiles.value.firstOrNull { it.name == name }?.displayName ?: name

    /** 显示名 → db 文件名（仅保留字母数字下划线，避免文件名非法字符）。 */
    private fun sanitizeProfileName(raw: String): String {
        val base = raw.trim().ifBlank { "imported" }
        val sanitized = base.map { c ->
            if (c.isLetterOrDigit() || c == '_') c else '_'
        }.joinToString("").trim('_').ifBlank { "imported" }
        // 确保不与默认 db 冲突
        return if (sanitized == PrefsManager.DEFAULT_DB_NAME) "${sanitized}_2" else sanitized
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbProfileScreen(onBack: () -> Unit) {
    val vm: DbProfileViewModel = viewModel()
    val profiles by vm.profiles.collectAsState()
    val activeName by vm.activeName.collectAsState()
    val loginRecords by vm.loginRecords.collectAsState()
    val importing by vm.importing.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PrefsManager.DbProfile?>(null) }

    // 模式切换时自动刷新（兜底）
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    LaunchedEffect(appMode) { vm.reload() }

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
                title = { Text("数据库管理", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "从远程导入", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前激活 db 提示卡片
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "当前数据库",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        profiles.firstOrNull { it.name == activeName }?.displayName ?: activeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "内部标识：$activeName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "已保存的数据库列表",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (profiles.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "暂无 db profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(profiles, key = { it.name }) { profile ->
                        DbProfileCard(
                            profile = profile,
                            isActive = profile.name == activeName,
                            isDefault = profile.name == PrefsManager.DEFAULT_DB_NAME,
                            onSwitch = { vm.switchTo(profile.name) },
                            onDelete = { deleteTarget = profile }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("从远程地址导入 nbotcfg")
            }

            Text(
                "提示：导入会从已保存的远程服务器下载加密配置包，解密后写入新的本地数据库。" +
                    "可在多个 db 间自由切换，互不影响。默认数据库不可删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        LoadingOverlay(visible = importing)
    }

    if (showImportDialog) {
        ImportFromRemoteDialog(
            loginRecords = loginRecords,
            importing = importing,
            onDismiss = { showImportDialog = false },
            onConfirm = { record, displayName ->
                vm.importFromRemote(record, displayName)
            }
        )
        // 导入完成后关闭对话框
        LaunchedEffect(importing) {
            if (!importing && toast != null) {
                showImportDialog = false
            }
        }
    }

    deleteTarget?.let { profile ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = "删除数据库",
            message = "确定删除「${profile.displayName}」吗？该 db 下的所有会话/角色卡/世界书等数据将永久丢失。",
            confirmText = "删除",
            onConfirm = {
                vm.delete(profile.name)
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun DbProfileCard(
    profile: PrefsManager.DbProfile,
    isActive: Boolean,
    isDefault: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val sourceLabel = when (profile.source) {
                    "imported" -> "远程导入"
                    "local" -> "本地"
                    else -> profile.source
                }
                val dateLabel = if (profile.createdAt > 0) {
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    sdf.format(Date(profile.createdAt))
                } else "—"
                Text(
                    "$sourceLabel · $dateLabel · ${profile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isActive) "当前数据库" else "切换到此数据库") },
                        onClick = {
                            menuExpanded = false
                            if (!isActive) onSwitch()
                        },
                        enabled = !isActive
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            if (!isDefault) onDelete()
                        },
                        enabled = !isDefault
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportFromRemoteDialog(
    loginRecords: List<LoginRecord>,
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LoginRecord, String) -> Unit
) {
    var selectedRecord by remember { mutableStateOf(loginRecords.firstOrNull()) }
    var displayName by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = onDismiss,
        title = "从远程导入 nbotcfg",
        confirmText = if (importing) "导入中…" else "开始导入",
        confirmEnabled = !importing && selectedRecord != null,
        onConfirm = {
            val rec = selectedRecord
            if (rec != null) {
                onConfirm(rec, displayName)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loginRecords.isEmpty()) {
                Text(
                    "暂无已保存的远程服务器记录。请先在登录页登录远程服务器并勾选「保存登录信息」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    "选择远程服务器",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    OutlinedTextField(
                        value = selectedRecord?.let { "${it.username} @ ${it.serverUrl}" } ?: "请选择",
                        onValueChange = {},
                        readOnly = true,
                        enabled = !importing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !importing) { menuExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { menuExpanded = true }, enabled = !importing) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        loginRecords.forEach { rec ->
                            DropdownMenuItem(
                                text = { Text("${rec.username} @ ${rec.serverUrl}") },
                                onClick = {
                                    selectedRecord = rec
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("新数据库显示名") },
                placeholder = { Text("如：服务器A 配置") },
                singleLine = true,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            )

            if (importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在下载并解密配置包…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "配置包在传输过程中会自动加密，无需手动输入密码。导入成功后将自动切换到新数据库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
