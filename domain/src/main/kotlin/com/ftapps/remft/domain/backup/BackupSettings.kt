package com.ftapps.remft.domain.backup

import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BackupSettings {
    val keys: List<String> = listOf(
        "remft_alarm_sound",
        "remft_reminder_sound",
        "remft_reminder_profile",
        "remft_snooze_presets",
        "notification_privacy",
        "remft_date_format",
        "remft_note_source_styling",
        "remft_bin_enabled",
        "remft_bin_auto_delete",
        "remft_bin_days",
        "remft_auto_backup_schedule",
        "remft_auto_backup_keep_days",
        "remft_home_calendar_visible",
        "remft_tasks_sort",
        "remft_inbox_sort",
        "remft_notes_sort",
        "remft_files_sort",
        "remft_notes_view_mode",
        "remft_onboarding_seen_v1",
    )

    val pendingKey: String = SettingKeys.RestorePendingSettings.name

    private val allowed: Set<String> = keys.toSet()

    fun collect(read: (String) -> String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in keys) {
            val value = runCatching { read(key) }.getOrNull()
            if (value != null) out[key] = value
        }
        return out
    }

    fun encode(settings: Map<String, String>): String =
        JsonText.encode(JsonObject(settings.mapValues { JsonPrimitive(it.value) }))

    fun parse(raw: String): Map<String, String>? {
        val decoded = JsonText.parseOrNull(raw) ?: return null
        if (decoded !is JsonObject) return null
        val out = LinkedHashMap<String, String>()
        for ((key, value) in decoded) {
            val text = JsonText.stringOf(value) ?: continue
            if (key in allowed) out[key] = text
        }
        return out
    }

    fun changesToApply(settings: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (key in keys) out[key] = settings[key]
        return out
    }

    fun applyPending(read: (String) -> String?, write: (String, String) -> Unit, remove: (String) -> Unit): Boolean {
        val raw = read(pendingKey) ?: return false
        val settings = parse(raw)
        if (settings != null) {
            for ((key, value) in changesToApply(settings)) {
                if (value == null) remove(key) else write(key, value)
            }
        }
        remove(pendingKey)
        return settings != null
    }
}
