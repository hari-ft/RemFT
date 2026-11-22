package com.ftapps.remft.data.mirror

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.onedrive.MirrorMove
import com.ftapps.remft.data.onedrive.MirrorRemote
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.onedrive.OneDriveRig
import com.ftapps.remft.data.onedrive.RemoteEntry
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.backup.mirror.QuickXorHash
import com.ftapps.remft.domain.backup.mirror.RemoteItem
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FileMirrorTest {
    private lateinit var rig: OneDriveRig
    private lateinit var dbFile: File
    private lateinit var filesDir: File
    private lateinit var sql: FileMirrorSql
    private val remote = FakeRemote()
    private var now: Instant = Instant.parse("2026-11-20T10:00:00Z")

    private val receipt = byteArrayOf(1, 2, 3, 4)
    private val manual = "manual text".toByteArray()

    @Before
    fun setUp() {
        rig = OneDriveRig()
        dbFile = File(rig.root, "remft.sqlite")
        filesDir = File(rig.root, "files").apply { mkdirs() }
        TestDatabases.create(dbFile) { db -> seed(db) }
        File(filesDir, "01F1.png").writeBytes(receipt)
        File(filesDir, "01F2.txt").writeBytes(manual)
        sql = FileMirrorSql(dbFile)
    }

    @After
    fun tearDown() {
        sql.close()
        rig.close()
    }

    private fun mirror() = FileMirror(sql, remote, filesDir, rig.store, clock = { now }, log = BackupLog.None)

    private fun seed(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO tasks (id, workspace_id, title, created_at, updated_at) VALUES ('T1', 'WS', 'Pay rent', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')")
        db.execSQL("INSERT INTO folders (id, workspace_id, name) VALUES ('D1', 'WS', 'Docs')")
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('F1', 'WS', 'task', 'T1', 'receipt.png', 'image/png', 4, '01F1.png')",
        )
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                "('F2', 'WS', 'workspace', 'WS', 'manual.txt', 'text/plain', 11, 'D1', '01F2.txt')",
        )
    }

    private fun row(name: String) = runBlocking { mirror().rows()[name] }

    @Test
    fun neededFilesGetTheirMirrorPaths() = runBlocking {
        val needed = mirror().neededFiles()
        assertEquals("Files/Tasks/Pay rent/receipt (F1).png", needed["01F1.png"]!!.remotePath)
        assertEquals("Files/Docs/manual (F2).txt", needed["01F2.txt"]!!.remotePath)
    }

    @Test
    fun catchUpUploadsEachFileOnceAndVerifiesIt() = runBlocking {
        val result = mirror().catchUp()
        assertEquals(2, result.uploaded)
        assertEquals(setOf("Files/Tasks/Pay rent/receipt (F1).png", "Files/Docs/manual (F2).txt"), remote.puts.toSet())
        val r = row("01F1.png")!!
        assertEquals(MirrorState.MIRRORED, r.state)
        assertEquals(FileOps.sha256Hex(receipt), r.sha256)
        assertEquals(QuickXorHash.of(receipt), r.quickXorHash)
        assertNotNull(r.itemId)
        assertEquals(now, r.mirroredAt)
        remote.puts.clear()
        assertEquals(0, mirror().catchUp().uploaded)
        assertTrue(remote.puts.isEmpty())
        assertTrue(remote.deletes.isEmpty())
    }

    @Test
    fun aCopyThatDoesNotMatchIsMarkedFailed() = runBlocking {
        remote.corruptNextPut = true
        assertFalse(mirror().uploadOne("01F1.png", "Files/Tasks/Pay rent/receipt (F1).png"))
        val r = row("01F1.png")!!
        assertEquals(MirrorState.FAILED, r.state)
        assertEquals(OneDriveErrors.MISMATCH, r.lastError)
    }

    @Test
    fun anOfficeFileOneDriveRewroteCountsAsMirrored() = runBlocking {
        File(filesDir, "01F3.docx").writeBytes(ByteArray(20) { 7 })
        remote.corruptNextPut = true
        assertTrue(mirror().uploadOne("01F3.docx", "Files/Other/report (F3).docx"))
        assertEquals(MirrorState.MIRRORED, row("01F3.docx")!!.state)
    }

    @Test
    fun goneFilesAreMovedToReviewNeededAndNeverDeleted() = runBlocking {
        mirror().catchUp()
        val itemId = row("01F2.txt")!!.itemId!!
        sql.exec("DELETE FROM file_rows WHERE id = 'F2'")
        remote.addFile("Files/reviewneeded/manual (F2).txt", "taken".toByteArray())
        rig.store.set(SettingKeys.MirrorReviewSweptAt.name, "done")

        val moved = mirror().reviewGone()

        assertEquals(1, moved)
        assertEquals("Files/reviewneeded/manual (F2) (2).txt", remote.pathOf(itemId))
        assertNull(row("01F2.txt"))
        assertTrue(remote.deletes.isEmpty())
        assertNotNull(row("01F1.png"))
    }

    @Test
    fun theOneTimeSweepMovesStrayFilesAndRemembersItRan() = runBlocking {
        mirror().catchUp()
        remote.addFile("Files/Old/stray.pdf", "x".toByteArray())
        rig.store.remove(SettingKeys.MirrorReviewSweptAt.name)
        val moved = mirror().reviewGone()
        assertEquals(1, moved)
        assertTrue(remote.paths().contains("Files/reviewneeded/stray.pdf"))
        assertTrue(remote.paths().contains("Files/Tasks/Pay rent/receipt (F1).png"))
        assertNotNull(rig.store.get(SettingKeys.MirrorReviewSweptAt.name))
        assertTrue(remote.deletes.isEmpty())
    }

    @Test
    fun planListsOnlyFilesOneDriveWasJustSeenToHold() = runBlocking {
        mirror().catchUp()
        val lost = row("01F2.txt")!!.itemId!!
        remote.drop(lost)
        val plan = mirror().planForBackup(now)
        assertEquals(setOf("01F1.png"), plan.keys)
        assertEquals(row("01F1.png")!!.itemId, plan["01F1.png"]!!.itemId)
        assertEquals(MirrorState.MISSING, row("01F2.txt")!!.state)
        assertEquals(now, row("01F1.png")!!.listedAt)
    }

    @Test
    fun noneOfSeveralFilesFoundMeansAnotherAccount() = runBlocking {
        mirror().catchUp()
        val ids = listOf(row("01F1.png")!!.itemId!!, row("01F2.txt")!!.itemId!!)
        ids.forEach { remote.drop(it) }
        File(filesDir, "01F1.png").delete()
        File(filesDir, "01F2.txt").delete()
        try {
            mirror().planForBackup(now)
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.OTHER_ACCOUNT, e.code)
        }
        assertEquals(MirrorState.MIRRORED, row("01F1.png")!!.state)
    }

    @Test
    fun downloadsAreVerifiedBeforeTheyLand() = runBlocking {
        mirror().catchUp()
        File(filesDir, "01F1.png").delete()
        val pending = mirror().pendingDownloads()
        assertEquals(listOf("01F1.png"), pending.map { it.storedName })
        val landed = mirror().download(pending.single())
        assertEquals(receipt.toList(), landed.readBytes().toList())
        assertTrue(mirror().pendingDownloads().isEmpty())
        assertFalse(File(filesDir.path + FileMirror.INCOMING_SUFFIX, "01F1.png.part").exists())
    }

    @Test
    fun aDamagedDownloadNeverLands() = runBlocking {
        mirror().catchUp()
        File(filesDir, "01F1.png").delete()
        remote.replaceBytes(row("01F1.png")!!.itemId!!, byteArrayOf(9, 9, 9, 9))
        val pending = mirror().pendingDownloads().single()
        try {
            mirror().download(pending)
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.MIRROR_DAMAGED, e.code)
        }
        assertFalse(File(filesDir, "01F1.png").exists())
    }

    @Test
    fun aDownloadOneDriveCannotFindIsMarkedRemoteMissing() = runBlocking {
        mirror().catchUp()
        File(filesDir, "01F1.png").delete()
        remote.drop(row("01F1.png")!!.itemId!!)
        try {
            mirror().download(mirror().pendingDownloads().single())
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.MIRROR_MISSING, e.code)
        }
        assertEquals(MirrorState.REMOTE_MISSING, row("01F1.png")!!.state)
        assertEquals(1, mirror().pendingDownloads().size)
    }

    @Test
    fun pendingForFindsAMissingFileById() = runBlocking {
        mirror().catchUp()
        assertNull(mirror().pendingFor("F1"))
        File(filesDir, "01F1.png").delete()
        assertEquals("01F1.png", mirror().pendingFor("F1")!!.storedName)
        assertNull(mirror().pendingFor("NOPE"))
    }

    @Test
    fun refsForMissingLocalStampsAYearAhead() = runBlocking {
        mirror().catchUp()
        File(filesDir, "01F2.txt").delete()
        val refs = mirror().refsForMissingLocal()
        assertEquals(setOf("01F2.txt"), refs.keys)
        assertEquals(now.plus(Duration.ofDays(365)), row("01F2.txt")!!.listedAt)
    }
}

