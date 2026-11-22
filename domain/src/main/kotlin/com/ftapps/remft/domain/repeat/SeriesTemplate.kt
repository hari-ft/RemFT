package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

data class SeriesTemplate(val fields: Map<String, JsonElement> = emptyMap()) {
    val title: String get() = fields["title"].stringOrNull() ?: ""
    val description: String? get() = fields["description"].stringOrNull()
    val priority: String get() = fields["priority"].stringOrNull() ?: "none"
    val isUrgent: Boolean get() = fields["isUrgent"].boolOrNull() == true
    val requiresAcknowledgement: Boolean get() = fields["requiresAcknowledgement"].boolOrNull() == true
    val remindMinutesBefore: Int? get() = fields["remindMinutesBefore"].intOrNull()

    fun with(key: String, value: JsonElement): SeriesTemplate = SeriesTemplate(LinkedHashMap(fields).apply { put(key, value) })

    fun without(key: String): SeriesTemplate = SeriesTemplate(LinkedHashMap(fields).apply { remove(key) })

    fun toJson(): JsonObject = JsonObject(fields)

    fun toJsonString(): String = RepeatJson.encodeToString(JsonElement.serializer(), toJson())

    companion object {
        const val Title = "title"
        const val Description = "description"
        const val Priority = "priority"
        const val IsUrgent = "isUrgent"
        const val RequiresAcknowledgement = "requiresAcknowledgement"
        const val RemindMinutesBefore = "remindMinutesBefore"

        fun of(
            title: String,
            description: String? = null,
            priority: String = "none",
            isUrgent: Boolean = false,
            requiresAcknowledgement: Boolean = false,
            remindMinutesBefore: Int? = null,
        ): SeriesTemplate {
            val map = LinkedHashMap<String, JsonElement>()
            map[Title] = JsonPrimitive(title)
            map[Description] = description?.let(::JsonPrimitive) ?: JsonNull
            map[Priority] = JsonPrimitive(priority)
            map[IsUrgent] = JsonPrimitive(isUrgent)
            map[RequiresAcknowledgement] = JsonPrimitive(requiresAcknowledgement)
            if (remindMinutesBefore != null) map[RemindMinutesBefore] = JsonPrimitive(remindMinutesBefore)
            return SeriesTemplate(map)
        }

        fun fromTask(task: OccurrenceRow, anchorAt: Instant): SeriesTemplate = of(
            title = task.title,
            description = task.description,
            priority = task.priority,
            isUrgent = task.isUrgent,
            requiresAcknowledgement = task.requiresAcknowledgement,
            remindMinutesBefore = task.remindAt?.let { Duration.between(it, task.dueAt ?: anchorAt).toMinutes().toInt() },
        )

        fun fromJson(value: JsonElement?): SeriesTemplate =
            if (value is JsonObject) SeriesTemplate(LinkedHashMap(value)) else SeriesTemplate()

        fun fromJsonString(text: String): SeriesTemplate =
            fromJson(runCatching { RepeatJson.parseToJsonElement(text) }.getOrNull())
    }
}
