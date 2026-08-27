package com.nekobot.app.data.local.plugin

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证清单 JSON 缺省字段补齐逻辑，防止 Gson 绕过 Kotlin 默认值导致的 NPE 崩溃。 */
class PluginManifestSanitizeTest {

    private val gson = Gson()

    @Test
    fun omittedOptionalFieldsAreFilledAndManifestPassesValidation() {
        // 精确复现用户崩溃场景：清单只写必填字段，没有 permissions/commands/aliases
        val raw = """
            {
              "api_version": 1,
              "id": "demo.minimal",
              "name": "最小清单",
              "version": "1.0.0",
              "author": "tester",
              "description": "仅必填字段",
              "entry": "main.js",
              "commands": [
                { "name": "ping", "usage": "/ping", "description": "测试" }
              ]
            }
        """.trimIndent()

        val manifest = gson.fromJson(
            PluginManifestValidator.sanitizeManifestJson(raw),
            PluginManifest::class.java
        )
        val errors = PluginManifestValidator.validate(manifest)

        assertEquals(emptyList<String>(), errors)
        assertEquals(emptyList<String>(), manifest.permissions)
        assertEquals(1, manifest.commands.size)
        assertEquals(emptyList<String>(), manifest.commands[0].aliases)
    }

    @Test
    fun explicitNullFieldsAreReplacedWithDefaults() {
        val raw = """
            {
              "api_version": null,
              "id": "demo.nulls",
              "name": "空值清单",
              "version": "1.0.0",
              "author": null,
              "description": null,
              "entry": null,
              "permissions": null,
              "commands": [
                { "name": "hello", "aliases": null, "usage": null, "description": null }
              ]
            }
        """.trimIndent()

        val manifest = gson.fromJson(
            PluginManifestValidator.sanitizeManifestJson(raw),
            PluginManifest::class.java
        )

        assertEquals(1, manifest.apiVersion)
        assertEquals("main.js", manifest.entry)
        assertEquals("", manifest.author)
        assertEquals("", manifest.description)
        assertEquals(emptyList<String>(), manifest.permissions)
        assertEquals(emptyList<String>(), manifest.commands[0].aliases)
        assertEquals("", manifest.commands[0].usage)
        assertEquals("", manifest.commands[0].description)
    }

    @Test
    fun typeMismatchedAliasesStillFailParsing() {
        // aliases 写成字符串属于类型错误，应交给 Gson 抛异常而不是静默修复
        val raw = """
            {
              "api_version": 1,
              "id": "demo.badtype",
              "name": "类型错误",
              "version": "1.0.0",
              "entry": "main.js",
              "commands": [
                { "name": "oops", "aliases": "not-an-array" }
              ]
            }
        """.trimIndent()

        val exception = runCatching {
            gson.fromJson(
                PluginManifestValidator.sanitizeManifestJson(raw),
                PluginManifest::class.java
            )
        }.exceptionOrNull()

        assertNotNull("类型不匹配的 aliases 应当解析失败", exception)
    }

    @Test
    fun completeManifestKeepsOriginalSemantics() {
        val raw = """
            {
              "api_version": 1,
              "id": "demo.full",
              "name": "完整清单",
              "version": "2.0.0",
              "author": "tester",
              "description": "全部字段齐全",
              "entry": "index.js",
              "permissions": ["storage", "notify"],
              "commands": [
                { "name": "greet", "aliases": ["hi"], "usage": "/greet", "description": "问好" }
              ]
            }
        """.trimIndent()

        val sanitized = PluginManifestValidator.sanitizeManifestJson(raw)
        val manifest = gson.fromJson(sanitized, PluginManifest::class.java)

        assertEquals(1, manifest.apiVersion)
        assertEquals("demo.full", manifest.id)
        assertEquals("index.js", manifest.entry)
        assertEquals(listOf("storage", "notify"), manifest.permissions)
        assertEquals(listOf("hi"), manifest.commands[0].aliases)
        assertTrue(PluginManifestValidator.validate(manifest).isEmpty())
    }
}