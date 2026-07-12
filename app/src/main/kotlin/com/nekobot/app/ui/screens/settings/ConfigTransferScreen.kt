package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.ConfigExportRequest
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置迁移界面 ViewModel：管理导出/导入操作。
 */
class ConfigTransferViewModel : BaseViewModel() {

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    /** 导出配置为 ZIP 文件，写入缓存目录并返回文件 */
    suspend fun exportConfig(context: Context, password: String): File? = withContext(Dispatchers.IO) {
        try {
            val req = ConfigExportRequest(password = password.ifBlank { null })
            val resp = unified.exportConfig(req)
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string()
                showError(parseErr(errBody) ?: "导出失败 (HTTP ${resp.code()})")
                return@withContext null
            }
            val body = resp.body() ?: return@withContext null
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val file = File(context.cacheDir, "nbot-config-$timestamp.zip")
            FileOutputStream(file).use { body.byteStream().copyTo(it) }
            file
        } catch (e: Exception) {
            showError(e.message ?: "导出失败")
            null
        }
    }

    /** 导入配置：从 Uri 读取文件并上传 */
    fun importConfig(context: Context, uri: Uri, password: String, overwrite: Boolean) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val (name, bytes) = withContext(Dispatchers.IO) { readUri(context, uri) } ?: run {
                    showError("读取文件失败")
                    return@launch
                }
                val mediaType = guessMime(name).toMediaTypeOrNull()
                val fileBody = bytes.toRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("file", name, fileBody)
                val pwdBody = password.toRequestBody()
                val overwriteBody = overwrite.toString().toRequestBody()

                when (val res = unified.importConfig(part, pwdBody, overwriteBody)) {
                    is Resource.Success -> {
                        val obj = res.data?.asJsonObject
                        val success = obj?.get("success")?.asBoolean ?: true
                        if (success) {
                            val imported = obj?.get("imported")?.asJsonArray?.size() ?: 0
                            val portraits = obj?.get("portraits_restored")?.asInt ?: 0
                            val msg = buildString {
                                append("导入成功")
                                if (imported > 0) append("，配置项 $imported 个")
                                if (portraits > 0) append("，立绘 $portraits 张")
                            }
                            _importResult.value = msg
                            showToast(msg)
                        } else {
                            showError(obj?.get("error")?.asString ?: "导入失败")
                        }
                    }
                    is Resource.Error -> showError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: "导入失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    private fun parseErr(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            obj.get("error")?.asString ?: obj.get("message")?.asString
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    private fun readUri(context: Context, uri: Uri): Pair<String, ByteArray>? {
        return try {
            val name = queryFileName(context, uri) ?: "config.zip"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            name to bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "zip" -> "application/zip"
            "json" -> "application/json"
            "nbotcfg" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigTransferScreen(onBack: () -> Unit) {
    val vm: ConfigTransferViewModel = viewModel()
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val importResult by vm.importResult.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var overwrite by remember { mutableStateOf(true) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 文件选择器
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedFileName = uri.lastPathSegment ?: "已选择文件"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置迁移", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (appMode == AppMode.LOCAL) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "此功能仅服务器模式可用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    error?.let {
                        ErrorBanner(message = it, onRetry = { vm.clearError() })
                    }

                    // 1. 导出卡片
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "导出配置", subtitle = "打包配置为加密 ZIP 文件")
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("加密密码（可选）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    val file = vm.exportConfig(context, exportPassword)
                                    if (file != null) {
                                        vm.showToast("已导出到缓存: ${file.name}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("导出配置", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 2. 导入卡片
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "导入配置", subtitle = "从 ZIP 或 .nbotcfg 文件恢复配置")
                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { pickFile.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(pickedFileName ?: "选择配置文件")
                        }
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = { Text("加密密码（可选）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "覆盖现有配置",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "关闭则保留已存在的配置项",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = overwrite,
                                onCheckedChange = { overwrite = it }
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val uri = pickedUri
                                if (uri == null) {
                                    vm.showError("请先选择配置文件")
                                } else {
                                    vm.importConfig(context, uri, importPassword, overwrite)
                                }
                            },
                            enabled = pickedUri != null,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("导入配置", color = MaterialTheme.colorScheme.onPrimary)
                        }

                        importResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }
}
