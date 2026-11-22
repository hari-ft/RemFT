package com.ftapps.remft.domain.backup.restore

import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.SqliteHeader
import com.ftapps.remft.domain.backup.ValidatedBackup
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

fun interface RestoreSettle {
    fun settle(dbFile: File, listed: List<BackupFileEntry>)
}

object BackupRestore {
    val defaultVerify: (File) -> Unit = { SqliteHeader.requireDatabase(it) }

    fun recoveryPending(dbFile: File, filesDir: File): Boolean {
        val paths = RestorePaths(dbFile, filesDir)
        return paths.journal.isFile ||
            (paths.previous.isFile && !dbFile.isFile) ||
            (paths.filesPrevious.isDirectory && !filesDir.isDirectory)
    }

    fun recoverInterruptedRestore(
        dbFile: File,
        filesDir: File,
        settle: RestoreSettle? = null,
        log: BackupLog = BackupLog.None,
    ): RestoreRecovery {
        val paths = RestorePaths(dbFile, filesDir)
        FileOps.deleteFileIfExists(paths.journalTmp)
        if (!paths.journal.isFile) return recoverWithoutJournal(paths, log)

        val journal = RestoreJournal.parse(paths.journal.readText(Charsets.UTF_8))
        val result: RestoreRecovery
        when (journal.phase) {
            RestorePhase.Staged -> {
                discardStaged(paths)
                result = RestoreRecovery.DiscardedStaging
            }
            RestorePhase.SwappingDb, RestorePhase.SwappingFiles -> {
                rollBack(paths, journal)
                result = RestoreRecovery.RolledBack
            }
            RestorePhase.Done -> {
                val settled = settle(paths, journal, settle, log, null)
                finish(paths, journal.keep, log, null)
                result = RestoreRecovery.Finished
                if (settle != null && !settled.settled) {
                    log.warn("backup.restore_recovered", fields = mapOf("phase" to journal.phase.wire, "result" to "unsettled"))
                    return result
                }
            }
        }
        FileOps.deleteFileIfExists(paths.journal)
        log.warn("backup.restore_recovered", fields = mapOf("phase" to journal.phase.wire, "result" to result.wire))
        return result
    }

    fun restore(
        backup: ValidatedBackup,
        dbFile: File,
        filesDir: File,
        verify: (File) -> Unit = defaultVerify,
        settle: RestoreSettle? = null,
        log: BackupLog = BackupLog.None,
        clock: () -> Instant = Instant::now,
        onStep: ((String) -> Unit)? = null,
    ) {
        val paths = RestorePaths(dbFile, filesDir)
        val step: (String) -> Unit = { name -> onStep?.invoke(name) }

        try {
            step("recover")
            recoverInterruptedRestore(dbFile, filesDir, log = log)
            step("recovered")
            setAsideLeftovers(paths, log, clock)
        } catch (e: Throwable) {
            backup.discard()
            throw e
        }

        var journal = RestoreJournal(
            RestorePhase.Staged,
            hadDb = dbFile.isFile,
            hadFiles = filesDir.isDirectory,
            keep = backup.manifest.listedOnly.map { RestoreKeep.of(it) },
        )
        try {
            writeJournal(paths, journal)
            discardStaged(paths)
            FileOps.move(backup.dbFile, paths.incoming)
            if (backup.filesDir.isDirectory) {
                FileOps.move(backup.filesDir, paths.filesIncoming)
            } else {
                FileOps.ensureDir(paths.filesIncoming)
            }
            step("staged")

            journal = journal.at(RestorePhase.SwappingDb)
            writeJournal(paths, journal)
            step("swapping-db")
            if (journal.hadDb) FileOps.rename(dbFile, paths.previous)
            for (sidecar in paths.sidecars) {
                if (sidecar.isFile) FileOps.rename(sidecar, paths.parked(sidecar))
            }
            step("db-parked")
            FileOps.rename(paths.incoming, dbFile)
            step("db-in")
            verify(dbFile)
            step("verified")

            journal = journal.at(RestorePhase.SwappingFiles)
            writeJournal(paths, journal)
            if (journal.hadFiles) FileOps.rename(filesDir, paths.filesPrevious)
            step("files-parked")
            FileOps.rename(paths.filesIncoming, filesDir)
            step("files-in")

            journal = journal.at(RestorePhase.Done)
            writeJournal(paths, journal)
            step("done")
        } catch (kill: SimulatedProcessKill) {
            throw kill
        } catch (e: Throwable) {
            try {
                rollBack(paths, journal)
                FileOps.deleteFileIfExists(paths.journal)
            } catch (rollbackError: Throwable) {
                log.error("backup.rollback_failed", rollbackError)
            }
            backup.discard()
            throw e
        }

        journal = settle(paths, journal, settle, log, step)
        try {
            finish(paths, journal.keep, log, step)
            if (settle == null || journal.settled) FileOps.deleteFileIfExists(paths.journal)
        } catch (kill: SimulatedProcessKill) {
            throw kill
        } catch (e: Throwable) {
            log.warn("backup.restore_finish_deferred", e)
        }
        try {
            backup.discard()
        } catch (e: Throwable) {
            log.warn("backup.restore_discard_failed", e)
        }
    }

