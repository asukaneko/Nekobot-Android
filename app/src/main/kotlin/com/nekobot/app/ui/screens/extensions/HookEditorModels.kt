package com.nekobot.app.ui.screens.extensions

import androidx.annotation.StringRes
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.R

/**
 * Hook 条件 / 动作的界面化编辑模型。
 *
 * 目的：让用户不必手写 JSON 就能配置 Hook。这里只做「JSON ↔ 结构化草稿」的纯数据转换，
 * 不依赖 Compose，便于单元测试。
 *
 * 兼容策略：界面里没有对应控件的键不会被丢弃——条件里未知的键进入 [HookConditionsDraft.extras]，
 * 动作里未知的键进入 [HookActionDraft.extra]，保存时原样写回。
 */

// ==================== 条件 ====================

/** 条件涉及的字段（用于界面展示，实际键名见 [HookConditionCatalog]）。 */
enum class HookConditionField(@StringRes val labelRes: Int) {
    AFFECTION(R.string.hook_field_affection),
    TRUST(R.string.hook_field_trust),
    FAMILIARITY(R.string.hook_field_familiarity),
    DEPENDENCY(R.string.hook_field_dependency),
    SECURITY(R.string.hook_field_security),
    JEALOUSY(R.string.hook_field_jealousy),
    ENERGY(R.string.hook_field_energy),
    MOOD(R.string.hook_field_mood),
    MODEL(R.string.hook_field_model),
    USER_MESSAGE(R.string.hook_field_user_message),
    ASSISTANT_MESSAGE(R.string.hook_field_assistant_message),
    TOOL_NAME(R.string.hook_field_tool_name),
    TOOL_SUCCESS(R.string.hook_field_tool_success),
    SUBAGENT(R.string.hook_field_subagent),
    ITERATION(R.string.hook_field_iteration)
}

/** 比较方式。 */
enum class HookConditionOp(@StringRes val labelRes: Int) {
    GTE(R.string.hook_op_gte),
    LTE(R.string.hook_op_lte),
    EQUALS(R.string.hook_op_equals),
    CONTAINS(R.string.hook_op_contains),
    IN(R.string.hook_op_in)
}

/** 值输入类型。 */
enum class HookValueKind { NUMBER, TEXT, BOOLEAN, STRING_LIST }

/** 一条可用条件：JSON 键 = 字段 + 比较方式，例如 affection_gte / tool_name_in。 */
data class HookConditionSpec(
    val key: String,
    val field: HookConditionField,
    val op: HookConditionOp,
    val kind: HookValueKind
)

object HookConditionCatalog {

    val specs: List<HookConditionSpec> = listOf(
        // 关系 / 状态
        num("affection_gte", HookConditionField.AFFECTION, HookConditionOp.GTE),
        num("affection_lte", HookConditionField.AFFECTION, HookConditionOp.LTE),
        num("trust_gte", HookConditionField.TRUST, HookConditionOp.GTE),
        num("trust_lte", HookConditionField.TRUST, HookConditionOp.LTE),
        num("familiarity_gte", HookConditionField.FAMILIARITY, HookConditionOp.GTE),
        num("familiarity_lte", HookConditionField.FAMILIARITY, HookConditionOp.LTE),
        num("dependency_gte", HookConditionField.DEPENDENCY, HookConditionOp.GTE),
        num("dependency_lte", HookConditionField.DEPENDENCY, HookConditionOp.LTE),
        num("security_gte", HookConditionField.SECURITY, HookConditionOp.GTE),
        num("security_lte", HookConditionField.SECURITY, HookConditionOp.LTE),
        num("jealousy_gte", HookConditionField.JEALOUSY, HookConditionOp.GTE),
        num("jealousy_lte", HookConditionField.JEALOUSY, HookConditionOp.LTE),
        num("energy_gte", HookConditionField.ENERGY, HookConditionOp.GTE),
        num("energy_lte", HookConditionField.ENERGY, HookConditionOp.LTE),
        HookConditionSpec("mood_equals", HookConditionField.MOOD, HookConditionOp.EQUALS, HookValueKind.TEXT),
        // 消息 / 模型
        HookConditionSpec("message_contains", HookConditionField.USER_MESSAGE, HookConditionOp.CONTAINS, HookValueKind.TEXT),
        HookConditionSpec("response_contains", HookConditionField.ASSISTANT_MESSAGE, HookConditionOp.CONTAINS, HookValueKind.TEXT),
        HookConditionSpec("model_equals", HookConditionField.MODEL, HookConditionOp.EQUALS, HookValueKind.TEXT),
        // Agent 工具循环
        HookConditionSpec("iteration_gte", HookConditionField.ITERATION, HookConditionOp.GTE, HookValueKind.NUMBER),
        HookConditionSpec("iteration_lte", HookConditionField.ITERATION, HookConditionOp.LTE, HookValueKind.NUMBER),
        HookConditionSpec("tool_name_equals", HookConditionField.TOOL_NAME, HookConditionOp.EQUALS, HookValueKind.TEXT),
        HookConditionSpec("tool_name_in", HookConditionField.TOOL_NAME, HookConditionOp.IN, HookValueKind.STRING_LIST),
        HookConditionSpec("tool_name_contains", HookConditionField.TOOL_NAME, HookConditionOp.CONTAINS, HookValueKind.TEXT),
        HookConditionSpec("tool_success", HookConditionField.TOOL_SUCCESS, HookConditionOp.EQUALS, HookValueKind.BOOLEAN),
        HookConditionSpec("subagent", HookConditionField.SUBAGENT, HookConditionOp.EQUALS, HookValueKind.BOOLEAN)
    )

