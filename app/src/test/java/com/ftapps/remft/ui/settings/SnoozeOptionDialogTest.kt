package com.ftapps.remft.ui.settings

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestAlarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnoozeOptionDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val alarms = TestAlarms()

    @After
    fun tearDown() {
        alarms.close()
    }

    private fun show() {
        compose.showAlarmScreen(alarms) { ReminderSystemScreen(onBack = {}) }
    }

    private fun order(): String? = alarms.setting(SettingKeys.SnoozePresets)

    private fun openAddDialog() {
        compose.waitFor { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("snooze-add"))
        compose.waitFor { compose.tagCount("snooze-add") == 1 }
        compose.onNodeWithTag("snooze-add").performClick()
        compose.waitFor { compose.tagCount("snooze-add-amount") == 1 }
    }

    private fun addSnooze(amount: String, unitTag: String) {
        openAddDialog()
        compose.onNodeWithTag("snooze-add-amount").performTextInput(amount)
        compose.onNodeWithTag(unitTag).performClick()
        compose.onNodeWithTag("snooze-add-confirm").performClick()
    }

    @Test
    fun addingACustomSnoozeOptionPersistsAndShowsItsLabel() {
        show()
        addSnooze("90", "snooze-add-unit-minutes")
        compose.waitFor { order() == "5_min,30_min,1_hour,tomorrow_morning,m:90" }
        compose.waitFor { compose.tagCount("snooze-add-dialog") == 0 }
        compose.waitFor { compose.tagCount("snooze-order-m:90") == 1 }
        compose.waitFor { compose.textCount("1 h 30 min") == 1 }
        addSnooze("2", "snooze-add-unit-days")
        compose.waitFor { order() == "5_min,30_min,1_hour,tomorrow_morning,m:90,m:2880" }
        compose.waitFor { compose.textCount("2 days") == 1 }
    }

    @Test
    fun aDuplicateOrInvalidSnoozeOptionIsRejectedWithAMessage() {
        show()
        addSnooze("1", "snooze-add-unit-hours")
        compose.waitFor { compose.textCount("“1 hour” is already in your list") == 1 }
        compose.onNodeWithTag("snooze-add-dialog").assertExists()
        compose.onNodeWithTag("snooze-add-amount").performTextClearance()
        compose.onNodeWithTag("snooze-add-confirm").performClick()
        compose.waitFor { compose.textCount("Enter a whole number") == 1 }
        compose.onNodeWithTag("snooze-add-amount").performTextInput("400")
        compose.onNodeWithTag("snooze-add-unit-days").performClick()
        compose.onNodeWithTag("snooze-add-confirm").performClick()
        compose.waitFor { compose.textCount("Snooze for at most 365 days") == 1 }
        compose.onNodeWithTag("snooze-add-amount").performTextClearance()
        compose.onNodeWithTag("snooze-add-amount").performTextInput("0")
        compose.onNodeWithTag("snooze-add-confirm").performClick()
        compose.waitFor { compose.textCount("Snooze for at least 1 minute") == 1 }
        compose.onNodeWithTag("snooze-add-cancel").performClick()
        compose.waitFor { compose.tagCount("snooze-add-dialog") == 0 }
        assertEquals(null, order())
    }

    @Test
    fun addIsHiddenOnceTheListIsFull() {
        alarms.put(SettingKeys.SnoozePresets, "5_min,30_min,1_hour,tomorrow_morning,m:10,m:15,m:20")
        show()
        openAddDialog()
        compose.onNodeWithTag("snooze-add-amount").performTextInput("25")
        compose.onNodeWithTag("snooze-add-confirm").performClick()
        compose.waitFor { order() == "5_min,30_min,1_hour,tomorrow_morning,m:10,m:15,m:20,m:25" }
        compose.waitFor { compose.tagCount("snooze-add") == 0 && compose.tagCount("snooze-full") == 1 }
        compose.waitFor { compose.textCount("You have the most snooze options allowed (8). Remove one to add another.") == 1 }
    }
}
