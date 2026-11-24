package com.ftapps.remft.ui.files

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.R
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

class FakeMediaProvider : ContentProvider() {
    val inserted = mutableListOf<Pair<Uri, ContentValues>>()
    val updated = mutableListOf<Pair<Uri, ContentValues>>()
    val deleted = mutableListOf<Uri>()
    val files = mutableMapOf<Uri, File>()
    var refuseInsert = false
    var refuseWrite = false

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (refuseInsert) return null
        val made = uri.buildUpon().appendPath((inserted.size + 1).toString()).build()
        inserted += made to ContentValues(values)
        files[made] = File.createTempFile("media", ".bin")
        return made
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (refuseWrite) throw IOException("disk full")
        val file = files[uri] ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        updated += uri to ContentValues(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted += uri
        return 1
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
}

@RunWith(AndroidJUnit4::class)
class FileExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var rig: TestFiles? = null

    @After
    fun tearDown() {
        rig?.close()
    }

    private fun files(): TestFiles = TestFiles().also { rig = it }

    private fun media(): FakeMediaProvider =
        Robolectric.buildContentProvider(FakeMediaProvider::class.java).create(ProviderInfo().apply { authority = MediaStore.AUTHORITY }).get()

    private fun source(text: String = "pixels"): File = File.createTempFile("source", ".bin").apply { writeText(text) }

    @Test
    fun imagesGoToPicturesVideosToMoviesAndEverythingElseToDownloads() {
        assertEquals(SaveTarget.Pictures, SaveTarget.of("image/jpeg"))
        assertEquals(SaveTarget.Movies, SaveTarget.of("video/mp4"))
        assertEquals(SaveTarget.Downloads, SaveTarget.of("application/pdf"))
        assertEquals("Pictures/RemFT", SaveTarget.Pictures.relativePath)
        assertEquals("Movies/RemFT", SaveTarget.Movies.relativePath)
        assertEquals("Download/RemFT", SaveTarget.Downloads.relativePath)
        assertEquals(R.string.sharesave_saveToGallery, SaveTarget.Pictures.label)
        assertEquals(R.string.sharesave_saveToGallery, SaveTarget.Movies.label)
        assertEquals(R.string.sharesave_saveToDownloads, SaveTarget.Downloads.label)
    }

