package com.nekobot.app.data.local.db

/**
 * 内置工具预设：参考 Ncatbot-comic-QQbot 仓库的 TOOL_DEFINITIONS + WORKSPACE_TOOL_DEFINITIONS。
 *
 * 这些工具在本地模式下作为只读模板展示，不允许删除/切换启用状态。
 * 本地 AI Pipeline 只会把已有 Android 执行器实现的子集以 function-calling 形式注入。
 */
data class BuiltinToolSpec(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val parametersJson: String,
    val implementationJson: String? = null
)

object BuiltinTools {

    /** 标准化 OpenAI function-calling 参数 schema JSON。 */
    private fun params(properties: Map<String, Map<String, Any>>, required: List<String> = emptyList()): String {
        val obj = StringBuilder("{\"type\":\"object\",\"properties\":{")
        properties.entries.forEachIndexed { i, (key, schema) ->
            if (i > 0) obj.append(",")
            obj.append("\"$key\":{")
            schema.entries.forEachIndexed { j, (k, v) ->
                if (j > 0) obj.append(",")
                obj.append("\"$k\":")
                when (v) {
                    is String -> obj.append("\"$v\"")
                    is Number -> obj.append(v)
                    is Boolean -> obj.append(v)
                    else -> obj.append("\"$v\"")
                }
            }
            obj.append("}")
        }
        obj.append("}")
        if (required.isNotEmpty()) {
            obj.append(",\"required\":[")
            required.forEachIndexed { i, r ->
                if (i > 0) obj.append(",")
                obj.append("\"$r\"")
            }
            obj.append("]")
        }
        obj.append("}")
        return obj.toString()
    }

