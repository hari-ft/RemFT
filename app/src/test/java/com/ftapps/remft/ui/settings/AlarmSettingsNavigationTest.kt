package com.ftapps.remft.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.shell.AppShell
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class AlarmSettingsNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val alarms = TestAlarms()

    @After
    fun tearDown() {
        alarms.close()
    }

    @Test
    fun settingsIndexOpensTheAlarmScreens() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(
                    LocalTasksEnvironment provides alarms.tasks.environment,
                    LocalAlarmEnvironment provides alarms.env,
                ) { AppShell() }
            }
        }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-notifications").performClick()
        compose.onNodeWithTag("alarm-status").assertIsDisplayed()
        compose.onNodeWithTag("settings-reminder-system").performClick()
        compose.waitFor { compose.tagCount("reminder-preset-standard") == 1 }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings-alarm-log").performClick()
        compose.waitFor { compose.tagCount("alarm-log-empty") == 1 }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings-permissions").performClick()
        compose.waitFor { compose.tagCount("permission-ALARM_VOLUME") == 1 }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings-app-log").performClick()
        compose.waitFor { compose.tagCount("app-log-alarm-log") == 1 }
        compose.onNodeWithTag("app-log-alarm-log").performClick()
        compose.waitFor { compose.tagCount("alarm-log-empty") == 1 }
    }
}
