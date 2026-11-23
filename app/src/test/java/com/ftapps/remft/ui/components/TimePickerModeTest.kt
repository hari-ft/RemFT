package com.ftapps.remft.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.tasks.TaskTimePickerDialog
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class TimePickerModeTest {
    @get:Rule
    val compose = createComposeRule()

    private var picked: LocalTime? = null

    private fun show() {
        compose.setContent {
            RemftTheme(darkTheme = false) {
                TaskTimePickerDialog(initial = LocalTime.of(7, 5), is24Hour = false, onDismiss = {}, onPicked = { picked = it })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun fitFollowsTheRoomOnScreen() {
        assertEquals(TimePickerFit.Dial, TimePickerSizing.fit(360.dp, 640.dp, 1f))
        assertEquals(TimePickerFit.Dial, TimePickerSizing.fit(360.dp, 640.dp, 2f))
        assertEquals(TimePickerFit.Input, TimePickerSizing.fit(640.dp, 360.dp, 1f))
        assertEquals(TimePickerFit.Input, TimePickerSizing.fit(891.dp, 411.dp, 1f))
        assertEquals(TimePickerFit.DialSideBySide, TimePickerSizing.fit(1280.dp, 500.dp, 1f))
        assertEquals(TimePickerFit.Input, TimePickerSizing.fit(300.dp, 640.dp, 1f))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun portraitShowsTheDialAndCanSwitchToTyping() {
        show()
        compose.onNodeWithTag("time-picker-dial").assertIsDisplayed()
        compose.onNodeWithTag("time-picker-mode").performClick()
        compose.onNodeWithTag("time-picker-input").assertIsDisplayed()
        compose.onNodeWithTag("time-picker-mode").performClick()
        compose.onNodeWithTag("time-picker-dial").assertIsDisplayed()
        compose.onNodeWithTag("time-picker-ok").performClick()
        assertEquals(LocalTime.of(7, 5), picked)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun shortLandscapeTypesTheTimeWithoutASwitch() {
        show()
        compose.onNodeWithTag("time-picker-input").assertIsDisplayed()
        compose.onNodeWithTag("time-picker-mode").assertDoesNotExist()
        compose.onNodeWithTag("time-picker-ok").performClick()
        assertEquals(LocalTime.of(7, 5), picked)
    }
}
