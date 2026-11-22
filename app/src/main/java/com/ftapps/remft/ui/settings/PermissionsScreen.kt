package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.theme.Breakpoints
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
    val scheme = MaterialTheme.colorScheme
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.x2, end = Space.x2, bottom = Space.x3),
            )
        }
        item {
            SettingsCard {
                PermissionKind.entries.forEach { kind ->
                    val spec = kind.spec()
                    val loaded = status != null
                    val allowed = status?.allowed(kind)
                    val statusText = stringResource(permissionStatusText(kind, allowed))
                    val blocked = loaded && allowed == false
                    ListItem(
                        modifier = Modifier.testTag("permission-${kind.name}").clickable { env.health.open(context, kind) },
                        leadingContent = { Icon(spec.icon, contentDescription = null) },
                        headlineContent = {
                            Text(stringResource(spec.title), color = if (blocked) scheme.error else Color.Unspecified)
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(spec.why))
                                if (loaded) {
                                    Text(
                                        statusText,
                                        color = if (blocked) scheme.error else Color.Unspecified,
                                        modifier = Modifier.testTag("permission-${kind.name}-status"),
                                    )
                                }
                            }
                        },
                        trailingContent = if (loaded) {
                            {
                                Icon(
                                    when (allowed) {
                                        true -> Icons.Outlined.CheckCircle
                                        false -> Icons.Outlined.ErrorOutline
                                        null -> Icons.AutoMirrored.Outlined.HelpOutline
                                    },
                                    contentDescription = statusText,
                                    tint = when (allowed) {
                                        true -> scheme.primary
                                        false -> scheme.error
                                        null -> scheme.onSurfaceVariant
                                    },
                                )
                            }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
