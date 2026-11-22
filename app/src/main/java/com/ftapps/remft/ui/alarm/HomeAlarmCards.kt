package com.ftapps.remft.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.ui.components.GradientCard
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftFilledButton
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.data.alarm.PermissionStatus
import com.ftapps.remft.domain.alarm.AlarmProblem
import com.ftapps.remft.domain.alarm.AlarmRinging
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun problemText(problem: AlarmProblem): Int = when (problem) {
    AlarmProblem.NOTIFICATIONS_OFF -> R.string.alarm_problem_notificationsOff
    AlarmProblem.ALARM_VOLUME_OFF -> R.string.alarm_problem_alarmVolumeOff
    AlarmProblem.EXACT_ALARMS_OFF -> R.string.alarm_problem_exactAlarmsOff
    AlarmProblem.FULL_SCREEN_OFF -> R.string.alarm_problem_fullScreenOff
}

fun fixText(problem: AlarmProblem): Int = when (problem) {
    AlarmProblem.NOTIFICATIONS_OFF -> R.string.alarm_fix_notificationsOff
    AlarmProblem.ALARM_VOLUME_OFF -> R.string.alarm_fix_alarmVolumeOff
    AlarmProblem.EXACT_ALARMS_OFF -> R.string.alarm_fix_exactAlarmsOff
    AlarmProblem.FULL_SCREEN_OFF -> R.string.alarm_fix_fullScreenOff
}

fun permissionFor(problem: AlarmProblem): PermissionKind = when (problem) {
    AlarmProblem.NOTIFICATIONS_OFF -> PermissionKind.NOTIFICATIONS
    AlarmProblem.ALARM_VOLUME_OFF -> PermissionKind.ALARM_VOLUME
    AlarmProblem.EXACT_ALARMS_OFF -> PermissionKind.EXACT_ALARMS
    AlarmProblem.FULL_SCREEN_OFF -> PermissionKind.FULL_SCREEN
}

@Composable
fun rememberPermissionStatus(env: AlarmEnvironment): PermissionStatus? {
    var status by remember(env) { mutableStateOf<PermissionStatus?>(null) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { status = withContext(Dispatchers.Default) { runCatching { env.health.status() }.getOrNull() } }
    }
    return status
}

data class UpcomingUrgent(val alarm: AlarmInput, val at: Instant)

object UrgentLookAhead {
    val Window: Duration = Duration.ofHours(24)

    fun next(feed: List<AlarmInput>, now: Instant, profile: ReminderProfile, window: Duration = Window): UpcomingUrgent? {
        val times = AlarmPlanner.plannedRingTimes(feed, profile)
        var best: UpcomingUrgent? = null
        for ((alarm, slots) in times) {
            if (!alarm.urgent) continue
            val at = slots.firstOrNull { it.isAfter(now) } ?: continue
            if (at.isAfter(now.plus(window))) continue
            if (best == null || at.isBefore(best.at)) best = UpcomingUrgent(alarm, at)
        }
        return best
    }

    fun hoursAndMinutes(now: Instant, at: Instant): Pair<Long, Long> {
        val minutes = maxOf(1L, (Duration.between(now, at).seconds + 59) / 60)
        return minutes / 60 to minutes % 60
    }
}

@Composable
fun HomeAlarmWarnings(modifier: Modifier = Modifier) {
    val env = LocalAlarmEnvironment.current ?: return
    val ring by env.ring.state.collectAsStateWithLifecycle()
    val status = rememberPermissionStatus(env)
    Column(modifier) {
        status?.let { current ->
            val volumeOff = current.alarmVolumeOff == true &&
                AlarmRinging.urgentRingAhead(ring.feed, env.clock.instant(), ring.profile)
            current.support.worstProblem(volumeOff)?.let { AlarmProblemBanner(env, it) }
        }
        if (ring.missed.isNotEmpty()) MissedAlarmCard(env, ring.missed)
    }
}

