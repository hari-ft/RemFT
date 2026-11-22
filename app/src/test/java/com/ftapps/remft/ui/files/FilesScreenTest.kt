package com.ftapps.remft.ui.files

import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class FilesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val downloads = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val rig = TestFiles(downloaded = downloads)

    @After
    fun tearDown() {
        rig.close()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides rig.environment) {
                    content()
                }
            }
        }
    }

    private fun showFiles(onOpenTask: (String) -> Unit = {}) = show { FilesScreen(rig.environment, 0.dp, {}, onOpenTask, {}) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aFolderOpensWithItsCrumbAndUploadLandsInIt() {
        showFiles()
        waitFor { exists("files-empty") }
        val folder = runBlocking { rig.files.createFolder("Receipts", null) }
        waitFor { exists("folder-row-$folder") }
        compose.onNodeWithTag("folder-row-$folder").performClick()
        waitFor { exists("crumb-$folder") }

        rig.picks = listOf(PickedFile("bill.txt", 4, "text/plain", { "abcd".byteInputStream() }))
        compose.onNodeWithTag("files-upload").performClick()
        waitFor { exists("attach-source-files") }
        compose.onNodeWithTag("attach-source-files").performClick()
        waitFor { rig.query("SELECT id FROM file_rows").isNotEmpty() }
        val row = rig.query("SELECT id, folder_id, name FROM file_rows").single()
        assertEquals(folder, row["folder_id"])
        assertEquals("bill.txt", row["name"])
        waitFor { exists("file-row-${row["id"]}") }
    }

    @Test
    fun sourcesListAttachmentsWithTheirSourceAndGoToSource() {
        val task = runBlocking { rig.tasks.repository.create(TaskDraft(title = "Passport")) }
        val file = rig.add("scan.pdf", targetType = "task", targetId = task, mime = "application/pdf")
        var opened: String? = null
        showFiles(onOpenTask = { opened = it })
        compose.onNodeWithTag("layer-sources").performClick()
        waitFor { exists("file-row-$file") }
        compose.onNodeWithTag("file-badge-$file", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Task · Passport", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("file-menu-$file").performClick()
        waitFor { exists("file-action-source") }
        compose.onNodeWithTag("file-action-source").performClick()
        waitFor { opened != null }
        assertEquals(task, opened)
    }

    @Test
    fun swipeRevealsDeleteAndConfirmSendsTheFileToTheBin() {
        val file = rig.add("old.txt")
        showFiles()
        waitFor { exists("file-row-$file") }
        compose.onNodeWithTag("swipe-$file").performTouchInput { swipeLeft() }
        assertEquals(1, rig.query("SELECT id FROM file_rows").size)
        waitFor { exists("swipe-delete-$file") }
        compose.onNodeWithTag("swipe-delete-$file").performClick()
        waitFor { exists("file-delete-confirm") }
        compose.onNodeWithTag("file-delete-confirm").performClick()
        waitFor { rig.query("SELECT id FROM file_rows").isEmpty() }
        assertEquals("file", rig.query("SELECT kind FROM trash_items").single()["kind"])
    }

    @Test
    fun aFileLinkOpensItsFolderAndHighlightsIt() {
        val folder = runBlocking { rig.files.createFolder("Deep", null) }
        val file = rig.add("target.txt", folderId = folder)
        showFiles()
        waitFor { exists("folder-row-$folder") }
        rig.environment.requests.open(FileLinks.idOf("remft://file/$file"))
        waitFor { exists("crumb-$folder") && exists("file-row-$file") }
        assertEquals(null, rig.environment.requests.requests.value)
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theSortAppliesToFoldersAndFilesInsideAFolder() {
        runBlocking { rig.settings.set("remft_files_sort", "name:asc") }
        val box = runBlocking { rig.files.createFolder("Box", null) }
        val zeta = runBlocking { rig.files.createFolder("Zeta", box) }
        val alpha = runBlocking { rig.files.createFolder("Alpha", box) }
        val charlie = rig.add("charlie.txt", folderId = box)
        val able = rig.add("able.txt", folderId = box)
        val baker = rig.add("baker.txt", folderId = box)
        showFiles()
        waitFor { exists("folder-row-$box") }
        compose.onNodeWithTag("folder-row-$box").performClick()
        waitFor { exists("folder-row-$zeta") && exists("folder-row-$alpha") && exists("file-row-$charlie") && exists("file-row-$able") && exists("file-row-$baker") }
        assert(top("folder-row-$alpha") < top("folder-row-$zeta"))
        assert(top("file-row-$able") < top("file-row-$baker"))
        assert(top("file-row-$baker") < top("file-row-$charlie"))

        runBlocking { rig.settings.set("remft_files_sort", "name:desc") }
        waitFor { top("folder-row-$zeta") < top("folder-row-$alpha") }
        waitFor { top("file-row-$charlie") < top("file-row-$baker") && top("file-row-$baker") < top("file-row-$able") }
    }

    @Test
    fun theSortMenuStoresTheChoice() {
        showFiles()
        compose.onNodeWithTag("files-sort").performClick()
        compose.onNodeWithTag("files-sort-name").performClick()
        waitFor { runBlocking { rig.settings.get("remft_files_sort") } == "name:asc" }
    }

    @Test
    fun anOpenFolderSurvivesStateRestoration() {
        val bills = runBlocking { rig.files.createFolder("Bills", null) }
        val year = runBlocking { rig.files.createFolder("2026", bills) }
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides rig.environment) {
                    FilesScreen(rig.environment, 0.dp, {}, {}, {})
                }
            }
        }
        waitFor { exists("folder-row-$bills") }
        compose.onNodeWithTag("folder-row-$bills").performClick()
        waitFor { exists("folder-row-$year") }
        compose.onNodeWithTag("folder-row-$year").performClick()
        waitFor { exists("crumb-$year") }

        restorer.emulateSavedInstanceStateRestore()

        waitFor { exists("crumb-$year") }
        compose.onNodeWithTag("crumb-$bills").assertExists()
    }

    @Test
    fun aSavedFolderThatIsGoneFallsBackToTheRoot() {
        val gone = runBlocking { rig.files.createFolder("Gone", null) }
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides rig.environment) {
                    FilesScreen(rig.environment, 0.dp, {}, {}, {})
                }
            }
        }
        waitFor { exists("folder-row-$gone") }
        compose.onNodeWithTag("folder-row-$gone").performClick()
        waitFor { exists("crumb-$gone") }
        rig.exec("DELETE FROM folders WHERE id = '$gone'")
        restorer.emulateSavedInstanceStateRestore()
        waitFor { !exists("crumb-$gone") && exists("files-empty") }
    }

    @Test
    fun backClosesSearchThenGoesUpOneFolderAtATimeThenLeaves() {
        val bills = runBlocking { rig.files.createFolder("Bills", null) }
        val year = runBlocking { rig.files.createFolder("2026", bills) }
        var left = 0
        var dispatcher: OnBackPressedDispatcher? = null
        show {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { left++ }
            FilesScreen(rig.environment, 0.dp, {}, {}, {})
        }
        waitFor { exists("folder-row-$bills") }
        compose.onNodeWithTag("folder-row-$bills").performClick()
        waitFor { exists("folder-row-$year") }
        compose.onNodeWithTag("folder-row-$year").performClick()
        waitFor { exists("crumb-$year") }
        compose.onNodeWithTag("files-search-open").performClick()
        waitFor { exists("files-search") }

        val back = { compose.runOnUiThread { dispatcher!!.onBackPressed() } }
        back()
        waitFor { !exists("files-search") }
        assertTrue(exists("crumb-$year"))
        back()
        waitFor { !exists("crumb-$year") && exists("crumb-$bills") }
        back()
        waitFor { !exists("crumb-$bills") && exists("folder-row-$bills") }
        assertEquals(0, left)
        back()
        waitFor { left == 1 }
    }

    private fun thumb(id: String): Boolean = compose.onAllNodesWithTag("file-thumb-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aThumbnailAppearsWhenTheBackgroundDownloadLands() {
        val image = rig.add("photo.png", mime = "image/png")
        val stored = rig.query("SELECT local_path FROM file_rows WHERE id = '$image'").single()["local_path"]!!
        val bytes = File(rig.dir, stored).readBytes()
        File(rig.dir, stored).delete()
        showFiles()
        waitFor { exists("file-row-$image") }
        assertFalse(thumb(image))
        File(rig.dir, stored).writeBytes(bytes)
        compose.waitForIdle()
        assertFalse(thumb(image))
        runBlocking { downloads.emit(stored) }
        waitFor { thumb(image) }
    }

    @Test
    fun aFolderSubtitleUsesASingularOrPluralItemCount() {
        val folder = runBlocking { rig.files.createFolder("Receipts", null) }
        rig.add("bill.txt", folderId = folder)
        showFiles()
        waitFor { exists("folder-row-$folder") }
        assertEquals(1, compose.onAllNodesWithText("1 item", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aFolderDeleteThatFailsSaysSoAndDoesNotReportItDeleted() {
        val folder = runBlocking { rig.files.createFolder("Receipts", null) }
        val entity = runBlocking { rig.holder.db.filesDao().folder(folder)!! }
        val ui = FileUiState()
        val messages = ArrayList<String>()
        var deleted = false
        ui.dialog = FileDialog.DeleteFolder(entity, com.ftapps.remft.data.files.SubtreeCounts(1, 0), toBin = true) { deleted = true }
        show { FileDialogsHost(ui, rig.environment, emptyList()) { messages.add(it) } }
        waitFor { exists("folder-delete-confirm") }
        rig.failDatabase = true
        compose.onNodeWithTag("folder-delete-confirm").performClick()
        waitFor { messages.isNotEmpty() }
        rig.failDatabase = false
        assertEquals(listOf("Could not delete the folder"), messages)
        assertFalse(deleted)
        assertEquals(1, rig.query("SELECT id FROM folders").size)
    }
}
