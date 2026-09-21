package com.nekobot.app.data.local.plugin

/**
 * `host.ui.render` 的模板渲染器：Handlebars 常用语法子集，纯 Kotlin 实现，不引入外部依赖。
 *
 * 支持：
 * - `{{path}}`（HTML 转义）、`{{{path}}}` / `{{&path}}`（原样输出）
 * - `{{#if expr}}…{{else}}…{{/if}}`、`{{#unless expr}}`、`{{#with expr}}`
 * - `{{#each list}}…{{this}}/{{@index}}/{{@first}}/{{@last}}/{{@key}}…{{else}}…{{/each}}`
 * - `{{! 注释 }}`、`../` 上级上下文、子表达式 helper：`eq`/`ne`/`gt`/`gte`/`lt`/`lte`/`and`/`or`/`not`/`contains`/`length`
 *
 * 不支持：自定义 helper 注册、partials、`{{#each}}` 的 `as |x|` 语法、`{{~` 空白控制。
 * 渲染失败（语法错误、未知 helper、路径越界）抛 [IllegalArgumentException]，由调用方转成插件错误码。
 */
object PluginTemplateRenderer {

    const val MAX_TEMPLATE_CHARS = 64_000
    const val MAX_OUTPUT_CHARS = 256_000

    fun render(template: String, data: Any?): String {
        require(template.length <= MAX_TEMPLATE_CHARS) {
            "模板最多 $MAX_TEMPLATE_CHARS 个字符"
        }
        val nodes = Parser(template).parse()
        val out = StringBuilder()
        Renderer(out).renderNodes(nodes, Frame(data, null, null, null, false, false))
        val html = out.toString()
        require(html.length <= MAX_OUTPUT_CHARS) {
            "渲染结果最多 $MAX_OUTPUT_CHARS 个字符"
        }
        return html
    }

    // ---- 语法树 ----

    private sealed interface Node {
        data class Text(val value: String) : Node
        data class Output(val expression: Expression, val escaped: Boolean) : Node
        data class Block(
            val kind: String,
            val expression: Expression,
            val children: List<Node>,
            val elseChildren: List<Node>
        ) : Node
    }

    private sealed interface Expression {
        data class Literal(val value: Any?) : Expression
        data class Path(val raw: String, val parents: Int, val segments: List<String>) : Expression
        data class Helper(val name: String, val arguments: List<Expression>) : Expression
    }

    private class Frame(
        val value: Any?,
        val parent: Frame?,
        val index: Int?,
        val key: String?,
        val first: Boolean,
        val last: Boolean
    )

    // ---- 解析 ----

    private class Parser(private val template: String) {
        private var position = 0

        /** [terminator] 为 `else`、`/kind` 或 null（读到模板结尾）。 */
        private class Chunk(val nodes: List<Node>, val terminator: String?)

        fun parse(): List<Node> {
            val chunk = parseNodes(openKind = null)
            require(position >= template.length) { "模板存在未闭合的 {{#…}} 块" }
            return chunk.nodes
        }

        private fun parseNodes(openKind: String?): Chunk {
            val nodes = mutableListOf<Node>()
            while (position < template.length) {
                val start = template.indexOf("{{", position)
                if (start < 0) {
                    nodes += Node.Text(template.substring(position))
                    position = template.length
                    break
                }
                if (start > position) nodes += Node.Text(template.substring(position, start))
                position = start
                if (template.startsWith("{{!--", position)) {
                    val end = template.indexOf("--}}", position)
                    require(end >= 0) { "注释未闭合" }
                    position = end + 4
                    continue
                }
                if (template.startsWith("{{{", position)) {
                    val end = template.indexOf("}}}", position)
                    require(end >= 0) { "{{{…}}} 未闭合" }
                    val body = template.substring(position + 3, end).trim()
                    position = end + 3
                    nodes += Node.Output(parseExpression(body), escaped = false)
                    continue
                }
                val end = template.indexOf("}}", position)
                require(end >= 0) { "{{…}} 未闭合" }
                val body = template.substring(position + 2, end).trim()
                position = end + 2
                when {
                    body.isEmpty() -> Unit
                    body.startsWith("!") -> Unit
                    body.startsWith("&") ->
                        nodes += Node.Output(parseExpression(body.substring(1).trim()), escaped = false)
                    body.startsWith("#") -> {
                        val header = body.substring(1).trim()
                        val kind = header.substringBefore(' ').trim().lowercase()
                        require(kind in BLOCK_KINDS) { "不支持的块语法：{{#$header}}" }
                        val argument = header.substringAfter(' ', "").trim()
                        val first = parseNodes(kind)
                        val elseChildren: List<Node> = if (first.terminator == "else") {
                            val second = parseNodes(kind)
                            require(second.terminator == "/$kind") { "缺少 {{/$kind}}" }
                            second.nodes
                        } else {
                            require(first.terminator == "/$kind") { "缺少 {{/$kind}}" }
                            emptyList()
                        }
                        nodes += Node.Block(
                            kind = kind,
                            expression = parseExpression(argument),
                            children = first.nodes,
                            elseChildren = elseChildren
                        )
                    }
                    body.startsWith("/") -> {
                        val kind = body.substring(1).trim().lowercase()
                        require(openKind != null) { "{{/$kind}} 不在块内" }
                        require(kind == openKind) { "{{/$kind}} 与 {{#$openKind}} 不匹配" }
                        return Chunk(nodes, "/$kind")
                    }
                    body == "else" -> {
                        require(openKind != null) { "{{else}} 不在块内" }
                        return Chunk(nodes, "else")
                    }
                    else -> nodes += Node.Output(parseExpression(body), escaped = true)
                }
            }
            require(openKind == null) { "缺少 {{/$openKind}}" }
            return Chunk(nodes, null)
        }