@Composable
fun HomeUrgentAlarm(onOpenTask: (String) -> Unit = {}) {
    val env = LocalAlarmEnvironment.current ?: return
    val ring by env.ring.state.collectAsStateWithLifecycle()
    val now by produceState(env.clock.instant(), env) {
        while (true) {
            value = env.clock.instant()
            delay(UPCOMING_REFRESH_MS)
        }
    }
    val upcoming = remember(ring.feed, ring.profile, now) { UrgentLookAhead.next(ring.feed, now, ring.profile) }
    if (upcoming != null) UpcomingUrgentCard(env, upcoming, now, onOpenTask)
}

private const val UPCOMING_REFRESH_MS = 30_000L

@Composable
private fun UpcomingUrgentCard(env: AlarmEnvironment, upcoming: UpcomingUrgent, now: Instant, onOpenTask: (String) -> Unit) {
    val format by remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }
        .collectAsStateWithLifecycle(DateFormatId.System)
    val (hours, minutes) = UrgentLookAhead.hoursAndMinutes(now, upcoming.at)
    val span = if (hours > 0) {
        stringResource(R.string.home_ringsInHoursMinutes).fill("h" to hours, "m" to minutes)
    } else {
        stringResource(R.string.home_ringsInMinutes).fill("m" to minutes)
    }
    val time = DateFormats.time(upcoming.at.atZone(env.clock.zone).toLocalDateTime(), format)
    UrgentAlarmCardContent(
        title = upcoming.alarm.taskTitle,
        time = time,
        ringsIn = stringResource(R.string.home_ringsIn).fill("time" to span),
        onClick = { onOpenTask(upcoming.alarm.taskId) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UrgentAlarmCardContent(title: String, time: String, ringsIn: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val urgent = RemftTheme.colors.urgent
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700, fontSize = 24.sp, lineHeight = 30.sp)
    GradientCard(
        brush = RemftTheme.gradients.urgent.diagonal,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = Space.x2).testTag("urgent-alarm-card"),
    ) {
        Column(Modifier.padding(start = Space.x5, end = Space.x5, top = Space.x4, bottom = Space.x5)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.x2),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsNone, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.x3))
                    Text(stringResource(R.string.home_urgentAlarm), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    ringsIn,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W500),
                    color = if (dark) urgent.container else urgent.onContainer,
                    modifier = Modifier
                        .background(if (dark) urgent.onContainer else Color.White, MarkedShapes.pill)
                        .padding(horizontal = Space.x4, vertical = Space.x2)
                        .testTag("urgent-alarm-rings-in"),
                )
            }
            Spacer(Modifier.height(Space.x3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = titleStyle, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.x3))
                Text(time, style = titleStyle, maxLines = 1)
            }
        }
    }
}

@Preview(name = "Urgent alarm card light")
@Composable
private fun UrgentAlarmCardLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { UrgentAlarmCardContent("Call the electrician", "6:30 pm", "Rings in 2 h 10 min", onClick = {}) } }
}

@Preview(name = "Urgent alarm card dark")
@Composable
private fun UrgentAlarmCardDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { UrgentAlarmCardContent("Call the electrician", "6:30 pm", "Rings in 2 h 10 min", onClick = {}) } }
}

@Composable
private fun AlarmProblemBanner(env: AlarmEnvironment, problem: AlarmProblem) {
    var sheet by remember { mutableStateOf(false) }
    AlarmProblemBannerContent(problem, onClick = { sheet = true })
    if (sheet) AlarmFixSheet(env, problem) { sheet = false }
}

@Composable
fun AlarmProblemBannerContent(problem: AlarmProblem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val warning = RemftTheme.colors.warning
    MarkedCard(
        color = warning.container,
        contentColor = warning.onContainer,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.x2)
            .heightIn(min = Sizes.touchTarget)
            .testTag("alarm-banner"),
    ) {
        Row(Modifier.padding(start = Space.x4, top = Space.x3, end = Space.x3, bottom = Space.x3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.x3))
            Text(stringResource(problemText(problem)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.x2))
            Text(
                stringResource(R.string.alarm_banner_fix),
                style = MaterialTheme.typography.labelLarge,
                color = warning.onColor,
                modifier = Modifier
                    .background(warning.color, MarkedShapes.pill)
                    .padding(horizontal = Space.x3, vertical = Space.x1),
            )
        }
    }
}

