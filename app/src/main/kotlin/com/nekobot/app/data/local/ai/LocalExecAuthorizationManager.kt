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
 * 本地 Agent 命令授权状态。
 *
 * `/yolo` 与“始终授权”都只在当前应用进程和当前会话内生效。
 */
class LocalExecAuthorizationManager(
    private val authorizationTimeoutMs: Long = 10 * 60 * 1000L
) {
    private data class Pending(
        val sessionId: String,
        val mainCommand: String,
        val authorizationCommands: Set<String>,
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

    suspend fun requestAuthorization(
        sessionId: String,
        command: String,
        mainCommand: String,
        onRequest: (ExecConfirmationRequest) -> Unit
    ): ExecAuthorization {
        if (isYoloEnabled(sessionId)) return ExecAuthorization.Once
        val authorizationCommands = extractLocalAuthorizationCommands(command, mainCommand)
        val allowedCommands = alwaysAllowed[sessionId].orEmpty()
        if (authorizationCommands.isNotEmpty() && authorizationCommands.all { it in allowedCommands }) {
            return ExecAuthorization.Always
        }

        val requestId = UUID.randomUUID().toString()
        val decision = CompletableDeferred<ExecAuthorization>()
        pending[requestId] = Pending(sessionId, mainCommand, authorizationCommands, decision)
        onRequest(
            ExecConfirmationRequest(
                requestId = requestId,
                command = command,
                mainCommand = mainCommand,
                message = "本地 Agent 请求执行命令",
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
            alwaysAllowed
                .computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
                .addAll(request.authorizationCommands)
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
