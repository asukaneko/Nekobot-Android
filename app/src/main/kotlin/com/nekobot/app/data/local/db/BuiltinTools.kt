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
                      "description": "动作：navigate、new_tab、switch_tab、close_tab、list_tabs、screenshot、understand_screenshot、get_html、get_links、click、type、get_text、get_readable、get_backbone、find_elements、get_page_info、get_cookies、set_cookies、fetch、set_viewport、set_user_agent、scroll_and_collect、wait_for_dom_stable、scroll、execute_js、wait、back、forward、reload"
                    },
                    "tab_id": {
                      "type": "integer",
                      "description": "要操作或切换的标签页 ID；list_tabs 可查看"
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
                    },
                    "keywords": {
                      "type": "string",
                      "description": "get_cookies 按名称过滤的关键词"
                    },
                    "fuzzy": {
                      "type": "boolean",
                      "description": "Cookie 名称是否使用模糊匹配"
                    },
                    "cookies": {
                      "type": "array",
                      "description": "set_cookies 要写入的 Cookie 列表，每项支持 name、value、url、domain、path、secure、http_only",
                      "items": {"type": "object"}
                    },
                    "headers": {
                      "type": "object",
                      "description": "fetch 的附加请求头；Cookie 会自动使用当前浏览器登录态"
                    },
                    "save_path": {
                      "type": "string",
                      "description": "fetch 保存文件名；文件会写入会话 browser 目录"
                    },
                    "viewport_width": {
                      "type": "integer",
                      "description": "set_viewport 的 CSS 宽度"
                    },
                    "viewport_height": {
                      "type": "integer",
                      "description": "set_viewport 的 CSS 高度"
                    },
                    "reset": {
                      "type": "boolean",
                      "description": "set_viewport 是否恢复默认视口"
                    },
                    "user_agent": {
                      "type": "string",
                      "description": "set_user_agent 使用 mobile、desktop 或自定义 User-Agent"
                    },
                    "reload": {
                      "type": "boolean",
                      "description": "修改 User-Agent 后是否刷新页面"
                    },
                    "item_selector": {
                      "type": "string",
                      "description": "scroll_and_collect 用于收集列表项的 CSS 选择器"
                    },
                    "scroll_count": {
                      "type": "integer",
                      "description": "scroll_and_collect 的滚动次数，1-20"
                    },
                    "timeout": {
                      "type": "integer",
                      "description": "wait_for_dom_stable 超时秒数，1-60"
                    }
                  },
                  "required": ["action"]
                }
            """.trimIndent()
        ),
        BuiltinToolSpec(
            id = "plugin_use",
            name = "插件管理",
            description = "管理本地插件，与 browser_use 一样通过 action 驱动不同行为。可以列出和查看已安装插件（list/view），按 plugin.json + main.js 规范直接创建并安装新插件（create），从 https 地址安装插件 ZIP（install_url，需用户确认），修改已安装插件的清单或源码（update），启用/停用（enable/disable）、卸载（uninstall，需用户确认）插件，以及在沙盒中测试运行插件命令（execute）。插件通过注册斜杠命令扩展会话功能，运行在无网络、无文件访问的 WebView 沙盒中，只能使用清单声明的 storage/chat.read/notify/network 权限 API。首次使用请先执行 action=help 阅读插件开发指南。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "动作：list、view、help、create、install_url、update、enable、disable、uninstall、execute"
                    },
                    "plugin_id": {
                      "type": "string",
                      "description": "目标插件 ID；view/update/enable/disable/uninstall/execute 需要"
                    },
                    "manifest_json": {
                      "type": "string",
                      "description": "create/update 时的完整 plugin.json 内容（JSON 文本）"
                    },
                    "main_js": {
                      "type": "string",
                      "description": "create/update 时的入口 main.js 完整源码"
                    },
                    "extra_files_json": {
                      "type": "string",
                      "description": "可选；JSON 对象文本，键为附加文件相对路径，值为文件内容，用于多文件插件"
                    },
                    "url": {
                      "type": "string",
                      "description": "install_url 的插件 ZIP 下载地址，仅支持 https"
                    },
                    "command": {
                      "type": "string",
                      "description": "execute 要测试的命令名（不带 /）"
                    },
                    "args": {
                      "type": "string",
                      "description": "execute 传给插件命令的参数文本"
                    },
                    "max_chars": {
                      "type": "integer",
                      "description": "view 返回源码的最大字符数，默认 20000，最大 120000"
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
            id = "generate_image",
            name = "图片生成",
            description = "根据文本描述生成图片。仅当用户明确要求创建、绘制或生成图片时调用。成功后图片会自动附加到本轮 AI 回复下方；最终回复只需用自然语言说明生成结果，不要输出本地 URI 或 Markdown 图片链接。",
            parametersJson = params(
                mapOf(
                    "prompt" to mapOf("type" to "string", "description" to "图片的详细描述，包含主体、场景、风格、构图和需要避免的内容"),
                    "size" to mapOf("type" to "string", "description" to "可选尺寸：1024x1024（默认）、1792x1024（横向）或 1024x1792（纵向）"),
                    "n" to mapOf("type" to "integer", "description" to "生成数量，范围 1-4，默认 1")
                ),
                listOf("prompt")
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
            id = "file_read",
            name = "读取 Linux 工作区文件",
            description = "读取 /workspace 内的 UTF-8 文本文件。支持相对路径或 /workspace 绝对路径，可按行分片读取，返回完整行数、字符数和截断状态。重要：为节省上下文 token，读取长文本时请优先一次性读取完整内容（设置足够大的 max_chars，如 200000 或 500000），避免多次分片读取导致工具结果在上下文中重复累积。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径，如 /workspace/src/main.py"),
                    "start_line" to mapOf("type" to "integer", "description" to "起始行号，1-based，默认 1"),
                    "end_line" to mapOf("type" to "integer", "description" to "结束行号，1-based，默认读到末尾"),
                    "max_chars" to mapOf("type" to "integer", "description" to "最大返回字符数，默认 100000，最大 500000。读取长文本时建议主动设置足够大的值（如 200000）一次性读完，避免分片读取浪费上下文 token；设为 0 表示不限制")
                ),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "file_write",
            name = "写入 Linux 工作区文件",
            description = "写入 /workspace 内的文本文件，自动创建父目录；append=true 时追加，否则覆盖。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径，如 /workspace/output.txt"),
                    "content" to mapOf("type" to "string", "description" to "要写入的文本"),
                    "append" to mapOf("type" to "boolean", "description" to "是否追加写入，默认 false")
                ),
                listOf("path", "content")
            )
        ),
        BuiltinToolSpec(
            id = "file_edit",
            name = "精确编辑 Linux 工作区文件",
            description = "在 /workspace 文本文件中精确替换 old_string。默认要求旧文本只出现一次；replace_all=true 可替换全部匹配。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "要编辑的文件路径"),
                    "old_string" to mapOf("type" to "string", "description" to "必须精确匹配的原文本"),
                    "new_string" to mapOf("type" to "string", "description" to "替换后的文本"),
                    "replace_all" to mapOf("type" to "boolean", "description" to "是否替换全部匹配，默认 false")
                ),
                listOf("path", "old_string", "new_string")
            )
        ),
        BuiltinToolSpec(
            id = "read_image",
            name = "查看 Linux 工作区图片",
            description = "读取 /workspace 内的图片并调用视觉模型理解内容；也支持 http(s) URL 和 data URI。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "图片路径或 URL"),
                    "question" to mapOf("type" to "string", "description" to "希望视觉模型回答的问题")
                ),
                listOf("path")
            )
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
            description = "在会话工作区或共享工作区中创建文件。path 使用 shared:// 前缀可写入共享工作区（跨会话复用），省略则写入当前会话工作区。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 写入共享工作区，或直接使用相对路径写入当前会话工作区"),
                    "content" to mapOf("type" to "string", "description" to "文件内容")
                ),
                listOf("path", "content")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_read_file",
            name = "工作区-读取文件",
            description = "读取工作区中指定文件的内容。支持按行范围读取（start_line/end_line，1-based 含两端）和限制返回字符数（max_chars，默认 100000，最大 500000）。path 使用 shared:// 前缀可读取共享工作区文件。重要：为节省上下文 token，读取长文本时请优先一次性读取完整内容（设置足够大的 max_chars，如 200000 或 500000，或设为 0 表示不限制），避免多次分片读取导致工具结果在上下文中重复累积。返回值含 truncated 字段标识是否因 max_chars 截断，total_chars/total_lines 为完整文件大小；若 truncated=true，hint 字段会给出一次性读取的建议 max_chars 值。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 读取共享工作区文件，或直接使用相对路径读取当前会话工作区文件"),
                    "start_line" to mapOf("type" to "integer", "description" to "起始行号（1-based，含），默认 1"),
                    "end_line" to mapOf("type" to "integer", "description" to "结束行号（1-based，含），默认读到末尾"),
                    "max_chars" to mapOf("type" to "integer", "description" to "最多返回的字符数，默认 100000，最大 500000；读取长文本时建议主动设置足够大的值一次性读完，设为 0 表示不限制；超出会被截断并置 truncated=true")
                ),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_edit_file",
            name = "工作区-编辑文件",
            description = "编辑工作区中已存在的文件内容。path 使用 shared:// 前缀可编辑共享工作区文件。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 编辑共享工作区文件，或直接使用相对路径编辑当前会话工作区文件"),
                    "content" to mapOf("type" to "string", "description" to "新文件内容")
                ),
                listOf("path", "content")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_delete_file",
            name = "工作区-删除文件",
            description = "删除工作区中的指定文件。path 使用 shared:// 前缀可删除共享工作区文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 删除共享工作区文件，或直接使用相对路径删除当前会话工作区文件")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_list_files",
            name = "工作区-列出文件",
            description = "列出工作区根目录或指定子目录下的文件。path 使用 shared:// 前缀可列出共享工作区文件，传 shared:// 列出共享工作区根目录。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "目录路径。使用 shared:// 列出共享工作区根目录，shared://subdir 列出子目录；省略或直接使用相对路径列出当前会话工作区"))
            )
        ),
        BuiltinToolSpec(
            id = "workspace_send_file",
            name = "工作区-发送文件",
            description = "将工作区中的文件作为附件发送到当前会话。path 使用 shared:// 前缀可发送共享工作区文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 发送共享工作区文件，或直接使用相对路径发送当前会话工作区文件")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_parse_file",
            name = "工作区-解析文件",
            description = "解析工作区中文件的内容（如 PDF/DOCX/TXT），返回文本。path 使用 shared:// 前缀可解析共享工作区文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 解析共享工作区文件，或直接使用相对路径解析当前会话工作区文件")),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_extract_epub",
            name = "工作区-提取 EPUB",
            description = "按小说阅读顺序提取工作区中的 EPUB 正文，生成 UTF-8 TXT 到工作区。成功时返回 TXT 的相对路径和真实绝对路径。path 和 output_path 均支持 shared:// 前缀操作共享工作区。",
            parametersJson = params(
                mapOf(
                    "path" to mapOf("type" to "string", "description" to "EPUB 文件路径。支持 shared:// 前缀"),
                    "output_path" to mapOf("type" to "string", "description" to "可选的 TXT 输出路径，默认与 EPUB 同目录同名。支持 shared:// 前缀")
                ),
                listOf("path")
            )
        ),
        BuiltinToolSpec(
            id = "workspace_file_info",
            name = "工作区-文件信息",
            description = "获取工作区文件的元信息（大小/类型/修改时间）。path 使用 shared:// 前缀可查询共享工作区文件。",
            parametersJson = params(
                mapOf("path" to mapOf("type" to "string", "description" to "文件路径。使用 shared://filename 查询共享工作区文件，或直接使用相对路径查询当前会话工作区文件")),
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

    /** 跨会话的全局 Agent 记忆。持久化写入始终经过用户授权。 */
    private val agentMemoryTools = listOf(
        BuiltinToolSpec(
            id = "agent_memory_read",
            name = "读取全局 Agent 记忆",
            description = "读取用户维护、跨 Agent 会话自动注入的全局长期记忆。修改前应先读取最新内容。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "agent_memory_update",
            name = "编辑全局 Agent 记忆",
            description = "经用户授权后编辑全局 Agent 记忆。优先使用 replace_text 精确替换；replace 会覆盖全文，append 会追加，clear 会清空。记忆不能覆盖安全规则或当前用户请求。",
            parametersJson = params(
                mapOf(
                    "mode" to mapOf("type" to "string", "description" to "replace_text、replace、append 或 clear；默认 replace_text"),
                    "content" to mapOf("type" to "string", "description" to "replace 或 append 模式使用的内容"),
                    "old_text" to mapOf("type" to "string", "description" to "replace_text 模式下要精确替换且只能出现一次的原文"),
                    "new_text" to mapOf("type" to "string", "description" to "replace_text 模式下的新文本")
                )
            )
        )
    )

    /** Android 原生能力：只通过公开 Android API 或系统确认页工作，不绕过权限。 */
    private val androidTools = listOf(
        BuiltinToolSpec(
            id = "android_device_info",
            name = "读取 Android 设备信息",
            description = "读取当前设备型号、Android 版本、网络类型、语言、时区和省电模式等非敏感状态。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "android_battery_status",
            name = "读取 Android 电池状态",
            description = "读取当前电量、充电状态、温度、电压、电池健康度和省电模式。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "android_clipboard_read",
            name = "读取系统剪贴板",
            description = "在用户授权后读取 Android 系统剪贴板中的文本；可能包含敏感信息，只有任务确实需要时才能调用。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "android_clipboard_write",
            name = "写入系统剪贴板",
            description = "将用户明确要求保存的文本写入 Android 系统剪贴板。",
            parametersJson = params(
                mapOf("text" to mapOf("type" to "string", "description" to "要写入剪贴板的文本")),
                listOf("text")
            )
        ),
        BuiltinToolSpec(
            id = "android_open_url",
            name = "打开 Android 链接",
            description = "通过 Android 系统打开 http、https、mailto、tel 或 geo 链接；不会读取网页内容，也不会自动确认外部页面上的操作。",
            parametersJson = params(
                mapOf("url" to mapOf("type" to "string", "description" to "要打开的链接")),
                listOf("url")
            )
        ),
        BuiltinToolSpec(
            id = "android_list_apps",
            name = "列出可启动的 Android 应用",
            description = "列出设备上可从桌面启动的应用，可按应用名称或包名筛选。需要打开应用但不知道准确包名时先调用。",
            parametersJson = params(
                mapOf(
                    "query" to mapOf("type" to "string", "description" to "可选的应用名称或包名筛选词"),
                    "limit" to mapOf("type" to "integer", "description" to "最多返回数量，默认 50，最大 200")
                )
            )
        ),
        BuiltinToolSpec(
            id = "android_open_app",
            name = "打开 Android 应用",
            description = "按应用名称、搜索词或包名启动已安装应用。精确名称/包名会直接启动；结果不唯一时返回候选列表，不会误开其他应用。不会执行目标应用内的后续操作。",
            parametersJson = params(
                mapOf(
                    "package_name" to mapOf("type" to "string", "description" to "可选的 Android 应用包名"),
                    "app_name" to mapOf("type" to "string", "description" to "可选的桌面应用名称"),
                    "query" to mapOf("type" to "string", "description" to "可选的应用名称或包名搜索词")
                )
            )
        ),
        BuiltinToolSpec(
            id = "android_open_settings",
            name = "打开 Android 系统设置",
            description = "打开白名单内的 Android 设置页面：main、wifi、bluetooth、display、sound、battery、location、accessibility、language、app 或 notifications。",
            parametersJson = params(
                mapOf(
                    "target" to mapOf("type" to "string", "description" to "设置页面名称，默认 main"),
                    "package_name" to mapOf("type" to "string", "description" to "target 为 app 或 notifications 时的应用包名")
                )
            )
        ),
        BuiltinToolSpec(
            id = "android_create_calendar_event",
            name = "创建日历事件入口",
            description = "打开 Android 日历的创建事件页面并填入内容；最终保存由系统日历页面和用户确认完成，不直接绕过日历权限。时间使用毫秒时间戳。",
            parametersJson = params(
                mapOf(
                    "title" to mapOf("type" to "string", "description" to "事件标题"),
                    "description" to mapOf("type" to "string", "description" to "事件描述"),
                    "location" to mapOf("type" to "string", "description" to "事件地点"),
                    "start_time" to mapOf("type" to "integer", "description" to "开始时间，Unix 毫秒时间戳"),
                    "end_time" to mapOf("type" to "integer", "description" to "结束时间，Unix 毫秒时间戳"),
                    "all_day" to mapOf("type" to "boolean", "description" to "是否全天事件")
                ),
                listOf("start_time", "end_time")
            )
        ),
        BuiltinToolSpec(
            id = "android_set_alarm",
            name = "设置闹钟入口",
            description = "打开 Android 时钟的设置闹钟页面并填入时间和标签；最终保存由系统时钟页面和用户确认完成。",
            parametersJson = params(
                mapOf(
                    "hour" to mapOf("type" to "integer", "description" to "小时，0-23"),
                    "minute" to mapOf("type" to "integer", "description" to "分钟，0-59"),
                    "message" to mapOf("type" to "string", "description" to "闹钟标签")
                ),
                listOf("hour", "minute")
            )
        ),
        BuiltinToolSpec(
            id = "android_volume",
            name = "读取或设置 Android 音量",
            description = "读取或设置 music、ring、notification、alarm、system、voice_call 音频流的音量。设置前必须确认用户确实要求改变设备音量。",
            parametersJson = params(
                mapOf(
                    "action" to mapOf("type" to "string", "description" to "get 或 set，默认 get"),
                    "stream" to mapOf("type" to "string", "description" to "音频流，默认 music"),
                    "level" to mapOf("type" to "integer", "description" to "set 时的音量等级，范围由设备返回的 max_level 决定")
                )
            )
        ),
        BuiltinToolSpec(
            id = "android_accessibility_status",
            name = "检查 Agent 系统操作权限",
            description = "检查 Nekobot 辅助功能和通知使用权是否已由用户开启并连接。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "android_ui_tree",
            name = "读取当前 Android 界面",
            description = "经用户授权后读取当前窗口的结构化界面树。密码字段始终脱敏，结果限制节点数。",
            parametersJson = params(
                mapOf("max_nodes" to mapOf("type" to "integer", "description" to "最多返回节点数，默认 250，最大 800"))
            )
        ),
        BuiltinToolSpec(
            id = "android_ui_click",
            name = "点击 Android 界面元素",
            description = "经用户授权后，按文字、内容描述或资源 ID 查找并点击当前界面元素。",
            parametersJson = params(
                mapOf(
                    "selector" to mapOf("type" to "string", "description" to "要查找的文字、内容描述或资源 ID"),
                    "field" to mapOf("type" to "string", "description" to "auto、text、description、view_id 或 class"),
                    "exact" to mapOf("type" to "boolean", "description" to "是否要求完整匹配")
                ),
                listOf("selector")
            )
        ),
        BuiltinToolSpec(
            id = "android_ui_set_text",
            name = "向 Android 输入框写入文字",
            description = "经用户授权后，定位可编辑界面元素并写入文字。不会向密码节点回读内容。",
            parametersJson = params(
                mapOf(
                    "selector" to mapOf("type" to "string", "description" to "输入框的文字、描述或资源 ID"),
                    "text" to mapOf("type" to "string", "description" to "要写入的文字"),
                    "field" to mapOf("type" to "string", "description" to "auto、text、description、view_id 或 class"),
                    "exact" to mapOf("type" to "boolean", "description" to "是否要求完整匹配")
                ),
                listOf("selector", "text")
            )
        ),
        BuiltinToolSpec(
            id = "android_ui_scroll",
            name = "滚动 Android 界面",
            description = "经用户授权后滚动当前界面或指定可滚动元素。",
            parametersJson = params(
                mapOf(
                    "direction" to mapOf("type" to "string", "description" to "up、down、forward、backward、left 或 right"),
                    "selector" to mapOf("type" to "string", "description" to "可选的滚动区域选择器"),
                    "field" to mapOf("type" to "string", "description" to "选择器字段，默认 auto"),
                    "exact" to mapOf("type" to "boolean", "description" to "是否要求完整匹配")
                ),
                listOf("direction")
            )
        ),
        BuiltinToolSpec(
            id = "android_global_action",
            name = "执行 Android 全局动作",
            description = "经用户授权后执行返回、主页、最近任务、通知栏或快捷设置等系统动作。",
            parametersJson = params(
                mapOf("action" to mapOf("type" to "string", "description" to "back、home、recents、notifications 或 quick_settings")),
                listOf("action")
            )
        ),
        BuiltinToolSpec(
            id = "android_screenshot",
            name = "截取当前 Android 屏幕",
            description = "经用户授权后通过辅助功能截取当前屏幕，并保存到当前 Agent 会话工作区。Android 11 及以上可用。",
            parametersJson = params(emptyMap())
        ),
        BuiltinToolSpec(
            id = "android_notifications",
            name = "读取 Android 活动通知",
            description = "经用户授权且已开启通知使用权后，读取当前活动通知；可按应用筛选。",
            parametersJson = params(
                mapOf(
                    "package_name" to mapOf("type" to "string", "description" to "可选的应用包名"),
                    "limit" to mapOf("type" to "integer", "description" to "最多返回数量，默认 30"),
                    "include_content" to mapOf("type" to "boolean", "description" to "是否包含通知标题和正文，默认 true")
                )
            )
        ),
        BuiltinToolSpec(
            id = "android_notification_action",
            name = "操作 Android 通知",
            description = "经用户授权后打开、清除通知，或执行通知提供的指定动作。",
            parametersJson = params(
                mapOf(
                    "notification_key" to mapOf("type" to "string", "description" to "android_notifications 返回的通知 key"),
                    "action" to mapOf("type" to "string", "description" to "open、dismiss 或 action"),
                    "action_index" to mapOf("type" to "integer", "description" to "action 模式下的动作序号，默认 0")
                ),
                listOf("notification_key", "action")
            )
        ),
        BuiltinToolSpec(
            id = "android_media_control",
            name = "读取或控制 Android 媒体播放",
            description = "经用户授权且已开启通知使用权后，列出媒体会话或执行播放、暂停、切歌和停止。",
            parametersJson = params(
                mapOf(
                    "action" to mapOf("type" to "string", "description" to "get、play、pause、toggle、next、previous 或 stop"),
                    "package_name" to mapOf("type" to "string", "description" to "可选的目标媒体应用包名")
                )
            )
        )
    )

    /** 全部内置工具列表。 */
    val all: List<BuiltinToolSpec> = standardTools + workspaceTools + agentMemoryTools + androidTools

    /** 判断 id 是否为内置工具。 */
    fun isBuiltin(id: String): Boolean = all.any { it.id == id }
}
