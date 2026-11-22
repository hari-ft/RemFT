package com.ftapps.remft.domain.backup.restore

import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import java.io.File

object ScratchSweep {
    const val BACKUP_SCRATCH_FOLDER = "backup-scratch"
    const val AUTO_BACKUP_SCRATCH_FOLDER = "auto-backup"

    fun backupScratch(tmp: File, log: BackupLog = BackupLog.None): Int {
        var swept = 0
        val scratch = File(tmp, BACKUP_SCRATCH_FOLDER)
        if (scratch.isDirectory) {
            for (entry in scratch.listFiles().orEmpty()) {
                try {
                    FileOps.deleteTree(entry)
                    swept++
                } catch (e: Exception) {
                    log.warn("backup.scratch_sweep_failed", e)
                }
            }
        }
        if (tmp.isDirectory) {
            for (entry in tmp.listFiles().orEmpty()) {
                if (!entry.isFile || !BackupNames.manualNamePattern.matches(entry.name)) continue
                try {
                    FileOps.deleteFile(entry)
                    swept++
                } catch (e: Exception) {
                    log.warn("backup.scratch_sweep_failed", e)
                }
            }
        }
        if (swept > 0) log.info("backup.scratch_swept", mapOf("entries" to swept))
        return swept
    }

    fun autoBackupScratch(tmp: File, locks: WorkLocks, log: BackupLog = BackupLog.None): Int =
        locks.withLock(WorkLockNames.ONEDRIVE_WORK) {
            val dir = File(tmp, AUTO_BACKUP_SCRATCH_FOLDER)
            if (!dir.isDirectory) return@withLock 0
            var swept = 0
            for (entry in dir.listFiles().orEmpty()) {
                val ours = (entry.isFile && BackupNames.autoLeftoverPattern.matches(entry.name)) ||
                    (entry.isDirectory && entry.name == "scratch")
                if (!ours) continue
                try {
                    FileOps.deleteTree(entry)
                    swept++
                } catch (e: Exception) {
                    log.warn("backup.scratch_sweep_failed", e)
                }
            }
            if (swept > 0) log.info("autobackup.scratch_swept", mapOf("entries" to swept))
            swept
        } ?: 0
}
