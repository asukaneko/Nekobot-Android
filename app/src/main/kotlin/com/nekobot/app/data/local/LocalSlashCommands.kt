package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.model.Message

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

/**
 * 本地模式斜杠命令目录。
 *
 * 命令在进入 AI Pipeline 前解析，避免把 `/help`、`/jm` 等命令误当作普通对话交给模型。
 * 能由 Android 本身完成的命令通过 [LocalCommandAction.isNative] 标记；依赖 NekoBot Python/QQ
 * 运行时的命令会给出明确提示，不在 APK 内动态安装或执行不受控的 Python 包。
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
        ),
        LocalCommandSpec(
            aliases = listOf("/jmrank"),
            usage = "/jmrank [周排行|月排行]",
            description = "生成带封面和详情链接的 JM 周榜或月榜 HTML",
            action = LocalCommandAction.JM_RANK
        ),
        LocalCommandSpec(
            aliases = listOf("/jm"),
            usage = "/jm <漫画ID> [--force]",
            description = "下载漫画并保存为当前会话工作区 PDF",
            action = LocalCommandAction.JM_DOWNLOAD
        ),
        LocalCommandSpec(
            aliases = listOf("/findbook", "/fb"),
            usage = "/findbook <书名>",
            description = "搜索轻小说并生成卡片网格 HTML",
            action = LocalCommandAction.NOVEL_SEARCH
        ),
        LocalCommandSpec(
            aliases = listOf("/fa"),
            usage = "/fa <作者>",
            description = "按作者搜索轻小说",
            action = LocalCommandAction.NOVEL_SEARCH_AUTHOR
        ),
        LocalCommandSpec(
            aliases = listOf("/select"),
            usage = "/select <编号>",
            description = "选择要下载的轻小说（先 /findbook 或 /fb 搜索）",
            action = LocalCommandAction.NOVEL_SELECT
        ),
        LocalCommandSpec(
            aliases = listOf("/info"),
            usage = "/info <编号>",
            description = "获取轻小说详情（先 /findbook 或 /fb 搜索）",
            action = LocalCommandAction.NOVEL_INFO
        ),
        LocalCommandSpec(
            aliases = listOf("/hotnovel"),
            usage = "/hotnovel <day|month> [数量]",
            description = "获取今日/本月热门轻小说榜单",
            action = LocalCommandAction.NOVEL_HOT
        ),
        LocalCommandSpec(
            aliases = listOf("/random_novel", "/rn"),
            usage = "/random_novel",
            description = "随机推荐一本轻小说",
            action = LocalCommandAction.NOVEL_RANDOM
        ),
        LocalCommandSpec(
            aliases = listOf("/novel_res"),
            usage = "/novel_res <res值>",
            description = "根据 wenku8 编号下载轻小说 TXT",
            action = LocalCommandAction.NOVEL_RES
        ),
        LocalCommandSpec(
            aliases = listOf("/set_wenku_cookie"),
            usage = "/set_wenku_cookie <Cookie> || <UA>",
            description = "更新文库8的 Cookie（推荐：设置 → 轻小说 → wenku8 登录）",
            action = LocalCommandAction.NOVEL_SET_COOKIE
        ),
        LocalCommandSpec(
            aliases = listOf(
                "/jm_search", "/jm_tag", "/jm_clear",
                "/get_fav", "/add_fav", "/list_fav", "/del_fav",
                "/add_black_list", "/abl", "/del_black_list", "/dbl",
                "/list_black_list", "/lbl"
            ),
            usage = "/jm <漫画ID>",
            description = "JM 漫画命令",
            action = LocalCommandAction.PYTHON_RUNTIME_REQUIRED
        ),
        LocalCommandSpec(
            aliases = listOf(
                "/agree", "/restart", "/shutdown", "/set_admin", "/sa",
                "/del_admin", "/da", "/get_admin", "/ga", "/myid", "/id",
                "/get_friends", "/set_qq_avatar", "/send_like",
                "/set_group_admin", "/del_group_admin", "/at_all",
                "/task", "/list_tasks", "/lt", "/cancel_tasks", "/ct"
            ),
            usage = "",
            description = "QQ/机器人管理命令",
            action = LocalCommandAction.REMOTE_RUNTIME_REQUIRED
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

        val spec = aliases[commandName]
        return if (spec != null) {
            LocalParsedCommand(
                name = commandName,
                args = args,
                action = spec.action,
                known = true
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

    fun helpText(): String = buildString {
        appendLine("本地模式命令")
        appendLine()
        commands
            .filter { it.action.isNative }
            .forEach { appendLine("• `${it.usage}` — ${it.description}") }
        appendLine()
        append("Agent 会话还支持 `/yolo`，用于在当前会话中跳过常规命令授权；高风险操作仍会阻止。")
    }.trim()

    fun pythonRuntimeMessage(commandName: String): String = buildString {
        appendLine("`$commandName` 暂不能在纯本地模式执行。")
        appendLine()
        appendLine("这组高级搜索、收藏或黑名单命令仍依赖 NekoBot 的 Python 运行时。")
        appendLine("Android APK 不会在运行时安装这些包，也不会把命令伪装成普通 AI 对话。")
        appendLine()
        append("请切换到服务器模式执行该命令；其他本地可用命令可输入 `/help` 查看。")
    }.trim()

    fun remoteRuntimeMessage(commandName: String): String =
        "`$commandName` 依赖 QQ Bot 或服务器管理运行时，本地模式无法执行。请切换到服务器模式后重试。"

    fun unknownMessage(commandName: String): String =
        "未知的本地命令：`$commandName`\n\n输入 `/help` 查看本地可用命令。"
}

internal data class LocalParsedCommand(
    val name: String,
    val args: String,
    val action: LocalCommandAction,
    val known: Boolean
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
    NOVEL_SEARCH(true),
    NOVEL_SEARCH_AUTHOR(true),
    NOVEL_SELECT(true),
    NOVEL_INFO(true),
    NOVEL_HOT(true),
    NOVEL_RANDOM(true),
    NOVEL_RES(true),
    NOVEL_SET_COOKIE(true),
    PYTHON_RUNTIME_REQUIRED(false),
    REMOTE_RUNTIME_REQUIRED(false),
    UNKNOWN(false)
}

private data class LocalCommandSpec(
    val aliases: List<String>,
    val usage: String,
    val description: String,
    val action: LocalCommandAction
)
