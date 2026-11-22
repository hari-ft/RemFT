package com.ftapps.remft.domain.alarm

import java.time.Instant

data class NotificationEvent(val id: Int? = null, val actionId: String? = null, val payload: String? = null)

data class NotificationButton(val actionId: String, val label: ButtonLabel)

enum class ButtonLabel { ACKNOWLEDGE, SNOOZE_5_MIN, COMPLETE, SNOOZE_30_MIN, SNOOZE_1_HOUR }

sealed interface NotificationDecision {
    data object Ignore : NotificationDecision

    data class OpenTask(val taskId: String, val reminderId: String?, val slotIndex: Int?) : NotificationDecision

    data class NotOurs(val action: String, val taskId: String, val reminderId: String?) : NotificationDecision

    data class NotApplied(val action: String, val taskId: String, val reminderId: String?, val reason: String) : NotificationDecision {
        val detail: String
            get() = NotificationActions.notAppliedDetail(action, reason)
    }

    data class Acknowledge(
        val taskId: String,
        val reminderId: String,
        val at: Instant?,
        val silenceOnlyThisAlarm: Boolean,
    ) : NotificationDecision

    data class Snooze(
        val taskId: String,
        val reminderId: String,
        val at: Instant?,
        val preset: String,
    ) : NotificationDecision {
        val silenceOnlyThisAlarm: Boolean
            get() = true
    }

    data class Complete(val taskId: String, val reminderId: String?) : NotificationDecision
}

object NotificationActions {
    const val COMPLETE = "complete"
    const val ACKNOWLEDGE = "acknowledge"
    const val SNOOZE_PREFIX = "snooze:"

    const val REASON_MOVED_LATER = "the alarm moved to a later time"
    const val REASON_NOT_THE_ONE = "the alarm is no longer the one that rang"

    const val LOG_NOT_OURS = "alarm.event_not_ours"
    const val LOG_STALE = "alarm.action_stale"

    fun snooze(preset: String): String = "$SNOOZE_PREFIX$preset"

    fun buttonsFor(urgent: Boolean): List<NotificationButton> = if (urgent) {
        listOf(
            NotificationButton(ACKNOWLEDGE, ButtonLabel.ACKNOWLEDGE),
            NotificationButton(snooze(SnoozePresets.FIVE_MIN), ButtonLabel.SNOOZE_5_MIN),
        )
    } else {
        listOf(
            NotificationButton(COMPLETE, ButtonLabel.COMPLETE),
            NotificationButton(snooze(SnoozePresets.THIRTY_MIN), ButtonLabel.SNOOZE_30_MIN),
            NotificationButton(snooze(SnoozePresets.ONE_HOUR), ButtonLabel.SNOOZE_1_HOUR),
        )
    }

    fun notAppliedDetail(action: String, reason: String): String = "$action not applied: $reason; notification dismissed"

    fun taskRoute(taskId: String): String = "/tasks/$taskId"

    fun decide(
        event: NotificationEvent,
        asRang: List<AlarmInput>,
        now: Instant,
        isOurs: ((Int?, String) -> Boolean)? = null,
    ): NotificationDecision {
        val payload = AlarmPlanner.parsePayload(event.payload) ?: return NotificationDecision.Ignore
        val taskId = payload.taskId
        val reminderId = payload.reminderId
        val at = payload.at
        val action = event.actionId ?: ""
        if (action.isEmpty()) return NotificationDecision.OpenTask(taskId, reminderId, payload.chainIndex)
        if (isOurs != null && (reminderId == null || !isOurs(event.id, reminderId))) {
            return NotificationDecision.NotOurs(action, taskId, reminderId)
        }
        val isSnooze = action.startsWith(SNOOZE_PREFIX)
        val oneAlarm = action == ACKNOWLEDGE || isSnooze
        if (oneAlarm) {
            if (reminderId == null) return NotificationDecision.Ignore
            if (at == null) {
                val current = asRang.firstOrNull { it.reminderId == reminderId }
                if (current != null && current.fireAt.isAfter(now)) {
                    return NotificationDecision.NotApplied(action, taskId, reminderId, REASON_MOVED_LATER)
                }
            }
            if (action == ACKNOWLEDGE) {
                val wholeTask = AlarmSelection.acknowledgesWholeTask(reminderId, payload.kind)
                return NotificationDecision.Acknowledge(taskId, reminderId, at, silenceOnlyThisAlarm = !wholeTask)
            }
            return NotificationDecision.Snooze(taskId, reminderId, at, action.substring(SNOOZE_PREFIX.length))
        }
        if (action == COMPLETE) return NotificationDecision.Complete(taskId, reminderId)
        return NotificationDecision.Ignore
    }
}
