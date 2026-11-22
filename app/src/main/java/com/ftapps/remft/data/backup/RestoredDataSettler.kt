package com.ftapps.remft.data.backup

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.restore.RestoreSettle
import com.ftapps.remft.domain.identity.WorkspaceIdentity
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant

class RestoredDataSettler(
    private val store: SettingsStore,
    private val log: BackupLog = AndroidBackupLog,
    private val clock: () -> Instant = Instant::now,
) : RestoreSettle {
    override fun settle(dbFile: File, listed: List<BackupFileEntry>) = runBlocking {
        try {
            val owner = WorkspaceIdentity.dominant(BackupDatabases.workspaceCounts(dbFile))
            if (owner != null) store.set(SettingKeys.LocalWorkspaceId.name, owner)
        } catch (e: Exception) {
            log.warn("backup.identity_adopt_failed", e)
        }
        if (listed.isNotEmpty()) {
            store.set(SettingKeys.MirrorDownloadsPending.name, "true")
            try {
                val refs = listed.mapNotNull { entry -> entry.mirror?.let { entry.path to it } }
                BackupDatabases.recordMirrorRefs(dbFile, refs, clock())
            } catch (e: Exception) {
                log.warn("backup.mirror_refs_restore_failed", e)
            }
        }
        store.remove(SettingKeys.MirrorReviewSweptAt.name)
        Unit
    }
}
