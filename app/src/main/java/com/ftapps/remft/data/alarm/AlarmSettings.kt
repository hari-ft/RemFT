package com.ftapps.remft.data.alarm

import android.content.Context
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.AlarmTexts
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.SettingKeys

data class AlarmSettings(
    val privacyMode: Boolean,
    val profile: ReminderProfile,
    val alarmSound: SoundChoice,
    val reminderSound: SoundChoice,
    val snoozeOrder: List<String>,
) {
    companion object {
        val Defaults = from(emptyMap())

        fun from(values: Map<String, String>): AlarmSettings {
            fun value(key: com.ftapps.remft.domain.settings.SettingKey): String? = values[key.name] ?: key.default
            return AlarmSettings(
                privacyMode = value(SettingKeys.NotificationPrivacy) == "true",
                profile = ReminderProfile.parse(value(SettingKeys.ReminderProfile)),
                alarmSound = SoundChoice.parse(value(SettingKeys.AlarmSound)),
                reminderSound = SoundChoice.parse(value(SettingKeys.ReminderSound)),
                snoozeOrder = SnoozePresets.parseOrder(value(SettingKeys.SnoozePresets)),
            )
        }

        val plannerKeys: Set<String> = setOf(
            SettingKeys.NotificationPrivacy.name,
            SettingKeys.ReminderProfile.name,
            SettingKeys.AlarmSound.name,
            SettingKeys.ReminderSound.name,
        )
    }
}

object AlarmTextsFactory {
    fun from(context: Context): AlarmTexts = AlarmTexts(
        privacyTitle = context.getString(R.string.app_title),
        privateBody = context.getString(R.string.notif_privateBody),
        urgentFirst = context.getString(R.string.notif_urgentFirst),
        urgentRepeat = context.getString(R.string.notif_urgentRepeat),
        dueNow = context.getString(R.string.notif_dueNow),
        afterSnooze = context.getString(R.string.notif_afterSnooze),
        reminder = context.getString(R.string.notif_reminder),
        testTitle = context.getString(R.string.settings_alarms_testTitle),
        testBody = context.getString(R.string.settings_alarms_testBody),
    )
}
