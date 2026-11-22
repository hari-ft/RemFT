package com.ftapps.remft.domain.alarm

import java.time.Instant

data class BookingPlan(
    val toCancel: Set<Int>,
    val toBook: List<PlannedNotification>,
    val rebooking: Boolean,
)

data class AnnouncedNotification(val fireAt: Instant, val urgent: Boolean, val reminderId: String?)

enum class BookingOutcome { DELIVERED, DROPPED }

data class ReconciledNotification(val id: Int, val outcome: BookingOutcome, val announced: AnnouncedNotification)

data class ScheduledDelivery(val sound: String, val level: String)

enum class AlarmProblem { NOTIFICATIONS_OFF, ALARM_VOLUME_OFF, EXACT_ALARMS_OFF, FULL_SCREEN_OFF }

data class AlarmSupport(
    val notificationsEnabled: Boolean,
    val exactAlarmsEnabled: Boolean? = null,
    val fullScreenAllowed: Boolean? = null,
) {
    fun worstProblem(alarmVolumeOff: Boolean = false): AlarmProblem? = when {
        !notificationsEnabled -> AlarmProblem.NOTIFICATIONS_OFF
        alarmVolumeOff -> AlarmProblem.ALARM_VOLUME_OFF
        exactAlarmsEnabled == false -> AlarmProblem.EXACT_ALARMS_OFF
        fullScreenAllowed == false -> AlarmProblem.FULL_SCREEN_OFF
        else -> null
    }
}

class RebookPolicy {
    var rebookAll: Boolean = true
        private set

    private var rebookNext: Boolean = false

    fun requestRebook() {
        rebookNext = true
    }

    fun beginPass(): Boolean {
        val rebook = rebookAll || rebookNext
        rebookNext = false
        return rebook
    }

    fun endPass(rebooked: Boolean, failures: Int) {
        if (rebooked && failures == 0) rebookAll = false
    }
}

object AlarmBooking {
    const val LEVEL_TIME_SENSITIVE = "timeSensitive"
    const val OS_DEFAULT_SOUND_NAME = "os-default"

    fun plan(desired: List<PlannedNotification>, pending: Set<Int>, rebookEverything: Boolean): BookingPlan {
        val desiredById = LinkedHashMap<Int, PlannedNotification>()
        for (notification in desired) desiredById[notification.id] = notification
        val toCancel = pending.filterTo(LinkedHashSet()) { it !in desiredById && it != AlarmPlanner.TEST_NOTIFICATION_ID }
        val toBook = if (rebookEverything) desiredById.values.toList() else desiredById.values.filter { it.id !in pending }
        return BookingPlan(toCancel, toBook, rebookEverything)
    }

    fun reconcile(
        announced: Map<Int, AnnouncedNotification>,
        pending: Set<Int>,
        now: Instant,
    ): List<ReconciledNotification> = announced.entries
        .filter { it.key !in pending }
        .map { (id, entry) ->
            ReconciledNotification(
                id = id,
                outcome = if (entry.fireAt.isAfter(now)) BookingOutcome.DROPPED else BookingOutcome.DELIVERED,
                announced = entry,
            )
        }

    fun announce(notification: PlannedNotification): AnnouncedNotification =
        AnnouncedNotification(notification.fireAt, notification.urgent, notification.reminderId)

    fun deliveryFor(urgent: Boolean, soundName: String?): ScheduledDelivery = ScheduledDelivery(
        sound = soundName ?: if (urgent) AlarmSounds.ALARM else OS_DEFAULT_SOUND_NAME,
        level = LEVEL_TIME_SENSITIVE,
    )

    fun silenceTargets(
        planner: AlarmPlanner,
        taskId: String,
        reminderId: String?,
        known: List<AlarmInput>,
        live: List<AlarmInput>,
        shownIds: Set<Int>,
        privacyMode: Boolean,
        profile: ReminderProfile = ReminderProfile.Factory,
        alarmSoundName: String? = null,
        reminderSoundName: String? = null,
        shownPayloads: Map<Int, String?> = emptyMap(),
    ): Set<Int> {
        fun mine(alarm: AlarmInput): Boolean = alarm.taskId == taskId && (reminderId == null || alarm.reminderId == reminderId)
        val targets = LinkedHashSet<Int>()
        for ((id, payload) in shownPayloads) {
            if (AlarmPlanner.shownForTask(payload, taskId, reminderId)) targets.add(id)
        }
        val alarms = known.filter(::mine) + live.filter(::mine)
        if (alarms.isEmpty()) return targets
        val candidates = HashSet<Int>()
        for (alarm in alarms) {
            candidates.addAll(planner.notificationIdsForAlarm(alarm, privacyMode, profile, alarmSoundName, reminderSoundName))
        }
        shownIds.filterTo(targets) { it in candidates }
        return targets
    }
}