    private fun num(key: String, field: HookConditionField, op: HookConditionOp) =
        HookConditionSpec(key, field, op, HookValueKind.NUMBER)

    fun byKey(key: String): HookConditionSpec? = specs.firstOrNull { it.key == key }
}

/** 一条条件草稿。 */
data class HookConditionDraft(
    val spec: HookConditionSpec,
    val value: String = ""
)

/** 条件集合草稿：[extras] 保存界面未覆盖的原始条件键。 */
data class HookConditionsDraft(
    val rows: List<HookConditionDraft> = emptyList(),
    val extras: JsonObject = JsonObject()
) {
    val isEmpty: Boolean get() = rows.isEmpty() && extras.size() == 0
}

/** 解析既有条件 JSON；无法识别的键原样保留。 */
fun parseConditions(json: JsonElement?): HookConditionsDraft {
    if (json == null || json.isJsonNull || !json.isJsonObject) return HookConditionsDraft()
    val rows = mutableListOf<HookConditionDraft>()
    val extras = JsonObject()
    for ((key, value) in json.asJsonObject.entrySet()) {
        val spec = HookConditionCatalog.byKey(key)
        if (spec == null) {
            extras.add(key, value)
        } else {
            rows.add(HookConditionDraft(spec, conditionValueToText(spec.kind, value)))
        }
    }
    return HookConditionsDraft(rows, extras)
}

/** 序列化为 `{"affection_gte": 80, ...}`；无内容时返回 null。 */
fun HookConditionsDraft.toJson(): JsonElement? {
    val obj = JsonObject()
    for ((key, value) in extras.entrySet()) obj.add(key, value)
    for (row in rows) {
        val text = row.value.trim()
        when (row.spec.kind) {
            HookValueKind.NUMBER -> {
                val number = text.toDoubleOrNull() ?: continue
                if (number == number.toLong().toDouble()) obj.addProperty(row.spec.key, number.toLong())
                else obj.addProperty(row.spec.key, number)
            }
            HookValueKind.TEXT -> if (text.isNotEmpty()) obj.addProperty(row.spec.key, text)
            HookValueKind.BOOLEAN -> obj.addProperty(row.spec.key, text.toBoolean())
            HookValueKind.STRING_LIST -> {
                val items = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) {
                    obj.add(row.spec.key, JsonArray().apply { items.forEach { add(it) } })
                }
            }
        }
    }
    return if (obj.size() == 0) null else obj
}

private fun conditionValueToText(kind: HookValueKind, value: JsonElement): String = when (kind) {
    HookValueKind.NUMBER, HookValueKind.TEXT -> value.takeUnless { it.isJsonNull }?.asString.orEmpty()
    HookValueKind.BOOLEAN -> value.takeUnless { it.isJsonNull }?.asBoolean?.toString() ?: "false"
    HookValueKind.STRING_LIST -> if (value.isJsonArray) {
        value.asJsonArray.joinToString(",") { it.takeUnless { it.isJsonNull }?.asString.orEmpty() }
    } else {
        value.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }
}

// ==================== 动作 ====================

/** 界面可编辑的动作类型（与 HookExecutor 的 action 分派一致）。 */
enum class HookActionType(val id: String, @StringRes val labelRes: Int) {
    LOG("log", R.string.hook_action_log),
    NOTIFY("notify", R.string.hook_action_notify),
    MEMORY_WRITE("memory_write", R.string.hook_action_memory_write),
    PROMPT_INJECT("prompt_inject", R.string.hook_action_prompt_inject),
    STATE_DELTA("state_delta", R.string.hook_action_state_delta),
    RELATIONSHIP_DELTA("relationship_delta", R.string.hook_action_relationship_delta),
    MESSAGE("message", R.string.hook_action_message),
    WORLD_BOOK_ADD("world_book_add", R.string.hook_action_world_book_add),
    TOOL_BLOCK("tool_block", R.string.hook_action_tool_block);