    private val standardTools = listOf(
        BuiltinToolSpec(
            id = "search_news",
            name = "搜索新闻",
            description = "搜索最新的新闻资讯，可按关键词和类别过滤。",
            parametersJson = params(
                mapOf(
                    "query" to mapOf("type" to "string", "description" to "搜索关键词"),
                    "category" to mapOf("type" to "string", "description" to "新闻类别：科技/财经/社会/娱乐等")
                ),
                listOf("query")
            )
        ),
        BuiltinToolSpec(
            id = "get_weather",
            name = "查询天气",
            description = "查询指定城市的当前天气与未来预报。",
            parametersJson = params(
                mapOf(
                    "city" to mapOf("type" to "string", "description" to "城市名称"),
                    "days" to mapOf("type" to "integer", "description" to "预报天数，1-7")
                ),
                listOf("city")
            )
        ),
        BuiltinToolSpec(
            id = "search_web",
            name = "网页搜索",
            description = "通用网页搜索，返回摘要与链接。",
            parametersJson = params(
                mapOf("query" to mapOf("type" to "string", "description" to "搜索词")),
                listOf("query")
            )
        ),
        BuiltinToolSpec(
            id = "browser_use",
            name = "浏览器使用",
            description = "控制会话内的原生浏览器。支持打开网页、截图、读取正文、动态 DOM 源码、页面 URL 和可交互结构，以及点击、输入、滚动、前进、后退、刷新和执行 JavaScript。查找链接或资源地址时优先使用 get_links，需要源码时使用 get_html；页面包含图片、图表或复杂视觉内容时，使用 understand_screenshot 截图并自动调用图片理解模型。需要精确操作时先调用 get_backbone。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "动作：navigate、screenshot、understand_screenshot（截图后自动调用图片理解模型）、get_html（获取动态 DOM 源码）、get_links（结构化提取 URL）、click、type、get_text、get_readable、get_backbone、find_elements、get_page_info、scroll、execute_js、wait、back、forward、reload"
                    },
                    "url": {
                      "type": "string",
                      "description": "navigate 要打开的 http(s) URL；省略协议时默认使用 https"
                    },
                    "selector": {
                      "type": "string",
                      "description": "用于 click、type、get_text、get_html、get_links、find_elements 或 scroll 的 CSS 选择器"
                    },
                    "text": {
                      "type": "string",
                      "description": "type 动作要输入的文本"
                    },
                    "coordinate_x": {
                      "type": "integer",
                      "description": "click 动作的可选视口 X 坐标"
                    },
                    "coordinate_y": {
                      "type": "integer",
                      "description": "click 动作的可选视口 Y 坐标"
                    },
                    "direction": {
                      "type": "string",
                      "description": "scroll 方向：up 或 down"
                    },
                    "amount": {
                      "type": "integer",
                      "description": "scroll 像素数，默认 600"
                    },
                    "script": {
                      "type": "string",
                      "description": "execute_js 要执行的 JavaScript；需要返回值时使用 return"
                    },
                    "wait_ms": {
                      "type": "integer",
                      "description": "wait 动作等待毫秒数，范围 0-10000"
                    },
                    "full_page": {
                      "type": "boolean",
                      "description": "screenshot 或 understand_screenshot 是否尝试截取完整页面；超长页面会安全截断"
                    },
                    "analyze": {
                      "type": "boolean",
                      "description": "screenshot 为 true 时，截图后继续调用图片理解模型；等价于 understand_screenshot"
                    },
                    "question": {
                      "type": "string",
                      "description": "understand_screenshot 或 analyze=true 时向图片理解模型提出的问题"
                    },
                    "max_depth": {
                      "type": "integer",
                      "description": "get_backbone 返回页面结构时的最大元素数量，默认 80"
                    },
                    "max_chars": {
                      "type": "integer",
                      "description": "get_html 返回的最大源码字符数，默认 60000，最大 120000"
                    },
                    "max_results": {
                      "type": "integer",
                      "description": "get_links 返回的最大 URL 数量，默认 200，最大 500"
                    }
                  },
                  "required": ["action"]
                }
            """.trimIndent()
        ),
        BuiltinToolSpec(
            id = "get_date_time",
            name = "获取日期时间",
            description = "获取当前的日期、时间、星期等信息。",
            parametersJson = params(
                mapOf("timezone" to mapOf("type" to "string", "description" to "时区，如 Asia/Shanghai"))
            )
        ),
        BuiltinToolSpec(
            id = "http_get",
            name = "HTTP GET 请求",
            description = "向指定 URL 发起 GET 请求，返回响应内容。",
            parametersJson = params(
                mapOf(
                    "url" to mapOf("type" to "string", "description" to "目标 URL"),
                    "headers" to mapOf("type" to "object", "description" to "请求头")
                ),
                listOf("url")
            )
        ),
        BuiltinToolSpec(
            id = "understand_image",
            name = "图片理解",
            description = "使用视觉模型识别图片内容并描述。支持工作区内的图片文件（通过附件名或 workspace_list_files 查询）、http URL 或 data URI。",
            parametersJson = params(
                mapOf(
                    "image_url" to mapOf("type" to "string", "description" to "图片路径或 URL。优先使用工作区内文件名（如 photo.png），也支持 http(s) URL 和 data URI。可通过 workspace_list_files 查看工作区内可用文件。"),
                    "question" to mapOf("type" to "string", "description" to "针对图片的问题，如“这张图片里有什么？”")
                ),
                listOf("image_url")
            )
        ),
        BuiltinToolSpec(
            id = "save_to_memory",
            name = "保存到记忆",
            description = "将指定内容保存到角色长期记忆中。",
            parametersJson = params(
                mapOf(
                    "key" to mapOf("type" to "string", "description" to "记忆键"),
                    "content" to mapOf("type" to "string", "description" to "记忆内容")
                ),
                listOf("key", "content")
            )
        ),
        BuiltinToolSpec(
            id = "read_memory",
            name = "读取记忆",
            description = "从角色长期记忆中读取指定键的内容。",
            parametersJson = params(
                mapOf("key" to mapOf("type" to "string", "description" to "记忆键")),
                listOf("key")
            )
        ),
        BuiltinToolSpec(
            id = "exec_command",
            name = "在 Linux 沙盒执行命令",
            description = "在共享的 Alpine Linux 沙盒中执行命令。当前会话工作区挂载为 /workspace；cwd、环境变量、已安装软件和后台进程会在同一会话后续调用中保留。不同会话使用不同 /workspace，但共享 rootfs。高风险命令仍需用户确认。",
            parametersJson = params(
                mapOf(
                    "command" to mapOf("type" to "string", "description" to "要交给 Alpine /bin/sh 执行的命令"),
                    "timeout" to mapOf("type" to "integer", "description" to "超时秒数，范围 1-600，默认 30")
                ),
                listOf("command")
            ),
            implementationJson = "{\"requires_confirmation\":true,\"ttl_seconds\":600}"
        ),
        BuiltinToolSpec(
            id = "download_file",
            name = "下载文件",
            description = "从指定 URL 下载文件到本地。",
            parametersJson = params(
                mapOf(
                    "url" to mapOf("type" to "string", "description" to "文件 URL"),
                    "save_path" to mapOf("type" to "string", "description" to "本地保存路径")
                ),
                listOf("url")
            )
        ),
        BuiltinToolSpec(
            id = "send_message",
            name = "发送消息",
            description = "向指定会话或频道发送消息。",
            parametersJson = params(
                mapOf(
                    "target" to mapOf("type" to "string", "description" to "目标会话/频道 ID"),
                    "content" to mapOf("type" to "string", "description" to "消息内容")
                ),
                listOf("target", "content")
            )
        ),
        BuiltinToolSpec(
            id = "get_session_thinking_history",
            name = "获取会话思考历史",
            description = "获取当前会话中模型的思考过程历史记录。",
            parametersJson = params(
                mapOf("limit" to mapOf("type" to "integer", "description" to "返回条数"))
            )
        )
    )

    private val workspaceTools = listOf(
        BuiltinToolSpec(
            id = "workspace_create_file",
            name = "工作区-创建文件",
            description = "在当前会话工作区中创建文件。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件相对路径"),
                    "content" to mapOf("type" to "string", "description" to "文件内容")
                ),
                listOf("path", "content")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_read_file",
            name = "工作区-读取文件",
            description = "读取工作区中指定文件的内容。支持按行范围读取（start_line/end_line，1-based 含两端）和限制返回字符数（max_chars，默认 30000）。若文件较大，建议先按行范围分片读取。返回值含 truncated 字段标识是否因 max_chars 截断，total_chars/total_lines 为完整文件大小。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件相对路径"),
                    "start_line" to mapOf("type" to "integer", "description" to "起始行号（1-based，含），默认 1"),
                    "end_line" to mapOf("type" to "integer", "description" to "结束行号（1-based，含），默认读到末尾"),
                    "max_chars" to mapOf("type" to "integer", "description" to "最多返回的字符数，默认 30000；超出会被截断并置 truncated=true")
                ),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_edit_file",
            name = "工作区-编辑文件",
            description = "编辑工作区中已存在的文件内容。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件相对路径"),
                    "content" to mapOf("type" to "string", "description" to "新文件内容")
                ),
                listOf("path", "content")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_delete_file",
            name = "工作区-删除文件",
            description = "删除工作区中的指定文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件相对路径")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_list_files",
            name = "工作区-列出文件",
            description = "列出工作区根目录或指定子目录下的文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "目录路径，默认根目录"))
            )
        ),
        BuiltinToolSpec(
            id = "workspace_send_file",
            name = "工作区-发送文件",
            description = "将工作区中的文件作为附件发送到当前会话。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件相对路径")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_parse_file",
            name = "工作区-解析文件",
            description = "解析工作区中文件的内容（如 PDF/DOCX/TXT），返回文本。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件相对路径")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_extract_epub",
            name = "工作区-提取 EPUB",
            description = "按小说阅读顺序提取工作区中的 EPUB 正文，生成 UTF-8 TXT 到工作区。成功时返回 TXT 的相对路径和真实绝对路径。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "EPUB 文件的工作区相对路径"),
                    "output_path" to mapOf("type" to "string", "description" to "可选的 TXT 输出相对路径，默认与 EPUB 同目录同名")
                ),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_file_info",
            name = "工作区-文件信息",
            description = "获取工作区文件的元信息（大小/类型/修改时间）。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件相对路径")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_skill_copy",
            name = "工作区-技能复制",
            description = "复制一个已有技能的定义到工作区进行编辑。",
            parametersJson = params(
                mapOf("skill_id" to mapOf("type" to "string", "description" to "要复制的技能 ID")),
                listOf("skill_id")
            )
        )
    )

    /** 全部内置工具列表。 */
    val all: List<BuiltinToolSpec> = standardTools + workspaceTools

    /** 判断 id 是否为内置工具。 */
    fun isBuiltin(id: String): Boolean = all.any { it.id == id }
}
