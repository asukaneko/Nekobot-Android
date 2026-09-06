package com.nekobot.app.ui.screens.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.LocalSandboxCommandResult

/** 沙盒命令输出上限为 20K 字符，扣除 heredoc 与标记开销后限制可编辑文件大小。 */
private const val SANDBOX_EDIT_MAX_BYTES = 16_000L

/** 超大文本文件的只读预览字节数。 */
private const val SANDBOX_PREVIEW_BYTES = 16_000L

/** 沙盒文件浏览器配色：与沙箱终端保持一致。 */
private val BrowserBackground = Color(0xFF0B0F14)
private val BrowserPanel = Color(0xFF111820)
private val BrowserForeground = Color(0xFFD8DEE9)
private val BrowserMuted = Color(0xFF7F8B99)
private val BrowserPrompt = Color(0xFF73D99F)
private val BrowserError = Color(0xFFFF7B72)

/** 文件浏览列表条目。 */
private data class SandboxFileEntry(
    val name: String,
    val isDirectory: Boolean,
)

/** 文件查看/编辑状态。 */
private data class SandboxFileEditorState(
    val path: String,
    val content: String,
    val initialContent: String,
    val readOnly: Boolean,
    val loading: Boolean,
    val binary: Boolean = false,
    val notice: String? = null,
) {
    val hasChanges: Boolean get() = content != initialContent
}

/**
 * 沙盒文件浏览器：复用当前会话沙盒 shell 的命令通道浏览 / 目录。
 *
 * 支持进入子目录、查看文本文件；不超过 [SANDBOX_EDIT_MAX_BYTES] 的文本文件
 * 可编辑并保存，超大文本文件只读预览前 16 KB，二进制文件不支持查看。
 * 与沙箱终端一致：与主界面同窗口的全屏覆盖层，不依赖 Dialog 窗口的 insets 派发。
 */
