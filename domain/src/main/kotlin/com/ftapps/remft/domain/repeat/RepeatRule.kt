package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val RepeatWeekdays: List<String> = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

const val MaxOccurrences: Int = 400

enum class RepeatFreq(val wire: String) {
    Daily("daily"),
    Weekly("weekly"),
    Monthly("monthly"),
    Yearly("yearly");

    companion object {
        fun parse(value: String?): RepeatFreq? = entries.firstOrNull { it.wire == value }
    }
}

enum class RepeatBasis { Calendar, Completion }

data class WeekdayPick(val day: String, val ordinal: Int? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", day)
        put("ordinal", ordinal?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        fun fromJson(value: JsonElement?): WeekdayPick? {
            if (value !is JsonObject) return null
            val day = value["day"].stringOrNull() ?: return null
            if (day !in RepeatWeekdays) return null
            return WeekdayPick(day, value["ordinal"].intOrNull())
        }
    }
}

sealed class RepeatEnd {
    abstract val type: String

    data object Never : RepeatEnd() {
        override val type: String = "never"
    }

    data class Until(val until: String) : RepeatEnd() {
        override val type: String = "until"
    }

    data class Count(val count: Int) : RepeatEnd() {
        override val type: String = "count"
    }

    val isNever: Boolean get() = this is Never

    fun toJson(): JsonObject = when (this) {
        is Until -> buildJsonObject {
            put("type", "until")
            put("until", until)
        }
        is Count -> buildJsonObject {
            put("type", "count")
            put("count", count)
        }
        Never -> buildJsonObject { put("type", "never") }
    }

    companion object {
        fun fromJson(value: JsonElement?): RepeatEnd {
            if (value !is JsonObject) return Never
            val type = value["type"].stringOrNull()
            val until = value["until"].stringOrNull()
            val count = value["count"].intOrNull()
            return when {
                type == "until" && until != null -> Until(until)
                type == "count" && count != null -> Count(count)
                else -> Never
            }
        }
    }
}

data class RepeatRule(
    val freq: RepeatFreq,
    val interval: Int = 1,
    val byWeekday: List<WeekdayPick> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val end: RepeatEnd = RepeatEnd.Never,
    val basis: RepeatBasis = RepeatBasis.Calendar,
) {
    val isAfterCompletion: Boolean get() = basis == RepeatBasis.Completion

    fun toJson(): JsonObject = buildJsonObject {
        put("freq", freq.wire)
        put("interval", interval)
        if (byWeekday.isNotEmpty()) put("byWeekday", JsonArray(byWeekday.map { it.toJson() }))
        if (byMonthDay.isNotEmpty()) put("byMonthDay", buildJsonArray { byMonthDay.forEach { add(JsonPrimitive(it)) } })
        if (byMonth.isNotEmpty()) put("byMonth", buildJsonArray { byMonth.forEach { add(JsonPrimitive(it)) } })
        put("end", end.toJson())
        if (isAfterCompletion) put("basis", "completion")
    }

    fun toJsonString(): String = RepeatJson.encodeToString(JsonElement.serializer(), toJson())

    companion object {
        fun fromJson(value: JsonElement?): RepeatRule? {
            if (value !is JsonObject) return null
            val freq = RepeatFreq.parse(value["freq"].stringOrNull()) ?: return null
            val rawBasis = value["basis"]
            val basis = when {
                rawBasis == null || rawBasis is JsonNull -> RepeatBasis.Calendar
                rawBasis.stringOrNull() == "calendar" -> RepeatBasis.Calendar
                rawBasis.stringOrNull() == "completion" -> RepeatBasis.Completion
                else -> return null
            }
            val interval = value["interval"].intOrNull()
            val weekdays = value["byWeekday"].listOrEmpty() ?: return null
            val monthDays = value["byMonthDay"].listOrEmpty() ?: return null
            val months = value["byMonth"].listOrEmpty() ?: return null
            return RepeatRule(
                freq = freq,
                interval = if (interval != null && interval >= 1) interval else 1,
                byWeekday = weekdays.mapNotNull { WeekdayPick.fromJson(it) },
                byMonthDay = monthDays.mapNotNull { it.intOrNull() },
                byMonth = months.mapNotNull { it.intOrNull() },
                end = RepeatEnd.fromJson(value["end"]),
                basis = basis,
            )
        }

        fun fromJsonString(text: String): RepeatRule? =
            runCatching { RepeatJson.parseToJsonElement(text) }.getOrNull()?.let(::fromJson)
    }
}

internal val RepeatJson: Json = Json { ignoreUnknownKeys = true }

internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.intOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    return primitive.content.toIntOrNull()
}

internal fun JsonElement?.boolOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonElement?.listOrEmpty(): List<JsonElement>? = when (this) {
    null, is JsonNull -> emptyList()
    is JsonArray -> this
    else -> null
}

fun validateRepeatRule(rule: RepeatRule): String? {
    if (rule.interval < 1 || rule.interval > 366) {
        return "interval must be an integer between 1 and 366"
    }
    val monthlyOrYearly = rule.freq == RepeatFreq.Monthly || rule.freq == RepeatFreq.Yearly
    if (rule.isAfterCompletion) {
        if (rule.freq == RepeatFreq.Yearly) return "basis=completion supports daily, weekly or monthly"
        if (rule.byWeekday.isNotEmpty() || rule.byMonthDay.isNotEmpty() || rule.byMonth.isNotEmpty()) {
            return "basis=completion takes no byWeekday, byMonthDay or byMonth"
        }
    }
    if (rule.byWeekday.isNotEmpty()) {
        if (rule.freq == RepeatFreq.Daily) return "byWeekday is not allowed with freq=daily; use freq=weekly"
        for (pick in rule.byWeekday) {
            if (pick.day !in RepeatWeekdays) return "unknown weekday"
            val ordinal = pick.ordinal ?: continue
            if (!monthlyOrYearly) return "byWeekday.ordinal is only allowed with freq=monthly or yearly"
            if (ordinal == 0 || ordinal > 5 || ordinal < -1) return "byWeekday.ordinal must be 1..5 or -1 (last)"
        }
    }
    if (rule.byMonthDay.isNotEmpty()) {
        if (!monthlyOrYearly) return "byMonthDay is only allowed with freq=monthly or yearly"
        for (day in rule.byMonthDay) {
            if (day == 0 || day > 31 || day < -1) return "byMonthDay entries must be 1..31 or -1 (last day)"
        }
    }
    if (rule.byMonth.isNotEmpty()) {
        if (!monthlyOrYearly) return "byMonth is only allowed with freq=monthly or yearly"
        for (month in rule.byMonth) {
            if (month < 1 || month > 12) return "byMonth entries must be 1..12"
        }
    }
    val end = rule.end
    if (end is RepeatEnd.Until && RepeatDays.parse(end.until) == null) {
        return "end.until must be a YYYY-MM-DD date"
    }
    if (end is RepeatEnd.Count && (end.count < 1 || end.count > 1000)) {
        return "end.count must be an integer between 1 and 1000"
    }
    return null
}
