package com.ftapps.remft.ui.settings

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DynamicColorSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    SettingsGeneralScreen(onBack = {})
                }
            }
        }
    }

    private fun stored(): String? = runBlocking { tasks.settings.get(SettingKeys.DynamicColor.name) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun brandPaletteIsTheDefault() {
        assertEquals("false", SettingKeys.DynamicColor.default)
        assertFalse(SettingKeys.DynamicColor.name in SettingKeys.backedUp)
        show()
        compose.onNodeWithText("Colours from your wallpaper").assertIsDisplayed()
        compose.onNodeWithTag("settings-dynamic-color-switch").assertIsOff()
        assertEquals(null, stored())
    }

    @Test
    fun switchTurnsWallpaperColoursOnAndOff() {
        show()
        compose.onNodeWithTag("settings-dynamic-color").performClick()
        waitFor { stored() == "true" }
        compose.onNodeWithTag("settings-dynamic-color-switch").assertIsOn()
        compose.onNodeWithTag("settings-dynamic-color").performClick()
        waitFor { stored() == "false" }
        compose.onNodeWithTag("settings-dynamic-color-switch").assertIsOff()
    }

    @Test
    @Config(sdk = [30])
    fun rowIsHiddenBeforeAndroid12() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-dynamic-color").assertDoesNotExist()
    }
}
