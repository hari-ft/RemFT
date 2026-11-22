package com.ftapps.remft.domain.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import java.util.TreeMap

data class PlannedNotification(
    val id: Int,
    val title: String,
    val body: String,
    val fireAt: Instant,
    val urgent: Boolean,
    val payload: String,
    val kind: String? = null,
    val slotIndex: Int? = null,
    val taskId: String? = null,
    val reminderId: String? = null,
    val soundName: String? = null,
)

data class HeldRing(val reminderId: String, val slot: Int, val planned: Long)

data class AlarmPayload(
    val taskId: String,
    val reminderId: String?,
    val kind: String?,
    val at: Instant?,
    val chainIndex: Int?,
    val planned: Long?,
    val fireAt: Long?,
    val base: Long? = null,
)

private class Ring(
    val alarm: AlarmInput,
    val index: Int,
    val originalFireAt: Instant,
    val title: String,
    val body: String,
    val soundName: String?,
) {
    var shifts: Int = 0
    var heldAt: Instant? = null

    val fireAt: Instant
        get() = heldAt ?: originalFireAt.plus(AlarmPlanner.RingCollisionShift.multipliedBy(shifts.toLong()))

    val heldKey: HeldRing
        get() = HeldRing(alarm.reminderId, index, originalFireAt.toEpochMilli())
}

class AlarmPlanner(private val texts: AlarmTexts) {

    fun planNotifications(
        alarms: List<AlarmInput>,
        now: Instant,
        privacyMode: Boolean,
        maxPending: Int = MAX_PENDING,
        profile: ReminderProfile = ReminderProfile.Factory,
        alarmSoundName: String? = null,
        reminderSoundName: String? = null,
        held: Map<HeldRing, Instant> = emptyMap(),
    ): List<PlannedNotification> {
        val rings = ringsOf(alarms, privacyMode, profile, alarmSoundName, reminderSoundName, texts)
        spaceCollidingRings(rings)
        applyHeld(rings, held)
        val upcoming = rings
            .filter { it.fireAt.isAfter(now) }
            .map { it to ringKey(it, it.fireAt) }
            .sortedWith(compareBy<Pair<Ring, String>> { it.first.fireAt }.thenBy { it.second })
            .take(maxPending)
        val ids = NotificationIds.assign(upcoming.map { it.second })
        return upcoming.mapIndexed { position, (ring, _) -> notificationOf(ring, ids[position]) }
    }

    fun currentRing(
        alarms: List<AlarmInput>,
        shown: AlarmInput,
        now: Instant,
        privacyMode: Boolean,
        profile: ReminderProfile = ReminderProfile.Factory,
        alarmSoundName: String? = null,
        reminderSoundName: String? = null,
        held: Map<HeldRing, Instant> = emptyMap(),
    ): PlannedNotification? {
        val feed = if (alarms.any { it.reminderId == shown.reminderId }) alarms else alarms + shown
        val rings = ringsOf(feed, privacyMode, profile, alarmSoundName, reminderSoundName, texts)
        spaceCollidingRings(rings)
        applyHeld(rings, held)
        val ring = rings
            .filter { it.alarm.taskId == shown.taskId && it.alarm.urgent && !it.fireAt.isAfter(now) }
            .maxWithOrNull(compareBy<Ring> { it.fireAt }.thenBy { if (it.alarm.reminderId == shown.reminderId) 1 else 0 })
            ?: return null
        return notificationOf(ring, NotificationIds.ring(ringKey(ring, ring.fireAt)))
    }

    private fun notificationOf(ring: Ring, id: Int): PlannedNotification {
        val fireAt = ring.fireAt
        val alarm = ring.alarm
        return PlannedNotification(
            id = id,
            title = ring.title,
            body = ring.body,
            fireAt = fireAt,
            urgent = alarm.urgent,
            payload = encodePayload(
                taskId = alarm.taskId,
                reminderId = alarm.reminderId,
                kind = alarm.kind,
                atMillis = alarm.remindAt.toEpochMilli(),
                chainIndex = ring.index,
                plannedMillis = ring.originalFireAt.toEpochMilli(),
                fireAtMillis = fireAt.toEpochMilli(),
                baseMillis = alarm.fireAt.toEpochMilli(),
            ),
            kind = alarm.kind,
            slotIndex = ring.index,
            taskId = alarm.taskId,
            reminderId = alarm.reminderId,
            soundName = ring.soundName,
        )
    }

