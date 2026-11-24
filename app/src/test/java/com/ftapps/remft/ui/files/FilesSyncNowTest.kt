package com.ftapps.remft.ui.files

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.mirror.MirrorPhase
import com.ftapps.remft.data.mirror.MirrorProgress
import com.ftapps.remft.data.onedrive.OneDriveSync
import com.ftapps.remft.data.onedrive.SyncNowResult
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

class FakeOneDriveSync : OneDriveSync {
    override val available = MutableStateFlow(true)
    override val running = MutableStateFlow(false)
    override val progress = MutableStateFlow<MirrorProgress?>(null)
    val emitted = MutableSharedFlow<SyncNowResult>(extraBufferCapacity = 8)
    override val results = emitted
    override val lastSynced = MutableStateFlow<Instant?>(null)
    var starts = 0

    override fun start(): Boolean {
        starts++
        return true
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class FilesSyncNowTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val sync = FakeOneDriveSync()
    private var showing by mutableStateOf(true)
    private var oneDriveOpened = 0

    @After
    fun tearDown() {
        showing = false
        runCatching { compose.waitForIdle() }
        rig.close()
    }

    private fun showFiles() {
        val env = FilesEnvironment(
            attachments = rig.attachments,
            files = rig.files,
            bin = rig.bin,
            settings = rig.settings,
            authority = "com.ftapps.remft.files",
            clock = rig.clock,
            oneDriveSync = sync,
        )
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides env) {
                    if (showing) FilesScreen(env, 0.dp, {}, {}, {}, onOpenOneDrive = { oneDriveOpened++ })
                }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSyncIconShowsOnlyWhileOneDriveIsOn() {
        showFiles()
        waitFor { exists("files-sync-now") }
        compose.onNodeWithTag("files-sync-now").performClick()
        assertEquals(1, sync.starts)
        sync.available.value = false
        waitFor { !exists("files-sync-now") }
        sync.available.value = true
        waitFor { exists("files-sync-now") }
    }

    @Test
    fun aRunningSyncShowsASpinnerAndTheProgressStrip() {
        showFiles()
        waitFor { exists("files-sync-now") }
        sync.running.value = true
        sync.progress.value = MirrorProgress(MirrorPhase.Uploading, done = 2, total = 12, name = "invoice.pdf")
        waitFor { exists("files-sync-now-running") && exists("files-sync-progress") }
        waitFor { hasText("Uploading 3 of 12 · invoice.pdf") }
        assertEquals(false, exists("files-sync-now"))
        sync.progress.value = MirrorProgress(MirrorPhase.Checking)
        waitFor { hasText("Checking OneDrive…") }
        sync.running.value = false
        sync.progress.value = null
        waitFor { exists("files-sync-now") && !exists("files-sync-progress") }
    }

    @Test
    fun aSignedOutResultOffersConnect() {
        showFiles()
        waitFor { exists("files-sync-now") }
        sync.emitted.tryEmit(SyncNowResult.SignedOut)
        waitFor { hasText("OneDrive is not connected — connect it to sync") }
        compose.onNodeWithText("Connect").performClick()
        waitFor { oneDriveOpened == 1 }
    }

    @Test
    fun theSummaryAppearsWhenTheSyncEnds() {
        showFiles()
        waitFor { exists("files-sync-now") }
        sync.emitted.tryEmit(SyncNowResult.Synced(uploaded = 3, moved = 2, failed = 1))
        waitFor { hasText("Synced: 3 uploaded, 2 moved, 1 failed") }
        compose.onNodeWithText("Synced: 3 uploaded, 2 moved, 1 failed").assertExists()
        compose.onNodeWithTag("screen-files").assertExists()
    }
}
