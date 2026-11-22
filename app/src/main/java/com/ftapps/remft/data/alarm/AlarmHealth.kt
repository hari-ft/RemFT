package com.ftapps.remft.data.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.ftapps.remft.domain.alarm.AlarmSupport

enum class PermissionKind { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, BACKGROUND_DATA, ALARM_VOLUME }

data class PermissionStatus(
    val notifications: Boolean,
    val exactAlarms: Boolean?,
    val fullScreen: Boolean?,
    val battery: Boolean?,
    val backgroundData: Boolean?,
    val alarmVolumeOff: Boolean?,
) {
    val support: AlarmSupport get() = AlarmSupport(notifications, exactAlarms, fullScreen)

    fun allowed(kind: PermissionKind): Boolean? = when (kind) {
        PermissionKind.NOTIFICATIONS -> notifications
        PermissionKind.EXACT_ALARMS -> exactAlarms
        PermissionKind.FULL_SCREEN -> fullScreen
        PermissionKind.BATTERY -> battery
        PermissionKind.BACKGROUND_DATA -> backgroundData
        PermissionKind.ALARM_VOLUME -> alarmVolumeOff?.not()
    }

    companion object {
        val Unknown = PermissionStatus(true, null, null, null, null, null)
    }
}

interface AlarmHealth {
    fun status(): PermissionStatus

    fun intentsFor(kind: PermissionKind): List<Intent>

    fun open(context: Context, kind: PermissionKind): Boolean {
        for (intent in intentsFor(kind)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return false
    }
}

class AndroidAlarmHealth(private val context: Context) : AlarmHealth {
    override fun status(): PermissionStatus = PermissionStatus(
        notifications = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true),
        exactAlarms = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
        }.getOrNull(),
        fullScreen = runCatching {
            if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true
        }.getOrNull(),
        battery = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
        backgroundData = runCatching {
            context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrNull(),
        alarmVolumeOff = runCatching { context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) <= 0 }.getOrNull(),
    )

    override fun intentsFor(kind: PermissionKind): List<Intent> {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val first: Intent? = when (kind) {
            PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                null
            }
            PermissionKind.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            PermissionKind.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            PermissionKind.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionKind.BACKGROUND_DATA -> Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, pkg)
            PermissionKind.ALARM_VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
        }
        val fallback = if (kind == PermissionKind.ALARM_VOLUME) Intent(Settings.ACTION_SETTINGS) else appInfo
        return listOfNotNull(first, fallback).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
