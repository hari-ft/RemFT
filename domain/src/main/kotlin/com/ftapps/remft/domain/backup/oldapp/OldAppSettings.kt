package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.auto.Retention
import com.ftapps.remft.domain.backup.bin.BinSettings
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object OldAppSettings {
    const val OLD_DEFAULT_ALARM_ID = "aw_alarm"
    val LEGACY_STANDARD_SLOTS: List<Int> = listOf(0, 2, 5, 10, 30)

    data class Result(val settings: Map<String, String>?, val adjustments: Map<String, Int>)

    private val toggles = setOf(
        SettingKeys.NotificationPrivacy.name,
        SettingKeys.BinEnabled.name,
        SettingKeys.BinAutoDelete.name,
        SettingKeys.HomeCalendarVisible.name,
    )
    private val sortFields: Map<String, Set<String>> = mapOf(
        SettingKeys.TasksSort.name to setOf("date", "priority", "title"),
        SettingKeys.NotesSort.name to setOf("updated", "created", "title"),
        SettingKeys.FilesSort.name to setOf("date", "name", "size"),
    )

    fun convert(settings: Map<String, String>, log: BackupLog): Result {
        val out = LinkedHashMap<String, String>()
        var converted = 0
        var skipped = 0
        for (key in BackupSettings.keys) {
            val raw = settings[key] ?: continue
            val next = convertValue(key, raw)
            when {
                next == null -> {
                    skipped++
                    log.warn("oldbackup.setting_skipped", fields = mapOf("key" to key))
                }
                next != raw -> {
                    converted++
                    out[key] = next
                    log.info("oldbackup.setting_converted", mapOf("key" to key))
                }
                else -> out[key] = next
            }
        }
        val adjustments = LinkedHashMap<String, Int>()
        if (converted > 0) adjustments["settingsConverted"] = converted
        if (skipped > 0) adjustments["settingsSkipped"] = skipped
        return Result(out, adjustments)
    }

    fun convertValue(key: String, raw: String): String? = when (key) {
        SettingKeys.AlarmSound.name, SettingKeys.ReminderSound.name -> sound(raw)
        SettingKeys.ReminderProfile.name -> reminderProfile(raw)
        SettingKeys.SnoozePresets.name -> snoozePresets(raw)
        in toggles -> raw.takeIf { it == "true" || it == "false" }
        SettingKeys.OnboardingSeen.name -> raw.takeIf { it == "true" }
        SettingKeys.DateFormat.name -> raw.takeIf { v -> DateFormatId.entries.any { it.id == v } }
        SettingKeys.NoteSourceStyling.name -> raw.takeIf { v -> NoteSourceStyling.entries.any { it.id == v } }
        SettingKeys.BinDays.name -> raw.takeIf { it.toIntOrNull() in BinSettings.DAY_CHOICES }
        SettingKeys.AutoBackupKeepDays.name -> raw.takeIf { it.toIntOrNull() in Retention.KEEP_DAY_CHOICES }
        SettingKeys.AutoBackupSchedule.name -> raw.takeIf { JsonText.parseOrNull(it) is JsonObject }
        SettingKeys.NotesViewMode.name -> raw.takeIf { it == "list" || it == "grid" }
        in sortFields.keys -> sort(raw, sortFields.getValue(key))
        else -> raw
    }

    private fun sound(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith(AlarmSounds.BUNDLED_PREFIX)) return raw
        val id = value.substring(AlarmSounds.BUNDLED_PREFIX.length)
        return when {
            id == OLD_DEFAULT_ALARM_ID -> AlarmSounds.BUNDLED_PREFIX + AlarmSounds.ALARM_ID
            AlarmSounds.bundledById(id) != null -> raw
            else -> "os"
        }
    }

    private fun reminderProfile(raw: String): String? {
        val json = JsonText.parseOrNull(raw) as? JsonObject ?: return null
        if (json["slots"] !is JsonArray) return null
        val profile = ReminderProfile.parse(raw)
        return if (profile.slots == LEGACY_STANDARD_SLOTS) ReminderProfile.Factory.copy(repeatAfterSnooze = profile.repeatAfterSnooze).encode() else raw
    }

    private fun snoozePresets(raw: String): String {
        val order = ArrayList<String>()
        for (part in raw.split(",")) {
            val id = part.trim()
            if (id in SnoozePresets.ids && id !in order) order.add(id)
        }
        for (id in SnoozePresets.ids) if (id !in order) order.add(id)
        val csv = order.joinToString(",")
        return if (csv == raw) raw else csv
    }

    private fun sort(raw: String, fields: Set<String>): String? {
        val parts = raw.split(":")
        if (parts.size != 2 || parts[0] !in fields || parts[1] !in setOf("asc", "desc")) return null
        return raw
    }
}
