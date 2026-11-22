package com.ftapps.remft.ui.alarm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.alarm.AlarmLogLane
import com.ftapps.remft.data.alarm.LogCode
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.AlarmWrite
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun AlarmRingHost(env: AlarmEnvironment, onOpenTask: (String) -> Unit, content: @Composable () -> Unit) {
    val state by env.ring.state.collectAsStateWithLifecycle()
    val missedShown by env.ring.missedShown.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        val ringing = state.ringing
        val missed = missedShown
        when {
            ringing != null -> key("ring-${ringing.reminderId}-${ringing.fireAt}") {
                AlarmRingOverlay(env, ringing, missed = false, onOpenTask = onOpenTask)
            }
            missed != null -> key("missed-${missed.reminderId}") {
                AlarmRingOverlay(env, missed, missed = true, onOpenTask = onOpenTask)
            }
        }
    }
}

@Composable
fun AlarmRingOverlay(env: AlarmEnvironment, alarm: AlarmInput, missed: Boolean, onOpenTask: (String) -> Unit) {
    val context = LocalContext.current
    val snoozeOrder by remember(env) { env.settings.flow(SettingKeys.SnoozePresets).map { SnoozePresets.parseOrder(it) } }
        .collectAsStateWithLifecycle(SnoozePresets.ids)
    val dateFormat by remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }
        .collectAsStateWithLifecycle(DateFormatId.System)
    val now by produceState(env.clock.instant(), env) {
        while (true) {
            value = env.clock.instant()
            delay(CLOCK_REFRESH_MS)
        }
    }
    val feedback = remember(alarm.reminderId, missed) {
        if (missed) {
            SilentFeedback()
        } else {
            var choice: SoundChoice = AlarmSounds.DefaultAlarmChoice
            MediaAlarmFeedback(
                context = context.applicationContext,
                chosen = { env.sounds.playbackUri(choice) },
                fallback = { env.sounds.rawUri(AlarmSounds.ALARM) },
            ).also { player ->
                env.scope.launch {
                    choice = SoundChoice.parse(env.settings.value(SettingKeys.AlarmSound))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.start() }
                }
            }
        }
    }
    val feedbackState by feedback.state.collectAsStateWithLifecycle()
    val volumeOff = remember(alarm.reminderId) { !missed && env.health.status().alarmVolumeOff == true }
    val wholeTask = alarm.kind == AlarmKind.DUE
    var loggedTrouble by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(feedback) {
        onDispose { feedback.stop() }
    }
    LaunchedEffect(alarm.reminderId, missed) {
        if (missed) return@LaunchedEffect
        env.log.record(
            AlarmLogEvent.RING_SHOWN,
            AlarmLogLane.INAPP,
            kind = alarm.kind,
            urgent = alarm.urgent,
            fireAt = alarm.fireAt,
            taskId = alarm.taskId,
            reminderId = alarm.reminderId,
        )
        if (volumeOff) {
            env.log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.INAPP, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "alarmVolume=0")
        }
        while (true) {
            feedback.pulse()
            delay(HAPTIC_MS)
        }
    }
    LaunchedEffect(feedbackState.playing) {
        if (missed || !feedbackState.playing) return@LaunchedEffect
        while (true) {
            env.silencer.silence(alarm.taskId, if (wholeTask) null else alarm.reminderId, listOf(alarm), "in-app ring", AlarmLogLane.INAPP)
            delay(SWEEP_MS)
        }
    }
    LaunchedEffect(feedbackState.fallback, feedbackState.blocked) {
        val notes = buildList {
            if (feedbackState.fallback) add("chosen sound failed; playing the default")
            if (feedbackState.blocked) add("in-app sound blocked")
        }.filter { it !in loggedTrouble }
        for (note in notes) {
            env.log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.INAPP, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = note)
            env.appLog.warn("alarm.sound_trouble", mapOf("note" to LogCode(if (note.startsWith("chosen")) "fallback" else "blocked")))
        }
        loggedTrouble = loggedTrouble + notes
    }

    val actions = remember(alarm, missed) { HostActions(env, alarm, missed, feedback, onOpenTask) }
    AlarmRingScreen(
        alarm = alarm,
        missed = missed,
        now = now,
        zone = env.clock.zone,
        dateFormat = dateFormat,
        snoozeOrder = snoozeOrder,
        feedback = feedbackState,
        volumeOff = volumeOff,
        actions = actions,
        currentTime = { env.clock.instant() },
    )
}

private const val CLOCK_REFRESH_MS = 60_000L
private const val HAPTIC_MS = 2_000L
private const val SWEEP_MS = 5_000L

class HostActions(
    private val env: AlarmEnvironment,
    private val alarm: AlarmInput,
    private val missed: Boolean,
    private val feedback: AlarmFeedback,
    private val onOpenTask: (String) -> Unit,
) : AlarmRingActions {
    private val lane = AlarmLogLane.INAPP

    private suspend fun answer(reason: String, wholeTask: Boolean, write: suspend () -> AlarmWrite): RingAnswer {
        val asRang = if (wholeTask) runCatching { env.store.alarmsOfTask(alarm.taskId) }.getOrDefault(emptyList()) else emptyList()
        val result = try {
            write()
        } catch (e: Exception) {
            env.appLog.error("alarm.action_failed", e, mapOf("action" to LogCode(reason)))
            env.log.record(AlarmLogEvent.DEGRADED, lane, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "$reason failed: ${e.javaClass.simpleName}")
            return RingAnswer.FAILED
        }
        if (!result.applied) {
            env.log.record(AlarmLogEvent.ACTION, lane, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "$reason not applied: the alarm changed")
            done()
            return RingAnswer.NOT_APPLIED
        }
        env.log.record(AlarmLogEvent.ACTION, lane, kind = alarm.kind, urgent = alarm.urgent, fireAt = alarm.remindAt, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = reason)
        feedback.stop()
        env.silencer.silence(alarm.taskId, if (wholeTask) null else alarm.reminderId, listOf(alarm) + asRang, reason, lane)
        done()
        env.scheduler.request()
        return RingAnswer.APPLIED
    }

    private fun done() {
        if (missed) {
            env.ring.handled(alarm.reminderId)
            env.ring.dismissMissed()
        } else {
            env.ring.handled(alarm.reminderId)
        }
    }

    override suspend fun acknowledge(): RingAnswer {
        val answer = answer("acknowledged", alarm.kind == AlarmKind.DUE) { env.store.acknowledge(alarm.reminderId, alarm.remindAt) }
        if (answer == RingAnswer.APPLIED) onOpenTask(alarm.taskId)
        return answer
    }

    override suspend fun snooze(until: Instant): RingAnswer =
        answer("snoozed", false) { env.store.snooze(alarm.taskId, until, alarm.reminderId, alarm.remindAt) }

    override suspend fun complete(): RingAnswer = answer("completed", true) { env.store.complete(alarm.taskId) }

    override suspend fun silence(): RingAnswer = answer("muted", true) { env.store.mute(alarm.taskId) }

    override fun open() {
        env.scope.launch {
            env.log.record(AlarmLogEvent.ACTION, lane, kind = alarm.kind, urgent = alarm.urgent, fireAt = alarm.remindAt, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "opened")
            env.silencer.silence(alarm.taskId, alarm.reminderId, listOf(alarm), "opened", lane)
        }
        feedback.stop()
        if (missed) {
            env.ring.dismissMissed()
        } else {
            env.ring.deferred(alarm.reminderId)
        }
        onOpenTask(alarm.taskId)
    }

    override fun startSound() = feedback.start()

    override fun dismissMissed() = env.ring.dismissMissed()
}
