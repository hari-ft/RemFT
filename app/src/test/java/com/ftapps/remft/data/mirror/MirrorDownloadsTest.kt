package com.ftapps.remft.data.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.onedrive.OneDriveRig
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class MirrorDownloadsTest {
    private lateinit var rig: OneDriveRig
    private lateinit var sql: FileMirrorSql
    private lateinit var filesDir: File
    private val remote = FakeRemote()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bytes = byteArrayOf(5, 6, 7)

    @Before
    fun setUp() {
        rig = OneDriveRig()
        val db = File(rig.root, "remft.sqlite")
        TestDatabases.create(db) {
            it.execSQL(
                "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                    "('F1', 'WS', 'other', 'X', 'a.bin', 'application/octet-stream', 3, '01F1.bin')",
            )
        }
        filesDir = File(rig.root, "files").apply { mkdirs() }
        File(filesDir, "01F1.bin").writeBytes(bytes)
        sql = FileMirrorSql(db)
        runBlocking {
            mirror().catchUp()
            File(filesDir, "01F1.bin").delete()
            rig.store.set(SettingKeys.MirrorDownloadsPending.name, "true")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        sql.close()
        rig.close()
    }

    private fun mirror() = FileMirror(sql, remote, filesDir, rig.store, log = BackupLog.None)

    private fun downloads() = MirrorDownloads(
        mirror(),
        rig.auth(),
        rig.store,
        { null },
        scope,
        startDelay = Duration.ZERO,
        backoff = listOf(Duration.ofHours(1)),
        log = BackupLog.None,
    )

    @Test
    fun withoutOneDriveTheFilesWaitForAConnection() = runBlocking {
        val d = downloads()
        d.start().join()
        assertEquals(MirrorDownloadPhase.NeedsOneDrive, d.state.value.phase)
        assertEquals(1, d.state.value.total)
        assertEquals("true", rig.store.get(SettingKeys.MirrorDownloadsPending.name))
        try {
            d.fetchNow("F1")
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.MIRROR_NEEDS_ONEDRIVE, e.code)
        }
    }

    @Test
    fun aRoundFetchesEverythingAndClearsTheFlag() = runBlocking {
        rig.tokens.write("R1")
        val d = downloads()
        d.start().join()
        assertEquals(MirrorDownloadPhase.Idle, d.state.value.phase)
        assertEquals(bytes.toList(), File(filesDir, "01F1.bin").readBytes().toList())
        assertNull(rig.store.get(SettingKeys.MirrorDownloadsPending.name))
    }

    @Test
    fun openingAMissingFileFetchesItAtOnce() = runBlocking {
        rig.tokens.write("R1")
        val file = downloads().fetchNow("F1")
        assertEquals(bytes.toList(), file!!.readBytes().toList())
        assertNull(downloads().fetchNow("F1"))
    }

    @Test
    fun aDamagedCopyWaitsAndRetriesLater() = runBlocking {
        rig.tokens.write("R1")
        remote.replaceBytes(mirror().rows().getValue("01F1.bin").itemId!!, byteArrayOf(0, 0, 0))
        val d = downloads()
        d.start().join()
        assertEquals(MirrorDownloadPhase.Waiting, d.state.value.phase)
        assertEquals(OneDriveErrors.MIRROR_DAMAGED, d.state.value.error)
        assertTrue(!File(filesDir, "01F1.bin").exists())
    }
}
