package com.ftapps.remft.data.alarm

import com.ftapps.remft.domain.alarm.PlannedNotification
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class BookedAlarm(
    val id: Int,
    val title: String,
    val body: String,
    val fireAtMillis: Long,
    val urgent: Boolean,
    val payload: String,
    val kind: String? = null,
    val slotIndex: Int? = null,
    val taskId: String? = null,
    val reminderId: String? = null,
    val soundName: String? = null,
) {
    val fireAt: Instant get() = Instant.ofEpochMilli(fireAtMillis)

    fun toPlanned(): PlannedNotification = PlannedNotification(
        id = id,
        title = title,
        body = body,
        fireAt = fireAt,
        urgent = urgent,
        payload = payload,
        kind = kind,
        slotIndex = slotIndex,
        taskId = taskId,
        reminderId = reminderId,
        soundName = soundName,
    )

    companion object {
        fun of(notification: PlannedNotification): BookedAlarm = BookedAlarm(
            id = notification.id,
            title = notification.title,
            body = notification.body,
            fireAtMillis = notification.fireAt.toEpochMilli(),
            urgent = notification.urgent,
            payload = notification.payload,
            kind = notification.kind,
            slotIndex = notification.slotIndex,
            taskId = notification.taskId,
            reminderId = notification.reminderId,
            soundName = notification.soundName,
        )
    }
}

@Serializable
private data class PendingFile(val version: Int = 1, val alarms: List<BookedAlarm> = emptyList())

class PendingUnreadable(cause: Throwable) : Exception(cause)

class PendingAlarms(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): Map<Int, BookedAlarm> = synchronized(LOCK) { readLocked() }

    fun readOrEmpty(): Map<Int, BookedAlarm> = try {
        read()
    } catch (_: PendingUnreadable) {
        emptyMap()
    }

    fun put(alarm: BookedAlarm) = update { it[alarm.id] = alarm }

    fun remove(id: Int) = update { it.remove(id) }

    fun replaceAll(alarms: Collection<BookedAlarm>) = synchronized(LOCK) {
        writeLocked(alarms.associateBy { it.id })
    }

    fun update(change: (MutableMap<Int, BookedAlarm>) -> Unit) = synchronized(LOCK) {
        val current = try {
            readLocked().toMutableMap()
        } catch (_: PendingUnreadable) {
            LinkedHashMap()
        }
        change(current)
        writeLocked(current)
    }

    private fun readLocked(): Map<Int, BookedAlarm> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString<PendingFile>(file.readText()).alarms.associateBy { it.id }
        } catch (e: Exception) {
            throw PendingUnreadable(e)
        }
    }

    private fun writeLocked(alarms: Map<Int, BookedAlarm>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        temp.writeText(json.encodeToString(PendingFile(alarms = alarms.values.sortedBy { it.fireAtMillis })))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private val LOCK = Any()
        const val FILE_NAME = "pending.json"
        const val DIRECTORY = "alarms"
    }
}
