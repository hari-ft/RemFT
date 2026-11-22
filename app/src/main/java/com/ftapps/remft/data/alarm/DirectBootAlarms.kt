package com.ftapps.remft.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import com.ftapps.remft.domain.alarm.AlarmTexts
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.alarm.SnoozePresets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class LockedAnswer(
    val id: Int? = null,
    val actionId: String,
    val payload: String? = null,
    val atMillis: Long = 0,
)

@Serializable
data class MirrorState(
    val version: Int = 1,
    val writtenAtMillis: Long = 0,
    val privacy: Boolean = false,
    val snoozeOrder: List<String> = emptyList(),
    val alarms: List<BookedAlarm> = emptyList(),
    val answers: List<LockedAnswer> = emptyList(),
    val delivered: List<Int> = emptyList(),
)

class DirectBootMirror(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): MirrorState = synchronized(LOCK) { readLocked() }

    fun replaceAlarms(
        booked: Collection<BookedAlarm>,
        now: Instant,
        privacy: Boolean,
        texts: AlarmTexts,
        snoozeOrder: List<String>,
    ): Set<Int> = synchronized(LOCK) {
        val current = readLocked()
        val kept = bounded(booked, now).map { mirrored(it, privacy, texts) }
        writeLocked(
            current.copy(
                writtenAtMillis = now.toEpochMilli(),
                privacy = privacy,
                snoozeOrder = snoozeOrder,
                alarms = kept,
            ),
        )
        current.alarms.map { it.id }.toSet() - kept.map { it.id }.toSet()
    }

    fun recordAnswer(answer: LockedAnswer) = synchronized(LOCK) {
        val current = readLocked()
        writeLocked(current.copy(answers = (current.answers + answer).takeLast(MAX_QUEUED)))
    }

    fun recordDelivered(id: Int) = synchronized(LOCK) {
        val current = readLocked()
        writeLocked(current.copy(delivered = (current.delivered - id + id).takeLast(MAX_QUEUED)))
    }

    fun takeQueued(): MirrorState = synchronized(LOCK) {
        val current = readLocked()
        if (current.answers.isNotEmpty() || current.delivered.isNotEmpty()) {
            writeLocked(current.copy(answers = emptyList(), delivered = emptyList()))
        }
        current
    }

    private fun readLocked(): MirrorState {
        if (!file.exists()) return MirrorState()
        return runCatching { json.decodeFromString<MirrorState>(file.readText()) }.getOrDefault(MirrorState())
    }

    private fun writeLocked(state: MirrorState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        temp.writeText(json.encodeToString(state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private val LOCK = Any()
        const val DIRECTORY = "alarms-boot"
        const val FILE_NAME = "mirror.json"
        const val MAX_ALARMS = 24
        const val MAX_QUEUED = 32
        val WINDOW: Duration = Duration.ofHours(48)

        fun of(context: Context): DirectBootMirror = DirectBootMirror(File(File(DirectBoot.storage(context).filesDir, DIRECTORY), FILE_NAME))

        fun bounded(booked: Collection<BookedAlarm>, now: Instant): List<BookedAlarm> {
            val from = now.minus(AlarmScheduler.IN_FLIGHT_GRACE)
            val until = now.plus(WINDOW)
            return booked
                .filter { it.fireAt.isAfter(from) && !it.fireAt.isAfter(until) }
                .sortedWith(compareBy({ it.fireAtMillis }, { it.id }))
                .take(MAX_ALARMS)
        }

        fun mirrored(alarm: BookedAlarm, privacy: Boolean, texts: AlarmTexts): BookedAlarm {
            val sound = alarm.soundName?.takeUnless { it.startsWith("content://") }
            return if (privacy) {
                alarm.copy(title = texts.privacyTitle, body = texts.privateBody, soundName = sound)
            } else {
                alarm.copy(soundName = sound)
            }
        }
    }
}

object DirectBoot {
    fun isUnlocked(context: Context): Boolean = UserManagerCompat.isUserUnlocked(context)

    fun storage(context: Context): Context = ContextCompat.createDeviceProtectedStorageContext(context) ?: context

    fun whenUnlocked(context: Context, action: () -> Unit) {
        val app = context.applicationContext
        val done = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context, intent: Intent) {
                if (done.compareAndSet(false, true)) {
                    runCatching { app.unregisterReceiver(this) }
                    action()
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (isUnlocked(app) && done.compareAndSet(false, true)) {
            runCatching { app.unregisterReceiver(receiver) }
            action()
        }
    }
}

object DirectBootAlarms {
    fun rebook(context: Context, now: Instant, gateway: AlarmGateway = AndroidAlarmGateway(context)): Int {
        val state = DirectBootMirror.of(context).read()
        var booked = 0
        for (alarm in DirectBootMirror.bounded(state.alarms, now)) {
            if (alarm.id in state.delivered) continue
            if (runCatching { gateway.book(alarm.toPlanned()) }.isSuccess) booked++
        }
        return booked
    }

    fun fire(context: Context, notification: PlannedNotification): Result<Unit> {
        val mirror = DirectBootMirror.of(context)
        val state = mirror.read()
        val order = state.snoozeOrder.ifEmpty { SnoozePresets.ids }
        val gateway = AndroidAlarmGateway(context, snoozeOrder = { order }, fullScreen = { canOpenFullScreen(context) })
        val shown = runCatching { gateway.show(notification) }
        if (shown.isSuccess) runCatching { AlarmFireReceiver.replaceEarlierRings(gateway, notification) }
        runCatching { mirror.recordDelivered(notification.id) }
        return shown
    }

    fun answer(context: Context, id: Int?, actionId: String, payload: String?, now: Instant) {
        runCatching { DirectBootMirror.of(context).recordAnswer(LockedAnswer(id, actionId, payload, now.toEpochMilli())) }
        if (id != null) runCatching { AndroidAlarmGateway(context).cancelShown(id) }
    }

    fun canOpenFullScreen(context: Context): Boolean {
        val intent = Intent(context, com.ftapps.remft.MainActivity::class.java).setAction(AndroidAlarmGateway.ACTION_FULL_SCREEN)
        return runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
    }

    suspend fun settle(env: AlarmEnvironment) {
        val queued = runCatching { env.mirror.takeQueued() }.getOrNull() ?: return
        for (id in queued.delivered) {
            val entry = runCatching { env.pending.readOrEmpty()[id] }.getOrNull() ?: continue
            runCatching { env.pending.remove(id) }
            runCatching {
                env.log.record(
                    AlarmLogEvent.DELIVERED,
                    AlarmLogLane.NOTIFICATION,
                    kind = entry.kind,
                    slotIndex = entry.slotIndex,
                    urgent = entry.urgent,
                    fireAt = entry.fireAt,
                    taskId = entry.taskId,
                    reminderId = entry.reminderId,
                    detail = "id=$id locked",
                )
            }
        }
        for (answer in queued.answers) {
            runCatching { env.actions.handle(answer.id, answer.actionId, answer.payload) }
        }
    }
}
