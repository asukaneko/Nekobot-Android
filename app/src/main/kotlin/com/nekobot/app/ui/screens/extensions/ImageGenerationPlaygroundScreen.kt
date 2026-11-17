package com.nekobot.app.ui.screens.extensions

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ImageHistoryEntry
import com.nekobot.app.data.local.LocalImageResult
import com.nekobot.app.data.local.ai.ImageGenerationReference
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import com.nekobot.app.ui.components.ErrorToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PlaygroundImageResult(val image: LocalImageResult, val prompt: String, val size: String)

class ImageGenerationPlaygroundViewModel : MediaPlaygroundViewModel("image_generation") {
    private val _results = MutableStateFlow<List<PlaygroundImageResult>>(emptyList())
    internal val results = _results.asStateFlow()
    private val _referenceUri = MutableStateFlow<String?>(null)
    val referenceUri = _referenceUri.asStateFlow()
    private val _history = MutableStateFlow<List<ImageHistoryEntry>>(emptyList())
    internal val history = _history.asStateFlow()
    private var reference: ImageGenerationReference? = null
    private var historyJob: kotlinx.coroutines.Job? = null

    init { refreshHistory() }

    override fun clearResult() { _results.value = emptyList(); removeReference() }
    fun clearImages() { if (!loading.value) _results.value = emptyList() }
    fun removeReference() { reference = null; _referenceUri.value = null }

