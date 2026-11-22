package com.ftapps.remft.ui.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.notes.export.NoteExportViewModel
import com.ftapps.remft.ui.notes.export.NoteExporter
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp", shadows = [NoteCameraRestoreTest.PlainUriFileProvider::class], instrumentedPackages = ["androidx.core.content"])
class NoteCameraRestoreTest {
    @Implements(FileProvider::class)
    class PlainUriFileProvider {
        companion object {
            @JvmStatic
            @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.fromFile(file)
        }
    }

    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = rig.close()

    private class RecordingRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        var input: Any? = null

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
            this.input = input
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aPhotoTakenWhileTheActivityIsRecreatedStillReachesTheNote() {
        val noteId = runBlocking { rig.notes.create("Trip", "body") }
        val environment = rig.environment.let {
            FilesEnvironment(it.attachments, it.files, it.bin, it.settings, BuildConfig.FILES_AUTHORITY, clock = it.clock)
        }
        val viewModel = NoteEditorViewModel(
            SavedStateHandle(mapOf("id" to noteId, "external" to false)),
            rig.notes,
            ExternalDocumentStore(context, rig.settings),
            rig.settings,
            rig.tasks.environment,
        )
        val export = NoteExportViewModel(NoteExporter(context, rig.notes), rig.notes, rig.settings)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalTasksEnvironment provides rig.tasks.environment,
                LocalFilesEnvironment provides environment,
            ) {
                RemftTheme { NoteEditorScreen(viewModel, export, onBack = {}) }
            }
        }
        waitFor { exists("note-quick-menu") }
        compose.onNodeWithTag("note-quick-menu").performClick()
        waitFor { exists("note-attachments") }
        compose.onNodeWithTag("note-attachments").performClick()
        waitFor { exists("attach-button") }
        compose.onNodeWithTag("attach-button").performClick()
        waitFor { exists("attach-source-camera") }
        compose.onNodeWithTag("attach-source-camera").performClick()
        waitFor { registry.requestCode != null }
        val capture = File((registry.input as Uri).path!!)
        assertEquals(File(context.cacheDir, "camera").canonicalPath, capture.parentFile!!.canonicalPath)
        capture.writeBytes(byteArrayOf(1, 2, 3, 4))

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(registry.requestCode!!, Activity.RESULT_OK, Intent()) }

        waitFor { rig.query("SELECT id FROM file_rows WHERE target_type = 'note' AND target_id = '$noteId'").isNotEmpty() }
        assertEquals(1, rig.query("SELECT id FROM file_rows WHERE target_id = '$noteId'").size)
    }
}
