package com.ftapps.remft.ui.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.TestAlarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class NotificationsSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private var alarms: TestAlarms? = null

    @After
    fun tearDown() {
        alarms?.close()
    }

    private fun show(health: FakeHealth = FakeHealth(), dark: Boolean = false, opened: MutableList<String> = mutableListOf()): TestAlarms {
        val created = TestAlarms(health = health)
        alarms = created
        compose.showAlarmScreen(created, dark) {
            NotificationsSettingsScreen(onBack = {}, onOpenReminderSystem = { opened += "reminders" }, onOpenAlarmLog = { opened += "log" })
        }
        return created
    }

    @Test
    fun healthyDeviceSaysReady() {
        show()
        compose.waitFor { compose.textCount("Ready — alarms are allowed to ring on time.") == 1 }
        assertEquals(1, compose.textCount("Arms a real urgent alarm 15 seconds from now", substring = true))
    }

    @Test
    fun worstProblemIsShownAndTapOpensItsFix() {
        val health = FakeHealth(FakeHealth.AllAllowed.copy(exactAlarms = false, fullScreen = false))
        val created = show(health, dark = true)
        compose.waitFor { compose.textCount("Alarms may arrive late", substring = true) == 1 }
        compose.onNodeWithTag("alarm-status").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PermissionKind.EXACT_ALARMS), created.health.opened)
    }

    @Test
    fun notificationsOffBeatsEverythingElse() {
        show(FakeHealth(FakeHealth.AllAllowed.copy(notifications = false, alarmVolumeOff = true)))
        compose.waitFor { compose.textCount("Notifications are off", substring = true) == 1 }
    }

    @Test
    fun privacySwitchPersists() {
        val created = show()
        compose.waitFor { compose.tagCount("notification-privacy") == 1 }
        compose.onNodeWithTag("notification-privacy").assertIsOff()
        compose.onNodeWithTag("notification-privacy").performClick()
        compose.waitFor { created.setting(SettingKeys.NotificationPrivacy) == "true" }
        compose.waitFor { runCatching { compose.onNodeWithTag("notification-privacy").assertIsOn() }.isSuccess }
        compose.onNodeWithTag("notification-privacy").performClick()
        compose.waitFor { created.setting(SettingKeys.NotificationPrivacy) == "false" }
    }

    @Test
    fun rowsOpenReminderSystemAndAlarmLog() {
        val opened = mutableListOf<String>()
        show(opened = opened)
        compose.onNodeWithTag("settings-reminder-system").performClick()
        compose.onNodeWithTag("settings-alarm-log").performClick()
        assertTrue(opened == listOf("reminders", "log"))
    }
}
