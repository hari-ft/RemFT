package com.ftapps.remft.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    @Composable
    private fun Shell() {
        CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell() }
    }

    @Test
    fun phoneShowsTheBottomBarAndSwitchesSections() {
        compose.setContent { RemftTheme { Shell() } }
        compose.onNodeWithTag("bottom-bar").assertIsDisplayed()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()

        compose.onNodeWithTag("nav-inbox").performClick()
        compose.onNodeWithTag("screen-inbox").assertIsDisplayed()
        compose.onNodeWithTag("nav-inbox").assertIsSelected()

        compose.onNodeWithTag("nav-notes").performClick()
        compose.onNodeWithTag("screen-notes").assertIsDisplayed()

        compose.onNodeWithTag("nav-files").performClick()
        compose.onNodeWithTag("screen-files").assertIsDisplayed()
    }

    @Test
    fun settingsGearOpensTheSettingsIndex() {
        compose.setContent { RemftTheme { Shell() } }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-general").assertIsDisplayed()
        compose.onNodeWithTag("settings-bin").assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun wideScreensUseTheRail() {
        compose.setContent { RemftTheme { Shell() } }
        compose.onNodeWithTag("nav-rail").assertIsDisplayed()
        compose.onNodeWithTag("bottom-bar").assertDoesNotExist()
    }

    @Test
    fun darkThemeRenders() {
        compose.setContent { RemftTheme(darkTheme = true) { Shell() } }
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
    }
}
