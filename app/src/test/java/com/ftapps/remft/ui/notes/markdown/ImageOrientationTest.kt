package com.ftapps.remft.ui.notes.markdown

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.notes.export.NoteDocx
import com.ftapps.remft.ui.notes.export.uprightImageBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun photo(orientation: Int?): File {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) for (y in 0 until 20) bitmap.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        val file = File(folder.newFolder(), "photo.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun isRed(color: Int) = Color.red(color) > 180 && Color.blue(color) < 80

    private fun isBlue(color: Int) = Color.blue(color) > 180 && Color.red(color) < 80

    @Test
    fun aPhotoTakenSidewaysIsDecodedUpright() {
        val decoded = decodeSampled(photo(ExifInterface.ORIENTATION_ROTATE_90), 1600)!!
        assertEquals(20, decoded.width)
        assertEquals(40, decoded.height)
        assertTrue(isRed(decoded.getPixel(10, 5)))
        assertTrue(isBlue(decoded.getPixel(10, 35)))
    }

    @Test
    fun rotate270AndMirroredOrientationsAreApplied() {
        val turned = decodeSampled(photo(ExifInterface.ORIENTATION_ROTATE_270), 1600)!!
        assertEquals(20, turned.width)
        assertTrue(isBlue(turned.getPixel(10, 5)))
        assertTrue(isRed(turned.getPixel(10, 35)))
        val mirrored = decodeSampled(photo(ExifInterface.ORIENTATION_FLIP_HORIZONTAL), 1600)!!
        assertEquals(40, mirrored.width)
        assertTrue(isBlue(mirrored.getPixel(5, 10)))
    }

    @Test
    fun aPhotoWithoutOrientationIsLeftAlone() {
        val decoded = decodeSampled(photo(null), 1600)!!
        assertEquals(40, decoded.width)
        assertTrue(isRed(decoded.getPixel(5, 10)))
        val file = photo(null)
        assertArrayEquals(file.readBytes(), uprightImageBytes(file))
    }

    @Test
    fun docxBytesAreRotatedUprightWithPortraitExtents() {
        val file = photo(ExifInterface.ORIENTATION_ROTATE_90)
        val bytes = uprightImageBytes(file)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(20, decoded.width)
        assertEquals(40, decoded.height)
        assertTrue(isRed(decoded.getPixel(10, 5)))
        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertNotEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
        assertEquals(NoteDocx.PictureSize(20, 40, "jpeg"), NoteDocx.pictureSize(bytes))
    }
}
