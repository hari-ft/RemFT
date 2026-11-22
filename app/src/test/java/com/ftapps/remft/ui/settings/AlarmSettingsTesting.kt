package com.ftapps.remft.ui.settings

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.ftapps.remft.domain.settings.SettingKey
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

fun ComposeContentTestRule.showAlarmScreen(alarms: TestAlarms, dark: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        RemftTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalAlarmEnvironment provides alarms.env) { content() }
        }
    }
}

fun ComposeContentTestRule.waitFor(condition: () -> Boolean) = waitUntil(10_000) {
    shadowOf(Looper.getMainLooper()).idle()
    condition()
}

fun ComposeContentTestRule.textCount(text: String, substring: Boolean = false): Int =
    onAllNodesWithText(text, substring = substring, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().size

fun ComposeContentTestRule.tagCount(tag: String): Int =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

fun TestAlarms.setting(key: SettingKey): String? = runBlocking { env.settings.get(key.name) }

fun TestAlarms.put(key: SettingKey, value: String) {
    runBlocking { env.settings.set(key.name, value) }
}
