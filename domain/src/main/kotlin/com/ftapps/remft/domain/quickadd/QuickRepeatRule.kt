package com.ftapps.remft.domain.quickadd

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class QuickRepeatFreq(val id: String) { Daily("daily"), Weekly("weekly"), Monthly("monthly"), Yearly("yearly") }

enum class QuickRepeatBasis(val id: String) { Calendar("calendar"), Completion("completion") }

data class QuickWeekdayPick(val day: String, val ordinal: Int? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", day)
        put("ordinal", ordinal?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

sealed interface QuickRepeatEnd {
    fun toJson(): JsonObject

    data object Never : QuickRepeatEnd {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "never") }
    }

    data class Until(val until: String) : QuickRepeatEnd {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "until")
            put("until", until)
        }
    }

    data class Count(val count: Int) : QuickRepeatEnd {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "count")
            put("count", count)
        }
    }
}

data class QuickRepeatRule(
    val freq: QuickRepeatFreq,
    val interval: Int = 1,
    val byWeekday: List<QuickWeekdayPick> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val end: QuickRepeatEnd = QuickRepeatEnd.Never,
    val basis: QuickRepeatBasis = QuickRepeatBasis.Calendar,
) {
    val isAfterCompletion: Boolean get() = basis == QuickRepeatBasis.Completion

    fun toJson(): JsonObject = buildJsonObject {
        put("freq", freq.id)
        put("interval", interval)
        if (byWeekday.isNotEmpty()) put("byWeekday", JsonArray(byWeekday.map { it.toJson() }))
        if (byMonthDay.isNotEmpty()) put("byMonthDay", JsonArray(byMonthDay.map { JsonPrimitive(it) }))
        if (byMonth.isNotEmpty()) put("byMonth", JsonArray(byMonth.map { JsonPrimitive(it) }))
        put("end", end.toJson())
        if (isAfterCompletion) put("basis", QuickRepeatBasis.Completion.id)
    }

    companion object {
        val weekdayCodes: List<String> = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    }
}
