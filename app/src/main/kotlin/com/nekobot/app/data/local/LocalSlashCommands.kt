package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.plugin.BuiltInPlugins
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.ServiceContainer

internal const val LOCAL_COMMAND_MODEL = "local-command"

/**
 * 本地命令消息仍保存在聊天记录中，但绝不能进入任何 AI 上下文。
 *
 * 用户输入没有单独的数据库字段，因此按“用户角色 + 可被命令解析器拦截”识别；
 * 命令执行结果统一使用 [LOCAL_COMMAND_MODEL] 标记。
 */
internal fun isLocalCommandMessage(
    role: String?,
    content: String?,
    model: String?
): Boolean {
    if (model == LOCAL_COMMAND_MODEL) return true
    val isUser = role.equals("user", ignoreCase = true) ||
        role.equals("human", ignoreCase = true)
    return isUser && LocalSlashCommands.parse(content.orEmpty()) != null
}

internal fun LocalMessageEntity.isLocalCommandMessage(): Boolean =
    isLocalCommandMessage(role = role, content = content, model = model)

internal fun Message.isLocalCommandMessage(): Boolean =
    isLocalCommandMessage(role = role, content = content, model = model)

/** 智能路由使用的上下文估算必须与真正交给 AI 的消息集合一致。 */
internal fun estimateLocalAiContextTokens(messages: List<LocalMessageEntity>): Int =
    messages
        .filterNot { it.isLocalCommandMessage() }
        .sumOf { (it.content.length + 2) / 3 }

/**
 * 本地命令虽然直接返回完整消息，也必须发送结束事件，让聊天运行时清理占位消息并重新读取 Room。
 */
internal fun localCommandCompletionEvents(
    sessionId: String,
    reply: Message
): List<RealtimeEvent> = listOf(
    RealtimeEvent.AiResponse(reply, sessionId),
    RealtimeEvent.StreamEnd(sessionId)
)

/**
 * 本地模式斜杠命令目录。
 *
 * 命令在进入 AI Pipeline 前解析，避免把 `/help`、`/jm` 等命令误当作普通对话交给模型。
 */
internal object LocalSlashCommands {
    private val commands = listOf(
        LocalCommandSpec(
            aliases = listOf("/help", "/h", "/commands"),
            usage = "/help",
            description = "查看本地命令帮助",
            action = LocalCommandAction.HELP
        ),
        LocalCommandSpec(
            aliases = listOf("/local_status", "/status"),
            usage = "/status",
            description = "查看本地会话、模型、消息与工作区状态",
            action = LocalCommandAction.LOCAL_STATUS
        ),
        LocalCommandSpec(
            aliases = listOf("/export_chat", "/export"),
            usage = "/export",
            description = "把当前聊天导出为工作区 Markdown 文件",
            action = LocalCommandAction.EXPORT_CHAT
        ),
        LocalCommandSpec(
            aliases = listOf("/note"),
            usage = "/note <内容>",
            description = "追加一条本地工作区速记",
            action = LocalCommandAction.NOTE_ADD
        ),
        LocalCommandSpec(
            aliases = listOf("/notes"),
            usage = "/notes",
            description = "打开当前会话的本地速记",
            action = LocalCommandAction.NOTES_SHOW
        ),
        LocalCommandSpec(
            aliases = listOf("/tts"),
            usage = "/tts [on|off]",
            description = "切换当前会话的 TTS",
            action = LocalCommandAction.TTS
        ),
        LocalCommandSpec(
            aliases = listOf("/workspace", "/ws"),
            usage = "/workspace",
            description = "查看当前会话工作区文件",
            action = LocalCommandAction.WORKSPACE_LIST
        ),
        LocalCommandSpec(
            aliases = listOf("/ws_send"),
            usage = "/ws_send <文件名>",
            description = "在聊天中发送工作区文件",
            action = LocalCommandAction.WORKSPACE_SEND
        ),
        LocalCommandSpec(
            aliases = listOf("/roll", "/random_dice", "/rd"),
            usage = "/roll [NdM±K]",
            description = "掷骰子，例如 /roll 2d6+1",
            action = LocalCommandAction.ROLL
        ),
        LocalCommandSpec(
            aliases = listOf("/random_rps", "/rps"),
            usage = "/random_rps",
            description = "随机石头剪刀布",
            action = LocalCommandAction.RANDOM_RPS
        ),
        LocalCommandSpec(
            aliases = listOf("/coin"),
            usage = "/coin",
            description = "抛硬币",
            action = LocalCommandAction.COIN
        ),
        LocalCommandSpec(
            aliases = listOf("/pick", "/choose"),
            usage = "/pick A | B | C",
            description = "从多个选项中随机选择",
            action = LocalCommandAction.PICK
        ),
        LocalCommandSpec(
            aliases = listOf("/calc"),
            usage = "/calc <表达式>",
            description = "安全本地计算，支持括号、幂和常用函数",
            action = LocalCommandAction.CALCULATE
        ),
        LocalCommandSpec(
            aliases = listOf("/password", "/pwdgen"),
            usage = "/password [长度]",
            description = "生成 8 到 128 位本地随机密码",
            action = LocalCommandAction.PASSWORD
        ),
        LocalCommandSpec(
            aliases = listOf("/sha256", "/hash"),
            usage = "/sha256 <文本>",
            description = "计算文本的 SHA-256",
            action = LocalCommandAction.HASH
        ),
        LocalCommandSpec(
            aliases = listOf("/fortune", "/jrrp"),
            usage = "/fortune",
            description = "查看今日运势",
            action = LocalCommandAction.FORTUNE
        )
    )

