package com.ftapps.remft.ui.alarm

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class RingAnswer { APPLIED, NOT_APPLIED, FAILED }

interface AlarmRingActions {
    suspend fun acknowledge(): RingAnswer

    suspend fun snooze(until: Instant): RingAnswer

    suspend fun complete(): RingAnswer

    suspend fun silence(): RingAnswer

    fun open()

    fun startSound()

    fun dismissMissed()
}

fun snoozePresetLabel(preset: String): Int = when (preset) {
    SnoozePresets.FIVE_MIN -> R.string.notif_action_snooze5m
    SnoozePresets.THIRTY_MIN -> R.string.notif_action_snooze30m
    SnoozePresets.ONE_HOUR -> R.string.notif_action_snooze1h
    else -> R.string.notif_action_snoozeTomorrow
}

fun snoozeTimeLabel(until: Instant, now: Instant, zone: ZoneId, format: DateFormatId): String {
    val at = until.atZone(zone).toLocalDateTime()
    val sameDay = at.toLocalDate() == now.atZone(zone).toLocalDate()
    return if (sameDay) DateFormats.time(at, format) else DateFormats.short(at, format)
}

fun relativePast(at: Instant, now: Instant, justNow: String, minutes: String, hours: String, days: String): String {
    val elapsed = Duration.between(at, now)
    return when {
        elapsed.toMinutes() < 1 -> justNow
        elapsed.toHours() < 1 -> minutes.fill("n" to elapsed.toMinutes())
        elapsed.toDays() < 1 -> hours.fill("n" to elapsed.toHours())
        else -> days.fill("n" to elapsed.toDays())
    }
}

