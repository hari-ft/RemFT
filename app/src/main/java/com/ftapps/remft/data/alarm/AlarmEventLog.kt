package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.AlarmEventEntity
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.alarm.ScheduledDelivery
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant

object AlarmLogEvent {
    const val SCHEDULED = "scheduled"
    const val CANCELLED = "cancelled"
    const val INTERACTED = "interacted"
    const val ACTION = "action"
    const val RING_SHOWN = "ring_shown"
    const val DEGRADED = "degraded"
    const val DELIVERED = "delivered"
    const val DROPPED = "dropped"
    const val TEST = "test"
}

object AlarmLogLane {
    const val NOTIFICATION = "notification"
    const val INAPP = "inapp"
}

class AlarmEventLog(private val database: () -> RemftDatabase, private val clock: Clock) {
    private val dao: AlarmDao get() = database().alarmDao()

    suspend fun record(
        event: String,
        lane: String,
        kind: String? = null,
        slotIndex: Int? = null,
        urgent: Boolean = false,
        sound: String? = null,
        level: String? = null,
        fireAt: Instant? = null,
        taskId: String? = null,
        reminderId: String? = null,
        detail: String? = null,
    ) {
        try {
            dao.insertEvent(
                AlarmEventEntity(
                    at = AlarmMapping.text(clock.instant())!!,
                    event = event,
                    lane = lane,
                    kind = kind,
                    slotIndex = slotIndex,
                    urgent = urgent,
                    sound = sound,
                    level = level,
                    fireAt = AlarmMapping.text(fireAt),
                    taskId = taskId,
                    reminderId = reminderId,
                    detail = detail,
                ),
            )
            dao.trimEvents(LIMIT)
        } catch (_: Exception) {
        }
    }

    suspend fun recordScheduled(notification: PlannedNotification, delivery: ScheduledDelivery, event: String = AlarmLogEvent.SCHEDULED) =
        record(
            event = event,
            lane = AlarmLogLane.NOTIFICATION,
            kind = notification.kind,
            slotIndex = notification.slotIndex,
            urgent = notification.urgent,
            sound = delivery.sound,
            level = delivery.level,
            fireAt = notification.fireAt,
            taskId = notification.taskId,
            reminderId = notification.reminderId,
            detail = "id=${notification.id}",
        )

    suspend fun recent(limit: Int = LIMIT): List<AlarmEventEntity> = try {
        dao.recentEvents(limit)
    } catch (_: Exception) {
        emptyList()
    }

    fun watch(limit: Int = LIMIT): Flow<List<AlarmEventEntity>> = dao.watchRecentEvents(limit)

    suspend fun clear() = dao.clearEvents()

    companion object {
        const val LIMIT = 200

        fun formatLine(event: AlarmEventEntity): String = buildList {
            add(event.at)
            add(event.event)
            add(event.lane)
            event.kind?.let { add(it) }
            event.slotIndex?.let { add("slot $it") }
            if (event.urgent) add("urgent")
            event.sound?.let { add("sound=$it") }
            event.level?.let { add("level=$it") }
            event.fireAt?.let { add("fireAt=$it") }
            event.reminderId?.let { add("reminder=$it") }
            event.detail?.let { add(it) }
        }.joinToString(" · ")
    }
}
