package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class LocalCommandPolicy(
    val mainCommand: String,
    val requiresAuthorization: Boolean,
    val blockedReason: String? = null
)

private val localSafeCommands = setOf(
    "pwd", "ls", "cat", "head", "tail", "wc", "echo", "date", "whoami", "id", "uname"
)

private val localBlockedCommandPatterns = listOf(
    Regex("""(?i)(^|\s)(rm|rmdir)\s+(-[^\s]*r[^\s]*f|--recursive)"""),
    Regex("""(?i)(^|\s)(mkfs(\.\w+)?|format|fdisk|parted)(\s|$)"""),
    Regex("""(?i)(^|\s)dd\s+.*\bof\s*="""),
    Regex("""(?i)(^|\s)(reboot|shutdown|poweroff|halt|su)(\s|$)"""),
    Regex("""(?i)(^|\s)(chmod|chown)\s+(-R|--recursive)"""),
    Regex("""(?i)(^|\s)(kill|pkill|killall)\s+(-9\s+)?(1|all)(\s|$)"""),
    Regex("""(?i)(^|\s)pm\s+(clear|uninstall)(\s|$)""")
)

internal fun evaluateLocalCommand(command: String): LocalCommandPolicy {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) {
        return LocalCommandPolicy("", requiresAuthorization = false, blockedReason = "命令不能为空")
    }
    val firstToken = Regex("""^\s*(?:"([^"]+)"|'([^']+)'|(\S+))""")
        .find(trimmed)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        .orEmpty()
    val mainCommand = normalizeLocalCommandName(firstToken)

    val blocked = localBlockedCommandPatterns.firstOrNull { it.containsMatchIn(trimmed) }
    if (blocked != null) {
        return LocalCommandPolicy(
            mainCommand = mainCommand,
            requiresAuthorization = false,
            blockedReason = "命令包含禁止执行的高风险操作"
        )
    }

    val containsShellControl = Regex("""[;&|><`$()\r\n]""").containsMatchIn(trimmed)
    val isBareSafeCommand = mainCommand in localSafeCommands && !containsShellControl
    return LocalCommandPolicy(
        mainCommand = mainCommand,
        requiresAuthorization = !isBareSafeCommand
    )
}

private fun normalizeLocalCommandName(token: String): String = File(token.trim())
    .name
    .removeSuffix(".exe")
    .removeSuffix(".cmd")
    .removeSuffix(".bat")
    .lowercase()

/**
 * 提取一次命令授权请求中涉及的可执行命令名。
 *
 * 工具命令可能包含 `git status && git diff` 这样的 Shell 命令链。参数变化时
 * 应复用授权，但不能把对 `git` 的授权扩展为新出现的 `curl`；引号中的 Shell
 * 运算符不参与切分。
 */
internal fun extractLocalAuthorizationCommands(
    command: String,
    fallbackMainCommand: String
): Set<String> {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) {
        return normalizeLocalCommandName(fallbackMainCommand).takeIf(String::isNotBlank)?.let(::setOf)
            ?: emptySet()
    }

    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var hasShellBoundary = false

    for (char in trimmed) {
        if (escaped) {
            current.append(char)
            escaped = false
            continue
        }
        if (char == '\\' && quote != '\'') {
            current.append(char)
            escaped = true
            continue
        }
        if (quote != null) {
            current.append(char)
            if (char == quote) quote = null
            continue
        }
        if (char == '\'' || char == '"') {
            quote = char
            current.append(char)
            continue
        }
        if (char == ';' || char == '|' || char == '&' || char == '\r' || char == '\n') {
            hasShellBoundary = true
            segments += current.toString()
            current.setLength(0)
        } else {
            current.append(char)
        }
    }
    segments += current.toString()

    // `agent_memory_update: mode=append` 等非 Shell 工具标签使用调用方提供的
    // 主命令，不能把整段标签当作可执行文件名。
    if (!hasShellBoundary) {
        return normalizeLocalCommandName(fallbackMainCommand)
            .takeIf(String::isNotBlank)
            ?.let(::setOf)
            ?: emptySet()
    }

    return segments.asSequence()
        .mapNotNull { segment ->
            Regex("""^\s*(?:"([^"]+)"|'([^']+)'|(\S+))""")
                .find(segment.trim())
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull(String::isNotBlank)
        }
        .map(::normalizeLocalCommandName)
        .filter(String::isNotBlank)
        .toSet()
        .ifEmpty {
            normalizeLocalCommandName(fallbackMainCommand)
                .takeIf(String::isNotBlank)
                ?.let(::setOf)
                ?: emptySet()
        }
}

/**
 * 需要连带记住“子命令”的高危多用途命令。
 *
 * `git`、`npm`、`python` 这类命令的能力完全取决于第一个参数：
 * 只按命令名记忆授权会让“批准 `git status`”顺带放行后续的 `git push`。
 */
