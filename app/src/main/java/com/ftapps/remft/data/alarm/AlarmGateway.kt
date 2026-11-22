package com.ftapps.remft.data.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.ftapps.remft.MainActivity
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.ButtonLabel
import com.ftapps.remft.domain.alarm.NotificationActions
import com.ftapps.remft.domain.alarm.NotificationButton
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.tasks.DeepLinks
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AlarmGateway {
    fun book(notification: PlannedNotification): BookResult

    fun cancel(id: Int)

    fun show(notification: PlannedNotification)

    fun showRinging(notification: PlannedNotification)

    fun shownIds(): Set<Int>

    fun shownPayloads(): Map<Int, String?>

    fun showMissed(notification: PlannedNotification)

    fun cancelShown(id: Int)

    fun pruneChannels(alarmSoundName: String?, reminderSoundName: String?): List<String>
}

enum class BookResult { EXACT, INEXACT }

class AndroidAlarmGateway(
    private val context: Context,
    private val snoozeOrder: () -> List<String> = { SnoozePresets.ids },
    private val fullScreen: () -> Boolean = { true },
) : AlarmGateway {
    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notificationManager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun book(notification: PlannedNotification): BookResult {
        val fire = firePendingIntent(context, notification)
        val at = notification.fireAt.toEpochMilli()
        return try {
            if (notification.urgent) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, contentIntent(context, notification)), fire)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            }
            BookResult.EXACT
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            BookResult.INEXACT
        }
    }

    override fun cancel(id: Int) {
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            fireIntent(context, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    override fun show(notification: PlannedNotification) {
        ensureChannel(context, notification.urgent, notification.soundName)
        notificationManager.notify(notification.id, AlarmNotifications.build(context, notification, snoozeOrder(), fullScreen()))
    }

    @SuppressLint("MissingPermission")
    override fun showRinging(notification: PlannedNotification) {
        ensureChannel(context, notification.urgent, notification.soundName)
        notificationManager.notify(notification.id, AlarmNotifications.build(context, notification, snoozeOrder(), fullScreen = false))
    }

    override fun shownIds(): Set<Int> = runCatching { notificationManager.activeNotifications.map { it.id }.toSet() }
        .getOrDefault(emptySet())

    override fun shownPayloads(): Map<Int, String?> = runCatching {
        notificationManager.activeNotifications.associate { it.id to it.notification.extras?.getString(EXTRA_PAYLOAD) }
    }.getOrDefault(emptyMap())

    @SuppressLint("MissingPermission")
    override fun showMissed(notification: PlannedNotification) {
        ensureChannel(context, false, AlarmNotifications.missedSound(notification))
        notificationManager.notify(notification.id, AlarmNotifications.build(context, notification, snoozeOrder(), fullScreen = false, missed = true))
    }

    override fun cancelShown(id: Int) = notificationManager.cancel(id)

    override fun pruneChannels(alarmSoundName: String?, reminderSoundName: String?): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val keep = AlarmSounds.channelsToKeep(alarmSoundName, reminderSoundName)
        val existing = notificationManager.notificationChannels.map { it.id }
        val showing = runCatching {
            notificationManager.activeNotifications.mapNotNull { it.notification.channelId }.toSet()
        }.getOrDefault(existing.toSet())
        val stale = AlarmSounds.staleChannels(existing, keep, showing)
        stale.forEach { notificationManager.deleteNotificationChannel(it) }
        return stale
    }

    companion object {
        const val ACTION_FIRE = "com.ftapps.remft.alarm.FIRE"
        const val ACTION_BUTTON = "com.ftapps.remft.alarm.BUTTON"
        const val ACTION_FULL_SCREEN = "com.ftapps.remft.alarm.FULL_SCREEN"
        const val EXTRA_ALARM = "remft.alarm"
        const val EXTRA_ID = "remft.notificationId"
        const val EXTRA_ACTION = "remft.actionId"
        const val EXTRA_PAYLOAD = "remft.payload"
        const val EXTRA_TAPPED = "remft.tapped"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(notification: PlannedNotification): String = json.encodeToString(BookedAlarm.of(notification))

        fun decode(raw: String?): PlannedNotification? =
            raw?.let { runCatching { json.decodeFromString<BookedAlarm>(it).toPlanned() }.getOrNull() }

        fun fireIntent(context: Context, id: Int): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData("remft-alarm://fire/$id".toUri())

        fun firePendingIntent(context: Context, notification: PlannedNotification): PendingIntent = PendingIntent.getBroadcast(
            context,
            notification.id,
            fireIntent(context, notification.id).putExtra(EXTRA_ALARM, encode(notification)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun contentIntent(context: Context, notification: PlannedNotification): PendingIntent {
            val link = notification.taskId?.let { "${DeepLinks.SCHEME}://task/$it" } ?: "${DeepLinks.SCHEME}://open"
            val intent = Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java)
                .putExtra(EXTRA_ID, notification.id)
                .putExtra(EXTRA_PAYLOAD, notification.payload)
                .putExtra(EXTRA_TAPPED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, notification.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun fullScreenIntent(context: Context, notification: PlannedNotification): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_FULL_SCREEN)
                .setData("remft-alarm://ring/${notification.id}".toUri())
                .putExtra(EXTRA_ID, notification.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, notification.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun actionIntent(context: Context, notification: PlannedNotification, actionId: String): PendingIntent {
            val intent = Intent(context, AlarmActionReceiver::class.java)
                .setAction(ACTION_BUTTON)
                .setData("remft-alarm://action/${notification.id}/${Uri.encode(actionId)}".toUri())
                .putExtra(EXTRA_ID, notification.id)
                .putExtra(EXTRA_ACTION, actionId)
                .putExtra(EXTRA_PAYLOAD, notification.payload)
            return PendingIntent.getBroadcast(context, notification.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun soundUri(context: Context, soundName: String?): Uri? = when {
            soundName == null -> null
            soundName.startsWith("content://") -> soundName.toUri()
            else -> AlarmSoundStore.rawId(soundName)?.let { "android.resource://${context.packageName}/raw/$soundName".toUri() }
        }

        fun audioAttributes(urgent: Boolean): AudioAttributes = AudioAttributes.Builder()
            .setUsage(if (urgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun ensureChannel(context: Context, urgent: Boolean, soundName: String?): String {
            val id = AlarmSounds.channelIdFor(urgent, soundName)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(id) != null) return id
            val channel = NotificationChannel(
                id,
                context.getString(if (urgent) R.string.notif_channel_urgentName else R.string.notif_channel_remindersName),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = context.getString(if (urgent) R.string.notif_channel_urgentDesc else R.string.notif_channel_remindersDesc)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.enableVibration(true)
            val sound = soundUri(context, soundName) ?: Settings.System.DEFAULT_NOTIFICATION_URI
            if (sound.scheme == "content" && sound.authority != Settings.AUTHORITY) grantToSystem(context, sound)
            channel.setSound(sound, audioAttributes(urgent))
            manager.createNotificationChannel(channel)
            return id
        }

        private fun grantToSystem(context: Context, uri: Uri) {
            runCatching { context.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { context.grantUriPermission("android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        fun isTest(id: Int): Boolean = id == AlarmPlanner.TEST_NOTIFICATION_ID
    }
}

object AlarmNotifications {
    fun build(
        context: Context,
        notification: PlannedNotification,
        snoozeOrder: List<String> = SnoozePresets.ids,
        fullScreen: Boolean = true,
        missed: Boolean = false,
    ): Notification {
        val urgent = notification.urgent
        val insistent = urgent && !missed
        val test = notification.payload == AlarmPlanner.TEST_PAYLOAD
        val channelId = if (missed) AlarmSounds.channelIdFor(false, missedSound(notification)) else AlarmSounds.channelIdFor(urgent, notification.soundName)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_remft)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(if (insistent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setWhen(notification.fireAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(!insistent || test)
            .setOngoing(false)
            .setContentIntent(AndroidAlarmGateway.contentIntent(context, notification))
            .addExtras(Bundle().apply { putString(AndroidAlarmGateway.EXTRA_PAYLOAD, notification.payload) })
        if (missed) builder.setSubText(context.getString(R.string.almed_missed_subtext))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) legacySound(context, builder, insistent, if (missed) missedSound(notification) else notification.soundName)
        if (insistent && fullScreen) builder.setFullScreenIntent(AndroidAlarmGateway.fullScreenIntent(context, notification), true)
        for (button in buttonsOf(test, urgent, snoozeOrder)) {
            builder.addAction(0, labelOf(context, button), AndroidAlarmGateway.actionIntent(context, notification, button.actionId))
        }
        val built = builder.build()
        if (insistent) built.flags = built.flags or Notification.FLAG_INSISTENT
        return built
    }

    fun missedSound(notification: PlannedNotification): String? = if (notification.urgent) null else notification.soundName

    private fun buttonsOf(test: Boolean, urgent: Boolean, snoozeOrder: List<String>): List<NotificationButton> =
        if (test) listOf(NotificationButton(NotificationActions.ACKNOWLEDGE, ButtonLabel.ACKNOWLEDGE)) else NotificationActions.buttonsFor(urgent, snoozeOrder)

    @Suppress("DEPRECATION")
    private fun legacySound(context: Context, builder: NotificationCompat.Builder, alarm: Boolean, soundName: String?) {
        val uri = AndroidAlarmGateway.soundUri(context, soundName)
        if (uri == null) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            builder.setSound(uri, if (alarm) android.media.AudioManager.STREAM_ALARM else android.media.AudioManager.STREAM_NOTIFICATION)
        }
    }

    fun labelOf(context: Context, button: NotificationButton): String = when (button.label) {
        ButtonLabel.ACKNOWLEDGE -> context.getString(R.string.notif_action_acknowledge)
        ButtonLabel.COMPLETE -> context.getString(R.string.notif_action_complete)
        ButtonLabel.SNOOZE -> SnoozeLabels.of(context.resources, button.preset ?: SnoozePresets.DEFAULT)
    }
}
