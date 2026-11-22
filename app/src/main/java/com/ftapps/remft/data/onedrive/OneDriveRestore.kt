package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.backup.RestoredFilesFetcher
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.FileMirrorSql
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

interface CloudBackups {
    suspend fun connected(): Boolean
    suspend fun list(): List<StoredBackup>
    suspend fun download(backup: StoredBackup, to: File, onProgress: (Long) -> Unit): Boolean
}

class OneDriveBackups(
    private val graph: GraphClient,
    private val auth: OneDriveAuth,
    private val store: SettingsStore,
) : CloudBackups {
    override suspend fun connected(): Boolean =
        store.get(SettingKeys.OneDriveOn.name) == OneDriveAuth.TRUE && auth.isConnected()

    override suspend fun list(): List<StoredBackup> = sorted(graph.list())

    override suspend fun download(backup: StoredBackup, to: File, onProgress: (Long) -> Unit): Boolean =
        graph.download(backup.id, null, backup.size, to, onProgress)

    companion object {
        fun sorted(all: Iterable<StoredBackup>, zone: ZoneId = ZoneId.systemDefault()): List<StoredBackup> {
            val zips = all.filter { it.name.lowercase().endsWith(".zip") }
            return zips.sortedWith { a, b ->
                val at: Instant? = BackupNames.parseAutomatic(a.name, zone)
                val bt: Instant? = BackupNames.parseAutomatic(b.name, zone)
                when {
                    at != null && bt != null -> bt.compareTo(at)
                    at != null -> -1
                    bt != null -> 1
                    else -> b.name.compareTo(a.name)
                }
            }
        }
    }
}

class OneDriveRestoredFiles(
    private val graph: GraphClient,
    private val auth: OneDriveAuth,
    private val store: SettingsStore,
    private val filesDir: File,
    private val log: BackupLog = AndroidBackupLog,
) : RestoredFilesFetcher {
    override suspend fun fetch(dbFile: File, onProgress: (done: Int, total: Int) -> Unit) {
        if (store.get(SettingKeys.OneDriveOn.name) != OneDriveAuth.TRUE || !auth.canWork()) return
        val sql = withContext(Dispatchers.IO) { FileMirrorSql(dbFile) }
        try {
            val mirror = FileMirror(sql, graph, filesDir, store, log = log)
            val pending = mirror.pendingDownloads()
            if (pending.isEmpty()) {
                store.remove(SettingKeys.MirrorDownloadsPending.name)
                return
            }
            var done = 0
            var failed = 0
            onProgress(0, pending.size)
            for (d in pending) {
                try {
                    mirror.download(d)
                    done++
                    onProgress(done, pending.size)
                } catch (e: OneDriveException) {
                    failed++
                    if (e.code == OneDriveErrors.OFFLINE || e.code in OneDriveErrors.reconnectCodes) break
                } catch (e: Exception) {
                    failed++
                    log.warn("backup.restore_file_failed", e)
                }
            }
            if (failed == 0 && done == pending.size) store.remove(SettingKeys.MirrorDownloadsPending.name)
            log.info("backup.restore_files", mapOf("done" to done, "failed" to failed, "total" to pending.size))
        } finally {
            withContext(Dispatchers.IO) { sql.close() }
        }
    }
}

object RestoreDownloads {
    fun tempZip(cacheDir: File, at: Instant): File {
        val dir = File(cacheDir, "backup-scratch")
        FileOps.ensureDir(dir)
        return File(dir, "restore-onedrive-${at.toEpochMilli()}.zip")
    }
}
