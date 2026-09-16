package com.nekobot.app.data.local

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把本地 Skill 目录导出成 ZIP 包（供「下载 ZIP」使用）。
 *
 * 结构与从 GitHub 下载的 Skill 包保持一致：ZIP 内只有一个以 Skill 目录名命名的根目录，
 * 其下原样保存 SKILL.md、reference.md、scripts/、resources/ 以及任意同级文件与目录。
 * 重新安装时 [SkillPackageDownloader] 会自动剥离这层根目录，因此导出的包可以直接再导入。
 */
internal object SkillZipExporter {

    /** 单次导出的文件数上限（与解析端 `MAX_FILES` 对齐）。 */
    private const val MAX_ENTRIES = 500

    /** 压缩级别：技能包以文本为主，默认级别即可，避免大文件耗时。 */
    private const val COMPRESSION_LEVEL = 6

    /** 导出后的文件名：`<安全目录名>.zip`。 */
    fun zipFileName(skillName: String): String = "${skillDirectoryName(skillName)}.zip"

    /**
     * 打包成 ZIP 字节。
     *
     * @param skillName Skill 名称，同时作为 ZIP 内的根目录名
     * @param files `Skill 根目录下的相对路径 → 文件内容`
     */
    fun build(skillName: String, files: Map<String, ByteArray>): ByteArray {
        require(files.isNotEmpty()) { "Skill 目录为空，无法导出" }
        val root = skillDirectoryName(skillName)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(COMPRESSION_LEVEL)
            var count = 0
            files.entries.sortedBy { it.key }.forEach { (path, content) ->
                val entryPath = sanitizeEntryPath(path) ?: return@forEach
                if (count >= MAX_ENTRIES) throw IllegalStateException("Skill 文件数超过 $MAX_ENTRIES，无法导出")
                zip.putNextEntry(ZipEntry("$root/$entryPath"))
                zip.write(content)
                zip.closeEntry()
                count++
            }
        }
        return output.toByteArray()
    }

    /**
     * 条目路径安全化：统一分隔符、去掉空片段与 `.`，越界路径直接丢弃。
     *
     * 导出端不允许产生 `..`，避免生成的 ZIP 在别处解压时穿越目录。
     */
    private fun sanitizeEntryPath(path: String): String? {
        val normalized = path.replace('\\', '/').trimStart('/')
        val segments = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }
}
