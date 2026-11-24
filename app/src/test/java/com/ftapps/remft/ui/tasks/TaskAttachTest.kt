package com.ftapps.remft.ui.tasks

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.PickedFile
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
import org.robolectric.shadows.ShadowDialog
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class TaskAttachTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val cleaned = mutableListOf<String>()

    @After
    fun tearDown() {
        rig.close()
    }

    private fun pick(name: String, text: String = "data-$name"): PickedFile =
        PickedFile(name, text.length.toLong(), "text/plain", { text.byteInputStream() }, cleanup = { cleaned += name })

    @Composable
    private fun Hosted(content: @Composable () -> Unit) {
        RemftTheme {
            CompositionLocalProvider(LocalTasksEnvironment provides rig.tasks.environment, LocalFilesEnvironment provides rig.environment) { content() }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { Hosted(content) }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pickThroughSheet() {
        compose.onNodeWithTag("task-sheet-attach").performScrollTo().performClick()
        waitFor { exists("attach-source-files") }
        compose.onNodeWithTag("attach-source-files").performClick()
    }

    private fun fileRows() = rig.query("SELECT name, target_type, target_id FROM file_rows ORDER BY name")

    @Test
    fun sheetAttachThenSaveUploadsToTheNewTask() {
        var closed = false
        show { TaskCreateSheet(onDismiss = { closed = true }) }
        waitFor { exists("task-sheet-title") }
        rig.picks = listOf(pick("plan.txt"), pick("ticket.txt"))
        pickThroughSheet()
        waitFor { exists("task-sheet-pick-0") && exists("task-sheet-pick-1") }
        compose.onNodeWithText("plan.txt").assertExists()
        assertTrue(fileRows().isEmpty())
        compose.onNodeWithTag("task-sheet-title").performTextInput("Pack bags")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { closed }
        val taskId = rig.query("SELECT id FROM tasks").single()["id"]
        waitFor { fileRows().size == 2 }
        val rows = fileRows()
        assertEquals(listOf("plan.txt", "ticket.txt"), rows.map { it["name"] })
        assertTrue(rows.all { it["target_type"] == "task" && it["target_id"] == taskId })
    }

    @Test
    fun removingAChipDropsThatPickBeforeSaving() {
        var closed = false
        show { TaskCreateSheet(onDismiss = { closed = true }) }
        waitFor { exists("task-sheet-title") }
        rig.picks = listOf(pick("keep.txt"), pick("drop.txt"))
        pickThroughSheet()
        waitFor { exists("task-sheet-pick-1") }
        compose.onNodeWithContentDescription("Remove drop.txt", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("task-sheet-pick-1").performClick()
        waitFor { !exists("task-sheet-pick-1") }
        assertEquals(listOf("drop.txt"), cleaned)
        compose.onNodeWithTag("task-sheet-title").performTextInput("Only one")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { closed }
        waitFor { fileRows().size == 1 }
        assertEquals("keep.txt", fileRows().single()["name"])
    }

    @Test
    fun cancellingTheSheetUploadsNothingAndCleansThePicks() {
        var closed = false
        show { TaskCreateSheet(onDismiss = { closed = true }) }
        waitFor { exists("task-sheet-title") }
        rig.picks = listOf(pick("camera.jpg"))
        pickThroughSheet()
        waitFor { exists("task-sheet-pick-0") }
        compose.onNodeWithTag("task-sheet-title").performTextInput("Never saved")
        compose.runOnUiThread { ShadowDialog.getShownDialogs().last { it.isShowing }.onBackPressed() }
        waitFor { closed }
        assertEquals(listOf("camera.jpg"), cleaned)
        compose.mainClock.advanceTimeBy(1_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(rig.query("SELECT id FROM tasks").isEmpty())
        assertTrue(fileRows().isEmpty())
        assertTrue(rig.environment.uploads.jobs.value.isEmpty())
    }

    @Test
    fun picksSurviveAConfigurationChange() {
        val tester = StateRestorationTester(compose)
        var closed = false
        tester.setContent { Hosted { TaskCreateSheet(onDismiss = { closed = true }) } }
        waitFor { exists("task-sheet-title") }
        rig.picks = listOf(pick("kept.txt"))
        pickThroughSheet()
        waitFor { exists("task-sheet-pick-0") }
        compose.onNodeWithTag("task-sheet-title").performTextInput("Survives")
        tester.emulateSavedInstanceStateRestore()
        waitFor { exists("task-sheet-pick-0") }
        assertTrue(cleaned.isEmpty())
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { closed }
        waitFor { fileRows().size == 1 }
    }

    @Test
    fun detailTopBarAttachUploadsAndShowsTheCount() {
        val id = runBlocking { rig.tasks.repository.create(TaskDraft(title = "Visa")) }
        show { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { exists("task-attach") }
        assertTrue(!exists("task-attach-count"))
        rig.picks = listOf(pick("passport.txt"), pick("photo.txt"))
        compose.onNodeWithTag("task-attach").performClick()
        waitFor { exists("attach-source-files") }
        compose.onNodeWithTag("attach-source-files").performClick()
        waitFor { fileRows().size == 2 }
        assertTrue(fileRows().all { it["target_type"] == "task" && it["target_id"] == id })
        waitFor { exists("task-attach-count") }
        compose.onNodeWithText("2", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Attach file, 2 attached", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("attach-button").performScrollTo()
    }

    @Test
    fun taskRowShowsAPaperclipOnlyWhenTheTaskHasFiles() {
        val with = runBlocking { rig.tasks.repository.create(TaskDraft(title = "With file")) }
        val without = runBlocking { rig.tasks.repository.create(TaskDraft(title = "Plain")) }
        rig.add("scan.txt", targetType = "task", targetId = with)
        val tasks = runBlocking { listOf(rig.tasks.repository.detail(with)!!.task, rig.tasks.repository.detail(without)!!.task) }
        show {
            val attached = rememberAttachedTaskIds()
            androidx.compose.foundation.layout.Column {
                tasks.forEach { task -> Row(task, task.id in attached) }
            }
        }
        waitFor { exists("task-attached-$with") }
        assertTrue(!exists("task-attached-$without"))
        compose.onNodeWithContentDescription("Has attachments", useUnmergedTree = true).assertExists()
    }

    @Composable
    private fun Row(task: Task, attached: Boolean) {
        TaskRow(
            task = task,
            format = DateFormatId.System,
            zone = ZoneId.of("Asia/Kolkata"),
            now = Instant.parse("2026-11-20T04:30:00Z"),
            onToggle = {},
            onOpen = {},
            onDelete = {},
            hasAttachments = attached,
        )
    }

    @Test
    fun viewModelHandsPicksToTheCreatedTaskAndCleansOnDiscard() {
        val handed = mutableListOf<Pair<String, List<String>>>()
        val vm = TaskSheetViewModel(rig.tasks.environment, attach = { id, picks -> handed += id to picks.map { it.name } })
        vm.addPicks(listOf(pick("a.txt")))
        vm.discard()
        assertEquals(listOf("a.txt"), cleaned)
        assertTrue(vm.picks.value.isEmpty())
        vm.addPicks(listOf(pick("b.txt"), pick("c.txt")))
        vm.removePick(0)
        assertEquals(listOf("a.txt", "b.txt"), cleaned)
        vm.create(TaskDraft(title = "Saved"), null, null)
        waitFor { vm.state.value == SheetSave.Saved }
        val id = rig.query("SELECT id FROM tasks").single()["id"]!!
        assertEquals(listOf(id to listOf("c.txt")), handed)
        assertTrue(vm.picks.value.isEmpty())
        vm.addPicks(listOf(pick("late.txt")))
        assertEquals(listOf("a.txt", "b.txt", "late.txt"), cleaned)
        assertTrue(vm.picks.value.isEmpty())
    }
}
