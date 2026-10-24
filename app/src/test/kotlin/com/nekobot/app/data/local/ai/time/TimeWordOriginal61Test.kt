package com.nekobot.app.data.local.ai.time

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

/** 原调研附录的61题；四种语言的月末范围按最终规格返回Unresolved。 */
@RunWith(Parameterized::class)
class TimeWordOriginal61Test(private val number: Int, private val language: String, private val text: String,
    private val expected: String?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}: {1} {2}")
        fun cases(): List<Array<Any?>> = requireNotNull(TimeWordOriginal61Test::class.java.getResourceAsStream("/timewords/original61.jsonl"))
            .bufferedReader().useLines { lines -> lines.filter(String::isNotBlank).map {
                val row = JsonParser.parseString(it).asJsonObject
                arrayOf<Any?>(row["number"].asInt, row["language"].asString, row["text"].asString,
                    row["expected"].takeUnless { value -> value.isJsonNull }?.asString)
            }.toList() }.also { check(it.size == 61) }
    }
    @Test fun matchesOriginalQuestionWithFinalDateContract() {
        val result = TimeWordEngine.resolve(text, LocalDate.of(2026, 10, 23))
        assertEquals("原题$number/$language/$text", expected, (result as? TimeWordEngine.Result.Day)?.date?.toString())
    }
}
