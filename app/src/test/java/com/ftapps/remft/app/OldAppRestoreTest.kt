package com.ftapps.remft.app

import android.app.AlarmManager
import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.alarm.AndroidAlarmGateway
import com.ftapps.remft.data.backup.AndroidSqlPort
import com.ftapps.remft.data.backup.BackupPaths
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.DatabaseSchema
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.FilesMaintenance
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.search.SearchDao
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.di.BackupModule
import com.ftapps.remft.di.SearchModule
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.backup.oldapp.OldAppFixture
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.MutableClock
import com.ftapps.remft.ui.backup.backupErrorRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.inject.Provider

@RunWith(AndroidJUnit4::class)
class OldAppRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = Files.createTempDirectory("oldapp").toFile()
    private val dataDir = File(root, "data")
    private val clock = MutableClock(Instant.parse("2026-11-04T00:00:00Z"))
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
    private val notes = NotesRepository(holder, { identity.workspaceId() }, store, paths.filesDir, { "Untitled" }, { clock.instant() })
    private val bin = RecycleBin({ holder.db }, store, paths.filesDir, { identity.resolve().workspaceId }, clock)
    private val maintenance = FilesMaintenance(bin, AttachmentStore({ holder.db }, paths.filesDir, bin, clock))
    private val alarmEnv = AlarmEnvironment(
        context = context,
        database = { holder.db },
        identity = identity,
        settings = store,
        tasks = tasks,
        clock = clock,
        gateway = AndroidAlarmGateway(context),
        filesDir = dataDir,
        scope = scope,
        health = FakeHealth(),
    )
    private val alarms = AlarmStartup(alarmEnv)
    private val startup = AppStartup(
        databaseHolder = holder,
        identity = identity,
        restoreRecovery = StartupRestoreRecovery(paths, store, BackupLog.None) { clock.instant() },
        series = series,
        oneDrive = Provider { throw IllegalStateException("OneDrive is not part of this test") },
        filesMaintenance = maintenance,
        alarms = Provider { alarms },
    )
    private val backup = BackupModule.backupService(
        paths = paths,
        holder = holder,
        store = store,
        locks = WorkLocks(paths.locksDir, 4242L, log = BackupLog.None),
        mirror = Provider { throw IllegalStateException("no mirror") },
        restoredFiles = Provider { throw IllegalStateException("no OneDrive") },
    )

    @After
    fun tearDown() {
        runCatching { holder.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun awaitReady(): StartupState.Ready {
        waitFor("startup") { startup.state.value !is StartupState.Starting }
        val state = startup.state.value
        if (state is StartupState.Failed) throw AssertionError("startup failed", state.error)
        return state as StartupState.Ready
    }

    private fun bookedTimes(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { it.triggerAtMs }.sorted()

    private suspend fun plannedTimes(): List<Long> = alarmEnv.planner.planNotifications(
        alarms = alarmEnv.workspaceFeed(),
        now = clock.instant(),
        privacyMode = store.read(SettingKeys.NotificationPrivacy.name) == "true",
        profile = ReminderProfile.parse(store.read(SettingKeys.ReminderProfile.name)),
    ).map { it.fireAt.toEpochMilli() }.sorted()

    @Test
    fun anOldAppBackupOpensWithRoomAndShowsItsTasksAndNotes() = runBlocking {
        alarms.start(observeProcess = false)
        startup.start()
        val fresh = awaitReady().identity.workspaceId

        val zip = OldAppFixture.buildZip(File(root, "in"), AndroidSqlPort.opener).zip
        val validated = backup.validate(zip.inputStream())
        assertNotNull(validated.oldApp)
        val oldApp = validated.oldApp!!
        assertEquals(OldAppFixture.APP_VERSION, oldApp.appVersion)
        assertEquals(3, oldApp.counts.tasks)
        assertEquals(2, oldApp.counts.notes)
        assertTrue(holder.file.exists())

        backup.restore(validated)
        startup.restartAfterRestore()
        val ready = awaitReady()

        assertEquals(OldAppFixture.WS_A, ready.identity.workspaceId)
        assertTrue(fresh != OldAppFixture.WS_A)
        assertEquals(DatabaseSchema.VERSION, holder.db.openHelper.readableDatabase.version)

        val shown = tasks.watchHome().first().map { it.id } + tasks.watchInbox().first().map { it.id }
        assertTrue(shown.toString(), "T1" in shown)
        assertTrue(shown.toString(), "T3" in shown)
        val t1 = tasks.detail("T1")!!
        assertEquals("Buy İstanbul MILK", t1.task.title)
        assertEquals(Instant.parse("2026-11-05T10:15:00Z"), t1.task.remindAt)
        assertEquals(2, t1.checklist.size)
        val listed = notes.observeList().first()
        assertTrue(listed.any { it.id == "N1" && it.title == "Plan" && it.isPinned })
        val search = SearchModule.searchRepository(SearchDao { holder.db }, identity)
        assertTrue(search.global("istanbul").isNotEmpty())
        assertTrue(search.global("omer").isNotEmpty())

        for ((name, bytes) in OldAppFixture.attachments) assertTrue(name, File(paths.filesDir, name).readBytes().contentEquals(bytes))
        assertEquals("bundled:alarm", store.read(SettingKeys.AlarmSound.name))
        assertEquals("file:F4", store.read(SettingKeys.ReminderSound.name))
        assertEquals("{\"slots\":[0,30,60],\"repeatAfterSnooze\":false}", store.read(SettingKeys.ReminderProfile.name))
        assertEquals(null, store.read(SettingKeys.BinDays.name))
        assertEquals("true", store.read(SettingKeys.MirrorDownloadsPending.name))

        assertTrue(plannedTimes().contains(Instant.parse("2026-11-05T10:45:00Z").toEpochMilli()))
        waitFor("alarms re-booked for the imported tasks") {
            val booked = bookedTimes()
            booked.isNotEmpty() && booked == runBlocking { plannedTimes() }
        }
        waitFor("the custom sound is installed from its imported file") { File(dataDir, "Sounds/F4.m4a").exists() }
    }

    @Test
    fun oldAppRefusalsNeverSayTheBackupIsNewer() {
        val newer = context.getString(backupErrorRes(BackupErrorCode.NEWER_VERSION)!!)
        for (code in listOf(BackupErrorCode.OLD_APP_SCHEMA, BackupErrorCode.OLD_APP_UNSUPPORTED)) {
            val text = context.getString(backupErrorRes(code)!!)
            assertTrue(text, text.contains("old RemFT app"))
            assertFalse(text, text == newer || text.contains("newer"))
        }
        val zip = OldAppFixture.buildZip(File(root, "old-schema"), AndroidSqlPort.opener, format = 2, schemaVersion = 2, withMirrored = false).zip
        val refused = runCatching { runBlocking { backup.validate(zip.inputStream()) } }.exceptionOrNull() as BackupException
        assertEquals(BackupErrorCode.OLD_APP_SCHEMA, refused.code)
    }
}
