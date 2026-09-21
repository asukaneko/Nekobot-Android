package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** `host.ui.render` 的模板子集渲染。 */
class PluginTemplateRendererTest {

    @Test
    fun interpolatesAndEscapesByDefault() {
        val html = PluginTemplateRenderer.render(
            "<p>{{name}}|{{{name}}}|{{&name}}</p>",
            mapOf("name" to "<b>Neko</b>")
        )
        assertEquals("<p>&lt;b&gt;Neko&lt;/b&gt;|<b>Neko</b>|<b>Neko</b></p>", html)
    }

    @Test
    fun resolvesNestedPathsAndMissingKeys() {
        val html = PluginTemplateRenderer.render(
            "{{user.name}}/{{user.age}}/{{user.none}}",
            mapOf("user" to mapOf("name" to "Neko", "age" to 3.0))
        )
        assertEquals("Neko/3/", html)
    }

    @Test
    fun ifUnlessAndElseBranches() {
        val template = "{{#if flag}}Y{{else}}N{{/if}}-{{#unless flag}}U{{else}}V{{/unless}}"
        assertEquals("Y-V", PluginTemplateRenderer.render(template, mapOf("flag" to true)))
        assertEquals("N-U", PluginTemplateRenderer.render(template, mapOf("flag" to false)))
    }

    @Test
    fun eachIteratesListsAndObjects() {
        val list = PluginTemplateRenderer.render(
            "{{#each items}}[{{@index}}:{{this}}{{#if @first}}!{{/if}}]{{else}}empty{{/each}}",
            mapOf("items" to listOf("a", "b"))
        )
        assertEquals("[0:a!][1:b]", list)

        val map = PluginTemplateRenderer.render(
            "{{#each items}}{{@key}}={{this}};{{else}}empty{{/each}}",
            mapOf("items" to mapOf("x" to 1.0, "y" to 2.0))
        )
        assertEquals("x=1;y=2;", map)

        assertEquals(
            "empty",
            PluginTemplateRenderer.render("{{#each items}}x{{else}}empty{{/each}}", mapOf("items" to emptyList<Any>()))
        )
    }

    @Test
    fun withAndParentPathsWork() {
        val html = PluginTemplateRenderer.render(
            "{{#with user}}{{name}}@{{../title}}{{/with}}",
            mapOf("title" to "T", "user" to mapOf("name" to "Neko"))
        )
        assertEquals("Neko@T", html)
    }

    @Test
    fun subexpressionHelpersWork() {
        val template =
            "{{#if (eq role \"admin\")}}A{{else}}B{{/if}}" +
                "{{#if (and ok (not blocked))}}!{{/if}}" +
                "{{#if (contains tags \"vip\")}}V{{/if}}" +
                "{{length items}}"
        val html = PluginTemplateRenderer.render(
            template,
            mapOf(
                "role" to "admin",
                "ok" to true,
                "blocked" to false,
                "tags" to listOf("vip", "new"),
                "items" to listOf(1.0, 2.0, 3.0)
            )
        )
        assertEquals("A!V3", html)
    }

    @Test
    fun commentsAreIgnored() {
        assertEquals(
            "ab",
            PluginTemplateRenderer.render("a{{! 注释 }}{{!-- 长注释 --}}b", null)
        )
    }

    @Test
    fun syntaxErrorsAreReported() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginTemplateRenderer.render("{{#if x}}no close", mapOf("x" to true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginTemplateRenderer.render("{{#if x}}{{/each}}", mapOf("x" to true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginTemplateRenderer.render("{{#if (unknown a b)}}x{{/if}}", emptyMap<String, Any>())
        }
    }

    @Test
    fun templateAndOutputLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginTemplateRenderer.render(
                "{{#each items}}{{this}}{{/each}}",
                mapOf("items" to List(1_000) { "x".repeat(300) })
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginTemplateRenderer.render("x".repeat(PluginTemplateRenderer.MAX_TEMPLATE_CHARS + 1), null)
        }
    }
}
