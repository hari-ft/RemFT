package com.ftapps.remft.ui.alarm

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.compositeOver
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
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftThemeExtras
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

@Composable
fun HomeAlarmCards(modifier: Modifier = Modifier) {
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
private fun AlarmProblemBanner(env: AlarmEnvironment, problem: AlarmProblem) {
    var sheet by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x2)
            .heightIn(min = 48.dp)
            .clickable { sheet = true }
            .testTag("alarm-banner"),
    ) {
        Row(Modifier.padding(Space.x3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x2))
            Text(stringResource(problemText(problem)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.x2))
            Text(stringResource(R.string.alarm_banner_fix), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
    if (sheet) AlarmFixSheet(env, problem) { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmFixSheet(env: AlarmEnvironment, problem: AlarmProblem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = Breakpoints.sheetMaxWidth).testTag("alarm-fix-sheet"),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x6, vertical = Space.x2)) {
            Text(stringResource(problemText(problem)), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.x3))
            Text(stringResource(fixText(problem)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.x5))
            Button(
                onClick = {
                    env.health.open(context, permissionFor(problem))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm-fix-open"),
            ) { Text(stringResource(R.string.alarm_fix_open)) }
            Spacer(Modifier.height(Space.x4))
        }
    }
}

@Composable
private fun MissedAlarmCard(env: AlarmEnvironment, missed: List<com.ftapps.remft.domain.alarm.AlarmInput>) {
    val summary = AlarmRinging.missedSummary(missed) ?: return
    val first = summary.first
    val format by remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }
        .collectAsStateWithLifecycle(DateFormatId.System)
    val urgent = RemftThemeExtras.colors.priority.urgent
    val now = env.clock.instant()
    val time = DateFormats.time(first.fireAt.atZone(env.clock.zone).toLocalDateTime(), format)
    Surface(
        color = urgent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x2)
            .clickable { env.ring.showMissed(first) }
            .testTag("missed-alarm-card"),
    ) {
        Row(Modifier.padding(Space.x3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsPaused, contentDescription = null, tint = urgent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x2))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.alarm_missed_cardTitle),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(first.taskTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.alarm_missed_ago).fill("time" to time, "ago" to relativePastText(first.fireAt, now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("missed-alarm-when"),
                )
            }
            if (summary.moreCount > 0) {
                Spacer(Modifier.width(Space.x2))
                Text(
                    stringResource(R.string.alarm_missed_cardMore).fill("n" to summary.moreCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("missed-alarm-more"),
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
