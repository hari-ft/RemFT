package com.ftapps.remft.ui.notes.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NoteExportFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rig = TestFiles()

    @After
    fun tearDown() = rig.close()

    @Test
    fun aNewExportKeepsARecentOneThatAnotherAppMayStillBeReading() = runBlocking {
        val exporter = NoteExporter(context, rig.notes)
        val stale = File(File(context.cacheDir, NoteExporter.EXPORT_DIR).apply { mkdirs() }, "stale.md").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        }
        val first = exporter.export(NoteExportFormat.Markdown, "Packing list", "# Tent", null, DateFormatId.System)
        val second = exporter.export(NoteExportFormat.Docx, "Shopping", "Milk", null, DateFormatId.System)
        assertTrue(first.file.isFile)
        assertTrue(second.file.isFile)
        assertFalse(stale.exists())
    }

    @Test
    fun aSecondExportWithTheSameTitleDoesNotOverwriteTheFirst() = runBlocking {
        val exporter = NoteExporter(context, rig.notes)
        val first = exporter.export(NoteExportFormat.Markdown, "Packing list", "# Tent", null, DateFormatId.System)
        val second = exporter.export(NoteExportFormat.Markdown, "Packing list", "# Stove", null, DateFormatId.System)
        assertEquals(first.file.name, second.file.name)
        assertNotEquals(first.file, second.file)
        assertTrue(first.file.readText().contains("Tent"))
        assertTrue(second.file.readText().contains("Stove"))
    }
}