    fun notificationIdsForAlarm(
        alarm: AlarmInput,
        privacyMode: Boolean,
        profile: ReminderProfile = ReminderProfile.Factory,
        alarmSoundName: String? = null,
        reminderSoundName: String? = null,
    ): Set<Int> {
        val ids = LinkedHashSet<Int>()
        for (ring in ringsOf(listOf(alarm), privacyMode, profile, alarmSoundName, reminderSoundName, texts)) {
            for (shift in 0..MAX_RING_COLLISION_SHIFTS_MATCHED) {
                ids.addAll(NotificationIds.matchedRings(ringKey(ring, ring.originalFireAt.plus(RingCollisionShift.multipliedBy(shift.toLong())))))
            }
        }
        return ids
    }

    fun testAlarm(now: Instant, soundName: String? = null): PlannedNotification = PlannedNotification(
        id = TEST_NOTIFICATION_ID,
        title = texts.testTitle,
        body = texts.testBody,
        fireAt = now.plus(TestAlarmDelay),
        urgent = true,
        payload = TEST_PAYLOAD,
        kind = TEST_KIND,
        soundName = soundName,
    )

    companion object {
        const val MAX_PENDING = 40
        const val RING_FORMAT = 1
        const val MAX_RING_COLLISION_SHIFTS_MATCHED = 12
        const val TEST_NOTIFICATION_ID = NotificationIds.TEST_ALARM
        const val TEST_PAYLOAD = "{\"test\":true}"
        const val TEST_KIND = "test"

        val RingCollisionShift: Duration = Duration.ofMinutes(15)
        val TestAlarmDelay: Duration = Duration.ofSeconds(15)

        private val json = Json

        fun plannedRingTimes(
            alarms: List<AlarmInput>,
            profile: ReminderProfile = ReminderProfile.Factory,
            held: Map<HeldRing, Instant> = emptyMap(),
        ): Map<AlarmInput, List<Instant>> {
            val rings = ringsOf(alarms, true, profile, null, null, null)
            spaceCollidingRings(rings)
            applyHeld(rings, held)
            val times = IdentityHashMap<AlarmInput, MutableList<Instant>>()
            for (ring in rings) times.getOrPut(ring.alarm) { ArrayList() }.add(ring.fireAt)
            for (slots in times.values) slots.sort()
            return times
        }

        fun isCurrentRing(notificationId: Int?, payload: AlarmPayload, alarms: List<AlarmInput>): Boolean {
            if (notificationId == null || !NotificationIds.isRing(notificationId)) return false
            val reminderId = payload.reminderId ?: return false
            val alarm = alarms.firstOrNull { it.reminderId == reminderId } ?: return false
            if (!ReminderStatus.isActive(alarm.status)) return false
            if (payload.at != null && payload.at != alarm.remindAt) return false
            val base = payload.base
            if (base != null) return base == alarm.fireAt.toEpochMilli()
            val planned = payload.planned ?: return true
            return planned >= alarm.fireAt.toEpochMilli()
        }

        fun shownForTask(payload: String?, taskId: String, reminderId: String?): Boolean {
            val parsed = parsePayload(payload) ?: return false
            if (parsed.taskId != taskId) return false
            return reminderId == null || parsed.reminderId == reminderId
        }

        fun heldRingsFromPayloads(payloads: Iterable<String?>, now: Instant): Map<HeldRing, Instant> {
            val held = LinkedHashMap<HeldRing, Instant>()
            for (raw in payloads) {
                if (raw == null) continue
                val decoded = try {
                    json.parseToJsonElement(raw)
                } catch (error: Exception) {
                    continue
                }
                if (decoded !is JsonObject) continue
                val reminderId = stringField(decoded, "reminderId") ?: continue
                val slot = intField(decoded, "chainIndex") ?: continue
                val planned = intField(decoded, "planned") ?: continue
                val fireAt = intField(decoded, "fireAt") ?: continue
                val at = Instant.ofEpochMilli(fireAt)
                if (!at.isAfter(now)) continue
                held[HeldRing(reminderId, slot.toInt(), planned)] = at
            }
            return held
        }

        fun parsePayload(raw: String?): AlarmPayload? {
            if (raw.isNullOrEmpty()) return null
            val decoded = try {
                json.parseToJsonElement(raw)
            } catch (error: Exception) {
                return null
            }
            if (decoded !is JsonObject) return null
            val taskId = stringField(decoded, "taskId") ?: return null
            return AlarmPayload(
                taskId = taskId,
                reminderId = stringField(decoded, "reminderId"),
                kind = stringField(decoded, "kind"),
                at = intField(decoded, "at")?.let { Instant.ofEpochMilli(it) },
                chainIndex = intField(decoded, "chainIndex")?.toInt(),
                planned = intField(decoded, "planned"),
                fireAt = intField(decoded, "fireAt"),
                base = intField(decoded, "base"),
            )
        }

        fun encodePayload(
            taskId: String,
            reminderId: String,
            kind: String,
            atMillis: Long,
            chainIndex: Int,
            plannedMillis: Long,
            fireAtMillis: Long,
            baseMillis: Long? = null,
        ): String = buildString {
            append("{\"taskId\":").append(JsonPrimitive(taskId))
            append(",\"reminderId\":").append(JsonPrimitive(reminderId))
            append(",\"kind\":").append(JsonPrimitive(kind))
            append(",\"at\":").append(atMillis)
            append(",\"chainIndex\":").append(chainIndex)
            append(",\"planned\":").append(plannedMillis)
            append(",\"fireAt\":").append(fireAtMillis)
            if (baseMillis != null) append(",\"base\":").append(baseMillis)
            append('}')
        }

        fun ringKey(
            reminderId: String,
            slot: Int,
            fireAtMillis: Long,
            title: String,
            body: String,
            urgent: Boolean,
            soundName: String?,
        ): String = buildString {
            append("ring/").append(RING_FORMAT)
            append('|').append(reminderId)
            append('|').append(slot)
            append('|').append(fireAtMillis)
            append('|').append(if (urgent) "urgent" else "plain")
            append('|').append(soundName?.length ?: -1).append(':').append(soundName.orEmpty())
            append('|').append(title.length).append(':').append(title)
            append('|').append(body.length).append(':').append(body)
        }

        private val integerText = Regex("^-?\\d+$")

        private fun stringField(obj: JsonObject, key: String): String? {
            val primitive = obj[key] as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.content else null
        }

        private fun intField(obj: JsonObject, key: String): Long? {
            val primitive = obj[key] as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            if (!integerText.matches(primitive.content)) return null
            return primitive.content.toLongOrNull()
        }

        private fun ringsOf(
            alarms: List<AlarmInput>,
            privacyMode: Boolean,
            profile: ReminderProfile,
            alarmSoundName: String?,
            reminderSoundName: String?,
            texts: AlarmTexts?,
        ): MutableList<Ring> {
            val rings = ArrayList<Ring>()
            for (alarm in alarms) {
                if (!ReminderStatus.isActive(alarm.status)) continue
                val base = alarm.fireAt
                val repeats = alarm.urgent &&
                    alarm.requiresAcknowledgement &&
                    (alarm.status != ReminderStatus.SNOOZED || profile.repeatAfterSnooze)
                val chain = if (repeats) profile.offsets else listOf(Duration.ZERO)
                chain.forEachIndexed { index, offset ->
                    rings.add(
                        Ring(
                            alarm = alarm,
                            index = index,
                            originalFireAt = base.plus(offset),
                            title = texts?.let { titleOf(alarm, privacyMode, it) } ?: "",
                            body = texts?.let { bodyOf(alarm, index, privacyMode, it) } ?: "",
                            soundName = if (alarm.urgent) alarmSoundName else reminderSoundName,
                        ),
                    )
                }
            }
            return rings
        }

        private fun titleOf(alarm: AlarmInput, privacyMode: Boolean, texts: AlarmTexts): String = when {
            privacyMode -> texts.privacyTitle
            alarm.urgent -> texts.urgentTitle(alarm.taskTitle)
            else -> alarm.taskTitle
        }

        private fun bodyOf(alarm: AlarmInput, index: Int, privacyMode: Boolean, texts: AlarmTexts): String = when {
            privacyMode -> texts.privateBody
            !alarm.urgent -> texts.reminder
            index == 0 -> firstBody(alarm, texts)
            else -> texts.urgentRepeatBody(index + 1)
        }

        private fun firstBody(alarm: AlarmInput, texts: AlarmTexts): String = when {
            alarm.status == ReminderStatus.SNOOZED -> texts.afterSnoozeBody(if (alarm.snoozeRound > 0) alarm.snoozeRound else 1)
            alarm.kind == AlarmKind.DUE -> texts.dueNow
            else -> texts.urgentFirst
        }

        private fun ringKey(ring: Ring, fireAt: Instant): String = ringKey(
            reminderId = ring.alarm.reminderId,
            slot = ring.index,
            fireAtMillis = fireAt.toEpochMilli(),
            title = ring.title,
            body = ring.body,
            urgent = ring.alarm.urgent,
            soundName = ring.soundName,
        )

        private fun applyHeld(rings: List<Ring>, held: Map<HeldRing, Instant>) {
            if (held.isEmpty()) return
            for (ring in rings) {
                val at = held[ring.heldKey]
                if (at != null && at.isAfter(ring.fireAt)) ring.heldAt = at
            }
        }

        private fun minuteOf(instant: Instant): Long = instant.toEpochMilli() / 60_000L

        private val ringPrecedence: Comparator<Ring> = Comparator { a, b ->
            fun flag(x: Boolean, y: Boolean): Int = if (x == y) 0 else if (x) -1 else 1
            var c = flag(a.index == 0, b.index == 0)
            if (c != 0) return@Comparator c
            c = flag(a.alarm.urgent, b.alarm.urgent)
            if (c != 0) return@Comparator c
            c = flag(a.shifts == 0, b.shifts == 0)
            if (c != 0) return@Comparator c
            c = flag(a.alarm.kind == AlarmKind.DUE, b.alarm.kind == AlarmKind.DUE)
            if (c != 0) return@Comparator c
            c = a.alarm.remindAt.compareTo(b.alarm.remindAt)
            if (c != 0) return@Comparator c
            c = a.alarm.taskId.compareTo(b.alarm.taskId)
            if (c != 0) return@Comparator c
            c = a.alarm.reminderId.compareTo(b.alarm.reminderId)
            if (c != 0) return@Comparator c
            c = a.index.compareTo(b.index)
            if (c != 0) return@Comparator c
            a.originalFireAt.compareTo(b.originalFireAt)
        }

        private fun spaceCollidingRings(rings: List<Ring>) {
            val byMinute = TreeMap<Long, MutableList<Ring>>()
            val chains = IdentityHashMap<AlarmInput, MutableList<Ring>>()
            for (ring in rings) {
                byMinute.getOrPut(minuteOf(ring.fireAt)) { ArrayList() }.add(ring)
                chains.getOrPut(ring.alarm) { ArrayList() }.add(ring)
            }
            while (byMinute.isNotEmpty()) {
                val here = byMinute.pollFirstEntry().value
                if (here.size < 2) continue
                here.sortWith(ringPrecedence)
                for (moved in here.drop(1)) {
                    for (ring in chains.getValue(moved.alarm)) {
                        if (ring.index < moved.index) continue
                        if (ring !== moved) byMinute[minuteOf(ring.fireAt)]?.removeIf { it === ring }
                        ring.shifts++
                        byMinute.getOrPut(minuteOf(ring.fireAt)) { ArrayList() }.add(ring)
                    }
                }
            }
        }
    }
}
