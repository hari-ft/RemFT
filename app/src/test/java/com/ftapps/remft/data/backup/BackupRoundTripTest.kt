package com.ftapps.remft.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.db.DatabaseSchema
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupKind
import com.ftapps.remft.domain.backup.BackupManifest
import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.backup.BackupZipWriter
import com.ftapps.remft.domain.backup.SqliteHeader
import com.ftapps.remft.domain.backup.lock.BackgroundWorkBusy
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.backup.restore.RESTORE_JOURNAL_NAME
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val rig = BackupRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun backUpThenRestoreBringsEverythingBack() = runBlocking {
        rig.seed()
        rig.store.setAll(
            mapOf(
                SettingKeys.DateFormat.name to "iso",
                SettingKeys.BinDays.name to "60",
                SettingKeys.LocalWorkspaceId.name to "01JC0000000000000000000OTHER",
            ),
        )
        val before = rig.dump()
        val filesBefore = rig.filesSnapshot()
        val service = rig.service()

        val built = service.build()
        assertEquals("remft-backup-2026-11-02.zip", built.zip.name)
        assertEquals(File(rig.paths.cacheDir, "backup-share"), built.zip.parentFile)
        assertEquals(DatabaseSchema.VERSION, built.manifest.schemaVersion)
        assertEquals("1.1.1", built.manifest.appVersion)
        assertEquals(BackupKind.COMPLETE, built.manifest.kind)
        assertEquals(3, built.manifest.fileCount)
        assertTrue(built.unavailable.isEmpty())
        ZipFile(built.zip).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf(BackupFormat.MANIFEST_NAME, BackupFormat.SETTINGS_NAME, BackupFormat.DB_NAME), names.takeLast(3))
            val settings = zip.getInputStream(zip.getEntry(BackupFormat.SETTINGS_NAME)).readBytes().toString(Charsets.UTF_8)
            assertEquals(mapOf(SettingKeys.DateFormat.name to "iso", SettingKeys.BinDays.name to "60"), BackupSettings.parse(settings))
        }
        assertTrue(rig.paths.scratch.listFiles().orEmpty().isEmpty())

        rig.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title) VALUES ('01JC00000000000000000000T9', '${BackupRig.WORKSPACE}', 'Made after the backup')",
        )
        File(rig.paths.filesDir, "01JC0000000000000000000001.png").delete()
        rig.writeFile("01JC0000000000000000000009.txt", "later".toByteArray())
        rig.store.setAll(
            mapOf(
                SettingKeys.DateFormat.name to "dmy_dot",
                SettingKeys.NotesViewMode.name to "grid",
                SettingKeys.MirrorReviewSweptAt.name to "2026-11-01T00:00:00.000Z",
            ),
        )
        assertNotEquals(before, rig.dump())

        val backup = service.validate(built.zip.inputStream())
        val stages = ArrayList<RestoreStage>()
        service.restore(backup) { stages.add(it) }
        assertEquals(listOf(RestoreStage.Tasks, RestoreStage.Settings), stages)

        assertTrue(rig.holder.isRestoring)
        rig.holder.reopenAfterRestore()
        assertEquals(before, rig.dump())
        assertEquals(DatabaseSchema.VERSION, rig.holder.db.openHelper.readableDatabase.version)
        assertEquals(filesBefore, rig.filesSnapshot())
        assertEquals("iso", rig.store.read(SettingKeys.DateFormat.name))
        assertEquals("60", rig.store.read(SettingKeys.BinDays.name))
        assertNull(rig.store.read(SettingKeys.NotesViewMode.name))
        assertNull(rig.store.read(SettingKeys.MirrorReviewSweptAt.name))
        assertNull(rig.store.read(SettingKeys.RestorePendingSettings.name))
        assertEquals(BackupRig.WORKSPACE, rig.store.read(SettingKeys.LocalWorkspaceId.name))
        val dataDir = rig.paths.dbFile.parentFile!!
        assertFalse(File(dataDir, RESTORE_JOURNAL_NAME).exists())
        assertFalse(File(rig.paths.dbFile.path + ".bak").exists())
        assertFalse(File(rig.paths.filesDir.path + ".bak").exists())
        assertFalse(File(rig.paths.locksDir, "restore.lock").exists())
        assertTrue(rig.paths.scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun snapshotWithoutVacuumIntoMatchesTheLiveData() = runBlocking {
        rig.seed()
        rig.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO notes (id, workspace_id, title, title_fold, body_fold, created_at, updated_at) VALUES ('01JC00000000000000000000N2', '${BackupRig.WORKSPACE}', 'Fresh', 'fresh', '', '2026-11-02T08:00:00.000Z', '2026-11-02T08:00:00.000Z')",
        )
        val before = rig.dump()
        val built = rig.service(preferVacuumInto = false).build()
        val backup = rig.service().validate(built.zip.inputStream())
        try {
            assertEquals(DatabaseSchema.VERSION.toLong(), SqliteHeader.userVersionOf(backup.dbFile))
            val notes = BackupDatabases.withDatabase(backup.dbFile) { db ->
                db.rawQuery("SELECT count(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) }
            }
            assertEquals(before.getValue("notes").size, notes)
        } finally {
            backup.discard()
        }
    }

    @Test
    fun aBackupWithoutSettingsLeavesSettingsAlone() = runBlocking {
        rig.seed()
        rig.store.setAll(mapOf(SettingKeys.DateFormat.name to "iso", SettingKeys.NotesViewMode.name to "grid"))
        rig.holder.db.openHelper.writableDatabase
        val snapshot = File(rig.root, "snapshot.sqlite")
        BackupDatabases.snapshot(rig.holder.db.openHelper.writableDatabase, rig.holder.file, snapshot)
        val zip = BackupZipWriter.build(
            dbSnapshot = snapshot,
            out = File(rig.root, "no-settings.zip"),
            appVersion = "1.1.1",
            schemaVersion = DatabaseSchema.VERSION,
            referenced = emptySet(),
            filesDir = rig.paths.filesDir,
            settings = null,
        ).zip
        val service = rig.service()
        service.restore(service.validate(zip.inputStream()))
        rig.holder.reopenAfterRestore()
        assertEquals("iso", rig.store.read(SettingKeys.DateFormat.name))
        assertEquals("grid", rig.store.read(SettingKeys.NotesViewMode.name))
    }

    @Test
    fun aBusyBackgroundTurnLeavesTheDataUntouched() = runBlocking {
        rig.seed()
        val before = rig.dump()
        val built = rig.service().build()
        val locks = WorkLocks(rig.paths.locksDir, 4242L)
        val turn = locks.tryAcquire(WorkLockNames.HEADLESS_TURN)!!
        val service = BackupService(
            paths = rig.paths,
            holder = rig.holder,
            store = rig.store,
            locks = { locks },
            appVersion = "1.1.1",
            restoreLockWait = java.time.Duration.ofMillis(300),
        )
        try {
            service.restore(service.validate(built.zip.inputStream()))
            fail("restore ran while the background turn held the database")
        } catch (_: BackgroundWorkBusy) {
        } finally {
            turn.release()
        }
        assertFalse(rig.holder.isRestoring)
        assertEquals(before, rig.dump())
        assertTrue(rig.paths.scratch.listFiles().orEmpty().none { it.name.startsWith("restore-") })
    }

    @Test
    fun aRunningOneDriveJobLeavesTheDataUntouched() = runBlocking {
        rig.seed()
        val before = rig.dump()
        val built = rig.service().build()
        val locks = WorkLocks(rig.paths.locksDir, 4242L)
        val work = locks.tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val service = BackupService(
            paths = rig.paths,
            holder = rig.holder,
            store = rig.store,
            locks = { locks },
            appVersion = "1.1.1",
            restoreLockWait = java.time.Duration.ofMillis(300),
        )
        try {
            service.restore(service.validate(built.zip.inputStream()))
            fail("restore ran while a OneDrive job held the database")
        } catch (_: BackgroundWorkBusy) {
        } finally {
            work.release()
        }
        assertFalse(rig.holder.isRestoring)
        assertFalse(locks.isHeld(WorkLockNames.RESTORE))
        assertEquals(before, rig.dump())
    }

    @Test
    fun aDatabaseThatDoesNotCloseInTimeStopsTheRestoreBeforeTheSwap() = runBlocking {
        rig.seed()
        val built = rig.service().build()
        rig.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title) VALUES ('01JC00000000000000000000T9', '${BackupRig.WORKSPACE}', 'Only here')",
        )
        val before = rig.dump()
        val release = java.util.concurrent.CountDownLatch(1)
        val stuck = ArrayList<com.ftapps.remft.data.db.RemftDatabase>()
        val locks = WorkLocks(rig.paths.locksDir, 4242L)
        val service = BackupService(
            paths = rig.paths,
            holder = rig.holder,
            store = rig.store,
            locks = { locks },
            appVersion = "1.1.1",
            closeWait = java.time.Duration.ofMillis(200),
            closeDb = { db ->
                synchronized(stuck) { stuck.add(db) }
                release.await()
                db.close()
            },
        )
        val backup = service.validate(built.zip.inputStream())
        try {
            service.restore(backup)
            fail("the swap ran while the database was still open")
        } catch (_: RestoreDatabaseInUse) {
        } finally {
            release.countDown()
        }
        assertEquals(1, stuck.size)
        assertFalse(File(rig.paths.dbFile.parentFile, RESTORE_JOURNAL_NAME).exists())
        assertFalse(locks.isHeld(WorkLockNames.RESTORE))
        assertFalse(locks.isHeld(WorkLockNames.ONEDRIVE_WORK))
        rig.holder.reopenAfterRestore()
        assertEquals(before, rig.dump())
    }

    @Test
    fun aFailedVerifyRollsBackToTheOriginal() = runBlocking {
        rig.seed()
        val built = rig.service().build()
        rig.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title) VALUES ('01JC00000000000000000000T9', '${BackupRig.WORKSPACE}', 'Only here')",
        )
        val before = rig.dump()
        val service = rig.service()
        val backup = service.validate(built.zip.inputStream())
        BackupDatabases.withDatabase(backup.dbFile) { it.version = 4 }
        try {
            service.restore(backup)
            fail("a database with the wrong version was swapped in")
        } catch (_: StagedDatabaseInvalid) {
        }
        rig.holder.reopenAfterRestore()
        assertEquals(before, rig.dump())
        assertFalse(File(rig.paths.dbFile.parentFile, RESTORE_JOURNAL_NAME).exists())
        val manifest = ZipFile(built.zip).use { z ->
            BackupManifest.parse(z.getInputStream(z.getEntry(BackupFormat.MANIFEST_NAME)).readBytes().toString(Charsets.UTF_8))
        }
        assertEquals(3, manifest.fileCount)
    }
}
