package com.ftapps.remft.domain.quickadd

import com.ftapps.remft.domain.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@RunWith(Parameterized::class)
class QuickParseFixtureTest(private val name: String, private val case: JsonObject) {

    companion object {
        private val root: JsonObject by lazy {
            Json.parseToJsonElement(Fixtures.text("quick_parse_cases.json")).jsonObject
        }

        private val defaultNow: String by lazy {
            root.getValue("defaults").jsonObject.getValue("now").jsonPrimitive.content
        }

        val wall: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = root.getValue("cases").jsonArray.mapIndexed { index, element ->
            val c = element.jsonObject
            val label = "#$index [${c.getValue("group").jsonPrimitive.content}] ${c.getValue("text").jsonPrimitive.content}"
            arrayOf(label, c)
        }
    }

    private fun options(): QuickParseOptions {
        val o = case["options"]?.jsonObject
        val now = LocalDateTime.parse(case["now"]?.jsonPrimitive?.content ?: defaultNow)
        return QuickParseOptions(
            now = now,
            weekStart = o?.get("weekStart")?.jsonPrimitive?.int?.let { DayOfWeek.of(it) } ?: DayOfWeek.MONDAY,
            defaultTime = o?.get("defaultTime")?.jsonPrimitive?.content?.let { LocalTime.parse(it) },
            monthFirstSlash = o?.get("monthFirstSlash")?.jsonPrimitive?.booleanOrNull ?: false,
            ignored = o?.get("ignored")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
        )
    }

    @Test
    fun matchesTheContract() {
        val text = case.getValue("text").jsonPrimitive.content
        val e = case.getValue("expect").jsonObject
        val p = QuickAdd.parse(text, options())
        assertEquals("title", e.getValue("title").jsonPrimitive.content, p.title)
        assertEquals("due", e["due"]?.jsonPrimitive?.contentOrNull, p.dueAt?.format(wall))
        val repeat = e["repeat"]
        assertEquals("repeat", if (repeat == null || repeat is JsonNull) null else repeat, p.repeat?.toJson())
        assertEquals("priority", e["priority"]?.jsonPrimitive?.contentOrNull, p.priority)
        e["kinds"]?.let { kinds ->
            assertEquals("kinds", kinds.jsonArray.map { it.jsonPrimitive.content }, p.tokens.map { it.kind.id })
        }
        e["dueHasTime"]?.let { assertEquals("dueHasTime", it.jsonPrimitive.booleanOrNull, p.dueHasTime) }
        p.repeat?.let { assertTrue(QuickRepeatDays.isValid(it)) }
        for (t in p.tokens) assertEquals(text.substring(t.start, t.end), t.text)
    }
}

class QuickParseFixtureShapeTest {
    @Test
    fun carriesTheContract() {
        val cases = Json.parseToJsonElement(Fixtures.text("quick_parse_cases.json")).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 120)
    }
}
