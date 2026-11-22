package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmProblem
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.AppBackground
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.launch

private val TestAlarmSeconds = AlarmPlanner.TestAlarmDelay.seconds

@StringRes
fun AlarmProblem.textRes(): Int = when (this) {
    AlarmProblem.NOTIFICATIONS_OFF -> R.string.alarm_problem_notificationsOff
    AlarmProblem.ALARM_VOLUME_OFF -> R.string.alarm_problem_alarmVolumeOff
    AlarmProblem.EXACT_ALARMS_OFF -> R.string.alarm_problem_exactAlarmsOff
    AlarmProblem.FULL_SCREEN_OFF -> R.string.alarm_problem_fullScreenOff
}

fun AlarmProblem.permissionKind(): PermissionKind = when (this) {
    AlarmProblem.NOTIFICATIONS_OFF -> PermissionKind.NOTIFICATIONS
    AlarmProblem.ALARM_VOLUME_OFF -> PermissionKind.ALARM_VOLUME
    AlarmProblem.EXACT_ALARMS_OFF -> PermissionKind.EXACT_ALARMS
    AlarmProblem.FULL_SCREEN_OFF -> PermissionKind.FULL_SCREEN
}

@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit, onOpenReminderSystem: () -> Unit, onOpenAlarmLog: () -> Unit) {
    val env = LocalAlarmEnvironment.current
    val snackbar = remember { SnackbarHostState() }
    AlarmSettingsScaffold(stringResource(R.string.settings_group_notifications), onBack, snackbar) { padding ->
        if (env != null) NotificationsSettingsContent(env, padding, snackbar, onOpenReminderSystem, onOpenAlarmLog)
    }
}

@Composable
private fun NotificationsSettingsContent(
    env: AlarmEnvironment,
    padding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenReminderSystem: () -> Unit,
    onOpenAlarmLog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe = rememberAlarmPermissionProbe(env)
    val status = probe.status
    val problem = status?.support?.worstProblem(status.alarmVolumeOff == true)
    val privacy = rememberAlarmSetting(env, SettingKeys.NotificationPrivacy) == "true"
    var testing by remember { mutableStateOf(false) }
    val armedText = stringFilled(R.string.settings_alarms_testArmed, "seconds" to TestAlarmSeconds)
    val failedText = stringResource(R.string.settings_alarms_testFailed)
    val statusText = when {
        status == null -> stringResource(R.string.settings_alarms_checking)
        problem != null -> stringResource(problem.textRes())
        else -> stringResource(R.string.settings_alarms_ready)
    }
    val alarmState = when {
        status == null -> AlarmReadiness.Checking
        problem != null -> AlarmReadiness.Problem
        else -> AlarmReadiness.Ready
    }
    NotificationsSettingsBody(
        padding = padding,
        readiness = alarmState,
        statusText = statusText,
        testing = testing,
        privacy = privacy,
        onStatus = { if (problem != null) env.health.open(context, problem.permissionKind()) else probe.refresh() },
        onTest = {
            testing = true
            scope.launch {
                val ok = runCatching { env.scheduler.bookTest() }.getOrDefault(false)
                testing = false
                snackbar.showSnackbar(if (ok) armedText else failedText)
            }
        },
        onPrivacy = { value -> scope.launch { env.settings.set(SettingKeys.NotificationPrivacy.name, value.toString()) } },
        onOpenReminderSystem = onOpenReminderSystem,
        onOpenAlarmLog = onOpenAlarmLog,
    )
}

enum class AlarmReadiness { Checking, Problem, Ready }

@Composable
fun NotificationsSettingsBody(
    padding: PaddingValues,
    readiness: AlarmReadiness,
    statusText: String,
    testing: Boolean,
    privacy: Boolean,
    onStatus: () -> Unit,
    onTest: () -> Unit,
    onPrivacy: (Boolean) -> Unit,
    onOpenReminderSystem: () -> Unit,
    onOpenAlarmLog: () -> Unit,
) {
    val warning = RemftTheme.colors.warning
    val problem = readiness == AlarmReadiness.Problem
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item { SettingsSectionHeader(stringResource(R.string.setx_sectionAlarms)) }
        item {
            SettingsCard {
                SettingsItem(
                    title = stringResource(R.string.settings_alarms_title),
                    modifier = Modifier.testTag("alarm-status").clickable(onClick = onStatus),
                    icon = if (problem) Icons.Outlined.AlarmOff else Icons.Outlined.AlarmOn,
                    tone = when (readiness) {
                        AlarmReadiness.Checking -> SettingsTone.Neutral
                        AlarmReadiness.Problem -> SettingsTone.Warning
                        AlarmReadiness.Ready -> SettingsTone.Success
                    },
                    subtitle = statusText,
                    subtitleModifier = Modifier.testTag("alarm-status-text"),
                    subtitleColor = if (problem) warning.color else MaterialTheme.colorScheme.onSurfaceVariant,
                    trailing = if (problem) {
                        { SettingsChevron() }
                    } else {
                        null
                    },
                )
                SettingsRow(
                    Icons.Outlined.PlayCircleOutline,
                    stringResource(R.string.settings_alarms_test),
                    stringFilled(R.string.settings_alarms_testSub, "seconds" to TestAlarmSeconds),
                    "alarm-test",
                    enabled = !testing,
                    trailing = null,
                    onClick = onTest,
                )
                SettingsRow(
                    Icons.Outlined.History,
                    stringResource(R.string.alarmLog_title),
                    stringResource(R.string.alarmLog_sub),
                    "settings-alarm-log",
                    onClick = onOpenAlarmLog,
                )
            }
        }
        item { SettingsSectionHeader(stringResource(R.string.setx_sectionReminders)) }
        item {
            SettingsCard {
                SettingsItem(
                    title = stringResource(R.string.settings_privateNotifications),
                    modifier = Modifier
                        .testTag("notification-privacy")
                        .toggleable(value = privacy, role = Role.Switch, onValueChange = onPrivacy),
                    icon = Icons.Outlined.NotificationsOff,
                    subtitle = stringResource(R.string.settings_privateNotificationsSub),
                    trailing = { SettingsSwitch(checked = privacy, onCheckedChange = null) },
                )
                SettingsRow(
                    Icons.Outlined.NotificationsActive,
                    stringResource(R.string.reminderSettings_title),
                    stringResource(R.string.reminderSettings_sub),
                    "settings-reminder-system",
                    onClick = onOpenReminderSystem,
                )
            }
        }
    }
}

@Preview(name = "Notifications light")
@Composable
private fun NotificationsLightPreview() {
    RemftTheme(darkTheme = false) { AppBackground { NotificationsPreviewBody(AlarmReadiness.Ready, "Ready — alarms are allowed to ring on time.") } }
}

@Preview(name = "Notifications dark")
@Composable
private fun NotificationsDarkPreview() {
    RemftTheme(darkTheme = true) { AppBackground { NotificationsPreviewBody(AlarmReadiness.Problem, "Exact alarms are switched off.") } }
}

@Composable
private fun NotificationsPreviewBody(readiness: AlarmReadiness, statusText: String) {
    NotificationsSettingsBody(PaddingValues(), readiness, statusText, testing = false, privacy = true, {}, {}, {}, {}, {})
}
