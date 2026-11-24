package com.ftapps.remft.ui.share

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class ShareInSheetTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val messages = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var showing by mutableStateOf(true)
    private var ready by mutableStateOf(true)
    private val incoming = File(rig.dir.parentFile, "incoming-${System.nanoTime()}")
    private var copies = emptyList<File>()

    private val inbox = ShareInbox(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        copy = { share -> Intake(copies.map { SharedItem(it, it.name, mimeOf(it.name), it.length()) }, problems, share.text) },
        discardFiles = { intake -> intake.items.forEach { it.file.parentFile?.deleteRecursively() } },
    )
    private var problems = emptyList<IntakeProblem>()

    @After
    fun tearDown() {
        showing = false
        runCatching { compose.waitForIdle() }
        rig.close()
    }

    private fun mimeOf(name: String) = if (name.endsWith(".png")) "image/png" else "text/plain"

    private fun copied(vararg names: String): List<File> = names.mapIndexed { index, name ->
        File(File(incoming, index.toString()).apply { mkdirs() }, name).apply { writeText("data-$name") }
    }

    private fun share(vararg names: String, text: String? = null) {
        copies = copied(*names)
        inbox.receive(IncomingShare(copies.map { android.net.Uri.fromFile(it) }, text))
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides rig.environment) {
                    if (showing) {
                        ShareInHost(inbox, rig.environment, rig.tasks.environment, rig.notes, ready, onOpenTask = { opened += it }) { messages += it }
                    }
                }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(text: String): Boolean = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rows(): List<Map<String, String?>> = rig.query("SELECT name, target_type, target_id, folder_id FROM file_rows")

    private fun click(tag: String) {
        waitFor { exists(tag) }
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun theSheetListsTheSharedItemsAndWaitsUntilTheAppIsReady() {
        ready = false
        share("Beach.png", "notes.txt")
        compose.waitForIdle()
        assertFalse(exists("sharein-sheet"))
        ready = true
        waitFor { exists("sharein-sheet") }
        assertEquals(2, compose.onAllNodesWithTag("sharein-item").fetchSemanticsNodes().size)
        assertTrue(textShown("Beach.png"))
        assertTrue(exists("sharein-dest-files"))
        assertTrue(exists("sharein-dest-new-task"))
        assertTrue(exists("sharein-dest-task"))
        assertTrue(exists("sharein-dest-note"))
    }

    @Test
    fun itemsThatCouldNotBeTakenAreNamedAsProblems() {
        problems = listOf(IntakeProblem.Unreadable, IntakeProblem.TooLarge, IntakeProblem.TooLarge)
        share("a.txt")
        waitFor { exists("sharein-sheet") }
        assertTrue(textShown("1 item could not be read."))
        assertTrue(textShown("2 items are over the 100 MB limit."))
    }

    @Test
    fun nothingUsableSaysSo() {
        problems = listOf(IntakeProblem.Unsupported)
        share()
        waitFor { exists("sharein-nothing") }
        assertTrue(textShown("1 item is not a file RemFT can save."))
        assertFalse(exists("sharein-dest-files"))
    }

    @Test
    fun savingToTheFilesRootUploadsThereAndCleansTheCopy() {
        share("Beach.png")
        click("sharein-dest-files")
        click("sharein-folder-root")
        waitFor { rows().size == 1 }
        val row = rows().single()
        assertEquals("Beach.png", row["name"])
        assertEquals("workspace", row["target_type"])
        assertNull(row["folder_id"])
        waitFor { !copies.single().exists() }
        assertEquals(listOf("Saving 1 file in Files"), messages)
        assertNull(inbox.state.value)
    }

    @Test
    fun savingIntoAFolderUsesThatFolder() {
        val folder = runBlocking { rig.files.createFolder("Trips", null) }
        share("Beach.png", "notes.txt")
        click("sharein-dest-files")
        click("sharein-folder-$folder")
        waitFor { rows().size == 2 }
        assertTrue(rows().all { it["folder_id"] == folder })
        assertEquals(listOf("Saving 2 files in Files"), messages)
    }

    @Test
    fun aNewFolderCanBeMadeOnTheWay() {
        share("Beach.png")
        click("sharein-dest-files")
        click("sharein-folder-new")
        waitFor { exists("sharein-folder-name") }
        compose.onNodeWithTag("sharein-folder-name").performTextInput("Holiday")
        compose.onNodeWithTag("sharein-folder-create").performClick()
        waitFor { rows().size == 1 }
        val folder = rig.query("SELECT id, name FROM folders").single()
        assertEquals("Holiday", folder["name"])
        assertEquals(folder["id"], rows().single()["folder_id"])
    }

    @Test
    fun anExistingTaskCanBeSearchedAndGetsTheFiles() {
        val rent = runBlocking { rig.tasks.repository.create(TaskDraft(title = "Pay rent")) }
        runBlocking { rig.tasks.repository.create(TaskDraft(title = "Water plants")) }
        share("receipt.png")
        click("sharein-dest-task")
        waitFor { exists("sharein-task-$rent") }
        compose.onNodeWithTag("sharein-task-search").performTextInput("rent")
        waitFor { !textShown("Water plants") }
        click("sharein-task-$rent")
        waitFor { rows().size == 1 }
        assertEquals("task", rows().single()["target_type"])
        assertEquals(rent, rows().single()["target_id"])
        assertEquals(listOf(rent), opened)
        assertEquals(listOf("Attaching 1 file to the task"), messages)
    }

    @Test
    fun aNewTaskStartsWithTheFilesPickedAndAttachesThemOnCreate() {
        share("receipt.png")
        click("sharein-dest-new-task")
        waitFor { exists("task-sheet-pick-0") }
        assertTrue(textShown("receipt.png"))
        assertNull(inbox.state.value)
        compose.onNodeWithTag("task-sheet-title").performTextInput("Claim expenses")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { rows().size == 1 }
        val task = rig.query("SELECT id FROM tasks WHERE title = 'Claim expenses'").single()["id"]
        assertEquals("task", rows().single()["target_type"])
        assertEquals(task, rows().single()["target_id"])
    }

    @Test
    fun anExistingNoteGetsTheFiles() {
        val note = runBlocking { rig.notes.create("Ideas", null) }
        share("sketch.png")
        click("sharein-dest-note")
        click("sharein-note-$note")
        waitFor { rows().size == 1 }
        assertEquals("note", rows().single()["target_type"])
        assertEquals(note, rows().single()["target_id"])
        assertEquals(listOf("Attaching 1 file to the note"), messages)
    }

    @Test
    fun aNewNoteCanBeMadeForTheFiles() {
        share("sketch.png")
        click("sharein-dest-note")
        click("sharein-note-new")
        waitFor { rows().size == 1 }
        val note = rig.query("SELECT id FROM notes").single()["id"]
        assertEquals("note", rows().single()["target_type"])
        assertEquals(note, rows().single()["target_id"])
    }

    @Test
    fun sharedTextCanBecomeANote() {
        share(text = "Call the plumber\nabout the sink")
        waitFor { exists("sharein-text") }
        assertFalse(exists("sharein-dest-files"))
        click("sharein-text-note")
        waitFor { messages.isNotEmpty() }
        assertEquals("Call the plumber\nabout the sink", rig.query("SELECT content_markdown FROM notes").single()["content_markdown"])
        assertEquals(listOf("Saved as a new note"), messages)
    }

    @Test
    fun sharedTextCanBecomeATaskTitle() {
        share(text = "Call the plumber\nabout the sink")
        click("sharein-text-task")
        waitFor { exists("task-sheet-title") }
        assertTrue(textShown("Call the plumber about the sink"))
    }

    @Test
    fun cancellingRemovesTheCopiesAndSavesNothing() {
        share("Beach.png")
        click("sharein-cancel")
        waitFor { !exists("sharein-sheet") }
        assertFalse(copies.single().exists())
        assertNull(inbox.state.value)
        assertTrue(rows().isEmpty())
    }
}
