package com.ftapps.remft.data.backup

import com.ftapps.remft.domain.backup.restore.ScratchSweep
import java.io.File

data class BackupPaths(
    val dbFile: File,
    val filesDir: File,
    val cacheDir: File,
    val locksDir: File,
) {
    val scratch: File get() = File(cacheDir, ScratchSweep.BACKUP_SCRATCH_FOLDER)
    val shareDir: File get() = File(cacheDir, SHARE_FOLDER)

    companion object {
        const val SHARE_FOLDER = "backup-share"
        const val FILES_FOLDER = "files"
        const val LOCKS_FOLDER = "locks"
    }
}
