package com.ftapps.remft.app

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.PassOutcome
import com.ftapps.remft.data.backup.BackupPaths
import com.ftapps.remft.data.backup.RestoreRecoveryFailed
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.DatabaseRecoveryPending
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.FilesMaintenance
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.di.AlarmModule
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.restore.RestoreJournal
import com.ftapps.remft.domain.backup.restore.RestorePaths
import com.ftapps.remft.domain.backup.restore.RestorePhase
import com.ftapps.remft.testing.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.inject.Provider

@RunWith(AndroidJUnit4::class)
class StartupSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = Files.createTempDirectory("startup").toFile()
    private val dataDir = File(root, "data")
    private val clock = MutableClock(Instant.parse("2026-11-20T04:30:00Z"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") })
    private val holder = DatabaseHolder(context, File(dataDir, "remft.sqlite"))
    private val paths = BackupPaths(
        dbFile = holder.file,
        filesDir = File(dataDir, BackupPaths.FILES_FOLDER),
        cacheDir = File(root, "cache"),
        locksDir = File(dataDir, BackupPaths.LOCKS_FOLDER),
    )
    private val identity = LocalIdentityRepository({ holder.db.identityDao() }, store)
    private val series = SeriesRepository({ holder.db }, clock, scope)
    private val tasks = TasksRepository({ holder.db }, identity, store, clock, paths.filesDir, series)
    private val bin = RecycleBin({ holder.db }, store, paths.filesDir, identity::workspaceId, clock)
    private val maintenance = FilesMaintenance(bin, AttachmentStore({ holder.db }, paths.filesDir, bin, clock))
    private val startup: AppStartup = AppStartup(
        databaseHolder = holder,
        identity = identity,
        restoreRecovery = StartupRestoreRecovery(paths, store, BackupLog.None) { clock.instant() },
        series = series,
        oneDrive = Provider { throw IllegalStateException("OneDrive is not part of this test") },
        filesMaintenance = maintenance,
        alarms = Provider { throw IllegalStateException("alarms are not part of this test") },
    )

    @After
    fun tearDown() {
        runCatching { holder.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun settle(): StartupState {
        val deadline = System.currentTimeMillis() + 15_000
        while (startup.state.value is StartupState.Starting) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for startup")
            Thread.sleep(20)
        }
        return startup.state.value
    }

    private fun writeJournal(phase: RestorePhase) {
        val journal = RestorePaths(paths.dbFile, paths.filesDir).journal
        journal.parentFile?.mkdirs()
        journal.writeText(RestoreJournal(phase, hadDb = true, hadFiles = true).encode())
    }

    private fun strandRecoveryMidDatabaseSwap() {
        writeJournal(RestorePhase.SwappingDb)
        RestorePaths(paths.dbFile, paths.filesDir).previous.writeText("parked database")
        File(paths.dbFile, "blocker").apply { parentFile?.mkdirs(); writeText("x") }
    }

    @Test
    fun theDatabaseStaysClosedWhileAnUnfinishedRestoreIsPending() {
        writeJournal(RestorePhase.SwappingDb)
        try {
            holder.db
            fail("the database opened with a restore journal pending")
        } catch (_: DatabaseRecoveryPending) {
        }
        assertFalse(paths.dbFile.exists())
    }

    @Test
    fun aFinishedRestoreWhoseFollowUpIsStillPendingDoesNotBlockTheDatabase() {
        writeJournal(RestorePhase.Done)
        holder.db.openHelper.writableDatabase
        assertTrue(paths.dbFile.exists())
    }

    @Test
    fun anAlarmPassAfterAFailedStartupLeavesTheDatabaseUntouched() = runBlocking {
        strandRecoveryMidDatabaseSwap()
        startup.start()
        val failed = settle()
        assertTrue(failed is StartupState.Failed)
        assertTrue((failed as StartupState.Failed).error is RestoreRecoveryFailed)

        val env = AlarmModule.alarmEnvironment(context, holder, identity, store, tasks, Provider { startup })
        val outcome = env.scheduler.pass(reason = "test")

        assertTrue(outcome is PassOutcome.Skipped)
        assertFalse(holder.isOpen)
        assertTrue(paths.dbFile.isDirectory)
        assertFalse(File(paths.dbFile.path + "-wal").exists())
        assertFalse(File(paths.dbFile.path + "-journal").exists())
        assertTrue(RestorePaths(paths.dbFile, paths.filesDir).journal.exists())
        assertTrue(RestorePaths(paths.dbFile, paths.filesDir).previous.isFile)
    }

    @Test
    fun retryReopensTheDatabaseInsteadOfKeepingTheOldHandle() {
        startup.start()
        assertTrue(settle() is StartupState.Ready)
        val before = holder.db
        startup.retry()
        assertTrue(settle() is StartupState.Ready)
        assertNotSame(before, holder.db)
        holder.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM tasks").use { assertTrue(it.moveToFirst()) }
    }
}
