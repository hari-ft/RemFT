package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.alarm.AlarmBooking
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmPlanner

class AlarmSilencer(
    private val settings: SettingsStore,
    private val store: AlarmStore,
    private val gateway: AlarmGateway,
    private val planner: AlarmPlanner,
    private val scheduler: () -> AlarmScheduler,
    private val log: AlarmEventLog,
) {
    suspend fun silence(
        taskId: String,
        reminderId: String?,
        known: List<AlarmInput>,
        reason: String,
        lane: String = AlarmLogLane.NOTIFICATION,
    ): Int = try {
        val config = AlarmSettings.from(settings.snapshot())
        val sounds = scheduler().resolveSounds(config)
        val live = runCatching { store.alarmsOfTask(taskId) }.getOrDefault(emptyList())
        val targets = AlarmBooking.silenceTargets(
            planner = planner,
            taskId = taskId,
            reminderId = reminderId,
            known = known,
            live = live,
            shownIds = gateway.shownIds(),
            shownPayloads = gateway.shownPayloads(),
            privacyMode = config.privacyMode,
            profile = config.profile,
            alarmSoundName = sounds.alarm,
            reminderSoundName = sounds.reminder,
        )
        for (id in targets) {
            gateway.cancelShown(id)
            log.record(
                AlarmLogEvent.CANCELLED,
                lane,
                urgent = (known + live).firstOrNull()?.urgent ?: false,
                taskId = taskId,
                reminderId = reminderId,
                detail = "shown id=$id ($reason)",
            )
        }
        targets.size
    } catch (e: Exception) {
        log.record(
            AlarmLogEvent.DEGRADED,
            lane,
            taskId = taskId,
            reminderId = reminderId,
            detail = "silence shown ($reason) failed: ${e.javaClass.simpleName}",
        )
        0
    }
}
