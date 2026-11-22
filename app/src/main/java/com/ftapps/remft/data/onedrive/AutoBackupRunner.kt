package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.mirror.BackupMirror
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.backup.auto.AutoBackupPause
import com.ftapps.remft.domain.backup.auto.BackupDataSize
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.auto.DestinationStatus
import com.ftapps.remft.domain.backup.auto.PauseRule
import com.ftapps.remft.domain.backup.auto.Retention
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

enum class AutoBackupOutcome { NotDue, NothingOn, Done, Failed, Busy, Paused, Incomplete }

fun interface AutoBackupBuilder {
    suspend fun build(out: File, now: Instant, listOnly: Map<String, MirrorRef>): BuiltBackup
}

class AutoBackupRunner(
    private val store: SettingsStore,
    private val destination: BackupDestination,
    private val builder: AutoBackupBuilder,
    private val scratch: File,
    private val mirror: BackupMirror? = null,
    private val dataSize: suspend () -> BackupDataSize? = { null },
    private val onSuccess: suspend (Instant) -> Unit = {},
    private val catchUpDeadline: Instant? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: BackupLog = AndroidBackupLog,
) {
    private val statusKey = DestinationStatus.key(destination.id)

    suspend fun isReady(): Boolean = store.get(SettingKeys.OneDriveOn.name) == OneDriveAuth.TRUE

    suspend fun lastSuccess(): Instant? = UtcText.parse(store.get(SettingKeys.AutoBackupLastOk.name))

    suspend fun isDue(now: Instant = clock()): Boolean =
        BackupSchedule.parse(store.get(SettingKeys.AutoBackupSchedule.name)).isDue(now, lastSuccess(), zone())

    suspend fun run(force: Boolean = false): AutoBackupOutcome {
        if (!isReady()) return AutoBackupOutcome.NothingOn
        if (!force && !isDue()) return AutoBackupOutcome.NotDue
        val now = clock()
        val name = BackupNames.automatic(now)

        val size = try {
            dataSize()
        } catch (e: Exception) {
            log.warn("autobackup.size_failed", e)
            null
        }
        if (size != null && pausedOnDrop(size, now)) return AutoBackupOutcome.Paused

        var listOnly = emptyMap<String, MirrorRef>()
        if (mirror != null) {
            try {
                mirror.catchUp(catchUpDeadline)
            } catch (e: Exception) {
                log.warn("autobackup.mirror_catch_up_failed", e)
            }
            try {
                listOnly = mirror.planForBackup(now)
            } catch (e: Exception) {
                log.error("autobackup.mirror_check_failed", e)
                writeStatus(DestinationStatus(now, false, (e as? OneDriveException)?.code ?: OneDriveErrors.MIRROR_CHECK))
                return AutoBackupOutcome.Failed
            }
        }

        val built = try {
            builder.build(File(scratch, name), now, listOnly)
        } catch (e: Exception) {
            log.error("autobackup.build_failed", e)
            writeStatus(DestinationStatus(now, false, OneDriveErrors.BUILD))
            return AutoBackupOutcome.Failed
        }

        val keepDays = Retention.keepDays(store.get(SettingKeys.AutoBackupKeepDays.name))
        val delivered = try {
            deliver(built.zip, name, keepDays, now)
        } finally {
            withContext(Dispatchers.IO) { runCatching { FileOps.deleteFileIfExists(built.zip) } }
        }
        if (!delivered) return AutoBackupOutcome.Failed
        store.set(SettingKeys.AutoBackupLastOk.name, UtcText.format(now))
        if (size != null) store.set(SettingKeys.AutoBackupLastSize.name, size.encode())
        store.remove(SettingKeys.AutoBackupDropAccepted.name)
        runCatching { onSuccess(now) }.onFailure { log.warn("autobackup.record_failed", it) }
        val unavailable = built.unavailable
        return if (unavailable.isEmpty()) {
            store.remove(SettingKeys.AutoBackupUnavailable.name)
            AutoBackupOutcome.Done
        } else {
            log.warn("autobackup.files_unavailable", fields = mapOf("count" to unavailable.size))
            store.set(SettingKeys.AutoBackupUnavailable.name, unavailable.size.toString())
            AutoBackupOutcome.Incomplete
        }
    }

    private suspend fun pausedOnDrop(size: BackupDataSize, now: Instant): Boolean {
        val accepted = PauseRule.dropAccepted(store.get(SettingKeys.AutoBackupDropAccepted.name))
        val previous = BackupDataSize.parse(store.get(SettingKeys.AutoBackupLastSize.name))
        if (PauseRule.shouldPause(size, previous, accepted) && previous != null) {
            log.warn(
                "autobackup.paused_on_drop",
                fields = mapOf(
                    "items" to size.items,
                    "previousItems" to previous.items,
                    "fileBytes" to size.fileBytes,
                    "previousFileBytes" to previous.fileBytes,
                ),
            )
            store.set(SettingKeys.AutoBackupPaused.name, AutoBackupPause(now, previous, size).encode())
            return true
        }
        store.remove(SettingKeys.AutoBackupPaused.name)
        return false
    }

    private suspend fun deliver(zip: File, name: String, keepDays: Int, now: Instant): Boolean {
        try {
            destination.upload(zip, name)
        } catch (e: Exception) {
            log.error("autobackup.upload_failed", e)
            writeStatus(DestinationStatus(now, false, (e as? OneDriveException)?.code ?: OneDriveErrors.UPLOAD))
            return false
        }
        var pruned = 0
        try {
            val expired = Retention.expired(destination.list(), keepDays, now, zone())
            for (backup in expired) {
                if (!BackupNames.isAutomatic(backup.name)) continue
                destination.delete(backup)
                pruned++
            }
        } catch (e: Exception) {
            log.warn("autobackup.prune_failed", e, mapOf("destination" to destination.id))
        }
        log.info("autobackup.done", mapOf("destination" to destination.id, "pruned" to pruned))
        writeStatus(DestinationStatus(now, true))
        return true
    }

    private suspend fun writeStatus(status: DestinationStatus) {
        store.set(statusKey, status.encode())
    }
}
