package com.ftapps.remft.ui.alarm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.alarm.AlarmLogLane
import com.ftapps.remft.data.alarm.LogCode
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.AlarmWrite
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.components.LocalTooltipsSuppressed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable
fun AlarmRingHost(env: AlarmEnvironment, onOpenTask: (String) -> Unit, content: @Composable () -> Unit) {
    val state by env.ring.state.collectAsStateWithLifecycle()
    val missedShown by env.ring.missedShown.collectAsStateWithLifecycle()
    val ringing = state.ringing
    val missed = missedShown
    val ringingKey = ringing?.let(InAppRing::keyOf)
    val inApp = remember(env) { InAppRing.of(env) }
    LaunchedEffect(inApp, ringingKey) { inApp.keepOnly(ringingKey) }
    RingCover(covered = ringing != null || missed != null, content = content) {
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
fun RingCover(covered: Boolean, content: @Composable () -> Unit, overlay: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(covered) { if (covered) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()) {
        val hidden = if (covered) Modifier.clearAndSetSemantics { } else Modifier
        Box(
            Modifier
                .fillMaxSize()
                .then(hidden)
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalTooltipsSuppressed provides covered) { content() }
        }
        if (covered) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
            )
        }
        overlay()
    }
}

@Composable
fun AlarmRingOverlay(env: AlarmEnvironment, alarm: AlarmInput, missed: Boolean, onOpenTask: (String) -> Unit) {
    val context = LocalContext.current
    val inApp = remember(env) { InAppRing.of(env) }
    val snoozeOrder by remember(env) { env.settings.flow(SettingKeys.SnoozePresets).map<String?, List<String>?> { SnoozePresets.parseOrder(it) } }
        .collectAsStateWithLifecycle(env.snoozeOrder.takeIf { it.ready }?.current())
    val dateFormat by remember(env) { env.settings.flow(SettingKeys.DateFormat).map<String?, DateFormatId?> { DateFormatId.parse(it) } }
        .collectAsStateWithLifecycle(null)
    val now by produceState(env.clock.instant(), env) {
        while (true) {
            value = env.clock.instant()
            delay(CLOCK_REFRESH_MS)
        }
    }
    val volumeOff = remember(alarm.reminderId) { !missed && env.health.status().alarmVolumeOff == true }
    val feedback = remember(alarm.reminderId, alarm.fireAt, missed) { if (missed) SilentFeedback() else inApp.attach(alarm, volumeOff) }
    val feedbackState by feedback.state.collectAsStateWithLifecycle()
    val visible by inApp.visible.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, missed) {
        if (missed) return@DisposableEffect onDispose { }
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inApp.shown(true)
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) inApp.shown(false)
                else -> Unit
            }
        }
        inApp.shown(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(feedback, visible) {
        if (missed || !visible) return@LaunchedEffect
        while (true) {
            feedback.pulse()
            delay(HAPTIC_MS)
        }
    }

    val actions = remember(alarm, missed) { HostActions(env, alarm, missed, inApp, onOpenTask) }
    AlarmRingScreen(
        alarm = alarm,
        missed = missed,
        now = now,
        zone = env.clock.zone,
        dateFormat = dateFormat ?: DateFormatId.System,
        snoozeOrder = snoozeOrder ?: SnoozePresets.ids,
        feedback = feedbackState,
        volumeOff = volumeOff,
        actions = actions,
        currentTime = { env.clock.instant() },
        inputReady = dateFormat != null && snoozeOrder != null,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val CLOCK_REFRESH_MS = 60_000L
private const val HAPTIC_MS = 2_000L

class HostActions(
    private val env: AlarmEnvironment,
    private val alarm: AlarmInput,
    private val missed: Boolean,
    private val inApp: InAppRing,
    private val onOpenTask: (String) -> Unit,
) : AlarmRingActions {
    private val lane = AlarmLogLane.INAPP

    private suspend fun answer(
        reason: String,
        wholeTask: Boolean,
        onApplied: () -> Unit = {},
        write: suspend () -> AlarmWrite,
    ): RingAnswer = withContext(NonCancellable) {
        val asRang = if (wholeTask) runCatching { env.store.alarmsOfTask(alarm.taskId) }.getOrDefault(emptyList()) else emptyList()
        val result = try {
            write()
        } catch (e: Exception) {
            env.appLog.error("alarm.action_failed", e, mapOf("action" to LogCode(reason)))
            env.log.record(AlarmLogEvent.DEGRADED, lane, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "$reason failed: ${e.javaClass.simpleName}")
            return@withContext RingAnswer.FAILED
        }
        if (!result.applied) {
            env.log.record(AlarmLogEvent.ACTION, lane, kind = alarm.kind, urgent = alarm.urgent, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = "$reason not applied: the alarm changed")
            done()
            return@withContext RingAnswer.NOT_APPLIED
        }
        env.log.record(AlarmLogEvent.ACTION, lane, kind = alarm.kind, urgent = alarm.urgent, fireAt = alarm.remindAt, taskId = alarm.taskId, reminderId = alarm.reminderId, detail = reason)
        withContext(Dispatchers.Main.immediate) { inApp.stop(alarm) }
        env.silencer.silence(alarm.taskId, if (wholeTask) null else alarm.reminderId, listOf(alarm) + asRang, reason, lane)
        done()
        env.scheduler.request()
        onApplied()
        RingAnswer.APPLIED
    }

    private fun done() {
        if (missed) {
            env.ring.handled(alarm.reminderId)
            env.ring.dismissMissed()
        } else {
            env.ring.handled(alarm.reminderId)
        }
    }

    override suspend fun acknowledge(): RingAnswer =
        answer("acknowledged", alarm.kind == AlarmKind.DUE, onApplied = { onOpenTask(alarm.taskId) }) {
            env.store.acknowledge(alarm.reminderId, alarm.remindAt)
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
        inApp.stop(alarm)
        if (missed) {
            env.ring.dismissMissed()
        } else {
            env.ring.deferred(alarm.reminderId)
        }
        onOpenTask(alarm.taskId)
    }

    override fun startSound() = inApp.startSound()

    override fun dismissMissed() = env.ring.dismissMissed()
}
