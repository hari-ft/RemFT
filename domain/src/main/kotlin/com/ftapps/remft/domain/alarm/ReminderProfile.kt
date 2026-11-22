package com.ftapps.remft.domain.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

data class ReminderProfilePreset(val id: String, val slots: List<Int>)

data class ReminderProfile(
    val slots: List<Int>,
    val repeatAfterSnooze: Boolean = true,
) {
    val offsets: List<Duration>
        get() = slots.map { Duration.ofMinutes(it.toLong()) }

    val alarmsFullyCovered: Int
        get() = if (slots.isEmpty()) 0 else PLANNER_WINDOW / slots.size

    val presetId: String?
        get() = PRESETS.firstOrNull { it.slots == slots }?.id

    fun encode(): String = "{\"slots\":[${slots.joinToString(",")}],\"repeatAfterSnooze\":$repeatAfterSnooze}"

    companion object {
        const val MAX_SLOTS = 20
        const val MIN_GAP_MINUTES = 1
        const val PLANNER_WINDOW = 40

        const val PRESET_CALM = "calm"
        const val PRESET_STANDARD = "standard"
        const val PRESET_INSISTENT = "insistent"

        val PRESETS: List<ReminderProfilePreset> = listOf(
            ReminderProfilePreset(PRESET_CALM, listOf(0)),
            ReminderProfilePreset(PRESET_STANDARD, listOf(0, 30, 60)),
            ReminderProfilePreset(PRESET_INSISTENT, listOf(0, 1, 2, 3, 5, 8, 12, 17, 25, 40)),
        )

        val Factory = ReminderProfile(slots = listOf(0, 30, 60))

        private val json = Json { isLenient = false }

        fun preset(id: String): ReminderProfilePreset? = PRESETS.firstOrNull { it.id == id }

        fun parse(raw: String?): ReminderProfile {
            if (raw == null || raw.isBlank()) return Factory
            return try {
                val data = json.parseToJsonElement(raw) as? JsonObject ?: return Factory
                val rawSlots = data["slots"] as? JsonArray ?: return Factory
                val values = rawSlots.mapNotNull { element ->
                    val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                    if (primitive.isString) return@mapNotNull null
                    primitive.longOrNull?.toInt() ?: primitive.doubleOrNull?.toInt()
                }
                val slots = normalizeSlots(values)
                val repeat = (data["repeatAfterSnooze"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.booleanOrNull
                ReminderProfile(slots = slots, repeatAfterSnooze = repeat ?: true)
            } catch (error: Exception) {
                Factory
            }
        }

        fun normalizeSlots(raw: Iterable<Int>): List<Int> {
            val sorted = raw.filter { it >= 0 }.toSortedSet()
            val kept = ArrayList<Int>()
            for (minutes in sorted) {
                if (kept.isEmpty() || minutes - kept.last() >= MIN_GAP_MINUTES) kept.add(minutes)
                if (kept.size == MAX_SLOTS) break
            }
            return if (kept.isEmpty()) listOf(0) else kept
        }

        fun minSlotAfter(previous: Int?): Int = if (previous == null) 0 else previous + MIN_GAP_MINUTES
    }
}