@Composable
internal fun SandboxFileBrowserOverlay(
    onRunCommand: (String, (LocalSandboxCommandResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    // 平板双栏嵌入会话页时抬升整体内容，避开底部悬浮导航栏
    bottomClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    var currentPath by rememberSaveable { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<SandboxFileEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<SandboxFileEditorState?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 文案在组合期解析好，供命令回调直接使用
    val openFailedText = stringResource(R.string.chat_sandbox_files_open_failed)
    val tooLargeText = stringResource(R.string.chat_sandbox_files_too_large)
    val binaryText = stringResource(R.string.chat_sandbox_files_binary)
    val savedText = stringResource(R.string.chat_sandbox_files_saved)
    val saveFailedText = stringResource(R.string.chat_sandbox_files_save_failed)

    fun normalizeDir(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }

    fun quoteShell(path: String): String = path.replace("'", "'\\''")

    fun joinPath(dir: String, name: String): String =
        if (dir == "/") "/$name" else "$dir/$name"

    fun parentPath(path: String): String = normalizeDir(path.substringBeforeLast('/'))

    fun listDirectory(path: String) {
        val target = normalizeDir(path)
        currentPath = target
        entries = null
        listError = null
        onRunCommand("ls -1Ap '${quoteShell(target)}'") { result ->
            if (result.isSuccess) {
                entries = result.output
                    .lines()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotEmpty() }
                    .map { line ->
                        if (line.endsWith("/")) {
                            SandboxFileEntry(line.removeSuffix("/"), isDirectory = true)
                        } else {
                            SandboxFileEntry(line, isDirectory = false)
                        }
                    }
                    .sortedWith(
                        compareByDescending<SandboxFileEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
            } else {
                listError = result.error
                    ?: result.output.ifBlank { null }
                    ?: "exit ${result.exitCode}"
                entries = emptyList()
            }
        }
    }

    fun openFile(path: String) {
        editor = SandboxFileEditorState(
            path = path,
            content = "",
            initialContent = "",
            readOnly = true,
            loading = true,
        )
        val quoted = quoteShell(path)
        // 先区分目录与文件：可正确处理指向目录的符号链接
        onRunCommand("if [ -d '$quoted' ]; then printf 'NEKODIR\\n'; else printf 'NEKOFILE\\n'; fi") { classify ->
            if (classify.output.contains("NEKODIR")) {
                editor = null
                listDirectory(path)
                return@onRunCommand
            }
            // 探测大小与文本属性：含 NUL 字节即视为二进制
            val probe = buildString {
                append("sz=\$(wc -c < '").append(quoted).append("' 2>/dev/null); ")
                append("printf 'NEKOSIZE:%s\\n' \"\$sz\"; ")
                append("h=\$(head -c 8192 '").append(quoted).append("' | wc -c); ")
                append("n=\$(head -c 8192 '").append(quoted).append("' | tr -d '\\000' | wc -c); ")
                append("if [ \"\$h\" = \"\$n\" ]; then printf 'NEKOTEXT\\n'; else printf 'NEKOBIN\\n'; fi")
            }
            onRunCommand(probe) { probeResult ->
                val size = Regex("NEKOSIZE:(\\d+)").find(probeResult.output)
                    ?.groupValues?.get(1)?.toLongOrNull()
                when {
                    !probeResult.isSuccess || size == null -> {
                        editor = SandboxFileEditorState(
                            path = path,
                            content = probeResult.output,
                            initialContent = "",
                            readOnly = true,
                            loading = false,
                            notice = openFailedText,
                        )
                    }
                    probeResult.output.contains("NEKOBIN") -> {
                        editor = SandboxFileEditorState(
                            path = path,
                            content = "",
                            initialContent = "",
                            readOnly = true,
                            loading = false,
                            binary = true,
                            notice = binaryText,
                        )
                    }
                    else -> {
                        val oversized = size > SANDBOX_EDIT_MAX_BYTES
                        val readCommand = if (oversized) {
                            "head -c $SANDBOX_PREVIEW_BYTES '$quoted'"
                        } else {
                            "cat '$quoted'"
                        }
                        onRunCommand(readCommand) { readResult ->
                            editor = if (readResult.isSuccess) {
                                SandboxFileEditorState(
                                    path = path,
                                    content = readResult.output,
                                    initialContent = readResult.output,
                                    readOnly = oversized,
                                    loading = false,
                                    notice = if (oversized) tooLargeText else null,
                                )
                            } else {
                                SandboxFileEditorState(
                                    path = path,
                                    content = readResult.output,
                                    initialContent = "",
                                    readOnly = true,
                                    loading = false,
                                    notice = openFailedText,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun saveFile() {
        val state = editor ?: return
        if (state.readOnly || saving || !state.hasChanges) return
        // 引号包裹的 heredoc：内容原样写入；分隔符与内容冲突时自动加长
        var delimiter = "__NEKOBOT_FILE_EOF"
        while (state.content.lines().any { it == delimiter }) delimiter += "_"
        val command = "cat > '${quoteShell(state.path)}' << '$delimiter'\n${state.content}\n$delimiter"
        saving = true
        onRunCommand(command) { result ->
            saving = false
            if (result.isSuccess) {
                editor = editor?.copy(initialContent = editor?.content ?: state.content)
                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
            } else {
                val detail = result.error ?: result.output.ifBlank { null } ?: "exit ${result.exitCode}"
                Toast.makeText(context, "$saveFailedText：$detail".take(180), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleBack() {
        val state = editor
        when {
            state != null && !state.readOnly && state.hasChanges && !showDiscardDialog ->
                showDiscardDialog = true
            state != null -> editor = null
            else -> onDismiss()
        }
    }

    BackHandler { handleBack() }

    LaunchedEffect(Unit) { listDirectory(currentPath) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrowserBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // 平板双栏嵌入时整体抬升，避开底部悬浮导航栏
                .padding(bottom = bottomClearance)
        ) {
            val editorState = editor
            // 顶栏：返回 + 标题/路径 +（列表：上一级/刷新 | 编辑：保存）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { handleBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = BrowserForeground,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = editorState?.path?.substringAfterLast('/')?.ifBlank { editorState.path }
                            ?: stringResource(R.string.chat_sandbox_files_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = BrowserForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = editorState?.path ?: currentPath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = BrowserMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (editorState == null) {
                    val canGoUp = currentPath != "/" && entries != null
                    IconButton(
                        onClick = { listDirectory(parentPath(currentPath)) },
                        enabled = canGoUp,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.chat_sandbox_files_up),
                            tint = if (canGoUp) BrowserForeground else BrowserMuted,
                        )
                    }
                    IconButton(
                        onClick = { listDirectory(currentPath) },
                        enabled = entries != null,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.chat_sandbox_files_refresh),
                            tint = if (entries != null) BrowserForeground else BrowserMuted,
                        )
                    }
                } else {
                    if (editorState.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = BrowserPrompt,
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(
                            onClick = { saveFile() },
                            enabled = !editorState.readOnly && editorState.hasChanges && !saving,
                        ) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription = stringResource(R.string.common_save),
                                tint = if (!editorState.readOnly && editorState.hasChanges) {
                                    BrowserPrompt
                                } else {
                                    BrowserMuted
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // 内容区
            val state = editorState
            val listEntries = entries
            when {
                state != null -> {
                    if (state.loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = BrowserPrompt)
                        }
                    } else if (state.binary) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = BrowserMuted,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = state.notice ?: binaryText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BrowserMuted,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .imePadding()
                        ) {
                            state.notice?.let { notice ->
                                Text(
                                    text = notice,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrowserMuted,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BrowserPanel)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            OutlinedTextField(
                                value = state.content,
                                onValueChange = { text ->
                                    editor = editor?.copy(content = text)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                readOnly = state.readOnly,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = BrowserForeground,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrowserPrompt,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                    disabledBorderColor = Color.White.copy(alpha = 0.08f),
                                    cursorColor = BrowserPrompt,
                                    focusedContainerColor = BrowserBackground,
                                    unfocusedContainerColor = BrowserBackground,
                                    disabledContainerColor = BrowserBackground,
                                ),
                            )
                        }
                    }
                }
                listEntries == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = BrowserPrompt)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.chat_sandbox_files_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = BrowserMuted,
                            )
                        }
                    }
                }
                listError != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.chat_sandbox_files_list_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = BrowserForeground,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = listError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = BrowserError,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { listDirectory(currentPath) }) {
                                Text(stringResource(R.string.chat_sandbox_files_retry))
                            }
                        }
                    }
                }
                listEntries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_sandbox_files_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrowserMuted,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(listEntries, key = { it.name }) { entry ->
                            SandboxFileRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        listDirectory(joinPath(currentPath, entry.name))
                                    } else {
                                        openFile(joinPath(currentPath, entry.name))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.chat_sandbox_files_unsaved_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        editor = null
                    }
                ) {
                    Text(stringResource(R.string.chat_sandbox_files_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.chat_sandbox_files_keep_editing))
                }
            },
        )
    }
}

/** 文件列表行：目录显示文件夹图标，文件显示文档图标。 */
@Composable
private fun SandboxFileRow(
    entry: SandboxFileEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) BrowserPrompt else BrowserMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = BrowserForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isDirectory) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = BrowserMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
