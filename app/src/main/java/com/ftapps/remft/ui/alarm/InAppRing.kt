package com.ftapps.remft.ui.alarm

import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.alarm.AlarmLogLane
import com.ftapps.remft.data.alarm.LogCode
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import kotlin.coroutines.CoroutineContext

class InAppRing(
    private val env: AlarmEnvironment,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val sweepMs: Long = SWEEP_MS,
    private val feedbackFor: (() -> SoundChoice) -> AlarmFeedback = { choice ->
        MediaAlarmFeedback(
            context = env.context.applicationContext,
            chosen = { env.sounds.playbackUri(choice()) },
            fallback = { env.sounds.rawUri(AlarmSounds.ALARM) },
        )
    },
) {
    private class Session(val key: String, val alarm: AlarmInput, feedbackFor: (() -> SoundChoice) -> AlarmFeedback) {
        var choice: SoundChoice = AlarmSounds.DefaultAlarmChoice
        val feedback: AlarmFeedback = feedbackFor { choice }
        var begun = false
        var starter: Job? = null
        var sweep: Job? = null
        val trouble = HashSet<String>()
    }

    @Volatile
    private var session: Session? = null
    private val shownToUser = MutableStateFlow(false)
    private val notices = Mutex()

    val visible: StateFlow<Boolean> = shownToUser.asStateFlow()

    val ringingKey: String? get() = session?.key

    fun attach(alarm: AlarmInput, volumeOff: Boolean): AlarmFeedback {
        val key = keyOf(alarm)
        session?.let { if (it.key == key) return it.feedback }
        stopSession()
        val created = Session(key, alarm, feedbackFor)
        session = created
        env.scope.launch {
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
        }
        return created.feedback
    }

    fun keepOnly(key: String?) {
        if (session?.key != key) stopSession()
    }

    fun stop(alarm: AlarmInput) {
        if (session?.key == keyOf(alarm)) stopSession()
    }

    fun startSound() {
        val current = session ?: return
        current.feedback.start()
        afterStart(current)
    }

    fun shown(visible: Boolean) {
        if (visible) appear() else leave()
    }

    private fun appear() {
        shownToUser.value = true
        val current = session ?: return
        if (!current.begun) begin(current)
    }

    private fun leave() {
        if (!shownToUser.value) return
        shownToUser.value = false
        val current = session ?: return
        current.begun = false
        current.starter?.cancel()
        current.starter = null
        current.feedback.stop()
        val swept = current.sweep ?: return
        current.sweep = null
        env.scope.launch {
            swept.cancelAndJoin()
            notices.withLock {
                if (!shownToUser.value && session === current) restoreNotification(current.alarm)
            }
        }
    }

    private fun begin(target: Session) {
        target.begun = true
        target.starter?.cancel()
        target.starter = env.scope.launch(main) {
            val choice = withContext(Dispatchers.IO) { SoundChoice.parse(env.settings.value(SettingKeys.AlarmSound)) }
            if (session !== target || !shownToUser.value) return@launch
            target.choice = choice
            target.feedback.start()
            afterStart(target)
        }
    }

    private fun afterStart(target: Session) {
        val state = target.feedback.state.value
        val notes = buildList {
            if (state.fallback) add("chosen sound failed; playing the default")
            if (state.blocked) add("in-app sound blocked")
        }.filter { target.trouble.add(it) }
        if (notes.isNotEmpty()) {
            val alarm = target.alarm
            env.scope.launch {
                for (note in notes) {
                    env.log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.INAPP, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = note)
                }
            }
        }
        if (!state.playing || target.sweep?.isActive == true) return
        val alarm = target.alarm
        target.sweep = env.scope.launch {
            while (isActive) {
                notices.withLock {
                    if (shownToUser.value && session === target) {
                        env.silencer.silence(alarm.taskId, if (alarm.kind == AlarmKind.DUE) null else alarm.reminderId, listOf(alarm), "in-app ring", AlarmLogLane.INAPP)
                    }
                }
                delay(sweepMs)
            }
        }
    }

    private suspend fun restoreNotification(alarm: AlarmInput) {
        val notification = try {
            env.scheduler.ringingNotification(alarm)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val shown = notification?.let { runCatching { env.gateway.showRinging(it) } }
        val ok = shown?.isSuccess == true
        env.log.record(
            if (ok) AlarmLogEvent.DELIVERED else AlarmLogEvent.DEGRADED,
            AlarmLogLane.NOTIFICATION,
            kind = alarm.kind,
            slotIndex = notification?.slotIndex,
            urgent = alarm.urgent,
            fireAt = notification?.fireAt,
            taskId = alarm.taskId,
            reminderId = alarm.reminderId,
            detail = if (ok) "id=${notification!!.id} restored: app left the ring" else "ring notification not restored when the app left",
        )
    }

    private fun stopSession() {
        val current = session ?: return
        session = null
        current.starter?.cancel()
        current.sweep?.cancel()
        current.feedback.stop()
    }

    companion object {
        const val SWEEP_MS = 5_000L

        private val instances = WeakHashMap<AlarmEnvironment, InAppRing>()

        fun keyOf(alarm: AlarmInput): String = "${alarm.reminderId}|${alarm.fireAt.toEpochMilli()}"

        fun of(env: AlarmEnvironment): InAppRing = synchronized(instances) { instances.getOrPut(env) { InAppRing(env) } }

        fun install(env: AlarmEnvironment, ring: InAppRing) = synchronized(instances) { instances[env] = ring }
    }
}
