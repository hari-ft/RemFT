package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class AppLogRoutingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val previous = AppLogs.current
    private lateinit var dir: File
    private lateinit var log: AppLog

    @Before
    fun setUp() {
        dir = File(folder.root, AppLogs.FOLDER)
        log = AppLog(dir)
        AppLogs.install(log)
    }

    @After
    fun tearDown() {
        AppLogs.install(previous)
    }

    private fun entries(): List<AppLogEntry> = log.read()

    private fun entry(event: String): AppLogEntry = entries().firstOrNull { it.event == event } ?: throw AssertionError("$event not logged in ${entries().map { it.event }}")

    @Test
    fun backupLogEventsLandInTheAppLogWithLevelCategoryAndMessage() {
        AndroidBackupLog.info("backup.restored", mapOf("schema" to 1, "files" to 2))
        AndroidBackupLog.warn("autobackup.onedrive_refused", fields = mapOf("status" to 507, "code" to "quotaLimitReached"))
        AndroidBackupLog.error("oldbackup.convert_failed", BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, "C:/Users/Someone/old.zip"))
        AndroidBackupLog.warn("mirror.upload_failed", OneDriveException("offline"))

        val restored = entry("backup.restored")
        assertEquals(AppLogLevel.INFO, restored.level)
        assertEquals(AppLogCategory.RESTORE, restored.category)
        assertEquals("Restore finished", restored.message)
        assertEquals("2", restored.fields["files"])

        val refused = entry("autobackup.onedrive_refused")
        assertEquals(AppLogLevel.WARN, refused.level)
        assertEquals(AppLogCategory.ONEDRIVE, refused.category)
        assertEquals("quotaLimitReached", refused.fields["code"])

        val convert = entry("oldbackup.convert_failed")
        assertEquals(AppLogLevel.ERROR, convert.level)
        assertEquals(AppLogCategory.IMPORT, convert.category)
        assertEquals("BackupException", convert.errorType)
        assertEquals(BackupErrorCode.OLD_APP_UNSUPPORTED, convert.fields["reason"])

        val upload = entry("mirror.upload_failed")
        assertEquals(AppLogCategory.ONEDRIVE, upload.category)
        assertEquals("offline", upload.fields["reason"])
        assertFalse(log.exportText().contains("Someone"))
    }

    @Test
    fun eventsLoggedBeforeTheLogIsInstalledAreKept() {
        AppLogs.install(null)
        AppLogs.warn("startup.early_trouble", fields = mapOf("attempt" to 1))
        AndroidBackupLog.info("backup.scratch_swept", mapOf("entries" to 3))
        AppLogs.install(log)
        assertEquals(AppLogCategory.STARTUP, entry("startup.early_trouble").category)
        assertEquals("3", entry("backup.scratch_swept").fields["entries"])
    }

    @Test
    fun titlesNamesPathsAndMessagesNeverReachTheLogText() {
        val secret = "Groceries"
        AndroidBackupLog.info(
            "backup.restored",
            mapOf("title" to secret, "name" to "$secret list", "path" to "/storage/emulated/0/Hari/$secret.md", "key" to "$secret for Sunday"),
        )
        AppLogs.warn("notes.save_failed", IllegalStateException("could not save $secret"), mapOf("note" to "$secret and more"))
        AppLogs.error("files.open_failed", RuntimeException("/data/user/0/Hari/files/$secret.pdf"))
        val text = log.exportText()
        assertFalse(text, text.contains(secret))
        assertFalse(text, text.contains("Hari"))
        assertTrue(text, text.contains(LogCode.REDACTED))
        assertEquals("IllegalStateException", entry("notes.save_failed").errorType)
    }

    @Test
    fun aFailingLogNeverBreaksTheCaller() {
        val blocked = folder.newFile("blocked")
        val broken = AppLog(blocked)
        AppLogs.install(broken)
        AndroidBackupLog.warn("backup.restore_settle_failed", IllegalStateException())
        AppLogs.info("bin.purged", mapOf("items" to 2))
        val throwing = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("boom")
        }
        AppLogs.info("files.attached", throwing)
        AndroidBackupLog.info("backup.created", throwing)
        broken.info("app.anything", throwing)
        assertEquals(listOf("bin.purged", "backup.restore_settle_failed"), broken.read().map { it.event })

        assertTrue(blocked.delete())
        AppLogs.info("bin.purged", mapOf("items" to 1))
        broken.flush()
        val written = File(blocked, AppLog.CURRENT).readLines()
        assertEquals(3, written.size)
        assertEquals(3, AppLog(blocked).read().size)
    }

    @Test
    fun aCrashIsRecordedAndStillReachesThePreviousHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
            AppLogs.watchCrashes()
            AppLogs.watchCrashes()
            val crash = IllegalStateException("Groceries crashed")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
            assertSame(crash, passedOn)
            val logged = entry("app.crash")
            assertEquals(AppLogLevel.ERROR, logged.level)
            assertEquals(AppLogCategory.APP, logged.category)
            assertEquals("IllegalStateException", logged.errorType)
            assertFalse(log.exportText().contains("Groceries"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun everyEventGetsAReadableCategoryAndMessage() {
        val expected = mapOf(
            "app.launch" to AppLogCategory.STARTUP,
            "startup.failed" to AppLogCategory.STARTUP,
            "backup.created" to AppLogCategory.BACKUP,
            "backup.export_failed" to AppLogCategory.BACKUP,
            "lock.busy" to AppLogCategory.BACKUP,
            "backup.validated" to AppLogCategory.RESTORE,
            "backup.refused" to AppLogCategory.RESTORE,
            "backup.restore_phase" to AppLogCategory.RESTORE,
            "backup.rollback_failed" to AppLogCategory.RESTORE,
            "backup.leftover_bak_kept" to AppLogCategory.RESTORE,
            "restore.settings_applied" to AppLogCategory.RESTORE,
            "backup.onedrive_list_failed" to AppLogCategory.ONEDRIVE,
            "autobackup.done" to AppLogCategory.AUTO_BACKUP,
            "autobackup.scheduled" to AppLogCategory.AUTO_BACKUP,
            "autobackup.onedrive_token_unreadable" to AppLogCategory.ONEDRIVE,
            "onedrive.connected" to AppLogCategory.ONEDRIVE,
            "mirror.downloads" to AppLogCategory.ONEDRIVE,
            "oldbackup.converted" to AppLogCategory.IMPORT,
            "files.open_failed" to AppLogCategory.FILES,
            "notes.export_failed" to AppLogCategory.NOTES,
            "tasks.save_failed" to AppLogCategory.TASKS,
            "series.change_failed" to AppLogCategory.TASKS,
            "bin.purged" to AppLogCategory.BIN,
            "settings.write_failed" to AppLogCategory.SETTINGS,
            "refresh.start" to AppLogCategory.ALARMS,
            "alarms.apply_failed" to AppLogCategory.ALARMS,
            "app.crash" to AppLogCategory.APP,
            "something" to AppLogCategory.APP,
        )
        expected.forEach { (event, category) -> assertEquals(event, category, AppLogCategory.of(event)) }

        val created = AppLogEntry(
            Instant.EPOCH,
            AppLogLevel.INFO,
            "backup.created",
            mapOf("tasks" to "12", "notes" to "3", "files" to "5", "bytes" to (2.1 * 1024 * 1024).toLong().toString()),
        )
        assertEquals("Backup created: 12 tasks, 3 notes, 5 files, 2.1 MB", created.message)
        assertEquals("Restored from old RemFT backup 2.3.7", AppLogEntry(Instant.EPOCH, AppLogLevel.INFO, "oldbackup.restored", mapOf("appVersion" to "2.3.7")).message)
        assertEquals("Mirror refs failed", AppLogEntry(Instant.EPOCH, AppLogLevel.WARN, "backup.mirror_refs_failed").message)
        assertTrue(created.format().contains("INFO Backup backup.created: Backup created: 12 tasks"))
    }

    @Test
    fun theCategoryIsStoredWhenItDiffersAndOldLinesStillRead() {
        dir.mkdirs()
        File(dir, AppLog.CURRENT).writeText("{\"t\":\"${Instant.now()}\",\"l\":\"warn\",\"e\":\"backup.refused\"}\n")
        log.add(AppLogLevel.WARN, "app.storage_low", emptyMap(), null, AppLogCategory.FILES)
        val read = entries()
        assertEquals(AppLogCategory.FILES, read.first { it.event == "app.storage_low" }.category)
        assertEquals(AppLogCategory.RESTORE, read.first { it.event == "backup.refused" }.category)
        assertNotNull(AppLog(dir).read().firstOrNull { it.category == AppLogCategory.FILES })
    }

    @Test
    fun theSizeCapStillHolds() {
        val capped = AppLog(File(folder.root, "capped"), maxBytes = 2_000)
        repeat(200) { capped.info("backup.restore_phase", mapOf("phase" to LogCode("staged"), "n" to it)) }
        capped.flush()
        val files = File(folder.root, "capped").listFiles().orEmpty()
        assertTrue(files.map { it.name }.toString(), files.size <= 2)
        assertTrue(files.sumOf { it.length() } <= 4_000)
    }
}