@Composable
fun relativePastText(at: Instant, now: Instant): String = relativePast(
    at,
    now,
    stringResource(R.string.time_ago_justNow),
    stringResource(R.string.time_ago_minutes),
    stringResource(R.string.time_ago_hours),
    stringResource(R.string.time_ago_days),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmRingScreen(
    alarm: AlarmInput,
    missed: Boolean,
    now: Instant,
    zone: ZoneId,
    dateFormat: DateFormatId,
    snoozeOrder: List<String>,
    feedback: RingFeedback,
    volumeOff: Boolean,
    actions: AlarmRingActions,
    modifier: Modifier = Modifier,
    currentTime: () -> Instant = { now },
) {
    val urgentColor = RemftThemeExtras.colors.priority.urgent
    val scheme = MaterialTheme.colorScheme
    val background = urgentColor.copy(alpha = 0.10f).compositeOver(scheme.surface)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val texts = RingTexts(
        failed = stringResource(R.string.alarm_actionFailed),
        notApplied = stringResource(R.string.alarm_notApplied),
        confirmed = stringResource(R.string.alarm_snoozeConfirmed),
        muted = stringResource(R.string.task_alarmsMuted),
        past = stringResource(R.string.alarm_snoozePast),
    )

    BackHandler(enabled = true) {
        if (missed) actions.dismissMissed()
    }

    fun run(action: suspend () -> RingAnswer, onApplied: () -> String? = { null }) {
        if (busy) return
        busy = true
        scope.launch {
            val answer = runCatching { action() }.getOrDefault(RingAnswer.FAILED)
            busy = false
            val message = when (answer) {
                RingAnswer.APPLIED -> onApplied()
                RingAnswer.NOT_APPLIED -> texts.notApplied
                RingAnswer.FAILED -> texts.failed
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    fun snoozeTo(until: Instant) {
        run({ actions.snooze(until) }) { texts.confirmed.fill("time" to snoozeTimeLabel(until, now, zone, dateFormat)) }
    }

    val fireAt = alarm.fireAt
    val timeText = DateFormats.time(fireAt.atZone(zone).toLocalDateTime(), dateFormat)
    Surface(color = background, modifier = modifier.fillMaxSize().testTag(if (missed) "alarm-missed-screen" else "alarm-ring")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Space.x6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PulsingRing(pulsing = !missed)
                    Spacer(Modifier.height(Space.x5))
                    Text(
                        stringResource(if (missed) R.string.alarm_missed_label else R.string.alarm_ringing_label).uppercase(),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = scheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        alarm.taskTitle,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                        modifier = Modifier.testTag("alarm-title"),
                    )
                    Spacer(Modifier.height(Space.x2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.x1))
                        Text(
                            if (missed) {
                                stringResource(R.string.alarm_missed_ago).fill("time" to timeText, "ago" to relativePastText(fireAt, now))
                            } else {
                                timeText
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (feedback.fallback) {
                        Notice(stringResource(R.string.alarm_soundFallback), "alarm-sound-fallback")
                    }
                    if (!missed && volumeOff) {
                        Notice(stringResource(R.string.alarm_volumeOff), "alarm-volume-off", bold = true)
                    }
                    if (!missed && feedback.blocked) {
                        OutlinedButton(onClick = actions::startSound, modifier = Modifier.padding(top = Space.x4).testTag("alarm-start-sound")) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.x2))
                            Text(stringResource(R.string.alarm_startSound))
                        }
                    }
                    Spacer(Modifier.height(Space.x6))
                    Button(
                        onClick = { run({ actions.acknowledge() }) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                        contentPadding = PaddingValues(vertical = Space.x4),
                        modifier = Modifier.fillMaxWidth().testTag("alarm-acknowledge"),
                    ) {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                        Spacer(Modifier.width(Space.x2))
                        Text(stringResource(R.string.notif_action_acknowledge), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.height(Space.x5))
                    Text(stringResource(R.string.alarm_ringing_snooze), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(Space.x2))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.x2, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(Space.x2),
                    ) {
                        val ringsAt = stringResource(R.string.alarm_snoozeRingsAt)
                        snoozeOrder.forEach { preset ->
                            val until = SnoozePresets.untilFor(preset, now, zone)
                            OutlinedButton(
                                onClick = { snoozeTo(SnoozePresets.untilFor(preset, currentTime(), zone)) },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("alarm-snooze-$preset"),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(snoozePresetLabel(preset)))
                                    Text(
                                        ringsAt.fill("time" to snoozeTimeLabel(until, now, zone, dateFormat)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { pickingDate = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("alarm-snooze-custom"),
                        ) { Text(stringResource(R.string.alarm_snoozeCustom)) }
                        OutlinedButton(
                            onClick = { run({ actions.silence() }) { texts.muted } },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("alarm-silence"),
                        ) {
                            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.x2))
                            Text(stringResource(R.string.alarm_silence))
                        }
                    }
                    Spacer(Modifier.height(Space.x4))
                    Row(horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { run({ actions.complete() }) }, enabled = !busy, modifier = Modifier.testTag("alarm-complete")) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.x1))
                            Text(stringResource(R.string.notif_action_complete))
                        }
                        Spacer(Modifier.width(Space.x2))
                        TextButton(onClick = actions::open, enabled = !busy, modifier = Modifier.testTag("alarm-open")) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.x1))
                            Text(stringResource(R.string.notif_action_open))
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (pickingDate) {
        CustomSnoozeDate(now, zone, onDismiss = { pickingDate = false }) { date ->
            pickingDate = false
            pickedDate = date
        }
    }
    pickedDate?.let { date ->
        CustomSnoozeTime(now, zone, onDismiss = { pickedDate = null }) { time ->
            pickedDate = null
            val until = SnoozePresets.customUntil(date, time, zone)
            if (!SnoozePresets.isCustomSnoozeAccepted(until, currentTime())) {
                scope.launch { snackbar.showSnackbar(texts.past) }
            } else {
                snoozeTo(until)
            }
        }
    }
}

private data class RingTexts(val failed: String, val notApplied: String, val confirmed: String, val muted: String, val past: String)

@Composable
private fun Notice(text: String, tag: String, bold: Boolean = false) {
    Text(
        text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.x4).testTag(tag),
    )
}

@Composable
private fun PulsingRing(pulsing: Boolean) {
    val urgent = RemftThemeExtras.colors.priority.urgent
    val scale = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "alarm-pulse")
        val value by transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "alarm-pulse-scale",
        )
        value
    } else {
        1f
    }
    Box(
        Modifier
            .size(108.dp)
            .scale(scale)
            .background(urgent.copy(alpha = 0.14f), CircleShape)
            .border(BorderStroke(2.dp, urgent), CircleShape)
            .testTag("alarm-pulse"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Alarm, contentDescription = null, tint = urgent, modifier = Modifier.size(52.dp))
    }
}

private const val PULSE_MS = 900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSnoozeDate(now: Instant, zone: ZoneId, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val first = SnoozePresets.customFirstDate(now, zone)
    val last = SnoozePresets.customLastDate(now, zone)
    val suggested = SnoozePresets.customSuggestion(now).atZone(zone).toLocalDate()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = suggested.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !day.isBefore(first) && !day.isAfter(last)
            }

            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis == null) onDismiss() else onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
                modifier = Modifier.testTag("alarm-custom-date-ok"),
            ) { Text(stringResource(R.string.tasks_pickerOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSnoozeTime(now: Instant, zone: ZoneId, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val suggested = SnoozePresets.customSuggestion(now).atZone(zone).toLocalTime()
    val state = rememberTimePickerState(initialHour = suggested.hour, initialMinute = suggested.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }, modifier = Modifier.testTag("alarm-custom-time-ok")) {
                Text(stringResource(R.string.tasks_pickerOk))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = { TimePicker(state = state) },
    )
}
