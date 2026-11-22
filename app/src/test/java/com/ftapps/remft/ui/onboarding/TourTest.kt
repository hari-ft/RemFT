package com.ftapps.remft.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestTasks
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
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TourTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tasks = TestTasks()
    private val tour = TourController(tasks.settings, tasks.scope)

    @After
    fun tearDown() {
        tasks.close()
    }

    @Composable
    private fun Shell() {
        CompositionLocalProvider(
            LocalTasksEnvironment provides tasks.environment,
            LocalTourController provides tour,
        ) { AppShell() }
    }

    private fun launch(dark: Boolean = false) {
        compose.setContent { RemftTheme(darkTheme = dark) { Shell() } }
    }

    private fun awaitTour() {
        compose.waitUntil(5_000) { tour.running }
        compose.waitForIdle()
    }

    private fun seen(): String? = runBlocking { tasks.settings.get(SettingKeys.OnboardingSeen.name) }

    private fun awaitSeen() {
        compose.waitUntil(5_000) { seen() == "true" }
    }

    private fun seedSeen() {
        runBlocking { tasks.settings.set(SettingKeys.OnboardingSeen.name, "true") }
    }

    private fun assertStep(step: TourStep) {
        compose.onNodeWithTag("tour-title").assertTextEquals(context.getString(step.title))
        compose.onNodeWithTag("tour-body").assertTextEquals(context.getString(step.body))
        val label = context.getString(R.string.tour_stepOf).replace("{step}", "${step.ordinal + 1}").replace("{total}", "${TourStep.entries.size}")
        compose.onNodeWithTag("tour").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, label))
    }

    private fun advanceTo(step: TourStep) {
        repeat(step.ordinal) { compose.onNodeWithTag("tour-next").performClick() }
        compose.waitForIdle()
        assertStep(step)
    }

    private fun near(a: Float, b: Float) = abs(a - b) <= 1f

    private fun assertRingsAround(spot: DpRect, item: DpRect) {
        assertTrue("left ${spot.left} vs ${item.left}", near(spot.left.value, item.left.value - 10f))
        assertTrue("top ${spot.top} vs ${item.top}", near(spot.top.value, item.top.value - 10f))
        assertTrue("right ${spot.right} vs ${item.right}", near(spot.right.value, item.right.value + 10f))
        assertTrue("bottom ${spot.bottom} vs ${item.bottom}", near(spot.bottom.value, item.bottom.value + 10f))
    }

    @Test
    fun showsOnFirstRunAndWalksAllSixStepsThenDoneMarksSeen() {
        launch()
        awaitTour()
        compose.onNodeWithTag("tour").assertIsDisplayed()
        TourStep.entries.forEach { step ->
            assertStep(step)
            val label = if (step == TourStep.Done) R.string.tour_done else R.string.tour_next
            compose.onNodeWithTag("tour-next").assertTextEquals(context.getString(label))
            if (step.section == null) compose.onNodeWithTag("tour-spotlight").assertDoesNotExist()
            else compose.onNodeWithTag("tour-spotlight").assertExists()
            assertEquals(null, seen())
            compose.onNodeWithTag("tour-next").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("tour").assertDoesNotExist()
        awaitSeen()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
    }

    @Test
    fun skipClosesTheTourAndMarksSeen() {
        launch()
        awaitTour()
        advanceTo(TourStep.Inbox)
        compose.onNodeWithTag("tour-skip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tour").assertDoesNotExist()
        assertFalse(tour.running)
        awaitSeen()
    }

    @Test
    fun seenFlagKeepsTheTourAway() {
        seedSeen()
        runBlocking { tour.maybeAutoStart() }
        launch()
        compose.waitForIdle()
        assertFalse(tour.running)
        compose.onNodeWithTag("tour").assertDoesNotExist()
        compose.onNodeWithTag("nav-inbox").performClick()
        compose.onNodeWithTag("screen-inbox").assertIsDisplayed()
    }

    @Test
    fun appTourRowReplaysTheTourOnHome() {
        seedSeen()
        runBlocking { tour.maybeAutoStart() }
        launch()
        compose.onNodeWithTag("nav-inbox").performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-general").performClick()
        compose.onNodeWithTag("replay-tour").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(tour.running)
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
        assertStep(TourStep.Welcome)
        advanceTo(TourStep.Done)
        compose.onNodeWithTag("tour-next").performClick()
        compose.onNodeWithTag("tour").assertDoesNotExist()
    }

    @Test
    fun spotlightRingsTheBottomBarItemOnPhones() {
        launch()
        awaitTour()
        compose.onNodeWithTag("bottom-bar").assertIsDisplayed()
        repeat(TourStep.Home.ordinal) { compose.onNodeWithTag("tour-next").performClick() }
        listOf(TourStep.Home to "nav-home", TourStep.Inbox to "nav-inbox", TourStep.Notes to "nav-notes", TourStep.Files to "nav-files").forEach { (step, tag) ->
            compose.waitForIdle()
            assertStep(step)
            val item = compose.onNodeWithTag(tag).getBoundsInRoot()
            val spot = compose.onNodeWithTag("tour-spotlight").getBoundsInRoot()
            assertRingsAround(spot, item)
            val card = compose.onNodeWithTag("tour-card").getBoundsInRoot()
            assertTrue("card above the bar", card.bottom <= spot.top)
            compose.onNodeWithTag("tour-next").performClick()
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun spotlightRingsTheRailItemOnWideScreens() {
        launch()
        awaitTour()
        compose.onNodeWithTag("nav-rail").assertIsDisplayed()
        advanceTo(TourStep.Notes)
        val item = compose.onNodeWithTag("nav-notes").getBoundsInRoot()
        val spot = compose.onNodeWithTag("tour-spotlight").getBoundsInRoot()
        assertRingsAround(spot, item)
        val card = compose.onNodeWithTag("tour-card").getBoundsInRoot()
        assertTrue("card beside the rail", card.left >= spot.right)
        assertTrue("card at most 380dp wide", card.right - card.left <= 380.dp + 1.dp)
    }

    @Test
    fun darkThemeRendersTheTour() {
        launch(dark = true)
        awaitTour()
        compose.onNodeWithTag("tour-card").assertIsDisplayed()
        compose.onNodeWithTag("tour-skip").assertIsDisplayed()
        assertStep(TourStep.Welcome)
        advanceTo(TourStep.Files)
        compose.onNodeWithTag("tour-spotlight").assertIsDisplayed()
    }
}
