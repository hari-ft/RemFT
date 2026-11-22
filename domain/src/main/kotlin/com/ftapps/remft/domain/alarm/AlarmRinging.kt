package com.ftapps.remft.domain.alarm

import java.time.Duration
import java.time.Instant

data class MissedAlarmSummary(val first: AlarmInput, val moreCount: Int, val all: List<AlarmInput>)

object AlarmRinging {
    val LiveWindow: Duration = Duration.ofMinutes(5)
    val MissedLookBack: Duration = Duration.ofHours(24)

    private class Rung(val alarm: AlarmInput, val latest: Instant)

    fun ringingAlarm(
        alarms: List<AlarmInput>,
        now: Instant,
        liveFor: Duration = LiveWindow,
        profile: ReminderProfile = ReminderProfile.Factory,
        times: Map<AlarmInput, List<Instant>>? = null,
        hidden: ((AlarmInput) -> Boolean)? = null,
    ): AlarmInput? {
        val rings = times ?: AlarmPlanner.plannedRingTimes(alarms, profile)
        val rung = ArrayList<Rung>()
        val dueRang = HashMap<String, AlarmInput>()
        for (alarm in alarms) {
            if (!alarm.urgent) continue
            val latest = latestRing(rings[alarm], now) ?: continue
            if (alarm.kind == AlarmKind.DUE) dueRang[alarm.taskId] = alarm
            if (!isLive(latest, now, liveFor)) continue
            rung.add(Rung(alarm, latest))
        }
        var best: Rung? = null
        for (entry in rung) {
            val shown = if (entry.alarm.kind == AlarmKind.DUE) entry.alarm else dueRang[entry.alarm.taskId] ?: entry.alarm
            if (hidden?.invoke(shown) == true) continue
            val candidate = Rung(shown, entry.latest)
            if (best == null || rangLater(candidate, best)) best = candidate
        }
        return best?.alarm
    }

    fun missedAlarms(
        alarms: List<AlarmInput>,
        now: Instant,
        liveFor: Duration = LiveWindow,
        lookBack: Duration = MissedLookBack,
        profile: ReminderProfile = ReminderProfile.Factory,
        held: Map<HeldRing, Instant> = emptyMap(),
    ): List<AlarmInput> {
        val floor = now.minus(lookBack)
        val rings = AlarmPlanner.plannedRingTimes(alarms, profile, held)
        val byTask = LinkedHashMap<String, Rung>()
        for (alarm in alarms) {
            if (!alarm.urgent) continue
            val slots = rings[alarm]
            val latest = latestRing(slots, now) ?: continue
            if (isLive(latest, now, liveFor)) continue
            if (slots!!.first().isBefore(floor)) continue
            val kept = byTask[alarm.taskId]
            val wins = when {
                kept == null -> true
                (alarm.kind == AlarmKind.DUE) != (kept.alarm.kind == AlarmKind.DUE) -> alarm.kind == AlarmKind.DUE
                else -> latest.isAfter(kept.latest)
            }
            if (wins) byTask[alarm.taskId] = Rung(alarm, latest)
        }
        return byTask.values.sortedByDescending { it.latest }.map { it.alarm }
    }

    fun missedSummary(missed: List<AlarmInput>): MissedAlarmSummary? {
        if (missed.isEmpty()) return null
        return MissedAlarmSummary(missed.first(), missed.size - 1, missed)
    }

    fun nextUrgentFireAfter(
        alarms: List<AlarmInput>,
        now: Instant,
        profile: ReminderProfile = ReminderProfile.Factory,
        times: Map<AlarmInput, List<Instant>>? = null,
    ): Instant? {
        val rings = times ?: AlarmPlanner.plannedRingTimes(alarms, profile)
        var next: Instant? = null
        for (alarm in alarms) {
            if (!alarm.urgent) continue
            for (at in rings[alarm].orEmpty()) {
                if (!at.isAfter(now)) continue
                if (next == null || at.isBefore(next)) next = at
                break
            }
        }
        return next
    }

    fun nextRealertAfter(
        alarm: AlarmInput,
        after: Instant,
        profile: ReminderProfile,
        times: Map<AlarmInput, List<Instant>>? = null,
    ): Instant? {
        val rings = times ?: AlarmPlanner.plannedRingTimes(listOf(alarm), profile)
        return rings[alarm].orEmpty().firstOrNull { it.isAfter(after) }
    }

    fun liveWindowEnds(
        alarms: List<AlarmInput>,
        now: Instant,
        times: Map<AlarmInput, List<Instant>>,
        liveFor: Duration = LiveWindow,
    ): Instant? {
        var next: Instant? = null
        for (alarm in alarms) {
            if (!alarm.urgent) continue
            val rang = latestRing(times[alarm], now) ?: continue
            val over = rang.plus(liveFor).plusSeconds(1)
            if (!over.isAfter(now)) continue
            if (next == null || over.isBefore(next)) next = over
        }
        return next
    }

    fun urgentRingAhead(
        alarms: List<AlarmInput>,
        now: Instant,
        profile: ReminderProfile = ReminderProfile.Factory,
    ): Boolean {
        val times = AlarmPlanner.plannedRingTimes(alarms, profile)
        return times.entries.any { (alarm, slots) -> alarm.urgent && slots.any { it.isAfter(now) } }
    }

    fun handledKey(alarm: AlarmInput): String =
        "${alarm.reminderId}|${alarm.status}|${alarm.fireAt.toEpochMilli()}"

    fun latestRing(slots: List<Instant>?, now: Instant): Instant? {
        var latest: Instant? = null
        for (at in slots.orEmpty()) {
            if (at.isAfter(now)) break
            latest = at
        }
        return latest
    }

    private fun isLive(ring: Instant, now: Instant, liveFor: Duration): Boolean =
        Duration.between(ring, now) <= liveFor

    private fun rangLater(a: Rung, b: Rung): Boolean {
        var c = a.latest.compareTo(b.latest)
        if (c != 0) return c > 0
        c = a.alarm.fireAt.compareTo(b.alarm.fireAt)
        if (c != 0) return c > 0
        val aDue = a.alarm.kind == AlarmKind.DUE
        val bDue = b.alarm.kind == AlarmKind.DUE
        if (aDue != bDue) return aDue
        return a.alarm.reminderId < b.alarm.reminderId
    }
}
