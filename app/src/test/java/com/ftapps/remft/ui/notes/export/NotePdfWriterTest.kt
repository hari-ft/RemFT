package com.ftapps.remft.ui.notes.export

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.notes.export.NoteBlock
import com.ftapps.remft.domain.notes.export.NoteExportLabels
import com.ftapps.remft.domain.notes.export.NoteSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotePdfWriterTest {
    private val labels = NoteExportLabels("Note", "Tip", "Important", "Warning", "Caution", "Missing", "Unsupported")

    private val tallCell = (1..2500).joinToString(" ") { "word$it" }

    private fun tallTable() = NoteBlock.Table(
        listOf(
            listOf("Name", "Details"),
            listOf("first", tallCell),
            listOf("second", "short"),
        ),
    )

    @Test
    fun aTableRowTallerThanAPageIsSplitAcrossPagesWithinTheMargins() {
        val measured = NotePdfWriter.measure("Report", null, listOf(tallTable()), labels) { null }
        assertTrue("pages ${measured.pages}", measured.pages >= 3)
        assertTrue("overflow ${measured.overflow}", measured.overflow <= 0.5f)
        assertEquals(measured.pages - 1, measured.repeatedHeaders)
    }

    @Test
    fun aShortRowThatDoesNotFitMovesWholeToTheNextPage() {
        val filler = NoteBlock.Paragraph(listOf(NoteSpan((1..420).joinToString(" ") { "fill$it" })))
        val table = NoteBlock.Table((0..30).map { listOf("row $it", "value $it") })
        val measured = NotePdfWriter.measure("Report", null, listOf(filler, table), labels) { null }
        assertTrue("overflow ${measured.overflow}", measured.overflow <= 0.5f)
    }

    @Test
    fun imagesAreDecodedPerUseAndNotHeldForTheWholeExport() {
        val handed = ArrayList<Bitmap>()
        val images = NotePdfWriter.PassImages { id ->
            if (id == "missing") null else Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { handed.add(it) }
        }
        val blocks = listOf(NoteBlock.Image("a", "first"), NoteBlock.Image("b", "second"), NoteBlock.Image("missing", "gone"))
        NotePdfWriter.measure("Photos", null, blocks, labels, images::forMeasure)
        handed.forEach { it.recycle() }
        val drawnA = images.forDraw("a")!!
        val drawnB = images.forDraw("b")!!
        assertTrue(!drawnA.isRecycled && !drawnB.isRecycled)
        assertEquals(null, images.forDraw("missing"))
        assertEquals(4, handed.size)
    }
}
