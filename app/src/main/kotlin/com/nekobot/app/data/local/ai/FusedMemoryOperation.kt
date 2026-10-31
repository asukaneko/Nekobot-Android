package com.nekobot.app.data.local.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.text.Normalizer

/** 调用级编号，只存在于本次调用的内存中。失败add仍占自己的N号。 */
internal class FusedMemoryReferences(sources: List<LocalMessageEntity> = emptyList(), existing: List<CompanionMemoryEntity> = emptyList()) {
    val sources = sources.mapIndexed { index, row -> "S${index + 1}" to row }.toMap()
    private val memories = linkedMapOf<String, String?>()
    private var added = 0
    init { existing.forEach { register(it.id) } }
    @Synchronized fun memoryId(ref: String): String? = memories[ref]
    @Synchronized fun register(id: String): String = memories.entries.firstOrNull { it.key.startsWith("M") && it.value == id }?.key
        ?: "M${memories.keys.count { it.startsWith("M") } + 1}".also { memories[it] = id }
    @Synchronized fun reserveAdd(): String = "N${++added}".also { memories[it] = null }
    @Synchronized fun bind(ref: String, id: String?) { memories[ref] = id }
    @Synchronized fun entries(): Map<String, String?> = memories.toMap()
}

internal data class FusedMemoryOperation(
    val op: String, val target: String?, val category: String?, val content: String,
    val evidence: List<String>, val timeText: String, val modelDate: String, val dateType: String,
    val tags: List<String>, val doubt: String?, val kind: String? = null
)

internal sealed interface FusedMemoryLine {
    data object Done : FusedMemoryLine
    data class Operation(val value: FusedMemoryOperation) : FusedMemoryLine
    data class Rejected(val reason: String, val ref: String? = null, val add: Boolean = false) : FusedMemoryLine
}

internal data class ValidFusedMemoryOperation(val operation: FusedMemoryOperation, val sources: List<LocalMessageEntity>,
    val target: CompanionMemoryEntity?, val doubt: CompanionMemoryEntity?, val category: String)

internal object FusedMemoryValidation {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val operations = setOf("add", "update", "cancel", "complete", "retract")
    fun parseLine(line: String): FusedMemoryLine {
        var add = false
        return try {
            val obj = gson.fromJson(line, JsonObject::class.java) ?: error("空对象")
            if (obj.has("done")) {
                require(obj.size() == 1 && obj["done"].isJsonPrimitive && obj["done"].asJsonPrimitive.isBoolean && obj["done"].asBoolean)
                return FusedMemoryLine.Done
            }
            fun text(key: String, required: Boolean = false): String {
                val value = obj[key]
                if (value == null) { require(!required); return "" }
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                return value.asString
            }
            fun strings(key: String, required: Boolean = false): List<String> {
                val value = obj[key]
                if (value == null) { require(!required); return emptyList() }
                require(value.isJsonArray)
                return value.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
            }
            val op = text("op", true)
            add = op == "add"
            require(op in operations)
            val target = text("target").takeIf(String::isNotBlank)
            require(if (add) !obj.has("target") else target != null)
            val category = text("category", op in setOf("add", "update")).takeIf(String::isNotBlank)
            require(category == null || category in FusedCompanionPolicy.categories)
            val content = text("content", op in setOf("add", "update"))
            require(op !in setOf("add", "update") || content.isNotBlank())
            val evidence = strings("evidence", true)
            require(evidence.isNotEmpty())
            val dateType = text("date_type", op in setOf("add", "update")).ifBlank { "day" }
            require(dateType in setOf("day", "habit"))
            val kind = text("kind").takeIf(String::isNotBlank)
            require(kind == null || kind in setOf("core", "fact", "task", "birthday", "episode"))
            FusedMemoryLine.Operation(FusedMemoryOperation(op, target, category, content, evidence,
                text("time_text"), text("model_date"), dateType, strings("tags"), text("doubt").takeIf(String::isNotBlank), kind))
        } catch (_: Exception) { FusedMemoryLine.Rejected("bad_format", add = add) }
    }

    fun validate(op: FusedMemoryOperation, refs: FusedMemoryReferences, memories: Map<String, CompanionMemoryEntity>,
        tool: Boolean = false): Pair<ValidFusedMemoryOperation?, FusedMemoryLine.Rejected?> {
        fun bad(reason: String, ref: String? = null) = null to FusedMemoryLine.Rejected(reason, ref)
        if (tool && op.evidence != listOf("S1")) return bad("bad_format")
        for (ref in op.evidence) {
            if (ref.matches(Regex("R[1-9][0-9]*"))) return bad("reference_not_evidence", ref)
            if (refs.sources[ref] == null) return bad("unknown_ref", ref)
        }
        for (ref in listOfNotNull(op.target, op.doubt)) {
            if (tool && !ref.matches(Regex("M[1-9][0-9]*"))) return bad("unknown_ref", ref)
            val id = refs.memoryId(ref) ?: return bad("unknown_ref", ref)
            val target = memories[id] ?: return bad("unknown_ref", ref)
            if (!target.valid || !target.current || target.voided) return bad("target_voided", ref)
        }
        val target = op.target?.let { memories[refs.memoryId(it)] }
        val category = op.category ?: target?.category ?: return bad("bad_format")
        val sources = op.evidence.distinct().map { refs.sources.getValue(it) }
        if (category == "user_profile" && sources.any { it.role == "assistant" }) return bad("user_profile_from_assistant")
        return ValidFusedMemoryOperation(op, sources, target, op.doubt?.let { memories[refs.memoryId(it)] }, category) to null
    }

    fun normalized(content: String) = Normalizer.normalize(content, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
    fun opKey(scope: String, op: FusedMemoryOperation, targetId: String?, sources: List<String>): String =
        CompanionMemoryPolicy.digest(listOf(scope, op.op, targetId.orEmpty(), sources.sorted().distinct().joinToString("\u0000"),
            normalized(op.content)).joinToString("\u0001"))
}
