package com.ftapps.remft.ui.tasks

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UndoSnackbarWindowTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val clock = TestScope(StandardTestDispatcher())
    private val env = TasksEnvironment(tasks.repository, tasks.settings, PendingDeletes(clock, window = 5_000, settle = 1_000))

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun theSnackbarStaysForTheWholeUndoWindowEvenWhenItAppearsLate() {
        var commits = 0
        env.undo.post("a", "“Gym” deleted") { commits++ }
        clock.advanceTimeBy(3_000)
        clock.runCurrent()
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides env) { UndoSnackbarHost() }
            }
        }
        settle()
        compose.onNodeWithText("“Gym” deleted").assertIsDisplayed()
        clock.advanceTimeBy(4_900)
        clock.runCurrent()
        settle()
        assertEquals(0, commits)
        compose.onNodeWithText("“Gym” deleted").assertIsDisplayed()
        clock.advanceTimeBy(200)
        clock.runCurrent()
        settle()
        assertEquals(1, commits)
    }
}
