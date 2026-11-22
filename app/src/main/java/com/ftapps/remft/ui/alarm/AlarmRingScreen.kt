package com.ftapps.remft.ui.alarm

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.MotionSpeed
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
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
    val colors = alarmRingColors()
    val motion = RemftTheme.motion
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
    LightSystemBarsOff()

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
    val ring = RingContent(
        missed = missed,
        timeText = DateFormats.time(fireAt.atZone(zone).toLocalDateTime(), dateFormat),
        whenText = if (missed) relativePastText(fireAt, now) else null,
        title = alarm.taskTitle,
    )
    val buttons = RingButtons(
        busy = busy,
        acknowledge = { run({ actions.acknowledge() }) },
        snooze = { preset -> snoozeTo(SnoozePresets.untilFor(preset, currentTime(), zone)) },
        custom = { pickingDate = true },
        silence = { run({ actions.silence() }) { texts.muted } },
        complete = { run({ actions.complete() }) },
        open = actions::open,
    )
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    val enter = if (motion.reduced) {
        motion.fadeEnter(MotionSpeed.Emphasised)
    } else {
        motion.fadeEnter(MotionSpeed.Emphasised) + scaleIn(motion.emphasised(), initialScale = 0.96f)
    }
    Box(
        modifier
            .fillMaxSize()
            .background(RemftTheme.gradients.alarmRing.diagonal)
            .testTag(if (missed) "alarm-missed-screen" else "alarm-ring"),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth > maxHeight && maxWidth >= WideMinWidth
                AnimatedVisibility(visibleState = entered, enter = enter) {
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(horizontal = Space.x6), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Space.x4),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) { RingHeader(ring, feedback, volumeOff, actions::startSound, orbitSize = 96.dp) }
                            Spacer(Modifier.width(Space.x6))
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Space.x4),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) { RingActions(buttons, snoozeOrder, now, zone, dateFormat) }
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Space.x6, vertical = Space.x8),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Column(Modifier.widthIn(max = MaxColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                                RingHeader(ring, feedback, volumeOff, actions::startSound, orbitSize = 128.dp)
                                Spacer(Modifier.height(Space.x8))
                                RingActions(buttons, snoozeOrder, now, zone, dateFormat)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) { RemftSnackbar(it) }
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

private val WideMinWidth = 560.dp
private val MaxColumnWidth = 480.dp

private data class RingTexts(val failed: String, val notApplied: String, val confirmed: String, val muted: String, val past: String)

private data class RingContent(val missed: Boolean, val timeText: String, val whenText: String?, val title: String)

private class RingButtons(
    val busy: Boolean,
    val acknowledge: () -> Unit,
    val snooze: (String) -> Unit,
    val custom: () -> Unit,
    val silence: () -> Unit,
    val complete: () -> Unit,
    val open: () -> Unit,
)

@Composable
private fun ColumnScope.RingHeader(ring: RingContent, feedback: RingFeedback, volumeOff: Boolean, onStartSound: () -> Unit, orbitSize: Dp) {
    val colors = alarmRingColors()
    val display = MaterialTheme.typography.displayLarge
    AlarmOrbitPulse(pulsing = !ring.missed, size = orbitSize)
    Spacer(Modifier.height(Space.x4))
    Text(
        stringResource(if (ring.missed) R.string.alarm_missed_label else R.string.alarm_ringing_label).uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.12.em),
        color = colors.content,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(colors.pillContainer, MarkedShapes.pill)
            .padding(horizontal = Space.x4, vertical = Space.x2),
    )
    Spacer(Modifier.height(Space.x3))
    BasicText(
        ring.timeText,
        style = display.copy(color = colors.content, textAlign = TextAlign.Center),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = display.fontSize),
        modifier = Modifier.fillMaxWidth().testTag("alarm-time"),
    )
    if (ring.whenText != null) {
        Text(
            ring.whenText,
            style = MaterialTheme.typography.titleMedium,
            color = colors.contentSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("alarm-when"),
        )
    }
    Spacer(Modifier.height(Space.x3))
    Text(
        ring.title,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineMedium,
        color = colors.content,
        modifier = Modifier.semantics { heading() }.testTag("alarm-title"),
    )
    if (feedback.fallback) {
        Notice(stringResource(R.string.alarm_soundFallback), "alarm-sound-fallback", Icons.Outlined.Info)
    }
    if (!ring.missed && volumeOff) {
        Notice(stringResource(R.string.alarm_volumeOff), "alarm-volume-off", Icons.AutoMirrored.Outlined.VolumeOff, bold = true)
    }
    if (!ring.missed && feedback.blocked) {
        AlarmOutlinePill(onClick = onStartSound, enabled = true, modifier = Modifier.padding(top = Space.x4).testTag("alarm-start-sound")) {
            AlarmPillLabel(Icons.AutoMirrored.Outlined.VolumeUp) {
                Text(stringResource(R.string.alarm_startSound), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingActions(buttons: RingButtons, snoozeOrder: List<String>, now: Instant, zone: ZoneId, dateFormat: DateFormatId) {
    val colors = alarmRingColors()
    AlarmPrimaryPill(
        onClick = buttons.acknowledge,
        enabled = !buttons.busy,
        modifier = Modifier.fillMaxWidth().testTag("alarm-acknowledge"),
    ) {
        Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
        Spacer(Modifier.width(Space.x2))
        Text(stringResource(R.string.notif_action_acknowledge), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(Space.x6))
    Text(
        stringResource(R.string.alarm_ringing_snooze),
        style = MaterialTheme.typography.titleMedium,
        color = colors.content,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Space.x3))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.x2, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Space.x2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val ringsAt = stringResource(R.string.alarm_snoozeRingsAt)
        snoozeOrder.forEach { preset ->
            val until = SnoozePresets.untilFor(preset, now, zone)
            AlarmOutlinePill(
                onClick = { buttons.snooze(preset) },
                enabled = !buttons.busy,
                modifier = Modifier.testTag("alarm-snooze-$preset"),
            ) {
                AlarmPillLabel(null) {
                    Text(snoozeLabel(preset), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        ringsAt.fill("time" to snoozeTimeLabel(until, now, zone, dateFormat)),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        AlarmOutlinePill(onClick = buttons.custom, enabled = !buttons.busy, modifier = Modifier.testTag("alarm-snooze-custom")) {
            AlarmPillLabel(Icons.Outlined.EditCalendar) { Text(stringResource(R.string.alarm_snoozeCustom), style = MaterialTheme.typography.titleMedium) }
        }
        AlarmOutlinePill(onClick = buttons.silence, enabled = !buttons.busy, modifier = Modifier.testTag("alarm-silence")) {
            AlarmPillLabel(Icons.Outlined.NotificationsOff) { Text(stringResource(R.string.alarm_silence), style = MaterialTheme.typography.titleMedium) }
        }
    }
    Spacer(Modifier.height(Space.x5))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        RingTextAction(stringResource(R.string.notif_action_complete), Icons.Outlined.CheckCircle, buttons.complete, !buttons.busy, "alarm-complete")
        RingTextAction(stringResource(R.string.notif_action_open), Icons.AutoMirrored.Outlined.OpenInNew, buttons.open, !buttons.busy, "alarm-open")
    }
}

@Composable
private fun RingTextAction(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean, tag: String) {
    val colors = alarmRingColors()
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.content, disabledContentColor = colors.contentSoft),
        modifier = Modifier.heightIn(min = Sizes.touchTarget).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.x2))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Notice(text: String, tag: String, icon: ImageVector, bold: Boolean = false) {
    val colors = alarmRingColors()
    Row(
        Modifier
            .padding(top = Space.x4)
            .background(colors.pillContainer, MarkedShapes.medium)
            .padding(horizontal = Space.x4, vertical = Space.x3)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.x3))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.W600 else FontWeight.W400),
            color = colors.content,
        )
    }
}

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
    val pickerColors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = MarkedShapes.large,
        colors = pickerColors,
        confirmButton = {
            SnoozeDialogButton(stringResource(R.string.tasks_pickerOk), "alarm-custom-date-ok") {
                val millis = state.selectedDateMillis
                if (millis == null) onDismiss() else onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }
        },
        dismissButton = { SnoozeDialogButton(stringResource(R.string.common_cancel), null, onDismiss) },
    ) { DatePicker(state = state, colors = pickerColors) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSnoozeTime(now: Instant, zone: ZoneId, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val suggested = SnoozePresets.customSuggestion(now).atZone(zone).toLocalTime()
    val state = rememberTimePickerState(initialHour = suggested.hour, initialMinute = suggested.minute)
    RemftDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            SnoozeDialogButton(stringResource(R.string.tasks_pickerOk), "alarm-custom-time-ok") { onPicked(LocalTime.of(state.hour, state.minute)) }
        },
        dismissButton = { SnoozeDialogButton(stringResource(R.string.common_cancel), null, onDismiss) },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun SnoozeDialogButton(text: String, tag: String?, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = MarkedShapes.pill,
        modifier = Modifier.heightIn(min = Sizes.touchTarget).then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

private val PreviewAlarm = AlarmInput(
    reminderId = "task:due:preview",
    taskId = "preview",
    taskTitle = "Call the electrician",
    remindAt = Instant.parse("2026-11-22T13:00:00Z"),
    status = "scheduled",
    urgent = true,
    requiresAcknowledgement = true,
    kind = AlarmKind.DUE,
)

private object PreviewActions : AlarmRingActions {
    override suspend fun acknowledge() = RingAnswer.APPLIED

    override suspend fun snooze(until: Instant) = RingAnswer.APPLIED

    override suspend fun complete() = RingAnswer.APPLIED

    override suspend fun silence() = RingAnswer.APPLIED

    override fun open() = Unit

    override fun startSound() = Unit

    override fun dismissMissed() = Unit
}

@Composable
private fun RingPreview(dark: Boolean, missed: Boolean = false, volumeOff: Boolean = false) {
    RemftTheme(darkTheme = dark) {
        AlarmRingScreen(
            alarm = PreviewAlarm,
            missed = missed,
            now = PreviewAlarm.remindAt.plus(Duration.ofMinutes(if (missed) 42 else 1)),
            zone = ZoneOffset.UTC,
            dateFormat = DateFormatId.System,
            snoozeOrder = SnoozePresets.ids,
            feedback = RingFeedback(playing = true),
            volumeOff = volumeOff,
            actions = PreviewActions,
        )
    }
}

@Preview(name = "Alarm ring light", widthDp = 411, heightDp = 891)
@Composable
private fun AlarmRingLightPreview() = RingPreview(dark = false)

@Preview(name = "Alarm ring dark", widthDp = 411, heightDp = 891)
@Composable
private fun AlarmRingDarkPreview() = RingPreview(dark = true, volumeOff = true)

@Preview(name = "Alarm ring landscape", widthDp = 891, heightDp = 411)
@Composable
private fun AlarmRingLandscapePreview() = RingPreview(dark = false)

@Preview(name = "Alarm ring largest font", widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun AlarmRingLargeFontPreview() = RingPreview(dark = true)

@Preview(name = "Missed alarm light", widthDp = 411, heightDp = 891)
@Composable
private fun MissedAlarmLightPreview() = RingPreview(dark = false, missed = true)

@Preview(name = "Missed alarm dark", widthDp = 411, heightDp = 891)
@Composable
private fun MissedAlarmDarkPreview() = RingPreview(dark = true, missed = true)