    companion object {
        fun fromId(id: String): HookActionType? = entries.firstOrNull { it.id == id }
    }
}

/** 记忆类别（与 AutoMemory 的 memoryfs 路径规范一致）。 */
val HookMemoryCategories: List<String> = listOf(
    "user_persona", "character_persona", "important_event", "timeline", "life_sim", "recent_digest", "legacy"
)

/** 动作草稿：所有类型的参数放在同一个数据类里，避免十几个子类在 UI 层来回判断。 */
data class HookActionDraft(
    val type: String,
    // log
    val level: String = "info",
    // log / notify / message
    val title: String = "",
    val message: String = "",
    // memory_write / prompt_inject / message / world_book_add
    val content: String = "",
    val key: String = "",
    val scope: String = "turn",
    val priority: String = "",
    val category: String = "",
    val summary: String = "",
    val importance: String = "",
    val memType: String = "long",
    // state_delta
    val mood: String = "",
    val energyDelta: String = "",
    val moodIntensityDelta: String = "",
    // relationship_delta
    val affectionDelta: String = "",
    val trustDelta: String = "",
    val familiarityDelta: String = "",
    val dependencyDelta: String = "",
    val securityDelta: String = "",
    val jealousyDelta: String = "",
    // world_book_add
    val name: String = "",
    val keywords: String = "",
    val alwaysOn: Boolean = false,
    // tool_block
    val reason: String = "",
    /** 界面未覆盖的原始参数键，保存时原样写回。 */
    val extra: JsonObject? = null
)

/** 动作类型的默认草稿（新增动作、切换类型时使用）。 */
fun defaultActionDraft(type: String): HookActionDraft {
    val draft = HookActionDraft(type = type)
    return when (type) {
        HookActionType.LOG.id -> draft.copy(level = "info")
        HookActionType.NOTIFY.id -> draft
        HookActionType.MEMORY_WRITE.id -> draft.copy(category = "character_persona", importance = "0.7", memType = "long")
        HookActionType.PROMPT_INJECT.id -> draft.copy(priority = "55", scope = "turn")
        HookActionType.WORLD_BOOK_ADD.id -> draft.copy(priority = "50")
        else -> draft
    }
}

/** 解析既有动作数组。 */
fun parseActions(list: List<JsonElement>): List<HookActionDraft> =
    list.mapNotNull { parseAction(it) }

fun parseAction(element: JsonElement): HookActionDraft? {
    if (!element.isJsonObject) return null
    val obj = element.asJsonObject
    val type = obj.str("type").trim()
    if (type.isBlank()) return null
    // state_delta / relationship_delta 允许 {payload: {...}} 形式，读取时取扁平或 payload 内的值
    val payload = obj.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
    val deltaSource = payload ?: obj
    val extra = JsonObject()
    for ((key, value) in obj.entrySet()) {
        if (key == "type" || key in KnownActionKeys) continue
        extra.add(key, value)
    }
    return HookActionDraft(
        type = type,
        level = obj.str("level").ifBlank { "info" },
        title = obj.str("title"),
        message = obj.str("message"),
        content = obj.str("content"),
        key = obj.str("key"),
        scope = obj.str("scope").ifBlank { "turn" },
        priority = obj.str("priority"),
        category = obj.str("category"),
        summary = obj.str("summary"),
        importance = obj.str("importance"),
        memType = obj.str("mem_type").ifBlank { "long" },
        mood = deltaSource.str("mood"),
        energyDelta = deltaSource.str("energy"),
        moodIntensityDelta = deltaSource.str("mood_intensity"),
        affectionDelta = deltaSource.str("affection"),
        trustDelta = deltaSource.str("trust"),
        familiarityDelta = deltaSource.str("familiarity"),
        dependencyDelta = deltaSource.str("dependency"),
        securityDelta = deltaSource.str("security"),
        jealousyDelta = deltaSource.str("jealousy"),
        name = obj.str("name"),
        keywords = keywordsToText(obj.get("keywords")),
        alwaysOn = obj.get("always_on")?.takeUnless { it.isJsonNull }?.asBoolean == true,
        reason = obj.str("reason"),
        extra = extra.takeIf { it.size() > 0 }
    )
}

