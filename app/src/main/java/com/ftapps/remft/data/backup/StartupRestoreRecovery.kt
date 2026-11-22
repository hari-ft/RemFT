package com.ftapps.remft.data.backup

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.restore.BackupRestore
import com.ftapps.remft.domain.backup.restore.RestoreRecovery
import com.ftapps.remft.domain.backup.restore.ScratchSweep
import com.ftapps.remft.domain.settings.SettingKeys
import java.io.IOException
import java.time.Instant

class RestoreRecoveryFailed(cause: Throwable) : IOException("An interrupted restore could not be finished", cause)

class StartupRestoreRecovery(
    private val paths: BackupPaths,
    private val store: SettingsStore,
    private val log: BackupLog = AndroidBackupLog,
    private val clock: () -> Instant = Instant::now,
) {
    private val settler = RestoredDataSettler(store, log, clock)

    suspend fun run(): RestoreRecovery? {
        val result = try {
            BackupRestore.recoverInterruptedRestore(paths.dbFile, paths.filesDir, settler, log)
        } catch (e: Exception) {
            log.error("backup.restore_recovery_failed", e)
            if (BackupRestore.recoveryPending(paths.dbFile, paths.filesDir)) throw RestoreRecoveryFailed(e)
            null
        }
        try {
            ScratchSweep.backupScratch(paths.cacheDir, log)
            FileOps.deleteDirIfExists(paths.shareDir)
        } catch (e: Exception) {
            log.warn("backup.scratch_sweep_failed", e)
        }
        try {
            if (store.get(SettingKeys.MirrorDownloadsPending.name) != "true") RestoreSettings.applyPending(store)
        } catch (e: Exception) {
            log.warn("backup.settings_apply_failed", e)
        }
        if (result?.tellUser == true) {
            log.warn("backup.restore_rolled_back", fields = mapOf("result" to result.wire))
            store.set(SettingKeys.RestoreRolledBackAt.name, UtcText.format(clock()))
        }
        return result
    }

    suspend fun takeRolledBackNotice(): Boolean {
        val at = store.get(SettingKeys.RestoreRolledBackAt.name) ?: return false
        store.remove(SettingKeys.RestoreRolledBackAt.name)
        return at.isNotEmpty()
    }
}
