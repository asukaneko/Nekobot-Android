package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class PortableFileWrite(val key: String, val target: File, val source: File, val expected: String?, val incoming: String? = null)
internal data class PortableJournalFile(val target: String, val before: String?, val after: String, val index: Int)
internal data class PortableJournalRecord(val id: String, val database: String, val profile: String,
    val files: List<PortableJournalFile>, val state: JsonObject)

/** Durable undo log. A SQLite receipt written in the same transaction decides recovery after death. */
internal class PortableImportJournal(private val root: File, private val allowedRoots: List<File>) {
    private val gson = Gson()
    // Android may expose the same private directory as /data/user/0 and /data/data.
    // Records use canonical targets; both forms of each explicitly trusted root must
    // remain valid on install and recovery, without trusting links below that root.
    private val trustedRootPaths = allowedRoots.flatMap {
        listOf(it.absoluteFile.toPath().normalize(), it.canonicalFile.toPath())
    }.distinct()
    fun prepare(database: String, profile: String, writes: List<PortableFileWrite>, state: JsonObject): PortableJournalRecord {
        require(File(database).name == database && database.endsWith(".db"))
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        val directory = directory(id).apply { check(mkdir()) { "无法准备导入恢复目录" } }
        try {
            val targets = hashSetOf<String>()
            val files = writes.mapIndexed { index, write ->
                val target = checked(write.target)
                require(targets.add(target.path)) { "归档有重复的附件目标" }
                require(!target.exists() || target.isFile) { "附件目标不是普通文件" }
                val before = target.takeIf(File::isFile)?.let(::hash)
                require(before == write.expected) { "附件在预览后已变化，请重新预览" }
                if (before != null) durableCopy(target, File(directory, "$index.old"))
                val staged = File(directory, "$index.new")
                durableCopy(write.source, staged)
                val after = hash(staged)
                require(write.incoming == null || after == write.incoming) { "导入附件在预览后已变化，请重新分析文件" }
                PortableJournalFile(target.path, before, after, index)
            }
            val record = PortableJournalRecord(id, database, profile, files, state)
            val temporary = File(directory, "journal.tmp")
            FileOutputStream(temporary).use { it.write(gson.toJson(record).toByteArray()); it.fd.sync() }
            move(temporary, File(directory, "journal.json"))
            return record
        } catch (error: Throwable) { removeDirectory(directory); throw error }
    }

    fun install(record: PortableJournalRecord, checkpoint: () -> Unit = {}) {
        record.files.forEach { file ->
            val target = checked(File(file.target))
            require(target.takeIf(File::isFile)?.let(::hash) == file.before) { "附件在预览后已变化，请重新预览" }
            require(hash(File(directory(record.id), "${file.index}.new")) == file.after) { "导入附件校验失败" }
        }
        record.files.forEach { file ->
            checkpoint()
            val target = checked(File(file.target))
            require(target.takeIf(File::isFile)?.let(::hash) == file.before) { "附件在预览后已变化，请重新预览" }
            replace(File(directory(record.id), "${file.index}.new"), target)
        }
    }

    fun rollback(record: PortableJournalRecord, restoreState: (PortableJournalRecord) -> Unit) {
        record.files.asReversed().forEach { file ->
            val target = checked(File(file.target))
            val current = target.takeIf(File::isFile)?.let(::hash)
            require(current == null || current == file.after || current == file.before) { "附件恢复遇到新的修改，已保留恢复记录" }
            if (file.before == null) {
                if (target.exists()) check(target.delete()) { "无法撤销新附件，已保留恢复记录" }
            } else {
                val backup = File(directory(record.id), "${file.index}.old")
                require(backup.isFile && hash(backup) == file.before) { "附件恢复备份校验失败" }
                replace(backup, target)
            }
        }
        restoreState(record)
        finish(record)
    }

    fun recover(committed: (PortableJournalRecord) -> Boolean, restoreState: (PortableJournalRecord) -> Unit) {
        root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.forEach { entry ->
            if (!runCatching { UUID.fromString(entry.name) }.isSuccess) return@forEach
            val file = File(entry, "journal.json")
            if (!file.isFile) { removeDirectory(entry); return@forEach }
            val record = gson.fromJson(file.readText(), PortableJournalRecord::class.java)
            require(record.id == entry.name && File(record.database).name == record.database) { "导入恢复记录无效" }
            if (committed(record)) finish(record) else rollback(record, restoreState)
        }
    }

    fun finish(record: PortableJournalRecord) = removeDirectory(directory(record.id))

    private fun directory(id: String): File {
        UUID.fromString(id)
        val result = File(root, id)
        require(result.canonicalFile.parentFile == root.canonicalFile && !Files.isSymbolicLink(result.toPath()))
        return result
    }
    private fun removeDirectory(directory: File) {
        require(directory.canonicalFile.parentFile == root.canonicalFile && !Files.isSymbolicLink(directory.toPath()))
        Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file) // walkFileTree does not follow symbolic links.
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
    fun checked(file: File): File {
        val absolute = file.absoluteFile.toPath().normalize()
        val base = trustedRootPaths.firstOrNull { absolute != it && absolute.startsWith(it) }
            ?: error("附件路径超出允许目录")
        var item: java.nio.file.Path? = absolute
        while (item != null && item != base && item.startsWith(base)) {
            require(!Files.isSymbolicLink(item)) { "附件路径不能使用符号链接" }
            item = item.parent
        }
        val target = absolute.toFile().canonicalFile
        val canonicalBase = base.toFile().canonicalFile.toPath()
        require(target.toPath() != canonicalBase && target.toPath().startsWith(canonicalBase)) { "附件路径越界" }
        return target
    }
    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.importing")
        try { durableCopy(source, temp); move(temp, target) } finally { temp.delete() }
    }
    companion object {
        fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().buffered().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun durableCopy(source: File, target: File) {
            source.inputStream().buffered().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
        }
        private fun move(source: File, target: File) {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
