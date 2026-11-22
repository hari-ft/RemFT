package com.ftapps.remft.ui.files

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImageViewerFetchTest {
    @get:Rule
    val compose = createComposeRule()

    private var rig: TestFiles? = null

    @After
    fun tearDown() {
        rig?.close()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun rigWith(fetch: suspend (TestFiles, String) -> File?): TestFiles {
        lateinit var made: TestFiles
        made = TestFiles(fetchMissing = { fetch(made, it) }, canFetch = { true })
        rig = made
        return made
    }

    private fun onlyInOneDrive(r: TestFiles): Pair<String, ByteArray> {
        val id = r.add("photo.png", mime = "image/png")
        val local = File(r.dir, r.query("SELECT local_path FROM file_rows WHERE id = '$id'").single()["local_path"]!!)
        val bytes = local.readBytes()
        local.delete()
        return id to bytes
    }

    private fun show(r: TestFiles, id: String) {
        compose.setContent { RemftTheme { ImageViewer(ViewerRequest(listOf(id), 0), r.environment, onClose = {}, onMessage = {}) } }
    }

    @Test
    fun anImageOnlyInOneDriveIsDownloadedWithProgressThenShown() {
        val gate = CountDownLatch(1)
        var asked: String? = null
        lateinit var bytes: ByteArray
        val r = rigWith { files, id ->
            asked = id
            gate.await(10, TimeUnit.SECONDS)
            File(files.dir, files.files.file(id)!!.localPath!!).apply { writeBytes(bytes) }
        }
        val (id, original) = onlyInOneDrive(r)
        bytes = original
        show(r, id)
        waitFor { exists("viewer-fetching") }
        compose.onNodeWithText("Downloading this file from OneDrive…").assertExists()
        assertFalse(exists("viewer-missing"))
        gate.countDown()
        waitFor { exists("viewer-image-$id") || exists("viewer-image-failed") }
        assertEquals(id, asked)
        assertFalse(exists("viewer-fetching"))
        assertFalse(exists("viewer-missing"))
    }

    @Test
    fun withoutOneDriveTheViewerSaysHowToGetTheImage() {
        val r = rigWith { _, _ -> throw OneDriveException(OneDriveErrors.MIRROR_NEEDS_ONEDRIVE) }
        val (id, _) = onlyInOneDrive(r)
        show(r, id)
        waitFor { exists("viewer-fetch-failed") }
        compose.onNodeWithText("This file will be available after you connect OneDrive (Settings › Automatic backup).").assertExists()
        assertFalse(exists("viewer-missing"))
    }
}
