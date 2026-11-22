package com.ftapps.remft.data.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.mirror.BackupMirror
import com.ftapps.remft.data.mirror.MirrorCatchUp
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupManifest
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.backup.auto.AutoBackupPause
import com.ftapps.remft.domain.backup.auto.BackupDataSize
import com.ftapps.remft.domain.backup.auto.DestinationStatus
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class AutoBackupRunnerTest {
    private lateinit var rig: OneDriveRig
    private val destination = FakeDestination()
    private val mirror = FakeBackupMirror()
    private val builds = ArrayList<Map<String, MirrorRef>>()
    private var size = BackupDataSize(20, 5, 3, 3_000_000)
    private var unavailable = emptyList<String>()
    private val successes = ArrayList<Instant>()

    @Before
    fun setUp() {
        rig = OneDriveRig()
        runBlocking { rig.store.set(SettingKeys.OneDriveOn.name, "true") }
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private fun runner(withMirror: Boolean = true) = AutoBackupRunner(
        store = rig.store,
        destination = destination,
        builder = AutoBackupBuilder { out, now, listOnly ->
            builds.add(listOnly)
            out.parentFile?.mkdirs()
            out.writeBytes(ByteArray(16))
            BuiltBackup(
                out,
                BackupManifest(BackupFormat.CURRENT, "1.1.1", 3, now, "00", 0, 0, unavailable = unavailable),
            )
        },
        scratch = File(rig.root, "auto-backup"),
        mirror = if (withMirror) mirror else null,
        dataSize = { size },
        onSuccess = { successes.add(it) },
        clock = { rig.now },
        zone = { ZoneOffset.UTC },
        log = BackupLog.None,
    )

    @Test
    fun offWhenOneDriveIsNotOn() = runBlocking {
        rig.store.remove(SettingKeys.OneDriveOn.name)
        assertEquals(AutoBackupOutcome.NothingOn, runner().run(force = true))
        assertTrue(destination.uploads.isEmpty())
    }

    @Test
    fun skipsWhenNotDue() = runBlocking {
        rig.store.set(SettingKeys.AutoBackupLastOk.name, UtcText.format(rig.now.minus(Duration.ofHours(1))))
        assertEquals(AutoBackupOutcome.NotDue, runner().run())
        assertTrue(destination.uploads.isEmpty())
        assertTrue(builds.isEmpty())
        assertEquals(0, mirror.catchUps)
    }

    @Test
    fun forceRunsEvenWhenNotDue() = runBlocking {
        rig.store.set(SettingKeys.AutoBackupLastOk.name, UtcText.format(rig.now.minus(Duration.ofHours(1))))
        assertEquals(AutoBackupOutcome.Done, runner().run(force = true))
        assertEquals(1, destination.uploads.size)
    }

    @Test
    fun pausesWhenTheDataShrankByMoreThanHalf() = runBlocking {
        rig.store.set(SettingKeys.AutoBackupLastSize.name, BackupDataSize(40, 10, 4, 2_000_000).encode())
        size = BackupDataSize(10, 2, 1, 2_000_000)
        assertEquals(AutoBackupOutcome.Paused, runner().run())
        assertTrue(destination.uploads.isEmpty())
        assertTrue(destination.deleted.isEmpty())
        val pause = AutoBackupPause.parse(rig.store.get(SettingKeys.AutoBackupPaused.name))
        assertNotNull(pause)
        assertEquals(54L, pause!!.previous.items)
        assertEquals(13L, pause.current.items)
        assertNull(rig.store.get(SettingKeys.AutoBackupLastOk.name))
    }

    @Test
    fun backUpAnywayGoesAheadAndClearsTheAcceptance() = runBlocking {
        rig.store.set(SettingKeys.AutoBackupLastSize.name, BackupDataSize(40, 10, 4, 2_000_000).encode())
        rig.store.set(SettingKeys.AutoBackupPaused.name, "{}")
        rig.store.set(SettingKeys.AutoBackupDropAccepted.name, "true")
        size = BackupDataSize(10, 2, 1, 2_000_000)
        assertEquals(AutoBackupOutcome.Done, runner().run())
        assertNull(rig.store.get(SettingKeys.AutoBackupDropAccepted.name))
        assertNull(rig.store.get(SettingKeys.AutoBackupPaused.name))
        assertEquals(size, BackupDataSize.parse(rig.store.get(SettingKeys.AutoBackupLastSize.name)))
    }

    @Test
    fun successUploadsRecordsStatusAndPrunesOnlyAutomaticBackups() = runBlocking {
        val old = (1..40).map { days ->
            val at = rig.now.minus(Duration.ofDays(days.toLong() * 9))
            StoredBackup("auto-$days", BackupNames.automatic(at), 10)
        }
        destination.files.addAll(old)
        destination.files.add(StoredBackup("manual", "remft-backup-2024-01-01.zip", 10))
        destination.files.add(StoredBackup("other", "my notes.txt", 10))
        destination.files.add(StoredBackup("notes", "notes-2020.zip", 10))
        mirror.plan = mapOf("01A.png" to MirrorRef("ITEM", "Files/Other/a (01A).png", 4, "ab"))

        assertEquals(AutoBackupOutcome.Done, runner().run())

        assertEquals(listOf(BackupNames.automatic(rig.now)), destination.uploads)
        assertEquals(listOf(mirror.plan), builds)
        assertEquals(1, mirror.catchUps)
        assertTrue(destination.deleted.isNotEmpty())
        assertTrue(destination.deleted.all { BackupNames.isAutomatic(it.name) })
        assertFalse(destination.deleted.any { it.id in setOf("manual", "other", "notes") })
        val status = DestinationStatus.parse(rig.store.get(DestinationStatus.key("onedrive")))
        assertEquals(DestinationStatus(rig.now, true), status)
        assertEquals(UtcText.format(rig.now), rig.store.get(SettingKeys.AutoBackupLastOk.name))
        assertEquals(size, BackupDataSize.parse(rig.store.get(SettingKeys.AutoBackupLastSize.name)))
        assertEquals(listOf(rig.now), successes)
        assertFalse(File(rig.root, "auto-backup").listFiles().orEmpty().any { it.isFile })
    }

    @Test
    fun filesItCouldNotIncludeAreCountedButTheRunStillCounts() = runBlocking {
        unavailable = listOf("01X.pdf", "01Y.pdf")
        assertEquals(AutoBackupOutcome.Incomplete, runner().run())
        assertEquals("2", rig.store.get(SettingKeys.AutoBackupUnavailable.name))
        assertNotNull(rig.store.get(SettingKeys.AutoBackupLastOk.name))
    }

    @Test
    fun aFailedUploadRecordsTheCodeAndKeepsTheLastSuccess() = runBlocking {
        destination.failWith = OneDriveException(OneDriveErrors.FULL)
        assertEquals(AutoBackupOutcome.Failed, runner().run())
        val status = DestinationStatus.parse(rig.store.get(DestinationStatus.key("onedrive")))
        assertEquals(OneDriveErrors.FULL, status!!.error)
        assertFalse(status.ok)
        assertNull(rig.store.get(SettingKeys.AutoBackupLastOk.name))
        assertTrue(destination.deleted.isEmpty())
    }

    @Test
    fun aMirrorCheckThatFailsStopsBeforeAnythingIsUploaded() = runBlocking {
        mirror.planError = OneDriveException(OneDriveErrors.OTHER_ACCOUNT)
        assertEquals(AutoBackupOutcome.Failed, runner().run())
        assertTrue(builds.isEmpty())
        assertTrue(destination.uploads.isEmpty())
        val status = DestinationStatus.parse(rig.store.get(DestinationStatus.key("onedrive")))
        assertEquals(OneDriveErrors.OTHER_ACCOUNT, status!!.error)
    }
}

class FakeDestination : BackupDestination {
    val uploads = ArrayList<String>()
    val files = ArrayList<StoredBackup>()
    val deleted = ArrayList<StoredBackup>()
    var failWith: Exception? = null

    override val id: String = "onedrive"

    override suspend fun upload(zip: File, name: String) {
        failWith?.let { throw it }
        check(zip.isFile)
        uploads.add(name)
        files.add(StoredBackup("new-$name", name, zip.length()))
    }

    override suspend fun list(): List<StoredBackup> = files.toList()

    override suspend fun delete(backup: StoredBackup) {
        check(BackupNames.isAutomatic(backup.name))
        deleted.add(backup)
        files.remove(backup)
    }
}

class FakeBackupMirror : BackupMirror {
    var catchUps = 0
    var plan: Map<String, MirrorRef> = emptyMap()
    var planError: Exception? = null

    override suspend fun catchUp(deadline: Instant?): MirrorCatchUp {
        catchUps++
        return MirrorCatchUp()
    }

    override suspend fun planForBackup(backupAt: Instant): Map<String, MirrorRef> {
        planError?.let { throw it }
        return plan
    }
}
