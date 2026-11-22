package com.ftapps.remft.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.backup.restore.BackupRestore
import com.ftapps.remft.domain.backup.restore.RESTORE_JOURNAL_NAME
import com.ftapps.remft.domain.backup.restore.RestoreRecovery
import com.ftapps.remft.domain.backup.restore.SimulatedProcessKill
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupRestoreRecoveryTest {
    private val rig = BackupRig()

    @After
    fun tearDown() = rig.close()

    private var filesBeforeCrash: Map<String, List<Byte>> = emptyMap()

    private fun crashDuringRestore(atStep: String): Map<String, List<String>> = runBlocking {
        rig.seed()
        val built = rig.service().build()
        rig.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title) VALUES ('01JC00000000000000000000T9', '${BackupRig.WORKSPACE}', 'Only on the phone')",
        )
        val before = rig.dump()
        filesBeforeCrash = rig.filesSnapshot()
        rig.holder.close()
        val backup = rig.service().validate(built.zip.inputStream())
        try {
            BackupRestore.restore(
                backup,
                rig.paths.dbFile,
                rig.paths.filesDir,
                verify = BackupDatabases::verify,
                onStep = { if (it == atStep) throw SimulatedProcessKill(it) },
            )
            fail("the simulated kill did not happen")
        } catch (_: SimulatedProcessKill) {
        }
        assertTrue(File(rig.paths.dbFile.parentFile, RESTORE_JOURNAL_NAME).exists())
        before
    }

    @Test
    fun aRestoreKilledMidSwapIsRolledBackAtStartAndTheUserIsToldOnce() = runBlocking {
        val before = crashDuringRestore("files-parked")
        val recovery = StartupRestoreRecovery(rig.paths, rig.store, clock = { rig.now })

        assertEquals(RestoreRecovery.RolledBack, recovery.run())

        assertEquals(before, rig.dump())
        assertEquals(filesBeforeCrash, rig.filesSnapshot())
        assertFalse(File(rig.paths.dbFile.parentFile, RESTORE_JOURNAL_NAME).exists())
        assertFalse(File(rig.paths.dbFile.path + ".bak").exists())
        assertEquals("2026-11-02T10:00:00.000Z", rig.store.read(SettingKeys.RestoreRolledBackAt.name))
        assertTrue(recovery.takeRolledBackNotice())
        assertFalse(recovery.takeRolledBackNotice())
    }

    @Test
    fun aRestoreKilledBeforeTheDbSwapIsRolledBack() = runBlocking {
        val before = crashDuringRestore("db-parked")
        assertEquals(RestoreRecovery.RolledBack, StartupRestoreRecovery(rig.paths, rig.store).run())
        assertEquals(before, rig.dump())
    }

    @Test
    fun aRestoreKilledAfterTheSwapIsFinishedAndSettled() = runBlocking {
        crashDuringRestore("done")
        rig.store.set(SettingKeys.MirrorReviewSweptAt.name, "2026-11-01T00:00:00.000Z")
        rig.store.set(BackupSettings.pendingKey, BackupSettings.encode(mapOf(SettingKeys.DateFormat.name to "iso")))

        assertEquals(RestoreRecovery.Finished, StartupRestoreRecovery(rig.paths, rig.store).run())

        assertEquals(2, rig.dump().getValue("tasks").size)
        assertEquals(BackupRig.WORKSPACE, rig.store.read(SettingKeys.LocalWorkspaceId.name))
        assertNull(rig.store.read(SettingKeys.MirrorReviewSweptAt.name))
        assertEquals("iso", rig.store.read(SettingKeys.DateFormat.name))
        assertNull(rig.store.read(BackupSettings.pendingKey))
        assertNull(rig.store.read(SettingKeys.RestoreRolledBackAt.name))
        assertFalse(File(rig.paths.dbFile.parentFile, RESTORE_JOURNAL_NAME).exists())
    }

    @Test
    fun nothingToRecoverSweepsScratchAndShareLeftovers() = runBlocking {
        rig.seed()
        File(rig.paths.scratch, "restore-123/remft.sqlite").apply { parentFile?.mkdirs(); writeText("x") }
        File(rig.paths.shareDir, "remft-backup-2026-11-01.zip").apply { parentFile?.mkdirs(); writeText("x") }

        assertEquals(RestoreRecovery.Nothing, StartupRestoreRecovery(rig.paths, rig.store).run())

        assertTrue(rig.paths.scratch.listFiles().orEmpty().isEmpty())
        assertFalse(rig.paths.shareDir.exists())
        assertFalse(StartupRestoreRecovery(rig.paths, rig.store).takeRolledBackNotice())
    }

    @Test
    fun pendingSettingsWaitWhileFilesAreStillDownloading() = runBlocking {
        rig.seed()
        rig.store.setAll(
            mapOf(
                SettingKeys.MirrorDownloadsPending.name to "true",
                BackupSettings.pendingKey to BackupSettings.encode(mapOf(SettingKeys.DateFormat.name to "iso")),
            ),
        )
        StartupRestoreRecovery(rig.paths, rig.store).run()
        assertNull(rig.store.read(SettingKeys.DateFormat.name))
        assertTrue(rig.store.read(BackupSettings.pendingKey) != null)
    }
}
