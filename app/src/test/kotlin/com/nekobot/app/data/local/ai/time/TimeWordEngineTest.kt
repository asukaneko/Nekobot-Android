package com.nekobot.app.data.local.ai.time

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

@RunWith(Parameterized::class)
class TimeWordEngineTest(private val text: String, private val spoken: String, private val expected: String?, private val source: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} @ {1} ({3})")
        fun cases(): List<Array<Any?>> = listOf("zh", "zh-TW", "en", "ja", "ko").flatMap { language ->
            requireNotNull(TimeWordEngineTest::class.java.getResourceAsStream("/timewords/$language.jsonl")).bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).map { line ->
                    val row = JsonParser.parseString(line).asJsonObject
                    arrayOf<Any?>(row["text"].asString, row["spoken"].asString,
                        row["expected"].takeUnless { it.isJsonNull }?.asString, row["source"].asString)
                }.toList()
            }
        }
    }

    @Test fun resolvesWithoutWrongDate() {
        val result = TimeWordEngine.resolve(text, LocalDate.parse(spoken))
        val actual = (result as? TimeWordEngine.Result.Day)?.date?.toString()
        // 比95%门槛更严格：每一个选中用例都必须符合日期或Unresolved期望。
        assertEquals(source, expected, actual)
    }
}
