package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalImageResult
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
import java.io.File

private data class ImageSizeOption(val label: String, val value: String, val ratio: Float)

private fun imageSizeOptions() = listOf(
    ImageSizeOption(ServiceContainer.getString(R.string.imggen_size_square), "1024x1024", 1f),
    ImageSizeOption(ServiceContainer.getString(R.string.imggen_size_landscape), "1792x1024", 1024f / 1792f),
    ImageSizeOption(ServiceContainer.getString(R.string.imggen_size_portrait), "1024x1792", 1792f / 1024f)
)

class ImageGenerationPlaygroundViewModel : BaseViewModel() {

    private val _results = MutableStateFlow<List<LocalImageResult>>(emptyList())
    val results: StateFlow<List<LocalImageResult>> = _results.asStateFlow()

    /** 生成图片：调用统一仓库的本地图片生成队列 */
    fun generate(prompt: String, size: String, n: Int) {
        if (prompt.isBlank()) {
            showToast(string(R.string.imggen_prompt_required))
            return
        }
        _results.value = emptyList()
        viewModelScope.launch {
            setLoading(true)
            try {
                when (val res = ServiceContainer.unified.generateImages(prompt, size, n)) {
                    is Resource.Success -> _results.value = res.data
                    is Resource.Error -> showError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.imggen_generate_failed))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationPlaygroundScreen(onBack: () -> Unit) {
    val vm: ImageGenerationPlaygroundViewModel = viewModel()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var prompt by remember { mutableStateOf("") }
    var selectedSize by remember { mutableStateOf(imageSizeOptions().first()) }
    var n by remember { mutableStateOf(1) }
    var sizeExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imggen_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
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

                // 生成区
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = stringResource(R.string.imggen_params), subtitle = stringResource(R.string.imggen_params_subtitle))
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.imggen_prompt_label)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    // 尺寸选择
                    ExposedDropdownMenuBox(
                        expanded = sizeExpanded,
                        onExpandedChange = { sizeExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        @Suppress("DEPRECATION")
                        OutlinedTextField(
                            value = selectedSize.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.imggen_size_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = sizeExpanded,
                            onDismissRequest = { sizeExpanded = false }
                        ) {
                            imageSizeOptions().forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    onClick = { selectedSize = opt; sizeExpanded = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 生成数量
                    Text(stringResource(R.string.imggen_count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { count ->
                            FilterChip(
                                selected = n == count,
                                onClick = { n = count },
                                label = { Text(stringResource(R.string.imggen_count_unit, count)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { vm.generate(prompt, selectedSize.value, n) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.imggen_generate_button), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                // 结果区
                if (results.isNotEmpty()) {
                    SectionHeader(title = stringResource(R.string.imggen_results), subtitle = stringResource(R.string.imggen_results_count, results.size))
                    results.forEach { img ->
                        GeneratedImageCard(image = img)
                    }
                }
            }

            LoadingOverlay(visible = loading, message = stringResource(R.string.imggen_generating))
        }
    }
}

@Composable
private fun GeneratedImageCard(image: LocalImageResult) {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.imggen_model_label, image.usedModelName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.cacheUri)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(R.string.imggen_generated_image),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            // 保存到相册
            IconButton(onClick = {
                saveToMediaStore(context, image.cacheUri) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.imggen_save_to_gallery), tint = MaterialTheme.colorScheme.primary)
            }
            // 分享
            IconButton(onClick = {
                shareImage(context, image.cacheUri)
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.common_share), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 将缓存文件保存到 MediaStore 图片目录 */
private fun saveToMediaStore(
    context: android.content.Context,
    cacheUri: String,
    onResult: (String) -> Unit
) {
    val sourceFile = Uri.parse(cacheUri).path?.let { File(it) }
    if (sourceFile == null || !sourceFile.exists()) {
        onResult(ServiceContainer.getString(R.string.imggen_file_not_found))
        return
    }
    val resolver = context.contentResolver
    val fileName = "nekobot_${System.currentTimeMillis()}.png"
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nekobot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(collection, values)
    if (uri == null) {
        onResult(ServiceContainer.getString(R.string.imggen_save_failed))
        return
    }
    try {
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        onResult(ServiceContainer.getString(R.string.imggen_saved_to_gallery))
    } catch (e: Exception) {
        onResult(ServiceContainer.localizedContext?.getString(R.string.imggen_save_failed_with_msg, e.message) ?: ServiceContainer.getString(R.string.imggen_save_failed))
    }
}

/** 通过 FileProvider 分享图片 */
private fun shareImage(context: android.content.Context, cacheUri: String) {
    val sourceFile = Uri.parse(cacheUri).path?.let { File(it) }
    if (sourceFile == null || !sourceFile.exists()) {
        Toast.makeText(context, ServiceContainer.getString(R.string.imggen_file_not_found), Toast.LENGTH_SHORT).show()
        return
    }
    val authority = "${context.packageName}.fileprovider"
    val contentUri = FileProvider.getUriForFile(context, authority, sourceFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, ServiceContainer.getString(R.string.imggen_share_image)))
}
