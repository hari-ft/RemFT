package com.ftapps.remft.data.onedrive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.mirror.FakeRemote
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.FileMirrorSql
import com.ftapps.remft.data.mirror.MirrorCatchUp
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestDatabases
import com.ftapps.remft.ui.onedrive.SyncNowText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MirrorSyncNowTest {
    private lateinit var rig: OneDriveRig
    private lateinit var sql: FileMirrorSql
    private lateinit var filesDir: File
    private lateinit var locks: WorkLocks
    private val remote = FakeRemote()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private val receiptPath = "Files/Tasks/Pay rent/receipt (F1).png"

    @Before
    fun setUp() {
        rig = OneDriveRig()
        locks = WorkLocks(File(rig.root, "locks"), 4242L)
        val db = File(rig.root, "remft.sqlite")
        TestDatabases.create(db) {
            it.execSQL("INSERT INTO tasks (id, workspace_id, title, created_at, updated_at) VALUES ('T1', 'WS', 'Pay rent', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')")
            it.execSQL("INSERT INTO folders (id, workspace_id, name) VALUES ('D1', 'WS', 'Docs')")
            it.execSQL(
                "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                    "('F1', 'WS', 'task', 'T1', 'receipt.png', 'image/png', 4, '01F1.png')",
            )
            it.execSQL(
                "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                    "('F2', 'WS', 'workspace', 'WS', 'manual.txt', 'text/plain', 6, 'D1', '01F2.txt')",
            )
        }
        filesDir = File(rig.root, "files").apply { mkdirs() }
        File(filesDir, "01F1.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(filesDir, "01F2.txt").writeBytes("manual".toByteArray())
        sql = FileMirrorSql(db)
        rig.tokens.write("refresh-token")
        runBlocking {
            rig.store.set(SettingKeys.OneDriveOn.name, OneDriveAuth.TRUE)
            rig.store.set(SettingKeys.MirrorReviewSweptAt.name, "done")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        sql.close()
        rig.close()
    }

    private val mirror by lazy {
        FileMirror(sql, remote, filesDir, rig.store, clock = { rig.now }, log = BackupLog.None, stopRequested = { locks.isHeld(WorkLockNames.RESTORE) })
    }

    private fun sync(): MirrorSyncNow {
        val auth = rig.auth()
        val turn = AutoBackupTurn(
            store = rig.store,
            auth = auth,
            mirror = mirror,
            runner = { throw IllegalStateException("no backup here") },
            wake = BackupWake(rig.store, WakeBooker { }, log = BackupLog.None),
            locks = { locks },
            restoring = { false },
            clock = { rig.now },
            log = BackupLog.None,
            lockWait = Duration.ofSeconds(2),
        )
        val downloads = MirrorDownloads(mirror, auth, rig.store, { locks }, scope, startDelay = Duration.ZERO, backoff = listOf(Duration.ofHours(1)), log = BackupLog.None)
        return MirrorSyncNow(rig.store, auth, turn, mirror, downloads, scope, log = BackupLog.None)
    }

    @Test
    fun aSyncUploadsEverythingThenReportsUpToDateAndTheTime() = runBlocking {
        val s = sync()
        val first = s.run()
        assertEquals(SyncNowResult.Synced(uploaded = 2), first)
        assertEquals("Synced: 2 uploaded", SyncNowText.message(resources, first))
        assertEquals(2, remote.puts.size)
        assertNotNull(withTimeout(5_000) { s.lastSynced.first { it != null } })
        val second = s.run()
        assertEquals(SyncNowResult.UpToDate, second)
        assertEquals("OneDrive is up to date", SyncNowText.message(resources, second))
        assertEquals(2, remote.puts.size)
        assertTrue(remote.deletes.isEmpty())
    }

    @Test
    fun aRestoreInProgressTurnsSyncAwayWithAClearMessage() = runBlocking {
        val restore = locks.tryAcquire(WorkLockNames.RESTORE)!!
        try {
            val result = sync().run()
            assertEquals(SyncNowResult.RestoreBusy, result)
            assertEquals("Busy, try again after the restore", SyncNowText.message(resources, result))
            assertTrue(remote.puts.isEmpty())
        } finally {
            restore.release()
        }
    }

    @Test
    fun aSecondTapWhileRunningDoesNothing() = runBlocking {
        val gate = CountDownLatch(1)
        remote.onPut = { gate.await(10, TimeUnit.SECONDS) }
        val s = sync()
        val result = CompletableDeferred<SyncNowResult>()
        val listener = scope.launch { result.complete(s.results.first()) }
        withTimeout(5_000) { s.running.first { !it } }
        assertTrue(s.start())
        withTimeout(5_000) { while (remote.puts.isEmpty()) kotlinx.coroutines.delay(10) }
        assertFalse(s.start())
        assertTrue(s.running.value)
        gate.countDown()
        assertEquals(SyncNowResult.Synced(uploaded = 2), withTimeout(10_000) { result.await() })
        withTimeout(5_000) { s.running.first { !it } }
        assertEquals(2, remote.puts.size)
        listener.cancel()
    }

    @Test
    fun signedOutOffersConnect() = runBlocking {
        rig.tokens.remove()
        val result = sync().run()
        assertEquals(SyncNowResult.SignedOut, result)
        assertTrue(SyncNowText.needsConnect(result))
        assertEquals("OneDrive is not connected — connect it to sync", SyncNowText.message(resources, result))
        assertTrue(remote.puts.isEmpty())
    }

    @Test
    fun offlineStopsWithAClearError() = runBlocking {
        remote.failPuts[receiptPath] = OneDriveErrors.OFFLINE
        val result = sync().run()
        assertEquals(SyncNowResult.Failed(OneDriveErrors.OFFLINE), result)
        assertEquals("Couldn’t sync: no internet connection", SyncNowText.message(resources, result))
    }

    @Test
    fun aFullOneDriveSaysSo() = runBlocking {
        remote.failPuts[receiptPath] = OneDriveErrors.FULL
        val result = sync().run()
        assertEquals(SyncNowResult.Failed(OneDriveErrors.FULL), result)
        assertEquals("Couldn’t sync: OneDrive is full", SyncNowText.message(resources, result))
    }

    @Test
    fun oneDriveTurnedOffExplainsHow() = runBlocking {
        rig.store.remove(SettingKeys.OneDriveOn.name)
        val result = sync().run()
        assertEquals(SyncNowResult.Off, result)
        assertTrue(remote.puts.isEmpty())
    }

    @Test
    fun theSummaryNamesEachKindOfChange() {
        val result = SyncNowResult.of(MirrorCatchUp(uploaded = 3, failed = 1, moved = 2))
        assertEquals(SyncNowResult.Synced(uploaded = 3, moved = 2, failed = 1), result)
        assertEquals("Synced: 3 uploaded, 2 moved, 1 failed", SyncNowText.message(resources, result))
        val stopped = SyncNowResult.of(MirrorCatchUp(uploaded = 1, remaining = 4))
        assertEquals(SyncNowResult.StoppedEarly(1, 4), stopped)
        assertTrue(SyncNowText.message(resources, stopped).startsWith("Stopped early, 4 left"))
        assertEquals(SyncNowResult.SignedOut, SyncNowResult.of(MirrorCatchUp(stoppedBy = OneDriveErrors.RECONNECT)))
        assertEquals(SyncNowResult.Failed(OneDriveErrors.OTHER_ACCOUNT), SyncNowResult.of(MirrorCatchUp(), downloadError = OneDriveErrors.OTHER_ACCOUNT))
    }
}
