package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 数据库管理页导出的本地图片，以及数据库中引用该图片的原始 URI。 */
internal data class DbProfilePortraitSource(
    val reference: String,
    val file: File
)

/** 数据库 profile 对应的故事地图，以及聊天页仍在使用的剧情选项缓存。 */
internal data class DbProfileStoryData(
    val graphJson: String,
    val plotChoices: Map<String, String> = emptyMap()
)

/** 从数据库备份 ZIP 中提取出的数据库文件与内嵌立绘。 */
internal data class ExtractedDbProfileArchive(
    val main: ByteArray,
    val wal: ByteArray? = null,
    val shm: ByteArray? = null,
    /** ZIP entry -> 图片字节。 */
    val portraits: Map<String, ByteArray> = emptyMap(),
    /** 数据库中的原始 URI -> ZIP entry。 */
    val portraitReferences: Map<String, String> = emptyMap(),
    /** 旧版备份没有该条目时为 null。 */
    val story: DbProfileStoryData? = null
)

/**
 * 数据库 profile 备份 ZIP 编解码器。
 *
 * 数据库仍保留在 ZIP 根目录，以兼容旧版导入；新增的立绘放在 [PORTRAIT_PREFIX] 下，
 * 并通过 manifest 保存完整原 URI 到 ZIP entry 的映射。故事地图放在 [STORY_ENTRY]，
 * 其中只包含目标数据库实际拥有的会话。导入端不能只按文件名匹配立绘，否则不同目录
 * 中的同名图片可能被错误替换。
 */
internal object DbProfileArchiveCodec {
    private const val MANIFEST_VERSION = 1
    private const val STORY_VERSION = 1
    private const val PORTRAIT_PREFIX = "portraits/"
    private const val MANIFEST_ENTRY = "${PORTRAIT_PREFIX}manifest.json"
    private const val STORY_ENTRY = "story/story.json"
    private const val MAX_ENTRY_COUNT = 4_096
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_STORY_BYTES = 16L * 1024 * 1024
    private const val MAX_PORTRAIT_BYTES = 64L * 1024 * 1024
    private const val MAX_DATABASE_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_EXPANDED_BYTES = 768L * 1024 * 1024
    private val gson = Gson()

    private data class PortraitManifest(
        val version: Int = MANIFEST_VERSION,
        val references: Map<String, String>? = emptyMap()
    )

    private data class StoryManifest(
        val version: Int = STORY_VERSION,
        val graphJson: String? = null,
        val plotChoices: Map<String, String>? = emptyMap()
    )

    private data class PreparedPortrait(
        val entryName: String,
        val file: File,
        val references: Set<String>
    )

    /** 将数据库和它实际引用到的本地图片流式写入 ZIP。 */
    fun writeArchive(
        output: OutputStream,
        databaseFiles: List<Pair<String, File>>,
        portraitSources: List<DbProfilePortraitSource>,
        story: DbProfileStoryData? = null
    ) {
        val portraitsByPath = linkedMapOf<String, Pair<File, MutableSet<String>>>()
        portraitSources.forEach { source ->
            require(source.reference.isNotBlank()) { "立绘引用不能为空" }
            require(source.file.isFile) { "立绘文件不存在：${source.file.name}" }
            val canonical = source.file.canonicalFile
            val item = portraitsByPath.getOrPut(canonical.path) {
                canonical to linkedSetOf()
            }
            item.second += source.reference
        }

        val preparedDatabases = databaseFiles.map { (requestedName, file) ->
            require(file.isFile) { "数据库文件不存在：${file.name}" }
            require(file.length() <= MAX_DATABASE_ENTRY_BYTES) { "数据库文件过大：${file.name}" }
            val entryName = requestedName.substringAfterLast('/').substringAfterLast('\\')
            require(entryName.isNotBlank()) { "数据库 ZIP entry 名称无效" }
            entryName to file
        }
        require(preparedDatabases.map { it.first }.distinct().size == preparedDatabases.size) {
            "数据库 ZIP entry 名称重复"
        }
        require(preparedDatabases.count { it.first.endsWith(".db", ignoreCase = true) } == 1) {
            "备份必须包含且只能包含一个主数据库"
        }

        val preparedPortraits = portraitsByPath.values.mapIndexed { index, (file, references) ->
            require(file.length() <= MAX_PORTRAIT_BYTES) { "立绘文件过大：${file.name}" }
            val extension = file.extension
                    .lowercase(Locale.ROOT)
                    .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                    ?.let { ".$it" }
                    .orEmpty()
            PreparedPortrait(
                entryName = "$PORTRAIT_PREFIX${index.toString().padStart(4, '0')}$extension",
                file = file,
                references = references
            )
        }

        val manifestReferences = linkedMapOf<String, String>()
        preparedPortraits.forEach { portrait ->
            portrait.references.forEach { reference ->
                manifestReferences[reference] = portrait.entryName
            }
        }
        val manifestBytes = manifestReferences.takeIf { it.isNotEmpty() }?.let { references ->
            gson.toJson(PortraitManifest(references = references)).toByteArray(Charsets.UTF_8)
                .also { require(it.size <= MAX_MANIFEST_BYTES) { "立绘清单过大" } }
        }
        val storyBytes = story?.let(::prepareStoryBytes)

        val archiveEntryCount = preparedDatabases.size + preparedPortraits.size +
            (if (manifestBytes != null) 1 else 0) + (if (storyBytes != null) 1 else 0)
        require(archiveEntryCount <= MAX_ENTRY_COUNT) { "备份条目过多" }
        var totalBytes = 0L
        (preparedDatabases.map { it.second } + preparedPortraits.map { it.file }).forEach { file ->
            require(file.length() <= MAX_TOTAL_EXPANDED_BYTES - totalBytes) { "备份内容过大" }
            totalBytes += file.length()
        }
        manifestBytes?.let { bytes ->
            require(bytes.size.toLong() <= MAX_TOTAL_EXPANDED_BYTES - totalBytes) { "备份内容过大" }
            totalBytes += bytes.size
        }
        storyBytes?.let { bytes ->
            require(bytes.size.toLong() <= MAX_TOTAL_EXPANDED_BYTES - totalBytes) { "备份内容过大" }
        }

        ZipOutputStream(output).use { zip ->
            preparedDatabases.forEach { (entryName, file) -> putFile(zip, entryName, file) }
            preparedPortraits.forEach { portrait ->
                putFile(zip, portrait.entryName, portrait.file)
            }

            manifestBytes?.let { putBytes(zip, MANIFEST_ENTRY, it) }
            storyBytes?.let { putBytes(zip, STORY_ENTRY, it) }
        }
    }