class FakeRemote : MirrorRemote {
    private class Item(var path: String, var bytes: ByteArray, val folder: Boolean = false)

    private val items = LinkedHashMap<String, Item>()
    private var next = 0
    val puts = ArrayList<String>()
    val deletes = ArrayList<String>()
    var corruptNextPut = false

    fun addFile(path: String, bytes: ByteArray): String {
        val id = "I${next++}"
        items[id] = Item(path, bytes)
        return id
    }

    fun drop(id: String) {
        items.remove(id)
    }

    fun replaceBytes(id: String, bytes: ByteArray) {
        items[id]!!.bytes = bytes
    }

    fun pathOf(id: String): String? = items[id]?.path

    fun paths(): List<String> = items.values.filter { !it.folder }.map { it.path }

    private fun idOfPath(path: String): String? = items.entries.firstOrNull { it.value.path.equals(path, ignoreCase = true) }?.key

    private fun parentPath(path: String) = path.substringBeforeLast('/', "")

    private fun ancestors(path: String): List<String> {
        val out = ArrayList<String>()
        var at = parentPath(path)
        while (at.isNotEmpty()) {
            out.add(at)
            at = parentPath(at)
        }
        return out
    }

    override suspend fun putFile(file: File, path: String, contentType: String): RemoteItem {
        puts.add(path)
        val bytes = file.readBytes()
        val id = idOfPath(path)?.also { items[it]!!.bytes = bytes } ?: addFile(path, bytes)
        val stored = if (corruptNextPut) bytes + byteArrayOf(0) else bytes
        corruptNextPut = false
        items[id]!!.bytes = stored
        return RemoteItem(id, path.substringAfterLast('/'), stored.size.toLong(), QuickXorHash.of(stored))
    }

