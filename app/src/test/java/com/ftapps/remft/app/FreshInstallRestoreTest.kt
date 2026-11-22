package com.ftapps.remft.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.alarm.AndroidAlarmGateway
import com.ftapps.remft.data.backup.BackupPaths
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.DatabaseSchema
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.FilesMaintenance
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.onedrive.AutoBackupHealth
import com.ftapps.remft.data.onedrive.FakeCipher
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.onedrive.OneDriveTokenStore
import com.ftapps.remft.data.onedrive.PrefsTokenSlot
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.search.SearchDao
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.di.BackupModule
import com.ftapps.remft.di.SearchModule
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupReminder
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.HandBuiltZips
import com.ftapps.remft.testing.MutableClock
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Provider

@RunWith(AndroidJUnit4::class)
class FreshInstallRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = Files.createTempDirectory("fresh").toFile()
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

    private fun awaitReady(): StartupState.Ready = runBlocking {
        waitFor("startup") { startup.state.value !is StartupState.Starting }
        val state = startup.state.value
        if (state is StartupState.Failed) throw AssertionError("startup failed", state.error)
        state as StartupState.Ready
    }

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    private fun bookedTimes(): List<Long> = shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtMs }.sorted()

    private suspend fun plannedTimes(): List<Long> = alarmEnv.planner.planNotifications(
        alarms = alarmEnv.workspaceFeed(),
        now = clock.instant(),
        privacyMode = false,
    ).map { it.fireAt.toEpochMilli() }.sorted()

    private fun dump(db: SQLiteDatabase): Map<String, Map<String, Map<String, String?>>> {
        val out = LinkedHashMap<String, Map<String, Map<String, String?>>>()
        for (table in TABLES) {
            val rows = LinkedHashMap<String, Map<String, String?>>()
            db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = (0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.getString(it) }
                    rows[keyOf(table, row)] = row
                }
            }
            out[table] = rows
        }
        return out
    }

    private fun dumpLive(): Map<String, Map<String, Map<String, String?>>> {
        val file = File(root, "live-copy.sqlite")
        file.delete()
        holder.db.openHelper.writableDatabase.execSQL("VACUUM INTO '${file.path.replace("'", "''")}'")
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            dump(db)
        } finally {
            db.close()
        }
    }

    private fun keyOf(table: String, row: Map<String, String?>): String = when (table) {
        "series_skips" -> "${row["series_id"]}|${row["occurrence_date"]}"
        "file_mirrors" -> row.getValue("local_path")!!
        else -> row.getValue("id")!!
    }

    private fun sourceDatabase(): File {
        val file = File(root, "source/remft.sqlite")
        TestDatabases.create(file) { db -> SOURCE_ROWS.forEach(db::execSQL) }
        return file
    }

    private fun settingsJson(): String = JsonObject(RESTORED_SETTINGS.mapValues { JsonPrimitive(it.value) }).toString()

    private fun sourceZip(createdAt: String = "2026-11-19T21:30:00.123Z"): File =
        HandBuiltZips.write(File(root, "remft-backup-2026-11-20.zip"), sourceDatabase(), SOURCE_FILES, settingsJson(), createdAt = createdAt)

    private fun readSource(): Map<String, Map<String, Map<String, String?>>> {
        val db = SQLiteDatabase.openDatabase(File(root, "source/remft.sqlite").path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            dump(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun aFreshInstallStartsEmptyAndBooksNothing() = runBlocking {
        alarms.start(observeProcess = false)
        startup.start()
        val ready = awaitReady()

        assertTrue(ready.identity.reliable)
        assertEquals(ready.identity.workspaceId, store.read(SettingKeys.LocalWorkspaceId.name))
        assertTrue(holder.file.exists())
        assertEquals(DatabaseSchema.VERSION, holder.db.openHelper.writableDatabase.version)
        assertTrue(dumpLive().values.all { it.isEmpty() })
        assertEquals(
            setOf(SettingKeys.LocalWorkspaceId.name),
            store.snapshot().keys,
        )
        assertTrue(bookedTimes().isEmpty())
        assertNull(store.read(SettingKeys.LastBackupAt.name))
        assertFalse(BackupReminder.isDue(ZonedDateTime.now(clock), hasData = false))
    }

    @Test
    fun aBackupRestoresCompletelyOnAFreshInstall() = runBlocking {
        alarms.start(observeProcess = false)
        startup.start()
        val freshWorkspace = awaitReady().identity.workspaceId
        store.set(SettingKeys.HomeCalendarVisible.name, "true")
        store.set(SettingKeys.NotesViewMode.name, "list")
        store.set(SettingKeys.OnboardingSeen.name, "true")

        val zip = sourceZip()
        val source = readSource()
        val validated = backup.validate(zip.inputStream())
        backup.restore(validated)
        startup.restartAfterRestore()
        val ready = awaitReady()

        assertEquals(SOURCE_WORKSPACE, ready.identity.workspaceId)
        assertTrue(ready.identity.reliable)
        assertEquals(SOURCE_WORKSPACE, store.read(SettingKeys.LocalWorkspaceId.name))
        assertTrue(freshWorkspace != SOURCE_WORKSPACE)

        val restored = dumpLive()
        val changed = mutableMapOf<String, Pair<String?, String?>>()
        for ((table, rows) in source) {
            val now = restored.getValue(table)
            for ((key, row) in rows) {
                val after = now[key] ?: throw AssertionError("$table $key was lost")
                for ((column, value) in row) {
                    if (after[column] != value) changed["$table.$key.$column"] = value to after[column]
                }
            }
            val added = now.keys - rows.keys
            if (table == "tasks") {
                added.forEach { assertEquals("only series occurrences may be added", SERIES_ID, now.getValue(it)["series_id"]) }
            } else {
                assertEquals("$table gained rows", emptySet<String>(), added)
            }
        }
        assertEquals(emptyMap<String, Pair<String?, String?>>(), changed)

        SOURCE_FILES.forEach { (name, bytes) -> assertTrue(name, File(paths.filesDir, name).readBytes().contentEquals(bytes)) }
        assertEquals(SOURCE_FILES.keys, paths.filesDir.list().orEmpty().toSet())

        val settings = store.snapshot()
        RESTORED_SETTINGS.forEach { (key, value) -> assertEquals(key, value, settings[key]) }
        assertEquals(listOf("30_min", "m:90", "tomorrow_morning", "m:2880"), SnoozePresets.parseOrder(settings[SettingKeys.SnoozePresets.name]))
        assertNull(settings[SettingKeys.HomeCalendarVisible.name])
        assertNull(settings[SettingKeys.NotesViewMode.name])
        assertNull(settings[SettingKeys.OnboardingSeen.name])
        assertNull(settings[SettingKeys.RestorePendingSettings.name])
        assertNull(settings[SettingKeys.RestoreRolledBackAt.name])

        waitFor("alarms re-booked for the restored tasks") {
            val booked = bookedTimes()
            booked.isNotEmpty() && booked == runBlocking { plannedTimes() }
        }
        val booked = bookedTimes()
        assertTrue(booked.contains(Instant.parse("2026-11-21T03:30:00.000Z").toEpochMilli()))
        assertTrue(booked.contains(Instant.parse("2026-11-21T04:00:00.000Z").toEpochMilli()))
        assertTrue(booked.contains(Instant.parse("2026-11-20T06:00:00.000Z").toEpochMilli()))

        val installed = File(dataDir, "Sounds/$SOUND_ID.m4a")
        waitFor("custom sound reinstalled from the restored file") { installed.exists() }
        assertTrue(installed.readBytes().contentEquals(SOURCE_FILES.getValue("$SOUND_ID.m4a")))
        val resolved = alarmEnv.sounds.resolve(SoundChoice.File(SOUND_ID))
        assertNull(resolved.degradedReason)
        assertTrue(resolved.name!!.endsWith("/sounds/$SOUND_ID.m4a"))

        val newTask = tasks.create(TaskDraft(title = "Added after the restore", remindAt = Instant.parse("2026-11-25T10:00:00Z")))
        assertNotNull(newTask)
        waitFor("a task added after the restore is booked") {
            bookedTimes().contains(Instant.parse("2026-11-25T10:00:00Z").toEpochMilli())
        }

        val auth = OneDriveAuth(OneDriveTokenStore(PrefsTokenSlot(context), FakeCipher()), store, OkHttpClient(), log = BackupLog.None)
        assertFalse(auth.isConnected())
        val snapshot = AutoBackupHealth.snapshots(store, auth.connected).first()
        assertFalse(snapshot.on)
        assertFalse(snapshot.connected)
        assertNull(AutoBackupHealth.trouble(snapshot, clock.instant()))

        val lastBackup = UtcText.parse(store.read(SettingKeys.LastBackupAt.name))
        assertEquals(Instant.parse("2026-11-19T21:30:00.123Z"), lastBackup)
        assertFalse(BackupReminder.isDue(ZonedDateTime.now(clock), hasData = true, lastBackup = lastBackup))
        assertTrue(BackupReminder.isDue(ZonedDateTime.now(clock).plusDays(31), hasData = true, lastBackup = lastBackup))

        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun aRestoreIntoAnotherWorkspaceIsWhatTasksAndSearchSeeAfterTheRestart() = runBlocking {
        startup.start()
        val freshWorkspace = awaitReady().identity.workspaceId
        val search = SearchModule.searchRepository(SearchDao { holder.db }, identity)
        assertEquals(freshWorkspace, tasks.workspaceId())
        assertTrue(search.global("passport").isEmpty())

        backup.restore(backup.validate(sourceZip().inputStream()))
        startup.restartAfterRestore()
        awaitReady()

        assertEquals(SOURCE_WORKSPACE, tasks.workspaceId())
        assertTrue(tasks.watchInbox().first().any { it.id == "01JCT000000000000000000004" })
        assertTrue(search.global("passport").isNotEmpty())

        val added = tasks.create(TaskDraft(title = "Written after the restore", status = "inbox"))
        assertEquals(SOURCE_WORKSPACE, tasks.detail(added)!!.task.workspaceId)
        holder.close()

        val coldHolder = DatabaseHolder(context, holder.file)
        try {
            val coldIdentity = LocalIdentityRepository({ coldHolder.db.identityDao() }, store)
            val coldTasks = TasksRepository({ coldHolder.db }, coldIdentity, store, clock)
            val coldSearch = SearchModule.searchRepository(SearchDao { coldHolder.db }, coldIdentity)
            assertEquals(SOURCE_WORKSPACE, coldIdentity.resolve().workspaceId)
            assertTrue(coldTasks.watchInbox().first().any { it.id == added })
            assertTrue(coldSearch.global("Written after").isNotEmpty())
        } finally {
            coldHolder.close()
        }
    }

    @Test
    fun anOlderBackupKeepsANewerBackupDateAndAFutureDateIsClamped() = runBlocking {
        startup.start()
        awaitReady()
        store.set(SettingKeys.LastBackupAt.name, "2026-11-18T00:00:00.000Z")
        backup.restore(backup.validate(sourceZip(createdAt = "2026-09-01T00:00:00.000Z").inputStream()))
        assertEquals("2026-11-18T00:00:00.000Z", store.read(SettingKeys.LastBackupAt.name))

        startup.restartAfterRestore()
        awaitReady()
        store.remove(SettingKeys.LastBackupAt.name)
        backup.restore(backup.validate(sourceZip(createdAt = "2027-01-01T00:00:00.000Z").inputStream()))
        val clamped = UtcText.parse(store.read(SettingKeys.LastBackupAt.name))!!
        assertTrue(clamped.isBefore(Instant.parse("2027-01-01T00:00:00Z")))
        assertFalse(clamped.isAfter(Instant.now()))
    }

    companion object {
        private const val SOURCE_WORKSPACE = "01JCWS00000000000000000001"
        private const val SERIES_ID = "01JCSER0000000000000000001"
        private const val SOUND_ID = "01JCSND0000000000000000001"
        private const val FILE_PDF = "01JCF000000000000000000001"
        private const val FILE_JPG = "01JCF000000000000000000002"
        private const val FILE_BINNED = "01JCF000000000000000000003"

        private val TABLES = TestDatabases.userTables

        private val SOURCE_FILES: Map<String, ByteArray> = mapOf(
            "$FILE_PDF.pdf" to "%PDF-1.4 synthetic receipt".toByteArray(),
            "$FILE_JPG.jpg" to ByteArray(2048) { (it % 199).toByte() },
            "$FILE_BINNED.txt" to "binned shopping list".toByteArray(),
            "$SOUND_ID.m4a" to ByteArray(1500) { (it % 97).toByte() },
        )

        private val RESTORED_SETTINGS: Map<String, String> = linkedMapOf(
            SettingKeys.AlarmSound.name to "file:$SOUND_ID",
            SettingKeys.ReminderSound.name to "bundled:chime",
            SettingKeys.ReminderProfile.name to "{\"slots\":[0,30,60],\"repeatAfterSnooze\":true}",
            SettingKeys.SnoozePresets.name to "30_min,m:90,tomorrow_morning,m:2880",
            SettingKeys.NotificationPrivacy.name to "false",
            SettingKeys.DateFormat.name to "dmy_dot",
            SettingKeys.NoteSourceStyling.name to "live",
            SettingKeys.BinEnabled.name to "true",
            SettingKeys.BinAutoDelete.name to "true",
            SettingKeys.BinDays.name to "60",
            SettingKeys.AutoBackupSchedule.name to
                "{\"frequency\":\"weekly\",\"minutes\":150,\"secondMinutes\":840,\"weekday\":3,\"everyDays\":2,\"monthDay\":1}",
            SettingKeys.AutoBackupKeepDays.name to "14",
            SettingKeys.TasksSort.name to "priority:desc",
            SettingKeys.NotesSort.name to "title:asc",
            SettingKeys.FilesSort.name to "name:asc",
        )

        private val SOURCE_ROWS: List<String> = listOf(
            "INSERT INTO tasks (id, workspace_id, title, description, status, priority, due_at, remind_at, is_urgent, requires_acknowledgement, title_fold, sort_order, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000001', '$SOURCE_WORKSPACE', 'Renew passport', 'Plan in [note](remft://notes/01JCN000000000000000000001)', 'open', 'high', '2026-11-21T04:30:00.000Z', '2026-11-21T03:30:00.000Z', 1, 1, 'renew passport', 1, '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.123Z')",
            "INSERT INTO tasks (id, workspace_id, title, status, remind_at, snoozed_until, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000002', '$SOURCE_WORKSPACE', 'Water the plants', 'scheduled', '2026-11-20T05:00:00.000Z', '2026-11-20T06:00:00.000Z', '2026-11-10T00:00:00.000Z', '2026-11-20T05:01:00.000Z')",
            "INSERT INTO tasks (id, workspace_id, title, status, completed_at, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000003', '$SOURCE_WORKSPACE', 'Book dentist', 'completed', '2026-11-12T09:00:00.000Z', '2026-11-11T00:00:00.000Z', '2026-11-12T09:00:00.000Z')",
            "INSERT INTO tasks (id, workspace_id, title, status, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000004', '$SOURCE_WORKSPACE', 'Idea: label the shelves', 'inbox', '2026-11-15T00:00:00.000Z', '2026-11-15T00:00:00.000Z')",
            "INSERT INTO tasks (id, workspace_id, title, status, due_at, series_id, occurrence_date, completed_at, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000005', '$SOURCE_WORKSPACE', 'Pay rent', 'completed', '2026-11-05T03:30:00.000Z', '$SERIES_ID', '2026-11-05', '2026-11-05T05:00:00.000Z', '2026-10-01T00:00:00.000Z', '2026-11-05T05:00:00.000Z')",
            "INSERT INTO tasks (id, workspace_id, title, status, due_at, series_id, occurrence_date, created_at, updated_at) VALUES " +
                "('01JCT000000000000000000006', '$SOURCE_WORKSPACE', 'Pay rent', 'open', '2026-12-05T03:30:00.000Z', '$SERIES_ID', '2026-12-05', '2026-10-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO checklist_items (id, task_id, title, is_done, sort_order, created_at, updated_at) VALUES " +
                "('01JCC000000000000000000001', '01JCT000000000000000000001', 'Photos', 1, 0, '2026-11-01T08:00:00.000Z', '2026-11-02T08:00:00.000Z')",
            "INSERT INTO checklist_items (id, task_id, title, sort_order) VALUES ('01JCC000000000000000000002', '01JCT000000000000000000001', 'Form', 1)",
            "INSERT INTO reminders (id, task_id, kind, snooze_count, remind_at, alarm_level, requires_acknowledgement, status, created_at, updated_at) VALUES " +
                "('01JCR000000000000000000001', '01JCT000000000000000000001', 'remind', 0, '2026-11-21T03:30:00.000Z', 'urgent', 1, 'scheduled', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')",
            "INSERT INTO reminders (id, task_id, kind, snooze_count, remind_at, status, snoozed_until, created_at, updated_at) VALUES " +
                "('01JCR000000000000000000002', '01JCT000000000000000000002', 'remind', 1, '2026-11-20T05:00:00.000Z', 'snoozed', '2026-11-20T06:00:00.000Z', '2026-11-10T00:00:00.000Z', '2026-11-20T05:01:00.000Z')",
            "INSERT INTO reminders (id, task_id, kind, remind_at, status, acknowledged_at) VALUES " +
                "('01JCR000000000000000000003', '01JCT000000000000000000003', 'due', '2026-11-12T08:00:00.000Z', 'acknowledged', '2026-11-12T08:01:00.000Z')",
            "INSERT INTO notes (id, workspace_id, title, content_markdown, plain_text, is_pinned, title_fold, body_fold, created_at, updated_at) VALUES " +
                "('01JCN000000000000000000001', '$SOURCE_WORKSPACE', 'Trip', '# Trip\nSee remft://tasks/01JCT000000000000000000001 and ![scan](remft://files/$FILE_PDF)', 'Trip', 1, 'trip', 'trip', '2026-10-30T00:00:00.000Z', '2026-11-02T04:30:00.000Z')",
            "INSERT INTO notes (id, workspace_id, title, content_markdown, is_archived, created_at, updated_at) VALUES " +
                "('01JCN000000000000000000002', '$SOURCE_WORKSPACE', 'Math', '\$\$a^2+b^2=c^2\$\$', 1, '2026-10-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO note_link_rows (id, note_id, entity_type, entity_id) VALUES ('01JCL000000000000000000001', '01JCN000000000000000000001', 'task', '01JCT000000000000000000001')",
            "INSERT INTO folders (id, workspace_id, parent_id, name, created_at, updated_at) VALUES ('01JCD000000000000000000001', '$SOURCE_WORKSPACE', NULL, 'Receipts', '2026-10-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO folders (id, workspace_id, parent_id, name, created_at, updated_at) VALUES ('01JCD000000000000000000002', '$SOURCE_WORKSPACE', NULL, 'Ringtones', '2026-10-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO folders (id, workspace_id, parent_id, name) VALUES ('01JCD000000000000000000003', '$SOURCE_WORKSPACE', '01JCD000000000000000000001', '2026')",
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, name_fold, local_path, created_at, updated_at) VALUES " +
                "('$FILE_PDF', '$SOURCE_WORKSPACE', 'workspace', '$SOURCE_WORKSPACE', 'receipt.pdf', 'application/pdf', 26, '01JCD000000000000000000003', 'receipt.pdf', '$FILE_PDF.pdf', '2026-10-02T00:00:00.000Z', '2026-10-02T00:00:00.000Z')",
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, name_fold, local_path, created_at, updated_at) VALUES " +
                "('$FILE_JPG', '$SOURCE_WORKSPACE', 'task', '01JCT000000000000000000001', 'photo.jpg', 'image/jpeg', 2048, 'photo.jpg', '$FILE_JPG.jpg', '2026-11-01T08:05:00.000Z', '2026-11-01T08:05:00.000Z')",
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, name_fold, local_path, created_at, updated_at) VALUES " +
                "('$SOUND_ID', '$SOURCE_WORKSPACE', 'workspace', '$SOURCE_WORKSPACE', 'bell.m4a', 'audio/mp4', 1500, '01JCD000000000000000000002', 'bell.m4a', '$SOUND_ID.m4a', '2026-10-03T00:00:00.000Z', '2026-10-03T00:00:00.000Z')",
            "INSERT INTO task_series (id, workspace_id, rule_json, template_json, timezone, anchor_at, created_at, updated_at) VALUES " +
                "('$SERIES_ID', '$SOURCE_WORKSPACE', '{\"freq\":\"monthly\",\"interval\":1,\"byMonthDay\":[5],\"end\":{\"type\":\"never\"}}', " +
                "'{\"title\":\"Pay rent\",\"description\":null,\"priority\":\"none\",\"isUrgent\":false,\"requiresAcknowledgement\":false}', 'Asia/Kolkata', '2026-10-05T03:30:00.000Z', '2026-10-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO series_skips (series_id, occurrence_date, created_at) VALUES ('$SERIES_ID', '2026-10-05', '2026-10-01T00:00:00.000Z')",
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('01JCTR00000000000000000001', '$SOURCE_WORKSPACE', 'file', '$FILE_BINNED', 'list.txt', " +
                "'{\"files\":[{\"id\":\"$FILE_BINNED\",\"name\":\"list.txt\",\"localPath\":\"$FILE_BINNED.txt\"}],\"note\":\"remft://open\"}', '[\"$FILE_BINNED.txt\"]', 20, '2026-11-15T00:00:00.000Z')",
            "INSERT INTO alarm_events (at, event, lane, kind, slot_index, urgent, sound, level, fire_at, task_id, reminder_id, detail) VALUES " +
                "('2026-11-20T05:00:00.000Z', 'scheduled', 'notification', 'remind', 0, 1, 'remft_alarm', 'urgent', '2026-11-21T03:30:00.000Z', '01JCT000000000000000000001', '01JCR000000000000000000001', NULL)",
            "INSERT INTO alarm_events (at, event, lane, urgent, detail) VALUES ('2026-11-20T05:01:00.000Z', 'action', 'notification', 0, 'snooze 1_hour')",
            "INSERT INTO file_mirrors (local_path, sha256, size_bytes, quick_xor_hash, state, remote_path, item_id, attempts, mirrored_at, updated_at) VALUES " +
                "('$FILE_PDF.pdf', 'abc123', 26, 'qxh=', 'mirrored', 'Files/Receipts/2026/receipt ($FILE_PDF).pdf', 'ITEM1', 1, '2026-10-02T00:10:00.000Z', '2026-10-02T00:10:00.000Z')",
        )
    }
}