@Composable
fun AlarmFixSheet(env: AlarmEnvironment, problem: AlarmProblem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlarmFixSheetContent(
        problem = problem,
        onOpen = {
            env.health.open(context, permissionFor(problem))
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmFixSheetContent(problem: AlarmProblem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val warning = RemftTheme.colors.warning
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("alarm-fix-sheet")) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x6, vertical = Space.x2)) {
            Box(
                Modifier.size(Sizes.touchTarget).background(warning.container, MarkedShapes.pill),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = warning.onContainer) }
            Spacer(Modifier.height(Space.x4))
            Text(stringResource(problemText(problem)), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.x3))
            Text(stringResource(fixText(problem)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.x6))
            RemftFilledButton(
                stringResource(R.string.alarm_fix_open),
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().testTag("alarm-fix-open"),
            )
            Spacer(Modifier.height(Space.x4))
        }
    }
}

@Composable
private fun MissedAlarmCard(env: AlarmEnvironment, missed: List<AlarmInput>) {
    val summary = AlarmRinging.missedSummary(missed) ?: return
    val first = summary.first
    val format by remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }
        .collectAsStateWithLifecycle(DateFormatId.System)
    val now = env.clock.instant()
    val time = DateFormats.time(first.fireAt.atZone(env.clock.zone).toLocalDateTime(), format)
    MissedAlarmCardContent(
        title = first.taskTitle,
        whenText = stringResource(R.string.alarm_missed_ago).fill("time" to time, "ago" to relativePastText(first.fireAt, now)),
        moreCount = summary.moreCount,
        onClick = { env.ring.showMissed(first) },
    )
}

@Composable
fun MissedAlarmCardContent(title: String, whenText: String, moreCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val urgent = RemftTheme.colors.urgent
    MarkedCard(
        color = urgent.container,
        contentColor = urgent.onContainer,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.x2)
            .testTag("missed-alarm-card"),
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(start = Space.x4, top = Space.x3, end = Space.x2, bottom = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(urgent.color, MarkedShapes.pill),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.NotificationsPaused, contentDescription = null, tint = urgent.onColor, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(Space.x3))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.alarm_missed_cardTitle), style = MaterialTheme.typography.labelLarge)
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(whenText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("missed-alarm-when"))
            }
            if (moreCount > 0) {
                Spacer(Modifier.width(Space.x2))
                Text(
                    stringResource(R.string.alarm_missed_cardMore).fill("n" to moreCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = urgent.onColor,
                    modifier = Modifier
                        .background(urgent.color, MarkedShapes.pill)
                        .padding(horizontal = Space.x3, vertical = Space.x1)
                        .testTag("missed-alarm-more"),
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Preview(name = "Missed alarm card light")
@Composable
private fun MissedAlarmCardLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { MissedAlarmCardContent("Water the plants", "8:00 am · 2 h ago", 1, onClick = {}) } }
}

@Preview(name = "Missed alarm card dark")
@Composable
private fun MissedAlarmCardDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { MissedAlarmCardContent("Water the plants", "8:00 am · 2 h ago", 1, onClick = {}) } }
}

@Preview(name = "Alarm problem banner light")
@Composable
private fun AlarmProblemBannerLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { AlarmProblemBannerContent(AlarmProblem.ALARM_VOLUME_OFF, onClick = {}) } }
}

@Preview(name = "Alarm problem banner dark")
@Composable
private fun AlarmProblemBannerDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { AlarmProblemBannerContent(AlarmProblem.ALARM_VOLUME_OFF, onClick = {}) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmRefreshBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val env = LocalAlarmEnvironment.current
    if (env == null) {
        Box(modifier, content = content)
        return
    }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                runCatching { env.scheduler.pass(rebook = true, reason = "refresh") }
                env.ring.reconsider()
                refreshing = false
            }
        },
        modifier = modifier.testTag("home-refresh"),
        content = content,
    )
}