    override suspend fun itemsById(ids: Collection<String>): Map<String, RemoteItem?> = ids.associateWith { id ->
        items[id]?.let { RemoteItem(id, it.path.substringAfterLast('/'), it.bytes.size.toLong(), QuickXorHash.of(it.bytes)) }
    }

    override suspend fun listFolder(path: String): List<RemoteEntry>? {
        val children = items.entries.filter { parentPath(it.value.path).equals(path, ignoreCase = true) }
            .map { RemoteEntry(it.key, it.value.path.substringAfterLast('/'), it.value.bytes.size.toLong(), it.value.folder) }
        val folders = items.values.flatMap { ancestors(it.path) }.filter { parentPath(it).equals(path, ignoreCase = true) }
            .distinct()
            .filter { f -> children.none { c -> c.isFolder && "$path/${c.name}".equals(f, ignoreCase = true) } }
            .map { RemoteEntry("folder:$it", it.substringAfterLast('/'), 0, true) }
        return children + folders
    }

    override suspend fun ensureFolder(path: String): String = idOfPath(path) ?: run {
        val id = "D${next++}"
        items[id] = Item(path, ByteArray(0), folder = true)
        id
    }

    override suspend fun parentOf(itemId: String): String? {
        val item = items[itemId] ?: return null
        return idOfPath(parentPath(item.path)) ?: "folder:${parentPath(item.path)}"
    }

    override suspend fun moveInto(itemId: String?, path: String?, folderId: String, name: String): MirrorMove {
        val id = itemId?.takeIf { items.containsKey(it) } ?: path?.let { idOfPath(it) } ?: return MirrorMove.Gone
        val folder = items[folderId] ?: return MirrorMove.Gone
        val target = "${folder.path}/$name"
        if (idOfPath(target) != null) return MirrorMove.NameTaken
        items[id]!!.path = target
        return MirrorMove.Moved
    }

    override suspend fun download(itemId: String?, path: String?, size: Long?, to: File, onProgress: ((Long) -> Unit)?): Boolean {
        val item = itemId?.let { items[it] } ?: path?.let { p -> idOfPath(p)?.let { items[it] } } ?: return false
        to.parentFile?.mkdirs()
        to.writeBytes(item.bytes)
        onProgress?.invoke(item.bytes.size.toLong())
        return true
    }
}
