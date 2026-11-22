package com.ftapps.remft.domain.repeat

import com.ftapps.remft.domain.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private val fixture: JsonObject by lazy { Json.parseToJsonElement(Fixtures.text("recurrence_cases.json")).jsonObject }

@RunWith(Parameterized::class)
class RecurrenceExpansionCaseTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            fixture.getValue("cases").jsonArray.map { arrayOf(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject) }
    }

    @Test
    fun expandsToTheExpectedDays() {
        val rule = RepeatRule.fromJson(case["rule"])
        assertNotNull("rule must parse: $name", rule)
        val days = Recurrence.expand(
            rule!!,
            anchor = case.getValue("anchor").jsonPrimitive.content,
            from = case["from"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            to = case["to"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
            max = case["max"]?.jsonPrimitive?.intOrNull ?: MaxOccurrences,
        )
        assertEquals(case.getValue("expected").jsonArray.map { it.jsonPrimitive.content }, days)
    }
}

@RunWith(Parameterized::class)
class AfterCompletionCaseTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            fixture.getValue("afterCompletion").jsonArray.map { arrayOf(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject) }
    }

    @Test
    fun nextDayMatchesTheExpectedDay() {
        val rule = RepeatRule.fromJson(case["rule"])
        assertNotNull(rule)
        assertTrue(rule!!.isAfterCompletion)
        val expected = case["expected"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        val actual = Recurrence.nextAfterCompletion(
            rule,
            case.getValue("completed").jsonPrimitive.content,
            emitted = case["emitted"]?.jsonPrimitive?.int ?: 1,
        )
        assertEquals(name, expected, actual)
    }
}

@RunWith(Parameterized::class)
class InvalidRuleCaseTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "refuses {0}")
        fun cases(): List<Array<Any>> =
            fixture.getValue("invalidRules").jsonArray.map { arrayOf(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject) }
    }

    @Test
    fun refuses() {
        val rule = RepeatRule.fromJson(case["rule"])
        assertTrue(name, rule == null || validateRepeatRule(rule) != null)
    }
}