/** 序列化单条动作。 */
fun HookActionDraft.toJson(): JsonObject {
    val obj = JsonObject()
    extra?.entrySet()?.forEach { (key, value) -> if (key != "type") obj.add(key, value) }
    obj.addProperty("type", type)
    when (type) {
        HookActionType.LOG.id -> {
            obj.addProperty("level", level.ifBlank { "info" })
            message.takeIf { it.isNotBlank() }?.let { obj.addProperty("message", it) }
        }
        HookActionType.NOTIFY.id -> {
            title.takeIf { it.isNotBlank() }?.let { obj.addProperty("title", it) }
            message.takeIf { it.isNotBlank() }?.let { obj.addProperty("message", it) }
        }
        HookActionType.MEMORY_WRITE.id -> {
            category.takeIf { it.isNotBlank() }?.let { obj.addProperty("category", it) }
            title.takeIf { it.isNotBlank() }?.let { obj.addProperty("title", it) }
            summary.takeIf { it.isNotBlank() }?.let { obj.addProperty("summary", it) }
            content.takeIf { it.isNotBlank() }?.let { obj.addProperty("content", it) }
            importance.trim().toDoubleOrNull()?.let { obj.addProperty("importance", it) }
            memType.takeIf { it.isNotBlank() }?.let { obj.addProperty("mem_type", it) }
        }
        HookActionType.PROMPT_INJECT.id -> {
            content.takeIf { it.isNotBlank() }?.let { obj.addProperty("content", it) }
            key.takeIf { it.isNotBlank() }?.let { obj.addProperty("key", it) }
            priority.trim().toDoubleOrNull()?.let { obj.addProperty("priority", it.toInt()) }
            scope.takeIf { it.isNotBlank() }?.let { obj.addProperty("scope", it) }
        }
        HookActionType.STATE_DELTA.id -> {
            mood.takeIf { it.isNotBlank() }?.let { obj.addProperty("mood", it) }
            obj.putNumber("energy", energyDelta)
            obj.putNumber("mood_intensity", moodIntensityDelta)
        }
        HookActionType.RELATIONSHIP_DELTA.id -> {
            obj.putNumber("affection", affectionDelta)
            obj.putNumber("trust", trustDelta)
            obj.putNumber("familiarity", familiarityDelta)
            obj.putNumber("dependency", dependencyDelta)
            obj.putNumber("security", securityDelta)
            obj.putNumber("jealousy", jealousyDelta)
        }
        HookActionType.MESSAGE.id -> {
            content.takeIf { it.isNotBlank() }?.let { obj.addProperty("content", it) }
        }
        HookActionType.WORLD_BOOK_ADD.id -> {
            name.takeIf { it.isNotBlank() }?.let { obj.addProperty("name", it) }
            content.takeIf { it.isNotBlank() }?.let { obj.addProperty("content", it) }
            val items = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                obj.add("keywords", JsonArray().apply { items.forEach { add(it) } })
            }
            priority.trim().toDoubleOrNull()?.let { obj.addProperty("priority", it.toInt()) }
            if (alwaysOn) obj.addProperty("always_on", true)
        }
        HookActionType.TOOL_BLOCK.id -> {
            reason.takeIf { it.isNotBlank() }?.let { obj.addProperty("reason", it) }
        }
        else -> {
            // 未知类型：保留原始参数（已在 extra 中回填），只补 type
        }
    }
    return obj
}

/** 条件/动作 JSON 的展示格式化（JSON 模式用）。 */
private val prettyGson = GsonBuilder().setPrettyPrinting().create()

fun conditionsToPrettyText(draft: HookConditionsDraft): String =
    prettyGson.toJson(draft.toJson() ?: JsonObject())

fun actionsToPrettyText(drafts: List<HookActionDraft>): String =
    prettyGson.toJson(JsonArray().apply { drafts.forEach { add(it.toJson()) } })

private fun JsonObject.str(key: String): String =
    get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.putNumber(key: String, text: String) {
    val number = text.trim().toDoubleOrNull() ?: return
    if (number == number.toLong().toDouble()) addProperty(key, number.toLong())
    else addProperty(key, number)
}

private fun keywordsToText(element: JsonElement?): String {
    if (element == null || element.isJsonNull) return ""
    return if (element.isJsonArray) {
        element.asJsonArray.joinToString(",") { it.takeUnless { it.isJsonNull }?.asString.orEmpty() }
    } else {
        element.asString
    }
}

/** 界面已覆盖的参数键：解析时这些键由结构化字段承载，其余进入 extra。 */
private val KnownActionKeys = setOf(
    "level", "title", "message", "content", "key", "scope", "priority",
    "category", "summary", "importance", "mem_type",
    "mood", "energy", "mood_intensity",
    "affection", "trust", "familiarity", "dependency", "security", "jealousy",
    "name", "keywords", "always_on", "reason", "payload"
)
