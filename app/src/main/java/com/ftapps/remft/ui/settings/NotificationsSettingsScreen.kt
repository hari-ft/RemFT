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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmProblem
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.theme.Breakpoints
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
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            SettingsCard {
                ListItem(
                    modifier = Modifier.testTag("alarm-status").clickable {
                        if (problem != null) env.health.open(context, problem.permissionKind()) else probe.refresh()
                    },
                    leadingContent = {
                        Icon(
                            if (problem != null) Icons.Outlined.AlarmOff else Icons.Outlined.AlarmOn,
                            contentDescription = null,
                            tint = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_alarms_title)) },
                    supportingContent = { Text(statusText, modifier = Modifier.testTag("alarm-status-text")) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsRow(
                    Icons.Outlined.PlayCircleOutline,
                    stringResource(R.string.settings_alarms_test),
                    stringFilled(R.string.settings_alarms_testSub, "seconds" to TestAlarmSeconds),
                    "alarm-test",
                    enabled = !testing,
                    trailing = null,
                    onClick = {
                        testing = true
                        scope.launch {
                            val ok = runCatching { env.scheduler.bookTest() }.getOrDefault(false)
                            testing = false
                            snackbar.showSnackbar(if (ok) armedText else failedText)
                        }
                    },
                )
                ListItem(
                    modifier = Modifier
                        .testTag("notification-privacy")
                        .toggleable(value = privacy, role = Role.Switch) { value ->
                            scope.launch { env.settings.set(SettingKeys.NotificationPrivacy.name, value.toString()) }
                        },
                    leadingContent = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_privateNotifications)) },
                    supportingContent = { Text(stringResource(R.string.settings_privateNotificationsSub)) },
                    trailingContent = { Switch(checked = privacy, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsRow(
                    Icons.Outlined.NotificationsActive,
                    stringResource(R.string.reminderSettings_title),
                    stringResource(R.string.reminderSettings_sub),
                    "settings-reminder-system",
                    onClick = onOpenReminderSystem,
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
    }
}