        private fun parseExpression(raw: String): Expression {
            val text = raw.trim()
            require(text.isNotEmpty()) { "表达式不能为空" }
            if (text.startsWith("(") && text.endsWith(")")) {
                return parseHelper(text.substring(1, text.length - 1).trim())
            }
            // 支持 `{{length items}}` 这类无括号的 helper 调用
            val parts = splitArguments(text)
            if (parts.size > 1 && parts.first().lowercase() in HELPERS) {
                return parseHelper(text)
            }
            return parseTerm(text)
        }

        private fun parseHelper(raw: String): Expression {
            val parts = splitArguments(raw)
            require(parts.isNotEmpty()) { "helper 需要名称" }
            val name = parts.first().lowercase()
            require(name in HELPERS) { "不支持的 helper：${parts.first()}" }
            val arguments = parts.drop(1).map { parseExpression(it) }
            require(arguments.size == HELPERS.getValue(name).second) {
                "helper $name 需要 ${HELPERS.getValue(name).second} 个参数"
            }
            return Expression.Helper(name, arguments)
        }

        /** 按空白切分参数，保留引号内的空格与括号分组。 */
        private fun splitArguments(raw: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var depth = 0
            raw.forEach { char ->
                when {
                    quote != null -> {
                        current.append(char)
                        if (char == quote) quote = null
                    }
                    char == '"' || char == '\'' -> {
                        quote = char
                        current.append(char)
                    }
                    char == '(' -> {
                        depth += 1
                        current.append(char)
                    }
                    char == ')' -> {
                        depth -= 1
                        current.append(char)
                    }
                    char.isWhitespace() && depth == 0 -> {
                        if (current.isNotEmpty()) {
                            parts += current.toString()
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) parts += current.toString()
            require(quote == null) { "表达式引号未闭合" }
            return parts
        }

        private fun parseTerm(raw: String): Expression {
            val text = raw.trim()
            if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) {
                return Expression.Literal(text.substring(1, text.length - 1))
            }
            return when (text) {
                "true" -> Expression.Literal(true)
                "false" -> Expression.Literal(false)
                "null", "undefined" -> Expression.Literal(null)
                else -> {
                    if (text.toDoubleOrNull() != null) return Expression.Literal(text.toDouble())
                    var rest = text
                    var parents = 0
                    while (rest.startsWith("../")) {
                        parents += 1
                        rest = rest.substring(3)
                    }
                    val normalized = rest.removePrefix("this.").removePrefix("./")
                    val segments = if (normalized == "this" || normalized == "." || normalized.isEmpty()) {
                        emptyList()
                    } else {
                        normalized.split('.').filter { it.isNotEmpty() }
                    }
                    Expression.Path(text, parents, segments)
                }
            }
        }
    }

    // ---- 渲染 ----

    private class Renderer(private val out: StringBuilder) {

        fun renderNodes(nodes: List<Node>, frame: Frame) {
            nodes.forEach { node ->
                when (node) {
                    is Node.Text -> out.append(node.value)
                    is Node.Output -> out.append(stringify(evaluate(node.expression, frame), node.escaped))
                    is Node.Block -> renderBlock(node, frame)
                }
            }
        }

        private fun renderBlock(block: Node.Block, frame: Frame) {
            val value = evaluate(block.expression, frame)
            when (block.kind) {
                "if" -> {
                    if (truthy(value)) renderNodes(block.children, frame)
                    else renderNodes(block.elseChildren, frame)
                }
                "unless" -> {
                    if (!truthy(value)) renderNodes(block.children, frame)
                    else renderNodes(block.elseChildren, frame)
                }
                "with" -> {
                    if (truthy(value)) {
                        renderNodes(block.children, Frame(value, frame, null, null, false, false))
                    } else {
                        renderNodes(block.elseChildren, frame)
                    }
                }
                "each" -> renderEach(block, frame, value)
            }
        }

        private fun renderEach(block: Node.Block, frame: Frame, value: Any?) {
            val items: List<Pair<String?, Any?>> = when (value) {
                is List<*> -> value.mapIndexed { index, item -> index.toString() to item }
                is Map<*, *> -> value.entries.map { entry -> entry.key?.toString() to entry.value }
                else -> emptyList()
            }
            if (items.isEmpty()) {
                renderNodes(block.elseChildren, frame)
                return
            }
            items.forEachIndexed { index, (key, item) ->
                val childFrame = Frame(
                    value = item,
                    parent = frame,
                    index = if (value is Map<*, *>) null else index,
                    key = if (value is Map<*, *>) key else null,
                    first = index == 0,
                    last = index == items.lastIndex
                )
                renderNodes(block.children, childFrame)
            }
        }

        private fun evaluate(expression: Expression, frame: Frame): Any? = when (expression) {
            is Expression.Literal -> expression.value
            is Expression.Path -> resolvePath(expression, frame)
            is Expression.Helper -> evaluateHelper(expression, frame)
        }

        private fun resolvePath(path: Expression.Path, frame: Frame): Any? {
            var target: Frame = frame
            repeat(path.parents) { target = target.parent ?: return null }
            val raw = path.raw
            when {
                raw.endsWith("@index") -> return target.index
                raw.endsWith("@key") -> return target.key
                raw.endsWith("@first") -> return target.first
                raw.endsWith("@last") -> return target.last
            }
            var current: Any? = target.value
            path.segments.forEach { segment ->
                current = when (val holder = current) {
                    is Map<*, *> -> holder[segment]
                    is List<*> -> segment.toIntOrNull()?.let { holder.getOrNull(it) }
                    else -> null
                }
            }
            return current
        }

        private fun evaluateHelper(helper: Expression.Helper, frame: Frame): Any? {
            val values = helper.arguments.map { evaluate(it, frame) }
            return when (helper.name) {
                "eq" -> looseEquals(values[0], values[1])
                "ne" -> !looseEquals(values[0], values[1])
                "gt" -> compare(values[0], values[1])?.let { it > 0 } ?: false
                "gte" -> compare(values[0], values[1])?.let { it >= 0 } ?: false
                "lt" -> compare(values[0], values[1])?.let { it < 0 } ?: false
                "lte" -> compare(values[0], values[1])?.let { it <= 0 } ?: false
                "and" -> truthy(values[0]) && truthy(values[1])
                "or" -> truthy(values[0]) || truthy(values[1])
                "not" -> !truthy(values[0])
                "contains" -> when (val holder = values[0]) {
                    is List<*> -> holder.any { looseEquals(it, values[1]) }
                    is Map<*, *> -> holder.containsKey(values[1]?.toString())
                    is String -> holder.contains(stringify(values[1], escaped = false))
                    else -> false
                }
                "length" -> when (val holder = values[0]) {
                    is List<*> -> holder.size
                    is Map<*, *> -> holder.size
                    is String -> holder.length
                    else -> 0
                }
                else -> null
            }
        }

        private fun looseEquals(left: Any?, right: Any?): Boolean {
            if (left == null || right == null) return left == null && right == null
            if (left is Number && right is Number) return left.toDouble() == right.toDouble()
            if (left is Boolean || right is Boolean) return truthy(left) == truthy(right)
            return stringify(left, escaped = false) == stringify(right, escaped = false)
        }

        private fun compare(left: Any?, right: Any?): Int? {
            val l = (left as? Number)?.toDouble() ?: left?.let { stringify(it, false) }?.toDoubleOrNull()
            val r = (right as? Number)?.toDouble() ?: right?.let { stringify(it, false) }?.toDoubleOrNull()
            if (l == null || r == null) return null
            return l.compareTo(r)
        }
    }

    // ---- 取值语义（对齐 Handlebars / JS）----

    internal fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> true
        else -> true
    }

    internal fun stringify(value: Any?, escaped: Boolean): String {
        val text = when (value) {
            null -> ""
            is Boolean -> if (value) "true" else "false"
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Float -> stringify(value.toDouble(), escaped = false)
            is Number -> value.toString()
            is List<*> -> value.joinToString(",") { stringify(it, escaped = false) }
            is Map<*, *> -> "[object Object]"
            else -> value.toString()
        }
        return if (escaped) escapeHtml(text) else text
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#x27;")
                '`' -> append("&#x60;")
                '=' -> append("&#x3D;")
                else -> append(char)
            }
        }
    }

    private val BLOCK_KINDS = setOf("if", "unless", "each", "with")

    /** helper 名称 → 参数个数。 */
    private val HELPERS: Map<String, Pair<String, Int>> = mapOf(
        "eq" to ("eq" to 2),
        "ne" to ("ne" to 2),
        "gt" to ("gt" to 2),
        "gte" to ("gte" to 2),
        "lt" to ("lt" to 2),
        "lte" to ("lte" to 2),
        "and" to ("and" to 2),
        "or" to ("or" to 2),
        "not" to ("not" to 1),
        "contains" to ("contains" to 2),
        "length" to ("length" to 1)
    )
}