    /** 读取历史记录；历史图片在应用私有目录，重启后仍然可见。 */
    fun refreshHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            try {
                _history.value = ServiceContainer.imageHistoryStore.list()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            }
        }
    }

    fun historyUri(entry: ImageHistoryEntry): String = ServiceContainer.imageHistoryStore.uriOf(entry)

    fun deleteHistory(entry: ImageHistoryEntry) {
        viewModelScope.launch {
            runCatching { ServiceContainer.imageHistoryStore.delete(entry.id) }
            refreshHistory()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { ServiceContainer.imageHistoryStore.clear() }
            refreshHistory()
        }
    }

    fun selectReference(context: Context, uri: Uri) {
        runOperation {
            val bytes = readPlaygroundFile(context, uri, 20 * 1024 * 1024)
            val mime = withContext(Dispatchers.IO) {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth > 0 && options.outHeight > 0) { context.getString(R.string.playground_invalid_image) }
                options.outMimeType ?: context.contentResolver.getType(uri) ?: "image/png"
            }
            reference = ImageGenerationReference(bytes, mime)
            _referenceUri.value = uri.toString()
        }
    }

    fun generate(prompt: String, size: String, n: Int) {
        if (prompt.isBlank()) { showToast(string(R.string.imggen_prompt_required)); return }
        if (!isValidImageSize(size)) { showToast(string(R.string.playground_invalid_size)); return }
        val referenceSnapshot = reference
        val modelId = requestedModelId
        runOperation {
            when (val result = unified.generateImages(prompt.trim(), size, n.coerceIn(1, 4), referenceSnapshot, modelId)) {
                is Resource.Success -> {
                    if (result.data.isEmpty()) showError(string(R.string.playground_no_images))
                    else {
                        _results.value = (result.data.map { PlaygroundImageResult(it, prompt.trim(), size) } + _results.value).take(20)
                        // 生成成功即写入历史（图片复制到私有目录），失败不影响本次展示。
                        result.data.forEach { image ->
                            runCatching {
                                ServiceContainer.imageHistoryStore.record(
                                    image.cacheUri, prompt.trim(), size, image.mimeType, image.usedModelName
                                )
                            }
                        }
                        refreshHistory()
                    }
                }
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }
}

internal fun isValidImageSize(size: String): Boolean {
    val dimensions = size.lowercase().split('x')
    return dimensions.size == 2 && dimensions.all { (it.toIntOrNull() ?: 0) in 64..4096 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGenerationPlaygroundScreen(onBack: () -> Unit, onConfigureModels: () -> Unit) {
    val vm: ImageGenerationPlaygroundViewModel = viewModel()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val referenceUri by vm.referenceUri.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val busy by vm.loading.collectAsStateWithLifecycle()
    val configLoading by vm.configLoading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("1024x1024") }
    var customSize by rememberSaveable { mutableStateOf("1024x1024") }
    var count by rememberSaveable { mutableIntStateOf(1) }
    var preview by remember { mutableStateOf<PlaygroundImageResult?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.selectReference(context.applicationContext, it)
        }
    }
    key(appMode, revision) {
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.updateSource("$appMode:$revision") }
    }
    LaunchedEffect(toast) { toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearToast() } }
    val selectedModel = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    val supportsReference = selectedModel?.supportsImageReference != false
    val resolvedSize = if (size == "custom") customSize.lowercase().replace('×', 'x').replace(" ", "") else size
    val samplePrompt = stringResource(R.string.playground_image_sample)
    val sizeOptions = listOf(
        "1024x1024" to stringResource(R.string.imggen_size_square),
        "1536x1024" to stringResource(R.string.playground_size_landscape),
        "1024x1536" to stringResource(R.string.playground_size_portrait),
        "1792x1024" to stringResource(R.string.imggen_size_landscape),
        "1024x1792" to stringResource(R.string.imggen_size_portrait),
        "512x512" to "512 × 512",
        "custom" to stringResource(R.string.playground_custom_size)
    )
    PlaygroundScaffold(stringResource(R.string.imggen_title), stringResource(R.string.imggen_generate_button), busy,
        prompt.isNotBlank() && isValidImageSize(resolvedSize) && models.isNotEmpty() && !configLoading &&
            (referenceUri == null || supportsReference),
        onBack, { vm.refresh() }, { vm.generate(prompt, resolvedSize, count) }, vm::cancel, onConfigureModels,
        onOpenHistory = { vm.refreshHistory(); showHistory = true }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            error?.let { message -> item { ErrorToast(message = message, onShown = { vm.clearError() }) } }
            item {
                PlaygroundSection(stringResource(R.string.playground_input), trailing = {
                    TextButton(onClick = { prompt = samplePrompt }, enabled = !busy) { Text(stringResource(R.string.playground_sample)) }
                }) {
                    OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.imggen_prompt_label)) }, minLines = 4, maxLines = 8,
                        placeholder = { Text(stringResource(R.string.playground_image_prompt_hint)) })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { referencePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !busy && supportsReference) {
                            Text(stringResource(if (referenceUri == null) R.string.playground_add_reference else R.string.playground_change_reference))
                        }
                        Spacer(Modifier.weight(1f))
                        if (referenceUri != null) IconButton(onClick = vm::removeReference, enabled = !busy) {
                            Icon(Icons.Default.Close, stringResource(R.string.playground_remove_reference))
                        }
                    }
                    referenceUri?.let { uri ->
                        AsyncImage(uri, stringResource(R.string.playground_reference_image), contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                        Text(stringResource(if (supportsReference) R.string.playground_reference_hint else R.string.playground_reference_unsupported), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                PlaygroundSection(stringResource(R.string.imggen_params)) {
                    PlaygroundModelPicker(models, modelId, !busy && !configLoading, vm::selectModel)
                    if (configLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    PlaygroundDropdown(stringResource(R.string.imggen_size_label), size, sizeOptions, { size = it }, !busy)
                    if (size == "custom") OutlinedTextField(customSize, { customSize = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.playground_dimensions)) }, singleLine = true, enabled = !busy,
                        isError = !isValidImageSize(resolvedSize),
                        supportingText = { Text(stringResource(R.string.playground_size_hint)) })
                    Text(stringResource(R.string.imggen_count), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { n -> FilterChip(count == n, { count = n }, enabled = !busy,
                            label = { Text(stringResource(R.string.imggen_count_unit, n)) }) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.imggen_results), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (results.isNotEmpty()) TextButton(vm::clearImages, enabled = !busy) { Text(stringResource(R.string.playground_clear)) }
                }
                if (results.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.playground_image_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(results, key = { it.image.cacheUri }) { result ->
                GeneratedImageCard(result, onPreview = { preview = result }, onReusePrompt = { prompt = result.prompt },
                    onUseReference = { vm.selectReference(context.applicationContext, Uri.parse(result.image.cacheUri)) }, enabled = !busy)
            }
        }
    }
    preview?.let { ImagePreviewDialog(it, onDismiss = { preview = null }) }
    if (showHistory) {
        ImageHistoryDialog(
            entries = history,
            uriOf = vm::historyUri,
            onDismiss = { showHistory = false },
            onPreview = { entry -> showHistory = false; preview = entry.toPlaygroundImageResult(vm.historyUri(entry)) },
            onReusePrompt = { prompt = it.prompt; showHistory = false },
            onUseReference = { entry ->
                showHistory = false
                vm.selectReference(context.applicationContext, Uri.parse(vm.historyUri(entry)))
            },
            onDelete = vm::deleteHistory,
            onClear = vm::clearHistory
        )
    }
}

/** 历史记录转成与本次生成结果一致的展示模型，复用预览/保存/分享逻辑。 */
private fun ImageHistoryEntry.toPlaygroundImageResult(uri: String) = PlaygroundImageResult(
    image = LocalImageResult(uri, mimeType, "", modelName),
    prompt = prompt,
    size = size
)

@Composable
private fun GeneratedImageCard(result: PlaygroundImageResult, onPreview: () -> Unit, onReusePrompt: () -> Unit,
    onUseReference: () -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
            SubcomposeAsyncImage(result.image.cacheUri, stringResource(R.string.imggen_generated_image),
                contentScale = ContentScale.Fit,
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.playground_image_load_failed)) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 440.dp)
                    .aspectRatio(result.size.substringBefore('x').toFloat() / result.size.substringAfter('x').toFloat())
                    .clickable(onClick = onPreview))
        }
        Text(result.image.usedModelName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(result.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.playground_more_actions)) }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.playground_reuse_prompt)) }, onClick = { expanded = false; onReusePrompt() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.playground_use_reference)) }, onClick = { expanded = false; onUseReference() })
                }
            }
            Spacer(Modifier.weight(1f))
            PlaygroundMediaActions(result.image.cacheUri, result.image.mimeType, image = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewDialog(result: PlaygroundImageResult, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.imggen_results)) }, navigationIcon = {
                    IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                }, actions = {
                    IconButton(onClick = { scale = (scale - 0.5f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero }) {
                        Icon(Icons.Default.ZoomOut, stringResource(R.string.playground_zoom_out))
                    }
                    IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(5f) }) {
                        Icon(Icons.Default.ZoomIn, stringResource(R.string.playground_zoom_in))
                    }
                })
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))
                    .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    } }, contentAlignment = Alignment.Center) {
                    AsyncImage(result.image.cacheUri, stringResource(R.string.imggen_generated_image), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    PlaygroundMediaActions(result.image.cacheUri, result.image.mimeType, image = true)
                }
            }
        }
    }
}