    /** 测试及小型调用使用的内存版本；正式导出应优先使用 [writeArchive]。 */
    fun createArchive(
        databaseFiles: List<Pair<String, File>>,
        portraitSources: List<DbProfilePortraitSource>,
        story: DbProfileStoryData? = null
    ): ByteArray = ByteArrayOutputStream().also { output ->
        writeArchive(output, databaseFiles, portraitSources, story)
    }.toByteArray()

    /** 提取新版或旧版数据库 ZIP；旧 ZIP 没有 manifest 时图片映射为空。 */
    fun extractArchive(zipBytes: ByteArray): ExtractedDbProfileArchive? =
        ByteArrayInputStream(zipBytes).use(::extractArchive)

    /** 流式读取 ZIP，避免在解压内容之外再保留一份完整压缩包。 */
    fun extractArchive(input: InputStream): ExtractedDbProfileArchive? {
        val rawEntries = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalExpandedBytes = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                require(entryCount <= MAX_ENTRY_COUNT) { "ZIP 条目过多" }
                if (!entry.isDirectory) {
                    val safeName = safeEntryName(entry.name)
                    val entryLimit = safeName?.let(::storedEntryLimit)
                    if (safeName != null && entryLimit != null) {
                        require(!rawEntries.containsKey(safeName)) {
                            "ZIP 中存在重复条目：$safeName"
                        }
                        rawEntries[safeName] = readCurrentEntry(zip, entryLimit) { count ->
                            totalExpandedBytes += count
                            require(totalExpandedBytes <= MAX_TOTAL_EXPANDED_BYTES) {
                                "ZIP 解压后内容过大"
                            }
                        }
                    } else {
                        drainCurrentEntry(zip) { count ->
                            totalExpandedBytes += count
                            require(totalExpandedBytes <= MAX_TOTAL_EXPANDED_BYTES) {
                                "ZIP 解压后内容过大"
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val mainEntries = rawEntries.keys.filter { path ->
            val name = path.substringAfterLast('/')
            name.endsWith(".db", ignoreCase = true) &&
                !name.endsWith(".db-wal", ignoreCase = true) &&
                !name.endsWith(".db-shm", ignoreCase = true)
        }
        if (mainEntries.isEmpty()) return null
        require(mainEntries.size == 1) { "ZIP 中包含多个数据库文件" }
        val mainEntry = mainEntries.single()
        val mainName = mainEntry.substringAfterLast('/')
        fun entryWithBaseName(name: String): ByteArray? = rawEntries.entries
            .firstOrNull { it.key.substringAfterLast('/').equals(name, ignoreCase = true) }
            ?.value

        val manifestBytes = rawEntries[MANIFEST_ENTRY]
        val manifest = manifestBytes?.let { bytes ->
            runCatching<PortraitManifest> {
                gson.fromJson(String(bytes, Charsets.UTF_8), PortraitManifest::class.java)
                    ?: throw IllegalArgumentException("立绘清单为空")
            }.getOrElse { error ->
                throw IllegalArgumentException("立绘清单格式无效", error)
            }
        }
        val references = if (manifest != null) {
            require(manifest.version == MANIFEST_VERSION) {
                "不支持的立绘清单版本：${manifest.version}"
            }
            val manifestReferences = requireNotNull(manifest.references) { "立绘清单缺少引用映射" }
            manifestReferences.map { (reference, rawEntryName) ->
                val entryName = safeEntryName(rawEntryName)
                require(reference.isNotBlank()) { "立绘清单包含空引用" }
                require(
                    entryName != null &&
                        entryName.startsWith(PORTRAIT_PREFIX) &&
                        entryName != MANIFEST_ENTRY
                ) { "立绘清单包含非法路径：$rawEntryName" }
                require(rawEntries.containsKey(entryName)) { "备份包缺少立绘文件：$entryName" }
                reference to entryName
            }.toMap(linkedMapOf())
        } else {
            emptyMap()
        }
        val portraitEntries = references.values.distinct().associateWith { rawEntries.getValue(it) }
        val story = rawEntries[STORY_ENTRY]?.let(::parseStoryBytes)

        return ExtractedDbProfileArchive(
            main = rawEntries.getValue(mainEntry),
            wal = entryWithBaseName("$mainName-wal"),
            shm = entryWithBaseName("$mainName-shm"),
            portraits = portraitEntries,
            portraitReferences = references,
            story = story
        )
    }

    private fun prepareStoryBytes(story: DbProfileStoryData): ByteArray {
        validateJsonObject(story.graphJson, "故事地图")
        story.plotChoices.forEach { (sessionId, choicesJson) ->
            require(sessionId.isNotBlank()) { "剧情选项包含空会话 ID" }
            validateJsonObject(choicesJson, "会话 $sessionId 的剧情选项")
        }
        return gson.toJson(
            StoryManifest(
                graphJson = story.graphJson,
                plotChoices = story.plotChoices.toSortedMap()
            )
        ).toByteArray(Charsets.UTF_8).also { bytes ->
            require(bytes.size <= MAX_STORY_BYTES) { "故事地图过大" }
        }
    }

    private fun parseStoryBytes(bytes: ByteArray): DbProfileStoryData {
        val manifest = runCatching<StoryManifest> {
            gson.fromJson(String(bytes, Charsets.UTF_8), StoryManifest::class.java)
                ?: throw IllegalArgumentException("故事地图清单为空")
        }.getOrElse { error ->
            throw IllegalArgumentException("故事地图清单格式无效", error)
        }
        require(manifest.version == STORY_VERSION) {
            "不支持的故事地图版本：${manifest.version}"
        }
        val graphJson = requireNotNull(manifest.graphJson) { "故事地图清单缺少图谱" }
        validateJsonObject(graphJson, "故事地图")
        val plotChoices = requireNotNull(manifest.plotChoices) { "故事地图清单缺少剧情选项" }
        plotChoices.forEach { (sessionId, choicesJson) ->
            require(sessionId.isNotBlank()) { "剧情选项包含空会话 ID" }
            validateJsonObject(choicesJson, "会话 $sessionId 的剧情选项")
        }
        return DbProfileStoryData(graphJson, plotChoices.toMap(linkedMapOf()))
    }

    private fun validateJsonObject(json: String, label: String) {
        val parsed = runCatching { JsonParser.parseString(json) }
            .getOrElse { error -> throw IllegalArgumentException("$label JSON 格式无效", error) }
        require(parsed.isJsonObject) { "$label 必须是 JSON 对象" }
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun storedEntryLimit(path: String): Long? {
        val name = path.substringAfterLast('/')
        return when {
            path == MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
            path == STORY_ENTRY -> MAX_STORY_BYTES
            path.startsWith(PORTRAIT_PREFIX) -> MAX_PORTRAIT_BYTES
            name.endsWith(".db", ignoreCase = true) ||
                name.endsWith(".db-wal", ignoreCase = true) ||
                name.endsWith(".db-shm", ignoreCase = true) -> MAX_DATABASE_ENTRY_BYTES
            else -> null
        }
    }

    private fun readCurrentEntry(
        zip: ZipInputStream,
        maxBytes: Long,
        onBytesRead: (Long) -> Unit
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count > 0) {
                entryBytes += count
                require(entryBytes <= maxBytes) { "ZIP 条目过大" }
                onBytesRead(count.toLong())
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun drainCurrentEntry(zip: ZipInputStream, onBytesRead: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count > 0) onBytesRead(count.toLong())
            // 仅丢弃不安全或无效路径的内容，不为其分配完整 ByteArray。
        }
    }

    /** 只接受相对路径，避免恶意 ZIP entry 越界。 */
    private fun safeEntryName(raw: String): String? {
        if (raw.startsWith('/') || raw.startsWith('\\')) return null
        val normalized = raw.replace('\\', '/').let { path ->
            generateSequence(path) { current ->
                current.removePrefix("./").takeIf { it != current }
            }.last()
        }
        val segments = normalized.split('/')
        return normalized.takeIf {
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
                segments.none { segment -> segment.isBlank() || segment == "." || segment == ".." }
        }
    }
}