private val localSubcommandSensitiveCommands = setOf(
    "git", "npm", "pnpm", "yarn", "npm.cmd", "pip", "pip3", "python", "python3", "python3.11",
    "node", "deno", "bun", "cargo", "go", "gradle", "make", "cmake",
    "curl", "wget", "adb", "apt", "apt-get", "apk", "pkg", "docker", "docker-compose", "gh",
    "bash", "sh", "zsh", "fish", "busybox", "openssl", "ssh", "scp", "sftp", "rsync",
    "tar", "unzip", "zip", "sqlite3", "psql", "mysql", "mongosh", "redis-cli", "npx"
)

/**
 * 生成单个 Shell 分段的授权指纹。
 *
 * - 普通命令：指纹就是命令名（`ls` 的所有参数共用一次授权，符合直觉）；
 * - 高危多用途命令：指纹追加首个非选项参数（`git status`、`git push`、`npm install`）。
 */
internal fun localAuthorizationFingerprint(segment: String, fallbackMainCommand: String): String? {
    val tokens = localCommandTokens(segment)
    val rawCommand = tokens.firstOrNull()
        ?: fallbackMainCommand.takeIf(String::isNotBlank)
        ?: return null
    val mainCommand = normalizeLocalCommandName(rawCommand).ifBlank {
        normalizeLocalCommandName(fallbackMainCommand)
    }
    if (mainCommand.isBlank()) return null
    if (mainCommand !in localSubcommandSensitiveCommands) return mainCommand

    val subcommand = tokens.drop(1)
        .firstOrNull { token ->
            val cleaned = token.trim('"', '\'')
            cleaned.isNotBlank() && !cleaned.startsWith("-") &&
                !cleaned.contains('=') && !cleaned.contains('/')
        }
    return if (subcommand == null) {
        mainCommand
    } else {
        "$mainCommand ${subcommand.trim('"', '\'').lowercase()}"
    }
}

/** 按 Shell 语法切分出命令分段中的“词”（引号内的空格不切分）。 */
private fun localCommandTokens(segment: String): List<String> =
    Regex(""""[^"]*"|'[^']*'|[^\s]+""")
        .findAll(segment.trim())
        .map { it.value }
        .toList()

/**
 * 生成一次命令授权请求对应的全部指纹。
 *
 * 与命令分段一致：`git status && git push` 会分别生成 `git status` 与 `git push`。
 * 非 Shell 参数（`agent_memory_update` 这类工具标签）无法解析出与调用方一致的主命令，
 * 统一退回调用方给出的主命令。
 */
internal fun extractLocalAuthorizationFingerprints(
    command: String,
    mainCommand: String
): Set<String> {
    val fallback = normalizeLocalCommandName(mainCommand)
    val fingerprints = extractLocalSegments(command)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { segment ->
            val firstToken = localCommandTokens(segment).firstOrNull()
                ?.let(::normalizeLocalCommandName)
            if (firstToken.isNullOrBlank() || (fallback.isNotBlank() && firstToken != fallback)) {
                // 段首不是预期的命令名：这是工具标签而非 Shell 命令，用主命令兜底。
                localAuthorizationFingerprint(fallback, fallback)
            } else {
                localAuthorizationFingerprint(segment, fallback)
            }
        }
        .toSet()
    return fingerprints.ifEmpty {
        localAuthorizationFingerprint(fallback, fallback)?.let(::setOf) ?: emptySet()
    }
}

/** 按 Shell 运算符切分命令，但忽略引号内部的运算符。 */
internal fun extractLocalSegments(command: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (char in command) {
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' && quote != '\'' -> {
                current.append(char)
                escaped = true
            }
            quote != null -> {
                current.append(char)
                if (char == quote) quote = null
            }
            char == '\'' || char == '"' -> {
                quote = char
                current.append(char)
            }
            char == ';' || char == '|' || char == '&' || char == '\r' || char == '\n' -> {
                segments += current.toString()
                current.setLength(0)
            }
            else -> current.append(char)
        }
    }
    segments += current.toString()
    return segments
}

/** 工具类操作（删除文件、插件安装/更新）的授权指纹：`工具名 参数摘要`。 */
internal fun toolAuthorizationFingerprint(toolName: String, argument: String): String? {
    val normalizedTool = toolName.trim().lowercase()
    if (normalizedTool.isBlank()) return null
    val argumentSummary = argument.trim()
        .replace(Regex("""\s+"""), " ")
        .take(120)
        .lowercase()
    return if (argumentSummary.isBlank()) normalizedTool else "$normalizedTool $argumentSummary"
}

/**
 * 本地 Agent 命令授权状态。
 *
 * `/yolo` 只在当前应用进程与当前会话内生效；“始终允许”按**授权指纹**记忆，
 * 并可按会话持久化（重启后仍有效），见 [extractLocalAuthorizationFingerprints]。
 */
