package com.ftapps.remft.domain.alarm

import java.time.Instant

class OverlayHarness(var now: Instant, private val profile: ReminderProfile = ReminderProfile.Factory) {
    private val handledKeys = HashSet<String>()
    private val deferredKeys = HashMap<String, Instant>()

    var feed: List<AlarmInput> = emptyList()
        private set
    var ringing: AlarmInput? = null
        private set
    var wakeAt: Instant? = null
        private set

    fun emit(alarms: List<AlarmInput>) {
        feed = alarms
        evaluate()
    }

    fun answer(reminderId: String) {
        for (alarm in feed + listOfNotNull(ringing)) {
            if (alarm.reminderId == reminderId) handledKeys.add(AlarmRinging.handledKey(alarm))
        }
        evaluate()
    }

    fun lookAway(reminderId: String) {
        for (alarm in feed + listOfNotNull(ringing)) {
            if (alarm.reminderId == reminderId) deferredKeys[AlarmRinging.handledKey(alarm)] = now
        }
        evaluate()
    }

    fun reconsider() = evaluate()

    private fun evaluate() {
        val alarms = feed
        val current = alarms.mapTo(HashSet()) { AlarmRinging.handledKey(it) }
        handledKeys.retainAll(current)
        deferredKeys.keys.retainAll(current)
        val times = AlarmPlanner.plannedRingTimes(alarms, profile)
        var nextRealert: Instant? = null
        for (alarm in alarms) {
            val key = AlarmRinging.handledKey(alarm)
            val since = deferredKeys[key] ?: continue
            val next = AlarmRinging.nextRealertAfter(alarm, since, profile, times) ?: continue
            if (!next.isAfter(now)) {
                deferredKeys.remove(key)
            } else if (nextRealert == null || next.isBefore(nextRealert)) {
                nextRealert = next
            }
        }
        val shown = AlarmRinging.ringingAlarm(
            alarms,
            now,
            profile = profile,
            times = times,
            hidden = { AlarmRinging.handledKey(it).let { key -> key in handledKeys || key in deferredKeys } },
        )
        var next = AlarmRinging.nextUrgentFireAfter(alarms, now, profile, times)
        if (nextRealert != null && (next == null || nextRealert.isBefore(next))) next = nextRealert
        if (shown != null) {
            val over = AlarmRinging.liveWindowEnds(alarms, now, times)
            if (over != null && (next == null || over.isBefore(next))) next = over
        }
        ringing = shown
        wakeAt = next
    }
}
