package com.nekobot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 单角色模式新增的文案必须五语齐全。
 * 同一语言的文案分散在多个 strings*.xml 里，Android 会把它们合并成同一个资源表，
 * 所以这里也按语言合并后再比对：键缺失、空值和格式参数不一致都会被拦下，
 * 避免英文 / 日文 / 韩文 / 繁中回退成中文兜底。
 */
class CompanionLocaleCoverageTest {
    private val locales = listOf("values-en", "values-ja", "values-ko", "values-zh-rTW")
    private val localeRoots = listOf(File("src/main/res"), File("app/src/main/res"))
    // 只校验单角色模式相关的键；其余模块的既有翻译缺口不在本测试范围内。
    private val scoped = Regex(
        "\\A(companion_|single_role_|sessions_relationship_state|thinking_source|" +
            "settings_thinking_source|imported_history_|single_session_)"
    )
    private val argument = Regex("%(?:\\d+\\$)?[\\d.]*[a-zA-Z]")

    private fun read(locale: String): Map<String, String> {
        val root = localeRoots.first { File(it, locale).isDirectory }
        val merged = LinkedHashMap<String, String>()
        File(root, locale).listFiles { file -> file.extension == "xml" }.orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("string")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    // 不可翻译的项（如本地构建专用的联系地址）只在默认 values 里定义一次。
                    if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                    val key = node.attributes.getNamedItem("name").nodeValue
                    assertTrue("$locale ${file.name} 重复定义 $key", key !in merged)
                    merged[key] = node.textContent
                }
            }
        return merged
    }

    @Test
    fun companionScreensKeepAllFiveLocales() {
        val original = read("values").filterKeys { scoped.find(it) != null }
        assertTrue("应覆盖单角色文案", original.size > 300)
        for (locale in locales) {
            val translated = read(locale)
            val missing = original.keys.filter { it !in translated }
            assertTrue("$locale 缺少单角色文案: ${missing.take(10)}", missing.isEmpty())
            original.forEach { (key, value) ->
                val text = translated.getValue(key)
                assertTrue("$locale $key 为空", text.isNotBlank())
                assertEquals(
                    "$locale $key 格式参数不一致",
                    argument.findAll(value).map { it.value }.sorted().toList(),
                    argument.findAll(text).map { it.value }.sorted().toList()
                )
            }
        }
    }
}