    @Test
    fun eachTargetWritesIntoItsOwnMediaStoreCollection() {
        assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), SaveTarget.Pictures.collection())
        assertEquals(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), SaveTarget.Movies.collection())
        assertEquals(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), SaveTarget.Downloads.collection())
    }

    @Test
    fun savingWritesAPendingEntryWithNameMimeAndPathThenPublishesIt() {
        val provider = media()
        MediaStoreSaver(context.contentResolver).save(source("photo-bytes"), "Holiday.jpg", "image/jpeg", SaveTarget.Pictures)
        val (uri, values) = provider.inserted.single()
        assertTrue(uri.toString().startsWith(SaveTarget.Pictures.collection().toString()))
        assertEquals("Holiday.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/RemFT", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("photo-bytes", provider.files.getValue(uri).readText())
        assertEquals(0, provider.updated.single().second.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun otherFilesAreSavedUnderDownloadRemft() {
        val provider = media()
        MediaStoreSaver(context.contentResolver).save(source(), "Bill.pdf", "application/pdf", SaveTarget.Downloads)
        val (uri, values) = provider.inserted.single()
        assertTrue(uri.toString().startsWith(SaveTarget.Downloads.collection().toString()))
        assertEquals("Download/RemFT", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("Bill.pdf", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
    }

    @Test
    fun aRefusedInsertFails() {
        media().refuseInsert = true
        val failure = runCatching { MediaStoreSaver(context.contentResolver).save(source(), "a.png", "image/png", SaveTarget.Pictures) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun aFailedWriteRemovesOnlyItsOwnUnfinishedEntry() {
        val provider = media()
        provider.refuseWrite = true
        val failure = runCatching { MediaStoreSaver(context.contentResolver).save(source(), "a.png", "image/png", SaveTarget.Pictures) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(listOf(provider.inserted.single().first), provider.deleted)
        assertTrue(provider.updated.isEmpty())
    }

    @Test
    fun aSuccessfulSaveReportsWhereItWentAndAFailureSaysSo() {
        val r = files()
        val row = runBlocking { r.files.file(r.add("clip.mp4", mime = "video/mp4"))!! }
        val file = source()
        var saw: Triple<String, String, SaveTarget>? = null
        val ok = runBlocking { FileExport.saveWith({ s, name, mime, target -> saw = Triple(name, mime, target); assertEquals(file, s) }, file, row) }
        assertEquals(R.string.sharesave_savedMovies, ok)
        assertEquals(Triple(file.name, "video/mp4", SaveTarget.Movies), saw)
        val bad = runBlocking { FileExport.saveWith({ _, _, _, _ -> throw IOException("no space") }, file, row) }
        assertEquals(R.string.sharesave_saveFailed, bad)
    }

    @Test
    fun sharingSendsTheFileThroughTheFileProviderWithAReadGrant() {
        val r = files()
        val id = r.add("Report.pdf", text = "report", mime = "application/pdf")
        val row = runBlocking { r.files.file(id)!! }
        var shared: File? = null
        val env = FilesEnvironment(
            attachments = r.attachments,
            files = r.files,
            bin = r.bin,
            settings = r.settings,
            authority = r.environment.authority,
            uriForFile = { _, authority, file ->
                shared = file
                Uri.Builder().scheme("content").authority(authority).appendPath("shared_files").appendPath(file.name).build()
            },
        )
        val outcome = runBlocking { FileOpener.share(context, env, row, "Share") }
        assertEquals(OpenOutcome.Opened, outcome)
        val chooser = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        val stream = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", stream.scheme)
        assertEquals(r.environment.authority, stream.authority)
        assertEquals("Report.pdf", stream.lastPathSegment)
        assertEquals(stream, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("report", shared!!.readText())
        assertEquals("Report.pdf", shared!!.name)
    }

    @Test
    fun aMissingFileIsNotSharedAndSaysWhy() {
        val r = files()
        val id = r.add("gone.txt")
        File(r.dir, r.storedName(id)).delete()
        val outcome = runBlocking { FileOpener.share(context, r.environment, r.files.file(id)!!, null) }
        assertEquals(OpenOutcome.Missing, outcome)
        assertEquals(R.string.error_FILE_MISSING, FileExport.shareMessageOf(outcome))
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun failuresHaveShareAndSaveSpecificMessages() {
        assertEquals(R.string.sharesave_couldNotShare, FileExport.shareMessageOf(OpenOutcome.Failed))
        assertEquals(R.string.sharesave_saveFailed, FileExport.saveMessageOf(OpenOutcome.Failed))
        assertEquals(R.string.mirror_fetchOffline, FileExport.saveMessageOf(OpenOutcome.Offline))
        assertNull(FileExport.shareMessageOf(OpenOutcome.Opened))
    }

    @Test
    fun newerPhonesUseTheMediaStoreSaver() {
        assertTrue(FileExport.saverFor(files().environment, context) is MediaStoreSaver)
    }

    @Test
    @Config(sdk = [28])
    fun olderPhonesAskWhereToSaveInsteadOfNeedingAStoragePermission() {
        assertNull(FileExport.saverFor(files().environment, context))
        val intent = CreateDocumentFor.createIntent(context, SaveRequest("Holiday.jpg", "image/jpeg"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals("Holiday.jpg", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        val chosen = File.createTempFile("chosen", ".jpg")
        CreateDocumentFor.copy(context.contentResolver, Uri.fromFile(chosen), source("copied"))
        assertEquals("copied", chosen.readText())
        assertNull(CreateDocumentFor.parseResult(android.app.Activity.RESULT_CANCELED, Intent().setData(Uri.fromFile(chosen))))
        assertEquals(Uri.fromFile(chosen), CreateDocumentFor.parseResult(android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(chosen))))
    }

    @Test
    fun theSavedMessagesNameTheFolders() {
        assertEquals("Saved to Pictures/RemFT", context.getString(SaveTarget.Pictures.saved))
        assertEquals("Saved to Movies/RemFT", context.getString(SaveTarget.Movies.saved))
        assertEquals("Saved to Download/RemFT", context.getString(SaveTarget.Downloads.saved))
        assertFalse(context.getString(R.string.sharesave_saveFailed).isBlank())
    }
}
