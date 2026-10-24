package com.nekobot.app.data.local

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** Supplied from the existing data catalog; the inspector does not maintain a second table registry. */
internal data class PortableImportContentSpec(
    val categoryId: String,
    val tables: Set<String> = emptySet(),
    val fileRoots: Set<String> = emptySet(),
    /** Detail key to the validated archive entry name (for example the story or credential bundle). */
    val sidecars: Map<String, String> = emptyMap(),
    val singletonJson: Boolean = false
)

internal data class PortableImportFileDescriptor(
    val archivePath: String,
    val categoryId: String,
    val rootId: String
)

/** Actual source contents only. These are neither destination differences nor permissions to restore. */
internal data class PortableImportContent(
    val categoryId: String,
    val detailKey: String,
    val rows: Int = 0,
    val files: Int = 0
)

internal data class PortableImportContentInventory(val contents: List<PortableImportContent>) {
    val categoryIds: Set<String> get() = contents.mapTo(linkedSetOf()) { it.categoryId }
}

/**
 * Pure inspection of already decrypted/validated portable entries. No database, Context, filesystem,
 * credentials, executable tools or network dependency. The existing archive reader remains responsible
 * for format/version, bounded extraction, paths and file hashes before calling this inspector.
 *
 * Values are streamed past and never returned; previews carry counts and catalog keys, not chat text,
 * secrets or model configuration values. Absent/empty tables do not produce selectable content.
 */
internal object PortableImportContentInspector {
    internal fun validateObject(raw: ByteArray) { countSingletonFields(raw) }
    fun inspect(
        catalog: List<PortableImportContentSpec>,
        categoryData: Map<String, ByteArray>,
        files: List<PortableImportFileDescriptor> = emptyList(),
        presentSidecars: Set<String> = emptySet()
    ): PortableImportContentInventory {
        val byId = catalog.associateBy { it.categoryId }
        require(byId.size == catalog.size) { "Duplicate import catalog category" }
        require(categoryData.keys.all(byId::containsKey)) { "Unsupported import category" }
        require(files.map { it.archivePath }.toSet().size == files.size) { "Duplicate archive file" }
        files.forEach { file ->
            require(file.rootId in byId[file.categoryId]?.fileRoots.orEmpty()) { "Unsupported file root" }
        }
        val sidecarNames = catalog.flatMap { it.sidecars.values }
        require(sidecarNames.toSet().size == sidecarNames.size) { "Duplicate import sidecar declaration" }
        require(presentSidecars.all(sidecarNames::contains)) { "Unsupported import sidecar" }
        val fileCounts = files.groupingBy { it.categoryId to it.rootId }.eachCount()
        val contents = buildList {
            catalog.forEach { spec ->
                categoryData[spec.categoryId]?.let { raw ->
                    if (spec.singletonJson) {
                        if (countSingletonFields(raw) > 0) add(PortableImportContent(spec.categoryId, "app_settings", rows = 1))
                    } else {
                        countTableRows(raw, spec.tables).forEach { (table, count) ->
                            if (count > 0) add(PortableImportContent(spec.categoryId, "table:$table", rows = count))
                        }
                    }
                }
                spec.fileRoots.forEach { root ->
                    val count = fileCounts[spec.categoryId to root] ?: 0
                    if (count > 0) add(PortableImportContent(spec.categoryId, "root:$root", files = count))
                }
                spec.sidecars.forEach { (detail, path) ->
                    if (path in presentSidecars) add(PortableImportContent(spec.categoryId, detail, rows = 1))
                }
            }
        }
        return PortableImportContentInventory(contents)
    }

    private fun countTableRows(raw: ByteArray, allowedTables: Set<String>): Map<String, Int> =
        JsonReader(raw.inputStream().reader(Charsets.UTF_8)).use { reader ->
            val counts = linkedMapOf<String, Int>()
            val rootFields = linkedSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(rootFields.add(name)) { "Duplicate category field" }
                if (name != "tables") {
                    skipUniqueValue(reader)
                    continue
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val table = reader.nextName()
                    require(table in allowedTables) { "Unsupported import table" }
                    require(table !in counts) { "Duplicate import table" }
                    var count = 0
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Import row must be an object" }
                        skipUniqueValue(reader)
                        count = Math.addExact(count, 1)
                    }
                    reader.endArray()
                    counts[table] = count
                }
                reader.endObject()
            }
            reader.endObject()
            require("tables" in rootFields && reader.peek() == JsonToken.END_DOCUMENT) { "Invalid category data" }
            counts
        }

    private fun countSingletonFields(raw: ByteArray): Int =
        JsonReader(raw.inputStream().reader(Charsets.UTF_8)).use { reader ->
            val names = linkedSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                require(names.add(reader.nextName())) { "Duplicate settings field" }
                skipUniqueValue(reader)
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Invalid settings data" }
            names.size
        }

    private fun skipUniqueValue(reader: JsonReader, depth: Int = 0) {
        require(depth < 128) { "Import JSON nesting too deep" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val names = linkedSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName())) { "Duplicate import field" }
                    skipUniqueValue(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) skipUniqueValue(reader, depth + 1)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }
}
