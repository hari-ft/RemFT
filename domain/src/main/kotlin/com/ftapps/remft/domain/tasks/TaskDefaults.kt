package com.ftapps.remft.domain.tasks

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object TaskDefaults {
    val AUTO_REMINDER_GAP: Duration = Duration.ofHours(1)

    fun autoReminderFor(dueAt: Instant?, now: Instant): Instant? {
        val at = dueAt?.minus(AUTO_REMINDER_GAP) ?: return null
        return if (at.isAfter(now)) at else null
    }

    fun autoReminderFor(dueAt: LocalDateTime?, now: LocalDateTime): LocalDateTime? {
        val at = dueAt?.minus(AUTO_REMINDER_GAP) ?: return null
        return if (at.isAfter(now)) at else null
    }

    fun reminderFollowsDue(oldDue: Instant, newDue: Instant, remind: Instant, zone: ZoneId): Boolean {
        if (newDue == oldDue) return false
        val derived = Duration.between(remind, oldDue) == AUTO_REMINDER_GAP
        val otherDay = oldDue.atZone(zone).toLocalDate() != newDue.atZone(zone).toLocalDate()
        val stillBefore = remind.isBefore(newDue)
        return derived || otherDay || !stillBefore
    }
}
