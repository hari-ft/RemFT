package com.ftapps.remft.data.backup

import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.DatabaseSchema
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.BackupReminder
import com.ftapps.remft.domain.backup.BackupValidator
import com.ftapps.remft.domain.backup.BackupZipWriter
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.BackupKind
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.backup.auto.BackupDataSize
import com.ftapps.remft.domain.backup.ValidatedBackup
import com.ftapps.remft.domain.backup.lock.WorkLockHold
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.backup.restore.BackupRestore
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class RestoreStage { Tasks, Files, Settings }

fun interface RestoredFilesFetcher {
    suspend fun fetch(dbFile: File, onProgress: (done: Int, total: Int) -> Unit)
}

class BackupService(
    private val paths: BackupPaths,
    private val holder: DatabaseHolder,
    private val store: SettingsStore,
    private val locks: () -> WorkLocks,
    private val appVersion: String,
    private val log: BackupLog = AndroidBackupLog,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val preferVacuumInto: Boolean = true,
    private val restoreLockWait: Duration = Duration.ofSeconds(45),
    private val closeWait: Duration = Duration.ofSeconds(10),
    private val missingLocalRefs: suspend () -> Map<String, MirrorRef> = { emptyMap() },
    private val restoredFiles: RestoredFilesFetcher? = null,
) {
    private val settler = RestoredDataSettler(store, log, clock)

    suspend fun build(): BuiltBackup {
        val now = clock()
        val notHere = try {
            missingLocalRefs()
        } catch (e: Exception) {
            log.warn("backup.mirror_refs_failed", e)
            emptyMap()
        }
        clearShareDir()
        return buildTo(
            File(paths.shareDir, BackupNames.manual(now.atZone(zone()).toLocalDate())),
            now,
            notHere,
            if (notHere.isEmpty()) null else BackupKind.COMPLETE_PARTIAL,
        )
    }

    suspend fun buildTo(out: File, now: Instant, listOnly: Map<String, MirrorRef> = emptyMap(), kind: String? = null): BuiltBackup =
        withContext(Dispatchers.IO) {
        FileOps.ensureDir(paths.scratch)
        val snapshot = File(paths.scratch, "backup-${micros(now)}.sqlite")
        try {
            BackupDatabases.snapshot(holder.db.openHelper.writableDatabase, holder.file, snapshot, preferVacuumInto)
            val referenced = BackupDatabases.referencedStoredNames(snapshot)
            val settings = try {
                store.backupSettings()
            } catch (e: Exception) {
                log.warn("backup.setting_unreadable", e)
                null
            }
            BackupZipWriter.build(
                dbSnapshot = snapshot,
                out = out,
                appVersion = appVersion,
                schemaVersion = DatabaseSchema.VERSION,
                referenced = referenced,
                filesDir = paths.filesDir,
                now = now,
                settings = settings,
                log = log,
                listOnly = listOnly,
                kind = kind,
            )
        } finally {
            FileOps.deleteFileIfExists(snapshot)
            for (side in BackupZipWriter.dbSidecars) FileOps.deleteFileIfExists(File(snapshot.path + side))
        }
    }

    suspend fun dataSize(): BackupDataSize = withContext(Dispatchers.IO) {
        holder.db.openHelper.readableDatabase.query(DATA_SIZE).use { cursor ->
            cursor.moveToFirst()
            BackupDataSize(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3))
        }
    }

    suspend fun recordShared(at: Instant = clock()) {
        store.set(SettingKeys.LastBackupAt.name, BackupReminder.lastBackupValue(at))
    }

    private suspend fun recordRestoredBackupDate(createdAt: Instant) {
        val madeAt = minOf(createdAt, clock())
        val known = UtcText.parse(store.get(SettingKeys.LastBackupAt.name))
        if (known == null || known.isBefore(madeAt)) store.set(SettingKeys.LastBackupAt.name, BackupReminder.lastBackupValue(madeAt))
    }

    fun clearShareDir() {
        val dir = paths.shareDir
        for (entry in dir.listFiles().orEmpty()) {
            runCatching { FileOps.deleteTree(entry) }.onFailure { log.warn("backup.share_sweep_failed", it) }
        }
    }

    suspend fun validate(input: InputStream): ValidatedBackup = withContext(Dispatchers.IO) {
        FileOps.ensureDir(paths.scratch)
        val copy = File(paths.scratch, "picked-${micros(clock())}.zip")
        try {
            input.use { FileOps.copyToFile(it, copy) }
            BackupValidator.validate(copy, DatabaseSchema.VERSION, paths.scratch, log, clock)
        } finally {
            FileOps.deleteFileIfExists(copy)
        }
    }

    suspend fun restore(
        backup: ValidatedBackup,
        onFiles: (done: Int, total: Int) -> Unit = { _, _ -> },
        onStage: (RestoreStage) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        var hold: WorkLockHold? = null
        try {
            onStage(RestoreStage.Tasks)
            hold = locks().beginRestore(restoreLockWait)
            if (!closeDatabase()) log.warn("backup.restore_db_close_timeout")
            BackupRestore.restore(
                backup,
                dbFile = paths.dbFile,
                filesDir = paths.filesDir,
                verify = BackupDatabases::verify,
                settle = settler,
                log = log,
                clock = clock,
            )
            log.info("backup.restored", mapOf("schema" to backup.manifest.schemaVersion, "files" to backup.manifest.fileCount))
            runCatching { recordRestoredBackupDate(backup.manifest.createdAt) }.onFailure { log.warn("backup.restored_date_failed", it) }
            backup.settings?.let { RestoreSettings.hold(store, it) }
            if (restoredFiles != null && backup.manifest.listedOnly.isNotEmpty()) {
                onStage(RestoreStage.Files)
                try {
                    restoredFiles.fetch(paths.dbFile, onFiles)
                } catch (e: Exception) {
                    log.warn("backup.restore_files_failed", e)
                }
            }
            onStage(RestoreStage.Settings)
            RestoreSettings.applyPending(store)
        } finally {
            runCatching { backup.discard() }.onFailure { log.warn("backup.restore_discard_failed", it) }
            hold?.release()
        }
    }

    private fun closeDatabase(): Boolean {
        val closed = CountDownLatch(1)
        thread(name = "restore-close-db", isDaemon = true) {
            try {
                holder.closeForRestore()
            } catch (e: Exception) {
                log.warn("backup.restore_db_close_failed", e)
            } finally {
                closed.countDown()
            }
        }
        return closed.await(closeWait.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun micros(at: Instant): Long = ChronoUnit.MICROS.between(Instant.EPOCH, at)

    companion object {
        private const val DATA_SIZE = "SELECT (SELECT COUNT(*) FROM tasks), (SELECT COUNT(*) FROM notes), " +
            "(SELECT COUNT(*) FROM file_rows), (SELECT COALESCE(SUM(size_bytes), 0) FROM file_rows)"
    }
}
