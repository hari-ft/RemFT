package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.AppBackground
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

private class PermissionRowSpec(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val why: Int)

private fun PermissionKind.spec(): PermissionRowSpec = when (this) {
    PermissionKind.NOTIFICATIONS -> PermissionRowSpec(Icons.Outlined.Notifications, R.string.permissions_notifications_title, R.string.permissions_notifications_why)
    PermissionKind.EXACT_ALARMS -> PermissionRowSpec(Icons.Outlined.Alarm, R.string.permissions_exactAlarms_title, R.string.permissions_exactAlarms_why)
    PermissionKind.FULL_SCREEN -> PermissionRowSpec(Icons.Outlined.Fullscreen, R.string.permissions_fullScreen_title, R.string.permissions_fullScreen_why)
    PermissionKind.BATTERY -> PermissionRowSpec(Icons.Outlined.BatteryChargingFull, R.string.permissions_battery_title, R.string.permissions_battery_why)
    PermissionKind.BACKGROUND_DATA -> PermissionRowSpec(Icons.Outlined.CloudSync, R.string.permissions_backgroundData_title, R.string.permissions_backgroundData_why)
    PermissionKind.ALARM_VOLUME -> PermissionRowSpec(Icons.AutoMirrored.Outlined.VolumeUp, R.string.permissions_alarmVolume_title, R.string.permissions_alarmVolume_why)
}

@StringRes
private fun permissionStatusText(kind: PermissionKind, allowed: Boolean?): Int = when {
    allowed == null -> R.string.permissions_unknown
    kind == PermissionKind.ALARM_VOLUME -> if (allowed) R.string.permissions_volumeOk else R.string.permissions_volumeZero
    allowed -> R.string.permissions_allowed
    else -> R.string.permissions_blocked
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val env = LocalAlarmEnvironment.current
    AlarmSettingsScaffold(stringResource(R.string.settings_group_permissions), onBack) { padding ->
        if (env != null) PermissionsContent(env, padding)
    }
}

@Composable
private fun PermissionsContent(env: AlarmEnvironment, padding: PaddingValues) {
    val context = LocalContext.current
    val status = rememberAlarmPermissionProbe(env).status
    PermissionsBody(
        padding = padding,
        loaded = status != null,
        allowed = { kind -> status?.allowed(kind) },
        onOpen = { kind -> env.health.open(context, kind) },
    )
}

@Composable
fun PermissionsBody(padding: PaddingValues, loaded: Boolean, allowed: (PermissionKind) -> Boolean?, onOpen: (PermissionKind) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val semantic = RemftTheme.colors
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item { SettingsNote(stringResource(R.string.permissions_intro)) }
        item { Spacer(Modifier.height(Space.x2)) }
        item {
            SettingsCard {
                PermissionKind.entries.forEach { kind ->
                    val spec = kind.spec()
                    val state = if (loaded) allowed(kind) else null
                    val statusText = stringResource(permissionStatusText(kind, state))
                    val statusColor = when {
                        !loaded || state == null -> scheme.onSurfaceVariant
                        state -> semantic.success.color
                        else -> semantic.warning.color
                    }
                    SettingsItem(
                        title = stringResource(spec.title),
                        modifier = Modifier.testTag("permission-${kind.name}").clickable { onOpen(kind) },
                        icon = spec.icon,
                        tone = when {
                            !loaded -> SettingsTone.Brand
                            state == true -> SettingsTone.Success
                            state == false -> SettingsTone.Warning
                            else -> SettingsTone.Neutral
                        },
                        subtitle = stringResource(spec.why),
                        below = if (loaded) {
                            {
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusColor,
                                    modifier = Modifier.padding(top = Space.x1).testTag("permission-${kind.name}-status"),
                                )
                            }
                        } else {
                            null
                        },
                        trailing = if (loaded) {
                            {
                                Icon(
                                    when (state) {
                                        true -> Icons.Outlined.CheckCircle
                                        false -> Icons.Outlined.ErrorOutline
                                        null -> Icons.AutoMirrored.Outlined.HelpOutline
                                    },
                                    contentDescription = statusText,
                                    tint = statusColor,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Preview(name = "Permissions light")
@Composable
private fun PermissionsLightPreview() {
    RemftTheme(darkTheme = false) { AppBackground { PermissionsPreviewBody() } }
}

@Preview(name = "Permissions dark")
@Composable
private fun PermissionsDarkPreview() {
    RemftTheme(darkTheme = true) { AppBackground { PermissionsPreviewBody() } }
}

@Composable
private fun PermissionsPreviewBody() {
    PermissionsBody(
        padding = PaddingValues(),
        loaded = true,
        allowed = { kind -> kind != PermissionKind.BATTERY && kind != PermissionKind.BACKGROUND_DATA },
        onOpen = {},
    )
}