    private val aliases = commands
        .flatMap { spec -> spec.aliases.map { alias -> alias.lowercase() to spec } }
        .toMap()

    fun parse(input: String): LocalParsedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null

        val commandName = trimmed
            .substringBefore(' ')
            .substringBefore('\n')
            .lowercase()
        val args = trimmed
            .drop(commandName.length)
            .trim()

        // /skill 是面向 Agent 的显式 Skill 调用语法，需要继续进入 AI Pipeline。
        if (commandName == "/skill") return null

        val spec = aliases[commandName]
        return if (spec != null) {
            LocalParsedCommand(
                name = commandName,
                args = args,
                action = spec.action,
                known = true
            )
        } else {
            val pluginCommand = runCatching {
                ServiceContainer.pluginManager.findCommand(commandName)
            }.getOrElse {
                // 单元测试与应用初始化前默认启用内置插件。
                BuiltInPlugins.findDefaultCommand(commandName)
            }
            if (pluginCommand != null) {
                LocalParsedCommand(
                    name = commandName,
                    args = args,
                    action = pluginCommand.builtInAction ?: LocalCommandAction.PLUGIN,
                    known = true,
                    pluginCommand = pluginCommand
                )
            } else {
                LocalParsedCommand(
                    name = commandName,
                    args = args,
                    action = LocalCommandAction.UNKNOWN,
                    known = false
                )
            }
        }
    }

    fun suggestions(query: String): List<LocalCommandSuggestion> {
        val normalized = query.trim().lowercase()
        val native = commands
            .asSequence()
            .filter { spec ->
                normalized.isBlank() || normalized == "/" ||
                    spec.aliases.any { alias ->
                        alias.startsWith(normalized) || alias.contains(normalized.removePrefix("/"))
                    }
            }
            .map { spec ->
                LocalCommandSuggestion(
                    command = spec.aliases.first(),
                    aliases = spec.aliases,
                    takesArguments = '<' in spec.usage || '[' in spec.usage,
                    description = spec.description
                )
            }
            .distinctBy(LocalCommandSuggestion::command)
            .toList()
        val plugins = runCatching {
            ServiceContainer.pluginManager.commandSuggestions(normalized)
        }.getOrElse {
            BuiltInPlugins.defaultCommandSuggestions(normalized)
        }
        // 每条插件命令只保留完整的主命令，别名不再单独出现在补全列表中
        val pluginSuggestions = plugins
            .distinctBy { it.pluginId to it.name }
            .map { binding ->
                LocalCommandSuggestion(
                    command = "/" + binding.name,
                    aliases = binding.aliases,
                    takesArguments = '<' in binding.usage || '[' in binding.usage,
                    description = binding.description
                )
            }
        return (native + pluginSuggestions).distinctBy(LocalCommandSuggestion::command)
    }

    fun helpText(): String = buildString {
        appendLine("本地模式命令")
        appendLine()
        commands
            .filter { it.action.isNative }
            .forEach { appendLine("• `${it.usage}` — ${it.description}") }
        val plugins = runCatching { ServiceContainer.pluginManager.commandSuggestions("") }
            .getOrElse { BuiltInPlugins.defaultCommandSuggestions("") }
        if (plugins.isNotEmpty()) {
            appendLine()
            appendLine("插件命令")
            plugins
                .distinctBy { it.pluginId to it.name }
                .forEach { command ->
                    appendLine("- `${command.usage}` — ${command.description} (${command.pluginId})")
                }
        }
        appendLine()
        append("Agent 会话还支持 `/yolo`，用于在当前会话中跳过常规命令授权；高风险操作仍会阻止。")
    }.trim()

    /** 供插件安装器检查命令冲突；返回值统一为带 / 的形式。 */
    internal fun reservedCommandAliases(): Set<String> = aliases.keys

    fun unknownMessage(commandName: String): String =
        "未知的本地命令：`$commandName`\n\n输入 `/help` 查看本地可用命令。"
}

internal data class LocalParsedCommand(
    val name: String,
    val args: String,
    val action: LocalCommandAction,
    val known: Boolean,
    val pluginCommand: com.nekobot.app.data.local.plugin.PluginCommandBinding? = null
)

internal data class LocalCommandSuggestion(
    val command: String,
    val aliases: List<String>,
    val takesArguments: Boolean,
    /** 补全面板在主命令下方展示的命令说明。 */
    val description: String = ""
)

internal enum class LocalCommandAction(val isNative: Boolean) {
    HELP(true),
    LOCAL_STATUS(true),
    EXPORT_CHAT(true),
    NOTE_ADD(true),
    NOTES_SHOW(true),
    TTS(true),
    WORKSPACE_LIST(true),
    WORKSPACE_SEND(true),
    ROLL(true),
    RANDOM_RPS(true),
    COIN(true),
    PICK(true),
    CALCULATE(true),
    PASSWORD(true),
    HASH(true),
    FORTUNE(true),
    JM_RANK(true),
    JM_DOWNLOAD(true),
    JM_SEARCH(true),
    NOVEL_SEARCH(true),
    NOVEL_SEARCH_AUTHOR(true),
    NOVEL_SELECT(true),
    NOVEL_INFO(true),
    NOVEL_HOT(true),
    NOVEL_RANDOM(true),
    NOVEL_RES(true),
    WENKU8_LOGIN(true),
    NOVEL_SET_COOKIE(true),
    PLUGIN(true),
    UNKNOWN(false)
}

private data class LocalCommandSpec(
    val aliases: List<String>,
    val usage: String,
    val description: String,
    val action: LocalCommandAction
)
