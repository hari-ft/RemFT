package com.ftapps.remft.data.alarm

import android.content.res.Resources
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.SnoozePresets

object SnoozeLabels {
    fun of(resources: Resources, id: String): String {
        val normalized = SnoozePresets.normalize(id) ?: return id
        builtIn(normalized)?.let { return resources.getString(it) }
        val minutes = SnoozePresets.minutesOf(normalized) ?: return id
        return duration(resources, minutes)
    }

    private fun builtIn(id: String): Int? = when (id) {
        SnoozePresets.FIVE_MIN -> R.string.notif_action_snooze5m
        SnoozePresets.THIRTY_MIN -> R.string.notif_action_snooze30m
        SnoozePresets.ONE_HOUR -> R.string.notif_action_snooze1h
        SnoozePresets.TOMORROW_MORNING -> R.string.notif_action_snoozeTomorrow
        else -> null
    }

    fun duration(resources: Resources, minutes: Long): String {
        val (days, hours, mins) = SnoozePresets.partsOf(minutes)
        fun plural(id: Int, count: Long) = resources.getQuantityString(id, count.toInt(), count.toInt())
        return when {
            days == 0L && hours == 0L -> plural(R.plurals.snz_minutes, mins)
            days == 0L && mins == 0L -> plural(R.plurals.snz_hours, hours)
            days > 0L && hours == 0L && mins == 0L -> plural(R.plurals.snz_days, days)
            else -> listOfNotNull(
                days.takeIf { it > 0 }?.let { plural(R.plurals.snz_days, it) },
                hours.takeIf { it > 0 }?.let { resources.getString(R.string.snz_hoursShort, it.toInt()) },
                mins.takeIf { it > 0 }?.let { plural(R.plurals.snz_minutes, it) },
            ).joinToString(" ")
        }
    }
}
