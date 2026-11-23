package com.ftapps.remft.ui.bin

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.completed.CompletedScreen
import com.ftapps.remft.ui.files.ImageViewer
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.ViewerRequest
import com.ftapps.remft.ui.files.ViewerZoom
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class BinAndViewerScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()

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

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun trashId(entity: String): String = rig.query("SELECT id FROM trash_items WHERE entity_id = '$entity'").single()["id"]!!

    @Test
    fun binListsEntriesAndRestoresOne() {
        val file = rig.add("contract.pdf", mime = "application/pdf")
        runBlocking { rig.attachments.delete(file) }
        val entry = trashId(file)
        show { RecycleBinScreen(onBack = {}) }
        waitFor { exists("bin-item-$entry") }
        compose.onNodeWithText("contract.pdf").assertIsDisplayed()
        compose.onNodeWithTag("bin-menu-$entry").performClick()
        compose.onNodeWithTag("bin-restore-$entry").performClick()
        waitFor { rig.query("SELECT id FROM file_rows").isNotEmpty() }
        waitFor { exists("bin-empty-state") }
        assertTrue(rig.query("SELECT id FROM trash_items").isEmpty())
    }

    @Test
    fun emptyBinAsksFirstThenDeletesForGood() {
        val a = rig.add("a.txt")
        val b = rig.add("b.txt")
        runBlocking {
            rig.attachments.delete(a)
            rig.attachments.delete(b)
        }
        show { RecycleBinScreen(onBack = {}) }
        waitFor { exists("bin-item-${trashId(a)}") }
        compose.onNodeWithTag("bin-empty").performClick()
        waitFor { exists("bin-empty-confirm") }
        compose.onNodeWithText("2 items and their attachments will be deleted for good. This cannot be undone.").assertExists()
        assertEquals(2, rig.query("SELECT id FROM trash_items").size)
        compose.onNodeWithTag("bin-empty-confirm").performClick()
        waitFor { rig.query("SELECT id FROM trash_items").isEmpty() }
        waitFor { rig.dir.listFiles().orEmpty().none { it.name.startsWith(a) || it.name.startsWith(b) } }
    }

    @Test
    fun binSettingsPickTheDays() {
        show { RecycleBinScreen(onBack = {}) }
        waitFor { exists("bin-days") }
        compose.onNodeWithTag("bin-days").performClick()
        compose.onNodeWithTag("bin-days-14").performClick()
        waitFor { runBlocking { rig.settings.get("remft_bin_days") } == "14" }
        waitFor { compose.onAllNodesWithTag("bin-days").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("14 days").assertIsDisplayed()
    }

    @Test
    fun viewerShowsPositionSwipesAndDeletes() {
        val first = rig.add("one.png", mime = "image/png")
        val second = rig.add("two.png", mime = "image/png")
        var closed = false
        show { ImageViewer(ViewerRequest(listOf(first, second), 1), rig.environment, onClose = { closed = true }, onMessage = {}) }
        waitFor { exists("viewer-counter") }
        compose.onNodeWithTag("viewer-counter").assertTextEquals("2 / 2")
        waitFor { compose.onAllNodesWithText("two.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewer-delete").performClick()
        waitFor { exists("viewer-delete-confirm") }
        compose.onNodeWithTag("viewer-delete-confirm").performClick()
        waitFor { rig.query("SELECT id FROM file_rows").size == 1 }
        waitFor { !exists("viewer-counter") }
        waitFor { compose.onAllNodesWithText("one.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewer-close").performClick()
        assertTrue(closed)
        assertEquals(1f, ViewerZoom.toggle(2.5f))
        assertEquals(ViewerZoom.DOUBLE_TAP_SCALE, ViewerZoom.toggle(1f))
        assertEquals(6f, ViewerZoom.clamp(40f))
    }

    @Test
    fun aDamagedImageSaysSoInsteadOfStayingBlank() {
        val broken = rig.add("broken.png", text = "not an image", mime = "image/png")
        show { ImageViewer(ViewerRequest(listOf(broken), 0), rig.environment, onClose = {}, onMessage = {}) }
        waitFor { exists("viewer-image-failed") }
        compose.onNodeWithText("This image could not be opened — the file may be damaged.").assertIsDisplayed()
    }

    @Test
    fun completedArchiveReopensATask() {
        val id = runBlocking {
            rig.tasks.repository.create(TaskDraft(title = "Filed taxes", dueAt = Instant.parse("2026-11-19T10:00:00Z"))).also {
                rig.tasks.repository.setCompleted(it, true)
            }
        }
        show { CompletedScreen(onBack = {}, onOpenTask = {}) }
        waitFor { exists("completed-$id") }
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithTag("reopen-$id").performClick()
        waitFor { rig.query("SELECT status FROM tasks WHERE id = '$id'").single()["status"] == "open" }
        waitFor { exists("completed-empty") }
    }

    @Test
    fun aReopenThatFailsSaysSoInsteadOfCrashing() {
        val id = runBlocking {
            rig.tasks.repository.create(TaskDraft(title = "Filed taxes", dueAt = Instant.parse("2026-11-19T10:00:00Z"))).also {
                rig.tasks.repository.setCompleted(it, true)
            }
        }
        rig.tasks.holder.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_reopen BEFORE UPDATE ON tasks BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )
        show { CompletedScreen(onBack = {}, onOpenTask = {}) }
        waitFor { exists("completed-$id") }
        compose.onNodeWithTag("reopen-$id").performClick()
        waitFor { compose.onAllNodesWithText("Couldn't update “Filed taxes”").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(exists("completed-$id"))
    }
    private fun line(tag: String): String? = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun selectable(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("onedrive-selectable")), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theViewerShowsTheOneDriveNameOfTheCurrentImage() {
        val first = rig.add("one.png", mime = "image/png")
        val second = rig.add("two.png", mime = "image/png")
        rig.mirror(rig.storedName(first), "mirrored", "Files/Photos/one.png")
        show { ImageViewer(ViewerRequest(listOf(first, second), 0), rig.environment, onClose = {}, onMessage = {}) }
        waitFor { line("viewer-onedrive-name") == "OneDrive: Files › Photos › one.png" }
        assertTrue(selectable("viewer-onedrive-name"))
        assertTrue(exists("viewer-title"))
        assertTrue(exists("viewer-open"))
        assertTrue(exists("viewer-delete"))
        assertTrue(exists("viewer-close"))
    }

    @Test
    fun aBinnedFileShowsWhereItIsInOneDrive() {
        val file = rig.add("contract.pdf", mime = "application/pdf")
        rig.mirror(rig.storedName(file), "mirrored", "Files/Recycle bin/contract.pdf")
        runBlocking { rig.attachments.delete(file) }
        val entry = trashId(file)
        show { RecycleBinScreen(onBack = {}) }
        waitFor { exists("bin-item-$entry") }
        waitFor { line("bin-onedrive-$entry-0") == "OneDrive: Files › Recycle bin › contract.pdf" }
        assertTrue(selectable("bin-onedrive-$entry-0"))
    }

    @Test
    fun aBinnedTaskShowsOneOneDriveLinePerAttachment() {
        val task = runBlocking { rig.tasks.repository.create(TaskDraft(title = "Trip")) }
        val ticket = rig.add("ticket.pdf", targetType = "task", targetId = task, mime = "application/pdf")
        rig.add("map.pdf", targetType = "task", targetId = task, mime = "application/pdf")
        rig.mirror(rig.storedName(ticket), "mirrored", "Files/Recycle bin/Tasks/Trip/ticket.pdf")
        runBlocking { rig.tasks.repository.delete(task) }
        val entry = trashId(task)
        show { RecycleBinScreen(onBack = {}) }
        waitFor { exists("bin-item-$entry") }
        val lines = { listOf(line("bin-onedrive-$entry-0"), line("bin-onedrive-$entry-1")).toSet() }
        waitFor { lines() == setOf("ticket.pdf: OneDrive: Files › Recycle bin › Tasks › Trip › ticket.pdf", "map.pdf: Not in OneDrive yet") }
        assertFalse(exists("bin-onedrive-$entry-2"))
    }
}
