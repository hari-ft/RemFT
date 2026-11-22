package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmWrite
import com.ftapps.remft.domain.alarm.NotificationActions
import com.ftapps.remft.domain.alarm.NotificationDecision
import com.ftapps.remft.domain.alarm.NotificationEvent
import com.ftapps.remft.domain.alarm.SnoozePresets
import java.time.Clock

enum class ActionResult { IGNORED, NOT_OURS, NOT_APPLIED, APPLIED, FAILED, UNVERIFIABLE }

class AlarmActionHandler(
    private val settings: SettingsStore,
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val silencer: AlarmSilencer,
    private val gateway: AlarmGateway,
    private val log: AlarmEventLog,
    private val appLog: AppLog,
    private val clock: Clock,
) {
    suspend fun handle(notificationId: Int?, actionId: String, payload: String?): ActionResult {
        if (payload == AlarmPlanner.TEST_PAYLOAD) return answerTest(notificationId, actionId)
        val parsed = AlarmPlanner.parsePayload(payload) ?: return ActionResult.IGNORED
        val taskId = parsed.taskId
        val reminderId = parsed.reminderId
        log.record(
            AlarmLogEvent.ACTION,
            AlarmLogLane.NOTIFICATION,
            kind = parsed.kind,
            slotIndex = parsed.chainIndex,
            taskId = taskId,
            reminderId = reminderId,
            detail = actionId,
        )
        try {
            settings.snapshot()
        } catch (_: SettingsUnavailable) {
            appLog.warn("alarm.action_unverified", mapOf("action" to LogCode(actionId)))
            log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, taskId = taskId, reminderId = reminderId, detail = "$actionId not verified: settings unreadable")
            return ActionResult.UNVERIFIABLE
        }
        return try {
            val asRang = runCatching { store.alarmsOfTask(taskId) }.getOrDefault(emptyList())
            val now = clock.instant()
            val decision = NotificationActions.decide(NotificationEvent(notificationId, actionId, payload), asRang, now) { id, _ ->
                AlarmPlanner.isCurrentRing(id, parsed, asRang)
            }
            act(decision, notificationId, asRang)
        } catch (e: Exception) {
            appLog.error("alarm.action_failed", e, mapOf("action" to LogCode(actionId)))
            log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, taskId = taskId, reminderId = reminderId, detail = "$actionId failed: ${e.javaClass.simpleName}")
            ActionResult.FAILED
        }
    }

    private suspend fun act(decision: NotificationDecision, notificationId: Int?, asRang: List<AlarmInput>): ActionResult = when (decision) {
        NotificationDecision.Ignore, is NotificationDecision.OpenTask -> ActionResult.IGNORED
        is NotificationDecision.NotOurs -> {
            appLog.warn(NotificationActions.LOG_NOT_OURS, mapOf("action" to LogCode(decision.action)))
            notificationId?.let { runCatching { gateway.cancelShown(it) } }
            ActionResult.NOT_OURS
        }
        is NotificationDecision.NotApplied -> notApplied(decision, notificationId)
        is NotificationDecision.Acknowledge -> {
            val write = store.acknowledge(decision.reminderId, decision.at)
            finish(write, decision.taskId, decision.reminderId, if (decision.silenceOnlyThisAlarm) decision.reminderId else null, asRang, NotificationActions.ACKNOWLEDGE, notificationId)
        }
        is NotificationDecision.Snooze -> {
            val until = SnoozePresets.untilForOrNull(decision.preset, clock.instant(), clock.zone)
            if (until == null) {
                ActionResult.IGNORED
            } else {
                val write = store.snooze(decision.taskId, until, decision.reminderId, decision.at)
                finish(write, decision.taskId, decision.reminderId, decision.reminderId, asRang, NotificationActions.snooze(decision.preset), notificationId)
            }
        }
        is NotificationDecision.Complete -> {
            val write = store.complete(decision.taskId)
            finish(write, decision.taskId, decision.reminderId, null, asRang, NotificationActions.COMPLETE, notificationId)
        }
    }

    private suspend fun finish(
        write: AlarmWrite,
        taskId: String,
        reminderId: String?,
        silenceReminder: String?,
        asRang: List<AlarmInput>,
        action: String,
        notificationId: Int?,
    ): ActionResult {
        if (!write.applied) {
            return notApplied(NotificationDecision.NotApplied(action, taskId, reminderId, NotificationActions.REASON_NOT_THE_ONE), notificationId)
        }
        silencer.silence(taskId, silenceReminder, asRang, action)
        notificationId?.let { runCatching { gateway.cancelShown(it) } }
        scheduler.pass(reason = "action")
        return ActionResult.APPLIED
    }

    private suspend fun answerTest(notificationId: Int?, actionId: String): ActionResult {
        runCatching { gateway.cancelShown(notificationId ?: AlarmPlanner.TEST_NOTIFICATION_ID) }
        log.record(AlarmLogEvent.TEST, AlarmLogLane.NOTIFICATION, urgent = true, detail = "test alarm answered: $actionId")
        return ActionResult.APPLIED
    }

    private suspend fun notApplied(decision: NotificationDecision.NotApplied, notificationId: Int?): ActionResult {
        appLog.info(NotificationActions.LOG_STALE, mapOf("action" to LogCode(decision.action)))
        log.record(AlarmLogEvent.ACTION, AlarmLogLane.NOTIFICATION, taskId = decision.taskId, reminderId = decision.reminderId, detail = decision.detail)
        notificationId?.let { runCatching { gateway.cancelShown(it) } }
        return ActionResult.NOT_APPLIED
    }
}
