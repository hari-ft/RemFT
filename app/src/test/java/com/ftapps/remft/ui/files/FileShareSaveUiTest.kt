package com.ftapps.remft.ui.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class FileShareSaveUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val messages = mutableListOf<String>()
    private val saved = mutableListOf<Triple<String, String, SaveTarget>>()
    private val savedText = mutableListOf<String>()
    private var failSave = false
    private var showing by mutableStateOf(true)

    private fun env(saver: DeviceSaver?) = FilesEnvironment(
        attachments = rig.attachments,
        files = rig.files,
        bin = rig.bin,
        settings = rig.settings,
        authority = rig.environment.authority,
        clock = rig.clock,
        deviceSaver = saver,
        uriForFile = { _, authority, file -> Uri.Builder().scheme("content").authority(authority).appendPath("shared_files").appendPath(file.name).build() },
    )

    private val environment = env { source, name, mime, target ->
        if (failSave) throw IOException("no space")
        saved += Triple(name, mime, target)
        savedText += source.readText()
    }

    @After
    fun tearDown() {
        showing = false
        runCatching { compose.waitForIdle() }
        rig.close()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun sheetFor(name: String, mime: String): String {
        val id = rig.add(name, text = "bytes-of-$name", mime = mime)
        val row = runBlocking { rig.files.file(id)!! }
        val state = FileUiState().apply { dialog = FileDialog.Actions(row) }
        compose.setContent {
            RemftTheme { if (showing) FileDialogsHost(state, environment, emptyList()) { messages += it } }
        }
        waitFor { exists("file-actions") }
        return id
    }

    private fun viewerFor(name: String): String {
        val id = rig.add(name, text = "bytes-of-$name", mime = "image/png")
        compose.setContent {
            RemftTheme { if (showing) ImageViewer(ViewerRequest(listOf(id), 0), environment, onClose = {}) { messages += it } }
        }
        waitFor { exists("viewer-title") && compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true)

    @Test
    fun anImageSheetOffersShareAndSaveToGalleryAndSavesUnderPictures() {
        sheetFor("Beach.png", "image/png")
        compose.onNodeWithTag("file-action-share").assertExists()
        compose.onNodeWithTag("file-action-open").assertExists()
        compose.onNodeWithText("Save to gallery").assertExists()
        compose.onNodeWithTag("file-action-save").performClick()
        waitFor { messages.isNotEmpty() }
        assertEquals(listOf(Triple("Beach.png", "image/png", SaveTarget.Pictures)), saved)
        assertEquals(listOf("bytes-of-Beach.png"), savedText)
        assertEquals(listOf("Saved to Pictures/RemFT"), messages)
    }

    @Test
    fun aVideoIsSavedUnderMovies() {
        sheetFor("Clip.mp4", "video/mp4")
        compose.onNodeWithText("Save to gallery").assertExists()
        compose.onNodeWithTag("file-action-save").performClick()
        waitFor { messages.isNotEmpty() }
        assertEquals(SaveTarget.Movies, saved.single().third)
        assertEquals(listOf("Saved to Movies/RemFT"), messages)
    }

    @Test
    fun anyOtherFileOffersSaveToDownloadsAndAFailureSaysSo() {
        failSave = true
        sheetFor("Bill.pdf", "application/pdf")
        compose.onNodeWithText("Save to Downloads").assertExists()
        assertTrue(compose.onAllNodesWithText("Save to gallery").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("file-action-save").performClick()
        waitFor { messages.isNotEmpty() }
        assertTrue(saved.isEmpty())
        assertEquals(listOf("Could not save a copy to this phone"), messages)
    }

    @Test
    fun shareFromTheSheetOpensTheShareChooser() {
        sheetFor("Bill.pdf", "application/pdf")
        compose.onNodeWithTag("file-action-share").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        var started: Intent? = null
        waitFor {
            started = started ?: shadowOf(app).nextStartedActivity
            started != null
        }
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        val send = IntentCompat.getParcelableExtra(started!!, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(!exists("file-actions"))
    }

    @Test
    fun theViewerSharesTheShownImage() {
        viewerFor("Sunset.png")
        compose.onNodeWithTag("viewer-share").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        var started: Intent? = null
        waitFor {
            started = started ?: shadowOf(app).nextStartedActivity
            started != null
        }
        val send = IntentCompat.getParcelableExtra(started!!, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
    }

    @Test
    fun theViewerSavesTheShownImageToTheGallery() {
        viewerFor("Sunset.png")
        compose.onNodeWithTag("viewer-save").performClick()
        waitFor { messages.isNotEmpty() }
        assertEquals(listOf(Triple("Sunset.png", "image/png", SaveTarget.Pictures)), saved)
        assertEquals(listOf("Saved to Pictures/RemFT"), messages)
    }

    @Test
    @Config(sdk = [28])
    fun onOlderPhonesSavingAsksWhereToPutTheCopyWithItsName() {
        val id = rig.add("Beach.png", mime = "image/png")
        val row = runBlocking { rig.files.file(id)!! }
        val state = FileUiState().apply { dialog = FileDialog.Actions(row) }
        compose.setContent {
            RemftTheme { if (showing) FileDialogsHost(state, env(null), emptyList()) { messages += it } }
        }
        waitFor { exists("file-actions") }
        compose.onNodeWithTag("file-action-save").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        var started: Intent? = null
        waitFor {
            started = started ?: shadowOf(app).nextStartedActivity
            started != null
        }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started!!.action)
        assertEquals("image/png", started!!.type)
        assertEquals("Beach.png", started!!.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun aViewerSaveThatFailsSaysSo() {
        failSave = true
        viewerFor("Sunset.png")
        compose.onNodeWithTag("viewer-save").performClick()
        waitFor { messages.isNotEmpty() }
        assertEquals(listOf("Could not save a copy to this phone"), messages)
    }
}
