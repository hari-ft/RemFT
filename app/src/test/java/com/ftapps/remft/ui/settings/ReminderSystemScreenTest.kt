package com.ftapps.remft.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestAlarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h4000dp")
class ReminderSystemScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val alarms = TestAlarms()

    @After
    fun tearDown() {
        alarms.close()
    }

    private fun show(dark: Boolean = false) {
        compose.showAlarmScreen(alarms, dark) { ReminderSystemScreen(onBack = {}) }
    }

    private fun stored(): ReminderProfile = ReminderProfile.parse(alarms.setting(SettingKeys.ReminderProfile))

    private fun waitForSlots(slots: List<Int>) = compose.waitFor {
        compose.tagCount("reminder-step-${slots.lastIndex}") == 1 && compose.tagCount("reminder-step-${slots.size}") == 0 &&
            slots.all { m -> compose.textCount(if (m == 0) "on time" else "+$m min") >= 1 }
    }

    @Test
    fun standardIsTheDefaultAndShowsTheTimeline() {
        show()
        compose.waitFor { compose.tagCount("reminder-step-2") == 1 }
        compose.onNodeWithTag("reminder-preset-standard").assertIsSelected()
        compose.waitFor { compose.textCount("An alarm at 9:00 am would ring:") == 1 }
        compose.waitFor { compose.textCount("9:00 am → 9:30 am → 10:00 am") == 1 }
        compose.waitFor { compose.textCount("This chain fully covers about 13 alarms at once.") == 1 }
    }

    @Test
    fun choosingInsistentPersistsItsSlots() {
        show(dark = true)
        compose.waitFor { compose.tagCount("reminder-step-2") == 1 }
        compose.onNodeWithTag("reminder-preset-insistent").performClick()
        val insistent = ReminderProfile.preset(ReminderProfile.PRESET_INSISTENT)!!.slots
        compose.waitFor { stored().slots == insistent }
        waitForSlots(insistent)
        compose.onNodeWithTag("reminder-preset-insistent").assertIsSelected()
        compose.waitFor { compose.tagCount("reminder-capacity-warning") == 0 }
    }

    @Test
    fun customChainSelectsYours() {
        alarms.put(SettingKeys.ReminderProfile, ReminderProfile(listOf(0, 7)).encode())
        show()
        waitForSlots(listOf(0, 7))
        compose.onNodeWithTag("reminder-preset-custom").assertIsSelected()
    }

    @Test
    fun addAndRemoveSteps() {
        show()
        waitForSlots(listOf(0, 30, 60))
        compose.onNodeWithTag("reminder-add-step").performClick()
        compose.waitFor { stored().slots == listOf(0, 30, 60, 65) }
        waitForSlots(listOf(0, 30, 60, 65))
        compose.onNodeWithTag("reminder-step-1-remove").performClick()
        compose.waitFor { stored().slots == listOf(0, 60, 65) }
        waitForSlots(listOf(0, 60, 65))
    }

    @Test
    fun stepButtonsMoveOneMinuteAndRespectNeighbours() {
        alarms.put(SettingKeys.ReminderProfile, ReminderProfile(listOf(0, 1, 10)).encode())
        show()
        waitForSlots(listOf(0, 1, 10))
        compose.onNodeWithTag("reminder-step-0-minus").assertIsNotEnabled()
        compose.onNodeWithTag("reminder-step-0-plus").assertIsNotEnabled()
        compose.onNodeWithTag("reminder-step-1-minus").assertIsNotEnabled()
        compose.onNodeWithTag("reminder-step-1-plus").assertIsEnabled().performClick()
        compose.waitFor { stored().slots == listOf(0, 2, 10) }
    }

    @Test
    fun singleStepCannotBeRemovedAndLongChainsWarn() {
        alarms.put(SettingKeys.ReminderProfile, ReminderProfile(listOf(0)).encode())
        show()
        waitForSlots(listOf(0))
        compose.onNodeWithTag("reminder-step-0-remove").assertIsNotEnabled()
        alarms.put(SettingKeys.ReminderProfile, ReminderProfile((0 until 20).toList()).encode())
        waitForSlots((0 until 20).toList())
        compose.onNodeWithTag("reminder-add-step").assertIsNotEnabled()
        compose.onNodeWithTag("reminder-capacity-warning").assertExists()
    }

    @Test
    fun backToStandardResetsTheChain() {
        alarms.put(SettingKeys.ReminderProfile, ReminderProfile(listOf(0), repeatAfterSnooze = false).encode())
        show()
        waitForSlots(listOf(0))
        compose.onNodeWithTag("reminder-reset").performClick()
        compose.waitFor { stored() == ReminderProfile.Factory }
    }

    @Test
    fun repeatAfterSnoozePersists() {
        show()
        waitForSlots(listOf(0, 30, 60))
        compose.onNodeWithTag("reminder-repeat-after-snooze").performClick()
        compose.waitFor { alarms.setting(SettingKeys.ReminderProfile)?.contains("\"repeatAfterSnooze\":false") == true }
    }

    @Test
    fun snoozeOrderMovesAndPersists() {
        show()
        compose.waitFor { compose.tagCount("snooze-down-5_min") == 1 }
        compose.onNodeWithTag("snooze-up-5_min").assertIsNotEnabled()
        compose.onNodeWithTag("snooze-down-5_min").performClick()
        compose.waitFor { alarms.setting(SettingKeys.SnoozePresets) == "30_min,5_min,1_hour,tomorrow_morning" }
    }

    private fun order(): String? = alarms.setting(SettingKeys.SnoozePresets)

    @Test
    fun removingAnOptionKeepsItRemovedButNeverTheLastOne() {
        show()
        compose.waitFor { compose.tagCount("snooze-remove-30_min") == 1 }
        compose.onNodeWithContentDescription("Remove 30 min").assertIsEnabled()
        compose.onNodeWithTag("snooze-remove-30_min").performClick()
        compose.waitFor { order() == "5_min,1_hour,tomorrow_morning" }
        compose.waitFor { compose.tagCount("snooze-order-30_min") == 0 }
        alarms.put(SettingKeys.SnoozePresets, "m:15")
        compose.waitFor { compose.tagCount("snooze-order-m:15") == 1 && compose.tagCount("snooze-order-5_min") == 0 }
        compose.onNodeWithTag("snooze-remove-m:15").assertIsNotEnabled()
    }

    @Test
    fun longPressOnRemoveShowsItsNameWithoutRemoving() {
        show()
        compose.waitFor { compose.tagCount("snooze-remove-30_min") == 1 }
        assertEquals(0, compose.textCount("Remove 30 min"))
        compose.onNodeWithTag("snooze-remove-30_min").performTouchInput { longClick() }
        compose.waitFor { compose.textCount("Remove 30 min") == 1 }
        compose.onNodeWithContentDescription("Remove 30 min").assertIsEnabled()
        assertEquals(1, compose.tagCount("snooze-order-30_min"))
        assertEquals(null, alarms.setting(SettingKeys.SnoozePresets))
    }

    @Test
    fun resetBringsBackTheDefaultFour() {
        alarms.put(SettingKeys.SnoozePresets, "m:15,tomorrow_morning")
        show()
        compose.waitFor { compose.tagCount("snooze-order-m:15") == 1 }
        compose.onNodeWithTag("snooze-reset").assertIsEnabled().performClick()
        compose.waitFor { order() == "5_min,30_min,1_hour,tomorrow_morning" }
        compose.waitFor { compose.tagCount("snooze-order-m:15") == 0 && compose.tagCount("snooze-order-5_min") == 1 }
        compose.onNodeWithTag("snooze-reset").assertIsNotEnabled()
    }

    @Test
    fun soundRowOpensThePicker() {
        show()
        compose.waitFor { compose.tagCount("sound-row-alarm") == 1 }
        compose.onNodeWithTag("sound-row-alarm").performClick()
        compose.waitFor { compose.tagCount("sound-bundled-chime") == 1 }
        compose.onNodeWithTag("sound-bundled-chime").performClick()
        compose.waitFor { alarms.setting(SettingKeys.AlarmSound) == "bundled:chime" }
        compose.waitFor { compose.textCount("Nothing uploaded yet.") == 1 }
    }
}
