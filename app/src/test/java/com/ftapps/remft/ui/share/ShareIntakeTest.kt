package com.ftapps.remft.ui.share

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.files.PickedFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

class FakeShareProvider : ContentProvider() {
    data class Entry(val name: String, val mime: String, val bytes: ByteArray)

    val entries = mutableMapOf<String, Entry>()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val entry = entries[uri.lastPathSegment] ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(entry.name, entry.bytes.size.toLong())) }
    }

    override fun getType(uri: Uri): String? = entries[uri.lastPathSegment]?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = entries[uri.lastPathSegment] ?: throw FileNotFoundException("gone")
        val file = File.createTempFile("served", ".bin").apply { writeBytes(entry.bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.ftapps.remft.test.share"

        fun install(): FakeShareProvider =
            Robolectric.buildContentProvider(FakeShareProvider::class.java).create(ProviderInfo().apply { authority = AUTHORITY }).get()

        fun uri(key: String): Uri = Uri.parse("content://$AUTHORITY/item/$key")
    }
}

@RunWith(AndroidJUnit4::class)
class ShareIntakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeShareProvider
    private val root = File(context.cacheDir, "share-in-test-${System.nanoTime()}")

    @Before
    fun setUp() {
        provider = FakeShareProvider.install()
    }

    private fun serve(key: String, name: String, mime: String, text: String): Uri {
        provider.entries[key] = FakeShareProvider.Entry(name, mime, text.toByteArray())
        return FakeShareProvider.uri(key)
    }

    private fun copier(maxBytes: Long = 1024): ShareCopier = ShareCopier({ context.contentResolver }, { PickedFiles.fromUri(context, it) }, root, maxBytes)

    private fun send(uri: Uri): Intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)

    @Test
    fun aSingleSharedFileIsRead() {
        val uri = FakeShareProvider.uri("a")
        val share = ShareIntents.parse(send(uri))!!
        assertEquals(listOf(uri), share.uris)
        assertNull(share.text)
    }

    @Test
    fun severalSharedFilesAreReadOnceEach() {
        val a = FakeShareProvider.uri("a")
        val b = FakeShareProvider.uri("b")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, a))
        assertEquals(listOf(a, b), ShareIntents.parse(intent)!!.uris)
    }

    @Test
    fun aStreamOnlyInTheClipIsStillFound() {
        val a = FakeShareProvider.uri("a")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").apply { clipData = ClipData.newRawUri(null, a) }
        assertEquals(listOf(a), ShareIntents.parse(intent)!!.uris)
    }

    @Test
    fun sharedTextIsKeptWhenThereAreNoFiles() {
        val share = ShareIntents.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  Buy milk\n"))!!
        assertTrue(share.uris.isEmpty())
        assertEquals("Buy milk", share.text)
        val both = ShareIntents.parse(send(FakeShareProvider.uri("a")).putExtra(Intent.EXTRA_TEXT, "caption"))!!
        assertNull(both.text)
    }

    @Test
    fun anEmptyShareIsRecognisedAndOtherIntentsAreIgnored() {
        assertTrue(ShareIntents.parse(Intent(Intent.ACTION_SEND).setType("image/png"))!!.isEmpty)
        assertNull(ShareIntents.parse(Intent(Intent.ACTION_VIEW, Uri.parse("remft://open"))))
        assertNull(ShareIntents.parse(null))
    }

    @Test
    fun aColdStartTakesTheShareButARestoreOrHistoryLaunchDoesNot() {
        val uri = FakeShareProvider.uri("a")
        assertEquals(listOf(uri), ShareIntents.atLaunch(send(uri), restored = false)!!.uris)
        assertNull(ShareIntents.atLaunch(send(uri), restored = true))
        assertNull(ShareIntents.atLaunch(send(uri).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), restored = false))
    }

    @Test
    fun sharedContentIsCopiedPromptlyWithItsNameAndType() {
        val uri = serve("a", "Beach.png", "image/png", "pixels")
        val intake = runBlocking { copier().copy(IncomingShare(listOf(uri), null)) }
        val item = intake.items.single()
        assertEquals("Beach.png", item.name)
        assertEquals("image/png", item.mime)
        assertEquals(6L, item.sizeBytes)
        assertEquals("pixels", item.file.readText())
        assertTrue(item.file.canonicalPath.startsWith(root.canonicalPath))
        assertTrue(intake.problems.isEmpty())
    }

    @Test
    fun unreadableTooLargeAndNonContentItemsAreReportedNotDropped() {
        val good = serve("good", "notes.txt", "text/plain", "ok")
        val big = serve("big", "movie.mp4", "video/mp4", "x".repeat(2000))
        val gone = FakeShareProvider.uri("gone")
        val local = Uri.fromFile(File(context.filesDir, "secret.db"))
        val intake = runBlocking { copier().copy(IncomingShare(listOf(good, big, gone, local), null)) }
        assertEquals(listOf("notes.txt"), intake.items.map { it.name })
        assertEquals(listOf(IntakeProblem.TooLarge, IntakeProblem.Unreadable, IntakeProblem.Unsupported), intake.problems)
        assertEquals(1, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun aStreamThatGrowsPastTheLimitIsStoppedAndRemoved() {
        val described = { _: Uri -> com.ftapps.remft.ui.files.PickedFile("huge.bin", null, "application/octet-stream", { "y".repeat(5000).byteInputStream() }) }
        val uri = serve("huge", "huge.bin", "application/octet-stream", "y".repeat(5000))
        val intake = runBlocking { ShareCopier({ context.contentResolver }, described, root, 1024).copy(IncomingShare(listOf(uri), null)) }
        assertTrue(intake.items.isEmpty())
        assertEquals(listOf(IntakeProblem.TooLarge), intake.problems)
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun discardingRemovesTheCopies() {
        val copier = copier()
        val intake = runBlocking { copier.copy(IncomingShare(listOf(serve("a", "a.txt", "text/plain", "1"), serve("b", "b.txt", "text/plain", "2")), null)) }
        assertEquals(2, intake.items.size)
        copier.discard(intake)
        assertTrue(intake.items.none { it.file.exists() })
    }

    @Test
    fun theInboxCopiesThenOffersTheItemsAndCancelCleansUp() {
        val copier = copier()
        val inbox = ShareInbox(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), copier)
        inbox.receive(IncomingShare(listOf(serve("a", "a.txt", "text/plain", "1")), null))
        waitUntil { inbox.state.value is ShareState.Ready }
        val file = (inbox.state.value as ShareState.Ready).intake.items.single().file
        assertTrue(file.exists())
        inbox.dismiss()
        assertNull(inbox.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun handedOverItemsAreKeptUntilTheirUploadCleansThem() {
        val inbox = ShareInbox(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), copier())
        inbox.receive(IncomingShare(listOf(serve("a", "a.txt", "text/plain", "hello")), null))
        waitUntil { inbox.state.value is ShareState.Ready }
        val picks = inbox.handOff()
        assertNull(inbox.state.value)
        val pick = picks.single()
        assertEquals("a.txt", pick.name)
        assertEquals("hello", pick.open().use { it.reader().readText() })
        inbox.dismiss()
        assertEquals("hello", pick.open().use { it.reader().readText() })
        pick.cleanup()
        assertTrue(runCatching { pick.open() }.isFailure)
    }

    @Test
    fun aNewShareReplacesAnUnfinishedOneAndDropsItsCopies() {
        val inbox = ShareInbox(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), copier())
        inbox.receive(IncomingShare(listOf(serve("a", "a.txt", "text/plain", "1")), null))
        waitUntil { inbox.state.value is ShareState.Ready }
        val first = (inbox.state.value as ShareState.Ready).intake.items.single().file
        inbox.receive(IncomingShare(listOf(serve("b", "b.txt", "text/plain", "2")), null))
        waitUntil { inbox.state.value is ShareState.Ready }
        assertFalse(first.exists())
        assertEquals("b.txt", (inbox.state.value as ShareState.Ready).intake.items.single().name)
    }

    @Test
    fun cancellingWhileCopyingThrowsAwayTheUnfinishedCopy() {
        val gate = CompletableDeferred<Unit>()
        var discarded = 0
        val inbox = ShareInbox(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            copy = { gate.await(); Intake(emptyList(), emptyList(), null) },
            discardFiles = { discarded++ },
        )
        inbox.receive(IncomingShare(listOf(FakeShareProvider.uri("a")), null))
        assertEquals(ShareState.Copying(1), inbox.state.value)
        inbox.dismiss()
        gate.complete(Unit)
        assertNull(inbox.state.value)
        assertEquals(0, discarded)
    }

    @Test
    fun aFailingCopyIsShownAsUnreadableItems() {
        val inbox = ShareInbox(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), copy = { error("boom") }, discardFiles = {})
        inbox.receive(IncomingShare(listOf(FakeShareProvider.uri("a"), FakeShareProvider.uri("b")), null))
        val ready = inbox.state.value as ShareState.Ready
        assertEquals(listOf(IntakeProblem.Unreadable, IntakeProblem.Unreadable), ready.intake.problems)
    }

    @Test
    fun aTextShareBecomesAOneLineTaskTitle() {
        assertEquals("Buy milk and eggs", ShareDestinations.taskTitleFrom("Buy milk\n  and   eggs\n"))
        assertTrue(ShareDestinations.matches("Pay rent", "RENT"))
        assertFalse(ShareDestinations.matches("Pay rent", "water"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