class LocalExecAuthorizationManager(
    private val authorizationTimeoutMs: Long = 10 * 60 * 1000L,
    /** 读取某会话已持久化的“始终允许”指纹；返回 null/空集表示无记录。 */
    private val loadPersistedRules: ((sessionId: String) -> Set<String>?)? = null,
    /** 保存某会话的“始终允许”指纹集合。 */
    private val savePersistedRules: ((sessionId: String, fingerprints: Set<String>) -> Unit)? = null
) {
    private data class Pending(
        val sessionId: String,
        val mainCommand: String,
        val authorizationKeys: Set<String>,
        val decision: CompletableDeferred<ExecAuthorization>
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val alwaysAllowed = ConcurrentHashMap<String, MutableSet<String>>()
    private val yoloSessions = ConcurrentHashMap.newKeySet<String>()

    fun enableYolo(sessionId: String) {
        yoloSessions.add(sessionId)
    }

    fun disableYolo(sessionId: String) {
        yoloSessions.remove(sessionId)
    }

    fun isYoloEnabled(sessionId: String): Boolean = sessionId in yoloSessions

    /** 某会话当前已记忆的授权指纹（含持久化恢复的部分），供测试与调试查看。 */
    fun allowedKeys(sessionId: String): Set<String> = allowedKeySet(sessionId).toSet()

    private fun allowedKeySet(sessionId: String): MutableSet<String> {
        val keys = alwaysAllowed.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        val persisted = runCatching { loadPersistedRules?.invoke(sessionId) }.getOrNull().orEmpty()
        if (persisted.isNotEmpty()) keys.addAll(persisted)
        return keys
    }

    suspend fun requestAuthorization(
        sessionId: String,
        command: String,
        mainCommand: String,
        onRequest: (ExecConfirmationRequest) -> Unit
    ): ExecAuthorization = awaitDecision(
        sessionId = sessionId,
        command = command,
        mainCommand = mainCommand,
        authorizationKeys = extractLocalAuthorizationFingerprints(command, mainCommand),
        message = "本地 Agent 请求执行命令",
        onRequest = onRequest
    )

    /**
     * 非 Shell 工具的授权确认（删除工作区文件、插件安装/更新等）。
     *
     * 复用与命令确认完全相同的通道：同一个弹窗、同一份“始终允许”记忆（指纹为
     * `工具名 参数摘要`），因此用户不会遇到两套互不相识的授权体验。
     *
     * @return true 表示放行（本次或始终），false 表示拒绝/超时。
     */
    suspend fun requestToolAuthorization(
        sessionId: String,
        toolName: String,
        fingerprintArgument: String,
        message: String,
        onRequest: (ExecConfirmationRequest) -> Unit
    ): Boolean {
        val decision = awaitDecision(
            sessionId = sessionId,
            command = message,
            mainCommand = toolName,
            authorizationKeys = setOfNotNull(
                toolAuthorizationFingerprint(toolName, fingerprintArgument)
            ),
            message = message,
            onRequest = onRequest
        )
        return decision != ExecAuthorization.Reject
    }

    private suspend fun awaitDecision(
        sessionId: String,
        command: String,
        mainCommand: String,
        authorizationKeys: Set<String>,
        message: String,
        onRequest: (ExecConfirmationRequest) -> Unit
    ): ExecAuthorization {
        if (isYoloEnabled(sessionId)) return ExecAuthorization.Once
        val allowedKeys = allowedKeySet(sessionId)
        if (authorizationKeys.isNotEmpty() && authorizationKeys.all { it in allowedKeys }) {
            return ExecAuthorization.Always
        }

        val requestId = UUID.randomUUID().toString()
        val decision = CompletableDeferred<ExecAuthorization>()
        pending[requestId] = Pending(sessionId, mainCommand, authorizationKeys, decision)
        onRequest(
            ExecConfirmationRequest(
                requestId = requestId,
                command = command,
                mainCommand = mainCommand,
                message = message,
                sessionId = sessionId
            )
        )

        return try {
            withTimeoutOrNull(authorizationTimeoutMs) { decision.await() }
                ?: ExecAuthorization.Reject
        } finally {
            pending.remove(requestId)
        }
    }

    fun resolve(
        requestId: String,
        sessionId: String,
        authorization: ExecAuthorization
    ): Boolean {
        val request = pending[requestId] ?: return false
        if (request.sessionId != sessionId) return false
        if (authorization == ExecAuthorization.Always) {
            val keys = allowedKeySet(sessionId)
            keys.addAll(request.authorizationKeys)
            runCatching { savePersistedRules?.invoke(sessionId, keys.toSet()) }
        }
        return request.decision.complete(authorization)
    }

    /** 停止生成时拒绝该会话全部待确认命令，立即解除同步等待。 */
    fun cancelSession(sessionId: String) {
        pending.entries
            .filter { (_, request) -> request.sessionId == sessionId }
            .forEach { (requestId, request) ->
                if (pending.remove(requestId, request)) {
                    request.decision.complete(ExecAuthorization.Reject)
                }
            }
    }
}