    private fun recoverWithoutJournal(paths: RestorePaths, log: BackupLog): RestoreRecovery {
        var result = RestoreRecovery.Nothing
        if (paths.previous.isFile && !paths.dbFile.isFile) {
            FileOps.rename(paths.previous, paths.dbFile)
            for (sidecar in paths.sidecars) {
                val parked = paths.parked(sidecar)
                if (parked.isFile && !sidecar.isFile) FileOps.rename(parked, sidecar)
            }
            result = RestoreRecovery.RestoredWithoutJournal
        }
        if (paths.filesPrevious.isDirectory && !paths.filesDir.isDirectory) {
            FileOps.rename(paths.filesPrevious, paths.filesDir)
            result = RestoreRecovery.RestoredWithoutJournal
        }
        discardStaged(paths)
        if (result != RestoreRecovery.Nothing) {
            log.warn("backup.restore_recovered", fields = mapOf("phase" to "no-journal", "result" to result.wire))
        }
        return result
    }

    private fun discardStaged(paths: RestorePaths) {
        FileOps.deleteFileIfExists(paths.incoming)
        FileOps.deleteDirIfExists(paths.filesIncoming)
    }

    private fun rollBack(paths: RestorePaths, journal: RestoreJournal) {
        if (journal.phase == RestorePhase.SwappingFiles) {
            if (paths.filesPrevious.isDirectory) {
                FileOps.deleteDirIfExists(paths.filesDir)
                FileOps.rename(paths.filesPrevious, paths.filesDir)
            } else if (!journal.hadFiles && !paths.filesIncoming.isDirectory) {
                FileOps.deleteDirIfExists(paths.filesDir)
            }
        }

        if (paths.previous.isFile) {
            FileOps.deleteFileIfExists(paths.dbFile)
            for (sidecar in paths.sidecars) FileOps.deleteFileIfExists(sidecar)
            FileOps.rename(paths.previous, paths.dbFile)
        } else if (!journal.hadDb && !paths.incoming.isFile) {
            FileOps.deleteFileIfExists(paths.dbFile)
            for (sidecar in paths.sidecars) FileOps.deleteFileIfExists(sidecar)
        }
        for (sidecar in paths.sidecars) {
            val parked = paths.parked(sidecar)
            if (parked.isFile && !sidecar.isFile) FileOps.rename(parked, sidecar)
        }
        discardStaged(paths)
    }

    private fun settle(
        paths: RestorePaths,
        journal: RestoreJournal,
        settle: RestoreSettle?,
        log: BackupLog,
        step: ((String) -> Unit)?,
    ): RestoreJournal {
        if (settle == null || journal.settled) return journal
        try {
            settle.settle(paths.dbFile, journal.keep.map { it.entry })
        } catch (e: Throwable) {
            log.warn("backup.restore_settle_failed", e)
            return journal
        }
        step?.invoke("settled")
        val next = journal.settledNow()
        try {
            writeJournal(paths, next)
        } catch (e: Throwable) {
            log.warn("backup.restore_journal_write_failed", e)
        }
        return next
    }

    private fun finish(paths: RestorePaths, keep: List<RestoreKeep>, log: BackupLog, step: ((String) -> Unit)?) {
        keepListed(paths, keep, log, step)
        FileOps.deleteFileIfExists(paths.previous)
        step?.invoke("cleaning")
        for (sidecar in paths.sidecars) FileOps.deleteFileIfExists(paths.parked(sidecar))
        FileOps.deleteDirIfExists(paths.filesPrevious)
        discardStaged(paths)
    }

    private fun keepListed(paths: RestorePaths, keep: List<RestoreKeep>, log: BackupLog, step: ((String) -> Unit)?) {
        if (keep.isEmpty() || !paths.filesPrevious.isDirectory) return
        var kept = 0
        for (k in keep) {
            val parts = k.path.split('/')
            val from = parts.fold(paths.filesPrevious) { dir, part -> File(dir, part) }
            val to = parts.fold(paths.filesDir) { dir, part -> File(dir, part) }
            if (to.isFile || !from.isFile) continue
            if (from.length() != k.size) continue
            if (FileOps.sha256Hex(from) != k.sha256) continue
            to.parentFile?.let { FileOps.ensureDir(it) }
            FileOps.move(from, to)
            kept++
            step?.invoke("kept")
        }
        if (kept > 0) log.info("backup.restore_kept_files", mapOf("files" to kept))
    }

    private fun setAsideLeftovers(paths: RestorePaths, log: BackupLog, clock: () -> Instant) {
        val stamp = ChronoUnit.MICROS.between(Instant.EPOCH, clock())
        val candidates = listOf(paths.previous) + paths.sidecars.map { paths.parked(it) }
        for (file in candidates) {
            if (file.isFile) {
                FileOps.rename(file, File(file.path + ".kept-$stamp"))
                log.warn("backup.leftover_bak_kept")
            }
        }
        if (paths.filesPrevious.isDirectory) {
            FileOps.rename(paths.filesPrevious, File(paths.filesPrevious.path + ".kept-$stamp"))
            log.warn("backup.leftover_bak_kept")
        }
    }

    private fun writeJournal(paths: RestorePaths, journal: RestoreJournal) {
        FileOps.writeDurably(paths.journalTmp, journal.encode())
        FileOps.rename(paths.journalTmp, paths.journal)
    }
}
