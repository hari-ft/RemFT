package com.ftapps.remft.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.alarm.PermissionPromptGate
import com.ftapps.remft.ui.shell.AppShell
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

@RunWith(AndroidJUnit4::class)
class PermissionAfterTourTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val tour = TourController(tasks.settings, tasks.scope)
    private var prompts = 0

    @After
    fun tearDown() {
        tasks.close()
    }

    @Composable
    private fun App(needed: Boolean = true) {
        CompositionLocalProvider(
            LocalTasksEnvironment provides tasks.environment,
            LocalTourController provides tour,
        ) {
            AppShell()
            PermissionPromptGate(ready = tour.settled, needed = { needed }, onRequest = { prompts += 1 })
        }
    }

    private fun launch(needed: Boolean = true) {
        compose.setContent { RemftTheme { App(needed) } }
    }

    @Test
    fun promptWaitsWhileTheTourShowsAndComesAfterSkip() {
        launch()
        compose.waitUntil(5_000) { tour.running }
        compose.waitForIdle()
        assertFalse(tour.settled)
        assertEquals(0, prompts)
        compose.onNodeWithTag("tour-next").performClick()
        compose.waitForIdle()
        assertEquals(0, prompts)
        compose.onNodeWithTag("tour-skip").performClick()
        compose.waitUntil(5_000) { prompts == 1 }
        assertTrue(tour.settled)
        compose.waitForIdle()
        assertEquals(1, prompts)
    }

    @Test
    fun promptComesAfterTheLastTourCard() {
        launch()
        compose.waitUntil(5_000) { tour.running }
        repeat(TourStep.entries.size - 1) {
            compose.onNodeWithTag("tour-next").performClick()
            compose.waitForIdle()
            assertEquals(0, prompts)
        }
        compose.onNodeWithTag("tour-next").performClick()
        compose.waitUntil(5_000) { prompts == 1 }
    }

    @Test
    fun promptComesStraightAwayWhenTheTourWasSeen() {
        runBlocking { tasks.settings.set(SettingKeys.OnboardingSeen.name, "true") }
        launch()
        compose.waitUntil(5_000) { prompts == 1 }
        assertFalse(tour.running)
        assertTrue(tour.settled)
    }

    @Test
    fun replayingTheTourDoesNotAskAgain() {
        runBlocking { tasks.settings.set(SettingKeys.OnboardingSeen.name, "true") }
        launch()
        compose.waitUntil(5_000) { prompts == 1 }
        tour.start()
        compose.waitForIdle()
        tour.skip()
        compose.waitForIdle()
        assertEquals(1, prompts)
    }

    @Test
    fun noPromptWhenThePermissionIsAlreadyGranted() {
        runBlocking { tasks.settings.set(SettingKeys.OnboardingSeen.name, "true") }
        launch(needed = false)
        compose.waitUntil(5_000) { tour.settled }
        compose.waitForIdle()
        assertEquals(0, prompts)
    }
}
